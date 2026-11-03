package uk.wakbijok.belanja.llm

import java.util.Calendar
import uk.wakbijok.belanja.data.Cycle
import uk.wakbijok.belanja.data.Txn
import uk.wakbijok.belanja.parser.Kedai

/**
 * What Murai notices. Every signal is computed from the ledger — the
 * timestamps are the time series, the earlier cycles are the baseline — and
 * handed to the model as a fact it may only word, never invent. No learning
 * step: "your usual" is an aggregate over your own history, recomputed on
 * demand, so every claim is auditable ("above 75% of your purchases, RM65").
 *
 * Calibrated on the soak ledger: most same-minute clusters were bill-paying
 * sessions (TNB + Air Selangor + CelcomDigi on payday) and toll sequences —
 * responsible, not impulsive — so burst and late-night look only at
 * discretionary categories. Bills, fuel, tolls and Pindahan never trip them.
 */
object Habits {

    /** Lower priority number = said first. */
    data class Signal(val id: String, val fact: String, val priority: Int)

    val DISCRETIONARY = setOf("Makan", "Online", "Family", "Lain-lain", "Grocery")

    const val BURST_WINDOW_MS = 30 * 60 * 1000L
    private const val DAY_MS = 86_400_000L
    private const val BASELINE_DAYS = 90L
    private const val BASELINE_CYCLES = 3

    fun signals(
        all: List<Txn>,
        latest: Txn,
        now: Long,
        cycleStartDay: Int,
    ): List<Signal> {
        val spend = BrainFacts.spend(all)
        val (cycleStart, cycleEnd) = Cycle.range(0, cycleStartDay, now)
        val cycleKey = cycleStart.toString()
        val thisCycle = spend.filter { it.capturedAt in cycleStart until cycleEnd }
        val elapsedDays = ((Cycle.startOfDay(now) - cycleStart) / DAY_MS + 1).toInt()
        val cycleDays = ((cycleEnd - cycleStart) / DAY_MS).toInt()
        val out = mutableListOf<Signal>()

        burst(spend, latest, now)?.let(out::add)
        lateNight(latest)?.let(out::add)
        streak(spend, latest, now)?.let(out::add)
        drift(spend, thisCycle, latest, now, cycleStartDay, cycleKey, elapsedDays, cycleDays)?.let(out::add)
        pace(spend, thisCycle, now, cycleStartDay, cycleKey, elapsedDays)?.let(out::add)
        repeat(thisCycle, latest, cycleKey)?.let(out::add)
        return out.sortedBy { it.priority }
    }

    /** Statement imports sit at noon of their day: only live captures carry a real clock. */
    private fun live(t: Txn) = t.sourcePkg != "statement"

    private fun discretionary(t: Txn) = t.category in DISCRETIONARY

    /** 75th percentile of your own purchases over the last 90 days. */
    fun personalP75(spend: List<Txn>, now: Long): Long {
        val amounts = spend
            .filter { it.capturedAt >= now - BASELINE_DAYS * DAY_MS }
            .mapNotNull { it.amountCents }
            .sorted()
        if (amounts.size < 10) return Long.MAX_VALUE
        return amounts[(amounts.size * 3) / 4]
    }

    /**
     * Impulse chaining: one tap lowers the resistance to the next. Two or
     * more discretionary purchases inside half an hour whose total beats
     * your usual purchase, or three regardless of size.
     */
    private fun burst(spend: List<Txn>, latest: Txn, now: Long): Signal? {
        if (!live(latest) || !discretionary(latest)) return null
        val window = spend.filter {
            live(it) && discretionary(it) &&
                it.capturedAt in (latest.capturedAt - BURST_WINDOW_MS)..latest.capturedAt
        }
        val total = window.sumOf { it.amountCents ?: 0 }
        val big = total >= personalP75(spend, now)
        if (window.size < 3 && !(window.size >= 2 && big)) return null
        val minutes = ((latest.capturedAt - window.minOf { it.capturedAt }) / 60_000L).coerceAtLeast(1)
        val bucket = latest.capturedAt / BURST_WINDOW_MS
        val kedai = window.mapNotNull { it.merchant }.distinct().take(3).joinToString(", ")
        return Signal(
            id = "burst@$bucket",
            fact = "Corak: ${window.size} belian dalam $minutes minit, jumlah ${BrainFacts.rm(total)}" +
                (if (kedai.isNotBlank()) " ($kedai)" else "") +
                (if (big) " — lebih besar daripada 75% belian kau selalunya." else "."),
            priority = 1,
        )
    }

    /** A discretionary purchase between 23:00 and 05:00 that is more than pocket change. */
    private fun lateNight(latest: Txn): Signal? {
        if (!live(latest) || !discretionary(latest) || (latest.amountCents ?: 0) < 2000) return null
        val cal = Calendar.getInstance().apply { timeInMillis = latest.capturedAt }
        val hour = cal.get(Calendar.HOUR_OF_DAY)
        if (hour in 5..22) return null
        val clock = "%02d:%02d".format(hour, cal.get(Calendar.MINUTE))
        return Signal(
            id = "late@${dayKey(latest.capturedAt)}",
            fact = "Corak: belian ni dibuat jam $clock malam.",
            priority = 2,
        )
    }

    /** Same kedai on three or more consecutive days, ending today. */
    private fun streak(spend: List<Txn>, latest: Txn, now: Long): Signal? {
        val fp = Kedai.fingerprint(latest.merchant) ?: return null
        val days = spend.filter { Kedai.fingerprint(it.merchant) == fp }
            .map { Cycle.startOfDay(it.capturedAt) }.toSet()
        var day = Cycle.startOfDay(now)
        var run = 0
        while (day in days) { run++; day -= DAY_MS }
        if (run < 3) return null
        return Signal(
            id = "streak:$fp@${dayKey(now)}",
            fact = "Corak: ${latest.merchant} $run hari berturut-turut.",
            priority = 3,
        )
    }

    /** Average of the same measure over the previous cycles that have data. */
    private fun baseline(
        spend: List<Txn>,
        now: Long,
        cycleStartDay: Int,
        measure: (List<Txn>) -> Long,
    ): Long? {
        val values = (1..BASELINE_CYCLES).mapNotNull { back ->
            val (s, e) = Cycle.range(-back, cycleStartDay, now)
            val rows = spend.filter { it.capturedAt in s until e }
            if (rows.isEmpty()) null else measure(rows)
        }
        if (values.size < 2) return null
        return values.sum() / values.size
    }

    /**
     * One category running well ahead of its own history: this cycle's
     * total projected to a full cycle vs the average of the previous ones.
     */
    private fun drift(
        spend: List<Txn>, thisCycle: List<Txn>, latest: Txn, now: Long,
        cycleStartDay: Int, cycleKey: String, elapsedDays: Int, cycleDays: Int,
    ): Signal? {
        val cat = latest.category
        if (cat == "Pindahan" || elapsedDays < 5) return null
        val soFar = thisCycle.filter { it.category == cat }.sumOf { it.amountCents ?: 0 }
        val projected = soFar * cycleDays / elapsedDays
        val usual = baseline(spend, now, cycleStartDay) { rows ->
            rows.filter { it.category == cat }.sumOf { it.amountCents ?: 0 }
        } ?: return null
        if (usual < 5_000 || projected < usual * 14 / 10) return null
        val band = (projected * 5 / usual).toInt() // steps of 20%
        return Signal(
            id = "drift:$cat@$cycleKey:$band",
            fact = "Corak: $cat dah ${BrainFacts.rm(soFar)} setakat hari ke-$elapsedDays; " +
                "pada kadar ni jadi ${BrainFacts.rm(projected)} sekitaran, biasanya ${BrainFacts.rm(usual)}.",
            priority = 4,
        )
    }

    /** Whole-cycle burn rate vs the previous cycles', once a week in. */
    private fun pace(
        spend: List<Txn>, thisCycle: List<Txn>, now: Long,
        cycleStartDay: Int, cycleKey: String, elapsedDays: Int,
    ): Signal? {
        if (elapsedDays < 7) return null
        val perDay = thisCycle.sumOf { it.amountCents ?: 0 } / elapsedDays
        val usualPerDay = baseline(spend, now, cycleStartDay) { rows ->
            val s = rows.minOf { it.capturedAt }
            val e = rows.maxOf { it.capturedAt }
            val days = ((e - s) / DAY_MS + 1).coerceAtLeast(20)
            rows.sumOf { it.amountCents ?: 0 } / days
        } ?: return null
        if (usualPerDay <= 0 || perDay < usualPerDay * 13 / 10) return null
        val pct = (perDay - usualPerDay) * 100 / usualPerDay
        return Signal(
            id = "pace@$cycleKey:w${elapsedDays / 7}",
            fact = "Corak: kadar belanja kitaran ni ${BrainFacts.rm(perDay)} sehari, " +
                "biasanya ${BrainFacts.rm(usualPerDay)} — $pct% lebih laju.",
            priority = 5,
        )
    }

    /** The same discretionary kedai four times or more this cycle; escalates at 4, 6, 8… */
    private fun repeat(thisCycle: List<Txn>, latest: Txn, cycleKey: String): Signal? {
        if (!discretionary(latest)) return null
        val fp = Kedai.fingerprint(latest.merchant) ?: return null
        val n = thisCycle.count { Kedai.fingerprint(it.merchant) == fp }
        if (n < 4) return null
        return Signal(
            id = "repeat:$fp@$cycleKey:${n / 2 * 2}",
            fact = "Corak: ${latest.merchant} sudah $n kali kitaran ini.",
            priority = 6,
        )
    }

    private fun dayKey(ms: Long): String = (Cycle.startOfDay(ms) / DAY_MS).toString()
}

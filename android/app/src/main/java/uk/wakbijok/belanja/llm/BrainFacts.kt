package uk.wakbijok.belanja.llm

import java.util.Calendar
import uk.wakbijok.belanja.data.Cycle
import uk.wakbijok.belanja.data.Txn

/**
 * Every number the brain is ever given comes from here — computed from the
 * ledger, deterministically. The brain words the facts; it never produces
 * them. A story you can't audit is just a feeling, and an invented figure in
 * a money app is worse than no feature at all.
 */
object BrainFacts {

    /** Spending rows only — Pindahan is movement between own accounts. */
    fun spend(txns: List<Txn>): List<Txn> =
        txns.filter { it.category != "Pindahan" && it.amountCents != null }

    fun totalCents(txns: List<Txn>): Long = spend(txns).sumOf { it.amountCents ?: 0 }

    fun byCategory(txns: List<Txn>): Map<String, Long> =
        spend(txns).groupBy { it.category }.mapValues { (_, v) -> v.sumOf { it.amountCents ?: 0 } }

    /**
     * [start, end) of the budget cycle at [offset] from the current one
     * (0 = this cycle). [startDay] is the user's payday; 1 = calendar month.
     */
    fun monthRange(
        offset: Int,
        now: Long = System.currentTimeMillis(),
        startDay: Int = 1,
    ): Pair<Long, Long> = Cycle.range(offset, startDay, now)

    fun monthKey(now: Long = System.currentTimeMillis()): String {
        val cal = Calendar.getInstance().apply { timeInMillis = now }
        return "%04d-%02d".format(cal.get(Calendar.YEAR), cal.get(Calendar.MONTH) + 1)
    }

    /** Days left in the current cycle, including today. */
    fun daysLeft(now: Long = System.currentTimeMillis(), startDay: Int = 1): Int =
        Cycle.daysLeft(startDay, now)

    /**
     * What the same remaining stretch of days cost last cycle. History beats a
     * daily average of the current cycle: cycle-ends have their own shape
     * (groceries, fuel, the 28th statement) that an average flattens away.
     */
    fun lastMonthTailCents(
        all: List<Txn>,
        now: Long = System.currentTimeMillis(),
        startDay: Int = 1,
    ): Long {
        val (curStart, _) = Cycle.range(0, startDay, now)
        val (lmStart, lmEnd) = Cycle.range(-1, startDay, now)
        // Same number of elapsed days into the previous cycle, from that
        // point to its end. A stretch the shorter cycle never reached
        // (Feb) simply yields an empty window, exactly as before.
        val from = lmStart + (Cycle.startOfDay(now) - curStart)
        return totalCents(all.filter { it.capturedAt in from until lmEnd })
    }

    fun rm(cents: Long): String = "RM%,.2f".format(cents / 100.0)
}

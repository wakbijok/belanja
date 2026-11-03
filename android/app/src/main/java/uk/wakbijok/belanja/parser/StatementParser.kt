package uk.wakbijok.belanja.parser

import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.encryption.InvalidPasswordException
import com.tom_roush.pdfbox.text.PDFTextStripper
import java.time.LocalDate
import kotlin.math.abs
import uk.wakbijok.belanja.data.Txn

/**
 * On-device Maybank credit-card statement parser + reconciliation matcher —
 * a faithful port of server/app/statements.py, used in SOLO mode.
 *
 * Line format (PDF text extraction of a Maybank Islamic card statement):
 *
 *     29/05 28/05 KEDAI CONTOH SP KUALA LUMPUR         MY 10.00
 *     29/05 29/05 PYMT@MAYBANK2U.COM 1,000.00CR
 *     29/05 29/05 RTL PROFIT CHRG RATE 18.00%          <- no amount: skipped
 *
 * Rows carry no year; it is derived from the statement date ("28 JUN 26") —
 * a row month greater than the statement month belongs to the previous year.
 */
object StatementParser {

    // English AND Malay month abbreviations. Longest-first in the regex so
    // OGOS wins over OGO.
    private val MONTHS = mapOf(
        "JAN" to 1, "FEB" to 2, "MAR" to 3, "MAC" to 3, "APR" to 4, "MAY" to 5,
        "MEI" to 5, "JUN" to 6, "JUL" to 7, "AUG" to 8, "OGOS" to 8, "OGO" to 8,
        "SEP" to 9, "OCT" to 10, "OKT" to 10, "NOV" to 11, "DEC" to 12, "DIS" to 12,
    )

    private val stmtDateRe = Regex(
        "\\b(\\d{1,2})\\s+(" +
            MONTHS.keys.sortedByDescending { it.length }.joinToString("|") +
            ")\\s+(\\d{2})\\b"
    )
    private val txnLineRe = Regex("^\\s*(\\d{2}/\\d{2})\\s+(\\d{2}/\\d{2})\\s+(.+)$")
    private val amountTailRe = Regex("\\s([\\d,]+\\.\\d{2})(CR)?$")
    private val countryTailRe = Regex("\\s[A-Z]{2}$")

    // Posting lags the purchase by a few days; allow slack on both sides.
    private const val MATCH_WINDOW_BEFORE_DAYS = 1L
    private const val MATCH_WINDOW_AFTER_DAYS = 4L

    // A merchant-backed match may reach further: petrol pumps settle (and
    // Samsung Wallet announces) 5–8 days after the fill. Amount alone never
    // gets this far — RM40 collides with everything.
    private const val STRONG_WINDOW_AFTER_DAYS = 10L

    enum class Fail { LOCKED_PDF, UNREADABLE_PDF, NOT_A_STATEMENT }

    data class Row(
        val postingDate: String,
        val txnDate: String,
        val description: String,
        val amountCents: Long,
        val credit: Boolean,
        var status: String = "",
        var matchedTxnId: String? = null,
    )

    sealed interface Result {
        data class Ok(val statementDate: LocalDate, val rows: List<Row>) : Result
        data class Err(val reason: Fail) : Result
    }

    private fun rowDate(ddMm: String, stmt: LocalDate): LocalDate {
        val (day, mon) = ddMm.split("/").map { it.toInt() }
        val year = if (mon > stmt.monthValue) stmt.year - 1 else stmt.year
        return LocalDate.of(year, mon, day)
    }

    fun parseText(text: String): Result {
        val m = stmtDateRe.find(text)
            ?: return Result.Err(Fail.NOT_A_STATEMENT)
        val stmtDate = LocalDate.of(
            2000 + m.groupValues[3].toInt(),
            MONTHS.getValue(m.groupValues[2]),
            m.groupValues[1].toInt(),
        )
        val rows = mutableListOf<Row>()
        for (line in text.lineSequence()) {
            val tm = txnLineRe.find(line) ?: continue
            val rest = tm.groupValues[3].trimEnd()
            // Note line (profit rate etc.) — no amount at the tail: skip.
            val am = amountTailRe.find(rest) ?: continue
            var desc = rest.substring(0, am.range.first).trim()
            desc = countryTailRe.replace(desc, "")
            desc = desc.replace(Regex("\\s+"), " ").trim()
            rows.add(
                Row(
                    postingDate = rowDate(tm.groupValues[1], stmtDate).toString(),
                    txnDate = rowDate(tm.groupValues[2], stmtDate).toString(),
                    description = desc,
                    amountCents = Math.round(
                        am.groupValues[1].replace(",", "").toDouble() * 100
                    ),
                    credit = am.groupValues[2] == "CR",
                )
            )
        }
        if (rows.isEmpty()) return Result.Err(Fail.NOT_A_STATEMENT)
        return Result.Ok(stmtDate, rows)
    }

    /**
     * M2U-web statements decrypt with an empty password; emailed statements
     * carry a real one and cannot be opened here ([Fail.LOCKED_PDF]).
     */
    fun parsePdf(blob: ByteArray): Result {
        val doc = try {
            PDDocument.load(blob, "")
        } catch (e: InvalidPasswordException) {
            return Result.Err(Fail.LOCKED_PDF)
        } catch (t: Throwable) {
            return Result.Err(Fail.UNREADABLE_PDF)
        }
        return try {
            doc.use {
                if (it.isEncrypted) it.setAllSecurityToBeRemoved(true)
                val stripper = PDFTextStripper().apply { sortByPosition = true }
                parseText(stripper.getText(it))
            }
        } catch (t: Throwable) {
            Result.Err(Fail.UNREADABLE_PDF)
        }
    }

    /** Local midnight of [dayIso] as UTC epoch-ms. */
    fun dayStartMs(dayIso: String, tzOffsetMinutes: Int): Long =
        LocalDate.parse(dayIso).toEpochDay() * 86_400_000L - tzOffsetMinutes * 60_000L

    /** Exclusive end of the local day [dayIso] — the cycle's last instant. */
    fun dayEndMs(dayIso: String, tzOffsetMinutes: Int): Long =
        dayStartMs(dayIso, tzOffsetMinutes) + 86_400_000L

    /**
     * [start, end) epoch-ms window a statement row can match: the local txn
     * day minus 1 day through plus [afterDays] (posting lag both ways).
     */
    fun dayWindowMs(
        dayIso: String,
        tzOffsetMinutes: Int,
        afterDays: Long = MATCH_WINDOW_AFTER_DAYS,
    ): Pair<Long, Long> {
        val d = LocalDate.parse(dayIso)
        val offMs = tzOffsetMinutes * 60_000L
        val start = d.minusDays(MATCH_WINDOW_BEFORE_DAYS)
        val end = d.plusDays(afterDays + 1)
        // local midnight → UTC ms
        fun toMs(x: LocalDate): Long = x.toEpochDay() * 86_400_000L - offMs
        return toMs(start) to toMs(end)
    }

    /**
     * Merchant identity tokens. Place and instrument words are dropped: two
     * Kuala Lumpur descriptors agreeing on "KUALA" is not evidence they are
     * the same kedai.
     */
    fun tokens(s: String): Set<String> =
        s.uppercase().split(Regex("[^A-Z0-9]+"))
            .filter { it.length >= 4 && it !in Categorizer.PLACE_TOKENS }
            .toSet()

    /**
     * Mutates each debit row: status = matched|missing (+ matchedTxnId).
     * Credits (payments, rebates) get status = credit. A captured txn is used
     * at most once.
     *
     * Two passes, strongest evidence first. Pass 1 takes amount + merchant
     * agreement inside the wide window; pass 2 takes amount alone inside the
     * tight window. One greedy pass on amount let a RM20 kedai capture
     * satisfy a RM20 PSS row whose own capture landed a day past the window,
     * and the displaced PSS capture then satisfied the kedai row — a
     * swapped pair that both showed green (August 2026).
     *
     * Only CARD captures can appear on a card statement, so nothing else is
     * a candidate; statement-imported rows count as CARD so a re-upload comes
     * back matched instead of duplicated.
     */
    fun matchRows(rows: List<Row>, txns: List<Txn>, tzOffsetMinutes: Int) {
        val used = mutableSetOf<String>()
        val pool = txns.filter { it.amountCents != null && Instrument.of(it) == Instrument.CARD }
        for (row in rows) {
            row.status = if (row.credit) "credit" else ""
            row.matchedTxnId = null
        }
        val debits = rows.filter { !it.credit }

        fun take(row: Row, txn: Txn) {
            row.status = "matched"
            row.matchedTxnId = txn.id
            used.add(txn.id)
        }

        fun free(row: Row, t: Txn, lo: Long, hi: Long) =
            t.id !in used && t.amountCents == row.amountCents && t.capturedAt in lo until hi

        // Pass 1: amount + merchant tokens, wide window; most overlap wins,
        // then the capture nearest the row's day.
        for (row in debits) {
            val (lo, hi) = dayWindowMs(row.txnDate, tzOffsetMinutes, STRONG_WINDOW_AFTER_DAYS)
            val rowToks = tokens(row.description)
            if (rowToks.isEmpty()) continue
            val at = dayStartMs(row.txnDate, tzOffsetMinutes)
            val best = pool.asSequence()
                .filter { free(row, it, lo, hi) }
                .map { it to (tokens(it.merchant.orEmpty()) intersect rowToks).size }
                .filter { it.second > 0 }
                .maxWithOrNull(compareBy({ it.second }, { -abs(it.first.capturedAt - at) }))
            if (best != null) take(row, best.first)
        }

        // Pass 2: amount alone, tight window, nearest in time.
        for (row in debits) {
            if (row.matchedTxnId != null) continue
            val (lo, hi) = dayWindowMs(row.txnDate, tzOffsetMinutes)
            val at = dayStartMs(row.txnDate, tzOffsetMinutes)
            val nearest = pool
                .filter { free(row, it, lo, hi) }
                .minByOrNull { abs(it.capturedAt - at) }
            if (nearest != null) take(row, nearest) else row.status = "missing"
        }
    }
}

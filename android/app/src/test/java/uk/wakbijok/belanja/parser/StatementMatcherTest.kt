package uk.wakbijok.belanja.parser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import uk.wakbijok.belanja.data.Txn

/**
 * Reconcile matcher cases shaped like the August 2026 statement — the first
 * full-month soak (merchants and amounts synthetic). Every shape here either cost a wrong "missing" or, worse,
 * a wrong "matched" that looked fine on screen.
 */
class StatementMatcherTest {

    private val tz = 480 // MYT

    private fun ms(dayIso: String, hour: Int = 12) =
        StatementParser.dayStartMs(dayIso, tz) + hour * 3_600_000L

    private fun spay(id: String, day: String, merchant: String, rm: String) = Txn(
        id = id, sourcePkg = "com.samsung.android.spay", amountCents = cents(rm),
        merchant = merchant, rawTitle = "Maybank MasterCard Plat",
        rawText = "$merchant RM $rm", capturedAt = ms(day), needsReview = false,
    )

    private fun maybankCard(id: String, day: String, merchant: String, rm: String) = Txn(
        id = id, sourcePkg = "com.maybank2u.life", amountCents = cents(rm),
        merchant = merchant, rawTitle = "Maybank2u: Card Transaction",
        rawText = "You've just spent RM $rm at $merchant with your MasterCard Platinum ending 1234.",
        capturedAt = ms(day), needsReview = false,
    )

    private fun tngToll(id: String, day: String, rm: String) = Txn(
        id = id, sourcePkg = "my.com.tngdigital.ewallet", amountCents = cents(rm),
        merchant = null, rawTitle = "Successful Toll Payment",
        rawText = "RM$rm has been deducted", capturedAt = ms(day), needsReview = false,
    )

    private fun imported(id: String, day: String, merchant: String, rm: String) = Txn(
        id = id, sourcePkg = "statement", amountCents = cents(rm), merchant = merchant,
        rawTitle = "", rawText = "", capturedAt = ms(day), needsReview = false,
    )

    private fun row(day: String, desc: String, rm: String, credit: Boolean = false) =
        StatementParser.Row(postingDate = day, txnDate = day, description = desc,
            amountCents = cents(rm), credit = credit)

    private fun cents(rm: String) = Math.round(rm.toDouble() * 100)

    @Test
    fun `merchant evidence beats amount-only greed - the swapped pair`() {
        val pss = row("2026-08-26", "PSS SUNGAI MAWAR UT SP SELANGOR", "20.00")
        val jen = row("2026-08-27", "KEDAI CONTOH ENTERPRISE SP SHAH ALAM", "20.00")
        val txns = listOf(
            spay("jen", "2026-08-27", "KEDAI CONTOH ENTERPRISE", "20.00"),
            spay("pss", "2026-08-31", "PSS SUNGAI MAWAR UTARA", "20.00"),
        )
        StatementParser.matchRows(listOf(pss, jen), txns, tz)
        assertEquals("pss", pss.matchedTxnId)
        assertEquals("jen", jen.matchedTxnId)
    }

    @Test
    fun `petrol settling 8 days late matches when the merchant agrees`() {
        val r = row("2026-07-31", "PSS LEBUHRAYA SILK SP SELANGOR", "40.00")
        StatementParser.matchRows(listOf(r), listOf(spay("p", "2026-08-08", "PSS LEBUHRAYA SILK", "40.00")), tz)
        assertEquals("matched", r.status)
        assertEquals("p", r.matchedTxnId)
    }

    @Test
    fun `8 days late without merchant agreement stays missing`() {
        val r = row("2026-07-31", "PSS LEBUHRAYA SILK SP SELANGOR", "40.00")
        StatementParser.matchRows(listOf(r), listOf(spay("x", "2026-08-08", "KEDAI LAIN", "40.00")), tz)
        assertEquals("missing", r.status)
        assertNull(r.matchedTxnId)
    }

    @Test
    fun `place tokens are not merchant agreement`() {
        val r = row("2026-08-19", "RVM*RESTORAN CONTOH KUALA LUMPUR", "30.00")
        val far = maybankCard("t", "2026-08-26", "TNG-EWALLET ECOM 2-EC KUALA LUMPUR MYS", "30.00")
        StatementParser.matchRows(listOf(r), listOf(far), tz)
        assertEquals("missing", r.status)
    }

    @Test
    fun `amount alone still matches inside the tight window`() {
        val r = row("2026-08-04", "SOME KEDAI SP SHAH ALAM", "12.34")
        StatementParser.matchRows(listOf(r), listOf(spay("s", "2026-08-06", "UNRELATED NAME", "12.34")), tz)
        assertEquals("s", r.matchedTxnId)
    }

    @Test
    fun `amount alone prefers the nearest capture`() {
        val r = row("2026-08-04", "SOME KEDAI SP SHAH ALAM", "12.34")
        val txns = listOf(
            spay("late", "2026-08-08", "ONE", "12.34"),
            spay("near", "2026-08-05", "TWO", "12.34"),
        )
        StatementParser.matchRows(listOf(r), txns, tz)
        assertEquals("near", r.matchedTxnId)
    }

    @Test
    fun `wallet balance and account money are never candidates`() {
        val r = row("2026-08-10", "LDP TOL CONTOH PUCHONG", "2.10")
        StatementParser.matchRows(listOf(r), listOf(tngToll("toll", "2026-08-10", "2.10")), tz)
        assertEquals("missing", r.status)
    }

    @Test
    fun `re-upload matches its own earlier imports`() {
        val r = row("2026-07-01", "MY TNB-EC KUALA LUMPUR", "300.00")
        StatementParser.matchRows(listOf(r), listOf(imported("i", "2026-07-01", "MY TNB-EC KUALA LUMPUR", "300.00")), tz)
        assertEquals("i", r.matchedTxnId)
    }

    @Test
    fun `credits are set aside and a capture is used once`() {
        val pay = row("2026-07-29", "PYMT@MAYBANK2U.COM", "1000.00", credit = true)
        val a = row("2026-08-04", "GRAB-EC PETALING JAYA", "25.00")
        val b = row("2026-08-05", "GRAB-EC PETALING JAYA", "25.00")
        val txns = listOf(maybankCard("g", "2026-08-04", "GRAB-EC PETALING JAYA MYS", "25.00"))
        StatementParser.matchRows(listOf(pay, a, b), txns, tz)
        assertEquals("credit", pay.status)
        assertEquals("g", a.matchedTxnId)
        assertEquals("missing", b.status)
    }
}

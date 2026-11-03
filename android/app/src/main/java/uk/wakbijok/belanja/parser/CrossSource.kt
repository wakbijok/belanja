package uk.wakbijok.belanja.parser

import kotlin.math.abs
import uk.wakbijok.belanja.data.Txn

/**
 * One purchase, several announcers: Samsung Wallet fires at the tap, the
 * issuing bank minutes later, and a merchant app (Grab, TNG auto-debit) on
 * its own clock. The August 2026 statement showed the old 5-minute rule was
 * too short — the bank trailed the wallet by 14 minutes for one kedai, and
 * the card alert for a TNG-funded Google charge came 7 hours later.
 *
 * Pure decision, kept out of the service so the shapes above stay testable.
 */
object CrossSource {
    /** Inside this gap two announcers of one amount are the same money, full stop. */
    const val TIGHT_WINDOW_MS = 5 * 60 * 1000L

    /** Up to here they still may be — but only with corroboration (below). */
    const val WIDE_WINDOW_MS = 12 * 60 * 60 * 1000L

    /**
     * Is [existing] the same money as an incoming capture of the same amount?
     *
     * Beyond the tight window: never merge account money (a RM40 DuitNow and a
     * RM40 pump on the same day are two spends), require a card on at least
     * one side, and require the merchants to agree unless one announcer names
     * none (the Grab app says only "your MasterCard has been charged").
     */
    fun sameMoney(
        existing: Txn,
        pkg: String,
        merchant: String?,
        rawTitle: String,
        rawText: String,
        postedAt: Long,
    ): Boolean {
        if (existing.sourcePkg == pkg) return false
        if (existing.sourcePkg == Instrument.STATEMENT_SOURCE) return false
        val gap = abs(existing.capturedAt - postedAt)
        if (gap <= TIGHT_WINDOW_MS) return true
        if (gap > WIDE_WINDOW_MS) return false

        val a = Instrument.of(existing)
        val b = Instrument.of(pkg, rawTitle, rawText)
        if (a == Instrument.ACCOUNT || b == Instrument.ACCOUNT) return false
        if (a != Instrument.CARD && b != Instrument.CARD) return false

        val ma = existing.merchant
        if (ma.isNullOrBlank() || merchant.isNullOrBlank()) return true
        val ta = StatementParser.tokens(ma)
        val tb = StatementParser.tokens(merchant)
        if (ta.isNotEmpty() && tb.isNotEmpty()) return (ta intersect tb).isNotEmpty()
        // "KFC-TAMAN 15 KUALA LUMPUR" is all short or place words — named,
        // just not distinctively. Compare it raw rather than treat it as absent.
        return (relaxed(ma) intersect relaxed(merchant)).isNotEmpty()
    }

    private fun relaxed(s: String): Set<String> =
        s.uppercase().split(Regex("[^A-Z0-9]+")).filter { it.length >= 3 }.toSet()
}

package uk.wakbijok.belanja.parser

import uk.wakbijok.belanja.data.Txn

/**
 * Which pot of money a capture drew from. A card statement can only ever
 * confirm CARD captures, so the reconcile matcher and the "belum jelas" list
 * must ignore the rest — the August 2026 list was 50 long and 46 of them were
 * TNG tolls, DuitNow transfers and a loan instalment that no card statement
 * will ever carry.
 *
 * Derived, never stored: the notification title/text already says it, and a
 * rule change must apply to history without a migration.
 */
enum class Instrument {
    /** A card charge, announced by the issuer, the tokenising wallet, or the
     *  merchant app ("Your MasterCard 1234 has been charged"). */
    CARD,

    /** Bank-account or wallet-balance money: transfers, DuitNow, bill payments
     *  from the account, ATM, tolls paid from the TNG balance. */
    ACCOUNT,

    /** App-level announcer whose funding source is not stated (TNG auto-debit,
     *  Shopee, anything unrecognised). */
    WALLET;

    companion object {
        const val STATEMENT_SOURCE = "statement"

        private val cardText = Regex(
            "\\b(mastercard|master card|visa|credit card|debit card)\\b",
            RegexOption.IGNORE_CASE,
        )
        private val cardVerb = Regex("\\b(charged|spent|ending)\\b", RegexOption.IGNORE_CASE)
        // \bcard\b, not "card": "Cardless withdrawal" is account money.
        private val cardTitle = Regex("\\bcard\\b|mastercard|visa", RegexOption.IGNORE_CASE)
        private val balanceTitle = Regex("toll|transfer|duitnow|cash-?out", RegexOption.IGNORE_CASE)

        fun of(sourcePkg: String, rawTitle: String, rawText: String): Instrument {
            if (sourcePkg == STATEMENT_SOURCE) return CARD
            if (sourcePkg.contains("samsung.android.spay")) return CARD
            val saysCard = cardTitle.containsMatchIn(rawTitle) ||
                (cardText.containsMatchIn(rawText) && cardVerb.containsMatchIn(rawText))
            if (saysCard) return CARD
            return when {
                // Bank app, not a card alert: everything else it announces is
                // account money (Payments, Scan & Pay, ATM Cash-out).
                sourcePkg.contains("maybank") -> ACCOUNT
                balanceTitle.containsMatchIn(rawTitle) -> ACCOUNT
                else -> WALLET
            }
        }

        fun of(t: Txn): Instrument = of(t.sourcePkg, t.rawTitle, t.rawText)
    }
}

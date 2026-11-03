package uk.wakbijok.belanja.parser

/**
 * Rule-based notification parser.
 *
 * Real notification formats from Samsung Wallet / MAE / Maybank2u are NOT yet
 * known. Rules are data, not code: add/adjust [ParserRule] entries in
 * [defaultRules] once real samples are captured (unparsed "RM"-containing
 * notifications are stored raw with needsReview=true precisely so we can learn
 * the formats).
 */
enum class Direction { DEBIT, CREDIT }

data class ParserRule(
    /** Notification source package this rule applies to; "*" = any package. */
    val packageName: String,
    /** Regex with named groups `amount` (required) and optionally `merchant`. */
    val regex: Regex,
    val direction: Direction = Direction.DEBIT,
)

data class ParsedTxn(
    val amountCents: Long,
    val merchant: String?,
    val direction: Direction,
)

object TxnParser {

    /** Samsung Wallet writes MYR2.00; MAE/Maybank write RM 2.00. */
    private const val CUR = """(?:RM|MYR)"""
    private const val AMOUNT = """$CUR\s?(?<amount>[0-9,]+\.\d{2})"""

    /** Plausible placeholder patterns until real samples are captured. */
    val defaultRules: List<ParserRule> = listOf(
        // "... RM12.34 at MERCHANT ..."
        ParserRule("*", Regex("""$AMOUNT\s+(?:paid\s+)?at\s+(?<merchant>[^.,\n]+)""", RegexOption.IGNORE_CASE)),
        // "... RM12.34 to MERCHANT ..." (transfer / DuitNow)
        ParserRule("*", Regex("""$AMOUNT\s+(?:sent\s+|paid\s+|transferred\s+)?to\s+(?<merchant>[^.,\n]+)""", RegexOption.IGNORE_CASE)),
        // "Payment of RM12.34 ..." / "You('ve) spent RM12.34"
        ParserRule("*", Regex("""(?:payment|purchase|spent|charged)[^\n]*?$AMOUNT""", RegexOption.IGNORE_CASE)),
        // Samsung Wallet: "Maybank MasterCard Plat KEDAI CONTOH - TAMAN MYR2.00"
        // — card label first, merchant next, amount last with no verb at all.
        ParserRule(
            "com.samsung.android.spay",
            Regex("""(?<merchant>[^\n]+?)\s+$CUR\s?(?<amount>[0-9,]+\.\d{2})\s*$""",
                RegexOption.IGNORE_CASE),
        ),
        // Bare amount fallback — always last.
        ParserRule("*", Regex(AMOUNT, RegexOption.IGNORE_CASE)),
    )

    /**
     * Wallet apps push marketing as well as receipts, but a receipt must
     * always win: TNG writes "TNG Rewards points" on real payment
     * notifications and beta2 rejected them as ads (27-07-2026). Order
     * matters — payment verb + amount-with-sen is conclusive; only then do
     * the ad heuristics get a say.
     */
    private val PROMO_MARKERS = listOf(
        "promo", "diskaun", "discount", "voucher", "baucar", "percuma",
        "free ", "don't miss", "dont miss", "jangan lepas", "miss out",
        "today's the day", "claim your", "sale!", "jualan", "up to",
        "% off", "off!", "new!", "baharu!", "rebate up",
        // deliberately NOT here: reward/ganjaran/try/cuba/menanti —
        // they appear inside genuine payment notifications
    )

    private val AMOUNT_WITH_SEN = Regex("""(?:RM|MYR)\s?[0-9,]+\.\d{2}""", RegexOption.IGNORE_CASE)

    private val PAYMENT_VERBS = listOf(
        "spent", "paid", "payment", "purchase", "charged", "debited",
        "transaction", "successful", "berjaya", "dibayar", "pembayaran",
        "belanja", "dibelanjakan", "transfer", "dipindahkan", "sent to",
        "reload", "top up", "topup",
    )

    /** Reason a notification was accepted/rejected — surfaced in the diag log. */
    fun classify(text: String): String {
        val low = text.lowercase()
        val hasAmount = AMOUNT_WITH_SEN.containsMatchIn(text)
        val hasVerb = PAYMENT_VERBS.any { low.contains(it) }
        return when {
            hasVerb && hasAmount -> "ok:verb+amount"
            PROMO_MARKERS.any { low.contains(it) } -> "reject:promo"
            hasAmount -> "ok:amount"
            hasVerb -> "reject:verb without RMx.xx"
            else -> "reject:no amount"
        }
    }

    fun looksLikePayment(text: String): Boolean = !classify(text).startsWith("reject")

    fun parse(
        packageName: String,
        text: String,
        rules: List<ParserRule> = defaultRules,
    ): ParsedTxn? {
        for (rule in rules) {
            if (rule.packageName != "*" && rule.packageName != packageName) continue
            val m = rule.regex.find(text) ?: continue
            val amountStr = m.groups["amount"]?.value ?: continue
            val cents = toCents(amountStr) ?: continue
            val merchant = runCatching { m.groups["merchant"]?.value }
                .getOrNull()?.let(::cleanMerchant)?.takeIf { it.isNotEmpty() }
            return ParsedTxn(cents, merchant, rule.direction)
        }
        return null
    }

    /**
     * Maybank2u card notifications append "... with your MasterCard
     * Platinum ending 1234" after the merchant — instrument junk, not
     * identity. Left in, it poisons merchant keys, learned overrides, and
     * token propagation (the MASTERCARD incident, 26-07-2026).
     */
    private val MERCHANT_TAIL = Regex(
        """\s+(?:with your|using your|via)\s.*$""",
        RegexOption.IGNORE_CASE,
    )

    fun cleanMerchant(raw: String): String = raw.replace(MERCHANT_TAIL, "").trim()

    private fun toCents(s: String): Long? {
        val clean = s.replace(",", "")
        val parts = clean.split(".")
        if (parts.size != 2 || parts[1].length != 2) return null
        val whole = parts[0].toLongOrNull() ?: return null
        val frac = parts[1].toLongOrNull() ?: return null
        return whole * 100 + frac
    }
}

package uk.wakbijok.belanja.parser

/**
 * Rule-based merchant categorizer. Same philosophy as [TxnParser]: rules are
 * data, not code — add keywords to [defaultRules] as new merchants show up.
 *
 * Priority (applied by the caller): learned MerchantCategory override for the
 * normalized merchant key > keyword rule here > [DEFAULT_CATEGORY].
 */
data class CategoryRule(val keyword: String, val category: String)

object Categorizer {

    const val DEFAULT_CATEGORY = "Lain-lain"

    /** Display / iteration order for all known categories. */
    val CATEGORIES = listOf(
        "Grocery", "Makan", "Minyak", "Online", "Bills",
        "Transport", "Health", "Family", "Pindahan", DEFAULT_CATEGORY,
    )

    val defaultRules: List<CategoryRule> = buildList {
        fun cat(category: String, vararg keywords: String) =
            keywords.forEach { add(CategoryRule(it, category)) }

        cat(
            "Grocery",
            "LOTUS", "MYDIN", "AEON", "JAYA GROCER", "VILLAGE GROCER", "NSK",
            "ECONSAVE", "99 SPEEDMART", "KK MART", "7-ELEVEN",
            "FAMILYMART", "HEROMARKET", "PASARAYA",
        )
        cat(
            "Makan",
            "MCD", "MCDONALD", "KFC", "PIZZA", "SECRET RECIPE", "ZUS", "TEALIVE",
            "STARBUCKS", "RESTORAN", "KOPITIAM", "MAMAK", "FOODPANDA", "GRABFOOD",
            "MIXUE", "KENANGAN", "HADRAMAWT", "BAKERY", "BAKER", "COFFEE", "KOPI",
            "CBTL", "LUCKIN", "SUBWAY", "TENDERISTA", "SNACK", "CAFE", "RESTAURANT",
            // Generic Malay food words — statement descriptors often concatenate,
            // so plain substrings ("NASIKUKUS" contains NASI) are the right tool.
            "NASI", "AYAM", "MEE ", "BIHUN", "BURGER", "SATE", "SATAY", "CENDOL",
            "KUIH", "WARUNG", "TOMYAM", "KUEY", "ROTI", "SEAFOOD", "STEAMBOAT",
            "BISTRO", "DAPUR", "SELERA", "KUKUS", "GORENG", "CHICKEN",
        )
        // PSS = Petronas Service Station (tap-at-pump); no trailing space — the
        // descriptors sometimes concatenate words into one token.
        cat("Minyak", "PETRONAS", "SHELL", "PETRON", "CALTEX", "BHP", "SETEL", "PSS")
        cat("Online", "SHOPEE", "LAZADA", "TIKTOK", "TEMU", "AMAZON", "SPAYLATER")
        cat(
            "Bills",
            // AIR SELANGO, not SELANGOR: Maybank truncates the descriptor to
            // "PENGURUSAN AIR SELANGO" and the full word never fired.
            "TNB", "AIR SELANGO", "INDAH WATER", "UNIFI", "MAXIS", "CELCOM",
            "DIGI", "U MOBILE", "ASTRO", "NETFLIX", "SPOTIFY", "YOUTUBE",
            "GOOGLE", "APPLE.COM", "OPENAI", "HETZNER", "EVOXT", "CUCKOO",
            "FINANCING",
        )
        cat(
            "Transport",
            "GRAB", "TOUCH N GO", "TNG", "RAPIDKL", "KTM", "PLUS", "ANIH", "PARKIN",
            "TIMETEC",
            // Highway / toll operators; APSB_ outranks AEON on the length tiebreak
            // for descriptors like APSB_AM_AEON RAWANG.
            "APSB_", "APSB", "TOLL", "KESAS", "BESRAYA", "LEKAS", "LATAR", "AKLEH",
            "GRANDSAGA", "EXPRESSWAY", "SMARTTAG", "DUKE", "SUKE", "DASH", "SILK",
            "LDP", "PARKING", "BUSONLINE",
        )
        // Pindahan = funding-layer movements between own pockets (SPayLater
        // repayment, wallet reloads) — excluded from spend totals; the real
        // detail already exists at the wallet layer. No bare "RELOAD":
        // prepaid phone reloads are real spending.
        cat(
            "Pindahan",
            // NOT SPAYLATER: ShopeePay QR issues no notification, so the
            // statement's SPayLater line is the ONLY record of that spending —
            // excluding it would under-count, which is worse than double-count.
            "PYMT@", "TNG RELOAD", "TOUCH N GO RELOAD",
            "TNG EWALLET RELOAD", "GRABPAY TOPUP", "SHOPEEPAY TOP",
            "EWALLET RELOAD", "GOPAYZ RELOAD",
            // Credit-card settlement: every purchase inside it was already
            // captured individually — counting the settlement too is the
            // biggest double-count in the ledger (RM6.4k caught 29-07).
            "CREDIT CARD-RPS", "TO MAYBANK MASTER CARD",
            // Own-wallet funding observed live: MAE top-up from own account,
            // incoming DuitNow/IBG credit ("You've received ...").
            "TO YOUR MAE WALLET", "YOU'VE RECEIVED RM",
        )
        cat(
            "Health",
            "PHARMACY", "FARMASI", "GUARDIAN", "WATSONS", "CARING", "KLINIK",
            "CLINIC", "HOSPITAL", "SPECIALIST", "OPTICAL", "OPTOMETR", "DENTAL",
        )
        cat(
            "Family",
            "TOYS", "MR DIY", "KAISON", "POPULAR", "UNIQLO", "PADINI", "H&M",
            "ECOSHOP", "PUSTAKA",
        )
    }

    /**
     * Longest keyword wins so e.g. GRABFOOD (Makan) beats GRAB (Transport)
     * regardless of list order.
     */
    private val rulesByLength = defaultRules.sortedByDescending { it.keyword.length }

    /**
     * Normalized key used for learned merchant overrides: uppercased, trimmed,
     * whitespace collapsed. Falls back to rawTitle when merchant was not parsed.
     */
    fun merchantKey(merchant: String?, rawTitle: String): String =
        (merchant?.takeIf { it.isNotBlank() } ?: rawTitle)
            .trim().uppercase().replace(Regex("\\s+"), " ")

    /**
     * Location / generic-noise tokens that must never drive a learned-merchant
     * match — fixing one kedai must not recategorize every descriptor from the
     * same town.
     */
    val PLACE_TOKENS = setOf(
        "PUTRAJAYA", "SELANGOR", "KUALA", "LUMPUR", "BANGI", "KAJANG",
        "SEMENYIH", "RAWANG", "SEPANG", "CHERAS", "SEREMBAN", "JENJAROM",
        "LANGAT", "SUNGAI", "BULOH", "PETALING", "BANGSAR", "BANDAR",
        "JAYA", "SHAH", "ALAM", "SERI", "KEMBANGAN", "PUCHONG", "AMPANG",
        "DAMANSARA", "SETIA", "TAMAN", "PRESINT", "MALAYSIA", "DUITNOW",
        "PAYMENT", "ONLINE",
        // instrumen & saluran bayaran — bukan identiti kedai; MASTERCARD
        // pernah propagate ke SEMUA transaksi kad (insiden 26-07-2026)
        "MASTERCARD", "VISA", "MAYBANK", "MAYBANK2U", "IKHWAN", "PLATINUM",
        "ENDING", "CREDIT", "DEBIT", "TRANSACTION", "WALLET", "EWALLET",
        "SPAYLATER", "SHOPEEPAY", "GRABPAY",
    )

    /**
     * Longest distinctive token of a merchant key, used to propagate a learned
     * category across statement variants of the same kedai ("RESTORANCONTOH NASI
     * KUKUS" vs "RESTORANCONTOH NASIKUKUS SHAH ALAM"). Null when nothing qualifies.
     */
    fun distinctiveToken(merchantKey: String): String? = merchantKey
        .uppercase()
        .split(Regex("[^A-Z0-9]+"))
        .filter { it.length >= 5 && it !in PLACE_TOKENS }
        .maxByOrNull { it.length }

    /** Keyword-rule categorization over the merchant (preferred) or full text. */
    fun categorize(merchant: String?, fullText: String): String {
        val haystack = (merchant?.takeIf { it.isNotBlank() } ?: fullText).uppercase().trimStart()
        // Prefix exception: a descriptor STARTING with PSS is a Petronas
        // station even when its name carries a highway (PSS LEBUHRAYA SILK) —
        // otherwise the 4-char toll keywords outrank the 3-char PSS.
        if (haystack.startsWith("PSS")) return "Minyak"
        return rulesByLength.firstOrNull { haystack.contains(it.keyword) }?.category
            ?: DEFAULT_CATEGORY
    }

    /**
     * Lateral-transfer detection, layered strongest-first:
     *  1. Keyword rules (the "Pindahan" table) — universal bank phrasings.
     *  2. The owner's own name in the text — DuitNow/IBG to yourself.
     *  3. A registered account's last4 in the text, or its label in the
     *     merchant/title — but ONLY alongside a transfer verb, because
     *     ordinary spending notifications also name your own card.
     *
     * Labels are matched against merchant/title only, never the body: a MAE
     * QR payment's body says "via MAE" while its merchant is the shop — the
     * narrow match keeps real spending capturable.
     */
    /**
     * Normalises bank text quirks that break literal matching: curly
     * apostrophes ("You\u2019ve") and masking asterisks ("ending ***1234") —
     * both observed defeating keyword and last4 signals on 29-07.
     */
    private fun normalise(s: String): String =
        s.uppercase().replace('\u2019', '\'').replace("*", "")

    /**
     * Money coming IN. Belanja is a spending tracker, not a wallet: income is
     * out of scope entirely — too many shapes to track, a different product.
     * Inbound is therefore ignored UNCONDITIONALLY at the door (with a diag
     * line), regardless of sender or registry. "Fund masuk pun dia membebel"
     * was Murai roasting a RM2,500 credit as if it were spending.
     */
    fun isInbound(rawTitle: String, fullText: String): Boolean {
        val title = normalise(rawTitle)
        val body = normalise(fullText)
        if (title.contains("FUNDS RECEIVED") || title.contains("DUIT MASUK")) return true
        val creditVerb = listOf("RECEIVED", "CREDITED", "DITERIMA", "DIKREDIT")
            .any { body.contains(it) }
        val intoOwn = listOf(
            "IN YOUR ACCOUNT", "TO YOUR ACCOUNT", "INTO YOUR ACCOUNT",
            "KE AKAUN ANDA", "DALAM AKAUN ANDA",
        ).any { body.contains(it) }
        return creditVerb && intoOwn
    }

    private val TRANSFER_VERBS = listOf(
        "TRANSFER", "TOPPED UP", "TOP UP", "TOP-UP", "RELOAD",
        "RECEIVED", "DUITNOW", "PAYMENT OF", "PINDAHAN",
    )

    /**
     * Wallet-branded Pindahan keywords defer to the account registry: money
     * into a wallet is only "yours moving sideways" if that wallet is YOURS.
     * Remove ShopeePay from Profil and a ShopeePay top-up counts as spending
     * again — the registry is the authority, not the keyword table. Generic
     * keywords (PYMT@, CREDIT CARD-RPS, YOU'VE RECEIVED…) stay universal.
     * With an EMPTY registry the keywords win: an unconfigured install keeps
     * its default protection.
     */
    private val WALLET_KEYWORD_BRAND = mapOf(
        "TNG RELOAD" to "TNG", "TOUCH N GO RELOAD" to "TNG",
        "TNG EWALLET RELOAD" to "TNG",
        "GRABPAY TOPUP" to "GRAB",
        "SHOPEEPAY TOP" to "SHOPEE",
        "GOPAYZ RELOAD" to "GOPAYZ",
        "TO YOUR MAE WALLET" to "MAE",
    )

    private fun brandRegistered(
        brand: String,
        accounts: List<uk.wakbijok.belanja.data.OwnAccount>,
    ): Boolean = accounts.any { acc ->
        val label = acc.label.trim().uppercase().replace(" ", "")
        label.contains(brand) || brand.contains(label)
    }

    fun isLateral(
        merchant: String?,
        rawTitle: String,
        fullText: String,
        ownName: String,
        accounts: List<uk.wakbijok.belanja.data.OwnAccount>,
    ): Boolean {
        val body = normalise(fullText)
        val head = normalise("${merchant.orEmpty()} $rawTitle")
        val hay = "$head $body"
        // Checked against the WHOLE text, not through categorize(): that
        // helper is merchant-first, so with a parsed merchant ("Maybank
        // Master Card") it never reads the body and phrase keywords like
        // "TO MAYBANK MASTER CARD" silently never fired. Found by unit test.
        val pindahanHit = defaultRules.firstOrNull {
            it.category == "Pindahan" && hay.contains(it.keyword)
        }
        if (pindahanHit != null) {
            val brand = WALLET_KEYWORD_BRAND[pindahanHit.keyword]
            when {
                brand == null -> return true             // generic keyword
                accounts.isEmpty() -> return true        // unconfigured: defaults protect
                brandRegistered(brand, accounts) -> return true
                // Branded wallet deliberately absent from Profil: the user
                // treats it as external, so this is spending. Fall through —
                // the name/last4 signals below may still catch a true lateral.
            }
        }
        val hasVerb = TRANSFER_VERBS.any { body.contains(it) || head.contains(it) }
        if (!hasVerb) return false
        if (ownName.isNotBlank()) {
            val full = ownName.trim().uppercase()
            // Banks truncate recipient names to fit ("AHMAD BIN AL" for
            // "AHMAD BIN ALI", observed live). Try full name then shorter
            // prefixes down to a floor so short names do not match by accident.
            if (full.length >= 10) {
                var probe = full
                while (probe.length >= 10) {
                    if (body.contains(probe) || head.contains(probe)) return true
                    probe = probe.dropLast(1)
                }
            }
        }
        for (acc in accounts) {
            if (acc.last4.isNotBlank() && body.contains("ENDING ${acc.last4}")) return true
            val label = acc.label.trim().uppercase()
            if (label.length >= 3 && head.contains(label)) return true
        }
        return false
    }
}

package uk.wakbijok.belanja.parser

import uk.wakbijok.belanja.data.AppDb
import uk.wakbijok.belanja.data.MerchantCat

/**
 * One kedai, many spellings. Samsung Wallet says "KEDAI CONTOH SG LAMA", the
 * bank alert "KEDAI CONTOH SG LAMA CHERAS MYS", the statement "KEDAI CONTOH
 * SG LAMA SP CHERAS" — and the learned override keyed on the exact string only
 * ever fires for the spelling it was taught under. August 2026: 37 of 158
 * merchants had 2–5 spellings and a quarter of the manual recategorisations
 * were the same kedai taught again.
 *
 * The fingerprint is the leading identity tokens with place, instrument and
 * corporate-suffix words removed. The statement descriptor (the acquirer's
 * registered name, of which both notifications are truncations) is the
 * canonical spelling: every matched row teaches its descriptor's fingerprint.
 */
object Kedai {
    /** merchant_category keys carrying a fingerprint rather than an exact spelling. */
    const val LEARNED_PREFIX = "~"

    private val STOP = setOf(
        "SDN", "BHD", "MYS", "BDR", "THE", "AND", "PTE", "LTD", "WITH", "YOUR",
        "FROM", "PUTRAJA", "PUTRAJAY", "MELAKA", "SKEMBANGN",
    )
    private val splitter = Regex("[^A-Z0-9]+")

    fun tokens(s: String?): List<String> = s.orEmpty().uppercase().split(splitter)
        .filter {
            it.length >= 3 && it !in Categorizer.PLACE_TOKENS && it !in STOP &&
                !it.all { c -> c.isDigit() }
        }

    /** Two leading identity tokens; null when nothing identifies the kedai. */
    fun fingerprint(merchant: String?): String? =
        tokens(merchant).take(2).joinToString(" ").ifEmpty { null }

    /** Leading token, when distinctive enough (≥5 chars) to stand alone. */
    fun head(merchant: String?): String? =
        tokens(merchant).firstOrNull()?.takeIf { it.length >= 5 }

    /**
     * Fingerprint → category. [learned] entries are explicit teaching and
     * win outright; [history] rows vote by majority. A branch-coded chain
     * ("ECOSHOP - ABC 1" / "ECOSHOP - XYZ 2") falls through to the leading
     * token, but only while every spelling under that token agrees — SUPER
     * ALFA (Makan) and SUPER BETA (Grocery) must stay strangers.
     */
    class Dictionary(learned: Map<String, String>, history: List<MerchantCat>) {
        private val byFingerprint: Map<String, String>
        private val byHead: Map<String, String?>

        init {
            val votes = HashMap<String, MutableMap<String, Int>>()
            val heads = HashMap<String, MutableSet<String>>()
            for (h in history) {
                fingerprint(h.merchant)?.let { fp ->
                    votes.getOrPut(fp) { HashMap() }.merge(h.category, 1, Int::plus)
                }
                head(h.merchant)?.let { heads.getOrPut(it) { HashSet() }.add(h.category) }
            }
            val fp = HashMap<String, String>()
            for ((k, v) in votes) fp[k] = v.maxByOrNull { it.value }!!.key
            fp.putAll(learned)
            for ((k, cat) in learned) {
                k.substringBefore(' ').takeIf { it.length >= 5 }
                    ?.let { heads.getOrPut(it) { HashSet() }.add(cat) }
            }
            byFingerprint = fp
            byHead = heads.mapValues { (_, cats) -> cats.singleOrNull() }
        }

        fun lookup(merchant: String?): String? {
            val fp = fingerprint(merchant) ?: return null
            byFingerprint[fp]?.let { return it }
            return byHead[head(merchant) ?: return null]
        }
    }

    /** Explicit fingerprint teaching plus everything the ledger already knows. */
    suspend fun load(db: AppDb): Dictionary = Dictionary(
        learned = db.merchantCategoryDao().all()
            .filter { it.merchantKey.startsWith(LEARNED_PREFIX) }
            .associate { it.merchantKey.removePrefix(LEARNED_PREFIX) to it.category },
        history = db.txnDao().taughtMerchants(),
    )
}

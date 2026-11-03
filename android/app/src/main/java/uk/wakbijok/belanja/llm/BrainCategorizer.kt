package uk.wakbijok.belanja.llm

import org.json.JSONObject
import uk.wakbijok.belanja.data.Txn
import uk.wakbijok.belanja.parser.Categorizer

/**
 * The brain's only job here: place merchants the RULES could not.
 *
 * Three guarantees the design rests on, all enforced below rather than hoped
 * for in the prompt:
 *  1. A category outside [Categorizer.CATEGORIES] is discarded, not stored — a
 *     hallucinated bucket must never reach the ledger.
 *  2. "Not sure" is a first-class answer. PSS petrol stations and rest-stop
 *     food courts share a merchant code; a brain that always answers is a
 *     brain that lies, and one wrong propagation cost us a whole day's
 *     categories once already (the MASTERCARD incident).
 *  3. Only merchant, amount and date leave the phone. NEVER rawText — that
 *     carries the card descriptor ("Maybank MasterCard Plat") and is
 *     exactly the sort of thing that should not be posted to a third party.
 */
object BrainCategorizer {

    /** Kept short: every token here is billed on every single call. */
    private val SYSTEM = """
        Kau pembantu kategori untuk app perbelanjaan peribadi di Malaysia.
        Diberi satu transaksi, pilih SATU kategori dari senarai ini sahaja:
        ${Categorizer.CATEGORIES.joinToString(", ")}

        Panduan:
        - Grocery = pasar raya, barangan dapur. Makan = restoran, kafe, medan selera.
        - Minyak = stesen minyak. Transport = parkir, tol, e-hailing, tambang.
        - Bills = utiliti, telco, insurans. Online = e-dagang, langganan digital.
        - Health = klinik, farmasi, hospital. Family = anak, sekolah, rumah.
        - Pindahan = pemindahan wang antara akaun sendiri, BUKAN perbelanjaan.
        - Lain-lain hanya jika ia benar-benar tiada kategori lain.

        Jawab JSON sahaja, tiada teks lain:
        {"category":"<kategori atau null>","reason":"<sebab ringkas dalam Bahasa Melayu>"}

        Guna category null jika kau tidak pasti antara dua kemungkinan yang
        munasabah. Tidak pasti itu jawapan yang betul dan berguna; tekaan yang
        salah lebih merosakkan daripada mengaku tidak tahu.
        Sebab mesti bawah 15 patah perkataan dan menyebut buktinya.
    """.trimIndent()

    /** What the brain is allowed to see. Nothing else about the transaction. */
    fun promptFor(txn: Txn, day: String): String = buildString {
        append("Kedai: ").append(txn.merchant?.takeIf { it.isNotBlank() } ?: "(tiada nama)")
        txn.amountCents?.let { append("\nJumlah: RM").append(String.format("%.2f", it / 100.0)) }
        append("\nTarikh: ").append(day)
        append("\nTekaan peraturan: ").append(txn.category)
    }

    data class Proposal(val category: String?, val reason: String?)

    /**
     * Parses the reply into a proposal, refusing anything that isn't a known
     * category. Unparseable output is treated as "not sure" rather than an
     * error: the brain answered and was billed, so re-asking would just spend
     * again for the same confusion.
     */
    fun parse(reply: String): Proposal {
        val json = extractJson(reply) ?: return Proposal(null, null)
        val obj = runCatching { JSONObject(json) }.getOrNull() ?: return Proposal(null, null)
        val reason = obj.optString("reason").trim().takeIf { it.isNotBlank() }
        val raw = if (obj.isNull("category")) null else obj.optString("category").trim()
        val category = Categorizer.CATEGORIES.firstOrNull { it.equals(raw, ignoreCase = true) }
        // A category we don't know is worse than none: it would render as a
        // blank chip and could be written to the ledger on a careless accept.
        return Proposal(category, reason)
    }

    /** Models wrap JSON in prose or code fences more often than they should. */
    private fun extractJson(reply: String): String? {
        val start = reply.indexOf('{')
        val end = reply.lastIndexOf('}')
        return if (start >= 0 && end > start) reply.substring(start, end + 1) else null
    }

    fun system(): String = SYSTEM
}

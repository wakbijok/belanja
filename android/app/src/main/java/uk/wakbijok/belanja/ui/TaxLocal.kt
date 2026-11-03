package uk.wakbijok.belanja.ui

import java.util.Calendar
import uk.wakbijok.belanja.api.TaxRelief
import uk.wakbijok.belanja.api.TaxSummary
import uk.wakbijok.belanja.data.Txn

/**
 * SOLO-mode tax summary: the /api/tax/summary computation done locally.
 * The caps table is copied from server/app/tax.py (YA2026, researched
 * 26-07-2026); a new YA is a new list entry, not new code. Server mode
 * never touches this file.
 */
object TaxLocal {

    data class ReliefDef(
        val code: String,
        val nameMs: String,
        val nameEn: String,
        /** null = no cap (zakat is a rebate, tracked the same way). */
        val capCents: Long?,
        val noteMs: String,
    )

    private val RELIEFS: Map<Int, List<ReliefDef>> = mapOf(
        2026 to listOf(
            ReliefDef("medical_self", "Perubatan (diri & keluarga)", "Medical (self & family)",
                1_000_000L, "sub-cap: vaksin 1k · dental 1k · checkup/mental 1k"),
            ReliefDef("medical_parents", "Perubatan ibu bapa", "Medical (parents)",
                800_000L, "sub-cap checkup 1k"),
            ReliefDef("lifestyle", "Gaya Hidup", "Lifestyle",
                250_000L, "buku · PC/telefon/tablet · internet — jam pintar TAK layak"),
            ReliefDef("sports", "Sukan (tambahan)", "Sports (additional)",
                100_000L, "gym · alatan · yuran acara"),
            ReliefDef("education", "Yuran pengajian", "Education fees",
                700_000L, "upskilling sub-cap 2k"),
            ReliefDef("childcare", "Taska / tadika", "Childcare",
                300_000L, "YA26: sampai umur 12 + pusat transit"),
            ReliefDef("oku_equipment", "Peralatan OKU", "Disability equipment",
                600_000L, ""),
            ReliefDef("breastfeeding", "Peralatan penyusuan", "Breastfeeding equipment",
                100_000L, "sekali per 2 tahun taksiran"),
            ReliefDef("ev_green", "EV / CCTV / sisa makanan", "EV charging / CCTV / food waste",
                250_000L, "YA26: + CCTV rumah (sekali per 2 tahun)"),
            ReliefDef("vm2026", "Visit Malaysia", "Visit Malaysia",
                100_000L, "tiket tarikan berdaftar — YA2026 sahaja"),
            ReliefDef("zakat", "Zakat & Fitrah (rebat)", "Zakat & fitrah (rebate)",
                null, "badan zakat negeri — tiada had"),
        ),
    )

    /** Nearest configured YA at or below the requested one (tax.py reliefs_for). */
    fun reliefsFor(ya: Int): List<ReliefDef> {
        val years = RELIEFS.keys.sorted()
        var pick = years.first()
        for (y in years) if (y <= ya) pick = y
        return RELIEFS.getValue(pick)
    }

    /** [1 Jan YA, 1 Jan YA+1) in LOCAL time, as epoch millis. */
    fun yaBoundsMs(ya: Int): Pair<Long, Long> {
        val cal = Calendar.getInstance().apply {
            clear()
            set(ya, Calendar.JANUARY, 1)
        }
        val start = cal.timeInMillis
        cal.add(Calendar.YEAR, 1)
        return start to cal.timeInMillis
    }

    /**
     * Same aggregation as the server: 'evidenced' (receipt attached) is the
     * headline; tagged-without-receipt is shown apart. [tagged] are the YA's
     * txns with relief != null/'none'; [evidencedTxnIds] the ones with a
     * receiptLocal row.
     */
    fun summary(ya: Int, tagged: List<Txn>, evidencedTxnIds: Set<String>): TaxSummary {
        data class Agg(var tagged: Long = 0, var evidenced: Long = 0, var receipts: Int = 0)

        val agg = mutableMapOf<String, Agg>()
        for (t in tagged) {
            val code = t.relief ?: continue
            val a = agg.getOrPut(code) { Agg() }
            val cents = t.amountCents ?: 0L
            a.tagged += cents
            if (t.id in evidencedTxnIds) {
                a.evidenced += cents
                a.receipts += 1
            }
        }
        var totalEvidenced = 0L
        var totalUnproven = 0L
        val out = reliefsFor(ya).map { def ->
            val a = agg[def.code] ?: Agg()
            val cap = def.capCents
            totalEvidenced += if (cap == null) a.evidenced else minOf(a.evidenced, cap)
            totalUnproven += a.tagged - a.evidenced
            TaxRelief(
                code = def.code,
                nameMs = def.nameMs,
                nameEn = def.nameEn,
                capCents = cap,
                noteMs = def.noteMs,
                taggedCents = a.tagged,
                evidencedCents = a.evidenced,
                receipts = a.receipts,
                roomCents = if (cap == null) null else maxOf(0L, cap - a.evidenced),
                overCap = cap != null && a.evidenced > cap,
            )
        }
        return TaxSummary(
            ya = ya,
            totalEvidencedCents = totalEvidenced,
            totalUnprovenCents = totalUnproven,
            reliefs = out,
        )
    }
}

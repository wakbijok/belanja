package uk.wakbijok.belanja.llm

import android.content.Context
import kotlinx.coroutines.flow.first
import uk.wakbijok.belanja.data.AppDb
import uk.wakbijok.belanja.diag.DiagLog
import uk.wakbijok.belanja.settings.SettingsRepository

/**
 * Boleh beli tak? — asked only when the user asks.
 *
 * The verdict is the smallest part of the card; the sums are the part that can
 * be checked. All four numbers below are computed from the ledger, and the
 * arithmetic verdict stands on its own — the brain only phrases it. If the
 * brain can't show up, the numbers still do.
 */
object Advisor {

    data class Sums(
        val budgetLeftCents: Long,
        val priceCents: Long,
        /** What the same remaining days cost last month — history, not average. */
        val typicalTailCents: Long,
        val daysLeft: Int,
    ) {
        val shortfallCents: Long get() = priceCents + typicalTailCents - budgetLeftCents
        val affordable: Boolean get() = shortfallCents <= 0
    }

    data class Advice(
        val sums: Sums,
        /** Short headline: "Boleh" / "Boleh — tapi 1 Ogos" / template fallback. */
        val verdict: String,
        /** One-sentence explanation. Always present, template when brain is down. */
        val lede: String,
        /** True when the brain wrote it; false = local arithmetic template. */
        val fromBrain: Boolean,
    )

    suspend fun sums(context: Context, priceCents: Long): Sums {
        val settings = SettingsRepository(context)
        val budget = settings.budgetCents.first()
        val cycleDay = settings.currentCycleStartDay()
        val all = AppDb.get(context).txnDao().all()
        val (s, e) = BrainFacts.monthRange(0, startDay = cycleDay)
        val spent = BrainFacts.totalCents(all.filter { it.capturedAt in s until e })
        return Sums(
            budgetLeftCents = budget - spent,
            priceCents = priceCents,
            typicalTailCents = BrainFacts.lastMonthTailCents(all, startDay = cycleDay),
            daysLeft = BrainFacts.daysLeft(startDay = cycleDay),
        )
    }

    suspend fun ask(context: Context, item: String, priceCents: Long): Advice {
        val settings = SettingsRepository(context)
        val s = sums(context, priceCents)

        // The arithmetic verdict — correct on its own, brain or no brain.
        val localVerdict =
            if (s.affordable) context.getString(uk.wakbijok.belanja.R.string.adv_yes)
            else context.getString(uk.wakbijok.belanja.R.string.adv_wait)
        val localLede =
            if (s.affordable) context.getString(
                uk.wakbijok.belanja.R.string.adv_yes_lede,
                BrainFacts.rm(s.budgetLeftCents - s.priceCents - s.typicalTailCents),
            )
            else context.getString(
                uk.wakbijok.belanja.R.string.adv_wait_lede,
                BrainFacts.rm(s.shortfallCents), s.daysLeft,
            )

        val cfg = settings.currentLlmConfig()
        val month = BrainFacts.monthKey()
        val allowed = settings.currentLlmFeatures().contains(SettingsRepository.LLM_F_ADVISOR) &&
            cfg.configured
        if (!allowed) return Advice(s, localVerdict, localLede, fromBrain = false)

        val facts = buildString {
            append("Barang: $item, harga ${BrainFacts.rm(s.priceCents)}. ")
            append("Baki bajet bulan ini: ${BrainFacts.rm(s.budgetLeftCents)}. ")
            append("${s.daysLeft} hari lagi sebelum bulan berakhir; ")
            append("tempoh sama bulan lepas makan ${BrainFacts.rm(s.typicalTailCents)}. ")
            append(
                if (s.affordable) "Kiraan: masih muat."
                else "Kiraan: kurang ${BrainFacts.rm(s.shortfallCents)} jika beli sekarang."
            )
        }
        val system = """
            Kau penasihat belanja peribadi yang berterus terang. Berdasarkan
            fakta yang diberi SAHAJA, jawab JSON tepat:
            {"verdict":"<keputusan ≤6 patah>","sebab":"<satu ayat BM>"}
            WAJIB selaras dengan kiraan yang diberi — jangan lawan matematik.
            Kekalkan angka tepat seperti diberi; jangan reka angka baharu.
        """.trimIndent()

        return when (val r = LlmClient.complete(cfg, system, facts, maxTokens = REPLY_BUDGET)) {
            is LlmResult.Ok -> {
                settings.noteLlmCall(month, r.value.tokensIn, r.value.tokensOut)
                val parsed = parse(r.value.text)
                if (parsed == null) {
                    Advice(s, localVerdict, localLede, fromBrain = false)
                } else {
                    DiagLog.log(TAG, "$item ${BrainFacts.rm(priceCents)} → ${parsed.first}")
                    Advice(s, parsed.first, parsed.second, fromBrain = true)
                }
            }
            is LlmResult.Err -> {
                if (r.error !is LlmError.Unreachable) settings.noteLlmCall(month, 0, 0)
                DiagLog.log(TAG, "gagal (${r.error.javaClass.simpleName}) — kiraan tempatan dipakai")
                Advice(s, localVerdict, localLede, fromBrain = false)
            }
        }
    }

    private fun parse(reply: String): Pair<String, String>? {
        val start = reply.indexOf('{'); val end = reply.lastIndexOf('}')
        if (start < 0 || end <= start) return null
        return runCatching {
            val o = org.json.JSONObject(reply.substring(start, end + 1))
            val v = o.optString("verdict").trim()
            val why = o.optString("sebab").trim()
            if (v.isBlank()) null else v.take(60) to why.take(200)
        }.getOrNull()
    }

    private const val TAG = "Advisor"
    private const val REPLY_BUDGET = 4000
}

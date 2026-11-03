package uk.wakbijok.belanja.llm

import android.content.Context
import uk.wakbijok.belanja.data.AppDb
import uk.wakbijok.belanja.diag.DiagLog
import uk.wakbijok.belanja.settings.SettingsRepository

/**
 * Cerita bulan — one Malay paragraph in Analisis, once a month.
 *
 * The comparison arithmetic (totals, biggest rise, biggest fall) is computed
 * from the ledger here; the brain only turns those facts into prose and is
 * explicitly forbidden from adding figures of its own. Cached per month so it
 * costs exactly one call, regenerated only on demand.
 */
object MonthStory {

    sealed interface State {
        data object Off : State
        data object Loading : State
        data class Ready(val text: String, val txnCount: Int) : State
        data class Failed(val why: String) : State
    }

    suspend fun cached(context: Context): State {
        val settings = SettingsRepository(context)
        if (!settings.currentLlmFeatures().contains(SettingsRepository.LLM_F_STORY)) return State.Off
        val month = BrainFacts.monthKey()
        val (m, text, count) = settings.currentStory()
        return if (m == month && text.isNotBlank()) State.Ready(text, count) else State.Loading
    }

    /** Generates (or returns the cached) story for the current month. */
    suspend fun ensure(context: Context, force: Boolean = false): State {
        val settings = SettingsRepository(context)
        if (!settings.currentLlmFeatures().contains(SettingsRepository.LLM_F_STORY)) return State.Off
        val month = BrainFacts.monthKey()

        if (!force) {
            val (m, text, count) = settings.currentStory()
            if (m == month && text.isNotBlank()) return State.Ready(text, count)
        }

        val cfg = settings.currentLlmConfig()
        if (!cfg.configured) return State.Failed("otak belum disambung")

        // ---- facts, computed not imagined --------------------------------
        val all = AppDb.get(context).txnDao().all()
        val cycleDay = settings.currentCycleStartDay()
        val (curS, curE) = BrainFacts.monthRange(0, startDay = cycleDay)
        val (prevS, prevE) = BrainFacts.monthRange(-1, startDay = cycleDay)
        val cur = all.filter { it.capturedAt in curS until curE }
        val prev = all.filter { it.capturedAt in prevS until prevE }
        if (BrainFacts.spend(cur).isEmpty()) return State.Failed("belum ada belanja bulan ini")

        val curTotal = BrainFacts.totalCents(cur)
        val prevTotal = BrainFacts.totalCents(prev)
        val curCat = BrainFacts.byCategory(cur)
        val prevCat = BrainFacts.byCategory(prev)
        val deltas = (curCat.keys + prevCat.keys).associateWith {
            (curCat[it] ?: 0) - (prevCat[it] ?: 0)
        }
        val rise = deltas.maxByOrNull { it.value }?.takeIf { it.value > 0 }
        val fall = deltas.minByOrNull { it.value }?.takeIf { it.value < 0 }

        val facts = buildString {
            append("Bulan ini setakat hari ke-${dayOfCycle(curS)}: ${BrainFacts.rm(curTotal)} ")
            append("(${BrainFacts.spend(cur).size} transaksi). ")
            if (prevTotal > 0) append("Bulan lepas penuh: ${BrainFacts.rm(prevTotal)}. ")
            rise?.let {
                append("Naik paling banyak: ${it.key} +${BrainFacts.rm(it.value)} ")
                append("(${BrainFacts.rm(prevCat[it.key] ?: 0)} → ${BrainFacts.rm(curCat[it.key] ?: 0)}). ")
            }
            fall?.let {
                append("Turun paling banyak: ${it.key} −${BrainFacts.rm(-it.value)} ")
                append("(${BrainFacts.rm(prevCat[it.key] ?: 0)} → ${BrainFacts.rm(curCat[it.key] ?: 0)}). ")
            }
            curCat.maxByOrNull { it.value }?.let {
                append("Kategori terbesar bulan ini: ${it.key} ${BrainFacts.rm(it.value)}.")
            }
        }

        val system = """
            Kau penganalisis belanja peribadi. Tulis cerita ringkas 2-3 ayat
            dalam Bahasa Melayu santai tentang bulan ini, berdasarkan fakta
            yang diberi SAHAJA. WAJIB kekalkan setiap angka tepat seperti yang
            diberi; JANGAN reka atau kira angka baharu. Jangan beri nasihat.
            Jawab perenggan sahaja, tiada tajuk, tiada senarai.
        """.trimIndent()

        return when (val r = LlmClient.complete(cfg, system, facts, maxTokens = REPLY_BUDGET)) {
            is LlmResult.Ok -> {
                settings.noteLlmCall(month, r.value.tokensIn, r.value.tokensOut)
                val text = r.value.text.take(600)
                settings.setStory(month, text, BrainFacts.spend(cur).size)
                DiagLog.log(TAG, "cerita $month dijana (${r.value.tokensIn}+${r.value.tokensOut} tok)")
                State.Ready(text, BrainFacts.spend(cur).size)
            }
            is LlmResult.Err -> {
                if (r.error !is LlmError.Unreachable) settings.noteLlmCall(month, 0, 0)
                DiagLog.log(TAG, "cerita gagal: ${r.error.javaClass.simpleName}")
                State.Failed(
                    when (r.error) {
                        is LlmError.Unreachable -> "otak tak dapat dihubungi"
                        is LlmError.Unauthorized -> "kunci ditolak"
                        is LlmError.ThinkingRanOut -> "model kehabisan ruang berfikir"
                        else -> "balasan tak difahami"
                    }
                )
            }
        }
    }

    /** 1-based day count into the current cycle ("hari ke-N"). */
    private fun dayOfCycle(cycleStartMillis: Long): Int =
        ((uk.wakbijok.belanja.data.Cycle.startOfDay(System.currentTimeMillis()) -
            cycleStartMillis) / 86_400_000L).toInt() + 1

    private const val TAG = "MonthStory"
    private const val REPLY_BUDGET = 4000
}

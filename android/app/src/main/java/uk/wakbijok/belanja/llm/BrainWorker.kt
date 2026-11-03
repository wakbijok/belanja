package uk.wakbijok.belanja.llm

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import uk.wakbijok.belanja.data.AppDb
import uk.wakbijok.belanja.diag.DiagLog
import uk.wakbijok.belanja.settings.SettingsRepository

/**
 * Asks the brain about transactions the rules left for review, and writes the
 * answer into the proposal lane only.
 *
 * Runs off the capture path deliberately: a notification must become a
 * transaction instantly and offline, so the network call happens later, in the
 * background, where failing costs nothing.
 */
class BrainWorker(context: Context, params: WorkerParameters) :
    CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val settings = SettingsRepository(applicationContext)
        if (!settings.currentLlmFeatures().contains(SettingsRepository.LLM_F_CATEGORY)) {
            return Result.success()
        }
        val cfg = settings.currentLlmConfig()
        if (!cfg.configured) {
            DiagLog.log(TAG, "langkau: otak belum disambung")
            return Result.success()
        }

        val month = SimpleDateFormat("yyyy-MM", Locale.US).format(Date())
        val dayFmt = SimpleDateFormat("yyyy-MM-dd", Locale.US)
        val dao = AppDb.get(applicationContext).txnDao()
        val pending = dao.pendingBrain(BATCH)
        if (pending.isEmpty()) return Result.success()

        var asked = 0
        var unsure = 0
        for (txn in pending) {
            val result = LlmClient.complete(
                cfg = cfg,
                system = BrainCategorizer.system(),
                user = BrainCategorizer.promptFor(txn, dayFmt.format(Date(txn.capturedAt))),
                // Generous on purpose: a reasoning model spends most of this
                // thinking before it writes the one line we asked for. 200 was
                // enough for a plain model and produced empty replies on
                // glm-5.2 — the symptom looked like a broken provider.
                maxTokens = REPLY_BUDGET,
            )
            when (result) {
                is LlmResult.Ok -> {
                    settings.noteLlmCall(month, result.value.tokensIn, result.value.tokensOut)
                    val p = BrainCategorizer.parse(result.value.text)
                    dao.setBrainProposal(txn.id, p.category, p.reason, System.currentTimeMillis())
                    asked++
                    if (p.category == null) unsure++
                    DiagLog.log(
                        TAG,
                        "${txn.merchant ?: txn.rawTitle.take(24)} → ${p.category ?: "tak pasti"}" +
                            " (${result.value.tokensIn}+${result.value.tokensOut} tok)",
                    )
                }
                is LlmResult.Err -> {
                    // Anything that reached the provider was billed, so it must
                    // count against the cap even though we could not use it.
                    // Only a call that never arrived is free.
                    if (result.error !is LlmError.Unreachable) settings.noteLlmCall(month, 0, 0)
                    DiagLog.log(TAG, "gagal: ${describe(result.error)}")
                    // Leave brainAskedAt at 0 so the row is retried once the
                    // cause is fixed; stop the batch either way, since one bad
                    // endpoint means every row would fail identically.
                    return if (result.error is LlmError.Unreachable) Result.retry() else Result.success()
                }
            }
        }
        DiagLog.log(TAG, "selesai: $asked ditanya, $unsure tak pasti")
        return Result.success()
    }

    private fun describe(e: LlmError): String = when (e) {
        is LlmError.Unauthorized -> "kunci ditolak (${e.code})"
        is LlmError.NoModelList -> "tiada senarai model (404)"
        is LlmError.Http -> "HTTP ${e.code}: ${e.detail}"
        is LlmError.Unreachable -> "tak sampai (${e.reason})"
        is LlmError.BadShape -> "balasan tak difahami (${e.reason})"
        is LlmError.ThinkingRanOut ->
            "model habis bajet token semasa berfikir (${e.tokensSpent} tok) — naikkan had"
    }

    companion object {
        private const val TAG = "BrainWorker"

        /** Small on purpose: a capture burst must not turn into a bill. */
        private const val BATCH = 10

        /**
         * Room for a reasoning model to think AND still answer. glm-5.2 was
         * observed spending >1200 tokens of thought on a two-line question;
         * 4000 clears that with margin. The per-month call cap is the real
         * cost control — this constant is not a spending lever.
         */
        private const val REPLY_BUDGET = 4000
        private const val WORK = "belanja-brain"

        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

        /**
         * Queues a pass. KEEP, not REPLACE: several captures in a row should
         * share one run rather than each cancelling the last one's progress.
         */
        fun nudge(context: Context) {
            val appContext = context.applicationContext
            scope.launch {
                val settings = SettingsRepository(appContext)
                if (!settings.currentLlmFeatures().contains(SettingsRepository.LLM_F_CATEGORY)) return@launch
                if (!settings.currentLlmConfig().configured) return@launch
                WorkManager.getInstance(appContext).enqueueUniqueWork(
                    WORK,
                    ExistingWorkPolicy.KEEP,
                    OneTimeWorkRequestBuilder<BrainWorker>()
                        .setConstraints(
                            Constraints.Builder()
                                .setRequiredNetworkType(NetworkType.CONNECTED)
                                .build()
                        )
                        .build(),
                )
            }
        }
    }
}

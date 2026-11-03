package uk.wakbijok.belanja.sync

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import uk.wakbijok.belanja.settings.SettingsRepository

object SyncScheduler {

    private const val PERIODIC_WORK = "belanja-sync-periodic"
    private const val EXPEDITED_WORK = "belanja-sync-now"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val networkConstraint = Constraints.Builder()
        .setRequiredNetworkType(NetworkType.CONNECTED)
        .build()

    /**
     * 15-minute periodic sync (minimum interval WorkManager allows).
     * Mode guard lives HERE, not at call sites: in solo mode this cancels
     * the periodic work instead of scheduling it, so flipping the mode in
     * Tetapan is enough to stop (or resume) syncing everywhere.
     */
    fun ensurePeriodic(context: Context) {
        val appContext = context.applicationContext
        scope.launch {
            val wm = WorkManager.getInstance(appContext)
            if (SettingsRepository(appContext).currentAppMode() == SettingsRepository.MODE_SOLO) {
                wm.cancelUniqueWork(PERIODIC_WORK)
                return@launch
            }
            val request = PeriodicWorkRequestBuilder<SyncWorker>(15, TimeUnit.MINUTES)
                .setConstraints(networkConstraint)
                .build()
            wm.enqueueUniquePeriodicWork(
                PERIODIC_WORK,
                ExistingPeriodicWorkPolicy.KEEP,
                request,
            )
        }
    }

    /**
     * Expedited one-shot sync (after a capture, an edit, or a manual tap).
     *
     * APPEND_OR_REPLACE, never REPLACE: REPLACE cancels a *running* worker, so
     * a bank notification arriving mid-sync would abort the pull before the
     * ledger was written. Appending guarantees a follow-up pass instead, and
     * still replaces work that already failed or was cancelled.
     */
    fun syncNow(context: Context) {
        val appContext = context.applicationContext
        scope.launch {
            // Solo mode has no server: quiet no-op, guarded here once.
            if (SettingsRepository(appContext).currentAppMode() == SettingsRepository.MODE_SOLO) {
                return@launch
            }
            val request = OneTimeWorkRequestBuilder<SyncWorker>()
                .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                .setConstraints(networkConstraint)
                .build()
            WorkManager.getInstance(appContext).enqueueUniqueWork(
                EXPEDITED_WORK,
                ExistingWorkPolicy.APPEND_OR_REPLACE,
                request,
            )
        }
    }
}

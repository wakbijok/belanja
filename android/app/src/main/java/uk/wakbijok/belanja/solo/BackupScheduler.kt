package uk.wakbijok.belanja.solo

import android.content.Context
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import uk.wakbijok.belanja.settings.SettingsRepository

/**
 * SOLO-mode counterpart of [uk.wakbijok.belanja.sync.SyncScheduler]: an hourly
 * [BackupWorker]. Mode-guarded inside, so call sites never need to check —
 * in server mode the periodic work is cancelled, not merely skipped.
 * No network constraint: the SAF tree is local (or a provider that syncs
 * on its own schedule).
 */
object BackupScheduler {

    private const val PERIODIC_WORK = "belanja-backup-periodic"
    private const val ONESHOT_WORK = "belanja-backup-now"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun ensurePeriodic(context: Context) {
        val appContext = context.applicationContext
        scope.launch {
            val wm = WorkManager.getInstance(appContext)
            if (SettingsRepository(appContext).currentAppMode() != SettingsRepository.MODE_SOLO) {
                wm.cancelUniqueWork(PERIODIC_WORK)
                return@launch
            }
            val request = PeriodicWorkRequestBuilder<BackupWorker>(1, TimeUnit.HOURS).build()
            wm.enqueueUniquePeriodicWork(
                PERIODIC_WORK,
                ExistingPeriodicWorkPolicy.KEEP,
                request,
            )
        }
    }

    /** One-shot pass (after picking the folder, or "Sandar sekarang"). */
    fun backupNow(context: Context) {
        val appContext = context.applicationContext
        scope.launch {
            if (SettingsRepository(appContext).currentAppMode() != SettingsRepository.MODE_SOLO) {
                return@launch
            }
            WorkManager.getInstance(appContext).enqueueUniqueWork(
                ONESHOT_WORK,
                ExistingWorkPolicy.APPEND_OR_REPLACE,
                OneTimeWorkRequestBuilder<BackupWorker>().build(),
            )
        }
    }
}

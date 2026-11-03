package uk.wakbijok.belanja.solo

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import uk.wakbijok.belanja.settings.SettingsRepository

/**
 * Hourly SOLO-mode backup pass (same shape as SyncWorker): retries pending
 * receipt write-throughs (which also refreshes the year manifests), then —
 * once per local day, after 03:00 — copies the Room DB into `_db/` in the
 * SAF tree, keeping the newest 30.
 *
 * Every branch records an outcome; a pass that did nothing must say why.
 */
class BackupWorker(context: Context, params: WorkerParameters) :
    CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val settings = SettingsRepository(applicationContext)
        if (settings.currentAppMode() != SettingsRepository.MODE_SOLO) {
            return Result.success()
        }
        if (settings.currentSafTree().isBlank()) {
            settings.recordBackup(System.currentTimeMillis(), "folder sandaran belum dipilih")
            return Result.success()
        }

        val steps = mutableListOf<String>()
        var shouldRetry = false

        val (ok, failed) = SafBackup.sweep(applicationContext)
        steps += when {
            ok + failed == 0 -> "resit 0 tertunggak"
            failed == 0 -> "resit $ok disandar"
            else -> "resit $ok ok · $failed gagal"
        }
        if (failed > 0) shouldRetry = true

        val today = SafBackup.todayLocal()
        if (SafBackup.localHour() >= 3 && settings.lastDbBackupDay() != today) {
            if (SafBackup.backupDb(applicationContext)) {
                settings.setLastDbBackupDay(today)
                steps += "DB disandar"
            } else {
                steps += "DB gagal"
                shouldRetry = true
            }
        }

        settings.recordBackup(System.currentTimeMillis(), steps.joinToString(" · "))
        return if (shouldRetry) Result.retry() else Result.success()
    }
}

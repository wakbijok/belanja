package uk.wakbijok.belanja.sync

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.CancellationException
import org.json.JSONArray
import org.json.JSONObject
import uk.wakbijok.belanja.BuildConfig
import uk.wakbijok.belanja.auth.AuthClient
import uk.wakbijok.belanja.auth.FetchResult
import uk.wakbijok.belanja.data.AppDb
import uk.wakbijok.belanja.data.Txn
import uk.wakbijok.belanja.diag.DiagLog
import uk.wakbijok.belanja.settings.SettingsRepository

/**
 * One sync pass, both directions: push local captures and edits up, flush
 * pending deletes, then pull the server ledger back down. Pulling lives here
 * rather than in the UI so the periodic worker restores history too — the
 * phone converges on the server whether or not anyone opens the app.
 *
 * Every branch records an outcome; a sync that did nothing must say why.
 */
class SyncWorker(context: Context, params: WorkerParameters) :
    CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val settings = SettingsRepository(applicationContext)
        if (settings.currentAppMode() == SettingsRepository.MODE_SOLO) {
            // Scheduling is already guarded; this is the belt to that brace.
            return Result.success()
        }
        val token = settings.currentSessionToken()
        if (token.isBlank()) {
            // Not logged in yet — txns stay queued in Room until a session exists.
            Log.d(TAG, "no session; deferring sync")
            settings.recordSync(System.currentTimeMillis(), "belum log masuk")
            return Result.success()
        }

        val dao = AppDb.get(applicationContext).txnDao()
        val steps = mutableListOf<String>()
        var shouldRetry = false

        // ---- 1. Push local captures and category edits ---------------------
        val pending = dao.unsynced()
        if (pending.isEmpty()) {
            steps += "naik 0"
        } else {
            val code = try {
                post("${BuildConfig.SYNC_URL}/api/txns", token, toJson(pending))
            } catch (c: CancellationException) {
                throw c // cancellation is not a sync failure; let it propagate
            } catch (t: Throwable) {
                Log.w(TAG, "push failed", t)
                steps += "naik gagal (${t.javaClass.simpleName})"
                shouldRetry = true
                null
            }
            when {
                code == null -> Unit // already recorded above
                code in 200..299 -> {
                    dao.markSynced(pending.map { it.id })
                    steps += "naik ${pending.size}"
                }
                code == 401 -> return expiredSession(settings)
                else -> {
                    steps += "naik gagal (HTTP $code)"
                    shouldRetry = true
                }
            }
        }

        // ---- 2. Flush deletes the server has not seen yet -------------------
        val tombstones = dao.tombstoneIds()
        if (tombstones.isNotEmpty()) {
            var cleared = 0
            for (id in tombstones) {
                if (AuthClient.deleteTxn(token, id)) {
                    dao.clearTombstone(id)
                    cleared++
                }
            }
            steps += "padam $cleared/${tombstones.size}"
            if (cleared < tombstones.size) shouldRetry = true
        }

        // ---- 3. Pull the ledger back down -----------------------------------
        when (val pull = AuthClient.fetchAllTxns(token)) {
            is FetchResult.Ok -> {
                // Never overwrite rows with unpushed local edits, and never
                // resurrect something deleted here but not yet deleted upstream.
                val protectedIds = (dao.unsyncedIds() + dao.tombstoneIds()).toSet()
                val incoming = pull.txns.filter { it.id !in protectedIds }
                if (incoming.isNotEmpty()) dao.upsertAll(incoming)
                steps += "turun ${incoming.size}"
            }
            is FetchResult.Http -> {
                if (pull.code == 401) return expiredSession(settings)
                steps += "turun gagal (HTTP ${pull.code})"
                shouldRetry = true
            }
            is FetchResult.Failed -> {
                steps += "turun gagal (${pull.message})"
                shouldRetry = true
            }
        }

        val summary = steps.joinToString(" · ")
        Log.i(TAG, "sync: $summary")
        DiagLog.log(TAG, "sync: $summary")
        settings.recordSync(System.currentTimeMillis(), summary)
        return if (shouldRetry) Result.retry() else Result.success()
    }

    /** Session expired/revoked: drop it; the UI falls back to the login screen. */
    private suspend fun expiredSession(settings: SettingsRepository): Result {
        Log.w(TAG, "sync unauthorized; clearing session")
        DiagLog.log(TAG, "sync unauthorized (401); session cleared")
        settings.recordSync(System.currentTimeMillis(), "sesi tamat (401) — log masuk semula")
        settings.clearSession()
        return Result.failure()
    }

    private fun toJson(txns: List<Txn>): String {
        val arr = JSONArray()
        for (t in txns) {
            arr.put(
                JSONObject().apply {
                    put("id", t.id)
                    put("sourcePkg", t.sourcePkg)
                    put("amountCents", t.amountCents ?: JSONObject.NULL)
                    put("merchant", t.merchant ?: JSONObject.NULL)
                    put("rawTitle", t.rawTitle)
                    put("rawText", t.rawText)
                    put("capturedAt", t.capturedAt)
                    put("needsReview", t.needsReview)
                    put("category", t.category)
                    put("categoryOverridden", t.categoryOverridden)
                    put("relief", t.relief ?: JSONObject.NULL)
                }
            )
        }
        return JSONObject().put("txns", arr).toString()
    }

    private fun post(endpoint: String, token: String, body: String): Int {
        val conn = URL(endpoint).openConnection() as HttpURLConnection
        return try {
            conn.requestMethod = "POST"
            conn.connectTimeout = 15_000
            conn.readTimeout = 15_000
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json")
            conn.setRequestProperty("Authorization", "Bearer $token")
            conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            conn.responseCode
        } finally {
            conn.disconnect()
        }
    }

    companion object {
        private const val TAG = "SyncWorker"
    }
}

package uk.wakbijok.belanja.auth

import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import uk.wakbijok.belanja.BuildConfig
import uk.wakbijok.belanja.data.Txn

sealed interface LoginResult {
    data class Success(val token: String) : LoginResult
    data object BadCredentials : LoginResult
    data class Error(val message: String) : LoginResult
}

/**
 * Outcome of a ledger pull. Every failure carries a reason: a silent `null`
 * is what previously made a broken restore indistinguishable from a working
 * one, so the caller can always report what actually happened.
 */
sealed interface FetchResult {
    data class Ok(val txns: List<Txn>) : FetchResult
    data class Http(val code: Int) : FetchResult
    data class Failed(val message: String) : FetchResult
}

object AuthClient {

    /** Pull the whole server ledger (used to restore a reinstalled phone). */
    suspend fun fetchAllTxns(token: String): FetchResult =
        withContext(Dispatchers.IO) {
            if (token.isBlank()) return@withContext FetchResult.Failed("tiada sesi")
            try {
                val conn = URL("${BuildConfig.SYNC_URL}/api/txns")
                    .openConnection() as HttpURLConnection
                try {
                    conn.requestMethod = "GET"
                    conn.connectTimeout = 15_000
                    conn.readTimeout = 30_000
                    conn.setRequestProperty("Authorization", "Bearer $token")
                    val code = conn.responseCode
                    if (code !in 200..299) return@withContext FetchResult.Http(code)
                    val body = conn.inputStream.bufferedReader().readText()
                    val arr = JSONObject(body).getJSONArray("txns")
                    FetchResult.Ok(
                        (0 until arr.length()).map { i ->
                            val o = arr.getJSONObject(i)
                            Txn(
                                id = o.getString("id"),
                                sourcePkg = o.optString("sourcePkg"),
                                amountCents = if (o.isNull("amountCents")) null else o.getLong("amountCents"),
                                merchant = if (o.isNull("merchant")) null else o.getString("merchant"),
                                rawTitle = o.optString("rawTitle"),
                                rawText = o.optString("rawText"),
                                capturedAt = o.getLong("capturedAt"),
                                needsReview = o.optBoolean("needsReview"),
                                synced = true,
                                category = o.optString("category", "Lain-lain"),
                                categoryOverridden = o.optBoolean("categoryOverridden"),
                                relief = if (o.isNull("relief")) null
                                    else o.optString("relief").ifBlank { null },
                            )
                        }
                    )
                } finally {
                    conn.disconnect()
                }
            } catch (t: Throwable) {
                FetchResult.Failed("${t.javaClass.simpleName} ${t.message.orEmpty()}".trim())
            }
        }

    /** Best-effort server-side delete; failure is not fatal to the local delete. */
    suspend fun deleteTxn(token: String, id: String): Boolean =
        withContext(Dispatchers.IO) {
            if (token.isBlank()) return@withContext false
            try {
                val conn = URL("${BuildConfig.SYNC_URL}/api/txns/$id")
                    .openConnection() as HttpURLConnection
                try {
                    conn.requestMethod = "DELETE"
                    conn.connectTimeout = 15_000
                    conn.readTimeout = 15_000
                    conn.setRequestProperty("Authorization", "Bearer $token")
                    conn.responseCode in 200..299 || conn.responseCode == 404
                } finally {
                    conn.disconnect()
                }
            } catch (t: Throwable) {
                false
            }
        }

    suspend fun login(username: String, password: String): LoginResult =
        withContext(Dispatchers.IO) {
            try {
                val body = JSONObject()
                    .put("username", username)
                    .put("password", password)
                    .toString()
                val conn = URL("${BuildConfig.SYNC_URL}/api/auth/login")
                    .openConnection() as HttpURLConnection
                try {
                    conn.requestMethod = "POST"
                    conn.connectTimeout = 15_000
                    conn.readTimeout = 15_000
                    conn.doOutput = true
                    conn.setRequestProperty("Content-Type", "application/json")
                    conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
                    when (val code = conn.responseCode) {
                        in 200..299 -> {
                            val resp = conn.inputStream.bufferedReader().readText()
                            val token = JSONObject(resp).optString("token")
                            if (token.isBlank()) LoginResult.Error("Malformed server response")
                            else LoginResult.Success(token)
                        }
                        401 -> LoginResult.BadCredentials
                        else -> LoginResult.Error("Server error (HTTP $code)")
                    }
                } finally {
                    conn.disconnect()
                }
            } catch (t: Throwable) {
                LoginResult.Error(t.message ?: "Network error")
            }
        }
}

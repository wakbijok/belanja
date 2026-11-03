package uk.wakbijok.belanja.api

import java.net.URLEncoder
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import uk.wakbijok.belanja.BuildConfig
import uk.wakbijok.belanja.data.Txn

/** Uniform outcome for every server call: value, HTTP status, or transport failure. */
sealed interface ApiResult<out T> {
    data class Ok<T>(val value: T) : ApiResult<T>
    data class Http(val code: Int) : ApiResult<Nothing>
    data class Fail(val message: String) : ApiResult<Nothing>
}

data class DaySummary(val day: String, val totalCents: Long, val count: Int)

data class MonthTotal(val month: String, val totalCents: Long)

data class RemoteTxn(val txn: Txn, val receiptId: String?)

data class TxnPage(val txns: List<RemoteTxn>, val nextBefore: Long?)

data class ReceiptMeta(
    val id: String,
    val filename: String,
    val mime: String,
    val sizeBytes: Long,
    val uploadedAt: Long,
)

data class ReceiptFile(val bytes: ByteArray, val mime: String)

/** Fields extracted from a receipt by POST /api/receipts/parse; all optional. */
data class ReceiptParse(
    val merchant: String?,
    val amountCents: Long?,
    /** "yyyy-MM-dd" or null. */
    val date: String?,
)

data class StatementInfo(
    val id: String,
    val month: String,
    val filename: String,
    val uploadedAt: Long,
    val matched: Int,
    val missing: Int,
    val credits: Int,
    val unresolved: Int,
)

data class StatementRow(
    val id: Long,
    val txnDate: String,
    val description: String,
    val amountCents: Long,
    val credit: Boolean,
    val status: String,
    val resolved: String?,
    val suggestedCategory: String?,
)

/** One LHDN relief line from GET /api/tax/summary. */
data class TaxRelief(
    val code: String,
    val nameMs: String,
    val nameEn: String,
    /** null = no cap (zakat rebate). */
    val capCents: Long?,
    val noteMs: String,
    val taggedCents: Long,
    val evidencedCents: Long,
    val receipts: Int,
    /** null when the relief has no cap. */
    val roomCents: Long?,
    val overCap: Boolean,
)

data class TaxSummary(
    val ya: Int,
    val totalEvidencedCents: Long,
    val totalUnprovenCents: Long,
    val reliefs: List<TaxRelief>,
)

data class StatementDetail(
    val id: String,
    val month: String,
    val rows: List<StatementRow>,
    val unclear: List<Txn>,
)

/**
 * Client for the newer endpoints (PATCH, search, summaries, receipts,
 * statements). Same plain-HttpURLConnection pattern as [uk.wakbijok.belanja.auth.AuthClient]:
 * bearer token, short timeouts, and no failure is allowed to escape as a crash.
 */
object ApiClient {

    private const val TIMEOUT_MS = 15_000

    // ---- Txns --------------------------------------------------------------

    /**
     * Partial edit. Any subset of amountCents / merchant / category /
     * needsReview / relief. For relief: a code or "none" sets the tag,
     * "" clears it (server answers 422 on an unknown code).
     */
    suspend fun patchTxn(
        token: String,
        id: String,
        amountCents: Long? = null,
        merchant: String? = null,
        category: String? = null,
        needsReview: Boolean? = null,
        relief: String? = null,
    ): ApiResult<Unit> = request("PATCH", "/api/txns/$id", token, body = JSONObject().apply {
        amountCents?.let { put("amountCents", it) }
        merchant?.let { put("merchant", it) }
        category?.let { put("category", it) }
        needsReview?.let { put("needsReview", it) }
        relief?.let { put("relief", it) }
    }.toString()) { ApiResult.Ok(Unit) }

    /** Month listing / keyword search / cursor pagination; rows carry receiptId. */
    suspend fun fetchTxns(
        token: String,
        month: String? = null,
        q: String? = null,
        limit: Int? = null,
        before: Long? = null,
    ): ApiResult<TxnPage> {
        val params = buildList {
            month?.let { add("month=${enc(it)}") }
            q?.let { add("q=${enc(it)}") }
            limit?.let { add("limit=$it") }
            before?.let { add("before=$it") }
        }
        val qs = if (params.isEmpty()) "" else "?" + params.joinToString("&")
        return request("GET", "/api/txns$qs", token) { body ->
            val o = JSONObject(body)
            val arr = o.getJSONArray("txns")
            ApiResult.Ok(
                TxnPage(
                    txns = (0 until arr.length()).map { parseRemoteTxn(arr.getJSONObject(it)) },
                    nextBefore = if (o.isNull("nextBefore")) null else o.getLong("nextBefore"),
                )
            )
        }
    }

    // ---- Summaries -----------------------------------------------------------

    suspend fun fetchSummaryDays(token: String, month: String): ApiResult<List<DaySummary>> =
        request("GET", "/api/summary/days?month=${enc(month)}", token) { body ->
            val arr = JSONObject(body).getJSONArray("days")
            ApiResult.Ok(
                (0 until arr.length()).map { i ->
                    val o = arr.getJSONObject(i)
                    DaySummary(o.getString("day"), o.getLong("totalCents"), o.getInt("count"))
                }
            )
        }

    suspend fun fetchSummaryMonths(token: String, end: String, n: Int = 6): ApiResult<List<MonthTotal>> =
        request("GET", "/api/summary/months?end=${enc(end)}&n=$n", token) { body ->
            val arr = JSONObject(body).getJSONArray("months")
            ApiResult.Ok(
                (0 until arr.length()).map { i ->
                    val o = arr.getJSONObject(i)
                    MonthTotal(o.getString("month"), o.getLong("totalCents"))
                }
            )
        }

    // ---- Tax (LHDN reliefs) -----------------------------------------------------

    suspend fun fetchTaxSummary(token: String, ya: Int): ApiResult<TaxSummary> =
        request("GET", "/api/tax/summary?ya=$ya", token) { body ->
            val o = JSONObject(body)
            val arr = o.getJSONArray("reliefs")
            ApiResult.Ok(
                TaxSummary(
                    ya = o.optInt("ya", ya),
                    totalEvidencedCents = o.optLong("totalEvidencedCents"),
                    totalUnprovenCents = o.optLong("totalUnprovenCents"),
                    reliefs = (0 until arr.length()).map { i ->
                        val r = arr.getJSONObject(i)
                        TaxRelief(
                            code = r.getString("code"),
                            nameMs = r.optString("nameMs"),
                            nameEn = r.optString("nameEn"),
                            capCents = if (r.isNull("capCents")) null else r.getLong("capCents"),
                            noteMs = r.optString("noteMs"),
                            taggedCents = r.optLong("taggedCents"),
                            evidencedCents = r.optLong("evidencedCents"),
                            receipts = r.optInt("receipts"),
                            roomCents = if (r.isNull("roomCents")) null else r.getLong("roomCents"),
                            overCap = r.optBoolean("overCap"),
                        )
                    },
                )
            )
        }

    /** Txns tagged with a relief for a YA; rows carry receiptId. */
    suspend fun fetchTaxTxns(token: String, ya: Int, relief: String): ApiResult<List<RemoteTxn>> =
        request("GET", "/api/tax/txns?ya=$ya&relief=${enc(relief)}", token) { body ->
            val arr = JSONObject(body).getJSONArray("txns")
            ApiResult.Ok((0 until arr.length()).map { parseRemoteTxn(arr.getJSONObject(it)) })
        }

    // ---- Receipts -------------------------------------------------------------

    /** Multipart upload; the server answers 409 when a receipt is already attached. */
    suspend fun uploadReceipt(
        token: String,
        txnId: String,
        bytes: ByteArray,
        filename: String,
        mime: String,
    ): ApiResult<String> = multipart("/api/txns/$txnId/receipt", token, bytes, filename, mime) { body ->
        ApiResult.Ok(JSONObject(body).optString("id"))
    }

    suspend fun fetchReceiptMeta(token: String, receiptId: String): ApiResult<ReceiptMeta> =
        request("GET", "/api/receipts/$receiptId", token) { body ->
            val o = JSONObject(body)
            ApiResult.Ok(
                ReceiptMeta(
                    id = o.getString("id"),
                    filename = o.optString("filename", "resit"),
                    mime = o.optString("mime", "application/octet-stream"),
                    sizeBytes = o.optLong("sizeBytes"),
                    uploadedAt = o.optLong("uploadedAt"),
                )
            )
        }

    suspend fun downloadReceipt(token: String, receiptId: String): ApiResult<ReceiptFile> =
        withContext(Dispatchers.IO) {
            if (token.isBlank()) return@withContext ApiResult.Fail("no session")
            try {
                client.newCall(get("/api/receipts/$receiptId/file", token)).execute().use { resp ->
                    if (!resp.isSuccessful) return@withContext ApiResult.Http(resp.code)
                    val mime = resp.header("Content-Type") ?: "application/octet-stream"
                    ApiResult.Ok(ReceiptFile(resp.body?.bytes() ?: ByteArray(0), mime))
                }
            } catch (t: Throwable) {
                ApiResult.Fail(t.javaClass.simpleName)
            }
        }

    suspend fun deleteReceipt(token: String, receiptId: String): ApiResult<Unit> =
        request("DELETE", "/api/receipts/$receiptId", token) { ApiResult.Ok(Unit) }

    /** Server-side receipt OCR/parse; images come back with all fields null. */
    suspend fun parseReceipt(
        token: String,
        bytes: ByteArray,
        filename: String,
        mime: String,
    ): ApiResult<ReceiptParse> = multipart("/api/receipts/parse", token, bytes, filename, mime) { body ->
        val o = JSONObject(body)
        ApiResult.Ok(
            ReceiptParse(
                merchant = if (o.isNull("merchant")) null else o.getString("merchant"),
                amountCents = if (o.isNull("amountCents")) null else o.getLong("amountCents"),
                date = if (o.isNull("date")) null else o.getString("date"),
            )
        )
    }

    /**
     * Push a single txn as a batch-of-one — same payload shape as the
     * [uk.wakbijok.belanja.sync.SyncWorker] push, so the server sees no
     * difference. Used when a receipt upload must follow immediately and
     * cannot wait for the next sync pass to create the row.
     */
    suspend fun pushTxn(token: String, t: Txn): ApiResult<Unit> = request(
        "POST",
        "/api/txns",
        token,
        body = JSONObject().put(
            "txns",
            JSONArray().put(
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
        ).toString(),
    ) { ApiResult.Ok(Unit) }

    /** Re-run keyword rules server-side over non-overridden Lain-lain txns. */
    suspend fun recategorize(token: String): ApiResult<Int> =
        request("POST", "/api/txns/recategorize", token) { body ->
            ApiResult.Ok(JSONObject(body).optInt("recategorized"))
        }

    // ---- Statements -----------------------------------------------------------

    suspend fun uploadStatement(
        token: String,
        bytes: ByteArray,
        filename: String,
        mime: String,
    ): ApiResult<String> = multipart("/api/statements", token, bytes, filename, mime) { body ->
        ApiResult.Ok(JSONObject(body).optString("id"))
    }

    suspend fun fetchStatements(token: String): ApiResult<List<StatementInfo>> =
        request("GET", "/api/statements", token) { body ->
            val arr = JSONObject(body).getJSONArray("statements")
            ApiResult.Ok(
                (0 until arr.length()).map { i ->
                    val o = arr.getJSONObject(i)
                    StatementInfo(
                        id = o.getString("id"),
                        month = o.getString("month"),
                        filename = o.optString("filename"),
                        uploadedAt = o.optLong("uploadedAt"),
                        matched = o.optInt("matched"),
                        missing = o.optInt("missing"),
                        credits = o.optInt("credits"),
                        unresolved = o.optInt("unresolved"),
                    )
                }
            )
        }

    suspend fun fetchStatementDetail(token: String, id: String): ApiResult<StatementDetail> =
        request("GET", "/api/statements/$id", token) { body ->
            val o = JSONObject(body)
            val rows = o.getJSONArray("rows")
            val unclear = o.getJSONArray("unclear")
            ApiResult.Ok(
                StatementDetail(
                    id = o.getString("id"),
                    month = o.getString("month"),
                    rows = (0 until rows.length()).map { i ->
                        val r = rows.getJSONObject(i)
                        StatementRow(
                            id = r.getLong("id"),
                            txnDate = r.optString("txnDate"),
                            description = r.optString("description"),
                            amountCents = r.optLong("amountCents"),
                            credit = r.optBoolean("credit"),
                            status = r.optString("status"),
                            resolved = if (r.isNull("resolved")) null else r.getString("resolved"),
                            suggestedCategory =
                                if (r.isNull("suggestedCategory")) null
                                else r.getString("suggestedCategory"),
                        )
                    },
                    unclear = (0 until unclear.length()).map { i ->
                        parseRemoteTxn(unclear.getJSONObject(i)).txn
                    },
                )
            )
        }

    suspend fun resolveStatementRow(
        token: String,
        statementId: String,
        rowId: Long,
        action: String,
        category: String? = null,
    ): ApiResult<Unit> = request(
        "POST",
        "/api/statements/$statementId/rows/$rowId/resolve",
        token,
        body = JSONObject().apply {
            put("action", action)
            category?.let { put("category", it) }
        }.toString(),
    ) { ApiResult.Ok(Unit) }

    suspend fun resolveAllStatementRows(
        token: String,
        statementId: String,
        action: String,
    ): ApiResult<Int> = request(
        "POST",
        "/api/statements/$statementId/resolve-all",
        token,
        body = JSONObject().apply { put("action", action) }.toString(),
    ) { ApiResult.Ok(JSONObject(it).optInt("count")) }

    suspend fun deleteStatement(token: String, statementId: String): ApiResult<Unit> =
        request("DELETE", "/api/statements/$statementId", token) { ApiResult.Ok(Unit) }

    // ---- Diagnostics -------------------------------------------------------------

    /**
     * Ship a diagnostic bundle to /api/diag. Wired ahead of the server side
     * landing everywhere: a 404 must surface as "endpoint belum tersedia",
     * so it lights up by itself once the endpoint exists.
     */
    suspend fun uploadDiag(
        token: String,
        bytes: ByteArray,
        filename: String,
    ): ApiResult<Unit> = multipart("/api/diag", token, bytes, filename, "application/zip") {
        ApiResult.Ok(Unit)
    }

    // ---- Account ---------------------------------------------------------------

    suspend fun changePassword(
        token: String,
        currentPassword: String,
        newPassword: String,
    ): ApiResult<Unit> = request(
        "POST",
        "/api/auth/change-password",
        token,
        body = JSONObject()
            .put("currentPassword", currentPassword)
            .put("newPassword", newPassword)
            .toString(),
    ) { ApiResult.Ok(Unit) }

    // ---- Plumbing ----------------------------------------------------------------

    private fun parseRemoteTxn(o: JSONObject): RemoteTxn = RemoteTxn(
        txn = Txn(
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
            relief = if (o.isNull("relief")) null else o.optString("relief").ifBlank { null },
        ),
        receiptId = if (o.isNull("receiptId")) null else o.optString("receiptId").ifBlank { null },
    )

    private fun enc(v: String): String = URLEncoder.encode(v, "UTF-8")

    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(TIMEOUT_MS.toLong(), TimeUnit.MILLISECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .build()
    }

    private val JSON = "application/json".toMediaType()

    private fun get(path: String, token: String): Request = Request.Builder()
        .url("${BuildConfig.SYNC_URL}$path")
        .header("Authorization", "Bearer $token")
        .get()
        .build()

    private suspend fun <T> request(
        method: String,
        path: String,
        token: String,
        body: String? = null,
        parse: (String) -> ApiResult<T>,
    ): ApiResult<T> = withContext(Dispatchers.IO) {
        if (token.isBlank()) return@withContext ApiResult.Fail("no session")
        try {
            val reqBody: RequestBody? = body?.toRequestBody(JSON)
            val req = Request.Builder()
                .url("${BuildConfig.SYNC_URL}$path")
                .header("Authorization", "Bearer $token")
                .method(method, reqBody ?: if (method == "GET") null else ByteArray(0).toRequestBody())
                .build()
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@withContext ApiResult.Http(resp.code)
                parse(resp.body?.string().orEmpty())
            }
        } catch (t: Throwable) {
            ApiResult.Fail(t.javaClass.simpleName)
        }
    }

    private suspend fun <T> multipart(
        path: String,
        token: String,
        bytes: ByteArray,
        filename: String,
        mime: String,
        parse: (String) -> ApiResult<T>,
    ): ApiResult<T> = withContext(Dispatchers.IO) {
        if (token.isBlank()) return@withContext ApiResult.Fail("no session")
        try {
            val fileBody = bytes.toRequestBody(mime.toMediaType())
            val req = Request.Builder()
                .url("${BuildConfig.SYNC_URL}$path")
                .header("Authorization", "Bearer $token")
                .post(
                    MultipartBody.Builder()
                        .setType(MultipartBody.FORM)
                        .addFormDataPart("file", filename.replace("\"", ""), fileBody)
                        .build()
                )
                .build()
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@withContext ApiResult.Http(resp.code)
                parse(resp.body?.string().orEmpty())
            }
        } catch (t: Throwable) {
            ApiResult.Fail(t.javaClass.simpleName)
        }
    }
}

package uk.wakbijok.belanja.llm

import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject

/**
 * Two request writers, five presets, every model host on earth.
 *
 * Model lists are FETCHED, never baked in: a hardcoded list is stale within
 * weeks, and a local Ollama holds whatever that machine happens to have
 * pulled. Fetching also means "Connect" is the real connection test — if the
 * models come back, the URL and the key are both proven at once, so there is
 * no separate "test" button left for the user to forget.
 */
object LlmClient {

    private const val ANTHROPIC_VERSION = "2023-06-01"
    private const val CONNECT_TIMEOUT_S = 10L

    // Reasoning models think before they write; at 4k output tokens a slow
    // provider runs past a minute, and a read timeout surfaces to the user as
    // "unreachable" — a lie about the network. Connect stays tight, read waits.
    private const val READ_TIMEOUT_S = 180L

    private val JSON = "application/json".toMediaType()

    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(CONNECT_TIMEOUT_S, TimeUnit.SECONDS)
            .readTimeout(READ_TIMEOUT_S, TimeUnit.SECONDS)
            .writeTimeout(READ_TIMEOUT_S, TimeUnit.SECONDS)
            .build()
    }

    // ---- model discovery -------------------------------------------------

    /**
     * Lists models, trying the base as given first and a `/v1`-suffixed
     * variant second. That second attempt exists because the single most
     * common BYOK mistake is pasting a provider's root URL when its
     * OpenAI-compatible layer lives at `/v1` (Ollama being the usual case).
     * On success we report which base actually worked so the caller can store
     * the corrected one — the app heals the typo instead of nagging about it.
     */
    suspend fun listModels(wire: Wire, baseUrl: String, key: String): LlmResult<ModelList> =
        withContext(Dispatchers.IO) {
            val bases = candidateBases(wire, baseUrl)
            var last: LlmError = LlmError.Unreachable("no candidate")
            for (base in bases) {
                val started = System.nanoTime()
                when (val r = fetchModels(wire, base, key)) {
                    is LlmResult.Ok -> {
                        val ms = (System.nanoTime() - started) / 1_000_000
                        return@withContext LlmResult.Ok(ModelList(r.value, base, ms))
                    }
                    is LlmResult.Err -> {
                        last = r.error
                        // A wrong key or an unreachable host will not be fixed
                        // by a different path — only a 404 is worth retrying.
                        if (r.error !is LlmError.NoModelList) return@withContext LlmResult.Err(last)
                    }
                }
            }
            LlmResult.Err(last)
        }

    /**
     * OpenAI-compatible endpoints are advertised ending in `/v1`; Anthropic
     * ones are advertised at the root with `/v1/...` in the path. We follow
     * each ecosystem's own convention so the user can paste what their
     * provider documents, then forgive the common slip in either direction.
     */
    internal fun candidateBases(wire: Wire, baseUrl: String): List<String> {
        val base = baseUrl.trim().trimEnd('/')
        if (base.isBlank()) return emptyList()
        return when (wire) {
            Wire.OPENAI -> if (base.endsWith("/v1")) listOf(base) else listOf(base, "$base/v1")
            Wire.ANTHROPIC -> if (base.endsWith("/v1")) listOf(base.removeSuffix("/v1"), base) else listOf(base)
        }
    }

    internal fun modelsUrl(wire: Wire, base: String): String = when (wire) {
        Wire.OPENAI -> "$base/models"
        Wire.ANTHROPIC -> "$base/v1/models"
    }

    private fun fetchModels(wire: Wire, base: String, key: String): LlmResult<List<String>> = try {
        val req = Request.Builder().url(modelsUrl(wire, base)).headers(authHeaders(wire, key)).get().build()
        client.newCall(req).execute().use { resp ->
            val body = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) {
                LlmResult.Err(httpError(resp, body))
            } else {
                val ids = parseModelIds(body)
                if (ids.isEmpty()) {
                    LlmResult.Err(LlmError.BadShape("no models in response"))
                } else {
                    LlmResult.Ok(ids)
                }
            }
        }
    } catch (t: Throwable) {
        LlmResult.Err(LlmError.Unreachable(t.javaClass.simpleName))
    }

    /** Both wire formats answer `{"data":[{"id":…}]}`; some proxies return a bare array. */
    internal fun parseModelIds(body: String): List<String> = runCatching {
        val arr: JSONArray = if (body.trimStart().startsWith("[")) {
            JSONArray(body)
        } else {
            JSONObject(body).optJSONArray("data") ?: JSONArray()
        }
        (0 until arr.length()).mapNotNull { i ->
            when (val el = arr.opt(i)) {
                is JSONObject -> el.optString("id").ifBlank { el.optString("name").ifBlank { null } }
                is String -> el.ifBlank { null }
                else -> null
            }
        }.distinct().sorted()
    }.getOrDefault(emptyList())

    // ---- completion ------------------------------------------------------

    /**
     * One turn, no history. Every Belanja use is a single question about a
     * single fact — keeping it stateless means a failed call costs nothing and
     * can always be retried without reconstructing a conversation.
     */
    suspend fun complete(
        cfg: LlmConfig,
        system: String,
        user: String,
        maxTokens: Int = 512,
        temperature: Double = 0.2,
    ): LlmResult<LlmReply> = withContext(Dispatchers.IO) {
        val base = cfg.baseUrl.trim().trimEnd('/')
        val url = when (cfg.wire) {
            Wire.OPENAI -> "$base/chat/completions"
            Wire.ANTHROPIC -> "$base/v1/messages"
        }
        val payload = when (cfg.wire) {
            Wire.OPENAI -> JSONObject().apply {
                put("model", cfg.model)
                put("max_tokens", maxTokens)
                put("temperature", temperature)
                put("messages", JSONArray().apply {
                    put(JSONObject().put("role", "system").put("content", system))
                    put(JSONObject().put("role", "user").put("content", user))
                })
            }
            Wire.ANTHROPIC -> JSONObject().apply {
                put("model", cfg.model)
                put("max_tokens", maxTokens)
                put("temperature", temperature)
                put("system", system)
                put("messages", JSONArray().apply {
                    put(JSONObject().put("role", "user").put("content", user))
                })
            }
        }
        try {
            val req = Request.Builder()
                .url(url)
                .headers(authHeaders(cfg.wire, cfg.key))
                .post(payload.toString().toRequestBody(JSON))
                .build()
            client.newCall(req).execute().use { resp ->
                val body = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) return@withContext LlmResult.Err(httpError(resp, body))
                parseReply(cfg.wire, body)
            }
        } catch (t: Throwable) {
            LlmResult.Err(LlmError.Unreachable(t.javaClass.simpleName))
        }
    }

    internal fun parseReply(wire: Wire, body: String): LlmResult<LlmReply> = runCatching {
        val o = JSONObject(body)
        when (wire) {
            Wire.OPENAI -> {
                val choice = o.getJSONArray("choices").getJSONObject(0)
                val msg = choice.getJSONObject("message")
                val usage = o.optJSONObject("usage")
                // Reasoning models (GLM, DeepSeek, Qwen) spend the token budget
                // on `reasoning_content` first and only then write `content`.
                // Too small a max_tokens leaves content empty with
                // finish_reason "length" — indistinguishable from a broken
                // provider unless we say so out loud.
                val text = msg.optString("content").trim()
                LlmReply(
                    text = text,
                    tokensIn = usage?.optInt("prompt_tokens") ?: 0,
                    tokensOut = usage?.optInt("completion_tokens") ?: 0,
                    finishReason = choice.optString("finish_reason").ifBlank { null },
                    truncatedThinking = text.isBlank() &&
                        msg.optString("reasoning_content").isNotBlank(),
                )
            }
            Wire.ANTHROPIC -> {
                val blocks = o.getJSONArray("content")
                val text = (0 until blocks.length())
                    .mapNotNull { blocks.optJSONObject(it)?.optString("text") }
                    .joinToString("").trim()
                val usage = o.optJSONObject("usage")
                LlmReply(
                    text = text,
                    tokensIn = usage?.optInt("input_tokens") ?: 0,
                    tokensOut = usage?.optInt("output_tokens") ?: 0,
                    finishReason = o.optString("stop_reason").ifBlank { null },
                )
            }
        }
    }.fold(
        onSuccess = {
            when {
                it.text.isNotBlank() -> LlmResult.Ok(it)
                // Name the actual cause so the fix is obvious: raise the limit,
                // not "change provider".
                it.truncatedThinking || it.finishReason == "length" ->
                    LlmResult.Err(LlmError.ThinkingRanOut(it.tokensOut))
                else -> LlmResult.Err(LlmError.BadShape("empty reply"))
            }
        },
        onFailure = { LlmResult.Err(LlmError.BadShape(it.javaClass.simpleName)) },
    )

    // ---- shared ----------------------------------------------------------

    internal fun authHeaders(wire: Wire, key: String) = okhttp3.Headers.Builder().apply {
        add("Accept", "application/json")
        when (wire) {
            // Blank is legitimate: a local Ollama checks no key at all.
            Wire.OPENAI -> if (key.isNotBlank()) add("Authorization", "Bearer $key")
            Wire.ANTHROPIC -> {
                if (key.isNotBlank()) add("x-api-key", key)
                // Required by Anthropic even on GET /v1/models — omitting it
                // reads as a malformed request, not as a missing key.
                add("anthropic-version", ANTHROPIC_VERSION)
            }
        }
    }.build()

    private fun httpError(resp: Response, body: String): LlmError = when (resp.code) {
        401, 403 -> LlmError.Unauthorized(resp.code)
        404 -> LlmError.NoModelList
        else -> LlmError.Http(resp.code, providerMessage(body) ?: resp.message)
    }

    /** Both formats nest a human-readable reason under `error.message`. */
    private fun providerMessage(body: String): String? = runCatching {
        JSONObject(body).optJSONObject("error")?.optString("message")?.ifBlank { null }
    }.getOrNull()
}

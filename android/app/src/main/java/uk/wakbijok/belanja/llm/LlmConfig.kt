package uk.wakbijok.belanja.llm

/**
 * BYOK: the app ships with no brain and no key. The user brings one.
 *
 * The whole field collapses to TWO wire formats — every model host worth
 * naming speaks one of them. Named providers below are presets that fill in a
 * base URL; they are not separate code paths. That is why Kimi, GLM, DeepSeek,
 * OpenRouter, LM Studio and a local Ollama all work without a line added.
 */
enum class Wire(val id: String) {
    OPENAI("openai"),
    ANTHROPIC("anthropic");

    companion object {
        fun from(id: String?): Wire = entries.firstOrNull { it.id == id } ?: OPENAI
    }
}

/**
 * A preset is a display name + wire + optional base URL. A blank [base] means
 * "compatible" — the user supplies the endpoint themselves.
 */
data class Preset(
    val id: String,
    val label: String,
    val wire: Wire,
    val base: String,
) {
    val bringYourOwnUrl: Boolean get() = base.isBlank()

    companion object {
        /**
         * Order matters — direct providers first, then the two compatible
         * forms that cover everything else.
         */
        val ALL = listOf(
            Preset("openai", "OpenAI", Wire.OPENAI, "https://api.openai.com/v1"),
            Preset("anthropic", "Anthropic", Wire.ANTHROPIC, "https://api.anthropic.com"),
            Preset("grok", "Grok", Wire.OPENAI, "https://api.x.ai/v1"),
            Preset("openai_compat", "Serasi OpenAI", Wire.OPENAI, ""),
            Preset("anthropic_compat", "Serasi Anthropic", Wire.ANTHROPIC, ""),
        )

        fun byId(id: String?): Preset = ALL.firstOrNull { it.id == id } ?: ALL[0]
    }
}

/**
 * What we persist. [key] is held in plaintext only in memory — at rest it is
 * sealed by [KeyVault] under an AndroidKeyStore key that never leaves the
 * device and is never written to the SAF backup tree.
 */
data class LlmConfig(
    val presetId: String,
    val wire: Wire,
    val baseUrl: String,
    val key: String,
    val model: String,
) {
    val configured: Boolean get() = baseUrl.isNotBlank() && model.isNotBlank()
}

/**
 * Failures are named, never swallowed. The Cukai tab taught us that a spinner
 * which never resolves is worse than an error: each case below maps to a
 * different fix, so the UI must be able to tell them apart.
 */
sealed interface LlmError {
    /** Endpoint answered, so the URL is right — it just won't take this key. */
    data class Unauthorized(val code: Int) : LlmError

    /** Alive but serves no model list. Some proxies skip it; typing an ID still works. */
    data object NoModelList : LlmError

    /** Any other HTTP status, reported with its code rather than hidden. */
    data class Http(val code: Int, val detail: String) : LlmError

    /** Never reached it at all — DNS, timeout, no route. Usually the VPN. */
    data class Unreachable(val reason: String) : LlmError

    /** Reached and answered, but not in a shape we understand. */
    data class BadShape(val reason: String) : LlmError

    /**
     * A reasoning model burned the whole token budget on thinking and wrote no
     * answer. Not a provider fault and not a key fault — the limit is too low.
     */
    data class ThinkingRanOut(val tokensSpent: Int) : LlmError
}

sealed interface LlmResult<out T> {
    data class Ok<T>(val value: T) : LlmResult<T>
    data class Err(val error: LlmError) : LlmResult<Nothing>
}

/** A successful model listing, plus the base URL that actually worked. */
data class ModelList(val models: List<String>, val resolvedBase: String, val latencyMs: Long)

/** One completion, with the token counts the provider reported (0 if absent). */
data class LlmReply(
    val text: String,
    val tokensIn: Int,
    val tokensOut: Int,
    /** "stop" / "length" / provider-specific; null when not reported. */
    val finishReason: String? = null,
    /** A reasoning model spent the whole budget thinking and never answered. */
    val truncatedThinking: Boolean = false,
)

package uk.wakbijok.belanja.llm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The two wire formats are the whole BYOK bet: get these wrong and Kimi, GLM,
 * Ollama and OpenRouter all break at once, in ways that look like the user's
 * key is bad. Pure logic only — no network, no Android.
 */
class LlmClientTest {

    // ---- base URL resolution --------------------------------------------

    @Test
    fun `openai base already ending in v1 is tried once`() {
        assertEquals(
            listOf("https://api.x.ai/v1"),
            LlmClient.candidateBases(Wire.OPENAI, "https://api.x.ai/v1"),
        )
    }

    @Test
    fun `openai base missing v1 falls back to the suffixed form`() {
        // The single most common BYOK mistake: pasting an Ollama root URL.
        assertEquals(
            listOf("http://127.0.0.1:11434", "http://127.0.0.1:11434/v1"),
            LlmClient.candidateBases(Wire.OPENAI, "http://127.0.0.1:11434"),
        )
    }

    @Test
    fun `trailing slashes and spaces never produce a double slash`() {
        assertEquals(
            listOf("https://api.openai.com/v1"),
            LlmClient.candidateBases(Wire.OPENAI, "  https://api.openai.com/v1/  "),
        )
    }

    @Test
    fun `anthropic base is the root because the version sits in the path`() {
        assertEquals(
            listOf("https://api.anthropic.com"),
            LlmClient.candidateBases(Wire.ANTHROPIC, "https://api.anthropic.com"),
        )
        assertEquals("https://api.anthropic.com/v1/models", LlmClient.modelsUrl(Wire.ANTHROPIC, "https://api.anthropic.com"))
    }

    @Test
    fun `anthropic base wrongly given with v1 is retried without it`() {
        // Would otherwise request /v1/v1/models and 404 with no explanation.
        assertEquals(
            listOf("https://proxy.example.com", "https://proxy.example.com/v1"),
            LlmClient.candidateBases(Wire.ANTHROPIC, "https://proxy.example.com/v1"),
        )
    }

    @Test
    fun `blank base yields no candidates rather than a request to nowhere`() {
        assertTrue(LlmClient.candidateBases(Wire.OPENAI, "   ").isEmpty())
    }

    // ---- model list parsing ---------------------------------------------

    @Test
    fun `parses the standard data-id envelope both formats use`() {
        val body = """{"object":"list","data":[{"id":"gpt-5"},{"id":"gpt-4o"}]}"""
        assertEquals(listOf("gpt-4o", "gpt-5"), LlmClient.parseModelIds(body))
    }

    @Test
    fun `parses a bare array as some proxies return`() {
        assertEquals(listOf("a", "b"), LlmClient.parseModelIds("""["b","a"]"""))
    }

    @Test
    fun `falls back to name when a host omits id`() {
        // Ollama's native shape uses "name"; its /v1 layer uses "id".
        assertEquals(listOf("llama3:8b"), LlmClient.parseModelIds("""{"data":[{"name":"llama3:8b"}]}"""))
    }

    @Test
    fun `duplicates collapse and order is stable`() {
        val body = """{"data":[{"id":"z"},{"id":"a"},{"id":"z"}]}"""
        assertEquals(listOf("a", "z"), LlmClient.parseModelIds(body))
    }

    @Test
    fun `garbage never throws it just yields nothing`() {
        assertTrue(LlmClient.parseModelIds("<html>not json</html>").isEmpty())
        assertTrue(LlmClient.parseModelIds("").isEmpty())
    }

    // ---- reply parsing ---------------------------------------------------

    @Test
    fun `openai reply yields text and token counts`() {
        val body = """
            {"choices":[{"message":{"role":"assistant","content":" Pengangkutan "}}],
             "usage":{"prompt_tokens":41,"completion_tokens":3}}
        """.trimIndent()
        val r = LlmClient.parseReply(Wire.OPENAI, body)
        assertTrue(r is LlmResult.Ok)
        val v = (r as LlmResult.Ok).value
        assertEquals("Pengangkutan", v.text)
        assertEquals(41, v.tokensIn)
        assertEquals(3, v.tokensOut)
    }

    @Test
    fun `anthropic reply concatenates content blocks`() {
        val body = """
            {"content":[{"type":"text","text":"Peng"},{"type":"text","text":"angkutan"}],
             "usage":{"input_tokens":41,"output_tokens":3}}
        """.trimIndent()
        val r = LlmClient.parseReply(Wire.ANTHROPIC, body)
        assertEquals("Pengangkutan", (r as LlmResult.Ok).value.text)
        assertEquals(41, r.value.tokensIn)
    }

    @Test
    fun `missing usage counts as zero rather than failing the call`() {
        val body = """{"choices":[{"message":{"content":"ok"}}]}"""
        val v = (LlmClient.parseReply(Wire.OPENAI, body) as LlmResult.Ok).value
        assertEquals(0, v.tokensIn)
        assertEquals(0, v.tokensOut)
    }

    @Test
    fun `an empty reply is an error not a silent blank category`() {
        // Left unguarded this would stamp "" onto a transaction as its category.
        val r = LlmClient.parseReply(Wire.OPENAI, """{"choices":[{"message":{"content":"  "}}]}""")
        assertTrue(r is LlmResult.Err && r.error is LlmError.BadShape)
    }

    @Test
    fun `a reply in the wrong shape is reported not thrown`() {
        val r = LlmClient.parseReply(Wire.OPENAI, """{"unexpected":true}""")
        assertTrue(r is LlmResult.Err && r.error is LlmError.BadShape)
    }

    // ---- auth headers ----------------------------------------------------

    @Test
    fun `openai sends bearer and nothing anthropic-specific`() {
        val h = LlmClient.authHeaders(Wire.OPENAI, "sk-test")
        assertEquals("Bearer sk-test", h["Authorization"])
        assertEquals(null, h["x-api-key"])
    }

    @Test
    fun `anthropic always sends the version header even without a key`() {
        // Omitting it reads as a malformed request, not a missing key — which
        // would send the user hunting for a key problem they do not have.
        val h = LlmClient.authHeaders(Wire.ANTHROPIC, "")
        assertEquals("2023-06-01", h["anthropic-version"])
        assertEquals(null, h["x-api-key"])
    }

    @Test
    fun `a blank key sends no auth header at all for a local ollama`() {
        val h = LlmClient.authHeaders(Wire.OPENAI, "")
        assertEquals(null, h["Authorization"])
    }

    // ---- preset table ----------------------------------------------------

    @Test
    fun `grok is the openai form not a third code path`() {
        val grok = Preset.byId("grok")
        assertEquals(Wire.OPENAI, grok.wire)
        assertEquals("https://api.x.ai/v1", grok.base)
    }

    @Test
    fun `the two compatible presets are the ones asking for a url`() {
        assertEquals(
            listOf("openai_compat", "anthropic_compat"),
            Preset.ALL.filter { it.bringYourOwnUrl }.map { it.id },
        )
    }

    @Test
    fun `an unknown preset id degrades to the first entry rather than crashing`() {
        assertEquals(Preset.ALL[0].id, Preset.byId("was-removed-in-an-update").id)
    }
}

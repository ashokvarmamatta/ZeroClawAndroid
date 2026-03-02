package ai.zeroclaw.android.data

import android.content.Context
import ai.zeroclaw.android.service.ZeroClawService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class LlmRouter(private val context: Context) {

    private val keyManager = LlmKeyManager.getInstance(context)
    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    companion object {
        const val SYSTEM_PROMPT = "You are ZeroClaw, a helpful AI assistant. Be concise and friendly."
        const val MAX_TOKENS = 1000

        @Volatile private var INSTANCE: LlmRouter? = null
        fun getInstance(context: Context): LlmRouter {
            return INSTANCE ?: synchronized(this) {
                LlmRouter(context.applicationContext).also { INSTANCE = it }
            }
        }
    }

    // ── Sealed result type so callers know WHY it failed ─────────────────────

    sealed class LlmResult {
        data class Success(val text: String) : LlmResult()
        data class RateLimit(val message: String) : LlmResult()  // 429 — key valid, quota hit
        data class Failure(val message: String) : LlmResult()    // auth/network error
    }

    // ── Main entry point — waterfall failover ─────────────────────────────────

    suspend fun call(userMessage: String): String {
        val allKeys = keyManager.loadKeys().filter { it.enabled }
        if (allKeys.isEmpty()) {
            return "⚠️ No API keys configured. Open Settings → Manage API Keys to add one."
        }

        var lastRateLimitMsg = ""

        for (entry in allKeys) {
            if (keyManager.allExhausted()) break
            val usable = keyManager.nextUsableKey() ?: break

            ZeroClawService.log("LLM: trying ${usable.safeLabel} (${usable.safeProvider})")

            val result = runCatching { dispatchToProvider(userMessage, usable) }

            when {
                result.isSuccess -> {
                    val reply = result.getOrNull() ?: ""
                    if (reply.isNotBlank()) {
                        ZeroClawService.log("LLM: ✓ reply from ${usable.safeLabel}")
                        return reply
                    }
                    // Blank response — treat as failure
                    keyManager.markFailed(usable.id)
                }
                else -> {
                    val ex = result.exceptionOrNull()
                    val msg = ex?.message ?: "unknown error"

                    // 429 = quota/rate-limit — key is VALID, just exhausted for now
                    // Don't permanently mark it failed — try next but remember message
                    if (msg.contains("429") || msg.lowercase().contains("quota") ||
                        msg.lowercase().contains("rate") || msg.lowercase().contains("exceeded")) {
                        ZeroClawService.log("LLM: ⚠ ${usable.safeLabel} rate-limited (quota) — trying next")
                        lastRateLimitMsg = msg
                        keyManager.markFailed(usable.id) // skip this key this session only
                    } else {
                        ZeroClawService.log("LLM: ✗ ${usable.safeLabel} failed — $msg")
                        keyManager.markFailed(usable.id)
                    }
                }
            }
        }

        val failedCount = keyManager.failedCount()
        val totalCount = allKeys.size
        return if (lastRateLimitMsg.isNotBlank()) {
            "⚠️ All keys hit quota/rate-limits ($failedCount/$totalCount). " +
            "This means your keys ARE valid but you've exceeded the free tier. " +
            "Try again in a minute or add a paid-tier key."
        } else {
            "⚠️ All $failedCount/$totalCount API keys failed. " +
            "Check your keys in Settings → Manage API Keys, then restart the service."
        }
    }

    // ── Validate a single key (used when user saves in dialog) ───────────────

    suspend fun validateKey(entry: ApiKeyEntry): ValidationResult {
        return try {
            when (entry.safeProvider) {
                "gemini"    -> validateGemini(entry.safeApiKey)
                "anthropic" -> validateAnthropic(entry.safeApiKey)
                "ollama"    -> validateOllama()
                else        -> validateOpenAICompatible(entry.safeApiKey, entry.safeProvider, entry.safeBaseUrl, entry.safePreferredModel)
            }
        } catch (e: Exception) {
            ValidationResult(false, "Network error: ${e.message}")
        }
    }

    // ── Model listing helpers ─────────────────────────────────────────────────

    /** Fetch model list from any OpenAI-compatible /v1/models endpoint */
    private suspend fun listOpenAIModels(apiKey: String, provider: String, baseUrl: String): List<String> {
        val defaultBase = if (provider == "openrouter") "https://openrouter.ai/api/v1" else "https://api.openai.com/v1"
        val resolvedBase = baseUrl.ifBlank { defaultBase }.trimEnd('/')
        val req = Request.Builder()
            .url("$resolvedBase/models")
            .addHeader("Authorization", "Bearer $apiKey")
            .addHeader("Content-Type", "application/json")
            .get().build()
        return withContext(Dispatchers.IO) {
            try {
                client.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) return@withContext emptyList()
                    val json = runCatching { JSONObject(resp.body?.string() ?: "") }.getOrNull()
                        ?: return@withContext emptyList()
                    val arr = json.optJSONArray("data") ?: return@withContext emptyList()
                    val list = mutableListOf<String>()
                    for (i in 0 until arr.length()) {
                        val id = arr.getJSONObject(i).optString("id", "")
                        if (id.isNotBlank()) list.add(id)
                    }
                    // Sort: newest/best first — GPT-4 family first, then gpt-3.5, then others
                    list.sortedWith(compareBy(
                        { !it.contains("gpt-4o") },
                        { !it.contains("gpt-4") },
                        { !it.contains("gpt-3.5") },
                        { it }
                    ))
                }
            } catch (e: Exception) { emptyList() }
        }
    }

    /** Curated Anthropic model list — Anthropic has no public /models endpoint */
    private fun listAnthropicModels(): List<String> = listOf(
        "claude-opus-4-6",
        "claude-sonnet-4-6",
        "claude-haiku-4-5-20251001",
        "claude-3-5-sonnet-20241022",
        "claude-3-5-haiku-20241022",
        "claude-3-opus-20240229",
        "claude-3-haiku-20240307"
    )

    // ── OpenRouter rich model catalog ─────────────────────────────────────────

    data class OpenRouterModel(
        val id: String,
        val name: String,
        val contextLength: Int,
        val promptPricePer1M: Double,   // USD per 1M prompt tokens
        val completionPricePer1M: Double // USD per 1M completion tokens
    ) {
        val isFree: Boolean get() = promptPricePer1M == 0.0 && completionPricePer1M == 0.0
        val priceLabel: String get() = when {
            isFree -> "FREE"
            promptPricePer1M < 1.0 -> "<\$1/M"
            else -> "\$${String.format("%.0f", promptPricePer1M)}/M"
        }
        val contextLabel: String get() = when {
            contextLength >= 1_000_000 -> "${contextLength / 1_000_000}M ctx"
            contextLength >= 1_000     -> "${contextLength / 1_000}K ctx"
            else                       -> "$contextLength ctx"
        }
    }

    /** Fetch full OpenRouter model catalog with pricing + context info */
    suspend fun listOpenRouterModels(apiKey: String): List<OpenRouterModel> {
        val req = Request.Builder()
            .url("https://openrouter.ai/api/v1/models")
            .addHeader("Authorization", "Bearer $apiKey")
            .addHeader("Content-Type", "application/json")
            .get().build()
        return withContext(Dispatchers.IO) {
            try {
                client.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) return@withContext emptyList()
                    val json = runCatching { JSONObject(resp.body?.string() ?: "") }.getOrNull()
                        ?: return@withContext emptyList()
                    val arr = json.optJSONArray("data") ?: return@withContext emptyList()
                    val list = mutableListOf<OpenRouterModel>()
                    for (i in 0 until arr.length()) {
                        val m = arr.getJSONObject(i)
                        val id = m.optString("id", "")
                        if (id.isBlank()) continue
                        val name = m.optString("name", id)
                        val ctx = m.optInt("context_length", 0)
                        val pricing = m.optJSONObject("pricing")
                        val prompt = pricing?.optString("prompt", "0")?.toDoubleOrNull() ?: 0.0
                        val completion = pricing?.optString("completion", "0")?.toDoubleOrNull() ?: 0.0
                        // OpenRouter prices are per-token; convert to per-1M
                        list.add(OpenRouterModel(
                            id = id,
                            name = name,
                            contextLength = ctx,
                            promptPricePer1M = prompt * 1_000_000,
                            completionPricePer1M = completion * 1_000_000
                        ))
                    }
                    // Sort: free first, then by name
                    list.sortedWith(compareBy({ !it.isFree }, { it.name.lowercase() }))
                }
            } catch (e: Exception) { emptyList() }
        }
    }

    /** Fetch local Ollama models from /api/tags */
    private suspend fun listOllamaModels(): List<String> {
        val req = Request.Builder().url("http://127.0.0.1:11434/api/tags").get().build()
        return withContext(Dispatchers.IO) {
            try {
                client.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) return@withContext emptyList()
                    val json = runCatching { JSONObject(resp.body?.string() ?: "") }.getOrNull()
                        ?: return@withContext emptyList()
                    val arr = json.optJSONArray("models") ?: return@withContext emptyList()
                    val list = mutableListOf<String>()
                    for (i in 0 until arr.length()) {
                        val name = arr.getJSONObject(i).optString("name", "")
                        if (name.isNotBlank()) list.add(name)
                    }
                    list.sorted()
                }
            } catch (e: Exception) { emptyList() }
        }
    }

    /** Test using a raw cURL-style call to a custom endpoint */
    suspend fun validateKeyWithCurl(bearerToken: String, baseUrl: String, model: String): ValidationResult {
        return try {
            val cleanBase = baseUrl.trimEnd('/')
            val url = "$cleanBase/chat/completions"
            val body = JSONObject().apply {
                put("model", model)
                put("max_tokens", 5)
                put("messages", JSONArray().apply {
                    put(JSONObject().apply { put("role", "user"); put("content", "hi") })
                })
            }.toString()
            val req = Request.Builder()
                .url(url)
                .addHeader("Authorization", "Bearer $bearerToken")
                .addHeader("Content-Type", "application/json")
                .post(body.toRequestBody())
                .build()
            withContext(Dispatchers.IO) {
                client.newCall(req).execute().use { resp ->
                    val respBody = resp.body?.string() ?: ""
                    if (resp.isSuccessful) {
                        val json = runCatching { JSONObject(respBody) }.getOrNull()
                        val modelUsed = json?.optJSONArray("choices")
                            ?.optJSONObject(0)?.optJSONObject("message")?.optString("content", "")
                        ValidationResult(true, "✅ cURL test passed — endpoint responded successfully")
                    } else {
                        val json = runCatching { JSONObject(respBody) }.getOrNull()
                        val msg = json?.optJSONObject("error")?.optString("message") ?: "HTTP ${resp.code}"
                        ValidationResult(false, "❌ $msg")
                    }
                }
            }
        } catch (e: Exception) {
            ValidationResult(false, "Network error: ${e.message}")
        }
    }

    data class ValidationResult(
        val isValid: Boolean,
        val message: String,
        val availableModels: List<String> = emptyList()
    )

    private suspend fun validateGemini(apiKey: String): ValidationResult {
        // Step 1: List models (also proves key is valid)
        val modelsUrl = "https://generativelanguage.googleapis.com/v1beta/models?key=$apiKey"
        val modelsReq = Request.Builder().url(modelsUrl).get().build()

        val models = withContext(Dispatchers.IO) {
            client.newCall(modelsReq).execute().use { resp ->
                val body = resp.body?.string() ?: return@withContext null
                val json = JSONObject(body)
                if (!resp.isSuccessful) {
                    val errMsg = json.optJSONObject("error")?.optString("message") ?: "HTTP ${resp.code}"
                    val code = json.optJSONObject("error")?.optInt("code") ?: resp.code
                    throw Exception("[$code] $errMsg")
                }
                val arr = json.optJSONArray("models") ?: return@withContext emptyList<String>()
                val list = mutableListOf<String>()
                for (i in 0 until arr.length()) {
                    val m = arr.getJSONObject(i)
                    val methods = m.optJSONArray("supportedGenerationMethods")
                    var supportsGenerate = false
                    if (methods != null) {
                        for (j in 0 until methods.length()) {
                            if (methods.getString(j) == "generateContent") { supportsGenerate = true; break }
                        }
                    }
                    if (supportsGenerate) {
                        val name = m.optString("name", "").removePrefix("models/")
                        val display = m.optString("displayName", name)
                        if (name.isNotBlank()) list.add("$name ($display)")
                    }
                }
                list
            }
        }

        return if (models != null) {
            val best = models.firstOrNull { it.contains("2.5-flash") }
                ?: models.firstOrNull { it.contains("2.0-flash") }
                ?: models.firstOrNull { it.contains("1.5-flash") }
            val bestClean = best?.substringBefore(" (") ?: ""
            val msg = if (bestClean.isNotBlank())
                "✅ Valid Gemini key — ${models.size} models available. Recommended: $bestClean"
            else
                "✅ Valid Gemini key — ${models.size} models available"
            ValidationResult(true, msg, models)
        } else {
            ValidationResult(false, "Failed to connect to Gemini API")
        }
    }

    private suspend fun validateOpenAICompatible(apiKey: String, provider: String, baseUrl: String = "", preferredModel: String = ""): ValidationResult {
        val defaultBase = if (provider == "openrouter") "https://openrouter.ai/api/v1" else "https://api.openai.com/v1"
        val resolvedBase = baseUrl.ifBlank { defaultBase }.trimEnd('/')
        val testModel = when {
            preferredModel.isNotBlank() -> preferredModel
            provider == "openrouter"    -> "openai/gpt-4o-mini"
            else                        -> "gpt-4o-mini"
        }
        val body = JSONObject().apply {
            put("model", testModel)
            put("max_tokens", 5)
            put("messages", JSONArray().apply {
                put(JSONObject().apply { put("role","user"); put("content","hi") })
            })
        }.toString()
        val req = Request.Builder()
            .url("$resolvedBase/chat/completions")
            .addHeader("Authorization", "Bearer $apiKey")
            .addHeader("Content-Type","application/json")
            .post(body.toRequestBody())
            .build()
        return withContext(Dispatchers.IO) {
            client.newCall(req).execute().use { resp ->
                val respBody = resp.body?.string() ?: ""
                if (resp.isSuccessful) {
                    val label = when {
                        baseUrl.isNotBlank() -> "custom endpoint ($resolvedBase)"
                        provider == "openrouter" -> "OpenRouter"
                        else -> "OpenAI"
                    }
                    // Fetch full model list in parallel after confirming key is valid
                    val models = listOpenAIModels(apiKey, provider, baseUrl)
                    val msg = if (models.isNotEmpty())
                        "✅ Valid $label key — ${models.size} models available"
                    else
                        "✅ Valid $label key"
                    ValidationResult(true, msg, models)
                } else {
                    val json = runCatching { JSONObject(respBody) }.getOrNull()
                    val msg = json?.optJSONObject("error")?.optString("message") ?: "HTTP ${resp.code}"
                    ValidationResult(false, "❌ $msg")
                }
            }
        }
    }

    private suspend fun validateAnthropic(apiKey: String): ValidationResult {
        val body = JSONObject().apply {
            put("model","claude-haiku-4-5-20251001")
            put("max_tokens", 5)
            put("messages", JSONArray().apply {
                put(JSONObject().apply { put("role","user"); put("content","hi") })
            })
        }.toString()
        val req = Request.Builder()
            .url("https://api.anthropic.com/v1/messages")
            .addHeader("x-api-key", apiKey)
            .addHeader("anthropic-version","2023-06-01")
            .addHeader("Content-Type","application/json")
            .post(body.toRequestBody())
            .build()
        return withContext(Dispatchers.IO) {
            client.newCall(req).execute().use { resp ->
                if (resp.isSuccessful) {
                    val models = listAnthropicModels()
                    ValidationResult(true, "✅ Valid Anthropic key — ${models.size} models available", models)
                } else {
                    val json = runCatching { JSONObject(resp.body?.string() ?: "") }.getOrNull()
                    val msg = json?.optJSONObject("error")?.optString("message") ?: "HTTP ${resp.code}"
                    ValidationResult(false, "❌ $msg")
                }
            }
        }
    }

    private suspend fun validateOllama(): ValidationResult {
        val req = Request.Builder().url("http://127.0.0.1:11434/api/tags").get().build()
        return withContext(Dispatchers.IO) {
            try {
                client.newCall(req).execute().use { resp ->
                    if (resp.isSuccessful) {
                        val models = listOllamaModels()
                        val msg = if (models.isNotEmpty())
                            "✅ Ollama running locally — ${models.size} models installed"
                        else
                            "✅ Ollama running locally (no models installed yet)"
                        ValidationResult(true, msg, models)
                    } else {
                        ValidationResult(false, "❌ Ollama not responding (HTTP ${resp.code})")
                    }
                }
            } catch (e: Exception) {
                ValidationResult(false, "❌ Ollama not found — is it running on this device?")
            }
        }
    }

    // ── Provider dispatch (actual message sending) ────────────────────────────

    private suspend fun dispatchToProvider(message: String, entry: ApiKeyEntry): String {
        return when (entry.safeProvider) {
            "anthropic" -> callAnthropic(message, entry.safeApiKey, entry.safePreferredModel)
            "gemini"    -> callGemini(message, entry.safeApiKey, entry.safePreferredModel)
            "ollama"    -> callOllama(message, entry.safePreferredModel)
            else        -> callOpenAICompatible(message, entry.safeApiKey, entry.safeProvider, entry.safeBaseUrl, entry.safePreferredModel)
        }
    }

    private suspend fun callOpenAICompatible(message: String, apiKey: String, provider: String, baseUrl: String = "", preferredModel: String = ""): String {
        val defaultBase = if (provider == "openrouter") "https://openrouter.ai/api/v1" else "https://api.openai.com/v1"
        val resolvedBase = baseUrl.ifBlank { defaultBase }.trimEnd('/')
        val model = when {
            preferredModel.isNotBlank() -> preferredModel
            provider == "openrouter"    -> "openai/gpt-4o-mini"
            else                        -> "gpt-4o-mini"
        }
        val body = JSONObject().apply {
            put("model", model); put("max_tokens", MAX_TOKENS)
            put("messages", JSONArray().apply {
                put(JSONObject().apply { put("role","system"); put("content", SYSTEM_PROMPT) })
                put(JSONObject().apply { put("role","user");   put("content", message) })
            })
        }.toString()
        val req = Request.Builder()
            .url("$resolvedBase/chat/completions")
            .addHeader("Authorization","Bearer $apiKey")
            .addHeader("Content-Type","application/json")
            .apply { if (provider == "openrouter") addHeader("HTTP-Referer","https://zeroclaw.ai") }
            .post(body.toRequestBody()).build()
        return withContext(Dispatchers.IO) {
            client.newCall(req).execute().use { resp ->
                val json = JSONObject(resp.body?.string() ?: throw Exception("Empty body"))
                if (!resp.isSuccessful) throw Exception("[${resp.code}] ${json.optJSONObject("error")?.optString("message") ?: "HTTP ${resp.code}"}")
                json.getJSONArray("choices").getJSONObject(0).getJSONObject("message").getString("content").trim()
            }
        }
    }

    private suspend fun callAnthropic(message: String, apiKey: String, preferredModel: String = ""): String {
        val model = preferredModel.ifBlank { "claude-haiku-4-5-20251001" }
        val body = JSONObject().apply {
            put("model", model); put("max_tokens", MAX_TOKENS)
            put("system", SYSTEM_PROMPT)
            put("messages", JSONArray().apply {
                put(JSONObject().apply { put("role","user"); put("content", message) })
            })
        }.toString()
        val req = Request.Builder()
            .url("https://api.anthropic.com/v1/messages")
            .addHeader("x-api-key", apiKey).addHeader("anthropic-version","2023-06-01")
            .addHeader("Content-Type","application/json").post(body.toRequestBody()).build()
        return withContext(Dispatchers.IO) {
            client.newCall(req).execute().use { resp ->
                val json = JSONObject(resp.body?.string() ?: throw Exception("Empty body"))
                if (!resp.isSuccessful) throw Exception("[${resp.code}] ${json.optJSONObject("error")?.optString("message") ?: "HTTP ${resp.code}"}")
                json.getJSONArray("content").getJSONObject(0).getString("text").trim()
            }
        }
    }

    private suspend fun callGemini(message: String, apiKey: String, preferredModel: String = ""): String {
        // Use the user's chosen model, fall back to 2.5-flash → 1.5-flash
        val model = preferredModel.ifBlank { "gemini-2.5-flash-preview-04-17" }
            .let { if (it.isBlank()) "gemini-1.5-flash" else it }
        val url = "https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent?key=$apiKey"
        val body = JSONObject().apply {
            put("contents", JSONArray().apply {
                put(JSONObject().apply {
                    put("role","user")
                    put("parts", JSONArray().apply { put(JSONObject().apply { put("text", message) }) })
                })
            })
            put("systemInstruction", JSONObject().apply {
                put("parts", JSONArray().apply { put(JSONObject().apply { put("text", SYSTEM_PROMPT) }) })
            })
            put("generationConfig", JSONObject().apply {
                put("maxOutputTokens", MAX_TOKENS); put("temperature", 0.7)
            })
        }.toString()
        val req = Request.Builder().url(url).addHeader("Content-Type","application/json")
            .post(body.toRequestBody()).build()
        return withContext(Dispatchers.IO) {
            client.newCall(req).execute().use { resp ->
                val respBody = resp.body?.string() ?: throw Exception("Empty body")
                val json = JSONObject(respBody)
                if (!resp.isSuccessful) {
                    val code = resp.code
                    val errMsg = json.optJSONObject("error")?.optString("message") ?: "HTTP $code"
                    throw Exception("[$code] $errMsg")
                }
                json.getJSONArray("candidates")
                    .getJSONObject(0).getJSONObject("content")
                    .getJSONArray("parts").getJSONObject(0).getString("text").trim()
            }
        }
    }

    private suspend fun callOllama(message: String, preferredModel: String = ""): String {
        val model = preferredModel.ifBlank { "llama3.2" }
        val body = JSONObject().apply {
            put("model", model); put("stream", false)
            put("messages", JSONArray().apply {
                put(JSONObject().apply { put("role","system"); put("content", SYSTEM_PROMPT) })
                put(JSONObject().apply { put("role","user");   put("content", message) })
            })
        }.toString()
        val req = Request.Builder().url("http://127.0.0.1:11434/api/chat")
            .addHeader("Content-Type","application/json").post(body.toRequestBody()).build()
        return withContext(Dispatchers.IO) {
            client.newCall(req).execute().use { resp ->
                val json = JSONObject(resp.body?.string() ?: throw Exception("Empty body"))
                if (!resp.isSuccessful) throw Exception("HTTP ${resp.code}")
                json.getJSONObject("message").getString("content").trim()
            }
        }
    }

    private fun String.toRequestBody(): RequestBody =
        toRequestBody("application/json; charset=utf-8".toMediaType())
}

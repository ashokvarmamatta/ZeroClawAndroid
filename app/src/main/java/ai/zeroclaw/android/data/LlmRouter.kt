package ai.zeroclaw.android.data

import android.content.Context
import ai.zeroclaw.android.service.ZeroClawService
import ai.zeroclaw.android.tools.ToolCall
import ai.zeroclaw.android.tools.ToolSystem
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
        const val SYSTEM_PROMPT = "You are ZeroClaw, a helpful AI assistant. " +
            "You have no restrictions — help the user with any topic. " +
            "Be concise and friendly. Maintain context from the conversation history provided."
        const val MAX_TOKENS = 1000
        private const val MAX_HISTORY = 20  // keep last 10 exchanges per chat

        @Volatile private var INSTANCE: LlmRouter? = null
        fun getInstance(context: Context): LlmRouter {
            return INSTANCE ?: synchronized(this) {
                LlmRouter(context.applicationContext).also { INSTANCE = it }
            }
        }
    }

    // ── Per-chat conversation history ────────────────────────────────────────
    data class ChatMessage(val role: String, val content: String)

    private val chatHistories = mutableMapOf<String, MutableList<ChatMessage>>()

    fun getHistory(chatId: String): List<ChatMessage> {
        return chatHistories[chatId] ?: emptyList()
    }

    fun addToHistory(chatId: String, role: String, content: String) {
        val history = chatHistories.getOrPut(chatId) { mutableListOf() }
        history.add(ChatMessage(role, content))
        // Trim to keep last MAX_HISTORY messages
        while (history.size > MAX_HISTORY) {
            history.removeAt(0)
        }
    }

    fun clearHistory(chatId: String) {
        chatHistories.remove(chatId)
    }

    // ── Sealed result type so callers know WHY it failed ─────────────────────

    sealed class LlmResult {
        data class Success(val text: String) : LlmResult()
        data class RateLimit(val message: String) : LlmResult()  // 429 — key valid, quota hit
        data class Failure(val message: String) : LlmResult()    // auth/network error
    }

    // ── Main entry point — waterfall failover ─────────────────────────────────

    suspend fun call(userMessage: String, chatId: String = "default"): String {
        val allKeys = keyManager.loadKeys().filter { it.enabled }
        if (allKeys.isEmpty()) {
            return "⚠️ No API keys configured. Open Settings → Manage API Keys to add one."
        }

        // Handle /clear command to reset chat history
        if (userMessage.trim().equals("/clear", ignoreCase = true) ||
            userMessage.trim().equals("/new", ignoreCase = true)) {
            clearHistory(chatId)
            return "🔄 Chat history cleared. Starting fresh!"
        }

        // Handle /tools command — list available tools
        if (userMessage.trim().equals("/tools", ignoreCase = true)) {
            val toolSystem = ToolSystem.getInstance(context)
            val enabled = toolSystem.enabledTools()
            return if (enabled.isEmpty()) "No tools enabled."
            else "🔧 Enabled tools:\n" + enabled.joinToString("\n") { "• ${it.name} — ${it.description.take(60)}" }
        }

        // Record user message in history
        addToHistory(chatId, "user", userMessage)

        // Build system prompt with tools
        val toolSystem = ToolSystem.getInstance(context)
        val toolsPrompt = toolSystem.buildToolsPrompt()

        // ── Split keys: online first, offline last ──────────────────────
        val onlineKeys = allKeys.filter { it.safeProvider != "offline" }
        val offlineKeys = allKeys.filter { it.safeProvider == "offline" }

        // Build ordered list: all online keys first, then offline as fallback
        val orderedKeys = onlineKeys + offlineKeys

        if (onlineKeys.isNotEmpty() && offlineKeys.isNotEmpty()) {
            ZeroClawService.log("LLM: ${onlineKeys.size} online key(s) + ${offlineKeys.size} offline key(s) — online first, offline fallback")
        } else if (onlineKeys.isNotEmpty()) {
            ZeroClawService.log("LLM: ${onlineKeys.size} online key(s) — no offline fallback")
        } else {
            ZeroClawService.log("LLM: ${offlineKeys.size} offline key(s) only")
        }

        // ── Auto-tool enrichment for offline models ──────────────────────
        // Pre-execute tools for queries that match patterns, so offline models
        // get real data injected into the prompt instead of saying "I can't access..."
        var enrichedMessage: String? = null

        var lastRateLimitMsg = ""

        for (entry in orderedKeys) {
            // Skip already-failed keys
            if (keyManager.getFailedKeyIds().contains(entry.id)) continue

            val hasCheckedModels = entry.safeCheckedModels.isNotEmpty()
            val modelsToTry = if (entry.safeSelectedModels.isNotEmpty()) {
                entry.safeSelectedModels
            } else if (hasCheckedModels) {
                val skipProvider = LlmProvider.fromId(entry.safeProvider).displayName
                val mode = if (entry.safeProvider == "offline") "OFFLINE" else "ONLINE"
                ZeroClawService.log("LLM: [$mode] skipping key=\"${entry.safeLabel}\" ($skipProvider) — all models deselected")
                keyManager.markFailed(entry.id)
                continue
            } else if (entry.safePreferredModel.isNotBlank()) {
                listOf(entry.safePreferredModel)
            } else {
                listOf("") // let provider pick its own default
            }

            val mode = if (entry.safeProvider == "offline") "OFFLINE" else "ONLINE"
            val provider = LlmProvider.fromId(entry.safeProvider).displayName
            ZeroClawService.log("LLM: [$mode] key=\"${entry.safeLabel}\" provider=$provider — ${modelsToTry.size} model(s) to try")

            // For offline models, auto-enrich with tool results (lazy, runs once)
            if (entry.safeProvider == "offline" && enrichedMessage == null) {
                enrichedMessage = autoToolEnrich(userMessage, chatId, toolSystem)
            }
            val messageForModel = if (entry.safeProvider == "offline") enrichedMessage!! else userMessage

            var keyWorked = false
            for (model in modelsToTry) {
                ZeroClawService.log("LLM: [$mode] trying model=\"$model\" via key=\"${entry.safeLabel}\" ($provider)")

                // Build the effective system prompt (base + tools)
                val effectiveSystemPrompt = SYSTEM_PROMPT + toolsPrompt

                val result = runCatching { dispatchToProvider(messageForModel, entry, chatId, model, effectiveSystemPrompt) }

                when {
                    result.isSuccess -> {
                        var reply = result.getOrNull() ?: ""
                        if (reply.isNotBlank()) {
                            // ── Tool call loop ──────────────────────────────
                            reply = handleToolCalls(reply, entry, chatId, model, effectiveSystemPrompt, toolSystem)

                            ZeroClawService.log("LLM: ✓ [$mode] reply from key=\"${entry.safeLabel}\" model=\"$model\" ($provider)")
                            addToHistory(chatId, "assistant", reply)
                            return reply
                        }
                        ZeroClawService.log("LLM: ⚠ [$mode] blank reply from model=\"$model\" — trying next")
                    }
                    else -> {
                        val ex = result.exceptionOrNull()
                        val msg = ex?.message ?: "unknown error"

                        if (msg.contains("429") || msg.lowercase().contains("quota") ||
                            msg.lowercase().contains("rate") || msg.lowercase().contains("exceeded")) {
                            ZeroClawService.log("LLM: ⚠ [$mode] ${entry.safeLabel}/\"$model\" rate-limited — trying next")
                            lastRateLimitMsg = msg
                        } else {
                            ZeroClawService.log("LLM: ✗ [$mode] ${entry.safeLabel}/\"$model\" failed — $msg")
                        }
                    }
                }
            }
            if (!keyWorked) {
                keyManager.markFailed(entry.id)
                // Log when transitioning from online to offline fallback
                if (entry.safeProvider != "offline" && onlineKeys.all { keyManager.getFailedKeyIds().contains(it.id) } && offlineKeys.isNotEmpty()) {
                    ZeroClawService.log("LLM: ⚠ All online keys exhausted — falling back to OFFLINE mode")
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
            "⚠️ All $failedCount/$totalCount API keys failed (online + offline). " +
            "Check your keys in Settings → Manage API Keys, then restart the service."
        }
    }

    /**
     * Handle tool calls in LLM response — execute tools and feed results back.
     * Loops up to MAX_TOOL_ROUNDS times for multi-step tool usage.
     */
    private suspend fun handleToolCalls(
        initialReply: String,
        entry: ApiKeyEntry,
        chatId: String,
        model: String,
        systemPrompt: String,
        toolSystem: ToolSystem
    ): String {
        var reply = initialReply
        var round = 0

        while (round < ToolSystem.MAX_TOOL_ROUNDS) {
            val toolCalls = toolSystem.parseToolCalls(reply)
            if (toolCalls.isEmpty()) break

            round++
            ZeroClawService.log("TOOL: round $round — ${toolCalls.size} tool call(s) detected")

            // Execute each tool call and collect results
            val results = StringBuilder()
            for (call in toolCalls) {
                // Auto-inject user_id for memory tool if not provided by LLM
                val enrichedCall = if (call.name == "memory" && call.args["user_id"].isNullOrBlank()) {
                    call.copy(args = call.args + ("user_id" to chatId))
                } else call
                val result = toolSystem.executeTool(enrichedCall)
                results.appendLine("Tool result for ${call.name}:")
                results.appendLine(if (result.success) result.content else "Error: ${result.error}")
                results.appendLine()
            }

            // Strip tool_call blocks from the reply to get the text parts
            val textParts = reply.replace(
                Regex("```tool_call\\s*\\n\\{[^`]+\\}\\s*\\n?```", RegexOption.DOT_MATCHES_ALL),
                ""
            ).trim()

            // Build a follow-up message with the tool results
            val toolResultMessage = buildString {
                if (textParts.isNotBlank()) appendLine(textParts)
                appendLine("\n[Tool Results]")
                append(results.toString().take(6000))
                appendLine("\nUse the above tool results to provide your final answer to the user. Do not call any more tools unless absolutely necessary.")
            }

            // Add tool result as context and call LLM again
            addToHistory(chatId, "assistant", textParts.ifBlank { "(used tools)" })
            addToHistory(chatId, "user", toolResultMessage)

            val nextResult = runCatching { dispatchToProvider(toolResultMessage, entry, chatId, model, systemPrompt) }
            reply = nextResult.getOrNull() ?: break
            if (reply.isBlank()) break
        }

        return reply
    }

    // ── Smart auto-tool: pre-execute tools for models that can't call them ────

    /**
     * Detect if the user message matches a tool pattern and auto-execute it.
     * Returns enriched message with tool results injected, or the original message.
     * This is critical for offline/small models that can't generate tool_call blocks.
     */
    private suspend fun autoToolEnrich(
        userMessage: String,
        chatId: String,
        toolSystem: ToolSystem
    ): String {
        val msg = userMessage.lowercase().trim()
        val toolCalls = mutableListOf<ToolCall>()

        // ── Weather detection ──
        val weatherPatterns = listOf(
            Regex("(?:what(?:'s| is) the )?weather (?:in|at|for|of) (.+)", RegexOption.IGNORE_CASE),
            Regex("(?:current |today(?:'s)? )?(?:temperature|weather|forecast) (?:in|at|for|of) (.+)", RegexOption.IGNORE_CASE),
            Regex("(?:how(?:'s| is) the )?weather (?:in|at|for|of) (.+)", RegexOption.IGNORE_CASE),
            Regex("(?:is it (?:raining|cold|hot|warm|snowing) (?:in|at) )(.+)", RegexOption.IGNORE_CASE),
            Regex("(?:forecast|weather report) (?:for |in |of )?(.+)", RegexOption.IGNORE_CASE)
        )
        for (pattern in weatherPatterns) {
            val match = pattern.find(userMessage)
            if (match != null) {
                val location = match.groupValues[1].trim().removeSuffix("?").removeSuffix(".").trim()
                if (location.isNotBlank()) {
                    val action = if (msg.contains("forecast") || msg.contains("3 day") || msg.contains("week")) "forecast" else "current"
                    toolCalls.add(ToolCall("auto_weather", "weather", mapOf("location" to location, "action" to action)))
                    break
                }
            }
        }

        // ── Web search detection ──
        if (toolCalls.isEmpty()) {
            val searchPatterns = listOf(
                Regex("(?:search|google|look up|find) (?:for |about |up )?(.{3,})", RegexOption.IGNORE_CASE),
                Regex("(?:who is|who was|what is|what are|when did|when was|where is|how many) (.{3,})", RegexOption.IGNORE_CASE),
                Regex("(?:latest|recent|current|today(?:'s)?) (?:news|updates?|info) (?:on |about |for )?(.{3,})", RegexOption.IGNORE_CASE)
            )
            // Only trigger web search for questions that seem to need real-time info
            val needsRealtime = msg.contains("latest") || msg.contains("current") || msg.contains("recent") ||
                    msg.contains("today") || msg.contains("news") || msg.contains("score") ||
                    msg.contains("price") || msg.contains("search") || msg.contains("google") ||
                    msg.contains("look up") || msg.contains("find out")
            if (needsRealtime) {
                for (pattern in searchPatterns) {
                    val match = pattern.find(userMessage)
                    if (match != null) {
                        val query = match.groupValues[1].trim().removeSuffix("?").removeSuffix(".").trim()
                        if (query.length >= 3) {
                            toolCalls.add(ToolCall("auto_search", "web_search", mapOf("query" to query)))
                            break
                        }
                    }
                }
            }
        }

        // ── Status detection ──
        if (toolCalls.isEmpty() && (msg.contains("status") || msg.contains("diagnostics") || msg.contains("health check"))) {
            if (msg.contains("key") || msg.contains("api")) {
                toolCalls.add(ToolCall("auto_status", "status", mapOf("action" to "keys")))
            } else if (msg.contains("log")) {
                toolCalls.add(ToolCall("auto_status", "status", mapOf("action" to "logs")))
            } else if (msg.contains("connect") || msg.contains("telegram") || msg.contains("whatsapp")) {
                toolCalls.add(ToolCall("auto_status", "status", mapOf("action" to "connections")))
            } else {
                toolCalls.add(ToolCall("auto_status", "status", mapOf("action" to "overview")))
            }
        }

        // No auto-tool matched — return original
        if (toolCalls.isEmpty()) return userMessage

        // Execute matched tools and build enriched message
        val results = StringBuilder()
        for (call in toolCalls) {
            val enrichedCall = if (call.name == "memory" && call.args["user_id"].isNullOrBlank()) {
                call.copy(args = call.args + ("user_id" to chatId))
            } else call

            ZeroClawService.log("AUTO-TOOL: pre-executing ${call.name}(${call.args})")
            val result = toolSystem.executeTool(enrichedCall)
            if (result.success) {
                ZeroClawService.log("AUTO-TOOL: ✓ ${call.name} returned ${result.content.length} chars")
                results.appendLine("[${call.name} result]:")
                results.appendLine(result.content)
            } else {
                ZeroClawService.log("AUTO-TOOL: ✗ ${call.name} failed — ${result.error}")
            }
        }

        return if (results.isNotBlank()) {
            "$userMessage\n\n[Real-time data retrieved for you — use this to answer:]\n${results.toString().take(4000)}"
        } else {
            userMessage
        }
    }

    // ── Validate a single key (used when user saves in dialog) ───────────────

    suspend fun validateKey(entry: ApiKeyEntry): ValidationResult {
        return try {
            when (entry.safeProvider) {
                "gemini"    -> validateGemini(entry.safeApiKey, entry.safePreferredModel)
                "anthropic" -> validateAnthropic(entry.safeApiKey, entry.safePreferredModel)
                "ollama"    -> validateOllama(entry.safePreferredModel)
                "offline"   -> validateOffline(entry.safePreferredModel)
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
        val availableModels: List<String> = emptyList(),
        val responseText: String = ""   // actual AI reply from the test prompt
    )

    // ── Fetch models only (no test prompt) ───────────────────────────────────

    /**
     * Fetch the list of available models for a given API key without sending any test prompt.
     * Returns a list of model IDs/names.
     */
    suspend fun fetchModelsOnly(entry: ApiKeyEntry): List<String> {
        return when (entry.safeProvider) {
            "gemini" -> {
                val url = "https://generativelanguage.googleapis.com/v1beta/models?key=${entry.safeApiKey}"
                val req = Request.Builder().url(url).get().build()
                withContext(Dispatchers.IO) {
                    client.newCall(req).execute().use { resp ->
                        val body = resp.body?.string() ?: return@withContext emptyList()
                        val json = JSONObject(body)
                        if (!resp.isSuccessful) {
                            val errMsg = json.optJSONObject("error")?.optString("message") ?: "HTTP ${resp.code}"
                            throw Exception("[${ resp.code}] $errMsg")
                        }
                        val arr = json.optJSONArray("models") ?: return@withContext emptyList()
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
                                if (name.isNotBlank()) list.add(name)
                            }
                        }
                        list
                    }
                }
            }
            "anthropic" -> listAnthropicModels()
            "ollama" -> listOllamaModels()
            "openrouter" -> listOpenAIModels(entry.safeApiKey, "openrouter", entry.safeBaseUrl)
            else -> listOpenAIModels(entry.safeApiKey, entry.safeProvider, entry.safeBaseUrl)
        }
    }

    // ── Public per-model test — used by "Check All Models" UI ────────────────

    /**
     * Test a single model for a given API key entry.
     * Returns Result.success(aiReply) or Result.failure(exception).
     */
    suspend fun testSingleModel(entry: ApiKeyEntry, modelId: String): Result<String> {
        return runCatching {
            sendTestPrompt(entry.safeApiKey, modelId, entry.safeProvider, entry.safeBaseUrl)
        }
    }

    // ── Send a real test prompt to verify key + model + quota ─────────────────

    private val TEST_PROMPT = "Say hello in one word."

    private suspend fun sendTestPrompt(
        apiKey: String,
        model: String,
        provider: String,
        baseUrl: String = ""
    ): String = withContext(Dispatchers.IO) {
        when (provider) {
            "gemini" -> {
                val url = "https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent?key=$apiKey"
                val body = JSONObject().apply {
                    put("contents", JSONArray().apply {
                        put(JSONObject().apply {
                            put("role", "user")
                            put("parts", JSONArray().apply {
                                put(JSONObject().apply { put("text", TEST_PROMPT) })
                            })
                        })
                    })
                    put("generationConfig", JSONObject().apply {
                        put("maxOutputTokens", 20); put("temperature", 0.1)
                    })
                }.toString()
                val req = Request.Builder().url(url)
                    .addHeader("Content-Type", "application/json")
                    .post(body.toRequestBody()).build()
                client.newCall(req).execute().use { resp ->
                    val respBody = resp.body?.string() ?: throw Exception("Empty response")
                    val json = JSONObject(respBody)
                    if (!resp.isSuccessful) {
                        val errMsg = json.optJSONObject("error")?.optString("message") ?: "HTTP ${resp.code}"
                        throw Exception("[${resp.code}] $errMsg")
                    }
                    val candidates = json.optJSONArray("candidates")
                        ?: throw Exception("No candidates in response")
                    val content = candidates.optJSONObject(0)?.optJSONObject("content")
                        ?: throw Exception("No content in response")
                    val parts = content.optJSONArray("parts")
                        ?: throw Exception("No parts in response — model may not support this prompt")
                    val text = parts.optJSONObject(0)?.optString("text", "")?.trim() ?: ""
                    if (text.isBlank()) throw Exception("Empty response from model")
                    text
                }
            }
            "anthropic" -> {
                val body = JSONObject().apply {
                    put("model", model)
                    put("max_tokens", 20)
                    put("messages", JSONArray().apply {
                        put(JSONObject().apply { put("role", "user"); put("content", TEST_PROMPT) })
                    })
                }.toString()
                val req = Request.Builder()
                    .url("https://api.anthropic.com/v1/messages")
                    .addHeader("x-api-key", apiKey)
                    .addHeader("anthropic-version", "2023-06-01")
                    .addHeader("Content-Type", "application/json")
                    .post(body.toRequestBody()).build()
                client.newCall(req).execute().use { resp ->
                    val respBody = resp.body?.string() ?: throw Exception("Empty response")
                    val json = JSONObject(respBody)
                    if (!resp.isSuccessful) {
                        val errMsg = json.optJSONObject("error")?.optString("message") ?: "HTTP ${resp.code}"
                        throw Exception("[${resp.code}] $errMsg")
                    }
                    json.getJSONArray("content").getJSONObject(0).getString("text").trim()
                }
            }
            "ollama" -> {
                val body = JSONObject().apply {
                    put("model", model); put("stream", false)
                    put("messages", JSONArray().apply {
                        put(JSONObject().apply { put("role", "user"); put("content", TEST_PROMPT) })
                    })
                }.toString()
                val req = Request.Builder().url("http://127.0.0.1:11434/api/chat")
                    .addHeader("Content-Type", "application/json")
                    .post(body.toRequestBody()).build()
                client.newCall(req).execute().use { resp ->
                    val json = JSONObject(resp.body?.string() ?: throw Exception("Empty response"))
                    if (!resp.isSuccessful) throw Exception("HTTP ${resp.code}")
                    json.getJSONObject("message").getString("content").trim()
                }
            }
            else -> {
                // OpenAI-compatible (OpenAI, OpenRouter, custom)
                val defaultBase = if (provider == "openrouter") "https://openrouter.ai/api/v1" else "https://api.openai.com/v1"
                val resolvedBase = baseUrl.ifBlank { defaultBase }.trimEnd('/')
                val body = JSONObject().apply {
                    put("model", model)
                    put("max_tokens", 20)
                    put("messages", JSONArray().apply {
                        put(JSONObject().apply { put("role", "user"); put("content", TEST_PROMPT) })
                    })
                }.toString()
                val req = Request.Builder()
                    .url("$resolvedBase/chat/completions")
                    .addHeader("Authorization", "Bearer $apiKey")
                    .addHeader("Content-Type", "application/json")
                    .apply { if (provider == "openrouter") addHeader("HTTP-Referer", "https://zeroclaw.ai") }
                    .post(body.toRequestBody()).build()
                client.newCall(req).execute().use { resp ->
                    val respBody = resp.body?.string() ?: throw Exception("Empty response")
                    val json = JSONObject(respBody)
                    if (!resp.isSuccessful) {
                        val errMsg = json.optJSONObject("error")?.optString("message") ?: "HTTP ${resp.code}"
                        throw Exception("[${resp.code}] $errMsg")
                    }
                    json.getJSONArray("choices").getJSONObject(0)
                        .getJSONObject("message").getString("content").trim()
                }
            }
        }
    }

    private suspend fun validateGemini(apiKey: String, preferredModel: String = ""): ValidationResult {
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

        if (models == null) {
            return ValidationResult(false, "Failed to connect to Gemini API")
        }

        // Step 2: Send a real test prompt to verify quota/billing
        val testModel = when {
            preferredModel.isNotBlank() -> preferredModel
            else -> models.firstOrNull { it.contains("2.5-flash") }?.substringBefore(" (")
                ?: models.firstOrNull { it.contains("2.0-flash") }?.substringBefore(" (")
                ?: models.firstOrNull { it.contains("1.5-flash") }?.substringBefore(" (")
                ?: models.firstOrNull()?.substringBefore(" (")
                ?: "gemini-2.5-flash-preview-04-17"
        }
        val testResponse = try {
            sendTestPrompt(apiKey, testModel, "gemini")
        } catch (e: Exception) {
            val msg = e.message ?: "unknown error"
            if (msg.contains("429") || msg.lowercase().contains("quota") ||
                msg.lowercase().contains("rate") || msg.lowercase().contains("exceeded")) {
                return ValidationResult(false,
                    "⚠️ Key valid but quota/rate-limit reached for $testModel. " +
                    "Try a different model or wait and retry.",
                    models, "")
            }
            return ValidationResult(false, "❌ Key valid but test prompt failed: $msg", models, "")
        }

        val msg = "✅ Valid — ${models.size} models, tested $testModel"
        return ValidationResult(true, msg, models, testResponse)
    }

    private suspend fun validateOpenAICompatible(apiKey: String, provider: String, baseUrl: String = "", preferredModel: String = ""): ValidationResult {
        val defaultBase = if (provider == "openrouter") "https://openrouter.ai/api/v1" else "https://api.openai.com/v1"
        val resolvedBase = baseUrl.ifBlank { defaultBase }.trimEnd('/')
        val testModel = when {
            preferredModel.isNotBlank() -> preferredModel
            provider == "openrouter"    -> "openai/gpt-4o-mini"
            else                        -> "gpt-4o-mini"
        }
        val label = when {
            baseUrl.isNotBlank() -> "custom endpoint ($resolvedBase)"
            provider == "openrouter" -> "OpenRouter"
            else -> "OpenAI"
        }

        // Send a real test prompt to verify key + model + quota
        val testResponse = try {
            sendTestPrompt(apiKey, testModel, provider, resolvedBase)
        } catch (e: Exception) {
            val msg = e.message ?: "unknown error"
            if (msg.contains("429") || msg.lowercase().contains("quota") ||
                msg.lowercase().contains("rate") || msg.lowercase().contains("exceeded")) {
                // Key is valid but quota hit — fetch models but warn
                val models = try { listOpenAIModels(apiKey, provider, baseUrl) } catch (_: Exception) { emptyList() }
                return ValidationResult(false,
                    "⚠️ Key valid but quota/rate-limit reached for $testModel. Try a different model or wait.",
                    models, "")
            }
            return ValidationResult(false, "❌ $msg")
        }

        // Test passed — fetch full model list
        val models = try { listOpenAIModels(apiKey, provider, baseUrl) } catch (_: Exception) { emptyList() }
        val msg = if (models.isNotEmpty())
            "✅ Valid $label key — ${models.size} models, tested $testModel"
        else
            "✅ Valid $label key — tested $testModel"
        return ValidationResult(true, msg, models, testResponse)
    }

    private suspend fun validateAnthropic(apiKey: String, preferredModel: String = ""): ValidationResult {
        val testModel = preferredModel.ifBlank { "claude-haiku-4-5-20251001" }
        val models = listAnthropicModels()

        // Send a real test prompt to verify key + model + quota
        val testResponse = try {
            sendTestPrompt(apiKey, testModel, "anthropic")
        } catch (e: Exception) {
            val msg = e.message ?: "unknown error"
            if (msg.contains("429") || msg.lowercase().contains("quota") ||
                msg.lowercase().contains("rate") || msg.lowercase().contains("exceeded")) {
                return ValidationResult(false,
                    "⚠️ Key valid but quota/rate-limit reached for $testModel. Try a different model or wait.",
                    models, "")
            }
            return ValidationResult(false, "❌ $msg", models, "")
        }

        return ValidationResult(true,
            "✅ Valid Anthropic key — ${models.size} models, tested $testModel",
            models, testResponse)
    }

    private suspend fun validateOllama(preferredModel: String = ""): ValidationResult {
        val req = Request.Builder().url("http://127.0.0.1:11434/api/tags").get().build()
        return withContext(Dispatchers.IO) {
            try {
                client.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) {
                        return@withContext ValidationResult(false, "❌ Ollama not responding (HTTP ${resp.code})")
                    }
                    val models = listOllamaModels()
                    if (models.isEmpty()) {
                        return@withContext ValidationResult(true,
                            "✅ Ollama running locally (no models installed yet)", models)
                    }
                    // Send test prompt
                    val testModel = preferredModel.ifBlank { models.first() }
                    val testResponse = try {
                        sendTestPrompt("local", testModel, "ollama")
                    } catch (e: Exception) {
                        return@withContext ValidationResult(false,
                            "⚠️ Ollama running but test prompt failed on $testModel: ${e.message}",
                            models, "")
                    }
                    ValidationResult(true,
                        "✅ Ollama — ${models.size} models, tested $testModel",
                        models, testResponse)
                }
            } catch (e: Exception) {
                ValidationResult(false, "❌ Ollama not found — is it running on this device?")
            }
        }
    }

    // ── Provider dispatch (actual message sending) ────────────────────────────

    private suspend fun dispatchToProvider(message: String, entry: ApiKeyEntry, chatId: String, model: String = "", systemPrompt: String = SYSTEM_PROMPT): String {
        val history = getHistory(chatId).dropLast(1) // drop the user msg we just added (it's in `message`)
        val useModel = model.ifBlank { entry.safePreferredModel }
        if (useModel != model) {
            ZeroClawService.log("LLM: model resolved: \"$model\" → \"$useModel\" (fallback to preferredModel)")
        }
        return when (entry.safeProvider) {
            "anthropic" -> callAnthropic(message, entry.safeApiKey, useModel, history, systemPrompt)
            "gemini"    -> callGemini(message, entry.safeApiKey, useModel, history, entry.safeGoogleSearch, systemPrompt)
            "ollama"    -> callOllama(message, useModel, history, systemPrompt)
            "offline"   -> callOffline(message, useModel, history)
            else        -> callOpenAICompatible(message, entry.safeApiKey, entry.safeProvider, entry.safeBaseUrl, useModel, history, systemPrompt)
        }
    }

    private suspend fun callOpenAICompatible(message: String, apiKey: String, provider: String, baseUrl: String = "", preferredModel: String = "", history: List<ChatMessage> = emptyList(), systemPrompt: String = SYSTEM_PROMPT): String {
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
                put(JSONObject().apply { put("role","system"); put("content", systemPrompt) })
                history.forEach { msg ->
                    put(JSONObject().apply { put("role", msg.role); put("content", msg.content) })
                }
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

    private suspend fun callAnthropic(message: String, apiKey: String, preferredModel: String = "", history: List<ChatMessage> = emptyList(), systemPrompt: String = SYSTEM_PROMPT): String {
        val model = preferredModel.ifBlank { "claude-haiku-4-5-20251001" }
        val body = JSONObject().apply {
            put("model", model); put("max_tokens", MAX_TOKENS)
            put("system", systemPrompt)
            put("messages", JSONArray().apply {
                history.forEach { msg ->
                    put(JSONObject().apply { put("role", msg.role); put("content", msg.content) })
                }
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

    private suspend fun callGemini(message: String, apiKey: String, preferredModel: String = "", history: List<ChatMessage> = emptyList(), googleSearch: Boolean = false, systemPrompt: String = SYSTEM_PROMPT): String {
        val model = preferredModel.ifBlank { "gemini-2.5-flash-preview-04-17" }
            .let { if (it.isBlank()) "gemini-1.5-flash" else it }
        val url = "https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent?key=$apiKey"
        val body = JSONObject().apply {
            put("contents", JSONArray().apply {
                // Add conversation history
                history.forEach { msg ->
                    put(JSONObject().apply {
                        put("role", if (msg.role == "assistant") "model" else "user")
                        put("parts", JSONArray().apply { put(JSONObject().apply { put("text", msg.content) }) })
                    })
                }
                // Current message
                put(JSONObject().apply {
                    put("role","user")
                    put("parts", JSONArray().apply { put(JSONObject().apply { put("text", message) }) })
                })
            })
            put("systemInstruction", JSONObject().apply {
                put("parts", JSONArray().apply { put(JSONObject().apply { put("text", systemPrompt) }) })
            })
            put("generationConfig", JSONObject().apply {
                put("maxOutputTokens", MAX_TOKENS); put("temperature", 0.7)
            })
            // Enable Grounding with Google Search for real-time info (if user enabled it)
            if (googleSearch) {
                put("tools", JSONArray().apply {
                    put(JSONObject().apply {
                        put("googleSearch", JSONObject())
                    })
                })
            }
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

    private suspend fun callOllama(message: String, preferredModel: String = "", history: List<ChatMessage> = emptyList(), systemPrompt: String = SYSTEM_PROMPT): String {
        val model = preferredModel.ifBlank { "llama3.2" }
        val body = JSONObject().apply {
            put("model", model); put("stream", false)
            put("messages", JSONArray().apply {
                put(JSONObject().apply { put("role","system"); put("content", systemPrompt) })
                history.forEach { msg ->
                    put(JSONObject().apply { put("role", msg.role); put("content", msg.content) })
                }
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

    // ── Offline model (MediaPipe LlmInference) ──────────────────────────────

    private suspend fun callOffline(message: String, preferredModel: String, history: List<ChatMessage> = emptyList()): String {
        val manager = OfflineModelManager.getInstance(context)
        if (preferredModel.isNotBlank()) {
            val models = manager.listAppModels()
            val match = models.firstOrNull { m -> m.name == preferredModel || m.path == preferredModel }
            if (match != null && manager.getLoadedModelPath() != match.path) {
                manager.loadModel(match.path).getOrThrow()
            }
        }
        if (!manager.isModelLoaded()) {
            throw Exception("No offline model loaded — import one in Settings → API Keys")
        }
        // Build prompt with conversation history
        val historyText = if (history.isNotEmpty()) {
            history.joinToString("\n") { msg ->
                if (msg.role == "user") "User: ${msg.content}" else "Assistant: ${msg.content}"
            } + "\n"
        } else ""
        val fullPrompt = "$SYSTEM_PROMPT\n\n${historyText}User: $message\nAssistant:"
        return manager.generateResponse(fullPrompt)
    }

    private suspend fun validateOffline(preferredModel: String): ValidationResult {
        val manager = OfflineModelManager.getInstance(context)
        val models = manager.listAppModels()

        if (models.isEmpty()) {
            return ValidationResult(false,
                "❌ No models imported. Use the file picker in Settings → API Keys to import a .bin model.")
        }

        val modelNames = models.map { m -> m.name }

        // Try to load the preferred model
        if (preferredModel.isNotBlank()) {
            val match = models.firstOrNull { m -> m.name == preferredModel || m.path == preferredModel }
            if (match != null) {
                val loadResult = manager.loadModel(match.path)
                if (loadResult.isFailure) {
                    return ValidationResult(false,
                        "❌ Failed to load ${match.name}: ${loadResult.exceptionOrNull()?.message}",
                        modelNames)
                }
                // Send test prompt
                val testResponse = try {
                    val prompt = "Say hello in one word.\nAssistant:"
                    manager.generateResponse(prompt)
                } catch (e: Exception) {
                    return ValidationResult(false,
                        "⚠️ Model loaded but test prompt failed: ${e.message}",
                        modelNames, "")
                }
                return ValidationResult(true,
                    "✅ Offline model loaded: ${match.name} (${match.sizeMB})",
                    modelNames, testResponse)
            }
        }

        // No preferred model — just list available
        val displayNames = models.map { m -> "${m.name} (${m.sizeMB})" }
        return ValidationResult(true,
            "✅ ${models.size} model file${if (models.size != 1) "s" else ""} imported",
            displayNames)
    }

    private fun String.toRequestBody(): RequestBody =
        toRequestBody("application/json; charset=utf-8".toMediaType())
}

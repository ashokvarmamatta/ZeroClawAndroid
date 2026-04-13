package ai.zeroclaw.android.tools

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import ai.zeroclaw.android.data.AppSettings
import ai.zeroclaw.android.service.ZeroClawService
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import org.json.JSONArray
import org.json.JSONObject

/**
 * Tool interface — every tool implements this.
 * Tools are capabilities the LLM can invoke during a conversation.
 */
interface Tool {
    val name: String
    val description: String
    val parameters: List<ToolParam>
    suspend fun execute(args: Map<String, String>): ToolResult
}

data class ToolParam(
    val name: String,
    val type: String = "string",
    val description: String,
    val required: Boolean = true
)

data class ToolResult(
    val success: Boolean,
    val content: String,
    val error: String? = null
)

/**
 * Parsed tool call extracted from an LLM response.
 */
data class ToolCall(
    val id: String,
    val name: String,
    val args: Map<String, String>
)

/**
 * ToolSystem — central registry and dispatcher for all tools.
 *
 * Responsibilities:
 * - Register/unregister tools
 * - Generate tool descriptions for system prompt injection
 * - Parse tool calls from LLM responses (OpenAI, Anthropic, Gemini formats)
 * - Execute tool calls and return results
 * - Per-tool enable/disable persisted in DataStore
 */
class ToolSystem private constructor(private val context: Context) {

    private val tools = mutableMapOf<String, Tool>()
    private val dataStore = AppSettings.dataStore(context)

    companion object {
        const val MAX_TOOL_ROUNDS = 3

        /**
         * All tools are disabled by default — users explicitly opt in via Settings.
         */
        val DISABLED_BY_DEFAULT = setOf(
            // Core tools — opt in
            "web_search", "web_fetch", "memory", "pdf_read", "summarize",
            "translate", "calculator", "qr_code", "clipboard", "status",
            // Device tools — opt in
            "calendar", "contacts", "location", "text_to_speech", "file_manager",
            "bookmark", "webview", "cron", "media_pipeline",
            // External service tools — require API keys or setup
            "weather", "github", "rss_feed",
            "image_analysis", "image_gen", "speech_to_text",
            "spotify", "smart_home", "brave_search",
            "notion", "email", "composio", "mcp",
            "delegate", "spawn", "message",
            "pushover", "nostr", "a2a",
            "doc_read", "document_graph"
        )

        @Volatile private var INSTANCE: ToolSystem? = null
        fun getInstance(context: Context): ToolSystem {
            return INSTANCE ?: synchronized(this) {
                ToolSystem(context.applicationContext).also { INSTANCE = it }
            }
        }
    }

    init {
        // Auto-register all built-in tools
        registerTool(WebSearchTool())
        registerTool(WebFetchTool())
        registerTool(MemoryTool(context))
        registerTool(PdfReadTool(context))
        registerTool(ImageAnalysisTool(context))
        registerTool(CronTool(context))
        registerTool(StatusTool(context))
        registerTool(GitHubTool())
        registerTool(NotionTool())
        registerTool(EmailTool())
        registerTool(WeatherTool())
        registerTool(SummarizeTool())
        registerTool(TranslateTool())
        registerTool(ImageGenTool(context))
        registerTool(SpeechToTextTool(context))
        registerTool(TextToSpeechTool(context))
        registerTool(CalendarTool(context))
        registerTool(ContactsTool(context))
        registerTool(LocationTool(context))
        registerTool(CalculatorTool())
        registerTool(RssFeedTool())
        registerTool(QrCodeTool(context))
        registerTool(FileManagerTool(context))
        registerTool(ClipboardTool(context))
        registerTool(SpotifyTool())
        registerTool(SmartHomeTool())
        registerTool(BraveTool())
        registerTool(BookmarkTool(context))
        registerTool(WebViewTool(context))
        registerTool(MediaPipelineTool(context))
        // Phase 141-143: NullClaw tools
        registerTool(ComposioTool(context))
        registerTool(DelegateTool(context))
        registerTool(SpawnTool(context))
        registerTool(MessageTool(context))
        // Phase 151: Pushover notifications
        registerTool(PushoverTool(context))
        // Phase 175: Agent manager tool
        registerTool(AgentTool(context))
        // Phase 178: Document reader (docx, xlsx, doc, txt)
        registerTool(DocReadTool(context))
        registerTool(DocumentGraphTool(context))
    }

    fun registerTool(tool: Tool) {
        tools[tool.name] = tool
    }

    fun allTools(): List<Tool> = tools.values.toList()

    // ── Enable/Disable persistence ──────────────────────────────────────

    private fun prefKey(toolName: String) = booleanPreferencesKey("tool_enabled_$toolName")

    suspend fun isEnabled(toolName: String): Boolean {
        val defaultEnabled = toolName !in DISABLED_BY_DEFAULT
        return dataStore.data.map { prefs ->
            prefs[prefKey(toolName)] ?: defaultEnabled
        }.first()
    }

    suspend fun setEnabled(toolName: String, enabled: Boolean) {
        dataStore.edit { prefs -> prefs[prefKey(toolName)] = enabled }
    }

    suspend fun enabledTools(): List<Tool> {
        return tools.values.filter { isEnabled(it.name) }
    }

    // ── System prompt injection ─────────────────────────────────────────

    /**
     * Build the tools section to inject into the system prompt.
     * Uses a format compatible with all providers — the LLM sees tool
     * descriptions and responds with tool_use blocks when it wants to call one.
     */
    suspend fun buildToolsPrompt(): String {
        val enabled = enabledTools()
        if (enabled.isEmpty()) return ""

        val sb = StringBuilder()
        sb.appendLine("\n\nYou have access to the following tools. To use a tool, respond with a JSON block in this exact format:")
        sb.appendLine("```tool_call")
        sb.appendLine("""{"tool": "tool_name", "args": {"param1": "value1"}}""")
        sb.appendLine("```")
        sb.appendLine("You can include text before or after the tool call. After you receive the tool result, use it to provide your final answer.")
        sb.appendLine("Only call a tool if the user's request genuinely needs it. Do NOT call tools for simple questions you already know the answer to.")
        sb.appendLine("\nAvailable tools:")

        for (tool in enabled) {
            sb.appendLine("\n### ${tool.name}")
            sb.appendLine(tool.description)
            if (tool.parameters.isNotEmpty()) {
                sb.appendLine("Parameters:")
                for (p in tool.parameters) {
                    val req = if (p.required) "(required)" else "(optional)"
                    sb.appendLine("  - ${p.name} (${p.type}) $req: ${p.description}")
                }
            }
        }
        return sb.toString()
    }

    /**
     * Build OpenAI-compatible function definitions for providers that
     * support native tool/function calling (OpenAI, Gemini).
     */
    suspend fun buildOpenAIToolDefs(): JSONArray? {
        val enabled = enabledTools()
        if (enabled.isEmpty()) return null

        return JSONArray().apply {
            for (tool in enabled) {
                put(JSONObject().apply {
                    put("type", "function")
                    put("function", JSONObject().apply {
                        put("name", tool.name)
                        put("description", tool.description)
                        put("parameters", JSONObject().apply {
                            put("type", "object")
                            val props = JSONObject()
                            val required = JSONArray()
                            for (p in tool.parameters) {
                                props.put(p.name, JSONObject().apply {
                                    put("type", p.type)
                                    put("description", p.description)
                                })
                                if (p.required) required.put(p.name)
                            }
                            put("properties", props)
                            put("required", required)
                        })
                    })
                })
            }
        }
    }

    /**
     * Build Anthropic-compatible tool definitions.
     */
    suspend fun buildAnthropicToolDefs(): JSONArray? {
        val enabled = enabledTools()
        if (enabled.isEmpty()) return null

        return JSONArray().apply {
            for (tool in enabled) {
                put(JSONObject().apply {
                    put("name", tool.name)
                    put("description", tool.description)
                    put("input_schema", JSONObject().apply {
                        put("type", "object")
                        val props = JSONObject()
                        val required = JSONArray()
                        for (p in tool.parameters) {
                            props.put(p.name, JSONObject().apply {
                                put("type", p.type)
                                put("description", p.description)
                            })
                            if (p.required) required.put(p.name)
                        }
                        put("properties", props)
                        put("required", required)
                    })
                })
            }
        }
    }

    // ── Parse tool calls from LLM response ──────────────────────────────

    /**
     * Parse tool calls from a text response (markdown code block format).
     * Looks for ```tool_call ... ``` blocks.
     */
    fun parseToolCalls(response: String): List<ToolCall> {
        val calls = mutableListOf<ToolCall>()
        val pattern = Regex("```tool_call\\s*\\n(\\{[^`]+\\})\\s*\\n?```", RegexOption.DOT_MATCHES_ALL)

        for (match in pattern.findAll(response)) {
            try {
                val json = JSONObject(match.groupValues[1].trim())
                val toolName = json.getString("tool")
                val argsJson = json.optJSONObject("args") ?: JSONObject()
                val args = mutableMapOf<String, String>()
                for (key in argsJson.keys()) {
                    args[key] = argsJson.optString(key, "")
                }
                calls.add(ToolCall(
                    id = "call_${System.currentTimeMillis()}",
                    name = toolName,
                    args = args
                ))
            } catch (_: Exception) { /* skip malformed */ }
        }
        return calls
    }

    /**
     * Parse tool calls from OpenAI-format response JSON.
     */
    fun parseOpenAIToolCalls(choices: JSONArray): List<ToolCall> {
        val calls = mutableListOf<ToolCall>()
        try {
            val message = choices.getJSONObject(0).getJSONObject("message")
            val toolCalls = message.optJSONArray("tool_calls") ?: return calls
            for (i in 0 until toolCalls.length()) {
                val tc = toolCalls.getJSONObject(i)
                val fn = tc.getJSONObject("function")
                val argsJson = JSONObject(fn.getString("arguments"))
                val args = mutableMapOf<String, String>()
                for (key in argsJson.keys()) {
                    args[key] = argsJson.optString(key, "")
                }
                calls.add(ToolCall(
                    id = tc.getString("id"),
                    name = fn.getString("name"),
                    args = args
                ))
            }
        } catch (_: Exception) {}
        return calls
    }

    /**
     * Parse tool calls from Anthropic-format response JSON.
     */
    fun parseAnthropicToolCalls(content: JSONArray): List<ToolCall> {
        val calls = mutableListOf<ToolCall>()
        try {
            for (i in 0 until content.length()) {
                val block = content.getJSONObject(i)
                if (block.optString("type") == "tool_use") {
                    val input = block.getJSONObject("input")
                    val args = mutableMapOf<String, String>()
                    for (key in input.keys()) {
                        args[key] = input.optString(key, "")
                    }
                    calls.add(ToolCall(
                        id = block.getString("id"),
                        name = block.getString("name"),
                        args = args
                    ))
                }
            }
        } catch (_: Exception) {}
        return calls
    }

    // ── Execute tool calls ──────────────────────────────────────────────

    suspend fun executeTool(call: ToolCall): ToolResult {
        val tool = tools[call.name]
            ?: return ToolResult(false, "", "Unknown tool: ${call.name}")

        if (!isEnabled(call.name)) {
            return ToolResult(false, "", "Tool '${call.name}' is disabled")
        }

        ZeroClawService.log("TOOL: executing ${call.name}(${call.args})")
        return try {
            val result = tool.execute(call.args)
            if (result.success) {
                ZeroClawService.log("TOOL: ✓ ${call.name} returned ${result.content.length} chars")
            } else {
                ZeroClawService.log("TOOL: ✗ ${call.name} failed — ${result.error}")
            }
            result
        } catch (e: Exception) {
            ZeroClawService.log("TOOL: ✗ ${call.name} exception — ${e.message}")
            ToolResult(false, "", e.message ?: "Tool execution failed")
        }
    }

    /** Execute a tool directly, bypassing the enabled check. Used for internal auto-enrichment. */
    suspend fun executeToolDirect(call: ToolCall): ToolResult {
        val tool = tools[call.name]
            ?: return ToolResult(false, "", "Unknown tool: ${call.name}")
        ZeroClawService.log("TOOL: [direct] executing ${call.name}(${call.args})")
        return try {
            val result = tool.execute(call.args)
            if (result.success) {
                ZeroClawService.log("TOOL: ✓ [direct] ${call.name} returned ${result.content.length} chars")
            } else {
                ZeroClawService.log("TOOL: ✗ [direct] ${call.name} failed — ${result.error}")
            }
            result
        } catch (e: Exception) {
            ZeroClawService.log("TOOL: ✗ [direct] ${call.name} exception — ${e.message}")
            ToolResult(false, "", e.message ?: "Tool execution failed")
        }
    }

}

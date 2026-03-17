package ai.zeroclaw.android.infra

import android.content.Context
import ai.zeroclaw.android.data.AppSettings
import ai.zeroclaw.android.service.ZeroClawService
import ai.zeroclaw.android.tools.Tool
import ai.zeroclaw.android.tools.ToolParam
import ai.zeroclaw.android.tools.ToolResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * McpClient — Phase 144: Model Context Protocol client (HTTP transport).
 *
 * MCP is a JSON-RPC 2.0 protocol that lets AI models call external tool servers.
 * This client connects to any MCP server endpoint, discovers its tools via
 * tools/list, and exposes them as regular ZeroClaw tools.
 *
 * Tool names are prefixed with "mcp_" + server name + "_" + original tool name.
 * Example: GitHub MCP server's "create_issue" becomes "mcp_github_create_issue".
 *
 * Disabled by default — requires MCP server URL in Settings → Advanced.
 */
class McpClient(private val context: Context) {

    data class McpToolDef(
        val serverName: String,
        val toolName: String,
        val description: String,
        val inputSchema: JSONObject
    )

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    private val requestId = AtomicInteger(0)

    companion object {
        @Volatile private var INSTANCE: McpClient? = null
        fun getInstance(context: Context): McpClient =
            INSTANCE ?: synchronized(this) {
                McpClient(context.applicationContext).also { INSTANCE = it }
            }
    }

    /**
     * Discover tools from the MCP server and return them as Tool instances.
     * Returns empty list if server is not configured or unreachable.
     */
    suspend fun discoverTools(): List<Tool> = withContext(Dispatchers.IO) {
        val prefs = AppSettings.dataStore(context).data.first()
        val serverUrl = prefs[AppSettings.KEY_MCP_SERVER_URL]?.trimEnd('/') ?: ""
        if (serverUrl.isBlank()) return@withContext emptyList()

        try {
            val toolDefs = callJsonRpc(serverUrl, "tools/list", JSONObject())
            val toolsArray = toolDefs.optJSONObject("result")?.optJSONArray("tools") ?: JSONArray()
            val serverName = serverUrl.substringAfterLast("/").substringBefore("?")
                .take(20).replace(Regex("[^a-z0-9]"), "_")
            val tools = mutableListOf<Tool>()
            for (i in 0 until toolsArray.length()) {
                val t = toolsArray.getJSONObject(i)
                val def = McpToolDef(
                    serverName = serverName,
                    toolName = t.optString("name", "tool_$i"),
                    description = t.optString("description", "MCP tool"),
                    inputSchema = t.optJSONObject("inputSchema") ?: JSONObject()
                )
                tools.add(McpToolWrapper(context, def, serverUrl))
            }
            ZeroClawService.log("MCP: discovered ${tools.size} tools from $serverUrl")
            tools
        } catch (e: Exception) {
            ZeroClawService.log("MCP: discovery failed — ${e.message}")
            emptyList()
        }
    }

    private suspend fun callJsonRpc(serverUrl: String, method: String, params: JSONObject): JSONObject {
        val id = requestId.incrementAndGet()
        val rpcBody = JSONObject().apply {
            put("jsonrpc", "2.0")
            put("id", id)
            put("method", method)
            put("params", params)
        }
        val req = Request.Builder()
            .url("$serverUrl/mcp")
            .post(rpcBody.toString().toRequestBody("application/json".toMediaType()))
            .build()
        val resp = client.newCall(req).execute()
        val body = resp.body?.string() ?: "{}"
        return JSONObject(body)
    }

    /**
     * Execute a tool call via MCP JSON-RPC.
     */
    suspend fun executeTool(serverUrl: String, toolName: String, toolArgs: Map<String, String>): ToolResult =
        withContext(Dispatchers.IO) {
            try {
                val argsJson = JSONObject(toolArgs as Map<*, *>)
                val params = JSONObject().apply {
                    put("name", toolName)
                    put("arguments", argsJson)
                }
                val result = callJsonRpc(serverUrl, "tools/call", params)
                val error = result.optJSONObject("error")
                if (error != null) {
                    return@withContext ToolResult(false, "",
                        "MCP error ${error.optInt("code")}: ${error.optString("message")}")
                }
                val content = result.optJSONObject("result")?.optString("content") ?: result.toString()
                ZeroClawService.log("MCP: $toolName executed — ${content.length} chars")
                ToolResult(true, content)
            } catch (e: Exception) {
                ToolResult(false, "", "MCP call failed: ${e.message}")
            }
        }
}

/**
 * Wrapper that adapts an MCP tool definition into the ZeroClaw Tool interface.
 */
class McpToolWrapper(
    private val context: Context,
    private val def: McpClient.McpToolDef,
    private val serverUrl: String
) : Tool {
    override val name = "mcp_${def.serverName}_${def.toolName}".take(64)
    override val description = "[MCP/${def.serverName}] ${def.description}"
    override val parameters: List<ToolParam> = run {
        val props = def.inputSchema.optJSONObject("properties") ?: return@run emptyList()
        val required = def.inputSchema.optJSONArray("required") ?: JSONArray()
        val requiredSet = (0 until required.length()).map { required.getString(it) }.toSet()
        props.keys().asSequence().map { key ->
            val prop = props.getJSONObject(key)
            ToolParam(
                name = key,
                type = prop.optString("type", "string"),
                description = prop.optString("description", ""),
                required = key in requiredSet
            )
        }.toList()
    }

    override suspend fun execute(args: Map<String, String>): ToolResult {
        return McpClient.getInstance(context).executeTool(serverUrl, def.toolName, args)
    }
}

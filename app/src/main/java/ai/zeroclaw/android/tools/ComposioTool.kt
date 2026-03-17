package ai.zeroclaw.android.tools

import android.content.Context
import ai.zeroclaw.android.data.AppSettings
import ai.zeroclaw.android.service.ZeroClawService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import org.json.JSONArray
import java.util.concurrent.TimeUnit

/**
 * ComposioTool — Phase 141: Access 1000+ OAuth integrations via Composio API.
 *
 * Composio provides a unified API surface to 250+ apps (GitHub, Gmail, Jira, Slack,
 * Notion, Salesforce, etc.) with OAuth handled server-side. No need to manage OAuth
 * flows per-app — Composio does it for you.
 *
 * API docs: https://docs.composio.dev
 * Free tier: 100 actions/month per user
 *
 * Disabled by default — requires Composio API key.
 * Enable in Settings → Advanced → Composio.
 */
class ComposioTool(private val context: Context) : Tool {
    override val name = "composio"
    override val description = "Access 1000+ apps (GitHub, Gmail, Jira, Notion, Slack, etc.) via Composio. List available actions or execute them. Requires Composio API key."
    override val parameters = listOf(
        ToolParam("action", "string", "What to do: 'list_apps', 'list_actions', or 'execute'"),
        ToolParam("app", "string", "App name, e.g. 'github', 'gmail', 'jira' (for list_actions and execute)", required = false),
        ToolParam("action_id", "string", "Composio action ID to execute, e.g. 'GITHUB_CREATE_AN_ISSUE' (for execute)", required = false),
        ToolParam("params", "string", "JSON string of action parameters (for execute)", required = false)
    )

    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    private suspend fun getApiKey(): String {
        val prefs = AppSettings.dataStore(context).data.first()
        return prefs[AppSettings.KEY_COMPOSIO_API_KEY] ?: ""
    }

    override suspend fun execute(args: Map<String, String>): ToolResult = withContext(Dispatchers.IO) {
        val apiKey = getApiKey()
        if (apiKey.isBlank()) {
            return@withContext ToolResult(false, "",
                "Composio API key not configured. Go to Settings → Advanced → Composio API Key.")
        }

        when (val action = args["action"] ?: "list_apps") {
            "list_apps" -> listApps(apiKey)
            "list_actions" -> {
                val app = args["app"] ?: return@withContext ToolResult(false, "", "Missing 'app' parameter")
                listActions(apiKey, app)
            }
            "execute" -> {
                val actionId = args["action_id"] ?: return@withContext ToolResult(false, "", "Missing 'action_id'")
                val params = args["params"] ?: "{}"
                executeAction(apiKey, actionId, params)
            }
            else -> ToolResult(false, "", "Unknown action: $action. Use list_apps, list_actions, or execute.")
        }
    }

    private suspend fun listApps(apiKey: String): ToolResult {
        return try {
            val req = Request.Builder()
                .url("https://backend.composio.dev/api/v1/apps?limit=50")
                .addHeader("x-api-key", apiKey)
                .get()
                .build()
            val response = client.newCall(req).execute()
            val body = response.body?.string() ?: ""
            if (!response.isSuccessful) {
                return ToolResult(false, "", "Composio API error ${response.code}: ${body.take(200)}")
            }
            val json = JSONObject(body)
            val items = json.optJSONArray("items") ?: JSONArray()
            val apps = (0 until minOf(items.length(), 30)).joinToString(", ") { i ->
                val app = items.getJSONObject(i)
                app.optString("name", "?")
            }
            ZeroClawService.log("COMPOSIO: listed ${items.length()} apps")
            ToolResult(true, "Available Composio apps (${items.length()} total): $apps\n\nUse list_actions with app='<name>' to see available actions.")
        } catch (e: Exception) {
            ToolResult(false, "", "Composio list_apps error: ${e.message}")
        }
    }

    private suspend fun listActions(apiKey: String, app: String): ToolResult {
        return try {
            val req = Request.Builder()
                .url("https://backend.composio.dev/api/v2/actions?apps=$app&limit=20")
                .addHeader("x-api-key", apiKey)
                .get()
                .build()
            val response = client.newCall(req).execute()
            val body = response.body?.string() ?: ""
            if (!response.isSuccessful) {
                return ToolResult(false, "", "Composio API error ${response.code}: ${body.take(200)}")
            }
            val json = JSONObject(body)
            val items = json.optJSONArray("items") ?: JSONArray()
            if (items.length() == 0) {
                return ToolResult(true, "No actions found for app '$app'. Check the app name is correct.")
            }
            val actions = (0 until items.length()).joinToString("\n") { i ->
                val a = items.getJSONObject(i)
                "• ${a.optString("name", "?")} — ${a.optString("description", "").take(80)}"
            }
            ZeroClawService.log("COMPOSIO: listed ${items.length()} actions for $app")
            ToolResult(true, "Actions for $app:\n$actions\n\nUse execute with action_id='ACTION_NAME' to run.")
        } catch (e: Exception) {
            ToolResult(false, "", "Composio list_actions error: ${e.message}")
        }
    }

    private suspend fun executeAction(apiKey: String, actionId: String, paramsJson: String): ToolResult {
        return try {
            val body = JSONObject().apply {
                put("input", JSONObject(paramsJson))
            }.toString().toRequestBody("application/json".toMediaType())
            val req = Request.Builder()
                .url("https://backend.composio.dev/api/v2/actions/$actionId/execute")
                .addHeader("x-api-key", apiKey)
                .post(body)
                .build()
            val response = client.newCall(req).execute()
            val responseBody = response.body?.string() ?: ""
            if (!response.isSuccessful) {
                return ToolResult(false, "", "Composio execute error ${response.code}: ${responseBody.take(200)}")
            }
            val json = JSONObject(responseBody)
            val result = json.optJSONObject("response")?.optString("data") ?: responseBody.take(500)
            ZeroClawService.log("COMPOSIO: executed $actionId — ${result.length} chars")
            ToolResult(true, "Composio $actionId result:\n$result")
        } catch (e: Exception) {
            ToolResult(false, "", "Composio execute error: ${e.message}")
        }
    }
}

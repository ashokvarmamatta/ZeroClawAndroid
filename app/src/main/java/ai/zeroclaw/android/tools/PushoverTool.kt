package ai.zeroclaw.android.tools

import android.content.Context
import ai.zeroclaw.android.data.AppSettings
import ai.zeroclaw.android.service.ZeroClawService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * PushoverTool — Phase 151: Push notifications to any device via Pushover API.
 *
 * Pushover delivers push notifications to iOS, Android, and desktop.
 * Use it to send alerts from agents, cron jobs, or triggered events
 * directly to your phone even when ZeroClaw is messaging someone else's device.
 *
 * Setup: Create account at pushover.net, get API token + user key.
 * Free tier: 10,000 messages/month per app.
 *
 * Disabled by default — requires Pushover token + user key.
 */
class PushoverTool(private val context: Context) : Tool {
    override val name = "pushover"
    override val description = "Send a push notification to your devices via Pushover. Instant delivery to iOS/Android. Useful for alerts from cron jobs or agents."
    override val parameters = listOf(
        ToolParam("message", "string", "The notification message to send"),
        ToolParam("title", "string", "Notification title (optional)", required = false),
        ToolParam("priority", "string", "Priority: -1 (quiet), 0 (normal, default), 1 (high), 2 (emergency)", required = false),
        ToolParam("url", "string", "Optional URL to attach to the notification", required = false),
        ToolParam("url_title", "string", "Link text for the URL", required = false)
    )

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    override suspend fun execute(args: Map<String, String>): ToolResult = withContext(Dispatchers.IO) {
        val prefs = AppSettings.dataStore(context).data.first()
        val token = prefs[AppSettings.KEY_PUSHOVER_TOKEN] ?: ""
        val userKey = prefs[AppSettings.KEY_PUSHOVER_USER_KEY] ?: ""

        if (token.isBlank() || userKey.isBlank()) {
            return@withContext ToolResult(false, "",
                "Pushover not configured. Go to Settings → Pushover → enter API Token and User Key.")
        }

        val message = args["message"] ?: return@withContext ToolResult(false, "", "Missing 'message' parameter")
        val title = args["title"] ?: "ZeroClaw"
        val priority = args["priority"]?.toIntOrNull()?.coerceIn(-1, 2) ?: 0

        val bodyBuilder = FormBody.Builder()
            .add("token", token)
            .add("user", userKey)
            .add("message", message.take(1024))
            .add("title", title.take(250))
            .add("priority", priority.toString())

        args["url"]?.takeIf { it.isNotBlank() }?.let { bodyBuilder.add("url", it) }
        args["url_title"]?.takeIf { it.isNotBlank() }?.let { bodyBuilder.add("url_title", it) }

        // Emergency priority requires retry + expire parameters
        if (priority == 2) {
            bodyBuilder.add("retry", "60")
            bodyBuilder.add("expire", "3600")
        }

        try {
            val req = Request.Builder()
                .url("https://api.pushover.net/1/messages.json")
                .post(bodyBuilder.build())
                .build()
            val response = client.newCall(req).execute()
            val responseBody = response.body?.string() ?: ""
            val json = runCatching { JSONObject(responseBody) }.getOrNull()
            if (!response.isSuccessful || json?.optInt("status") != 1) {
                val errors = json?.optJSONArray("errors")?.let { arr ->
                    (0 until arr.length()).joinToString(", ") { arr.getString(it) }
                } ?: "HTTP ${response.code}"
                return@withContext ToolResult(false, "", "Pushover failed: $errors")
            }
            ZeroClawService.log("PUSHOVER: notification sent — \"${message.take(60)}\"")
            ToolResult(true, "Push notification sent: \"${message.take(100)}\"")
        } catch (e: Exception) {
            ToolResult(false, "", "Pushover error: ${e.message}")
        }
    }
}

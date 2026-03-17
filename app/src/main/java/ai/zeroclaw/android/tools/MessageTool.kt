package ai.zeroclaw.android.tools

import android.content.Context
import ai.zeroclaw.android.service.ZeroClawService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * MessageTool — Phase 143: Proactive cross-channel messaging from agents or cron jobs.
 *
 * Lets an agent or scheduled cron task send a message to any connected channel
 * without waiting for user input first. Use for alerts, digests, and notifications.
 *
 * Example: A cron job runs every morning and uses MessageTool to send a news summary
 * to a Telegram chat without any user trigger.
 *
 * Disabled by default — enable when you want agents to send proactive messages.
 */
class MessageTool(private val context: Context) : Tool {
    override val name = "message"
    override val description = "Send a proactive message to any connected channel (Telegram, Discord, Slack) without waiting for user input. Use for alerts, summaries, or notifications from cron jobs."
    override val parameters = listOf(
        ToolParam("channel", "string", "Channel to send to: 'telegram', 'discord', 'slack'"),
        ToolParam("chat_id", "string", "Target chat/channel ID to send the message to"),
        ToolParam("text", "string", "The message text to send")
    )

    override suspend fun execute(args: Map<String, String>): ToolResult = withContext(Dispatchers.IO) {
        val channel = args["channel"]?.lowercase()
            ?: return@withContext ToolResult(false, "", "Missing 'channel' parameter. Use: telegram, discord, slack")
        val chatId = args["chat_id"]
            ?: return@withContext ToolResult(false, "", "Missing 'chat_id' parameter")
        val text = args["text"]
            ?: return@withContext ToolResult(false, "", "Missing 'text' parameter")

        if (text.isBlank()) {
            return@withContext ToolResult(false, "", "Message text cannot be empty")
        }
        if (text.length > 4000) {
            return@withContext ToolResult(false, "",
                "Message too long (max 4000 chars). Current: ${text.length} chars.")
        }

        // Validate channel connection before attempting send
        val connected = when (channel) {
            "telegram" -> ZeroClawService.telegramConnected
            "discord"  -> ZeroClawService.discordConnected
            "slack"    -> ZeroClawService.slackConnected
            else       -> return@withContext ToolResult(false, "",
                "Unknown channel '$channel'. Supported: telegram, discord, slack.")
        }
        if (!connected) {
            return@withContext ToolResult(false, "",
                "$channel is not connected. Start the service with $channel credentials configured in Settings.")
        }

        val svc = ZeroClawService.instance
            ?: return@withContext ToolResult(false, "", "ZeroClaw service is not running.")

        try {
            svc.sendProactive(channel, chatId, text)
            ZeroClawService.log("MESSAGE: ✓ sent to $channel/$chatId")
            ToolResult(true, "Message sent to $channel chat $chatId.")
        } catch (e: Exception) {
            ToolResult(false, "", "Failed to send message: ${e.message}")
        }
    }
}

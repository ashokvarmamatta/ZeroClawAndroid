package ai.zeroclaw.android.infra

import ai.zeroclaw.android.service.ZeroClawService

/**
 * GroupChatSupport — Phase 140: Handle Telegram/Discord group messages with @mention.
 *
 * In group chats, the bot should only respond when:
 * 1. Directly @mentioned (e.g. "@MyBot what is the weather?")
 * 2. Replying to a previous bot message
 * 3. A command is sent (/help, /ask, etc.)
 *
 * This prevents the bot from responding to every message in busy group chats.
 *
 * Features:
 * - Extract @mention from message text
 * - Configurable bot usernames per channel
 * - Command prefix support (/ask, /ai)
 * - Reply-chain tracking: bot responds if someone replied to its message
 * - Clean message stripping: remove @mention prefix before sending to LLM
 */
object GroupChatSupport {

    data class GroupConfig(
        val botUsername: String = "",     // e.g. "myzeroclaw_bot" for Telegram
        val respondToCommands: Boolean = true,
        val commandPrefixes: List<String> = listOf("/ask", "/ai", "/zeroclaw"),
        val respondToReplies: Boolean = true   // respond if replying to bot's message
    )

    private val configs = mutableMapOf<String, GroupConfig>() // channel -> config
    private val botSentMessageIds = mutableMapOf<String, MutableSet<String>>() // chatId -> messageIds

    /**
     * Register the bot's config for a channel.
     */
    fun setConfig(channel: String, config: GroupConfig) {
        configs[channel] = config
        ZeroClawService.log("GROUP: config set for $channel — @${config.botUsername}")
    }

    fun getConfig(channel: String): GroupConfig = configs[channel] ?: GroupConfig()

    /**
     * Determine if the bot should respond to a group message.
     *
     * @param channel     The channel (telegram, discord, etc.)
     * @param chatId      The group chat ID
     * @param message     The raw message text
     * @param replyToId   The message ID being replied to (null if not a reply)
     * @param isGroup     Whether this is a group/supergroup chat
     */
    fun shouldRespond(
        channel: String,
        chatId: String,
        message: String,
        replyToId: String? = null,
        isGroup: Boolean = true
    ): Boolean {
        if (!isGroup) return true  // Always respond in DMs

        val config = getConfig(channel)
        val text = message.trim()

        // Check @mention
        if (config.botUsername.isNotBlank()) {
            val mention = "@${config.botUsername}"
            if (text.contains(mention, ignoreCase = true)) return true
        }

        // Check command prefixes
        if (config.respondToCommands) {
            for (prefix in config.commandPrefixes) {
                if (text.startsWith(prefix, ignoreCase = true)) return true
            }
            // Also respond to any /command
            if (text.startsWith("/")) return true
        }

        // Check reply chain
        if (config.respondToReplies && replyToId != null) {
            val botMsgIds = botSentMessageIds[chatId] ?: emptySet<String>()
            if (replyToId in botMsgIds) return true
        }

        return false
    }

    /**
     * Strip @mention and command prefixes from message before sending to LLM.
     * Returns the clean message text.
     */
    fun cleanMessage(channel: String, message: String): String {
        val config = getConfig(channel)
        var text = message.trim()

        // Remove @mention
        if (config.botUsername.isNotBlank()) {
            text = text.replace("@${config.botUsername}", "", ignoreCase = true).trim()
        }

        // Remove command prefixes
        for (prefix in config.commandPrefixes) {
            if (text.startsWith(prefix, ignoreCase = true)) {
                text = text.removePrefix(prefix).trim()
                break
            }
        }

        return text.ifBlank { message }
    }

    /**
     * Record a message ID sent by the bot in a group (for reply-chain detection).
     */
    fun recordBotMessage(chatId: String, messageId: String) {
        val set = botSentMessageIds.getOrPut(chatId) { mutableSetOf() }
        set.add(messageId)
        // Keep last 100 message IDs per chat to avoid unbounded growth
        if (set.size > 100) {
            val oldest = set.first()
            set.remove(oldest)
        }
    }

    /**
     * Format a group context prefix for the system prompt.
     * Tells the LLM it's in a group chat context.
     */
    fun groupContextPrompt(chatId: String, channel: String): String {
        return "You are in a group chat on $channel (chat: $chatId). " +
            "Be concise. Only address the specific question or command directed at you."
    }

    /**
     * Parse a Telegram group message and determine if this is a group chat.
     * Returns true if the chatId suggests a group (Telegram group IDs are negative).
     */
    fun isTelegramGroup(chatId: String): Boolean {
        return chatId.toLongOrNull()?.let { it < 0 } ?: false
    }
}

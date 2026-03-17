package ai.zeroclaw.android.ai

import android.content.Context
import ai.zeroclaw.android.data.LlmRouter
import ai.zeroclaw.android.service.ZeroClawService

/**
 * ConversationSummarizer — Phase 117: Auto-compress long chat histories.
 *
 * When a conversation history exceeds a token budget, this summarizer compresses
 * older messages into a concise summary while preserving key context.
 *
 * Inspired by OpenClaw's compaction.ts system:
 * - Split messages by token share
 * - Generate partial summaries
 * - Merge into a single compacted summary
 * - Preserve: active tasks, decisions, identifiers, key facts
 */
class ConversationSummarizer(private val context: Context) {

    companion object {
        // Approximate tokens per character (rough estimate for English)
        private const val CHARS_PER_TOKEN = 4

        // When history exceeds this many estimated tokens, trigger compaction
        const val COMPACTION_THRESHOLD_TOKENS = 3000  // ~12000 chars
        // Keep this many recent messages uncompacted
        const val KEEP_RECENT_MESSAGES = 6
        // Target summary size (in chars)
        const val TARGET_SUMMARY_CHARS = 1500

        @Volatile private var INSTANCE: ConversationSummarizer? = null
        fun getInstance(context: Context): ConversationSummarizer {
            return INSTANCE ?: synchronized(this) {
                ConversationSummarizer(context.applicationContext).also { INSTANCE = it }
            }
        }
    }

    /**
     * Check if a conversation history needs compaction.
     */
    fun needsCompaction(history: List<LlmRouter.ChatMessage>): Boolean {
        if (history.size <= KEEP_RECENT_MESSAGES + 2) return false
        val totalChars = history.sumOf { it.content.length }
        val estimatedTokens = totalChars / CHARS_PER_TOKEN
        return estimatedTokens > COMPACTION_THRESHOLD_TOKENS
    }

    /**
     * Compact a conversation history by summarizing older messages.
     * Returns a new history with a summary message followed by recent messages.
     */
    suspend fun compact(
        history: List<LlmRouter.ChatMessage>,
        chatId: String
    ): List<LlmRouter.ChatMessage> {
        if (!needsCompaction(history)) return history

        val oldMessages = history.dropLast(KEEP_RECENT_MESSAGES)
        val recentMessages = history.takeLast(KEEP_RECENT_MESSAGES)

        if (oldMessages.isEmpty()) return history

        ZeroClawService.log("COMPACT: compacting ${oldMessages.size} old messages for $chatId")

        // Check if first message is already a summary
        val existingSummary = if (oldMessages.first().role == "system" &&
            oldMessages.first().content.startsWith("[Conversation Summary]")) {
            oldMessages.first().content
        } else null

        // Build the text to summarize
        val textToSummarize = buildString {
            if (existingSummary != null) {
                appendLine("Previous summary:\n$existingSummary\n")
                appendLine("Additional conversation:")
                oldMessages.drop(1).forEach { msg ->
                    appendLine("${msg.role}: ${msg.content.take(500)}")
                }
            } else {
                oldMessages.forEach { msg ->
                    appendLine("${msg.role}: ${msg.content.take(500)}")
                }
            }
        }

        // Generate summary using extractive approach (no LLM needed)
        val summary = extractiveSummarize(textToSummarize)

        ZeroClawService.log("COMPACT: reduced ${oldMessages.sumOf { it.content.length }} chars → ${summary.length} chars")

        // Build new compacted history
        val compactedHistory = mutableListOf<LlmRouter.ChatMessage>()
        compactedHistory.add(LlmRouter.ChatMessage("system", "[Conversation Summary]\n$summary"))
        compactedHistory.addAll(recentMessages)
        return compactedHistory
    }

    /**
     * Extractive summarization — no LLM call needed.
     * Extracts key information from the conversation:
     * - User requests / questions
     * - AI decisions / answers
     * - Key facts mentioned
     * - Active tasks / todos
     */
    private fun extractiveSummarize(text: String): String {
        val lines = text.lines().filter { it.isNotBlank() }
        if (lines.isEmpty()) return ""

        val summary = StringBuilder()

        // Extract user messages (questions/requests)
        val userMessages = lines.filter { it.startsWith("user:") }
            .map { it.removePrefix("user:").trim() }
        val assistantMessages = lines.filter { it.startsWith("assistant:") }
            .map { it.removePrefix("assistant:").trim() }

        // Extract key topics from user messages
        val topics = mutableSetOf<String>()
        for (msg in userMessages) {
            // Extract noun phrases / key concepts (simple heuristic)
            val words = msg.split(Regex("\\s+")).filter { it.length > 3 }
            topics.addAll(words.take(5).map { it.lowercase().removeSuffix("?").removeSuffix(".") })
        }

        summary.appendLine("Topics discussed: ${topics.take(15).joinToString(", ")}")
        summary.appendLine()

        // Include condensed user questions
        summary.appendLine("User asked about:")
        for (msg in userMessages.takeLast(8)) {
            summary.appendLine("- ${msg.take(150)}")
        }
        summary.appendLine()

        // Include key points from assistant responses
        summary.appendLine("Key points from AI responses:")
        for (msg in assistantMessages.takeLast(8)) {
            // Extract first sentence as the key point
            val firstSentence = msg.split(Regex("[.!?]")).firstOrNull { it.trim().length > 10 }
                ?.trim()?.let { "$it." } ?: msg.take(150)
            summary.appendLine("- ${firstSentence.take(150)}")
        }

        // Extract any identifiers (URLs, numbers, names)
        val identifiers = mutableSetOf<String>()
        val urlPattern = Regex("https?://\\S+")
        val emailPattern = Regex("[\\w.]+@[\\w.]+")
        for (line in lines) {
            urlPattern.findAll(line).forEach { identifiers.add(it.value) }
            emailPattern.findAll(line).forEach { identifiers.add(it.value) }
        }
        if (identifiers.isNotEmpty()) {
            summary.appendLine()
            summary.appendLine("Referenced: ${identifiers.take(5).joinToString(", ")}")
        }

        // Truncate to target size
        val result = summary.toString()
        return if (result.length > TARGET_SUMMARY_CHARS) {
            result.take(TARGET_SUMMARY_CHARS) + "..."
        } else result
    }

    /**
     * LLM-powered summarization — more accurate but requires an API call.
     * Use this when an online model is available and quality matters.
     */
    suspend fun llmSummarize(
        text: String,
        chatId: String
    ): String {
        val llmRouter = LlmRouter.getInstance(context)
        val prompt = """Summarize this conversation concisely. Preserve:
- Key decisions and conclusions
- Active tasks or todos
- Important facts, names, URLs, numbers
- User preferences expressed

Conversation:
${text.take(4000)}

Write a concise summary (max 500 words):"""

        return try {
            val summaryChatId = "summarizer_$chatId"
            llmRouter.call(prompt, chatId = summaryChatId)
        } catch (e: Exception) {
            ZeroClawService.log("COMPACT: LLM summarize failed — ${e.message}")
            extractiveSummarize(text)
        }
    }
}

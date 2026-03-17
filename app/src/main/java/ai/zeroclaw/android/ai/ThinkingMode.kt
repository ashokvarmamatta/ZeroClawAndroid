package ai.zeroclaw.android.ai

import android.content.Context
import ai.zeroclaw.android.data.LlmRouter
import ai.zeroclaw.android.service.ZeroClawService

/**
 * ThinkingMode — Phase 116: Extended thinking/reasoning for complex problems.
 *
 * When the user asks a complex question or explicitly requests "think step by step",
 * this wraps the query in a chain-of-thought prompt that forces the LLM to reason
 * through the problem before answering.
 *
 * Supports three modes:
 * - Auto: detect complex questions automatically
 * - Explicit: user says "think about", "reason through", "step by step"
 * - Off: standard responses
 *
 * Inspired by OpenClaw's extended thinking and reasoning tags.
 */
class ThinkingMode(private val context: Context) {

    enum class Mode { AUTO, EXPLICIT, OFF }

    companion object {
        // Complexity indicators that suggest deep thinking is needed
        private val COMPLEXITY_PATTERNS = listOf(
            Regex("(?:compare|contrast|difference between|pros and cons|trade.?off)", RegexOption.IGNORE_CASE),
            Regex("(?:why (?:does|would|should|is|are|do|did|can|could))", RegexOption.IGNORE_CASE),
            Regex("(?:how (?:would|should|could|can) (?:I|you|we|one))", RegexOption.IGNORE_CASE),
            Regex("(?:explain|analyze|evaluate|assess|critique|review|debug)", RegexOption.IGNORE_CASE),
            Regex("(?:what (?:are the|would be|is the best|approach|strategy))", RegexOption.IGNORE_CASE),
            Regex("(?:design|architect|plan|implement|optimize|refactor)", RegexOption.IGNORE_CASE),
            Regex("(?:if .+ then .+ else|given that|assuming|considering)", RegexOption.IGNORE_CASE),
            Regex("(?:what happens when|what if|suppose|imagine)", RegexOption.IGNORE_CASE),
            Regex("(?:solve|prove|derive|calculate complex|mathematical)", RegexOption.IGNORE_CASE)
        )

        // Explicit thinking triggers from user
        private val EXPLICIT_TRIGGERS = listOf(
            "think step by step", "think about this", "reason through",
            "think carefully", "think deeply", "let me think",
            "think hard", "think it through", "reasoning mode",
            "chain of thought", "show your reasoning", "show your work",
            "think before answering", "take your time"
        )

        @Volatile private var INSTANCE: ThinkingMode? = null
        fun getInstance(context: Context): ThinkingMode {
            return INSTANCE ?: synchronized(this) {
                ThinkingMode(context.applicationContext).also { INSTANCE = it }
            }
        }
    }

    /**
     * Detect if the message should trigger thinking mode.
     */
    fun shouldThink(message: String, mode: Mode = Mode.AUTO): Boolean {
        if (mode == Mode.OFF) return false
        if (mode == Mode.EXPLICIT) {
            return EXPLICIT_TRIGGERS.any { message.contains(it, ignoreCase = true) }
        }
        // Auto mode: check both explicit triggers and complexity patterns
        if (EXPLICIT_TRIGGERS.any { message.contains(it, ignoreCase = true) }) return true
        // Count complexity indicators (need 2+ for auto-trigger to avoid false positives)
        val complexityScore = COMPLEXITY_PATTERNS.count { it.containsMatchIn(message) }
        return complexityScore >= 2 || message.length > 300
    }

    /**
     * Wrap a message with chain-of-thought reasoning prompt.
     * Returns a modified system prompt addition that enables thinking.
     */
    fun buildThinkingPromptAddition(): String = """

## Extended Thinking Mode
You are in extended thinking mode. For this response, you MUST:
1. **Analyze** the question — identify what's being asked and what knowledge is needed
2. **Break down** the problem into smaller sub-problems
3. **Reason** through each sub-problem step by step
4. **Consider** alternative approaches or perspectives
5. **Synthesize** your reasoning into a clear, well-structured answer

Structure your response as:
<thinking>
[Your step-by-step reasoning process — be thorough]
</thinking>

<answer>
[Your final, polished answer based on the reasoning above]
</answer>
"""

    /**
     * Extract the final answer from a thinking-mode response.
     * If the response contains <answer> tags, return just the answer.
     * Otherwise return the full response.
     */
    fun extractAnswer(response: String): String {
        val answerMatch = Regex("<answer>\\s*([\\s\\S]*?)\\s*</answer>").find(response)
        if (answerMatch != null) {
            return answerMatch.groupValues[1].trim()
        }
        // If no tags, return the full response (model didn't follow the format)
        return response
    }

    /**
     * Extract thinking process (for logging/display).
     */
    fun extractThinking(response: String): String? {
        val thinkingMatch = Regex("<thinking>\\s*([\\s\\S]*?)\\s*</thinking>").find(response)
        return thinkingMatch?.groupValues?.get(1)?.trim()
    }

    /**
     * Get complexity score for a message (0-10).
     */
    fun complexityScore(message: String): Int {
        var score = 0
        score += COMPLEXITY_PATTERNS.count { it.containsMatchIn(message) }
        if (message.length > 200) score += 1
        if (message.length > 500) score += 1
        if (message.contains("?") && message.count { it == '?' } > 1) score += 1
        if (message.lines().size > 3) score += 1
        return score.coerceAtMost(10)
    }
}

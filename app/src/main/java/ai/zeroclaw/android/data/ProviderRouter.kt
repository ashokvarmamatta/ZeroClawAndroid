package ai.zeroclaw.android.data

import ai.zeroclaw.android.service.ZeroClawService

/**
 * ProviderRouter — Phase 149: Hint-based model routing + per-model fallback chains.
 *
 * Routes LLM calls to specific providers/models based on hints in the message:
 * - "hint:reasoning" → prefers Claude Sonnet or o1 (deep reasoning)
 * - "hint:vision" → prefers GPT-4o or Gemini Vision (image analysis)
 * - "hint:fast" → prefers Haiku or GPT-4o-mini (speed over quality)
 * - "hint:code" → prefers Claude or GPT-4 (code generation)
 * - "hint:creative" → prefers GPT-4o or Claude Sonnet (creative tasks)
 * - "hint:long" → prefers Gemini (long context window)
 *
 * Hints can be injected by: tools, agents, workflows, or users directly.
 * The main LlmRouter uses this to select the best key from the configured pool.
 */
object ProviderRouter {

    data class RoutingHint(
        val type: String,
        val preferredProviders: List<String>,  // in preference order
        val preferredModels: List<String>       // model names to prefer
    )

    val HINT_MAP = mapOf(
        "reasoning" to RoutingHint("reasoning",
            listOf("anthropic", "openai"),
            listOf("claude-sonnet-4-6", "claude-opus-4-6", "o1", "o3-mini")),
        "vision" to RoutingHint("vision",
            listOf("openai", "gemini"),
            listOf("gpt-4o", "gpt-4-vision-preview", "gemini-pro-vision")),
        "fast" to RoutingHint("fast",
            listOf("anthropic", "openai", "gemini"),
            listOf("claude-haiku-4-5-20251001", "gpt-4o-mini", "gemini-1.5-flash")),
        "code" to RoutingHint("code",
            listOf("anthropic", "openai"),
            listOf("claude-sonnet-4-6", "gpt-4o", "claude-haiku-4-5-20251001")),
        "creative" to RoutingHint("creative",
            listOf("openai", "anthropic"),
            listOf("gpt-4o", "claude-sonnet-4-6", "claude-opus-4-6")),
        "long" to RoutingHint("long",
            listOf("gemini", "openai", "anthropic"),
            listOf("gemini-1.5-pro", "gpt-4-turbo", "claude-opus-4-6"))
    )

    /**
     * Extract hint from message text. Hints are prefixed with "hint:".
     * E.g. "hint:reasoning Explain quantum entanglement"
     * Returns Pair(hint type or null, cleaned message with hint stripped)
     */
    fun extractHint(message: String): Pair<String?, String> {
        val hintRegex = Regex("hint:(\\w+)\\s*", RegexOption.IGNORE_CASE)
        val match = hintRegex.find(message.trimStart()) ?: return Pair(null, message)
        val hintType = match.groupValues[1].lowercase()
        val cleanedMessage = message.replace(match.value, "").trim()
        return Pair(hintType, cleanedMessage)
    }

    /**
     * Given a list of API keys and a hint type, return the keys sorted
     * so that preferred providers appear first.
     */
    fun sortKeysByHint(keys: List<ApiKeyEntry>, hintType: String?): List<ApiKeyEntry> {
        if (hintType == null) return keys
        val hint = HINT_MAP[hintType] ?: return keys

        return keys.sortedWith(Comparator { a, b ->
            val aRank = hint.preferredProviders.indexOf(a.safeProvider)
                .let { if (it < 0) Int.MAX_VALUE else it }
            val bRank = hint.preferredProviders.indexOf(b.safeProvider)
                .let { if (it < 0) Int.MAX_VALUE else it }
            aRank.compareTo(bRank)
        }).also {
            ZeroClawService.log("ROUTING: hint=$hintType → preferred ${hint.preferredProviders.firstOrNull()}")
        }
    }

    /**
     * Per-model fallback chain: if the preferred model fails, which model to try next?
     */
    val MODEL_FALLBACKS = mapOf(
        "gpt-4o" to listOf("gpt-4o-mini", "gpt-3.5-turbo"),
        "claude-opus-4-6" to listOf("claude-sonnet-4-6", "claude-haiku-4-5-20251001"),
        "claude-sonnet-4-6" to listOf("claude-haiku-4-5-20251001"),
        "gemini-1.5-pro" to listOf("gemini-1.5-flash", "gemini-pro"),
        "o1" to listOf("o1-mini", "gpt-4o")
    )

    fun fallbackFor(model: String): String? = MODEL_FALLBACKS[model]?.firstOrNull()
}

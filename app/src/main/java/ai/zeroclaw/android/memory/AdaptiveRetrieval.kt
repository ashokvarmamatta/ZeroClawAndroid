package ai.zeroclaw.android.memory

import ai.zeroclaw.android.service.ZeroClawService

/**
 * AdaptiveRetrieval — Phase 145: Auto-select the optimal retrieval strategy.
 *
 * Analyzes query characteristics and selects the best search mode:
 * - KEYWORD_ONLY: Short queries, code/technical terms, exact matches
 * - VECTOR_ONLY: Long natural language questions, conceptual queries
 * - HYBRID: Default for mixed queries (best overall quality)
 *
 * Inspired by nullclaw's adaptive query routing logic.
 */
object AdaptiveRetrieval {

    enum class Strategy { KEYWORD_ONLY, VECTOR_ONLY, HYBRID }

    data class RetrievalPlan(
        val strategy: Strategy,
        val reason: String,
        val expandQuery: Boolean = true,
        val topK: Int = 10,
        val mmrEnabled: Boolean = true
    )

    /**
     * Analyze a query and return the optimal retrieval plan.
     */
    fun planFor(query: String): RetrievalPlan {
        val lower = query.lowercase().trim()
        val wordCount = lower.split(Regex("\\s+")).size
        val hasSpecialChars = lower.any { it in "._#@/\\:" }
        val isQuestion = lower.endsWith("?") || lower.startsWith(Regex("(what|who|when|where|why|how)"))
        val isLong = wordCount >= 7
        val isShort = wordCount <= 3

        // Code/technical identifiers → keyword search is better
        if (hasSpecialChars || (isShort && lower.any { it.isUpperCase() })) {
            ZeroClawService.log("RETRIEVAL: KEYWORD_ONLY — technical/code query: \"${query.take(40)}\"")
            return RetrievalPlan(
                strategy = Strategy.KEYWORD_ONLY,
                reason = "Short technical query with identifiers",
                expandQuery = false,
                mmrEnabled = false
            )
        }

        // Long natural language questions → vector is better at capturing meaning
        if (isLong && isQuestion) {
            ZeroClawService.log("RETRIEVAL: VECTOR_ONLY — long conceptual query: \"${query.take(40)}\"")
            return RetrievalPlan(
                strategy = Strategy.VECTOR_ONLY,
                reason = "Long natural language question",
                expandQuery = true,
                topK = 8,
                mmrEnabled = true
            )
        }

        // Default: hybrid for best coverage
        ZeroClawService.log("RETRIEVAL: HYBRID — mixed query: \"${query.take(40)}\"")
        return RetrievalPlan(
            strategy = Strategy.HYBRID,
            reason = "Mixed query — hybrid for best coverage",
            expandQuery = true,
            topK = 10,
            mmrEnabled = true
        )
    }

    private fun String.startsWith(regex: Regex): Boolean = regex.containsMatchIn(this.take(10))
}

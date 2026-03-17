package ai.zeroclaw.android.memory

import ai.zeroclaw.android.service.ZeroClawService
import kotlin.math.min

/**
 * MmrReranker — Phase 145: Maximal Marginal Relevance reranking for memory search.
 *
 * After RRF fusion, the top-N results may be redundant (same topic, similar content).
 * MMR balances relevance with diversity: each selected result is chosen to maximize
 * relevance to the query while minimizing redundancy with already-selected results.
 *
 * Uses Jaccard token similarity as the diversity metric (fast, no embedding needed).
 * Inspired by nullclaw's MMR implementation.
 */
object MmrReranker {

    data class RankedResult(
        val id: String,
        val text: String,
        val score: Double
    )

    /**
     * Rerank results using MMR.
     *
     * @param results Candidate results (id, text, relevance score)
     * @param queryTokens Tokenized query for relevance scoring
     * @param topK How many results to return
     * @param lambda Balance factor: 1.0 = pure relevance, 0.0 = pure diversity (0.5 default)
     */
    fun rerank(
        results: List<RankedResult>,
        queryTokens: Set<String>,
        topK: Int = 5,
        lambda: Double = 0.5
    ): List<RankedResult> {
        if (results.isEmpty()) return emptyList()
        if (results.size <= topK) return results

        val selected = mutableListOf<RankedResult>()
        val candidates = results.toMutableList()

        // Select iteratively using MMR criterion
        repeat(min(topK, results.size)) {
            val best = candidates.maxByOrNull { candidate ->
                val relevance = candidate.score
                val maxSimilarity = if (selected.isEmpty()) 0.0 else
                    selected.maxOf { jaccardSimilarity(tokenize(candidate.text), tokenize(it.text)) }
                lambda * relevance - (1.0 - lambda) * maxSimilarity
            } ?: return@repeat
            selected.add(best)
            candidates.remove(best)
        }

        ZeroClawService.log("MMR: reranked ${results.size} → ${selected.size} results (lambda=$lambda)")
        return selected
    }

    /**
     * Jaccard similarity: |A ∩ B| / |A ∪ B|
     */
    private fun jaccardSimilarity(a: Set<String>, b: Set<String>): Double {
        if (a.isEmpty() && b.isEmpty()) return 1.0
        val intersection = a.intersect(b).size
        val union = a.union(b).size
        return if (union == 0) 0.0 else intersection.toDouble() / union
    }

    /**
     * Tokenize text into lowercase word tokens, filtering stop words.
     */
    fun tokenize(text: String): Set<String> {
        val stopWords = setOf("the", "a", "an", "is", "in", "on", "at", "to", "for",
            "of", "and", "or", "but", "with", "by", "from", "as", "this", "that")
        return text.lowercase()
            .replace(Regex("[^a-z0-9\\s]"), " ")
            .split(Regex("\\s+"))
            .filter { it.length > 2 && it !in stopWords }
            .toSet()
    }
}

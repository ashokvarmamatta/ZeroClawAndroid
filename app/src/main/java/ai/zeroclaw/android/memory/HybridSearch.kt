package ai.zeroclaw.android.memory

import android.content.Context
import ai.zeroclaw.android.data.MemoryDatabase
import ai.zeroclaw.android.data.MemoryEntity
import ai.zeroclaw.android.service.ZeroClawService

/**
 * HybridSearch — Phase 119: Combine semantic + keyword search for better recall.
 *
 * Merges vector similarity (semantic meaning) with keyword search (exact terms)
 * using configurable weights. Also applies query expansion and temporal decay.
 *
 * Pipeline:
 * 1. Expand query with synonyms/related terms (QueryExpansion)
 * 2. Keyword search on all expanded queries (SQLite LIKE)
 * 3. Vector search for semantic similarity (VectorMemory)
 * 4. Merge and re-rank with RRF (Reciprocal Rank Fusion)
 * 5. Apply temporal decay (TemporalDecay)
 *
 * Inspired by OpenClaw's memory/hybrid.ts.
 */
class HybridSearch(private val context: Context) {

    data class SearchResult(
        val memory: MemoryEntity,
        val score: Float,
        val source: String,       // "hybrid", "vector", "keyword"
        val recencyLabel: String
    )

    companion object {
        // Weights for score fusion (must sum to 1.0)
        const val VECTOR_WEIGHT = 0.6f    // semantic similarity dominates
        const val KEYWORD_WEIGHT = 0.4f   // keyword overlap contributes

        // RRF constant (k=60 from Cormack et al.)
        const val RRF_K = 60

        const val TOP_K = 10

        @Volatile private var INSTANCE: HybridSearch? = null
        fun getInstance(context: Context): HybridSearch {
            return INSTANCE ?: synchronized(this) {
                HybridSearch(context.applicationContext).also { INSTANCE = it }
            }
        }
    }

    private val vectorMemory = VectorMemory.getInstance(context)

    /**
     * Main hybrid search entry point.
     * Returns merged, re-ranked, and decay-adjusted results.
     */
    suspend fun search(
        userId: String,
        query: String,
        topK: Int = TOP_K,
        halfLifeDays: Double = TemporalDecay.DEFAULT_HALF_LIFE_DAYS
    ): List<SearchResult> {
        val dao = MemoryDatabase.getInstance(context).memoryDao()

        // 1. Expand query
        val expandedQueries = QueryExpansion.expand(query)
        ZeroClawService.log("HYBRID: searching with ${expandedQueries.size} query variants")

        // 2. Keyword search — collect all LIKE matches across expanded queries
        val keywordResults = mutableMapOf<Long, Pair<MemoryEntity, Float>>()
        for (q in expandedQueries) {
            val results = dao.search(userId, q.take(100))
            for (mem in results) {
                val score = QueryExpansion.keywordScore(query, "${mem.key} ${mem.value}")
                val existing = keywordResults[mem.id]
                if (existing == null || score > existing.second) {
                    keywordResults[mem.id] = mem to score
                }
            }
        }
        ZeroClawService.log("HYBRID: keyword search found ${keywordResults.size} matches")

        // 3. Vector search
        val vectorResults = try {
            vectorMemory.semanticSearch(userId, query, topK = topK * 2)
        } catch (e: Exception) {
            ZeroClawService.log("HYBRID: vector search failed — ${e.message}")
            emptyList()
        }
        ZeroClawService.log("HYBRID: vector search found ${vectorResults.size} matches")

        // 4. RRF fusion
        val fusedScores = reciprocalRankFusion(
            keywordRanked = keywordResults.values.sortedByDescending { it.second },
            vectorRanked = vectorResults.map { it.memory to it.score }
        )

        // 5. Temporal decay
        val decayed = TemporalDecay.applyToScores(fusedScores, halfLifeDays)

        // 6. Format results
        return decayed.take(topK).map { (memory, score) ->
            val source = when {
                keywordResults.containsKey(memory.id) && vectorResults.any { it.memory.id == memory.id } -> "hybrid"
                vectorResults.any { it.memory.id == memory.id } -> "vector"
                else -> "keyword"
            }
            SearchResult(
                memory = memory,
                score = score,
                source = source,
                recencyLabel = TemporalDecay.recencyLabel(memory.updatedAt)
            )
        }
    }

    /**
     * Reciprocal Rank Fusion — merges two ranked lists without needing normalized scores.
     * Score = 1/(k + rank_keyword) * KEYWORD_WEIGHT + 1/(k + rank_vector) * VECTOR_WEIGHT
     */
    private fun reciprocalRankFusion(
        keywordRanked: List<Pair<MemoryEntity, Float>>,
        vectorRanked: List<Pair<MemoryEntity, Float>>
    ): List<Pair<MemoryEntity, Float>> {
        val scores = mutableMapOf<Long, Pair<MemoryEntity, Float>>()

        // Keyword rank contributions
        keywordRanked.forEachIndexed { rank, (memory, _) ->
            val rrfScore = KEYWORD_WEIGHT / (RRF_K + rank + 1)
            val existing = scores[memory.id]
            scores[memory.id] = memory to ((existing?.second ?: 0f) + rrfScore)
        }

        // Vector rank contributions
        vectorRanked.forEachIndexed { rank, (memory, _) ->
            val rrfScore = VECTOR_WEIGHT / (RRF_K + rank + 1)
            val existing = scores[memory.id]
            scores[memory.id] = memory to ((existing?.second ?: 0f) + rrfScore)
        }

        return scores.values.sortedByDescending { it.second }
    }

    /**
     * Format search results as a readable string for the AI to consume.
     */
    fun formatResults(results: List<SearchResult>, query: String): String {
        if (results.isEmpty()) return "No memories found for: $query"
        return buildString {
            appendLine("Found ${results.size} relevant memories:")
            results.forEachIndexed { i, r ->
                val scoreStr = "%.0f%%".format(r.score * 100)
                appendLine("${i + 1}. [${r.source}, $scoreStr, ${r.recencyLabel}] ${r.memory.key}: ${r.memory.value.take(200)}")
            }
        }
    }
}

package ai.zeroclaw.android.memory

import android.content.Context
import ai.zeroclaw.android.service.ZeroClawService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.sqrt

/**
 * SemanticCache — Phase 146: LRU response cache with cosine similarity matching.
 *
 * Caches LLM responses keyed by query embedding vectors. Before calling the LLM,
 * checks if a semantically similar query was already answered and returns the
 * cached response — saving API costs and reducing latency.
 *
 * - LRU eviction: oldest entries removed when cache exceeds max size
 * - Cosine similarity threshold: queries must be >80% similar to get cache hit
 * - Cache is in-memory only (no persistence) — cleared on service restart
 */
class SemanticCache(private val context: Context) {

    data class CacheEntry(
        val queryText: String,
        val embedding: FloatArray?,
        val response: String,
        val hitCount: Int = 0,
        val createdAt: Long = System.currentTimeMillis(),
        var lastUsed: Long = System.currentTimeMillis()
    )

    companion object {
        const val MAX_SIZE = 100
        const val SIMILARITY_THRESHOLD = 0.80f  // 80% cosine similarity for cache hit
        const val MAX_AGE_MS = 30 * 60 * 1000L  // 30 minutes TTL

        @Volatile private var INSTANCE: SemanticCache? = null
        fun getInstance(context: Context): SemanticCache =
            INSTANCE ?: synchronized(this) {
                SemanticCache(context.applicationContext).also { INSTANCE = it }
            }
    }

    private val cache = LinkedHashMap<String, CacheEntry>(MAX_SIZE, 0.75f, true)

    /**
     * Check cache for a semantically similar query.
     * Returns the cached response if found, null otherwise.
     */
    suspend fun get(queryText: String, queryEmbedding: FloatArray?): String? =
        withContext(Dispatchers.Default) {
            val now = System.currentTimeMillis()
            synchronized(cache) {
                // Remove expired entries
                cache.entries.removeIf { (_, v) -> now - v.createdAt > MAX_AGE_MS }

                if (queryEmbedding == null) {
                    // Fall back to exact text match
                    return@withContext cache[queryText.lowercase().trim()]?.also {
                        it.lastUsed = now
                    }?.response
                }

                // Find best matching entry by cosine similarity
                var bestScore = 0f
                var bestEntry: CacheEntry? = null
                for (entry in cache.values) {
                    val sim = if (entry.embedding != null)
                        cosineSimilarity(queryEmbedding, entry.embedding)
                    else 0f
                    if (sim > bestScore) {
                        bestScore = sim
                        bestEntry = entry
                    }
                }

                if (bestScore >= SIMILARITY_THRESHOLD && bestEntry != null) {
                    bestEntry.lastUsed = now
                    ZeroClawService.log("CACHE: hit (similarity=${String.format("%.2f", bestScore)}) — \"${queryText.take(40)}\"")
                    bestEntry.response
                } else {
                    null
                }
            }
        }

    /**
     * Store a query-response pair in the cache.
     */
    fun put(queryText: String, embedding: FloatArray?, response: String) {
        if (response.isBlank()) return
        synchronized(cache) {
            // LRU eviction when full
            if (cache.size >= MAX_SIZE) {
                val oldest = cache.entries.minByOrNull { it.value.lastUsed }
                oldest?.let { cache.remove(it.key) }
            }
            val key = queryText.lowercase().trim()
            cache[key] = CacheEntry(queryText, embedding, response)
        }
    }

    fun clear() = synchronized(cache) { cache.clear() }
    fun size() = synchronized(cache) { cache.size }

    private fun cosineSimilarity(a: FloatArray, b: FloatArray): Float {
        if (a.size != b.size) return 0f
        var dot = 0f; var normA = 0f; var normB = 0f
        for (i in a.indices) { dot += a[i] * b[i]; normA += a[i] * a[i]; normB += b[i] * b[i] }
        val denom = sqrt(normA) * sqrt(normB)
        return if (denom == 0f) 0f else dot / denom
    }
}

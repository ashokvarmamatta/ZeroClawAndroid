package ai.zeroclaw.android.memory

import android.content.Context
import ai.zeroclaw.android.data.LlmKeyManager
import ai.zeroclaw.android.data.MemoryDatabase
import ai.zeroclaw.android.data.MemoryEntity
import ai.zeroclaw.android.service.ZeroClawService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import kotlin.math.sqrt

/**
 * VectorMemory — Phase 118: Semantic search with embeddings.
 *
 * Generates vector embeddings for memory entries using OpenAI/Gemini APIs,
 * stores them as JSON in the database, and performs cosine similarity search
 * to find semantically related memories even when keywords don't match.
 *
 * Inspired by OpenClaw's memory/embeddings.ts + memory/manager.ts.
 */
class VectorMemory(private val context: Context) {

    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    data class ScoredMemory(
        val memory: MemoryEntity,
        val score: Float,         // cosine similarity 0.0–1.0
        val source: String = "vector"
    )

    companion object {
        // Embedding dimension for OpenAI text-embedding-3-small
        const val OPENAI_EMBEDDING_DIM = 1536
        // Embedding dimension for Gemini text-embedding-004
        const val GEMINI_EMBEDDING_DIM = 768
        // Similarity threshold — below this score, results are excluded
        const val SIMILARITY_THRESHOLD = 0.35f
        // Max results to return
        const val TOP_K = 8

        @Volatile private var INSTANCE: VectorMemory? = null
        fun getInstance(context: Context): VectorMemory {
            return INSTANCE ?: synchronized(this) {
                VectorMemory(context.applicationContext).also { INSTANCE = it }
            }
        }
    }

    /**
     * Get embedding vector for a text string.
     * Tries OpenAI first, then Gemini, then falls back to TF-IDF approximation.
     */
    suspend fun embed(text: String): FloatArray? = withContext(Dispatchers.IO) {
        val keyManager = LlmKeyManager.getInstance(context)
        val keys = keyManager.loadKeys().filter { it.enabled }

        // Try OpenAI embedding
        val openAiKey = keys.firstOrNull { it.safeProvider == "openai" || it.safeProvider == "openrouter" }
        if (openAiKey != null) {
            try {
                return@withContext embedOpenAI(text, openAiKey.safeApiKey)
            } catch (e: Exception) {
                ZeroClawService.log("VECTOR: OpenAI embed failed — ${e.message}")
            }
        }

        // Try Gemini embedding
        val geminiKey = keys.firstOrNull { it.safeProvider == "gemini" }
        if (geminiKey != null) {
            try {
                return@withContext embedGemini(text, geminiKey.safeApiKey)
            } catch (e: Exception) {
                ZeroClawService.log("VECTOR: Gemini embed failed — ${e.message}")
            }
        }

        // Fallback: TF-IDF-like sparse vector approximation
        ZeroClawService.log("VECTOR: using TF-IDF fallback (no embedding API available)")
        return@withContext tfidfVector(text)
    }

    /**
     * OpenAI text-embedding-3-small (1536 dims, cheapest + great quality).
     */
    private fun embedOpenAI(text: String, apiKey: String): FloatArray {
        val body = JSONObject().apply {
            put("input", text.take(8000))
            put("model", "text-embedding-3-small")
        }.toString()

        val req = Request.Builder()
            .url("https://api.openai.com/v1/embeddings")
            .addHeader("Authorization", "Bearer $apiKey")
            .post(body.toRequestBody("application/json".toMediaType()))
            .build()

        client.newCall(req).execute().use { resp ->
            val json = JSONObject(resp.body?.string() ?: throw Exception("Empty body"))
            if (!resp.isSuccessful) throw Exception("HTTP ${resp.code}")
            val data = json.getJSONArray("data").getJSONObject(0).getJSONArray("embedding")
            return FloatArray(data.length()) { data.getDouble(it).toFloat() }
        }
    }

    /**
     * Gemini text-embedding-004 (768 dims).
     */
    private fun embedGemini(text: String, apiKey: String): FloatArray {
        val body = JSONObject().apply {
            put("model", "models/text-embedding-004")
            put("content", JSONObject().apply {
                put("parts", JSONArray().apply {
                    put(JSONObject().apply { put("text", text.take(3000)) })
                })
            })
        }.toString()

        val req = Request.Builder()
            .url("https://generativelanguage.googleapis.com/v1beta/models/text-embedding-004:embedContent?key=$apiKey")
            .post(body.toRequestBody("application/json".toMediaType()))
            .build()

        client.newCall(req).execute().use { resp ->
            val json = JSONObject(resp.body?.string() ?: throw Exception("Empty body"))
            if (!resp.isSuccessful) throw Exception("HTTP ${resp.code}")
            val values = json.getJSONObject("embedding").getJSONArray("values")
            return FloatArray(values.length()) { values.getDouble(it).toFloat() }
        }
    }

    /**
     * TF-IDF-like sparse vector for offline fallback (256 dims using char/word hashing).
     */
    private fun tfidfVector(text: String): FloatArray {
        val dim = 256
        val vec = FloatArray(dim)
        val words = text.lowercase().split(Regex("[\\s,.:;!?]+")).filter { it.length > 2 }
        for (word in words) {
            val idx = (word.hashCode() and 0x7FFFFFFF) % dim
            vec[idx] += 1.0f / (1.0f + words.count { it == word }.toFloat())
        }
        // L2 normalize
        val norm = sqrt(vec.sumOf { it * it.toDouble() }.toFloat())
        if (norm > 0) for (i in vec.indices) vec[i] /= norm
        return vec
    }

    /**
     * Cosine similarity between two vectors.
     */
    fun cosineSimilarity(a: FloatArray, b: FloatArray): Float {
        if (a.size != b.size) return 0f
        var dot = 0f; var normA = 0f; var normB = 0f
        for (i in a.indices) { dot += a[i] * b[i]; normA += a[i] * a[i]; normB += b[i] * b[i] }
        val denom = sqrt(normA) * sqrt(normB)
        return if (denom == 0f) 0f else dot / denom
    }

    /**
     * Serialize embedding to JSON string for storage.
     */
    fun serializeEmbedding(vec: FloatArray): String {
        val arr = JSONArray()
        for (v in vec) arr.put(v.toDouble())
        return arr.toString()
    }

    /**
     * Deserialize embedding from JSON string.
     */
    fun deserializeEmbedding(json: String): FloatArray? {
        if (json.isBlank()) return null
        return try {
            val arr = JSONArray(json)
            FloatArray(arr.length()) { arr.getDouble(it).toFloat() }
        } catch (_: Exception) { null }
    }

    /**
     * Store a memory with its embedding vector.
     */
    suspend fun storeWithEmbedding(memory: MemoryEntity): MemoryEntity {
        val embeddingText = "${memory.key} ${memory.value}"
        val vec = embed(embeddingText)
        val enriched = if (vec != null) {
            memory.copy(embedding = serializeEmbedding(vec))
        } else memory
        MemoryDatabase.getInstance(context).memoryDao().upsert(enriched)
        return enriched
    }

    /**
     * Semantic search: find memories most similar to the query.
     * Returns results sorted by cosine similarity (highest first).
     */
    suspend fun semanticSearch(
        userId: String,
        query: String,
        topK: Int = TOP_K,
        threshold: Float = SIMILARITY_THRESHOLD
    ): List<ScoredMemory> = withContext(Dispatchers.IO) {
        val queryVec = embed(query) ?: return@withContext emptyList()

        val allMemories = MemoryDatabase.getInstance(context).memoryDao()
            .getAllWithEmbeddings(userId)

        val scored = allMemories.mapNotNull { memory ->
            val memVec = deserializeEmbedding(memory.embedding) ?: return@mapNotNull null
            val score = cosineSimilarity(queryVec, memVec)
            if (score >= threshold) ScoredMemory(memory, score) else null
        }

        scored.sortedByDescending { it.score }.take(topK)
    }
}

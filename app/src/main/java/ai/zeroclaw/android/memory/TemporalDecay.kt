package ai.zeroclaw.android.memory

import kotlin.math.exp

/**
 * TemporalDecay — Phase 121: Weight recent memories higher in search results.
 *
 * More recent memories are more likely to be relevant. This class applies
 * exponential time decay to memory scores, so old memories gradually become
 * less prominent while retaining their semantic match score contribution.
 *
 * Score formula: decayed = original * e^(-λ * age_in_days)
 * where λ = ln(2) / half_life_days (so score halves every half_life_days)
 *
 * Inspired by OpenClaw's memory/temporal-decay.ts.
 */
object TemporalDecay {

    private const val MILLIS_PER_DAY = 86_400_000L

    /**
     * Default half-life: 30 days. A memory 30 days old gets 50% of its original score.
     * A memory 90 days old gets ~12.5% of its original score.
     */
    const val DEFAULT_HALF_LIFE_DAYS = 30.0

    /**
     * Recency boost factor: very new memories (< 1 day) get a small bonus.
     */
    private const val RECENCY_BOOST = 1.15f

    /**
     * Pinned memories don't decay (for important facts like name, birthday, etc.)
     */
    private const val PINNED_SCORE_MULTIPLIER = 1.5f

    /**
     * Apply temporal decay to a base score.
     *
     * @param score Base similarity/relevance score (0.0–1.0)
     * @param createdAt Unix timestamp (ms) when memory was created
     * @param updatedAt Unix timestamp (ms) when memory was last accessed/updated
     * @param halfLifeDays How many days until score halves (default 30)
     * @param pinned If true, skip decay and apply pinned bonus
     * @return Decayed score (still 0.0–1.0 but lower for old memories)
     */
    fun decay(
        score: Float,
        createdAt: Long,
        updatedAt: Long,
        halfLifeDays: Double = DEFAULT_HALF_LIFE_DAYS,
        pinned: Boolean = false
    ): Float {
        if (pinned) return (score * PINNED_SCORE_MULTIPLIER).coerceAtMost(1.0f)

        val now = System.currentTimeMillis()
        // Use updatedAt for decay (accessing a memory resets its recency)
        val effectiveAge = maxOf(now - updatedAt, 0L)
        val ageDays = effectiveAge.toDouble() / MILLIS_PER_DAY

        // λ = ln(2) / half_life
        val lambda = 0.693147 / halfLifeDays
        val decayFactor = exp(-lambda * ageDays).toFloat()

        val decayed = score * decayFactor

        // Apply recency boost for very new memories (< 1 day old)
        val boosted = if (ageDays < 1.0) decayed * RECENCY_BOOST else decayed

        return boosted.coerceIn(0.0f, 1.0f)
    }

    /**
     * Apply decay to a list of scored memories and re-rank.
     */
    fun applyToResults(
        results: List<VectorMemory.ScoredMemory>,
        halfLifeDays: Double = DEFAULT_HALF_LIFE_DAYS
    ): List<VectorMemory.ScoredMemory> {
        return results.map { scored ->
            val pinned = scored.memory.tags.contains("pinned", ignoreCase = true)
            val decayed = decay(
                scored.score,
                scored.memory.createdAt,
                scored.memory.updatedAt,
                halfLifeDays,
                pinned
            )
            scored.copy(score = decayed)
        }.sortedByDescending { it.score }
    }

    /**
     * Apply decay to hybrid search results (list of MemoryEntity + scores).
     */
    fun applyToScores(
        memories: List<Pair<ai.zeroclaw.android.data.MemoryEntity, Float>>,
        halfLifeDays: Double = DEFAULT_HALF_LIFE_DAYS
    ): List<Pair<ai.zeroclaw.android.data.MemoryEntity, Float>> {
        return memories.map { (memory, score) ->
            val pinned = memory.tags.contains("pinned", ignoreCase = true)
            val decayed = decay(score, memory.createdAt, memory.updatedAt, halfLifeDays, pinned)
            memory to decayed
        }.sortedByDescending { it.second }
    }

    /**
     * Get a human-readable recency label for a memory.
     */
    fun recencyLabel(updatedAt: Long): String {
        val now = System.currentTimeMillis()
        val ageDays = (now - updatedAt).toDouble() / MILLIS_PER_DAY
        return when {
            ageDays < 0.042 -> "just now"      // < 1 hour
            ageDays < 1.0   -> "today"
            ageDays < 2.0   -> "yesterday"
            ageDays < 7.0   -> "${ageDays.toInt()} days ago"
            ageDays < 30.0  -> "${(ageDays / 7).toInt()} weeks ago"
            ageDays < 365.0 -> "${(ageDays / 30).toInt()} months ago"
            else            -> "${(ageDays / 365).toInt()} years ago"
        }
    }

    /**
     * Compute the decay factor for display purposes (useful for debugging/logging).
     */
    fun decayFactor(updatedAt: Long, halfLifeDays: Double = DEFAULT_HALF_LIFE_DAYS): Float {
        val ageDays = (System.currentTimeMillis() - updatedAt).toDouble() / MILLIS_PER_DAY
        val lambda = 0.693147 / halfLifeDays
        return exp(-lambda * ageDays).toFloat()
    }
}

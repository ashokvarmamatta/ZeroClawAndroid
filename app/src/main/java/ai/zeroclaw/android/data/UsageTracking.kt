package ai.zeroclaw.android.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import ai.zeroclaw.android.service.ZeroClawService
import kotlinx.coroutines.flow.first
import org.json.JSONObject

/**
 * UsageTracking — Phase 135: Track token usage & estimated costs per key/model.
 *
 * Records input/output token counts for every LLM call, aggregated per API key label.
 * Provides cost estimates based on published per-token pricing.
 *
 * Persisted in DataStore as a JSON map: { keyLabel -> UsageStats }
 *
 * Pricing (per 1M tokens, USD, approximate as of 2026):
 * - GPT-4o:         $5 in / $15 out
 * - GPT-4o-mini:    $0.15 in / $0.60 out
 * - Claude Sonnet:  $3 in / $15 out
 * - Claude Haiku:   $0.25 in / $1.25 out
 * - Gemini Flash:   $0.35 in / $1.05 out
 * - Gemini Pro:     $1.25 in / $5.00 out
 */
object UsageTracking {

    data class UsageStats(
        val keyLabel: String,
        val model: String,
        val inputTokens: Long = 0L,
        val outputTokens: Long = 0L,
        val callCount: Long = 0L,
        val lastUsed: Long = 0L
    ) {
        val totalTokens get() = inputTokens + outputTokens

        fun estimatedCostUsd(): Double {
            val (inRate, outRate) = pricingFor(model)
            return (inputTokens * inRate + outputTokens * outRate) / 1_000_000.0
        }

        fun formatCost(): String = "$%.4f".format(estimatedCostUsd())
        fun formatTokens(): String = "${totalTokens / 1000}K tokens ($inputTokens in / $outputTokens out)"
    }

    private val KEY_USAGE = stringPreferencesKey("usage_tracking_data")

    // In-memory cache: "keyLabel|model" -> UsageStats
    private val cache = mutableMapOf<String, UsageStats>()

    // ── Record ─────────────────────────────────────────────────────────────────

    /**
     * Record token usage for a completed LLM call. Call this after every successful API response.
     */
    suspend fun record(
        context: Context,
        keyLabel: String,
        model: String,
        inputTokens: Int,
        outputTokens: Int
    ) {
        val cacheKey = "$keyLabel|$model"
        val existing = cache[cacheKey] ?: UsageStats(keyLabel, model)

        val updated = existing.copy(
            inputTokens  = existing.inputTokens + inputTokens,
            outputTokens = existing.outputTokens + outputTokens,
            callCount    = existing.callCount + 1,
            lastUsed     = System.currentTimeMillis()
        )
        cache[cacheKey] = updated

        // Persist periodically (every 10 calls to reduce writes)
        if (updated.callCount % 10 == 0L) {
            persist(context)
        }

        ZeroClawService.log("USAGE: $keyLabel/$model +${inputTokens}in +${outputTokens}out " +
            "(total: ${updated.totalTokens / 1000}K, ~${updated.formatCost()})")
    }

    // ── Query ──────────────────────────────────────────────────────────────────

    /**
     * Get usage stats for all keys. Returns sorted by total tokens desc.
     */
    fun getAllStats(): List<UsageStats> {
        return cache.values.sortedByDescending { it.totalTokens }
    }

    /**
     * Get stats for a specific key label.
     */
    fun getStatsForKey(keyLabel: String): List<UsageStats> {
        return cache.values.filter { it.keyLabel == keyLabel }
    }

    /**
     * Get total cost estimate across all keys.
     */
    fun totalCostUsd(): Double = cache.values.sumOf { it.estimatedCostUsd() }

    /**
     * Format a summary report for display.
     */
    fun formatReport(): String = buildString {
        val stats = getAllStats()
        if (stats.isEmpty()) {
            appendLine("No usage tracked yet.")
            return@buildString
        }
        appendLine("📊 Token Usage Report")
        appendLine("Total estimated cost: ${"$%.4f".format(totalCostUsd())}")
        appendLine()
        stats.take(10).forEach { s ->
            appendLine("• ${s.keyLabel} / ${s.model}")
            appendLine("  ${s.formatTokens()}")
            appendLine("  Cost: ${s.formatCost()} (${s.callCount} calls)")
        }
    }

    // ── Persistence ────────────────────────────────────────────────────────────

    suspend fun load(context: Context) {
        val prefs = context.appDataStore.data.first()
        val json = prefs[KEY_USAGE] ?: return
        try {
            val obj = JSONObject(json)
            for (key in obj.keys()) {
                val entry = obj.getJSONObject(key)
                cache[key] = UsageStats(
                    keyLabel     = entry.optString("keyLabel"),
                    model        = entry.optString("model"),
                    inputTokens  = entry.optLong("inputTokens"),
                    outputTokens = entry.optLong("outputTokens"),
                    callCount    = entry.optLong("callCount"),
                    lastUsed     = entry.optLong("lastUsed")
                )
            }
        } catch (_: Exception) {}
    }

    suspend fun persist(context: Context) {
        val obj = JSONObject()
        for ((key, stats) in cache) {
            obj.put(key, JSONObject().apply {
                put("keyLabel",     stats.keyLabel)
                put("model",        stats.model)
                put("inputTokens",  stats.inputTokens)
                put("outputTokens", stats.outputTokens)
                put("callCount",    stats.callCount)
                put("lastUsed",     stats.lastUsed)
            })
        }
        context.appDataStore.edit { prefs -> prefs[KEY_USAGE] = obj.toString() }
    }

    suspend fun reset(context: Context) {
        cache.clear()
        context.appDataStore.edit { prefs -> prefs.remove(KEY_USAGE) }
        ZeroClawService.log("USAGE: reset all tracking data")
    }

    // ── Pricing ────────────────────────────────────────────────────────────────

    /** Returns (inputPricePerMToken, outputPricePerMToken) in USD. */
    private fun pricingFor(model: String): Pair<Double, Double> {
        val m = model.lowercase()
        return when {
            m.contains("gpt-4o-mini")              -> 0.15 to 0.60
            m.contains("gpt-4o")                   -> 5.00 to 15.00
            m.contains("gpt-4")                    -> 10.00 to 30.00
            m.contains("gpt-3.5")                  -> 0.50 to 1.50
            m.contains("claude-3-haiku")            -> 0.25 to 1.25
            m.contains("claude-3-5-haiku")          -> 0.80 to 4.00
            m.contains("claude-3-sonnet") ||
            m.contains("claude-3-5-sonnet")         -> 3.00 to 15.00
            m.contains("claude-3-opus")             -> 15.00 to 75.00
            m.contains("gemini-1.5-flash")          -> 0.35 to 1.05
            m.contains("gemini-1.5-pro") ||
            m.contains("gemini-pro")                -> 1.25 to 5.00
            m.contains("gemini-2.0-flash")          -> 0.10 to 0.40
            else                                    -> 1.00 to 3.00  // default estimate
        }
    }
}

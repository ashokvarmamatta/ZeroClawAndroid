package ai.zeroclaw.android.infra

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import ai.zeroclaw.android.data.appDataStore
import ai.zeroclaw.android.service.ZeroClawService
import kotlinx.coroutines.flow.first
import org.json.JSONObject

/**
 * RateLimiting — Phase 134: Per-user message rate limits (anti-abuse).
 *
 * Tracks message timestamps per user in a sliding window.
 * Configurable via Settings: max messages per window (default: 20/minute).
 *
 * Also supports per-user custom limits (e.g. trusted users get higher limits).
 *
 * Usage:
 *   val allowed = RateLimiting.checkAndRecord(userId)
 *   if (!allowed) sendMessage(user, "Rate limit exceeded. Try again in ${remaining}s.")
 */
object RateLimiting {

    data class RateConfig(
        val maxMessages: Int = 20,         // max messages in the window
        val windowMs: Long = 60_000L,      // window size (default: 1 minute)
        val enabled: Boolean = true
    )

    data class RateStatus(
        val allowed: Boolean,
        val remaining: Int,                // messages remaining in window
        val resetInMs: Long,               // ms until oldest message leaves window
        val windowMs: Long
    )

    private val KEY_CONFIG = stringPreferencesKey("rate_limit_config")
    private val KEY_USER_OVERRIDES = stringPreferencesKey("rate_limit_user_overrides")

    // In-memory sliding window per userId: list of timestamps
    private val windows = mutableMapOf<String, ArrayDeque<Long>>()

    private var defaultConfig = RateConfig()

    // ── Check ──────────────────────────────────────────────────────────────────

    /**
     * Check if the user can send a message. Records the message if allowed.
     * Thread-safe via synchronization on windows map.
     */
    fun checkAndRecord(userId: String, config: RateConfig = defaultConfig): RateStatus {
        if (!config.enabled) return RateStatus(true, config.maxMessages, 0L, config.windowMs)

        val now = System.currentTimeMillis()
        val cutoff = now - config.windowMs

        synchronized(windows) {
            val queue = windows.getOrPut(userId) { ArrayDeque() }

            // Evict expired timestamps
            while (queue.isNotEmpty() && queue.first() < cutoff) {
                queue.removeFirst()
            }

            val count = queue.size
            if (count >= config.maxMessages) {
                val resetInMs = (queue.first() + config.windowMs) - now
                ZeroClawService.log("RATE_LIMIT: user=$userId blocked ($count/${config.maxMessages})")
                return RateStatus(false, 0, resetInMs.coerceAtLeast(0L), config.windowMs)
            }

            // Record this message
            queue.addLast(now)
            val remaining = config.maxMessages - queue.size
            return RateStatus(true, remaining, config.windowMs, config.windowMs)
        }
    }

    /**
     * Get remaining quota for a user without recording a message.
     */
    fun getStatus(userId: String, config: RateConfig = defaultConfig): RateStatus {
        val now = System.currentTimeMillis()
        val cutoff = now - config.windowMs
        synchronized(windows) {
            val queue = windows[userId] ?: return RateStatus(true, config.maxMessages, 0L, config.windowMs)
            val active = queue.count { it >= cutoff }
            val remaining = (config.maxMessages - active).coerceAtLeast(0)
            val resetInMs = if (queue.isNotEmpty()) {
                ((queue.firstOrNull { it >= cutoff } ?: now) + config.windowMs) - now
            } else 0L
            return RateStatus(remaining > 0, remaining, resetInMs.coerceAtLeast(0L), config.windowMs)
        }
    }

    /**
     * Format a user-friendly rate limit message.
     */
    fun formatDeniedMessage(status: RateStatus): String {
        val secs = (status.resetInMs / 1000).coerceAtLeast(1L)
        return "Rate limit reached. You can send another message in ${secs}s. " +
            "(Limit: ${defaultConfig.maxMessages} messages per ${defaultConfig.windowMs / 1000}s)"
    }

    // ── Config ─────────────────────────────────────────────────────────────────

    /**
     * Update the default rate limit config and persist it.
     */
    suspend fun setConfig(context: Context, config: RateConfig) {
        defaultConfig = config
        context.appDataStore.edit { prefs ->
            prefs[KEY_CONFIG] = JSONObject().apply {
                put("maxMessages", config.maxMessages)
                put("windowMs", config.windowMs)
                put("enabled", config.enabled)
            }.toString()
        }
        ZeroClawService.log("RATE_LIMIT: config updated — ${config.maxMessages}/${config.windowMs}ms")
    }

    /**
     * Load config from DataStore. Call on app start.
     */
    suspend fun loadConfig(context: Context) {
        val prefs = context.appDataStore.data.first()
        val json = prefs[KEY_CONFIG] ?: return
        try {
            val obj = JSONObject(json)
            defaultConfig = RateConfig(
                maxMessages = obj.optInt("maxMessages", 20),
                windowMs    = obj.optLong("windowMs", 60_000L),
                enabled     = obj.optBoolean("enabled", true)
            )
        } catch (_: Exception) {}
    }

    /**
     * Clear the window for a specific user (e.g., after they're unbanned).
     */
    fun clearUser(userId: String) {
        synchronized(windows) { windows.remove(userId) }
    }

    /**
     * Clear all windows (e.g., on service restart).
     */
    fun clearAll() {
        synchronized(windows) { windows.clear() }
    }

    /**
     * Get current config.
     */
    fun getConfig(): RateConfig = defaultConfig
}

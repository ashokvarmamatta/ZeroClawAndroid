package ai.zeroclaw.android.agents.api

import ai.zeroclaw.android.service.ZeroClawService

/**
 * Phase 166 — Per-API rate limiter.
 *
 * Tracks call timestamps per API source and enforces minimum intervals.
 * Different from RateLimiting.kt (which is per-user anti-abuse);
 * this is per-API to respect free-tier limits of external services.
 */
object ApiRateLimiter {

    // sourceId → list of call timestamps
    private val windows = mutableMapOf<String, ArrayDeque<Long>>()

    /**
     * Check if the API can be called. Returns true if allowed.
     * Does NOT record — call [record] after a successful fetch.
     */
    fun isAllowed(sourceId: String, limit: ApiRateLimit): Boolean {
        if (limit.maxRequests == Int.MAX_VALUE) return true

        val now = System.currentTimeMillis()
        val cutoff = now - limit.windowMs

        synchronized(windows) {
            val queue = windows[sourceId] ?: return true
            // Evict expired
            while (queue.isNotEmpty() && queue.first() < cutoff) {
                queue.removeFirst()
            }
            return queue.size < limit.maxRequests
        }
    }

    /**
     * Record a successful API call.
     */
    fun record(sourceId: String) {
        val now = System.currentTimeMillis()
        synchronized(windows) {
            val queue = windows.getOrPut(sourceId) { ArrayDeque() }
            queue.addLast(now)
        }
        ZeroClawService.log("API_RATE: recorded call for $sourceId")
    }

    /**
     * Get remaining calls in the current window.
     */
    fun remaining(sourceId: String, limit: ApiRateLimit): Int {
        if (limit.maxRequests == Int.MAX_VALUE) return Int.MAX_VALUE

        val now = System.currentTimeMillis()
        val cutoff = now - limit.windowMs

        synchronized(windows) {
            val queue = windows[sourceId] ?: return limit.maxRequests
            val active = queue.count { it >= cutoff }
            return (limit.maxRequests - active).coerceAtLeast(0)
        }
    }

    /**
     * Get milliseconds until the next call is allowed (0 if allowed now).
     */
    fun retryAfterMs(sourceId: String, limit: ApiRateLimit): Long {
        if (limit.maxRequests == Int.MAX_VALUE) return 0L

        val now = System.currentTimeMillis()
        val cutoff = now - limit.windowMs

        synchronized(windows) {
            val queue = windows[sourceId] ?: return 0L
            while (queue.isNotEmpty() && queue.first() < cutoff) {
                queue.removeFirst()
            }
            if (queue.size < limit.maxRequests) return 0L
            return (queue.first() + limit.windowMs) - now
        }
    }

    /** Clear all tracking (e.g., on service restart). */
    fun clearAll() {
        synchronized(windows) { windows.clear() }
    }
}

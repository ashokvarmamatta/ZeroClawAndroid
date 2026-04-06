package ai.zeroclaw.android.agents.api

/**
 * Phase 166 — Base interface for all free API data sources.
 *
 * Each data source wraps a free public API (no key or free-tier key)
 * and returns structured text that can be delivered directly to channels
 * WITHOUT needing LLM extraction.
 */
interface ApiDataSource {

    /** Unique identifier matching template apiSource field */
    val sourceId: String

    /** Human-readable name */
    val displayName: String

    /** Rate limit info for the UI */
    val rateLimit: ApiRateLimit

    /**
     * Fetch data from the API.
     * @param params — template-specific parameters (e.g., "query" → "Bitcoin", "currency" → "USD")
     * @return ApiResult with formatted text or error
     */
    suspend fun fetch(params: Map<String, String>): ApiResult
}

data class ApiResult(
    val success: Boolean,
    val content: String,
    val error: String? = null,
    val rawJson: String? = null
) {
    companion object {
        fun ok(content: String, rawJson: String? = null) = ApiResult(true, content, rawJson = rawJson)
        fun fail(error: String) = ApiResult(false, "", error)
    }
}

data class ApiRateLimit(
    val maxRequests: Int,          // max requests in the window
    val windowMs: Long,            // window size in milliseconds
    val description: String,       // human-readable, e.g. "30 requests/minute"
    val requiresKey: Boolean = false,
    val keyInstructions: String = ""
) {
    companion object {
        fun unlimited(desc: String = "Unlimited") = ApiRateLimit(Int.MAX_VALUE, 60_000L, desc)
        fun perMinute(n: Int) = ApiRateLimit(n, 60_000L, "$n requests/minute")
        fun perHour(n: Int) = ApiRateLimit(n, 3_600_000L, "$n requests/hour")
        fun perDay(n: Int) = ApiRateLimit(n, 86_400_000L, "$n requests/day")
    }
}

package ai.zeroclaw.android.agents

/**
 * AgentConfig — persisted definition of a user-created agent.
 *
 * Supported types:
 *  - "web_scraper"  — fetch URL, optionally extract via LLM
 *  - "search_only"  — skip fetch entirely, run web_search with extractPrompt as query (BUG-43)
 * Future types: rss_monitor, price_tracker, social_feed, etc.
 */
data class AgentConfig(
    val id: String,                    // UUID
    val name: String,                  // User-given label
    val type: String,                  // "web_scraper" | "search_only"
    val url: String,                   // Target URL to scrape (empty for search_only)
    val intervalMinutes: Int,          // How often to run (min 5)
    val channel: String,               // "telegram" | "discord" | "slack" | "whatsapp" | "email"
    val chatId: String,                // Destination chat/channel/email
    val extractPrompt: String,         // Optional: what to extract/summarize (blank = full content)
    val onlyOnChange: Boolean,         // Only push if content changed since last run
    val enabled: Boolean,
    val createdAt: Long,
    val lastRunAt: Long,
    val lastContentHash: Int,          // Hash of last delivered content for change detection
    val lastStatus: String,            // Human-readable last result summary
    val templateId: String? = null,    // ID of the template this agent was created from (null = custom)
    val apiSource: String? = null,     // Phase 166: free API source ID (e.g. "coingecko") — uses direct API instead of web scraping
    val fetchType: String? = null,     // Phase 165: fetch method — "http" | "rss" | "webview" (null = "http")
    val formatPreview: String? = null, // Phase 165: AI-generated format preview shown to user — persisted so user can review/edit later
    val trackingMode: String? = null,  // Phase 174: "full_site" (reload page each run) | "value_only" (track changing values) — null = "full_site"
    val extraUrls: String? = null      // Phase 175: JSON array of additional URLs to fetch from (agent cycles through all)
) {
    val safeFetchType: String get() = fetchType ?: "http"
    val safeFormatPreview: String get() = formatPreview ?: ""
    val safeTrackingMode: String get() = trackingMode ?: "full_site"
    val safeExtraUrls: List<String> get() = try {
        if (extraUrls.isNullOrBlank()) emptyList()
        else org.json.JSONArray(extraUrls).let { arr -> (0 until arr.length()).map { arr.getString(it) } }
    } catch (_: Exception) { emptyList() }
    /** All URLs this agent fetches from (primary + extras) */
    val allUrls: List<String> get() = listOf(url) + safeExtraUrls
}

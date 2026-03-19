package ai.zeroclaw.android.agents

/**
 * AgentConfig — persisted definition of a user-created agent.
 *
 * Currently supports type = "web_scraper".
 * Future types: rss_monitor, price_tracker, social_feed, etc.
 */
data class AgentConfig(
    val id: String,                    // UUID
    val name: String,                  // User-given label
    val type: String,                  // "web_scraper"
    val url: String,                   // Target URL to scrape
    val intervalMinutes: Int,          // How often to run (min 5)
    val channel: String,               // "telegram" | "discord" | "slack" | "whatsapp" | "email"
    val chatId: String,                // Destination chat/channel/email
    val extractPrompt: String,         // Optional: what to extract/summarize (blank = full content)
    val onlyOnChange: Boolean,         // Only push if content changed since last run
    val enabled: Boolean,
    val createdAt: Long,
    val lastRunAt: Long,
    val lastContentHash: Int,          // Hash of last delivered content for change detection
    val lastStatus: String             // Human-readable last result summary
)

package ai.zeroclaw.android.agents.api

import org.json.JSONArray
import org.json.JSONObject

/**
 * GitHub REST API — Trending repos via search endpoint.
 * No API key required (60 requests/hour unauthenticated).
 * Docs: https://docs.github.com/en/rest/search/search
 */
class GitHubTrendingApi : ApiDataSource {

    override val sourceId = "github_trending"
    override val displayName = "GitHub API (Free, 60/hr)"
    override val rateLimit = ApiRateLimit.perHour(60)

    override suspend fun fetch(params: Map<String, String>): ApiResult {
        return try {
            val language = params["query"]?.trim() ?: ""
            val period = params["period"] ?: "daily"

            // Use search API to find repos created/pushed recently with most stars
            val dateRange = when (period) {
                "weekly" -> "pushed:>${daysAgo(7)}"
                "monthly" -> "pushed:>${daysAgo(30)}"
                else -> "pushed:>${daysAgo(1)}" // daily
            }

            val languageFilter = if (language.isNotBlank() && language.lowercase() != "all")
                "+language:${language.replace(" ", "+")}" else ""

            val url = "https://api.github.com/search/repositories?q=$dateRange$languageFilter&sort=stars&order=desc&per_page=15"

            val (code, body) = httpGet(url, mapOf(
                "Accept" to "application/vnd.github.v3+json",
                "User-Agent" to "ZeroClaw-Android-Agent"
            ))
            if (code != 200) return ApiResult.fail("GitHub API HTTP $code")

            val json = JSONObject(body)
            val items = json.getJSONArray("items")

            val sb = StringBuilder()
            sb.appendLine("GitHub Trending Repositories")
            if (language.isNotBlank()) sb.appendLine("Language: $language")
            sb.appendLine("Period: $period")
            sb.appendLine("─".repeat(30))

            for (i in 0 until minOf(items.length(), 15)) {
                val repo = items.getJSONObject(i)
                val name = repo.optString("full_name", "")
                val desc = repo.optString("description", "No description")
                    .take(100).let { if (it.length == 100) "$it..." else it }
                val stars = repo.optInt("stargazers_count", 0)
                val forks = repo.optInt("forks_count", 0)
                val lang = repo.optString("language", "N/A")

                sb.appendLine("\n${i + 1}. $name")
                sb.appendLine("   $desc")
                sb.appendLine("   Stars: ${formatCount(stars)} | Forks: ${formatCount(forks)} | Lang: $lang")
            }

            if (items.length() == 0) {
                sb.appendLine("\nNo trending repos found for this criteria.")
            }

            ApiResult.ok(sb.toString(), body)
        } catch (e: Exception) {
            ApiResult.fail("GitHub API error: ${e.message}")
        }
    }

    private fun daysAgo(days: Int): String {
        val cal = java.util.Calendar.getInstance()
        cal.add(java.util.Calendar.DAY_OF_YEAR, -days)
        val fmt = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US)
        return fmt.format(cal.time)
    }

    private fun formatCount(n: Int): String = when {
        n >= 1000 -> "${String.format("%.1f", n / 1000.0)}k"
        else -> n.toString()
    }
}

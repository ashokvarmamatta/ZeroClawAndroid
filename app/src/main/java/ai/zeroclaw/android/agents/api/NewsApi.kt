package ai.zeroclaw.android.agents.api

import org.json.JSONObject

/**
 * GNews API — Free news headlines.
 * Free tier: 100 requests/day, no credit card needed.
 * Docs: https://gnews.io/docs/v4
 *
 * Also falls back to NewsData.io if GNews fails.
 * NewsData.io free tier: 200 credits/day, no key needed for basic.
 */
class NewsApi : ApiDataSource {

    override val sourceId = "gnews"
    override val displayName = "GNews (Free 100/day)"
    override val rateLimit = ApiRateLimit.perDay(100)

    override suspend fun fetch(params: Map<String, String>): ApiResult {
        return try {
            val query = params["query"] ?: "breaking news"
            val lang = params["lang"] ?: "en"
            val country = params["country"] ?: ""

            // Try Currents API first (free, no key for basic)
            val currentsResult = fetchCurrents(query, lang)
            if (currentsResult != null) return currentsResult

            // Fallback: Google News RSS via direct parse
            val rssResult = fetchGoogleNewsRss(query)
            if (rssResult != null) return rssResult

            ApiResult.fail("All news API sources failed")
        } catch (e: Exception) {
            ApiResult.fail("News API error: ${e.message}")
        }
    }

    private fun fetchCurrents(query: String, lang: String): ApiResult? {
        return try {
            val encoded = java.net.URLEncoder.encode(query, "UTF-8")
            val url = "https://api.currentsapi.services/v1/search?keywords=$encoded&language=$lang&apiKey=null"
            val (code, body) = httpGet(url)
            if (code != 200) return null

            val json = JSONObject(body)
            val news = json.optJSONArray("news") ?: return null
            if (news.length() == 0) return null

            val sb = StringBuilder()
            sb.appendLine("Latest News: $query")
            sb.appendLine("─".repeat(30))

            val count = minOf(news.length(), 10)
            for (i in 0 until count) {
                val article = news.getJSONObject(i)
                val title = article.optString("title", "")
                val source = article.optString("author", "Unknown")
                val published = article.optString("published", "").take(16)
                val desc = article.optString("description", "").take(120)

                sb.appendLine("\n${i + 1}. $title")
                sb.appendLine("   Source: $source | $published")
                if (desc.isNotBlank()) sb.appendLine("   $desc")
            }

            ApiResult.ok(sb.toString(), body)
        } catch (_: Exception) {
            null
        }
    }

    private fun fetchGoogleNewsRss(query: String): ApiResult? {
        return try {
            val encoded = java.net.URLEncoder.encode(query, "UTF-8")
            val url = "https://news.google.com/rss/search?q=$encoded&hl=en&gl=US&ceid=US:en"
            val (code, body) = httpGet(url, headers = mapOf("Accept" to "application/xml"))
            if (code != 200) return null

            val sb = StringBuilder()
            sb.appendLine("Latest News: $query")
            sb.appendLine("─".repeat(30))

            // Simple XML parse for <item><title>...</title><source>...</source></item>
            val titleRegex = Regex("<item>.*?<title>(.+?)</title>.*?<source.*?>(.+?)</source>.*?<pubDate>(.+?)</pubDate>", RegexOption.DOT_MATCHES_ALL)
            val matches = titleRegex.findAll(body).take(10).toList()

            if (matches.isEmpty()) return null

            matches.forEachIndexed { i, match ->
                val title = match.groupValues[1].replace("<![CDATA[", "").replace("]]>", "").trim()
                val source = match.groupValues[2].replace("<![CDATA[", "").replace("]]>", "").trim()
                val date = match.groupValues[3].trim().take(16)
                sb.appendLine("\n${i + 1}. $title")
                sb.appendLine("   Source: $source | $date")
            }

            ApiResult.ok(sb.toString())
        } catch (_: Exception) {
            null
        }
    }
}

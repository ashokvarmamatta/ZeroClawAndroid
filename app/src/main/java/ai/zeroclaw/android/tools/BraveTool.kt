package ai.zeroclaw.android.tools

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * BraveTool — web search via Brave Search API.
 *
 * Inspired by OpenClaw's Brave Search skill.
 * Alternative to DuckDuckGo with richer results and snippet quality.
 * Requires a Brave Search API key (free tier: 2000 queries/month).
 */
class BraveTool : Tool {

    override val name = "brave_search"

    override val description = "Search the web using Brave Search API. " +
            "Returns rich results with titles, URLs, descriptions, and optional news/video results. " +
            "Actions: 'web' (general search), 'news' (news search). " +
            "Requires a Brave Search API key."

    override val parameters = listOf(
        ToolParam("query", "string", "Search query"),
        ToolParam("action", "string", "One of: web, news. Default: web.", required = false),
        ToolParam("api_key", "string", "Brave Search API key", required = false),
        ToolParam("count", "string", "Number of results (1-10). Default: 5.", required = false),
        ToolParam("country", "string", "Country code for results (e.g. 'US', 'GB', 'IN'). Default: all.", required = false)
    )

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()

    override suspend fun execute(args: Map<String, String>): ToolResult {
        val query = args["query"]?.trim()
            ?: return ToolResult(false, "", "Missing 'query' parameter")

        if (query.isBlank()) return ToolResult(false, "", "Search query cannot be empty")

        val apiKey = args["api_key"]?.trim()
            ?: return ToolResult(false, "", "Missing 'api_key'. Provide a Brave Search API key. " +
                    "Get one free at https://api.search.brave.com/")

        val action = args["action"]?.trim()?.lowercase() ?: "web"
        val count = args["count"]?.trim()?.toIntOrNull()?.coerceIn(1, 10) ?: 5
        val country = args["country"]?.trim()?.uppercase()

        return withContext(Dispatchers.IO) {
            try {
                when (action) {
                    "web" -> webSearch(apiKey, query, count, country)
                    "news" -> newsSearch(apiKey, query, count, country)
                    else -> ToolResult(false, "", "Unknown action: $action. Use: web, news.")
                }
            } catch (e: Exception) {
                ToolResult(false, "", "Brave Search error: ${e.message}")
            }
        }
    }

    private fun webSearch(apiKey: String, query: String, count: Int, country: String?): ToolResult {
        val encoded = java.net.URLEncoder.encode(query, "UTF-8")
        var url = "https://api.search.brave.com/res/v1/web/search?q=$encoded&count=$count"
        if (country != null) url += "&country=$country"

        val request = Request.Builder()
            .url(url)
            .header("Accept", "application/json")
            .header("Accept-Encoding", "gzip")
            .header("X-Subscription-Token", apiKey)
            .get()
            .build()

        val response = client.newCall(request).execute()
        val body = response.body?.string() ?: return ToolResult(false, "", "Empty response from Brave")

        if (response.code == 401 || response.code == 403) {
            return ToolResult(false, "", "Invalid Brave Search API key.")
        }
        if (response.code == 429) {
            return ToolResult(false, "", "Brave Search rate limit exceeded. Free tier: 2000 queries/month.")
        }
        if (response.code != 200) {
            return ToolResult(false, "", "Brave Search error: HTTP ${response.code}")
        }

        val json = JSONObject(body)
        val webResults = json.optJSONObject("web")?.optJSONArray("results")

        if (webResults == null || webResults.length() == 0) {
            return ToolResult(true, "No results found for \"$query\".")
        }

        val sb = StringBuilder("Brave Search: \"$query\"\n${"═".repeat(40)}\n\n")

        for (i in 0 until minOf(webResults.length(), count)) {
            val result = webResults.getJSONObject(i)
            val title = result.optString("title", "?")
            val url = result.optString("url", "")
            val desc = result.optString("description", "")
            val age = result.optString("age", "")

            sb.appendLine("${i + 1}. $title")
            if (desc.isNotBlank()) sb.appendLine("   $desc")
            sb.appendLine("   🔗 $url")
            if (age.isNotBlank()) sb.appendLine("   📅 $age")
            sb.appendLine()
        }

        // Include info box if available
        val infobox = json.optJSONObject("infobox")
        if (infobox != null) {
            val ibTitle = infobox.optString("title", "")
            val ibDesc = infobox.optString("description", "")
            if (ibTitle.isNotBlank()) {
                sb.appendLine("── Info Box ──")
                sb.appendLine(ibTitle)
                if (ibDesc.isNotBlank()) sb.appendLine(ibDesc.take(300))
                sb.appendLine()
            }
        }

        return ToolResult(true, sb.toString().take(4000))
    }

    private fun newsSearch(apiKey: String, query: String, count: Int, country: String?): ToolResult {
        val encoded = java.net.URLEncoder.encode(query, "UTF-8")
        var url = "https://api.search.brave.com/res/v1/news/search?q=$encoded&count=$count"
        if (country != null) url += "&country=$country"

        val request = Request.Builder()
            .url(url)
            .header("Accept", "application/json")
            .header("Accept-Encoding", "gzip")
            .header("X-Subscription-Token", apiKey)
            .get()
            .build()

        val response = client.newCall(request).execute()
        val body = response.body?.string() ?: return ToolResult(false, "", "Empty response from Brave")

        if (response.code == 401 || response.code == 403) {
            return ToolResult(false, "", "Invalid Brave Search API key.")
        }
        if (response.code != 200) {
            return ToolResult(false, "", "Brave News error: HTTP ${response.code}")
        }

        val json = JSONObject(body)
        val newsResults = json.optJSONArray("results")

        if (newsResults == null || newsResults.length() == 0) {
            return ToolResult(true, "No news found for \"$query\".")
        }

        val sb = StringBuilder("Brave News: \"$query\"\n${"═".repeat(40)}\n\n")

        for (i in 0 until minOf(newsResults.length(), count)) {
            val result = newsResults.getJSONObject(i)
            val title = result.optString("title", "?")
            val url = result.optString("url", "")
            val desc = result.optString("description", "")
            val source = result.optString("source", "")
            val age = result.optString("age", "")

            sb.appendLine("${i + 1}. $title")
            if (source.isNotBlank()) sb.append("   📰 $source")
            if (age.isNotBlank()) sb.appendLine(" • $age") else sb.appendLine()
            if (desc.isNotBlank()) sb.appendLine("   ${desc.take(200)}")
            sb.appendLine("   🔗 $url")
            sb.appendLine()
        }

        return ToolResult(true, sb.toString().take(4000))
    }
}

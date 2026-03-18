package ai.zeroclaw.android.tools

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/**
 * WebSearchTool — searches the web via DuckDuckGo HTML (no API key needed).
 * Returns top results with title, snippet, and URL.
 */
class WebSearchTool : Tool {

    override val name = "web_search"

    override val description = "Search the web for real-time information. Use this when the user asks about current events, recent news, facts you're unsure about, or anything that requires up-to-date information."

    override val parameters = listOf(
        ToolParam("query", "string", "The search query to look up on the web")
    )

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    override suspend fun execute(args: Map<String, String>): ToolResult {
        val query = args["query"]?.trim()
            ?: return ToolResult(false, "", "Missing 'query' parameter")

        if (query.isBlank()) {
            return ToolResult(false, "", "Empty search query")
        }

        return withContext(Dispatchers.IO) {
            try {
                val encoded = URLEncoder.encode(query, "UTF-8")
                val url = "https://html.duckduckgo.com/html/?q=$encoded"
                android.util.Log.d("ZeroClaw.DDG", "Searching: $url")

                val request = Request.Builder()
                    .url(url)
                    .header("User-Agent", "Mozilla/5.0 (Linux; Android 10; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36")
                    .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                    .header("Accept-Language", "en-US,en;q=0.5")
                    .header("Accept-Encoding", "identity")
                    .header("DNT", "1")
                    .get()
                    .build()

                val response = client.newCall(request).execute()
                android.util.Log.d("ZeroClaw.DDG", "HTTP status: ${response.code}")

                if (response.code != 200) {
                    android.util.Log.w("ZeroClaw.DDG", "Non-200 response: ${response.code}")
                    return@withContext ToolResult(false, "", "DuckDuckGo returned HTTP ${response.code}")
                }

                val html = response.body?.string()
                if (html.isNullOrBlank()) {
                    android.util.Log.w("ZeroClaw.DDG", "Empty body")
                    return@withContext ToolResult(false, "", "Empty response from DuckDuckGo")
                }

                android.util.Log.d("ZeroClaw.DDG", "Response body: ${html.length} chars, preview: ${html.take(200)}")

                val results = parseDDGResults(html)
                android.util.Log.d("ZeroClaw.DDG", "Parsed ${results.size} results")

                if (results.isEmpty()) {
                    android.util.Log.w("ZeroClaw.DDG", "No results parsed — HTML snippet: ${html.take(500)}")
                    return@withContext ToolResult(false, "", "No results found for: $query")
                }

                val sb = StringBuilder("Web search results for: \"$query\"\n\n")
                for ((i, result) in results.withIndex()) {
                    sb.appendLine("${i + 1}. ${result.title}")
                    sb.appendLine("   ${result.snippet}")
                    sb.appendLine("   URL: ${result.url}")
                    sb.appendLine()
                }
                ToolResult(true, sb.toString().take(MAX_RESULT_LENGTH))
            } catch (e: Exception) {
                android.util.Log.e("ZeroClaw.DDG", "Exception: ${e.javaClass.simpleName}: ${e.message}", e)
                ToolResult(false, "", "Web search failed: ${e.message}")
            }
        }
    }

    private data class SearchResult(val title: String, val snippet: String, val url: String)

    /**
     * Parse DuckDuckGo HTML results page.
     * Extracts result blocks with class="result" or "result__a" / "result__snippet".
     */
    private fun parseDDGResults(html: String): List<SearchResult> {
        val results = mutableListOf<SearchResult>()

        // Match result links: <a class="result__a" href="...">Title</a>
        val linkPattern = Regex("""<a[^>]*class="result__a"[^>]*href="([^"]*)"[^>]*>(.*?)</a>""", RegexOption.DOT_MATCHES_ALL)
        // Match result snippets: <a class="result__snippet" ...>Snippet</a>
        val snippetPattern = Regex("""<a[^>]*class="result__snippet"[^>]*>(.*?)</a>""", RegexOption.DOT_MATCHES_ALL)

        val links = linkPattern.findAll(html).toList()
        val snippets = snippetPattern.findAll(html).toList()

        for (i in links.indices.take(MAX_RESULTS)) {
            val rawUrl = links[i].groupValues[1]
            val title = stripHtml(links[i].groupValues[2])
            val snippet = if (i < snippets.size) stripHtml(snippets[i].groupValues[1]) else ""

            // DDG wraps URLs in a redirect — extract actual URL
            val actualUrl = extractDDGUrl(rawUrl)

            if (title.isNotBlank()) {
                results.add(SearchResult(title, snippet, actualUrl))
            }
        }
        return results
    }

    /**
     * DDG HTML results wrap URLs like: //duckduckgo.com/l/?uddg=https%3A%2F%2Fexample.com&...
     * Extract the actual destination URL.
     */
    private fun extractDDGUrl(raw: String): String {
        val uddgMatch = Regex("[?&]uddg=([^&]+)").find(raw)
        return if (uddgMatch != null) {
            java.net.URLDecoder.decode(uddgMatch.groupValues[1], "UTF-8")
        } else {
            raw.removePrefix("//")
                .let { if (!it.startsWith("http")) "https://$it" else it }
        }
    }

    private fun stripHtml(s: String): String {
        return s.replace(Regex("<[^>]*>"), "")
            .replace("&amp;", "&")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace("&#x27;", "'")
            .replace("&nbsp;", " ")
            .trim()
    }

    companion object {
        private const val MAX_RESULTS = 5
        private const val MAX_RESULT_LENGTH = 4000
    }
}

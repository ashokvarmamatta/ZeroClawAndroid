package ai.zeroclaw.android.tools

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * WebFetchTool — fetches a URL and extracts readable text content.
 * Strips HTML tags, scripts, styles, and returns clean text.
 */
class WebFetchTool : Tool {

    override val name = "web_fetch"

    override val description = "Fetch the content of a web page and extract its readable text. Use this when the user shares a URL and wants you to read, summarize, or answer questions about its content."

    override val parameters = listOf(
        ToolParam("url", "string", "The URL of the web page to fetch and read")
    )

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    override suspend fun execute(args: Map<String, String>): ToolResult {
        var url = args["url"]?.trim()
            ?: return ToolResult(false, "", "Missing 'url' parameter")

        if (url.isBlank()) {
            return ToolResult(false, "", "Empty URL")
        }

        // Add https:// if missing
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            url = "https://$url"
        }

        return withContext(Dispatchers.IO) {
            try {
                val request = Request.Builder()
                    .url(url)
                    .header("User-Agent", "Mozilla/5.0 (Android; ZeroClaw Bot)")
                    .header("Accept", "text/html,application/xhtml+xml,text/plain")
                    .get()
                    .build()

                val response = client.newCall(request).execute()
                val body = response.body?.string()
                    ?: return@withContext ToolResult(false, "", "Empty response from $url")

                val contentType = response.header("Content-Type", "") ?: ""

                val text = if (contentType.contains("text/html") || body.trimStart().startsWith("<")) {
                    extractReadableText(body)
                } else {
                    // Plain text or other format — use as-is
                    body
                }

                if (text.isBlank()) {
                    return@withContext ToolResult(true, "Page fetched but no readable text content found at: $url")
                }

                val truncated = text.take(MAX_CONTENT_LENGTH)
                val header = "Content from: $url\n\n"
                ToolResult(true, header + truncated)
            } catch (e: Exception) {
                ToolResult(false, "", "Failed to fetch $url: ${e.message}")
            }
        }
    }

    /**
     * Extract readable text from HTML — removes scripts, styles, nav, footer,
     * then strips all remaining tags and cleans whitespace.
     */
    private fun extractReadableText(html: String): String {
        var text = html

        // Remove scripts, styles, and non-content elements
        text = text.replace(Regex("<script[^>]*>[\\s\\S]*?</script>", RegexOption.IGNORE_CASE), " ")
        text = text.replace(Regex("<style[^>]*>[\\s\\S]*?</style>", RegexOption.IGNORE_CASE), " ")
        text = text.replace(Regex("<nav[^>]*>[\\s\\S]*?</nav>", RegexOption.IGNORE_CASE), " ")
        text = text.replace(Regex("<footer[^>]*>[\\s\\S]*?</footer>", RegexOption.IGNORE_CASE), " ")
        text = text.replace(Regex("<header[^>]*>[\\s\\S]*?</header>", RegexOption.IGNORE_CASE), " ")
        text = text.replace(Regex("<!--[\\s\\S]*?-->"), " ")

        // Convert some tags to newlines for readability
        text = text.replace(Regex("<br[^>]*/?>", RegexOption.IGNORE_CASE), "\n")
        text = text.replace(Regex("</p>", RegexOption.IGNORE_CASE), "\n\n")
        text = text.replace(Regex("</div>", RegexOption.IGNORE_CASE), "\n")
        text = text.replace(Regex("</h[1-6]>", RegexOption.IGNORE_CASE), "\n\n")
        text = text.replace(Regex("</li>", RegexOption.IGNORE_CASE), "\n")
        text = text.replace(Regex("</tr>", RegexOption.IGNORE_CASE), "\n")

        // Strip remaining tags
        text = text.replace(Regex("<[^>]+>"), " ")

        // Decode HTML entities
        text = text.replace("&amp;", "&")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace("&#x27;", "'")
            .replace("&apos;", "'")
            .replace("&#39;", "'")
            .replace("&nbsp;", " ")
            .replace("&#x2F;", "/")
            .replace("&mdash;", "—")
            .replace("&ndash;", "–")
            .replace("&hellip;", "…")
            .replace(Regex("&#(\\d+);")) { chr ->
                val code = chr.groupValues[1].toIntOrNull()
                if (code != null && code in 32..126) code.toChar().toString() else " "
            }

        // Clean up whitespace
        text = text.replace(Regex("[ \\t]+"), " ")
        text = text.replace(Regex("\\n[ \\t]+"), "\n")
        text = text.replace(Regex("\\n{3,}"), "\n\n")
        text = text.trim()

        return text
    }

    companion object {
        private const val MAX_CONTENT_LENGTH = 6000
    }
}

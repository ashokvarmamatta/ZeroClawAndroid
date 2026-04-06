package ai.zeroclaw.android.tools

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
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

    // Longer timeouts for slow/international sites; retry on connect timeout
    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(45, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    override suspend fun execute(args: Map<String, String>): ToolResult {
        var url = args["url"]?.trim()
            ?: return ToolResult(false, "", "Missing 'url' parameter")

        if (url.isBlank()) return ToolResult(false, "", "Empty URL")

        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            url = "https://$url"
        }

        return withContext(Dispatchers.IO) {
            fetchWithRetry(url, retries = 2)
        }
    }

    private suspend fun fetchWithRetry(url: String, retries: Int): ToolResult {
        var lastError = ""
        repeat(retries) { attempt ->
            try {
                val request = Request.Builder()
                    .url(url)
                    // Full Chrome-like browser headers — avoids bot detection on most sites.
                    // NOTE: Do NOT set Accept-Encoding manually — OkHttp automatically adds
                    // "Accept-Encoding: gzip" and transparently decompresses the response.
                    // Setting it manually disables that auto-decompression → raw binary garbage.
                    .header("User-Agent", "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36")
                    .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/webp,*/*;q=0.8")
                    .header("Accept-Language", "en-US,en;q=0.9")
                    .header("Cache-Control", "no-cache")
                    .header("Pragma", "no-cache")
                    .header("Sec-Fetch-Dest", "document")
                    .header("Sec-Fetch-Mode", "navigate")
                    .header("Sec-Fetch-Site", "none")
                    .header("Upgrade-Insecure-Requests", "1")
                    .get()
                    .build()

                client.newCall(request).execute().use { response ->
                    val code = response.code

                    // Give a clear error for common non-success codes
                    if (code == 403) return ToolResult(false, "", "Access denied (HTTP 403) — $url blocks automated access")
                    if (code == 429) return ToolResult(false, "", "Rate limited (HTTP 429) — try again later")
                    if (code == 404) return ToolResult(false, "", "Page not found (HTTP 404) — $url")
                    if (code !in 200..299) return ToolResult(false, "", "HTTP $code from $url")

                    val body = response.body?.string()
                        ?: return ToolResult(false, "", "Empty response from $url")

                    val contentType = response.header("Content-Type", "") ?: ""
                    val text = if (contentType.contains("text/html") || body.trimStart().startsWith("<")) {
                        extractReadableText(body)
                    } else {
                        body
                    }

                    if (text.isBlank()) {
                        return ToolResult(true, "Page fetched but no readable text found at: $url")
                    }

                    val header = "Content from: $url\n\n"
                    return ToolResult(true, header + text.take(MAX_CONTENT_LENGTH))
                }

            } catch (e: java.net.SocketTimeoutException) {
                lastError = "Timeout on attempt ${attempt + 1}/${retries}"
                if (attempt < retries - 1) delay(2000)
            } catch (e: java.net.UnknownHostException) {
                return ToolResult(false, "", "Cannot reach host — check internet connection or URL: $url")
            } catch (e: javax.net.ssl.SSLException) {
                return ToolResult(false, "", "SSL/TLS error for $url: ${e.message}")
            } catch (e: Exception) {
                lastError = e.message ?: "Unknown error"
                if (attempt < retries - 1) delay(1000)
            }
        }
        return ToolResult(false, "", "Failed to fetch $url after $retries attempts: $lastError")
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

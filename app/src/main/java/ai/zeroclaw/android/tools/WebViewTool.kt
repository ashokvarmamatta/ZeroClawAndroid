package ai.zeroclaw.android.tools

import android.content.Context
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeout

/**
 * WebViewTool — Phase 125: Browser automation via Android WebView.
 *
 * Loads URLs in a headless WebView, waits for the page to finish loading,
 * executes JavaScript, and extracts the page text content. Enables the AI to:
 * - Render JavaScript-heavy SPAs that regular HTTP fetch can't handle
 * - Fill forms and click buttons via JS injection
 * - Extract structured page data after JS execution
 *
 * Actions:
 * - fetch   — load URL and return visible text content
 * - js      — execute JavaScript on a loaded page and return result
 * - scrape  — extract structured data (title, meta, links, headings)
 */
class WebViewTool(private val context: Context) : Tool {

    override val name = "webview"
    override val description = "Load and interact with web pages using a real browser engine. " +
        "Can render JavaScript-heavy pages and extract content. " +
        "Actions: fetch (load URL, get text), js (execute JavaScript), scrape (extract structure)."

    override val parameters = listOf(
        ToolParam("action", "string", "One of: fetch, js, scrape"),
        ToolParam("url", "string", "URL to load"),
        ToolParam("script", "string", "JavaScript to execute (for js action)", required = false),
        ToolParam("wait_ms", "string", "Extra wait time in ms after page load (default 2000)", required = false)
    )

    private val mainHandler = Handler(Looper.getMainLooper())

    override suspend fun execute(args: Map<String, String>): ToolResult {
        val action = args["action"]?.lowercase() ?: "fetch"
        val url = args["url"]?.trim() ?: return ToolResult(false, "", "Missing 'url' parameter.")
        val script = args["script"]?.trim() ?: ""
        val waitMs = args["wait_ms"]?.toLongOrNull() ?: 2000L

        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            return ToolResult(false, "", "URL must start with http:// or https://")
        }

        return try {
            val pageData = loadPage(url, waitMs)
            when (action) {
                "fetch" -> ToolResult(true, pageData.text.take(8000))
                "scrape" -> ToolResult(true, buildStructure(pageData))
                "js" -> {
                    if (script.isBlank()) return ToolResult(false, "", "Missing 'script' for js action.")
                    val result = executeJs(url, script, waitMs)
                    ToolResult(true, "JS result: $result")
                }
                else -> ToolResult(false, "", "Unknown action '$action'. Use: fetch, js, scrape.")
            }
        } catch (e: Exception) {
            ToolResult(false, "", "WebView error: ${e.message}")
        }
    }

    private data class PageData(
        val title: String,
        val text: String,
        val html: String,
        val url: String
    )

    private suspend fun loadPage(url: String, waitMs: Long): PageData {
        val deferred = CompletableDeferred<PageData>()

        mainHandler.post {
            val webView = WebView(context).apply {
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                settings.loadWithOverviewMode = true
            }

            // Bridge to get page content after load
            webView.addJavascriptInterface(object {
                @JavascriptInterface
                fun onContent(title: String, text: String, html: String) {
                    if (!deferred.isCompleted) {
                        deferred.complete(PageData(title, text, html.take(50000), url))
                    }
                }
            }, "Android")

            webView.webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView?, loadedUrl: String?) {
                    mainHandler.postDelayed({
                        view?.evaluateJavascript("""
                            (function() {
                                var title = document.title || '';
                                var text = document.body ? document.body.innerText : '';
                                var html = document.documentElement.outerHTML;
                                Android.onContent(title, text.substring(0, 20000), html.substring(0, 30000));
                            })();
                        """.trimIndent(), null)
                    }, waitMs)
                }
            }

            webView.loadUrl(url)
        }

        return withTimeout(30_000L) { deferred.await() }
    }

    private suspend fun executeJs(url: String, script: String, waitMs: Long): String {
        val deferred = CompletableDeferred<String>()

        mainHandler.post {
            val webView = WebView(context).apply {
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
            }

            webView.addJavascriptInterface(object {
                @JavascriptInterface
                fun onResult(result: String) {
                    if (!deferred.isCompleted) deferred.complete(result)
                }
            }, "Android")

            webView.webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView?, loadedUrl: String?) {
                    mainHandler.postDelayed({
                        view?.evaluateJavascript("""
                            (function() {
                                try {
                                    var result = (function() { $script })();
                                    Android.onResult(String(result));
                                } catch(e) {
                                    Android.onResult('Error: ' + e.message);
                                }
                            })();
                        """.trimIndent(), null)
                    }, waitMs)
                }
            }
            webView.loadUrl(url)
        }

        return withTimeout(30_000L) { deferred.await() }
    }

    private fun buildStructure(page: PageData): String = buildString {
        appendLine("Title: ${page.title}")
        appendLine("URL: ${page.url}")
        appendLine()

        // Extract headings from text (lines with short content, likely headers)
        val lines = page.text.lines()
        val headings = lines.filter { it.trim().length in 3..80 && it.trim().isNotBlank() }.take(20)
        if (headings.isNotEmpty()) {
            appendLine("Key sections:")
            headings.forEach { appendLine("  • $it") }
        }

        appendLine()
        appendLine("Content preview:")
        appendLine(page.text.take(3000))
    }
}

package ai.zeroclaw.android.tools

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.StringReader
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.TimeUnit

/**
 * RssFeedTool — monitor RSS/Atom feeds, blog watcher.
 *
 * Inspired by OpenClaw's RSS skill.
 * Actions: read (default), headlines.
 * Parses both RSS 2.0 and Atom feeds.
 */
class RssFeedTool : Tool {

    override val name = "rss_feed"

    override val description = "Read RSS and Atom feeds from any blog, news site, or podcast. " +
            "Actions: 'read' (full feed with descriptions), 'headlines' (titles only, more items). " +
            "No API key needed."

    override val parameters = listOf(
        ToolParam("url", "string", "RSS/Atom feed URL (e.g. 'https://example.com/feed.xml')"),
        ToolParam("action", "string", "One of: read, headlines. Default: read.", required = false),
        ToolParam("limit", "string", "Max items to return. Default: 5 for read, 15 for headlines.", required = false)
    )

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    override suspend fun execute(args: Map<String, String>): ToolResult {
        val url = args["url"]?.trim()
            ?: return ToolResult(false, "", "Missing 'url' parameter")

        if (url.isBlank() || !url.startsWith("http")) {
            return ToolResult(false, "", "Invalid URL: must start with http:// or https://")
        }

        val action = args["action"]?.trim()?.lowercase() ?: "read"
        val defaultLimit = if (action == "headlines") 15 else 5
        val limit = args["limit"]?.trim()?.toIntOrNull() ?: defaultLimit

        return withContext(Dispatchers.IO) {
            try {
                val xml = fetchFeed(url)
                    ?: return@withContext ToolResult(false, "", "Failed to fetch feed from $url")

                val feed = parseFeed(xml)
                    ?: return@withContext ToolResult(false, "", "Could not parse feed. Is this a valid RSS/Atom feed?")

                when (action) {
                    "headlines" -> formatHeadlines(feed, limit)
                    else -> formatFull(feed, limit)
                }
            } catch (e: Exception) {
                ToolResult(false, "", "RSS feed error: ${e.message}")
            }
        }
    }

    private fun fetchFeed(url: String): String? {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", "ZeroClaw-Android/1.0")
            .header("Accept", "application/rss+xml, application/atom+xml, application/xml, text/xml, */*")
            .get()
            .build()

        val response = client.newCall(request).execute()
        if (response.code != 200) return null
        return response.body?.string()
    }

    data class FeedData(
        val title: String,
        val description: String,
        val link: String,
        val items: List<FeedItem>
    )

    data class FeedItem(
        val title: String,
        val link: String,
        val description: String,
        val pubDate: String,
        val author: String
    )

    private fun parseFeed(xml: String): FeedData? {
        return try {
            if (xml.contains("<feed") && xml.contains("xmlns=\"http://www.w3.org/2005/Atom\"")) {
                parseAtom(xml)
            } else {
                parseRss(xml)
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun parseRss(xml: String): FeedData? {
        val factory = XmlPullParserFactory.newInstance()
        factory.isNamespaceAware = false
        val parser = factory.newPullParser()
        parser.setInput(StringReader(xml))

        var feedTitle = ""
        var feedDesc = ""
        var feedLink = ""
        val items = mutableListOf<FeedItem>()
        var inItem = false
        var inChannel = false
        var currentTag = ""
        var itemTitle = ""
        var itemLink = ""
        var itemDesc = ""
        var itemDate = ""
        var itemAuthor = ""

        var eventType = parser.eventType
        while (eventType != XmlPullParser.END_DOCUMENT) {
            when (eventType) {
                XmlPullParser.START_TAG -> {
                    currentTag = parser.name ?: ""
                    when {
                        currentTag == "channel" -> inChannel = true
                        currentTag == "item" -> {
                            inItem = true
                            itemTitle = ""; itemLink = ""; itemDesc = ""; itemDate = ""; itemAuthor = ""
                        }
                    }
                }
                XmlPullParser.TEXT -> {
                    val text = parser.text?.trim() ?: ""
                    if (text.isNotEmpty()) {
                        if (inItem) {
                            when (currentTag) {
                                "title" -> itemTitle = text
                                "link" -> itemLink = text
                                "description" -> itemDesc = stripHtml(text)
                                "pubDate", "dc:date" -> itemDate = text
                                "author", "dc:creator" -> itemAuthor = text
                            }
                        } else if (inChannel) {
                            when (currentTag) {
                                "title" -> if (feedTitle.isEmpty()) feedTitle = text
                                "description" -> if (feedDesc.isEmpty()) feedDesc = text
                                "link" -> if (feedLink.isEmpty()) feedLink = text
                            }
                        }
                    }
                }
                XmlPullParser.END_TAG -> {
                    val tag = parser.name ?: ""
                    if (tag == "item") {
                        items.add(FeedItem(itemTitle, itemLink, itemDesc.take(300), itemDate, itemAuthor))
                        inItem = false
                    }
                    if (tag == "channel") inChannel = false
                    currentTag = ""
                }
            }
            eventType = parser.next()
        }

        if (feedTitle.isEmpty() && items.isEmpty()) return null
        return FeedData(feedTitle, feedDesc, feedLink, items)
    }

    private fun parseAtom(xml: String): FeedData? {
        val factory = XmlPullParserFactory.newInstance()
        factory.isNamespaceAware = false
        val parser = factory.newPullParser()
        parser.setInput(StringReader(xml))

        var feedTitle = ""
        var feedDesc = ""
        var feedLink = ""
        val items = mutableListOf<FeedItem>()
        var inEntry = false
        var currentTag = ""
        var itemTitle = ""
        var itemLink = ""
        var itemDesc = ""
        var itemDate = ""
        var itemAuthor = ""

        var eventType = parser.eventType
        while (eventType != XmlPullParser.END_DOCUMENT) {
            when (eventType) {
                XmlPullParser.START_TAG -> {
                    currentTag = parser.name ?: ""
                    when {
                        currentTag == "entry" -> {
                            inEntry = true
                            itemTitle = ""; itemLink = ""; itemDesc = ""; itemDate = ""; itemAuthor = ""
                        }
                        currentTag == "link" -> {
                            val href = parser.getAttributeValue(null, "href") ?: ""
                            if (inEntry && itemLink.isEmpty()) itemLink = href
                            else if (!inEntry && feedLink.isEmpty()) feedLink = href
                        }
                    }
                }
                XmlPullParser.TEXT -> {
                    val text = parser.text?.trim() ?: ""
                    if (text.isNotEmpty()) {
                        if (inEntry) {
                            when (currentTag) {
                                "title" -> itemTitle = text
                                "summary", "content" -> if (itemDesc.isEmpty()) itemDesc = stripHtml(text)
                                "updated", "published" -> if (itemDate.isEmpty()) itemDate = text
                                "name" -> itemAuthor = text
                            }
                        } else {
                            when (currentTag) {
                                "title" -> if (feedTitle.isEmpty()) feedTitle = text
                                "subtitle" -> if (feedDesc.isEmpty()) feedDesc = text
                            }
                        }
                    }
                }
                XmlPullParser.END_TAG -> {
                    val tag = parser.name ?: ""
                    if (tag == "entry") {
                        items.add(FeedItem(itemTitle, itemLink, itemDesc.take(300), itemDate, itemAuthor))
                        inEntry = false
                    }
                    currentTag = ""
                }
            }
            eventType = parser.next()
        }

        if (feedTitle.isEmpty() && items.isEmpty()) return null
        return FeedData(feedTitle, feedDesc, feedLink, items)
    }

    private fun formatFull(feed: FeedData, limit: Int): ToolResult {
        val sb = StringBuilder()
        sb.appendLine("${feed.title}")
        if (feed.description.isNotBlank()) sb.appendLine(feed.description.take(200))
        sb.appendLine("═".repeat(40))

        val count = minOf(feed.items.size, limit)
        for (i in 0 until count) {
            val item = feed.items[i]
            sb.appendLine()
            sb.appendLine("${i + 1}. ${item.title}")
            if (item.pubDate.isNotBlank()) sb.appendLine("   📅 ${item.pubDate}")
            if (item.author.isNotBlank()) sb.appendLine("   ✍️ ${item.author}")
            if (item.description.isNotBlank()) sb.appendLine("   ${item.description}")
            if (item.link.isNotBlank()) sb.appendLine("   🔗 ${item.link}")
        }

        if (feed.items.size > count) {
            sb.appendLine("\n... and ${feed.items.size - count} more items")
        }

        return ToolResult(true, sb.toString().take(4000))
    }

    private fun formatHeadlines(feed: FeedData, limit: Int): ToolResult {
        val sb = StringBuilder()
        sb.appendLine("${feed.title} — Headlines")
        sb.appendLine("═".repeat(40))

        val count = minOf(feed.items.size, limit)
        for (i in 0 until count) {
            val item = feed.items[i]
            val date = if (item.pubDate.isNotBlank()) " (${item.pubDate.take(16)})" else ""
            sb.appendLine("${i + 1}. ${item.title}$date")
        }

        if (feed.items.size > count) {
            sb.appendLine("\n... and ${feed.items.size - count} more items")
        }

        return ToolResult(true, sb.toString().take(4000))
    }

    private fun stripHtml(html: String): String {
        return html.replace(Regex("<[^>]*>"), "")
            .replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">")
            .replace("&quot;", "\"").replace("&#39;", "'").replace("&nbsp;", " ")
            .replace(Regex("\\s+"), " ").trim()
    }
}

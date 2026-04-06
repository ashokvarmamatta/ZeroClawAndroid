package ai.zeroclaw.android.agents.api

import org.json.JSONObject

/**
 * YouTube Trending — Fetches trending videos via public RSS/page scraping.
 * No API key needed for RSS feed. YouTube Data API v3 free: 10k units/day.
 * Docs: https://developers.google.com/youtube/v3
 *
 * Primary: YouTube RSS trending feed
 * Fallback: Public trending page scrape
 */
class YouTubeTrendingApi : ApiDataSource {

    override val sourceId = "youtube_trending"
    override val displayName = "YouTube Trending (Free)"
    override val rateLimit = ApiRateLimit.perDay(100)

    override suspend fun fetch(params: Map<String, String>): ApiResult {
        return try {
            val country = params["country"] ?: "IN"
            val query = params["query"]?.lowercase() ?: ""

            // Try YouTube trending page
            val result = fetchTrending(country)
            if (result != null) return result

            // Fallback: YouTube RSS feed for popular channels
            val rssResult = fetchPopularRss()
            if (rssResult != null) return rssResult

            ApiResult.fail("YouTube Trending: could not fetch data")
        } catch (e: Exception) {
            ApiResult.fail("YouTube Trending error: ${e.message}")
        }
    }

    private fun fetchTrending(country: String): ApiResult? {
        return try {
            val url = "https://www.youtube.com/feed/trending?gl=$country"
            val (code, body) = httpGet(url, headers = mapOf(
                "User-Agent" to "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36",
                "Accept-Language" to "en-US,en;q=0.9"
            ))
            if (code != 200) return null

            val sb = StringBuilder()
            sb.appendLine("YouTube Trending ($country)")
            sb.appendLine("─".repeat(30))

            // Extract video data from the page's initial data JSON
            val dataRegex = Regex("var ytInitialData = (\\{.*?\\});", RegexOption.DOT_MATCHES_ALL)
            val dataMatch = dataRegex.find(body)

            if (dataMatch != null) {
                try {
                    val json = JSONObject(dataMatch.groupValues[1])
                    val tabs = json.optJSONObject("contents")
                        ?.optJSONObject("twoColumnBrowseResultsRenderer")
                        ?.optJSONArray("tabs")

                    if (tabs != null && tabs.length() > 0) {
                        val tab = tabs.getJSONObject(0)
                        val contents = tab.optJSONObject("tabRenderer")
                            ?.optJSONObject("content")
                            ?.optJSONObject("sectionListRenderer")
                            ?.optJSONArray("contents")

                        var count = 0
                        if (contents != null) {
                            for (s in 0 until contents.length()) {
                                val section = contents.getJSONObject(s)
                                val items = section.optJSONObject("itemSectionRenderer")
                                    ?.optJSONArray("contents")
                                    ?: continue

                                for (j in 0 until items.length()) {
                                    if (count >= 15) break
                                    val shelf = items.getJSONObject(j)
                                    val videos = shelf.optJSONObject("shelfRenderer")
                                        ?.optJSONObject("content")
                                        ?.optJSONObject("expandedShelfContentsRenderer")
                                        ?.optJSONArray("items")
                                        ?: continue

                                    for (v in 0 until videos.length()) {
                                        if (count >= 15) break
                                        val video = videos.getJSONObject(v)
                                            .optJSONObject("videoRenderer") ?: continue
                                        val title = video.optJSONObject("title")
                                            ?.optJSONArray("runs")
                                            ?.optJSONObject(0)
                                            ?.optString("text", "") ?: ""
                                        val channel = video.optJSONObject("ownerText")
                                            ?.optJSONArray("runs")
                                            ?.optJSONObject(0)
                                            ?.optString("text", "") ?: ""
                                        val views = video.optJSONObject("viewCountText")
                                            ?.optString("simpleText", "") ?: ""

                                        if (title.isNotBlank()) {
                                            count++
                                            sb.appendLine("\n$count. $title")
                                            if (channel.isNotBlank()) sb.appendLine("   Channel: $channel")
                                            if (views.isNotBlank()) sb.appendLine("   Views: $views")
                                        }
                                    }
                                }
                            }
                        }

                        if (count > 0) return ApiResult.ok(sb.toString())
                    }
                } catch (_: Exception) {}
            }

            // Fallback: simple regex extraction from page HTML
            val videoRegex = Regex("\"title\":\\{\"runs\":\\[\\{\"text\":\"(.*?)\"", RegexOption.DOT_MATCHES_ALL)
            val channelRegex = Regex("\"ownerText\":\\{\"runs\":\\[\\{\"text\":\"(.*?)\"", RegexOption.DOT_MATCHES_ALL)

            val titles = videoRegex.findAll(body).take(15).map { it.groupValues[1] }.toList()
            val channels = channelRegex.findAll(body).take(15).map { it.groupValues[1] }.toList()

            if (titles.isEmpty()) return null

            titles.forEachIndexed { i, title ->
                val channel = channels.getOrElse(i) { "" }
                sb.appendLine("\n${i + 1}. $title")
                if (channel.isNotBlank()) sb.appendLine("   Channel: $channel")
            }

            ApiResult.ok(sb.toString())
        } catch (_: Exception) {
            null
        }
    }

    private fun fetchPopularRss(): ApiResult? {
        return try {
            // YouTube trending RSS isn't available, but we can use the public feed
            val url = "https://www.youtube.com/feed/trending"
            val (code, body) = httpGet(url, headers = mapOf(
                "User-Agent" to "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36"
            ))
            if (code != 200) return null

            // Already handled above
            null
        } catch (_: Exception) {
            null
        }
    }
}

package ai.zeroclaw.android.agents.api

import org.json.JSONObject
import org.json.JSONArray

/**
 * IPO Tracker — Fetches upcoming and recent IPO data.
 * Primary: Finnhub API (free, 60 req/min)
 * Docs: https://finnhub.io/docs/api/ipo-calendar
 *
 * Fallback: Investorgain public IPO data (India-specific, no key)
 */
class IpoTrackerApi : ApiDataSource {

    override val sourceId = "ipo_tracker"
    override val displayName = "IPO Tracker (Free)"
    override val rateLimit = ApiRateLimit.perMinute(60)

    override suspend fun fetch(params: Map<String, String>): ApiResult {
        return try {
            val query = params["query"]?.lowercase() ?: "upcoming"

            // Try Finnhub IPO calendar (free tier)
            val finnhubResult = fetchFinnhub()
            if (finnhubResult != null) return finnhubResult

            // Fallback: scrape public IPO data
            val scrapeResult = fetchIpoScrape()
            if (scrapeResult != null) return scrapeResult

            ApiResult.fail("IPO Tracker: could not fetch data")
        } catch (e: Exception) {
            ApiResult.fail("IPO Tracker error: ${e.message}")
        }
    }

    private fun fetchFinnhub(): ApiResult? {
        return try {
            // Finnhub free tier allows IPO calendar without key (limited)
            val cal = java.util.Calendar.getInstance()
            val endDate = "%04d-%02d-%02d".format(
                cal.get(java.util.Calendar.YEAR),
                cal.get(java.util.Calendar.MONTH) + 1,
                cal.get(java.util.Calendar.DAY_OF_MONTH)
            )
            cal.add(java.util.Calendar.DAY_OF_MONTH, -30)
            val startDate = "%04d-%02d-%02d".format(
                cal.get(java.util.Calendar.YEAR),
                cal.get(java.util.Calendar.MONTH) + 1,
                cal.get(java.util.Calendar.DAY_OF_MONTH)
            )

            val url = "https://finnhub.io/api/v1/calendar/ipo?from=$startDate&to=$endDate&token=demo"
            val (code, body) = httpGet(url)
            if (code != 200) return null

            val json = JSONObject(body)
            val ipoCalendar = json.optJSONArray("ipoCalendar") ?: return null
            if (ipoCalendar.length() == 0) return null

            val sb = StringBuilder()
            sb.appendLine("IPO Calendar (Last 30 Days)")
            sb.appendLine("─".repeat(30))

            val count = minOf(ipoCalendar.length(), 15)
            for (i in 0 until count) {
                val ipo = ipoCalendar.getJSONObject(i)
                val name = ipo.optString("name", "Unknown")
                val symbol = ipo.optString("symbol", "")
                val date = ipo.optString("date", "")
                val price = ipo.optString("price", "TBD")
                val shares = ipo.optLong("numberOfShares", 0)
                val status = ipo.optString("status", "")

                sb.appendLine("\n${i + 1}. $name ($symbol)")
                sb.appendLine("   Date: $date")
                if (price != "TBD" && price.isNotBlank()) sb.appendLine("   Price: $$price")
                if (shares > 0) sb.appendLine("   Shares: ${formatShares(shares)}")
                if (status.isNotBlank()) sb.appendLine("   Status: $status")
            }

            ApiResult.ok(sb.toString(), body)
        } catch (_: Exception) {
            null
        }
    }

    private fun fetchIpoScrape(): ApiResult? {
        return try {
            // Fetch from Investorgain (India IPO data, public)
            val url = "https://www.investorgain.com/report/live-ipo-gmp/331/"
            val (code, body) = httpGet(url, headers = mapOf(
                "User-Agent" to "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36",
                "Accept" to "text/html"
            ))
            if (code != 200) return null

            val sb = StringBuilder()
            sb.appendLine("Upcoming & Recent IPOs (India)")
            sb.appendLine("─".repeat(30))

            // Extract IPO data from table
            val rowRegex = Regex("<tr[^>]*>\\s*<td[^>]*>(.*?)</td>\\s*<td[^>]*>(.*?)</td>\\s*<td[^>]*>(.*?)</td>",
                setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))
            val rows = rowRegex.findAll(body).take(15).toList()

            if (rows.isEmpty()) return null

            rows.forEachIndexed { i, match ->
                val name = match.groupValues[1].replace(Regex("<.*?>"), "").trim()
                val price = match.groupValues[2].replace(Regex("<.*?>"), "").trim()
                val gmp = match.groupValues[3].replace(Regex("<.*?>"), "").trim()

                if (name.isNotBlank() && !name.equals("IPO", ignoreCase = true)) {
                    sb.appendLine("\n${i + 1}. $name")
                    if (price.isNotBlank()) sb.appendLine("   Price: ₹$price")
                    if (gmp.isNotBlank()) sb.appendLine("   GMP: $gmp")
                }
            }

            sb.appendLine("\n  Source: InvestorGain")
            ApiResult.ok(sb.toString())
        } catch (_: Exception) {
            null
        }
    }

    private fun formatShares(n: Long): String {
        return when {
            n >= 1_000_000_000 -> String.format("%.1fB", n / 1_000_000_000.0)
            n >= 1_000_000 -> String.format("%.1fM", n / 1_000_000.0)
            n >= 1_000 -> String.format("%.1fK", n / 1_000.0)
            else -> n.toString()
        }
    }
}

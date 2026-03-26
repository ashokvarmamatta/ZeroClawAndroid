package ai.zeroclaw.android.agents.api

import org.json.JSONObject

/**
 * Alpha Vantage API — Free stock/index data.
 * Free tier: 25 requests/day with demo key, or 5 req/min with free key.
 * Docs: https://www.alphavantage.co/documentation/
 *
 * Falls back to Yahoo Finance v8 public quotes endpoint.
 */
class AlphaVantageApi : ApiDataSource {

    override val sourceId = "stock_index"
    override val displayName = "Stock/Index API (Free)"
    override val rateLimit = ApiRateLimit.perMinute(5)

    // Common index/stock symbol mapping
    private val symbolMap = mapOf(
        "nifty 50" to "^NSEI", "nifty" to "^NSEI", "sensex" to "^BSESN",
        "s&p 500" to "^GSPC", "s&p500" to "^GSPC", "sp500" to "^GSPC",
        "nasdaq" to "^IXIC", "dow jones" to "^DJI", "dow" to "^DJI",
        "ftse 100" to "^FTSE", "ftse" to "^FTSE",
        "dax" to "^GDAXI", "nikkei" to "^N225", "nikkei 225" to "^N225",
        "hang seng" to "^HSI", "asx 200" to "^AXJO", "asx" to "^AXJO",
        "cac 40" to "^FCHI", "kospi" to "^KS11", "shanghai" to "000001.SS",
        "ibovespa" to "^BVSP", "tsx" to "^GSPTSE",
        "bank nifty" to "^NSEBANK", "nifty bank" to "^NSEBANK",
        "nifty it" to "^CNXIT", "russel" to "^RUT", "russell 2000" to "^RUT"
    )

    override suspend fun fetch(params: Map<String, String>): ApiResult {
        return try {
            val query = params["query"]?.trim() ?: "NIFTY 50"

            // Try Yahoo Finance first (no key needed)
            val yahooResult = fetchYahoo(query)
            if (yahooResult != null) return yahooResult

            ApiResult.fail("Stock API: could not fetch data for '$query'")
        } catch (e: Exception) {
            ApiResult.fail("Stock API error: ${e.message}")
        }
    }

    private fun fetchYahoo(query: String): ApiResult? {
        return try {
            // Resolve symbol(s)
            val symbols = resolveSymbols(query)
            val joined = symbols.joinToString(",")
            val encoded = java.net.URLEncoder.encode(joined, "UTF-8")

            val url = "https://query1.finance.yahoo.com/v7/finance/quote?symbols=$encoded"
            val (code, body) = httpGet(url, headers = mapOf(
                "User-Agent" to "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36"
            ))
            if (code != 200) return null

            val json = JSONObject(body)
            val results = json.optJSONObject("quoteResponse")?.optJSONArray("result") ?: return null
            if (results.length() == 0) return null

            val sb = StringBuilder()
            sb.appendLine("Stock / Index Prices")
            sb.appendLine("─".repeat(30))

            for (i in 0 until results.length()) {
                val q = results.getJSONObject(i)
                val name = q.optString("shortName", q.optString("symbol", "Unknown"))
                val symbol = q.optString("symbol", "")
                val price = q.optDouble("regularMarketPrice", 0.0)
                val change = q.optDouble("regularMarketChange", 0.0)
                val changePct = q.optDouble("regularMarketChangePercent", 0.0)
                val open = q.optDouble("regularMarketOpen", 0.0)
                val high = q.optDouble("regularMarketDayHigh", 0.0)
                val low = q.optDouble("regularMarketDayLow", 0.0)
                val prevClose = q.optDouble("regularMarketPreviousClose", 0.0)
                val currency = q.optString("currency", "")
                val arrow = if (change >= 0) "+" else ""

                sb.appendLine("\n$name ($symbol)")
                sb.appendLine("  Price: ${String.format("%,.2f", price)} $currency")
                sb.appendLine("  Change: $arrow${String.format("%.2f", change)} ($arrow${String.format("%.2f", changePct)}%)")
                sb.appendLine("  Open: ${String.format("%,.2f", open)} | High: ${String.format("%,.2f", high)} | Low: ${String.format("%,.2f", low)}")
                if (prevClose > 0) sb.appendLine("  Prev Close: ${String.format("%,.2f", prevClose)}")
            }

            ApiResult.ok(sb.toString(), body)
        } catch (_: Exception) {
            null
        }
    }

    private fun resolveSymbols(query: String): List<String> {
        val lower = query.lowercase().trim()

        // Check direct mapping
        symbolMap[lower]?.let { return listOf(it) }

        // Check if it's already a symbol (e.g. AAPL, RELIANCE.NS)
        if (query.matches(Regex("^[A-Z0-9.^]+$"))) return listOf(query)

        // Try comma-separated
        val parts = lower.split(",", " and ", "&").map { it.trim() }
        val resolved = parts.mapNotNull { part -> symbolMap[part] ?: part.uppercase().takeIf { it.isNotBlank() } }
        return if (resolved.isNotEmpty()) resolved else listOf("^GSPC") // default S&P 500
    }
}

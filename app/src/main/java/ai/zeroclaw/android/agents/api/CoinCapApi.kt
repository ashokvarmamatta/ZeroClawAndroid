package ai.zeroclaw.android.agents.api

import org.json.JSONObject

/**
 * CoinCap API v2 — Free crypto market data.
 * No API key required. Rate limit: 200 requests/minute.
 * Docs: https://docs.coincap.io/
 */
class CoinCapApi : ApiDataSource {

    override val sourceId = "coincap"
    override val displayName = "CoinCap (Free)"
    override val rateLimit = ApiRateLimit.perMinute(200)

    override suspend fun fetch(params: Map<String, String>): ApiResult {
        return try {
            val query = params["query"]?.lowercase() ?: ""
            val limit = params["limit"]?.toIntOrNull() ?: 15

            val url = if (query.isNotBlank() && query != "all" && query != "top") {
                "https://api.coincap.io/v2/assets?search=$query&limit=$limit"
            } else {
                "https://api.coincap.io/v2/assets?limit=$limit"
            }

            val (code, body) = httpGet(url)
            if (code != 200) return ApiResult.fail("CoinCap HTTP $code")

            val json = JSONObject(body)
            val data = json.getJSONArray("data")

            val sb = StringBuilder()
            sb.appendLine("Crypto Market Overview (via CoinCap)")
            sb.appendLine("─".repeat(35))

            for (i in 0 until data.length()) {
                val coin = data.getJSONObject(i)
                val rank = coin.optString("rank", "?")
                val name = coin.optString("name", "Unknown")
                val symbol = coin.optString("symbol", "?")
                val priceUsd = coin.optString("priceUsd", "0").toDoubleOrNull() ?: 0.0
                val change24h = coin.optString("changePercent24Hr", "0").toDoubleOrNull() ?: 0.0
                val marketCap = coin.optString("marketCapUsd", "0").toDoubleOrNull() ?: 0.0
                val volume24h = coin.optString("volumeUsd24Hr", "0").toDoubleOrNull() ?: 0.0
                val supply = coin.optString("supply", "0").toDoubleOrNull() ?: 0.0

                val arrow = if (change24h >= 0) "+" else ""

                sb.appendLine("\n#$rank $name ($symbol)")
                sb.appendLine("  Price: $${formatPrice(priceUsd)}")
                sb.appendLine("  24h: $arrow${String.format("%.2f", change24h)}%")
                if (marketCap > 0) sb.appendLine("  MCap: ${formatLarge(marketCap)}")
                if (volume24h > 0) sb.appendLine("  Vol: ${formatLarge(volume24h)}")
            }

            ApiResult.ok(sb.toString(), body)
        } catch (e: Exception) {
            ApiResult.fail("CoinCap error: ${e.message}")
        }
    }

    private fun formatPrice(n: Double): String = when {
        n >= 1.0 -> String.format("%,.2f", n)
        n >= 0.01 -> String.format("%.4f", n)
        else -> String.format("%.8f", n)
    }

    private fun formatLarge(n: Double): String = when {
        n >= 1e12 -> String.format("$%.2fT", n / 1e12)
        n >= 1e9 -> String.format("$%.2fB", n / 1e9)
        n >= 1e6 -> String.format("$%.2fM", n / 1e6)
        else -> String.format("$%,.0f", n)
    }
}

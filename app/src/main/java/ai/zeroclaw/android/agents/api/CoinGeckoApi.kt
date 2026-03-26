package ai.zeroclaw.android.agents.api

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * CoinGecko API — Free crypto price data.
 * No API key required. Rate limit: 30 requests/minute.
 * Docs: https://www.coingecko.com/en/api/documentation
 */
class CoinGeckoApi : ApiDataSource {

    override val sourceId = "coingecko"
    override val displayName = "CoinGecko (Free)"
    override val rateLimit = ApiRateLimit.perMinute(30)

    override suspend fun fetch(params: Map<String, String>): ApiResult {
        return try {
            val query = params["query"]?.lowercase() ?: "bitcoin"
            // Map common names to CoinGecko IDs
            val ids = mapQueryToIds(query)
            val currency = params["currency"]?.lowercase() ?: "usd"

            val url = "https://api.coingecko.com/api/v3/simple/price?ids=$ids&vs_currencies=$currency&include_24hr_change=true&include_market_cap=true&include_24hr_vol=true"

            val (code, body) = httpGet(url)
            if (code != 200) return ApiResult.fail("CoinGecko HTTP $code")

            val json = JSONObject(body)
            val sb = StringBuilder()
            sb.appendLine("Crypto Prices (via CoinGecko)")
            sb.appendLine("Currency: ${currency.uppercase()}")
            sb.appendLine("─".repeat(30))

            for (id in ids.split(",")) {
                val coin = json.optJSONObject(id) ?: continue
                val price = coin.optDouble("${currency}", 0.0)
                val change24h = coin.optDouble("${currency}_24h_change", 0.0)
                val marketCap = coin.optDouble("${currency}_market_cap", 0.0)
                val vol24h = coin.optDouble("${currency}_24h_vol", 0.0)
                val arrow = if (change24h >= 0) "+" else ""
                val name = id.replaceFirstChar { it.uppercase() }

                sb.appendLine("\n$name")
                sb.appendLine("  Price: ${formatNumber(price)} ${currency.uppercase()}")
                sb.appendLine("  24h Change: $arrow${String.format("%.2f", change24h)}%")
                if (marketCap > 0) sb.appendLine("  Market Cap: ${formatLargeNumber(marketCap)}")
                if (vol24h > 0) sb.appendLine("  24h Volume: ${formatLargeNumber(vol24h)}")
            }

            ApiResult.ok(sb.toString(), body)
        } catch (e: Exception) {
            ApiResult.fail("CoinGecko error: ${e.message}")
        }
    }

    private fun mapQueryToIds(query: String): String {
        val mapping = mapOf(
            "btc" to "bitcoin", "bitcoin" to "bitcoin",
            "eth" to "ethereum", "ethereum" to "ethereum",
            "sol" to "solana", "solana" to "solana",
            "xrp" to "ripple", "ripple" to "ripple",
            "bnb" to "binancecoin", "binance" to "binancecoin",
            "ada" to "cardano", "cardano" to "cardano",
            "doge" to "dogecoin", "dogecoin" to "dogecoin",
            "dot" to "polkadot", "polkadot" to "polkadot",
            "matic" to "matic-network", "polygon" to "matic-network",
            "avax" to "avalanche-2", "avalanche" to "avalanche-2",
            "link" to "chainlink", "chainlink" to "chainlink",
            "shib" to "shiba-inu", "shiba" to "shiba-inu",
            "sui" to "sui", "ton" to "the-open-network",
            "near" to "near", "apt" to "aptos", "aptos" to "aptos"
        )
        // If query matches a known name, use it; otherwise try as comma-separated
        val id = mapping[query.trim()]
        if (id != null) return id

        // Default: top coins
        return "bitcoin,ethereum,solana,ripple,binancecoin,cardano,dogecoin"
    }

    private fun formatNumber(n: Double): String {
        return if (n >= 1.0) String.format("%,.2f", n)
        else if (n >= 0.01) String.format("%.4f", n)
        else String.format("%.8f", n)
    }

    private fun formatLargeNumber(n: Double): String {
        return when {
            n >= 1_000_000_000_000 -> String.format("$%.2fT", n / 1_000_000_000_000)
            n >= 1_000_000_000 -> String.format("$%.2fB", n / 1_000_000_000)
            n >= 1_000_000 -> String.format("$%.2fM", n / 1_000_000)
            else -> String.format("$%,.0f", n)
        }
    }
}

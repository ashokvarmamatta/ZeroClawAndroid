package ai.zeroclaw.android.agents.api

import org.json.JSONArray
import org.json.JSONObject

/**
 * Metals.live API — Free gold, silver, platinum, palladium prices.
 * No API key required. No documented rate limit.
 * Docs: https://metals.live (returns spot prices in USD/oz)
 */
class MetalsLiveApi : ApiDataSource {

    override val sourceId = "metals_live"
    override val displayName = "Metals.live (Free)"
    override val rateLimit = ApiRateLimit.perMinute(10) // conservative

    override suspend fun fetch(params: Map<String, String>): ApiResult {
        return try {
            val (code, body) = httpGet("https://api.metals.live/v1/spot")
            if (code != 200) return ApiResult.fail("Metals.live HTTP $code")

            val arr = JSONArray(body)
            val sb = StringBuilder()
            sb.appendLine("Precious Metals Spot Prices (via Metals.live)")
            sb.appendLine("All prices in USD per troy ounce")
            sb.appendLine("─".repeat(30))

            val query = params["query"]?.lowercase() ?: ""

            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                val metal = obj.optString("metal", "")
                val price = obj.optDouble("price", 0.0)
                val timestamp = obj.optLong("timestamp", 0L)

                // Filter if query specifies a particular metal
                if (query.isNotBlank() && !metal.lowercase().contains(query) &&
                    !matchesMetalAlias(query, metal)) continue

                val symbol = metalSymbol(metal)
                sb.appendLine("\n$symbol $metal")
                sb.appendLine("  Price: $${String.format("%,.2f", price)}/oz")

                // Convert to common units
                val perGram = price / 31.1035
                val per10Gram = perGram * 10
                sb.appendLine("  Per gram: $${String.format("%.2f", perGram)}")
                sb.appendLine("  Per 10g: $${String.format("%.2f", per10Gram)}")
            }

            if (sb.toString().count { it == '\n' } <= 3) {
                return ApiResult.fail("No metals data found for query: $query")
            }

            ApiResult.ok(sb.toString(), body)
        } catch (e: Exception) {
            ApiResult.fail("Metals.live error: ${e.message}")
        }
    }

    private fun matchesMetalAlias(query: String, metal: String): Boolean {
        val aliases = mapOf(
            "gold" to "Gold", "au" to "Gold",
            "silver" to "Silver", "ag" to "Silver",
            "platinum" to "Platinum", "pt" to "Platinum",
            "palladium" to "Palladium", "pd" to "Palladium",
            "copper" to "Copper", "cu" to "Copper"
        )
        return aliases[query]?.equals(metal, ignoreCase = true) == true
    }

    private fun metalSymbol(metal: String): String = when (metal.lowercase()) {
        "gold" -> "Au"
        "silver" -> "Ag"
        "platinum" -> "Pt"
        "palladium" -> "Pd"
        "copper" -> "Cu"
        else -> metal.take(2)
    }
}

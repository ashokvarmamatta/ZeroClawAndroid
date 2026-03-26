package ai.zeroclaw.android.agents.api

import org.json.JSONObject

/**
 * ExchangeRate-API — Free currency exchange rates.
 * No API key needed for the open endpoint (v6/latest).
 * Rate limit: ~1500 requests/month on free tier.
 * Docs: https://www.exchangerate-api.com/docs/free
 */
class ExchangeRateApi : ApiDataSource {

    override val sourceId = "exchangerate"
    override val displayName = "ExchangeRate-API (Free)"
    override val rateLimit = ApiRateLimit.perDay(50) // ~1500/month ≈ 50/day

    override suspend fun fetch(params: Map<String, String>): ApiResult {
        return try {
            val query = params["query"] ?: "USD/INR"
            val parts = query.replace(" ", "").uppercase().split("/", "-", "→", "TO")
            val base = parts.getOrElse(0) { "USD" }
            val target = if (parts.size > 1) parts[1] else null

            val url = "https://open.er-api.com/v6/latest/$base"
            val (code, body) = httpGet(url)
            if (code != 200) return ApiResult.fail("ExchangeRate-API HTTP $code")

            val json = JSONObject(body)
            if (json.optString("result") != "success") {
                return ApiResult.fail("ExchangeRate-API: ${json.optString("error-type", "unknown error")}")
            }

            val rates = json.getJSONObject("rates")
            val lastUpdate = json.optString("time_last_update_utc", "")

            val sb = StringBuilder()
            sb.appendLine("Exchange Rates (via ExchangeRate-API)")
            sb.appendLine("Base: $base")
            sb.appendLine("Updated: $lastUpdate")
            sb.appendLine("─".repeat(30))

            if (target != null) {
                // Single pair
                val rate = rates.optDouble(target, -1.0)
                if (rate < 0) return ApiResult.fail("Currency $target not found")
                sb.appendLine("\n1 $base = ${String.format("%.4f", rate)} $target")
                sb.appendLine("1 $target = ${String.format("%.6f", 1.0 / rate)} $base")
            } else {
                // Show top currencies
                val popular = listOf("USD", "EUR", "GBP", "INR", "JPY", "CNY", "CAD", "AUD", "CHF", "SGD", "AED", "SAR", "KRW", "BRL", "MXN")
                for (cur in popular) {
                    if (cur == base) continue
                    val rate = rates.optDouble(cur, -1.0)
                    if (rate > 0) {
                        sb.appendLine("  1 $base = ${String.format("%,.4f", rate)} $cur")
                    }
                }
            }

            ApiResult.ok(sb.toString(), body)
        } catch (e: Exception) {
            ApiResult.fail("ExchangeRate-API error: ${e.message}")
        }
    }
}

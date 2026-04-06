package ai.zeroclaw.android.agents.api

import org.json.JSONArray
import org.json.JSONObject

/**
 * MFAPI.in — Free, unlimited Indian mutual fund NAV data.
 * No API key required.
 * Docs: https://www.mfapi.in/
 */
class MfApiIndiaApi : ApiDataSource {

    override val sourceId = "mfapi_india"
    override val displayName = "MFAPI.in (Free, Unlimited)"
    override val rateLimit = ApiRateLimit.unlimited("Unlimited (public API)")

    // Top mutual fund scheme codes (popular Indian MFs)
    private val popularFunds = mapOf(
        "119551" to "Axis Bluechip Fund - Direct Growth",
        "120503" to "Mirae Asset Large Cap Fund - Direct Growth",
        "120505" to "SBI Bluechip Fund - Direct Growth",
        "118989" to "ICICI Prudential Bluechip Fund - Direct Growth",
        "100527" to "HDFC Top 100 Fund - Direct Growth",
        "122639" to "Parag Parikh Flexi Cap Fund - Direct Growth",
        "120716" to "UTI Nifty 50 Index Fund - Direct Growth",
        "118834" to "SBI Small Cap Fund - Direct Growth",
        "125354" to "Nippon India Small Cap Fund - Direct Growth",
        "120587" to "Kotak Emerging Equity Fund - Direct Growth"
    )

    override suspend fun fetch(params: Map<String, String>): ApiResult {
        return try {
            val query = params["query"]?.lowercase() ?: ""
            val schemeCode = params["scheme_code"]

            if (schemeCode != null) {
                return fetchSingleFund(schemeCode)
            }

            // Fetch top popular funds
            val sb = StringBuilder()
            sb.appendLine("Mutual Fund NAV Report (via MFAPI.in)")
            sb.appendLine("Top Indian Mutual Funds - Latest NAV")
            sb.appendLine("─".repeat(35))

            var fetched = 0
            for ((code, name) in popularFunds) {
                if (query.isNotBlank() && !name.lowercase().contains(query)) continue
                try {
                    val (httpCode, body) = httpGet("https://api.mfapi.in/mf/$code/latest")
                    if (httpCode != 200) continue

                    val json = JSONObject(body)
                    val data = json.optJSONArray("data")
                    if (data != null && data.length() > 0) {
                        val latest = data.getJSONObject(0)
                        val nav = latest.optString("nav", "N/A")
                        val date = latest.optString("date", "N/A")
                        val fundName = json.optJSONObject("meta")?.optString("fund_house", "") ?: ""

                        sb.appendLine("\n${fetched + 1}. $name")
                        if (fundName.isNotBlank()) sb.appendLine("   Fund House: $fundName")
                        sb.appendLine("   NAV: Rs $nav")
                        sb.appendLine("   Date: $date")
                        fetched++
                    }
                } catch (_: Exception) { /* skip failed fund */ }

                if (fetched >= 10) break
            }

            if (fetched == 0) {
                return ApiResult.fail("No mutual fund data found for query: $query")
            }

            ApiResult.ok(sb.toString())
        } catch (e: Exception) {
            ApiResult.fail("MFAPI error: ${e.message}")
        }
    }

    private suspend fun fetchSingleFund(code: String): ApiResult {
        val (httpCode, body) = httpGet("https://api.mfapi.in/mf/$code")
        if (httpCode != 200) return ApiResult.fail("MFAPI HTTP $httpCode for scheme $code")

        val json = JSONObject(body)
        val meta = json.optJSONObject("meta")
        val data = json.optJSONArray("data")

        val sb = StringBuilder()
        sb.appendLine("Mutual Fund Details (via MFAPI.in)")
        sb.appendLine("─".repeat(35))
        sb.appendLine("Fund: ${meta?.optString("scheme_name", "Unknown")}")
        sb.appendLine("Category: ${meta?.optString("scheme_category", "N/A")}")
        sb.appendLine("Type: ${meta?.optString("scheme_type", "N/A")}")
        sb.appendLine("Fund House: ${meta?.optString("fund_house", "N/A")}")

        if (data != null && data.length() > 0) {
            sb.appendLine("\nRecent NAV History:")
            val count = minOf(data.length(), 10)
            for (i in 0 until count) {
                val entry = data.getJSONObject(i)
                sb.appendLine("  ${entry.optString("date")}: Rs ${entry.optString("nav")}")
            }
        }

        return ApiResult.ok(sb.toString(), body)
    }
}

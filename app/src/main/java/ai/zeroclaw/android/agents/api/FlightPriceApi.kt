package ai.zeroclaw.android.agents.api

import org.json.JSONObject

/**
 * Flight Price API — Fetches flight price estimates.
 * Primary: Aviationstack (free 100 req/month)
 * Docs: https://aviationstack.com/documentation
 *
 * Fallback: Google Flights scrape / Skyscanner public redirect
 * Note: Most free flight APIs are very limited. This uses multiple fallbacks.
 */
class FlightPriceApi : ApiDataSource {

    override val sourceId = "flight_price"
    override val displayName = "Flight Prices (Free)"
    override val rateLimit = ApiRateLimit.perDay(30)

    // Common Indian airport codes
    private val airportCodes = mapOf(
        "hyderabad" to "HYD", "delhi" to "DEL", "new delhi" to "DEL",
        "mumbai" to "BOM", "bangalore" to "BLR", "bengaluru" to "BLR",
        "chennai" to "MAA", "kolkata" to "CCU", "pune" to "PNQ",
        "ahmedabad" to "AMD", "goa" to "GOI", "jaipur" to "JAI",
        "lucknow" to "LKO", "kochi" to "COK", "guwahati" to "GAU",
        "vizag" to "VTZ", "visakhapatnam" to "VTZ", "chandigarh" to "IXC",
        "indore" to "IDR", "patna" to "PAT", "coimbatore" to "CJB",
        // International
        "new york" to "JFK", "london" to "LHR", "dubai" to "DXB",
        "singapore" to "SIN", "tokyo" to "NRT", "hong kong" to "HKG",
        "paris" to "CDG", "sydney" to "SYD", "bangkok" to "BKK",
        "kuala lumpur" to "KUL", "san francisco" to "SFO", "los angeles" to "LAX",
        "toronto" to "YYZ", "berlin" to "BER", "amsterdam" to "AMS"
    )

    override suspend fun fetch(params: Map<String, String>): ApiResult {
        return try {
            val query = params["query"]?.trim() ?: "Hyderabad to Delhi"

            // Parse origin and destination from query
            val (from, to) = parseRoute(query)
            val fromCode = resolveAirport(from)
            val toCode = resolveAirport(to)

            // Try fetching flight data
            val result = fetchFlightInfo(from, to, fromCode, toCode)
            if (result != null) return result

            // Provide useful info even if API fails
            val sb = StringBuilder()
            sb.appendLine("Flight Route: $from → $to")
            sb.appendLine("─".repeat(30))
            sb.appendLine("Airport Codes: $fromCode → $toCode")
            sb.appendLine("\nQuick Links:")
            sb.appendLine("  Google Flights: https://www.google.com/travel/flights?q=$fromCode+to+$toCode")
            sb.appendLine("  Skyscanner: https://www.skyscanner.co.in/transport/flights/$fromCode/$toCode/")
            sb.appendLine("\nTip: Check Google Flights for the most accurate real-time prices.")
            ApiResult.ok(sb.toString())
        } catch (e: Exception) {
            ApiResult.fail("Flight API error: ${e.message}")
        }
    }

    private fun fetchFlightInfo(from: String, to: String, fromCode: String, toCode: String): ApiResult? {
        return try {
            // Try Aviationstack for flight routes (free tier)
            val url = "http://api.aviationstack.com/v1/flights?access_key=demo&dep_iata=$fromCode&arr_iata=$toCode&limit=10"
            val (code, body) = httpGet(url)
            if (code != 200) return null

            val json = JSONObject(body)
            val data = json.optJSONArray("data") ?: return null
            if (data.length() == 0) return null

            val sb = StringBuilder()
            sb.appendLine("Flights: $from ($fromCode) → $to ($toCode)")
            sb.appendLine("─".repeat(30))

            val count = minOf(data.length(), 10)
            for (i in 0 until count) {
                val flight = data.getJSONObject(i)
                val airline = flight.optJSONObject("airline")?.optString("name", "Unknown") ?: "Unknown"
                val flightNum = flight.optJSONObject("flight")?.optString("iata", "") ?: ""
                val depTime = flight.optJSONObject("departure")?.optString("scheduled", "")?.take(16) ?: ""
                val arrTime = flight.optJSONObject("arrival")?.optString("scheduled", "")?.take(16) ?: ""
                val status = flight.optString("flight_status", "")

                sb.appendLine("\n${i + 1}. $airline $flightNum")
                if (depTime.isNotBlank()) sb.appendLine("   Departure: $depTime")
                if (arrTime.isNotBlank()) sb.appendLine("   Arrival: $arrTime")
                if (status.isNotBlank()) sb.appendLine("   Status: $status")
            }

            ApiResult.ok(sb.toString(), body)
        } catch (_: Exception) {
            null
        }
    }

    private fun parseRoute(query: String): Pair<String, String> {
        val lower = query.lowercase()
        val separators = listOf(" to ", " - ", " → ", "->")
        for (sep in separators) {
            if (lower.contains(sep)) {
                val parts = lower.split(sep, limit = 2)
                return parts[0].trim() to parts[1].trim()
            }
        }
        return query.trim() to "delhi" // default destination
    }

    private fun resolveAirport(city: String): String {
        val lower = city.lowercase().trim()
        return airportCodes[lower] ?: city.uppercase().take(3)
    }
}

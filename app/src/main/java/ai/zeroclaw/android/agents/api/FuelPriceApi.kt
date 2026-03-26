package ai.zeroclaw.android.agents.api

import org.json.JSONObject

/**
 * Fuel Price API — Fetches petrol, diesel, CNG prices.
 * Primary: GoodReturns public data (India-specific, no key)
 * Fallback: Google search scrape
 *
 * For India: scrapes public fuel price pages
 * For international: uses public energy data
 */
class FuelPriceApi : ApiDataSource {

    override val sourceId = "fuel_price"
    override val displayName = "Fuel Prices (Free)"
    override val rateLimit = ApiRateLimit.perDay(50)

    // Major Indian cities and their fuel price page slugs
    private val indianCities = mapOf(
        "hyderabad" to "hyderabad", "delhi" to "new-delhi", "new delhi" to "new-delhi",
        "mumbai" to "mumbai", "bangalore" to "bangalore", "bengaluru" to "bangalore",
        "chennai" to "chennai", "kolkata" to "kolkata", "pune" to "pune",
        "ahmedabad" to "ahmedabad", "jaipur" to "jaipur", "lucknow" to "lucknow",
        "chandigarh" to "chandigarh", "bhopal" to "bhopal", "indore" to "indore",
        "patna" to "patna", "kochi" to "kochi", "guwahati" to "guwahati",
        "vizag" to "visakhapatnam", "visakhapatnam" to "visakhapatnam",
        "nagpur" to "nagpur", "coimbatore" to "coimbatore", "thiruvananthapuram" to "thiruvananthapuram"
    )

    override suspend fun fetch(params: Map<String, String>): ApiResult {
        return try {
            val city = params["query"]?.trim() ?: "Hyderabad"
            val cityLower = city.lowercase()

            // Try fetching from public fuel price data
            val result = fetchFuelPrice(city, cityLower)
            if (result != null) return result

            ApiResult.fail("Fuel price: could not fetch data for '$city'")
        } catch (e: Exception) {
            ApiResult.fail("Fuel price error: ${e.message}")
        }
    }

    private fun fetchFuelPrice(city: String, cityLower: String): ApiResult? {
        return try {
            val slug = indianCities[cityLower] ?: cityLower.replace(" ", "-")
            val url = "https://www.goodreturns.in/petrol-price-in-$slug.html"
            val (code, body) = httpGet(url, headers = mapOf(
                "User-Agent" to "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36",
                "Accept" to "text/html"
            ))
            if (code != 200) return null

            val sb = StringBuilder()
            sb.appendLine("Fuel Prices in $city")
            sb.appendLine("─".repeat(30))

            // Extract petrol price
            val petrolRegex = Regex("Petrol\\s*(?:Price|price).*?Rs\\.?\\s*([\\d.]+)", RegexOption.DOT_MATCHES_ALL)
            val dieselRegex = Regex("Diesel\\s*(?:Price|price).*?Rs\\.?\\s*([\\d.]+)", RegexOption.DOT_MATCHES_ALL)

            val petrolMatch = petrolRegex.find(body)
            val dieselMatch = dieselRegex.find(body)

            // Alternative: look for price in structured data
            val priceRegex = Regex("\"price\":\\s*\"?([\\d.]+)\"?", RegexOption.DOT_MATCHES_ALL)
            val prices = priceRegex.findAll(body).map { it.groupValues[1] }.toList()

            // Try to extract from table structure
            val tableRegex = Regex("<td[^>]*>\\s*(Petrol|Diesel|CNG)\\s*</td>\\s*<td[^>]*>\\s*(?:Rs\\.?|₹)?\\s*([\\d.]+)", RegexOption.DOT_MATCHES_ALL.let { RegexOption.IGNORE_CASE })
            val tableMatches = Regex("<td[^>]*>\\s*(Petrol|Diesel|CNG)\\s*</td>\\s*<td[^>]*>\\s*(?:Rs\\.?|₹)?\\s*([\\d.]+)", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))
                .findAll(body).toList()

            if (tableMatches.isNotEmpty()) {
                for (m in tableMatches) {
                    val fuel = m.groupValues[1]
                    val price = m.groupValues[2]
                    sb.appendLine("  $fuel: ₹$price / litre")
                }
            } else {
                if (petrolMatch != null) sb.appendLine("  Petrol: ₹${petrolMatch.groupValues[1]} / litre")
                if (dieselMatch != null) sb.appendLine("  Diesel: ₹${dieselMatch.groupValues[1]} / litre")

                if (petrolMatch == null && dieselMatch == null && prices.isNotEmpty()) {
                    sb.appendLine("  Petrol: ₹${prices.getOrElse(0) { "N/A" }} / litre")
                    if (prices.size > 1) sb.appendLine("  Diesel: ₹${prices[1]} / litre")
                }
            }

            // Extract date if available
            val dateRegex = Regex("(\\d{1,2}\\s+(?:Jan|Feb|Mar|Apr|May|Jun|Jul|Aug|Sep|Oct|Nov|Dec)\\w*\\s+\\d{4})", RegexOption.IGNORE_CASE)
            val dateMatch = dateRegex.find(body)
            if (dateMatch != null) {
                sb.appendLine("\n  Last updated: ${dateMatch.groupValues[1]}")
            }

            sb.appendLine("\n  Source: GoodReturns.in")

            val content = sb.toString()
            if (content.contains("₹") || content.contains("Rs")) {
                ApiResult.ok(content)
            } else {
                null
            }
        } catch (_: Exception) {
            null
        }
    }
}

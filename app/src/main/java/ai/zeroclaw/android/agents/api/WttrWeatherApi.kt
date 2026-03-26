package ai.zeroclaw.android.agents.api

/**
 * wttr.in — Free weather API. No key required. Unlimited requests.
 * Docs: https://wttr.in/:help
 */
class WttrWeatherApi : ApiDataSource {

    override val sourceId = "wttr_weather"
    override val displayName = "wttr.in (Free, Unlimited)"
    override val rateLimit = ApiRateLimit.unlimited("Unlimited")

    override suspend fun fetch(params: Map<String, String>): ApiResult {
        return try {
            val city = params["query"] ?: "London"
            val encoded = java.net.URLEncoder.encode(city, "UTF-8")

            // JSON format for structured data
            val url = "https://wttr.in/$encoded?format=j1"
            val (code, body) = httpGet(url)
            if (code != 200) return ApiResult.fail("wttr.in HTTP $code")

            val json = org.json.JSONObject(body)
            val current = json.getJSONArray("current_condition").getJSONObject(0)
            val weather = json.getJSONArray("weather")

            val sb = StringBuilder()
            sb.appendLine("Weather Report for $city (via wttr.in)")
            sb.appendLine("─".repeat(30))

            // Current conditions
            val tempC = current.optString("temp_C", "N/A")
            val tempF = current.optString("temp_F", "N/A")
            val feelsLikeC = current.optString("FeelsLikeC", "N/A")
            val humidity = current.optString("humidity", "N/A")
            val windSpeedKmph = current.optString("windspeedKmph", "N/A")
            val windDir = current.optString("winddir16Point", "")
            val pressure = current.optString("pressure", "N/A")
            val visibility = current.optString("visibility", "N/A")
            val uvIndex = current.optString("uvIndex", "N/A")

            val descArr = current.optJSONArray("weatherDesc")
            val desc = descArr?.optJSONObject(0)?.optString("value", "N/A") ?: "N/A"

            sb.appendLine("\nCurrent Conditions:")
            sb.appendLine("  $desc")
            sb.appendLine("  Temperature: ${tempC}C / ${tempF}F (feels like ${feelsLikeC}C)")
            sb.appendLine("  Humidity: $humidity%")
            sb.appendLine("  Wind: $windSpeedKmph km/h $windDir")
            sb.appendLine("  Pressure: $pressure hPa")
            sb.appendLine("  Visibility: $visibility km")
            sb.appendLine("  UV Index: $uvIndex")

            // 3-day forecast
            sb.appendLine("\n3-Day Forecast:")
            for (i in 0 until minOf(weather.length(), 3)) {
                val day = weather.getJSONObject(i)
                val date = day.optString("date", "")
                val maxC = day.optString("maxtempC", "")
                val minC = day.optString("mintempC", "")
                val avgC = day.optString("avgtempC", "")
                val sunHour = day.optString("sunHour", "")
                val totalSnow = day.optString("totalSnow_cm", "0")

                val hourly = day.optJSONArray("hourly")
                val midday = hourly?.optJSONObject(4) // ~12:00
                val dayDesc = midday?.optJSONArray("weatherDesc")
                    ?.optJSONObject(0)?.optString("value", "") ?: ""
                val chanceRain = midday?.optString("chanceofrain", "0") ?: "0"

                sb.appendLine("  $date: ${minC}C–${maxC}C | $dayDesc | Rain: $chanceRain%")
            }

            ApiResult.ok(sb.toString(), body)
        } catch (e: Exception) {
            ApiResult.fail("wttr.in error: ${e.message}")
        }
    }
}

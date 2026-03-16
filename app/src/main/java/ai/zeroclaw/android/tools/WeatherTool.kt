package ai.zeroclaw.android.tools

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * WeatherTool — current weather & forecasts via wttr.in (free, no API key).
 *
 * Inspired by OpenClaw's weather skill.
 * Actions: current (default), forecast (3-day), brief (one-liner).
 */
class WeatherTool : Tool {

    override val name = "weather"

    override val description = "Get current weather and forecasts for any location. " +
            "Actions: 'current' (detailed now), 'forecast' (3-day), 'brief' (one-liner). " +
            "No API key needed — uses wttr.in."

    override val parameters = listOf(
        ToolParam("location", "string", "City name, zip code, or coordinates (e.g. 'London', '10001', '48.8566,2.3522')"),
        ToolParam("action", "string", "One of: current, forecast, brief. Default: current.", required = false)
    )

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    override suspend fun execute(args: Map<String, String>): ToolResult {
        val location = args["location"]?.trim()
            ?: return ToolResult(false, "", "Missing 'location' parameter")

        if (location.isBlank()) {
            return ToolResult(false, "", "Empty location")
        }

        val action = args["action"]?.trim()?.lowercase() ?: "current"

        return withContext(Dispatchers.IO) {
            try {
                when (action) {
                    "brief" -> fetchBrief(location)
                    "forecast" -> fetchForecast(location)
                    else -> fetchCurrent(location)
                }
            } catch (e: Exception) {
                ToolResult(false, "", "Weather fetch failed: ${e.message}")
            }
        }
    }

    /** One-liner weather string */
    private fun fetchBrief(location: String): ToolResult {
        val encoded = java.net.URLEncoder.encode(location, "UTF-8")
        val request = Request.Builder()
            .url("https://wttr.in/$encoded?format=3")
            .header("User-Agent", "ZeroClaw-Android/1.0")
            .get()
            .build()

        val response = client.newCall(request).execute()
        val body = response.body?.string()?.trim()
            ?: return ToolResult(false, "", "Empty response from wttr.in")

        if (response.code != 200 || body.contains("Unknown location")) {
            return ToolResult(false, "", "Location not found: $location")
        }

        return ToolResult(true, body)
    }

    /** Detailed current weather via JSON API */
    private fun fetchCurrent(location: String): ToolResult {
        val json = fetchJson(location) ?: return ToolResult(false, "", "Failed to fetch weather data")

        val current = json.optJSONObject("current_condition")
            ?.let { it } ?: json.optJSONArray("current_condition")?.optJSONObject(0)
            ?: return ToolResult(false, "", "No current weather data available")

        val nearest = json.optJSONArray("nearest_area")?.optJSONObject(0)
        val areaName = nearest?.optJSONArray("areaName")?.optJSONObject(0)?.optString("value", location) ?: location
        val country = nearest?.optJSONArray("country")?.optJSONObject(0)?.optString("value", "") ?: ""
        val region = nearest?.optJSONArray("region")?.optJSONObject(0)?.optString("value", "") ?: ""

        val tempC = current.optString("temp_C", "?")
        val tempF = current.optString("temp_F", "?")
        val feelsLikeC = current.optString("FeelsLikeC", "?")
        val feelsLikeF = current.optString("FeelsLikeF", "?")
        val humidity = current.optString("humidity", "?")
        val windSpeedKmph = current.optString("windspeedKmph", "?")
        val windDir = current.optString("winddir16Point", "?")
        val pressure = current.optString("pressure", "?")
        val visibility = current.optString("visibility", "?")
        val uvIndex = current.optString("uvIndex", "?")
        val cloudCover = current.optString("cloudcover", "?")
        val precip = current.optString("precipMM", "0")
        val desc = current.optJSONArray("weatherDesc")?.optJSONObject(0)?.optString("value", "Unknown") ?: "Unknown"

        val locationStr = buildString {
            append(areaName)
            if (region.isNotBlank() && region != areaName) append(", $region")
            if (country.isNotBlank()) append(", $country")
        }

        val sb = StringBuilder("Weather for $locationStr\n")
        sb.appendLine("═══════════════════════════")
        sb.appendLine()
        sb.appendLine("☁️ Condition: $desc")
        sb.appendLine("🌡️ Temperature: ${tempC}°C / ${tempF}°F")
        sb.appendLine("🤔 Feels Like: ${feelsLikeC}°C / ${feelsLikeF}°F")
        sb.appendLine("💧 Humidity: $humidity%")
        sb.appendLine("💨 Wind: $windSpeedKmph km/h $windDir")
        sb.appendLine("📊 Pressure: $pressure hPa")
        sb.appendLine("👁️ Visibility: $visibility km")
        sb.appendLine("☀️ UV Index: $uvIndex")
        sb.appendLine("☁️ Cloud Cover: $cloudCover%")
        if (precip != "0" && precip != "0.0") {
            sb.appendLine("🌧️ Precipitation: ${precip}mm")
        }

        return ToolResult(true, sb.toString())
    }

    /** 3-day forecast via JSON API */
    private fun fetchForecast(location: String): ToolResult {
        val json = fetchJson(location) ?: return ToolResult(false, "", "Failed to fetch weather data")

        val nearest = json.optJSONArray("nearest_area")?.optJSONObject(0)
        val areaName = nearest?.optJSONArray("areaName")?.optJSONObject(0)?.optString("value", location) ?: location

        val weather = json.optJSONArray("weather")
            ?: return ToolResult(false, "", "No forecast data available")

        val sb = StringBuilder("3-Day Forecast for $areaName\n")
        sb.appendLine("═══════════════════════════════")

        for (i in 0 until minOf(weather.length(), 3)) {
            val day = weather.getJSONObject(i)
            val date = day.optString("date", "?")
            val maxC = day.optString("maxtempC", "?")
            val minC = day.optString("mintempC", "?")
            val maxF = day.optString("maxtempF", "?")
            val minF = day.optString("mintempF", "?")
            val sunHour = day.optString("sunHour", "?")
            val totalSnow = day.optString("totalSnow_cm", "0")
            val uvIndex = day.optString("uvIndex", "?")

            val hourly = day.optJSONArray("hourly")
            val midday = hourly?.optJSONObject(minOf(4, (hourly.length() - 1).coerceAtLeast(0)))
            val desc = midday?.optJSONArray("weatherDesc")?.optJSONObject(0)?.optString("value", "?") ?: "?"
            val chanceOfRain = midday?.optString("chanceofrain", "?") ?: "?"
            val humidity = midday?.optString("humidity", "?") ?: "?"
            val windSpeed = midday?.optString("windspeedKmph", "?") ?: "?"

            sb.appendLine()
            sb.appendLine("📅 $date")
            sb.appendLine("   $desc")
            sb.appendLine("   🌡️ ${minC}°C – ${maxC}°C (${minF}°F – ${maxF}°F)")
            sb.appendLine("   🌧️ Rain chance: $chanceOfRain%  💧 Humidity: $humidity%")
            sb.appendLine("   💨 Wind: $windSpeed km/h  ☀️ UV: $uvIndex  🌤️ Sun: ${sunHour}h")
            if (totalSnow != "0" && totalSnow != "0.0") {
                sb.appendLine("   ❄️ Snow: ${totalSnow}cm")
            }
        }

        return ToolResult(true, sb.toString().take(4000))
    }

    /** Fetch JSON from wttr.in */
    private fun fetchJson(location: String): JSONObject? {
        val encoded = java.net.URLEncoder.encode(location, "UTF-8")
        val request = Request.Builder()
            .url("https://wttr.in/$encoded?format=j1")
            .header("User-Agent", "ZeroClaw-Android/1.0")
            .get()
            .build()

        val response = client.newCall(request).execute()
        val body = response.body?.string() ?: return null

        if (response.code != 200) return null

        return try {
            JSONObject(body)
        } catch (_: Exception) {
            null
        }
    }
}

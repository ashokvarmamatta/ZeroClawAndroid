package ai.zeroclaw.android.agents.api

import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * USGS Earthquake API — Free, unlimited, no key required.
 * Docs: https://earthquake.usgs.gov/fdsnws/event/1/
 * Returns GeoJSON with earthquake data.
 */
class UsgsEarthquakeApi : ApiDataSource {

    override val sourceId = "usgs_earthquake"
    override val displayName = "USGS Earthquake (Free, Unlimited)"
    override val rateLimit = ApiRateLimit.unlimited("Unlimited (public government API)")

    override suspend fun fetch(params: Map<String, String>): ApiResult {
        return try {
            val feed = params["feed"] ?: "significant_week"
            val url = "https://earthquake.usgs.gov/earthquakes/feed/v1.0/summary/$feed.geojson"

            val (code, body) = httpGet(url)
            if (code != 200) return ApiResult.fail("USGS HTTP $code")

            val json = JSONObject(body)
            val features = json.getJSONArray("features")
            val metadata = json.getJSONObject("metadata")

            val sb = StringBuilder()
            sb.appendLine("Earthquake Report (via USGS)")
            sb.appendLine("Feed: ${metadata.optString("title", feed)}")
            sb.appendLine("Total events: ${metadata.optInt("count", 0)}")
            sb.appendLine("─".repeat(30))

            val dtFmt = SimpleDateFormat("MMM d, yyyy HH:mm z", Locale.ENGLISH)
            dtFmt.timeZone = TimeZone.getDefault()

            val count = minOf(features.length(), 15)
            for (i in 0 until count) {
                val feature = features.getJSONObject(i)
                val props = feature.getJSONObject("properties")
                val geom = feature.getJSONObject("geometry")
                val coords = geom.getJSONArray("coordinates")

                val mag = props.optDouble("mag", 0.0)
                val place = props.optString("place", "Unknown")
                val time = props.optLong("time", 0L)
                val depth = coords.optDouble(2, 0.0)
                val tsunami = props.optInt("tsunami", 0)
                val alert = props.optString("alert", "")

                sb.appendLine("\n${i + 1}. Magnitude ${String.format("%.1f", mag)} — $place")
                if (time > 0) sb.appendLine("   Time: ${dtFmt.format(Date(time))}")
                sb.appendLine("   Depth: ${String.format("%.1f", depth)} km")
                if (tsunami > 0) sb.appendLine("   Tsunami warning issued")
                if (alert.isNotBlank() && alert != "null") sb.appendLine("   Alert level: $alert")
            }

            if (features.length() == 0) {
                sb.appendLine("\nNo significant earthquakes in this period.")
            }

            ApiResult.ok(sb.toString(), body)
        } catch (e: Exception) {
            ApiResult.fail("USGS error: ${e.message}")
        }
    }
}

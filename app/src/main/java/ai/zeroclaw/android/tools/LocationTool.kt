package ai.zeroclaw.android.tools

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Geocoder
import android.location.Location
import android.location.LocationManager
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * LocationTool — GPS location, geocoding, and nearby places.
 *
 * Inspired by OpenClaw's location skill.
 * Actions: current (GPS), geocode (address→coords), reverse (coords→address), nearby (places via Nominatim).
 */
class LocationTool(private val context: Context) : Tool {

    override val name = "location"

    override val description = "Get device GPS location, geocode addresses, or find nearby places. " +
            "Actions: 'current' (device GPS), 'geocode' (address to coordinates), " +
            "'reverse' (coordinates to address), 'nearby' (search nearby places). " +
            "Requires location permission for GPS; geocoding works without permission."

    override val parameters = listOf(
        ToolParam("action", "string", "One of: current, geocode, reverse, nearby. Default: current.", required = false),
        ToolParam("address", "string", "Address to geocode (for 'geocode')", required = false),
        ToolParam("latitude", "string", "Latitude (for 'reverse' or 'nearby')", required = false),
        ToolParam("longitude", "string", "Longitude (for 'reverse' or 'nearby')", required = false),
        ToolParam("query", "string", "Place type to search (for 'nearby', e.g. 'restaurant', 'hospital')", required = false)
    )

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    override suspend fun execute(args: Map<String, String>): ToolResult {
        val action = args["action"]?.trim()?.lowercase() ?: "current"

        return withContext(Dispatchers.IO) {
            try {
                when (action) {
                    "current" -> getCurrentLocation()
                    "geocode" -> {
                        val address = args["address"]?.trim()
                            ?: return@withContext ToolResult(false, "", "Missing 'address' parameter for geocode")
                        geocodeAddress(address)
                    }
                    "reverse" -> {
                        val lat = args["latitude"]?.trim()?.toDoubleOrNull()
                            ?: return@withContext ToolResult(false, "", "Missing or invalid 'latitude'")
                        val lon = args["longitude"]?.trim()?.toDoubleOrNull()
                            ?: return@withContext ToolResult(false, "", "Missing or invalid 'longitude'")
                        reverseGeocode(lat, lon)
                    }
                    "nearby" -> {
                        val query = args["query"]?.trim()
                            ?: return@withContext ToolResult(false, "", "Missing 'query' parameter for nearby search")
                        val lat = args["latitude"]?.trim()?.toDoubleOrNull()
                        val lon = args["longitude"]?.trim()?.toDoubleOrNull()
                        nearbyPlaces(query, lat, lon)
                    }
                    else -> ToolResult(false, "", "Unknown action: $action. Use: current, geocode, reverse, nearby.")
                }
            } catch (e: SecurityException) {
                ToolResult(false, "", "Location permission denied: ${e.message}")
            } catch (e: Exception) {
                ToolResult(false, "", "Location error: ${e.message}")
            }
        }
    }

    private fun hasLocationPermission(): Boolean {
        return ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
                PackageManager.PERMISSION_GRANTED ||
                ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) ==
                PackageManager.PERMISSION_GRANTED
    }

    @Suppress("MissingPermission")
    private fun getCurrentLocation(): ToolResult {
        if (!hasLocationPermission()) {
            return ToolResult(false, "", "Location permission not granted. " +
                    "Please grant ACCESS_FINE_LOCATION or ACCESS_COARSE_LOCATION in device settings.")
        }

        val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager

        // Try to get last known location from any provider
        var bestLocation: Location? = null
        val providers = listOf(
            LocationManager.GPS_PROVIDER,
            LocationManager.NETWORK_PROVIDER,
            LocationManager.PASSIVE_PROVIDER
        )

        for (provider in providers) {
            try {
                val loc = locationManager.getLastKnownLocation(provider)
                if (loc != null) {
                    if (bestLocation == null || loc.time > bestLocation.time) {
                        bestLocation = loc
                    }
                }
            } catch (_: Exception) { /* provider not available */ }
        }

        if (bestLocation == null) {
            return ToolResult(false, "", "Could not get current location. " +
                    "Make sure GPS or network location is enabled on the device.")
        }

        val lat = bestLocation.latitude
        val lon = bestLocation.longitude
        val accuracy = bestLocation.accuracy
        val altitude = if (bestLocation.hasAltitude()) bestLocation.altitude else null
        val speed = if (bestLocation.hasSpeed()) bestLocation.speed else null
        val ageMs = System.currentTimeMillis() - bestLocation.time
        val ageStr = when {
            ageMs < 60_000 -> "${ageMs / 1000}s ago"
            ageMs < 3_600_000 -> "${ageMs / 60_000}min ago"
            else -> "${ageMs / 3_600_000}h ago"
        }

        val sb = StringBuilder("Current Location\n${"═".repeat(40)}\n\n")
        sb.appendLine("📍 Coordinates: ${"%.6f".format(lat)}, ${"%.6f".format(lon)}")
        sb.appendLine("🎯 Accuracy: ${"%.0f".format(accuracy)}m")
        if (altitude != null) sb.appendLine("⛰️ Altitude: ${"%.1f".format(altitude)}m")
        if (speed != null && speed > 0) sb.appendLine("🏃 Speed: ${"%.1f".format(speed * 3.6)} km/h")
        sb.appendLine("🕐 Fix age: $ageStr")
        sb.appendLine("📡 Provider: ${bestLocation.provider}")

        // Try reverse geocoding
        try {
            @Suppress("DEPRECATION")
            val geocoder = Geocoder(context, Locale.getDefault())
            val addresses = geocoder.getFromLocation(lat, lon, 1)
            if (!addresses.isNullOrEmpty()) {
                val addr = addresses[0]
                val parts = mutableListOf<String>()
                if (!addr.thoroughfare.isNullOrBlank()) parts.add(addr.thoroughfare)
                if (!addr.subLocality.isNullOrBlank()) parts.add(addr.subLocality)
                if (!addr.locality.isNullOrBlank()) parts.add(addr.locality)
                if (!addr.adminArea.isNullOrBlank()) parts.add(addr.adminArea)
                if (!addr.countryName.isNullOrBlank()) parts.add(addr.countryName)
                if (!addr.postalCode.isNullOrBlank()) parts.add(addr.postalCode)
                if (parts.isNotEmpty()) {
                    sb.appendLine("\n🏠 Address: ${parts.joinToString(", ")}")
                }
            }
        } catch (_: Exception) { /* geocoder not available */ }

        return ToolResult(true, sb.toString())
    }

    private fun geocodeAddress(address: String): ToolResult {
        if (address.isBlank()) return ToolResult(false, "", "Address cannot be empty")

        // Try Android Geocoder first
        try {
            @Suppress("DEPRECATION")
            val geocoder = Geocoder(context, Locale.getDefault())
            val results = geocoder.getFromLocationName(address, 3)
            if (!results.isNullOrEmpty()) {
                val sb = StringBuilder("Geocode Results for \"$address\"\n${"═".repeat(40)}\n\n")
                results.forEachIndexed { i, addr ->
                    sb.appendLine("${i + 1}. ${"%.6f".format(addr.latitude)}, ${"%.6f".format(addr.longitude)}")
                    val parts = mutableListOf<String>()
                    for (j in 0..addr.maxAddressLineIndex) {
                        parts.add(addr.getAddressLine(j))
                    }
                    if (parts.isNotEmpty()) sb.appendLine("   ${parts.joinToString(", ")}")
                    sb.appendLine()
                }
                return ToolResult(true, sb.toString().trim())
            }
        } catch (_: Exception) { /* fall through to Nominatim */ }

        // Fallback to Nominatim API
        return geocodeViaNominatim(address)
    }

    private fun geocodeViaNominatim(address: String): ToolResult {
        val encoded = java.net.URLEncoder.encode(address, "UTF-8")
        val request = Request.Builder()
            .url("https://nominatim.openstreetmap.org/search?q=$encoded&format=json&limit=3&addressdetails=1")
            .header("User-Agent", "ZeroClaw-Android/1.0")
            .get()
            .build()

        val response = client.newCall(request).execute()
        val body = response.body?.string() ?: return ToolResult(false, "", "Nominatim returned empty response")

        if (response.code != 200) {
            return ToolResult(false, "", "Nominatim error: HTTP ${response.code}")
        }

        val results = org.json.JSONArray(body)
        if (results.length() == 0) {
            return ToolResult(true, "No results found for \"$address\".")
        }

        val sb = StringBuilder("Geocode Results for \"$address\"\n${"═".repeat(40)}\n\n")
        for (i in 0 until minOf(results.length(), 3)) {
            val item = results.getJSONObject(i)
            val lat = item.optString("lat", "?")
            val lon = item.optString("lon", "?")
            val displayName = item.optString("display_name", "?")
            val type = item.optString("type", "")
            sb.appendLine("${i + 1}. $lat, $lon")
            sb.appendLine("   $displayName")
            if (type.isNotBlank()) sb.appendLine("   Type: $type")
            sb.appendLine()
        }

        return ToolResult(true, sb.toString().trim())
    }

    private fun reverseGeocode(lat: Double, lon: Double): ToolResult {
        // Try Android Geocoder first
        try {
            @Suppress("DEPRECATION")
            val geocoder = Geocoder(context, Locale.getDefault())
            val results = geocoder.getFromLocation(lat, lon, 1)
            if (!results.isNullOrEmpty()) {
                val addr = results[0]
                val sb = StringBuilder("Reverse Geocode for ${"%.6f".format(lat)}, ${"%.6f".format(lon)}\n${"═".repeat(40)}\n\n")
                for (i in 0..addr.maxAddressLineIndex) {
                    sb.appendLine(addr.getAddressLine(i))
                }
                if (!addr.locality.isNullOrBlank()) sb.appendLine("City: ${addr.locality}")
                if (!addr.adminArea.isNullOrBlank()) sb.appendLine("State: ${addr.adminArea}")
                if (!addr.countryName.isNullOrBlank()) sb.appendLine("Country: ${addr.countryName}")
                if (!addr.postalCode.isNullOrBlank()) sb.appendLine("Postal: ${addr.postalCode}")
                return ToolResult(true, sb.toString().trim())
            }
        } catch (_: Exception) { /* fall through to Nominatim */ }

        // Fallback to Nominatim
        val request = Request.Builder()
            .url("https://nominatim.openstreetmap.org/reverse?lat=$lat&lon=$lon&format=json&addressdetails=1")
            .header("User-Agent", "ZeroClaw-Android/1.0")
            .get()
            .build()

        val response = client.newCall(request).execute()
        val body = response.body?.string() ?: return ToolResult(false, "", "Nominatim returned empty response")

        val json = JSONObject(body)
        val displayName = json.optString("display_name", "")
        if (displayName.isBlank()) {
            return ToolResult(true, "No address found for coordinates ${"%.6f".format(lat)}, ${"%.6f".format(lon)}.")
        }

        return ToolResult(true, "Reverse Geocode for ${"%.6f".format(lat)}, ${"%.6f".format(lon)}\n${"═".repeat(40)}\n\n$displayName")
    }

    private fun nearbyPlaces(query: String, lat: Double?, lon: Double?): ToolResult {
        if (query.isBlank()) return ToolResult(false, "", "Search query cannot be empty")

        // Use provided coordinates or get from GPS
        val useLat: Double
        val useLon: Double

        if (lat != null && lon != null) {
            useLat = lat
            useLon = lon
        } else {
            if (!hasLocationPermission()) {
                return ToolResult(false, "", "No coordinates provided and location permission not granted. " +
                        "Provide latitude/longitude or grant location permission.")
            }
            val loc = getLastLocation()
                ?: return ToolResult(false, "", "Could not get device location. Provide latitude and longitude manually.")
            useLat = loc.latitude
            useLon = loc.longitude
        }

        // Search via Nominatim
        val encoded = java.net.URLEncoder.encode(query, "UTF-8")
        val viewbox = "${"%.4f".format(useLon - 0.05)},${"%.4f".format(useLat + 0.05)},${"%.4f".format(useLon + 0.05)},${"%.4f".format(useLat - 0.05)}"
        val request = Request.Builder()
            .url("https://nominatim.openstreetmap.org/search?q=$encoded&format=json&limit=10&viewbox=$viewbox&bounded=0&addressdetails=1")
            .header("User-Agent", "ZeroClaw-Android/1.0")
            .get()
            .build()

        val response = client.newCall(request).execute()
        val body = response.body?.string() ?: return ToolResult(false, "", "Nominatim returned empty response")

        val results = org.json.JSONArray(body)
        if (results.length() == 0) {
            return ToolResult(true, "No \"$query\" found near ${"%.4f".format(useLat)}, ${"%.4f".format(useLon)}.")
        }

        val sb = StringBuilder("Nearby \"$query\" (near ${"%.4f".format(useLat)}, ${"%.4f".format(useLon)})\n${"═".repeat(40)}\n\n")
        for (i in 0 until minOf(results.length(), 10)) {
            val item = results.getJSONObject(i)
            val name = item.optString("display_name", "?")
            val pLat = item.optDouble("lat", 0.0)
            val pLon = item.optDouble("lon", 0.0)
            val type = item.optString("type", "")

            // Calculate approximate distance
            val dist = haversineDistance(useLat, useLon, pLat, pLon)
            val distStr = if (dist < 1.0) "${"%.0f".format(dist * 1000)}m" else "${"%.1f".format(dist)}km"

            sb.appendLine("${i + 1}. ${name.split(",").take(3).joinToString(",")}")
            sb.appendLine("   📍 $distStr away | ${"%.5f".format(pLat)}, ${"%.5f".format(pLon)}")
            if (type.isNotBlank()) sb.appendLine("   Type: $type")
            sb.appendLine()
        }

        return ToolResult(true, sb.toString().take(4000))
    }

    @Suppress("MissingPermission")
    private fun getLastLocation(): Location? {
        val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        val providers = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER, LocationManager.PASSIVE_PROVIDER)
        var best: Location? = null
        for (provider in providers) {
            try {
                val loc = locationManager.getLastKnownLocation(provider)
                if (loc != null && (best == null || loc.time > best.time)) best = loc
            } catch (_: Exception) {}
        }
        return best
    }

    private fun haversineDistance(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val R = 6371.0 // Earth radius in km
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = Math.sin(dLat / 2) * Math.sin(dLat / 2) +
                Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2)) *
                Math.sin(dLon / 2) * Math.sin(dLon / 2)
        val c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a))
        return R * c
    }
}

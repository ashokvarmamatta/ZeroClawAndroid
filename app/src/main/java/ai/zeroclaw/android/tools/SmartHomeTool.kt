package ai.zeroclaw.android.tools

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.MediaType.Companion.toMediaType
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * SmartHomeTool — control Philips Hue / generic smart home devices.
 *
 * Inspired by OpenClaw's smart home skill.
 * Actions: lights (list), on, off, brightness, color, status, scenes.
 * Requires Hue Bridge IP and API username.
 */
class SmartHomeTool : Tool {

    override val name = "smart_home"

    override val description = "Control smart home devices (Philips Hue lights). " +
            "Actions: 'lights' (list all lights), 'on' (turn on), 'off' (turn off), " +
            "'brightness' (set brightness 0-100%), 'color' (set color), " +
            "'status' (check device status), 'scenes' (list/activate scenes). " +
            "Requires Hue Bridge IP and API username."

    override val parameters = listOf(
        ToolParam("action", "string", "One of: lights, on, off, brightness, color, status, scenes. Default: lights.", required = false),
        ToolParam("bridge_ip", "string", "Hue Bridge IP address (e.g. '192.168.1.100')"),
        ToolParam("username", "string", "Hue API username/token"),
        ToolParam("light", "string", "Light ID or name (for on/off/brightness/color/status)", required = false),
        ToolParam("value", "string", "Brightness (0-100) or color name/hex (for brightness/color)", required = false),
        ToolParam("scene", "string", "Scene name or ID (for scenes activate)", required = false)
    )

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()

    override suspend fun execute(args: Map<String, String>): ToolResult {
        val bridgeIp = args["bridge_ip"]?.trim()
            ?: return ToolResult(false, "", "Missing 'bridge_ip' parameter. Provide your Hue Bridge IP.")
        val username = args["username"]?.trim()
            ?: return ToolResult(false, "", "Missing 'username' parameter. Provide your Hue API username.")

        val action = args["action"]?.trim()?.lowercase() ?: "lights"
        val baseUrl = "http://$bridgeIp/api/$username"

        return withContext(Dispatchers.IO) {
            try {
                when (action) {
                    "lights" -> listLights(baseUrl)
                    "on" -> setLightState(baseUrl, args["light"], true)
                    "off" -> setLightState(baseUrl, args["light"], false)
                    "brightness" -> setBrightness(baseUrl, args["light"], args["value"])
                    "color" -> setColor(baseUrl, args["light"], args["value"])
                    "status" -> lightStatus(baseUrl, args["light"])
                    "scenes" -> {
                        val scene = args["scene"]?.trim()
                        if (scene != null) activateScene(baseUrl, scene) else listScenes(baseUrl)
                    }
                    else -> ToolResult(false, "", "Unknown action: $action. Use: lights, on, off, brightness, color, status, scenes.")
                }
            } catch (e: Exception) {
                ToolResult(false, "", "Smart home error: ${e.message}")
            }
        }
    }

    private fun listLights(baseUrl: String): ToolResult {
        val request = Request.Builder().url("$baseUrl/lights").get().build()
        val response = client.newCall(request).execute()
        val body = response.body?.string() ?: return ToolResult(false, "", "Empty response from Hue Bridge")

        if (response.code != 200) return ToolResult(false, "", "Hue Bridge error: HTTP ${response.code}")

        val json = JSONObject(body)
        if (json.length() == 0) return ToolResult(true, "No lights found on this Hue Bridge.")

        val sb = StringBuilder("💡 Hue Lights\n${"═".repeat(40)}\n\n")
        for (key in json.keys()) {
            val light = json.getJSONObject(key)
            val name = light.optString("name", "Light $key")
            val state = light.optJSONObject("state")
            val isOn = state?.optBoolean("on", false) ?: false
            val bri = state?.optInt("bri", 0) ?: 0
            val briPct = (bri * 100.0 / 254).toInt()
            val reachable = state?.optBoolean("reachable", false) ?: false
            val type = light.optString("type", "?")

            sb.appendLine("${if (isOn) "🟢" else "⚫"} [$key] $name")
            sb.appendLine("   State: ${if (isOn) "ON" else "OFF"} | Brightness: $briPct% | Type: $type")
            if (!reachable) sb.appendLine("   ⚠️ Not reachable")
            sb.appendLine()
        }

        return ToolResult(true, sb.toString().take(4000))
    }

    private fun setLightState(baseUrl: String, light: String?, on: Boolean): ToolResult {
        val lightId = light?.trim() ?: return ToolResult(false, "", "Missing 'light' parameter (ID or name)")
        val id = resolveLightId(baseUrl, lightId) ?: return ToolResult(false, "", "Light not found: $lightId")

        val jsonBody = JSONObject().apply { put("on", on) }.toString()
        val request = Request.Builder()
            .url("$baseUrl/lights/$id/state")
            .put(jsonBody.toRequestBody("application/json".toMediaType()))
            .build()

        val response = client.newCall(request).execute()
        val body = response.body?.string() ?: ""

        if (body.contains("\"success\"")) {
            return ToolResult(true, "${if (on) "🟢 Turned ON" else "⚫ Turned OFF"}: Light $id ($lightId)")
        }
        return ToolResult(false, "", "Failed to set light state: $body")
    }

    private fun setBrightness(baseUrl: String, light: String?, value: String?): ToolResult {
        val lightId = light?.trim() ?: return ToolResult(false, "", "Missing 'light' parameter")
        val pct = value?.trim()?.replace("%", "")?.toIntOrNull()
            ?: return ToolResult(false, "", "Missing or invalid 'value' (0-100)")

        val id = resolveLightId(baseUrl, lightId) ?: return ToolResult(false, "", "Light not found: $lightId")
        val bri = (pct.coerceIn(0, 100) * 254 / 100).coerceIn(1, 254)

        val jsonBody = JSONObject().apply {
            put("on", true)
            put("bri", bri)
        }.toString()

        val request = Request.Builder()
            .url("$baseUrl/lights/$id/state")
            .put(jsonBody.toRequestBody("application/json".toMediaType()))
            .build()

        val response = client.newCall(request).execute()
        val body = response.body?.string() ?: ""

        if (body.contains("\"success\"")) {
            return ToolResult(true, "💡 Brightness set to $pct% for Light $id ($lightId)")
        }
        return ToolResult(false, "", "Failed to set brightness: $body")
    }

    private fun setColor(baseUrl: String, light: String?, value: String?): ToolResult {
        val lightId = light?.trim() ?: return ToolResult(false, "", "Missing 'light' parameter")
        val colorStr = value?.trim()?.lowercase() ?: return ToolResult(false, "", "Missing 'value' (color name or hex)")

        val id = resolveLightId(baseUrl, lightId) ?: return ToolResult(false, "", "Light not found: $lightId")

        // Map color to Hue values (hue: 0-65535, sat: 0-254)
        val (hue, sat) = colorToHueSat(colorStr)
            ?: return ToolResult(false, "", "Unknown color: $colorStr. Try: red, blue, green, yellow, purple, orange, pink, white, warm, cool.")

        val jsonBody = JSONObject().apply {
            put("on", true)
            put("hue", hue)
            put("sat", sat)
        }.toString()

        val request = Request.Builder()
            .url("$baseUrl/lights/$id/state")
            .put(jsonBody.toRequestBody("application/json".toMediaType()))
            .build()

        val response = client.newCall(request).execute()
        val body = response.body?.string() ?: ""

        if (body.contains("\"success\"")) {
            return ToolResult(true, "🎨 Color set to $colorStr for Light $id ($lightId)")
        }
        return ToolResult(false, "", "Failed to set color: $body")
    }

    private fun lightStatus(baseUrl: String, light: String?): ToolResult {
        val lightId = light?.trim() ?: return ToolResult(false, "", "Missing 'light' parameter")
        val id = resolveLightId(baseUrl, lightId) ?: return ToolResult(false, "", "Light not found: $lightId")

        val request = Request.Builder().url("$baseUrl/lights/$id").get().build()
        val response = client.newCall(request).execute()
        val body = response.body?.string() ?: return ToolResult(false, "", "Empty response")

        val json = JSONObject(body)
        val name = json.optString("name", "Light $id")
        val state = json.optJSONObject("state")
        val type = json.optString("type", "?")
        val model = json.optString("modelid", "?")
        val manufacturer = json.optString("manufacturername", "?")

        val sb = StringBuilder("Light Status: $name\n${"═".repeat(40)}\n\n")
        sb.appendLine("ID: $id")
        sb.appendLine("Type: $type")
        sb.appendLine("Model: $model ($manufacturer)")

        if (state != null) {
            sb.appendLine("Power: ${if (state.optBoolean("on")) "ON 🟢" else "OFF ⚫"}")
            sb.appendLine("Brightness: ${(state.optInt("bri", 0) * 100 / 254)}%")
            if (state.has("hue")) sb.appendLine("Hue: ${state.optInt("hue")}")
            if (state.has("sat")) sb.appendLine("Saturation: ${state.optInt("sat")}")
            if (state.has("ct")) sb.appendLine("Color Temp: ${state.optInt("ct")} mirek")
            sb.appendLine("Reachable: ${state.optBoolean("reachable", false)}")
            sb.appendLine("Color Mode: ${state.optString("colormode", "?")}")
        }

        return ToolResult(true, sb.toString())
    }

    private fun listScenes(baseUrl: String): ToolResult {
        val request = Request.Builder().url("$baseUrl/scenes").get().build()
        val response = client.newCall(request).execute()
        val body = response.body?.string() ?: return ToolResult(false, "", "Empty response")

        val json = JSONObject(body)
        if (json.length() == 0) return ToolResult(true, "No scenes found.")

        val sb = StringBuilder("🎭 Hue Scenes\n${"═".repeat(40)}\n\n")
        for (key in json.keys()) {
            val scene = json.getJSONObject(key)
            val name = scene.optString("name", "Scene $key")
            val type = scene.optString("type", "?")
            val lights = scene.optJSONArray("lights")?.let { arr ->
                (0 until arr.length()).map { arr.getString(it) }.joinToString(", ")
            } ?: "?"
            sb.appendLine("🎭 [$key] $name (type: $type, lights: $lights)")
        }

        return ToolResult(true, sb.toString().take(4000))
    }

    private fun activateScene(baseUrl: String, scene: String): ToolResult {
        val sceneId = resolveSceneId(baseUrl, scene) ?: scene

        val jsonBody = JSONObject().apply { put("scene", sceneId) }.toString()
        val request = Request.Builder()
            .url("$baseUrl/groups/0/action")
            .put(jsonBody.toRequestBody("application/json".toMediaType()))
            .build()

        val response = client.newCall(request).execute()
        val body = response.body?.string() ?: ""

        if (body.contains("\"success\"")) {
            return ToolResult(true, "🎭 Scene activated: $scene")
        }
        return ToolResult(false, "", "Failed to activate scene: $body")
    }

    private fun resolveLightId(baseUrl: String, lightRef: String): String? {
        // If numeric, use directly
        if (lightRef.all { it.isDigit() }) return lightRef

        // Otherwise search by name
        try {
            val request = Request.Builder().url("$baseUrl/lights").get().build()
            val response = client.newCall(request).execute()
            val body = response.body?.string() ?: return null
            val json = JSONObject(body)
            for (key in json.keys()) {
                val name = json.getJSONObject(key).optString("name", "")
                if (name.equals(lightRef, ignoreCase = true)) return key
            }
        } catch (_: Exception) {}
        return null
    }

    private fun resolveSceneId(baseUrl: String, sceneRef: String): String? {
        try {
            val request = Request.Builder().url("$baseUrl/scenes").get().build()
            val response = client.newCall(request).execute()
            val body = response.body?.string() ?: return null
            val json = JSONObject(body)
            for (key in json.keys()) {
                val name = json.getJSONObject(key).optString("name", "")
                if (name.equals(sceneRef, ignoreCase = true)) return key
            }
        } catch (_: Exception) {}
        return null
    }

    private fun colorToHueSat(color: String): Pair<Int, Int>? {
        return when (color) {
            "red" -> 0 to 254
            "orange" -> 5000 to 254
            "yellow" -> 10000 to 254
            "green" -> 21845 to 254
            "cyan", "teal" -> 32768 to 254
            "blue" -> 43690 to 254
            "purple", "violet" -> 49151 to 254
            "pink", "magenta" -> 56100 to 254
            "white" -> 0 to 0
            "warm", "warm white" -> 0 to 50
            "cool", "cool white", "daylight" -> 34000 to 50
            else -> null
        }
    }
}

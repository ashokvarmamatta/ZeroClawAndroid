package ai.zeroclaw.android.agents.api

import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL

/**
 * Shared HTTP GET helper for all API data sources.
 * Uses java.net.HttpURLConnection (available on Android without extra deps).
 */
fun httpGet(
    urlStr: String,
    headers: Map<String, String> = emptyMap(),
    timeoutMs: Int = 15_000
): Pair<Int, String> {
    val url = URL(urlStr)
    val conn = url.openConnection() as HttpURLConnection
    conn.requestMethod = "GET"
    conn.connectTimeout = timeoutMs
    conn.readTimeout = timeoutMs
    conn.setRequestProperty("User-Agent", "ZeroClaw-Android-Agent/1.0")
    conn.setRequestProperty("Accept", "application/json")
    for ((k, v) in headers) {
        if (v.isNotBlank()) conn.setRequestProperty(k, v)
    }

    return try {
        val code = conn.responseCode
        val stream = if (code in 200..299) conn.inputStream else conn.errorStream
        val reader = BufferedReader(InputStreamReader(stream ?: return code to ""))
        val body = reader.readText()
        reader.close()
        code to body
    } finally {
        conn.disconnect()
    }
}

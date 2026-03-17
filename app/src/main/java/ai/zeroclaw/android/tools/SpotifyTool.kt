package ai.zeroclaw.android.tools

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * SpotifyTool — Spotify playback control via Spotify Web API.
 *
 * Inspired by OpenClaw's Spotify skill.
 * Actions: search, play, pause, next, previous, now_playing, queue.
 * Requires a Spotify access token (set in tool args or settings).
 */
class SpotifyTool : Tool {

    override val name = "spotify"

    override val description = "Control Spotify music playback. " +
            "Actions: 'search' (find tracks/artists/albums), 'play' (resume or play a track), " +
            "'pause' (pause playback), 'next' (skip to next), 'previous' (go back), " +
            "'now_playing' (what's currently playing), 'queue' (add track to queue). " +
            "Requires a Spotify access token."

    override val parameters = listOf(
        ToolParam("action", "string", "One of: search, play, pause, next, previous, now_playing, queue. Default: now_playing.", required = false),
        ToolParam("query", "string", "Search query — song name, artist, or album (for 'search', 'play', 'queue')", required = false),
        ToolParam("token", "string", "Spotify access token (Bearer token). Required for all actions.", required = false),
        ToolParam("type", "string", "Search type: track, artist, album, playlist. Default: track.", required = false),
        ToolParam("uri", "string", "Spotify URI to play (e.g. spotify:track:xxx). Optional for 'play' and 'queue'.", required = false)
    )

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()

    private val baseUrl = "https://api.spotify.com/v1"

    override suspend fun execute(args: Map<String, String>): ToolResult {
        val token = args["token"]?.trim()
            ?: return ToolResult(false, "", "Missing 'token' parameter. Provide a Spotify access token.")

        val action = args["action"]?.trim()?.lowercase() ?: "now_playing"

        return withContext(Dispatchers.IO) {
            try {
                when (action) {
                    "search" -> {
                        val query = args["query"]?.trim()
                            ?: return@withContext ToolResult(false, "", "Missing 'query' for search")
                        val type = args["type"]?.trim()?.lowercase() ?: "track"
                        searchSpotify(token, query, type)
                    }
                    "play" -> {
                        val uri = args["uri"]?.trim()
                        val query = args["query"]?.trim()
                        playTrack(token, uri, query)
                    }
                    "pause" -> pausePlayback(token)
                    "next" -> skipNext(token)
                    "previous" -> skipPrevious(token)
                    "now_playing" -> nowPlaying(token)
                    "queue" -> {
                        val uri = args["uri"]?.trim()
                        val query = args["query"]?.trim()
                        addToQueue(token, uri, query)
                    }
                    else -> ToolResult(false, "", "Unknown action: $action. Use: search, play, pause, next, previous, now_playing, queue.")
                }
            } catch (e: Exception) {
                ToolResult(false, "", "Spotify error: ${e.message}")
            }
        }
    }

    private fun searchSpotify(token: String, query: String, type: String): ToolResult {
        val encoded = java.net.URLEncoder.encode(query, "UTF-8")
        val request = Request.Builder()
            .url("$baseUrl/search?q=$encoded&type=$type&limit=5")
            .header("Authorization", "Bearer $token")
            .get()
            .build()

        val response = client.newCall(request).execute()
        val body = response.body?.string() ?: return ToolResult(false, "", "Empty response from Spotify")

        if (response.code == 401) return ToolResult(false, "", "Invalid or expired Spotify token. Please refresh your token.")
        if (response.code != 200) return ToolResult(false, "", "Spotify API error: HTTP ${response.code}")

        val json = JSONObject(body)
        val sb = StringBuilder("Spotify Search: \"$query\"\n${"═".repeat(40)}\n\n")

        when (type) {
            "track" -> {
                val tracks = json.optJSONObject("tracks")?.optJSONArray("items")
                if (tracks == null || tracks.length() == 0) return ToolResult(true, "No tracks found for \"$query\".")
                for (i in 0 until tracks.length()) {
                    val track = tracks.getJSONObject(i)
                    val name = track.optString("name", "?")
                    val artist = track.optJSONArray("artists")?.optJSONObject(0)?.optString("name", "?") ?: "?"
                    val album = track.optJSONObject("album")?.optString("name", "?") ?: "?"
                    val duration = track.optLong("duration_ms", 0) / 1000
                    val uri = track.optString("uri", "")
                    sb.appendLine("${i + 1}. 🎵 $name")
                    sb.appendLine("   Artist: $artist | Album: $album")
                    sb.appendLine("   Duration: ${duration / 60}:${"%02d".format(duration % 60)} | URI: $uri")
                    sb.appendLine()
                }
            }
            "artist" -> {
                val artists = json.optJSONObject("artists")?.optJSONArray("items")
                if (artists == null || artists.length() == 0) return ToolResult(true, "No artists found for \"$query\".")
                for (i in 0 until artists.length()) {
                    val artist = artists.getJSONObject(i)
                    val name = artist.optString("name", "?")
                    val followers = artist.optJSONObject("followers")?.optInt("total", 0) ?: 0
                    val genres = artist.optJSONArray("genres")?.let { arr -> (0 until arr.length()).map { arr.getString(it) }.take(3).joinToString(", ") } ?: ""
                    val uri = artist.optString("uri", "")
                    sb.appendLine("${i + 1}. 🎤 $name")
                    sb.appendLine("   Followers: ${formatNumber(followers)} | Genres: ${genres.ifBlank { "N/A" }}")
                    sb.appendLine("   URI: $uri")
                    sb.appendLine()
                }
            }
            "album" -> {
                val albums = json.optJSONObject("albums")?.optJSONArray("items")
                if (albums == null || albums.length() == 0) return ToolResult(true, "No albums found for \"$query\".")
                for (i in 0 until albums.length()) {
                    val album = albums.getJSONObject(i)
                    val name = album.optString("name", "?")
                    val artist = album.optJSONArray("artists")?.optJSONObject(0)?.optString("name", "?") ?: "?"
                    val tracks = album.optInt("total_tracks", 0)
                    val date = album.optString("release_date", "?")
                    val uri = album.optString("uri", "")
                    sb.appendLine("${i + 1}. 💿 $name")
                    sb.appendLine("   Artist: $artist | Tracks: $tracks | Released: $date")
                    sb.appendLine("   URI: $uri")
                    sb.appendLine()
                }
            }
            else -> return ToolResult(false, "", "Unsupported search type: $type. Use: track, artist, album.")
        }

        return ToolResult(true, sb.toString().take(4000))
    }

    private fun nowPlaying(token: String): ToolResult {
        val request = Request.Builder()
            .url("$baseUrl/me/player/currently-playing")
            .header("Authorization", "Bearer $token")
            .get()
            .build()

        val response = client.newCall(request).execute()
        if (response.code == 204 || response.code == 202) {
            return ToolResult(true, "Nothing is currently playing on Spotify.")
        }
        if (response.code == 401) return ToolResult(false, "", "Invalid or expired Spotify token.")
        val body = response.body?.string() ?: return ToolResult(true, "Nothing is currently playing.")

        val json = JSONObject(body)
        val isPlaying = json.optBoolean("is_playing", false)
        val item = json.optJSONObject("item") ?: return ToolResult(true, "Nothing is currently playing.")

        val name = item.optString("name", "?")
        val artist = item.optJSONArray("artists")?.optJSONObject(0)?.optString("name", "?") ?: "?"
        val album = item.optJSONObject("album")?.optString("name", "?") ?: "?"
        val durationMs = item.optLong("duration_ms", 0)
        val progressMs = json.optLong("progress_ms", 0)
        val duration = durationMs / 1000
        val progress = progressMs / 1000

        val sb = StringBuilder("Now Playing on Spotify\n${"═".repeat(40)}\n\n")
        sb.appendLine("${if (isPlaying) "▶️" else "⏸️"} ${if (isPlaying) "Playing" else "Paused"}")
        sb.appendLine("🎵 $name")
        sb.appendLine("🎤 $artist")
        sb.appendLine("💿 $album")
        sb.appendLine("⏱️ ${progress / 60}:${"%02d".format(progress % 60)} / ${duration / 60}:${"%02d".format(duration % 60)}")

        return ToolResult(true, sb.toString())
    }

    private fun playTrack(token: String, uri: String?, query: String?): ToolResult {
        if (uri != null && uri.startsWith("spotify:")) {
            // Play specific track
            val jsonBody = if (uri.contains(":track:")) {
                """{"uris":["$uri"]}"""
            } else {
                """{"context_uri":"$uri"}"""
            }
            val request = Request.Builder()
                .url("$baseUrl/me/player/play")
                .header("Authorization", "Bearer $token")
                .header("Content-Type", "application/json")
                .put(jsonBody.toRequestBody("application/json".toMediaType()))
                .build()

            val response = client.newCall(request).execute()
            if (response.code == 401) return ToolResult(false, "", "Invalid or expired Spotify token.")
            if (response.code == 404) return ToolResult(false, "", "No active Spotify device found. Open Spotify on a device first.")
            if (response.code in 200..204) return ToolResult(true, "▶️ Playing: $uri")
            return ToolResult(false, "", "Spotify play error: HTTP ${response.code}")
        }

        if (query != null && query.isNotBlank()) {
            // Search and play first result
            val searchResult = searchSpotify(token, query, "track")
            if (!searchResult.success) return searchResult

            // Extract first URI from search results
            val uriMatch = Regex("URI: (spotify:track:\\S+)").find(searchResult.content)
            if (uriMatch != null) {
                return playTrack(token, uriMatch.groupValues[1], null)
            }
            return ToolResult(false, "", "No tracks found for \"$query\"")
        }

        // Just resume playback
        val request = Request.Builder()
            .url("$baseUrl/me/player/play")
            .header("Authorization", "Bearer $token")
            .put("{}".toRequestBody("application/json".toMediaType()))
            .build()

        val response = client.newCall(request).execute()
        if (response.code == 401) return ToolResult(false, "", "Invalid or expired Spotify token.")
        if (response.code == 404) return ToolResult(false, "", "No active Spotify device found.")
        if (response.code in 200..204) return ToolResult(true, "▶️ Playback resumed.")
        return ToolResult(false, "", "Spotify play error: HTTP ${response.code}")
    }

    private fun pausePlayback(token: String): ToolResult {
        val request = Request.Builder()
            .url("$baseUrl/me/player/pause")
            .header("Authorization", "Bearer $token")
            .put("{}".toRequestBody("application/json".toMediaType()))
            .build()

        val response = client.newCall(request).execute()
        if (response.code == 401) return ToolResult(false, "", "Invalid or expired Spotify token.")
        if (response.code in 200..204) return ToolResult(true, "⏸️ Playback paused.")
        return ToolResult(false, "", "Spotify pause error: HTTP ${response.code}")
    }

    private fun skipNext(token: String): ToolResult {
        val request = Request.Builder()
            .url("$baseUrl/me/player/next")
            .header("Authorization", "Bearer $token")
            .post("".toRequestBody(null))
            .build()

        val response = client.newCall(request).execute()
        if (response.code == 401) return ToolResult(false, "", "Invalid or expired Spotify token.")
        if (response.code in 200..204) return ToolResult(true, "⏭️ Skipped to next track.")
        return ToolResult(false, "", "Spotify skip error: HTTP ${response.code}")
    }

    private fun skipPrevious(token: String): ToolResult {
        val request = Request.Builder()
            .url("$baseUrl/me/player/previous")
            .header("Authorization", "Bearer $token")
            .post("".toRequestBody(null))
            .build()

        val response = client.newCall(request).execute()
        if (response.code == 401) return ToolResult(false, "", "Invalid or expired Spotify token.")
        if (response.code in 200..204) return ToolResult(true, "⏮️ Went to previous track.")
        return ToolResult(false, "", "Spotify previous error: HTTP ${response.code}")
    }

    private fun addToQueue(token: String, uri: String?, query: String?): ToolResult {
        val trackUri = if (uri != null && uri.startsWith("spotify:track:")) {
            uri
        } else if (query != null && query.isNotBlank()) {
            val searchResult = searchSpotify(token, query, "track")
            if (!searchResult.success) return searchResult
            val uriMatch = Regex("URI: (spotify:track:\\S+)").find(searchResult.content)
            uriMatch?.groupValues?.get(1) ?: return ToolResult(false, "", "No tracks found for \"$query\"")
        } else {
            return ToolResult(false, "", "Provide a 'uri' or 'query' to add to queue")
        }

        val encoded = java.net.URLEncoder.encode(trackUri, "UTF-8")
        val request = Request.Builder()
            .url("$baseUrl/me/player/queue?uri=$encoded")
            .header("Authorization", "Bearer $token")
            .post("".toRequestBody(null))
            .build()

        val response = client.newCall(request).execute()
        if (response.code == 401) return ToolResult(false, "", "Invalid or expired Spotify token.")
        if (response.code in 200..204) return ToolResult(true, "✅ Added to queue: $trackUri")
        return ToolResult(false, "", "Spotify queue error: HTTP ${response.code}")
    }

    private fun formatNumber(n: Int): String {
        return when {
            n >= 1_000_000 -> "${"%.1f".format(n / 1_000_000.0)}M"
            n >= 1_000 -> "${"%.1f".format(n / 1_000.0)}K"
            else -> n.toString()
        }
    }
}

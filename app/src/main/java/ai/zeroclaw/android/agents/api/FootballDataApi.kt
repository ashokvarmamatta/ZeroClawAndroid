package ai.zeroclaw.android.agents.api

import org.json.JSONObject

/**
 * Football-Data.org — Free football/soccer data.
 * Free tier: 10 requests/minute, no key required for basic endpoints.
 * Docs: https://www.football-data.org/documentation/api
 */
class FootballDataApi : ApiDataSource {

    override val sourceId = "football_data"
    override val displayName = "Football-Data.org (Free)"
    override val rateLimit = ApiRateLimit.perMinute(10)

    // Competition codes for popular leagues
    private val competitions = mapOf(
        "premier league" to "PL",
        "pl" to "PL",
        "epl" to "PL",
        "la liga" to "PD",
        "laliga" to "PD",
        "bundesliga" to "BL1",
        "serie a" to "SA",
        "ligue 1" to "FL1",
        "champions league" to "CL",
        "ucl" to "CL",
        "world cup" to "WC",
        "euro" to "EC"
    )

    override suspend fun fetch(params: Map<String, String>): ApiResult {
        return try {
            val query = params["query"]?.lowercase()?.trim() ?: "premier league"
            val competitionCode = competitions[query] ?: "PL"

            // Fetch matches for this competition
            val url = "https://api.football-data.org/v4/competitions/$competitionCode/matches?status=SCHEDULED,LIVE,IN_PLAY,FINISHED&limit=15"
            val (code, body) = httpGet(url, mapOf(
                "X-Auth-Token" to "" // works without token for basic data
            ))

            if (code == 429) return ApiResult.fail("Football-Data rate limit exceeded. Try again in 1 minute.")
            if (code != 200) {
                // Try standings instead
                return fetchStandings(competitionCode)
            }

            val json = JSONObject(body)
            val matches = json.optJSONArray("matches")

            val sb = StringBuilder()
            sb.appendLine("Football Matches — $query (via Football-Data.org)")
            sb.appendLine("─".repeat(35))

            if (matches != null) {
                for (i in 0 until minOf(matches.length(), 15)) {
                    val match = matches.getJSONObject(i)
                    val homeTeam = match.getJSONObject("homeTeam").optString("shortName",
                        match.getJSONObject("homeTeam").optString("name", "?"))
                    val awayTeam = match.getJSONObject("awayTeam").optString("shortName",
                        match.getJSONObject("awayTeam").optString("name", "?"))
                    val status = match.optString("status", "UNKNOWN")
                    val score = match.optJSONObject("score")
                    val ft = score?.optJSONObject("fullTime")
                    val homeGoals = ft?.optInt("home", -1) ?: -1
                    val awayGoals = ft?.optInt("away", -1) ?: -1
                    val utcDate = match.optString("utcDate", "").take(16).replace("T", " ")

                    val scoreStr = if (homeGoals >= 0) "$homeGoals - $awayGoals" else "vs"
                    val statusStr = when (status) {
                        "FINISHED" -> "FT"
                        "IN_PLAY" -> "LIVE"
                        "PAUSED" -> "HT"
                        "SCHEDULED", "TIMED" -> utcDate
                        else -> status
                    }

                    sb.appendLine("  $homeTeam $scoreStr $awayTeam [$statusStr]")
                }
            }

            if (matches == null || matches.length() == 0) {
                sb.appendLine("  No recent/upcoming matches found.")
            }

            ApiResult.ok(sb.toString(), body)
        } catch (e: Exception) {
            ApiResult.fail("Football-Data error: ${e.message}")
        }
    }

    private suspend fun fetchStandings(code: String): ApiResult {
        val url = "https://api.football-data.org/v4/competitions/$code/standings"
        val (httpCode, body) = httpGet(url)
        if (httpCode != 200) return ApiResult.fail("Football-Data HTTP $httpCode")

        val json = JSONObject(body)
        val standings = json.optJSONArray("standings")
        if (standings == null || standings.length() == 0)
            return ApiResult.fail("No standings available")

        val table = standings.getJSONObject(0).getJSONArray("table")
        val competition = json.optJSONObject("competition")?.optString("name", code) ?: code

        val sb = StringBuilder()
        sb.appendLine("$competition Standings (via Football-Data.org)")
        sb.appendLine("─".repeat(40))
        sb.appendLine("Pos | Team                | P  | W  | D  | L  | Pts")

        for (i in 0 until minOf(table.length(), 20)) {
            val row = table.getJSONObject(i)
            val pos = row.optInt("position", i + 1)
            val team = row.getJSONObject("team").optString("shortName",
                row.getJSONObject("team").optString("name", "?"))
            val played = row.optInt("playedGames", 0)
            val won = row.optInt("won", 0)
            val draw = row.optInt("draw", 0)
            val lost = row.optInt("lost", 0)
            val points = row.optInt("points", 0)

            sb.appendLine("${pos.toString().padStart(3)} | ${team.padEnd(19)} | ${played.toString().padStart(2)} | ${won.toString().padStart(2)} | ${draw.toString().padStart(2)} | ${lost.toString().padStart(2)} | ${points.toString().padStart(3)}")
        }

        return ApiResult.ok(sb.toString(), body)
    }
}

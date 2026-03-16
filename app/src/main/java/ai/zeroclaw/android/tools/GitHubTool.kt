package ai.zeroclaw.android.tools

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * GitHubTool — interact with GitHub repositories.
 *
 * Inspired by ZeroClaw upstream's github-issue and github-pr skills.
 * Lets the AI search repos, read READMEs, list issues/PRs,
 * and create issues — all from chat.
 *
 * Uses GitHub's public API (no auth for public repos, optional token for private).
 */
class GitHubTool : Tool {

    override val name = "github"

    override val description = "Interact with GitHub repositories. " +
            "Actions: 'search' (search repos), 'readme' (read a repo's README), " +
            "'issues' (list open issues), 'create_issue' (file a new issue — needs token in args)."

    override val parameters = listOf(
        ToolParam("action", "string", "One of: search, readme, issues, create_issue"),
        ToolParam("repo", "string", "Repository in 'owner/name' format (e.g. 'zeroclaw-labs/zeroclaw')", required = false),
        ToolParam("query", "string", "Search query (for search action)", required = false),
        ToolParam("title", "string", "Issue title (for create_issue action)", required = false),
        ToolParam("body", "string", "Issue body/description (for create_issue action)", required = false),
        ToolParam("token", "string", "GitHub personal access token (for create_issue or private repos)", required = false)
    )

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    override suspend fun execute(args: Map<String, String>): ToolResult {
        val action = args["action"]?.trim()?.lowercase()
            ?: return ToolResult(false, "", "Missing 'action' parameter.")

        return withContext(Dispatchers.IO) {
            when (action) {
                "search" -> searchRepos(args)
                "readme" -> readReadme(args)
                "issues" -> listIssues(args)
                "create_issue" -> createIssue(args)
                else -> ToolResult(false, "", "Unknown action '$action'. Use: search, readme, issues, create_issue.")
            }
        }
    }

    private fun searchRepos(args: Map<String, String>): ToolResult {
        val query = args["query"]?.trim()
            ?: return ToolResult(false, "", "Missing 'query' parameter for search.")

        return try {
            val url = "https://api.github.com/search/repositories?q=${java.net.URLEncoder.encode(query, "UTF-8")}&per_page=5"
            val json = apiGet(url, args["token"])
            val items = json.getJSONArray("items")

            val sb = StringBuilder("GitHub repos matching \"$query\" (${json.getInt("total_count")} total):\n\n")
            for (i in 0 until items.length()) {
                val repo = items.getJSONObject(i)
                sb.appendLine("${i + 1}. ${repo.getString("full_name")} ⭐ ${repo.getInt("stargazers_count")}")
                sb.appendLine("   ${repo.optString("description", "(no description)").take(120)}")
                sb.appendLine("   ${repo.getString("html_url")}")
                sb.appendLine()
            }
            ToolResult(true, sb.toString().take(MAX_RESULT))
        } catch (e: Exception) {
            ToolResult(false, "", "GitHub search failed: ${e.message}")
        }
    }

    private fun readReadme(args: Map<String, String>): ToolResult {
        val repo = args["repo"]?.trim()
            ?: return ToolResult(false, "", "Missing 'repo' parameter (e.g. 'owner/name').")

        return try {
            val url = "https://api.github.com/repos/$repo/readme"
            val request = Request.Builder().url(url)
                .header("Accept", "application/vnd.github.raw+json")
                .header("User-Agent", "ZeroClaw/1.0")
                .apply { args["token"]?.let { header("Authorization", "Bearer $it") } }
                .build()
            val response = client.newCall(request).execute()
            val body = response.body?.string()
                ?: return ToolResult(false, "", "Empty README response.")

            if (!response.isSuccessful) {
                return ToolResult(false, "", "GitHub API error ${response.code}: ${body.take(200)}")
            }

            ToolResult(true, "README for $repo:\n\n${body.take(MAX_RESULT)}")
        } catch (e: Exception) {
            ToolResult(false, "", "GitHub readme failed: ${e.message}")
        }
    }

    private fun listIssues(args: Map<String, String>): ToolResult {
        val repo = args["repo"]?.trim()
            ?: return ToolResult(false, "", "Missing 'repo' parameter (e.g. 'owner/name').")

        return try {
            val url = "https://api.github.com/repos/$repo/issues?state=open&per_page=10"
            val request = Request.Builder().url(url)
                .header("Accept", "application/vnd.github+json")
                .header("User-Agent", "ZeroClaw/1.0")
                .apply { args["token"]?.let { header("Authorization", "Bearer $it") } }
                .build()
            val response = client.newCall(request).execute()
            val body = response.body?.string() ?: return ToolResult(false, "", "Empty response.")

            val items = JSONArray(body)
            if (items.length() == 0) {
                return ToolResult(true, "No open issues in $repo.")
            }

            val sb = StringBuilder("Open issues in $repo (showing ${items.length()}):\n\n")
            for (i in 0 until items.length()) {
                val issue = items.getJSONObject(i)
                // Skip pull requests (GitHub API includes PRs in issues)
                if (issue.has("pull_request")) continue
                sb.appendLine("#${issue.getInt("number")} — ${issue.getString("title")}")
                val labels = issue.getJSONArray("labels")
                if (labels.length() > 0) {
                    val labelNames = (0 until labels.length()).map { labels.getJSONObject(it).getString("name") }
                    sb.appendLine("   Labels: ${labelNames.joinToString(", ")}")
                }
                sb.appendLine("   ${issue.getString("html_url")}")
                sb.appendLine()
            }
            ToolResult(true, sb.toString().take(MAX_RESULT))
        } catch (e: Exception) {
            ToolResult(false, "", "GitHub issues failed: ${e.message}")
        }
    }

    private fun createIssue(args: Map<String, String>): ToolResult {
        val repo = args["repo"]?.trim()
            ?: return ToolResult(false, "", "Missing 'repo' parameter.")
        val title = args["title"]?.trim()
            ?: return ToolResult(false, "", "Missing 'title' parameter.")
        val body = args["body"]?.trim() ?: ""
        val token = args["token"]?.trim()
            ?: return ToolResult(false, "", "Missing 'token' parameter — GitHub PAT required to create issues.")

        return try {
            val url = "https://api.github.com/repos/$repo/issues"
            val json = JSONObject().apply {
                put("title", title)
                put("body", body)
            }
            val request = Request.Builder().url(url)
                .header("Accept", "application/vnd.github+json")
                .header("Authorization", "Bearer $token")
                .header("User-Agent", "ZeroClaw/1.0")
                .post(json.toString().toRequestBody("application/json".toMediaType()))
                .build()
            val response = client.newCall(request).execute()
            val respBody = response.body?.string() ?: return ToolResult(false, "", "Empty response.")

            if (!response.isSuccessful) {
                return ToolResult(false, "", "Failed to create issue (${response.code}): ${respBody.take(200)}")
            }

            val issue = JSONObject(respBody)
            ToolResult(true, "Created issue #${issue.getInt("number")}: ${issue.getString("title")}\n${issue.getString("html_url")}")
        } catch (e: Exception) {
            ToolResult(false, "", "GitHub create issue failed: ${e.message}")
        }
    }

    private fun apiGet(url: String, token: String?): JSONObject {
        val request = Request.Builder().url(url)
            .header("Accept", "application/vnd.github+json")
            .header("User-Agent", "ZeroClaw/1.0")
            .apply { if (!token.isNullOrBlank()) header("Authorization", "Bearer $token") }
            .build()
        val response = client.newCall(request).execute()
        val body = response.body?.string() ?: throw Exception("Empty response")
        if (!response.isSuccessful) throw Exception("API error ${response.code}: ${body.take(200)}")
        return JSONObject(body)
    }

    companion object {
        private const val MAX_RESULT = 4000
    }
}

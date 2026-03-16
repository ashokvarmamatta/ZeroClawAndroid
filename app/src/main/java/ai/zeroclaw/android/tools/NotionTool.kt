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
 * NotionTool — interact with Notion workspaces via the Notion API.
 *
 * Actions:
 * - search: Search pages/databases in the workspace
 * - read_page: Read the content of a specific page
 * - create_page: Create a new page in a database
 * - append: Append content blocks to an existing page
 *
 * Requires a Notion Integration token (passed as 'token' arg or stored in memory).
 */
class NotionTool : Tool {

    override val name = "notion"

    override val description = "Interact with Notion workspaces — search pages, read content, create pages, and append blocks. " +
            "Requires a Notion integration token. " +
            "Actions: 'search' (find pages), 'read_page' (get page content), 'create_page' (new page in database), 'append' (add content to page)."

    override val parameters = listOf(
        ToolParam("action", "string", "One of: search, read_page, create_page, append"),
        ToolParam("token", "string", "Notion integration token (starts with 'ntn_' or 'secret_')"),
        ToolParam("query", "string", "Search query (for search action)", required = false),
        ToolParam("page_id", "string", "Page or block ID (for read_page and append actions)", required = false),
        ToolParam("database_id", "string", "Database ID (for create_page action)", required = false),
        ToolParam("title", "string", "Page title (for create_page action)", required = false),
        ToolParam("content", "string", "Text content to add (for create_page and append actions)", required = false)
    )

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    private val notionVersion = "2022-06-28"

    override suspend fun execute(args: Map<String, String>): ToolResult {
        val action = args["action"]?.trim()?.lowercase()
            ?: return ToolResult(false, "", "Missing 'action' parameter.")
        val token = args["token"]?.trim()
            ?: return ToolResult(false, "", "Missing 'token' parameter. Provide your Notion integration token.")

        return withContext(Dispatchers.IO) {
            when (action) {
                "search" -> search(token, args)
                "read_page" -> readPage(token, args)
                "create_page" -> createPage(token, args)
                "append" -> appendBlocks(token, args)
                else -> ToolResult(false, "", "Unknown action '$action'. Use: search, read_page, create_page, append.")
            }
        }
    }

    private fun search(token: String, args: Map<String, String>): ToolResult {
        val query = args["query"]?.trim() ?: ""

        return try {
            val body = JSONObject().apply {
                if (query.isNotBlank()) put("query", query)
                put("page_size", 10)
            }
            val json = apiPost("https://api.notion.com/v1/search", token, body)
            val results = json.getJSONArray("results")

            if (results.length() == 0) {
                return ToolResult(true, "No Notion pages found${if (query.isNotBlank()) " for '$query'" else ""}.")
            }

            val sb = StringBuilder("Notion search results${if (query.isNotBlank()) " for '$query'" else ""} (${results.length()}):\n\n")
            for (i in 0 until results.length()) {
                val item = results.getJSONObject(i)
                val type = item.getString("object") // "page" or "database"
                val id = item.getString("id")
                val title = extractTitle(item)
                val lastEdited = item.optString("last_edited_time", "")
                sb.appendLine("${i + 1}. [$type] $title")
                sb.appendLine("   ID: $id")
                if (lastEdited.isNotBlank()) sb.appendLine("   Last edited: $lastEdited")
                sb.appendLine()
            }
            ToolResult(true, sb.toString().take(MAX_RESULT))
        } catch (e: Exception) {
            ToolResult(false, "", "Notion search failed: ${e.message}")
        }
    }

    private fun readPage(token: String, args: Map<String, String>): ToolResult {
        val pageId = args["page_id"]?.trim()
            ?: return ToolResult(false, "", "Missing 'page_id' parameter.")

        return try {
            // Get page metadata
            val page = apiGet("https://api.notion.com/v1/pages/$pageId", token)
            val title = extractTitle(page)

            // Get page content (blocks)
            val blocks = apiGet("https://api.notion.com/v1/blocks/$pageId/children?page_size=50", token)
            val blockResults = blocks.getJSONArray("results")

            val sb = StringBuilder("📄 $title\n")
            sb.appendLine("═══════════════════════\n")

            for (i in 0 until blockResults.length()) {
                val block = blockResults.getJSONObject(i)
                val blockType = block.getString("type")
                val text = extractBlockText(block, blockType)
                if (text.isNotBlank()) {
                    sb.appendLine(text)
                }
            }

            ToolResult(true, sb.toString().take(MAX_RESULT))
        } catch (e: Exception) {
            ToolResult(false, "", "Notion read failed: ${e.message}")
        }
    }

    private fun createPage(token: String, args: Map<String, String>): ToolResult {
        val dbId = args["database_id"]?.trim()
            ?: return ToolResult(false, "", "Missing 'database_id' parameter.")
        val title = args["title"]?.trim()
            ?: return ToolResult(false, "", "Missing 'title' parameter.")
        val content = args["content"]?.trim() ?: ""

        return try {
            val body = JSONObject().apply {
                put("parent", JSONObject().put("database_id", dbId))
                put("properties", JSONObject().apply {
                    put("title", JSONObject().apply {
                        put("title", JSONArray().apply {
                            put(JSONObject().apply {
                                put("text", JSONObject().put("content", title))
                            })
                        })
                    })
                })
                if (content.isNotBlank()) {
                    put("children", JSONArray().apply {
                        put(JSONObject().apply {
                            put("object", "block")
                            put("type", "paragraph")
                            put("paragraph", JSONObject().apply {
                                put("rich_text", JSONArray().apply {
                                    put(JSONObject().apply {
                                        put("type", "text")
                                        put("text", JSONObject().put("content", content))
                                    })
                                })
                            })
                        })
                    })
                }
            }

            val result = apiPost("https://api.notion.com/v1/pages", token, body)
            val newId = result.getString("id")
            val url = result.optString("url", "")

            ToolResult(true, "Created Notion page: '$title'\nID: $newId${if (url.isNotBlank()) "\nURL: $url" else ""}")
        } catch (e: Exception) {
            ToolResult(false, "", "Notion create page failed: ${e.message}")
        }
    }

    private fun appendBlocks(token: String, args: Map<String, String>): ToolResult {
        val pageId = args["page_id"]?.trim()
            ?: return ToolResult(false, "", "Missing 'page_id' parameter.")
        val content = args["content"]?.trim()
            ?: return ToolResult(false, "", "Missing 'content' parameter.")

        return try {
            val body = JSONObject().apply {
                put("children", JSONArray().apply {
                    // Split content by newlines and create paragraph blocks
                    for (line in content.split("\n")) {
                        put(JSONObject().apply {
                            put("object", "block")
                            put("type", "paragraph")
                            put("paragraph", JSONObject().apply {
                                put("rich_text", JSONArray().apply {
                                    put(JSONObject().apply {
                                        put("type", "text")
                                        put("text", JSONObject().put("content", line))
                                    })
                                })
                            })
                        })
                    }
                })
            }

            apiPatch("https://api.notion.com/v1/blocks/$pageId/children", token, body)
            ToolResult(true, "Appended content to page $pageId.")
        } catch (e: Exception) {
            ToolResult(false, "", "Notion append failed: ${e.message}")
        }
    }

    // ── Helpers ─────────────────────────────────────────────────────────

    private fun extractTitle(item: JSONObject): String {
        return try {
            val props = item.optJSONObject("properties")
            if (props != null) {
                // Try common title property names
                for (key in props.keys()) {
                    val prop = props.getJSONObject(key)
                    if (prop.optString("type") == "title") {
                        val titleArr = prop.getJSONArray("title")
                        if (titleArr.length() > 0) {
                            return titleArr.getJSONObject(0).optString("plain_text", "(untitled)")
                        }
                    }
                }
            }
            // For databases
            val titleArr = item.optJSONArray("title")
            if (titleArr != null && titleArr.length() > 0) {
                return titleArr.getJSONObject(0).optString("plain_text", "(untitled)")
            }
            "(untitled)"
        } catch (_: Exception) {
            "(untitled)"
        }
    }

    private fun extractBlockText(block: JSONObject, type: String): String {
        return try {
            val blockData = block.optJSONObject(type) ?: return ""
            val richText = blockData.optJSONArray("rich_text") ?: return ""

            val sb = StringBuilder()
            for (i in 0 until richText.length()) {
                sb.append(richText.getJSONObject(i).optString("plain_text", ""))
            }

            val text = sb.toString()
            when (type) {
                "heading_1" -> "# $text"
                "heading_2" -> "## $text"
                "heading_3" -> "### $text"
                "bulleted_list_item" -> "• $text"
                "numbered_list_item" -> "  $text"
                "to_do" -> {
                    val checked = blockData.optBoolean("checked", false)
                    "${if (checked) "☑" else "☐"} $text"
                }
                "code" -> "```\n$text\n```"
                "quote" -> "> $text"
                "divider" -> "---"
                else -> text
            }
        } catch (_: Exception) {
            ""
        }
    }

    private fun apiGet(url: String, token: String): JSONObject {
        val request = Request.Builder().url(url)
            .header("Authorization", "Bearer $token")
            .header("Notion-Version", notionVersion)
            .header("Content-Type", "application/json")
            .build()
        val response = client.newCall(request).execute()
        val body = response.body?.string() ?: throw Exception("Empty response")
        if (!response.isSuccessful) throw Exception("API error ${response.code}: ${body.take(200)}")
        return JSONObject(body)
    }

    private fun apiPost(url: String, token: String, body: JSONObject): JSONObject {
        val request = Request.Builder().url(url)
            .header("Authorization", "Bearer $token")
            .header("Notion-Version", notionVersion)
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()
        val response = client.newCall(request).execute()
        val respBody = response.body?.string() ?: throw Exception("Empty response")
        if (!response.isSuccessful) throw Exception("API error ${response.code}: ${respBody.take(200)}")
        return JSONObject(respBody)
    }

    private fun apiPatch(url: String, token: String, body: JSONObject): JSONObject {
        val request = Request.Builder().url(url)
            .header("Authorization", "Bearer $token")
            .header("Notion-Version", notionVersion)
            .patch(body.toString().toRequestBody("application/json".toMediaType()))
            .build()
        val response = client.newCall(request).execute()
        val respBody = response.body?.string() ?: throw Exception("Empty response")
        if (!response.isSuccessful) throw Exception("API error ${response.code}: ${respBody.take(200)}")
        return JSONObject(respBody)
    }

    companion object {
        private const val MAX_RESULT = 4000
    }
}

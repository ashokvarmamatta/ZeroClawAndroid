package ai.zeroclaw.android.tools

import android.content.Context
import ai.zeroclaw.android.agents.AGENT_TEMPLATES
import ai.zeroclaw.android.agents.AgentConfig
import ai.zeroclaw.android.agents.AgentManager
import ai.zeroclaw.android.agents.AgentTemplate
import ai.zeroclaw.android.agents.WebScraperAgent
import ai.zeroclaw.android.agents.api.FreeApiRegistry
import ai.zeroclaw.android.data.AgentResultDatabase
import ai.zeroclaw.android.data.AgentResultEntity
import ai.zeroclaw.android.service.ZeroClawService
import ai.zeroclaw.android.tools.WebSearchTool
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * AgentTool — lets the AI manage and query all agents.
 *
 * The AI can list agents, check status, enable/disable, run, view results,
 * create, and delete agents. Works from any channel (Telegram, Discord, API, etc.)
 */
class AgentTool(private val context: Context) : Tool {

    override val name = "agents"

    override val description = "Manage autonomous background agents that fetch data from the web periodically. " +
        "Actions: 'list' (all agents with status), 'status <name|id>' (details + recent results), " +
        "'results <name|id>' (fetch history), 'enable <name|id>', 'disable <name|id>', " +
        "'run <name|id>' (trigger now), 'delete <name|id>', " +
        "'modify <name|id>' (change extraction prompt or settings — use when user wants to adjust what/how the agent fetches), " +
        "'create' (create new agent — IMPORTANT: URL is OPTIONAL. Just pass a topic like 'gold prices' or 'android developer jobs' " +
        "and the system automatically finds the best sources, creates the agent, runs it once, and shows a PREVIEW. " +
        "If user says 'looks good' keep it. If user wants changes, use 'modify' action. " +
        "Supports: news, crypto, gold, stocks, weather, earthquakes, movies, jobs, youtube, sports, forex, fuel, IPOs, flights, anime)."

    override val parameters = listOf(
        ToolParam("action", "string", "One of: list, status, results, enable, disable, run, delete, create, modify. Default: list."),
        ToolParam("target", "string", "Agent name or ID (for status/results/enable/disable/run/delete).", required = false),
        ToolParam("limit", "string", "Max results to return (for results action). Default: 10.", required = false),
        // create params — ALL optional. For create, just pass topic/concept and the system finds sources.
        ToolParam("agent_name", "string", "Topic or name for new agent. E.g. 'latest movies in india', 'gold prices', 'tech news'. The system auto-finds best URLs.", required = false),
        ToolParam("url", "string", "Optional URL. If omitted, system auto-discovers sources from the topic.", required = false),
        ToolParam("interval", "string", "Interval in minutes. Default: 60.", required = false),
        ToolParam("channel", "string", "Delivery channel: telegram, discord, slack, whatsapp, etc. Default: telegram.", required = false),
        ToolParam("extract_prompt", "string", "What to extract from the page. Default: auto-generated from topic.", required = false)
    )

    private val agentManager = AgentManager.getInstance(context)
    private val resultDao = AgentResultDatabase.getInstance(context).agentResultDao()
    private val dtFmt = SimpleDateFormat("MMM d HH:mm", Locale.ENGLISH)

    override suspend fun execute(args: Map<String, String>): ToolResult {
        val action = args["action"]?.trim()?.lowercase() ?: "list"
        val target = args["target"]?.trim() ?: ""

        return when (action) {
            "list" -> listAgents()
            "status" -> agentStatus(target)
            "results" -> agentResults(target, args["limit"]?.toIntOrNull() ?: 10)
            "enable" -> toggleAgent(target, true)
            "disable" -> toggleAgent(target, false)
            "run" -> runAgent(target)
            "delete" -> deleteAgent(target)
            "create" -> createAgent(args)
            "modify" -> modifyAgent(target, args)
            else -> listAgents()
        }
    }

    private fun listAgents(): ToolResult {
        val agents = agentManager.loadAll()
        if (agents.isEmpty()) {
            return ToolResult(true, "No agents configured. Create one with action='create'.")
        }

        val sb = StringBuilder("Agents (${agents.size} total)\n")
        sb.appendLine("═══════════════════════")
        for (a in agents) {
            val status = if (a.enabled) "✅ Active" else "⏸️ Inactive"
            val lastRun = if (a.lastRunAt > 0) dtFmt.format(Date(a.lastRunAt)) else "Never"
            sb.appendLine("\n${a.name}")
            sb.appendLine("  Status: $status | Interval: ${a.intervalMinutes}m")
            sb.appendLine("  URL: ${a.url.take(60)}")
            sb.appendLine("  Channel: ${a.channel} | Last: $lastRun")
            sb.appendLine("  Result: ${a.lastStatus.take(80)}")
            sb.appendLine("  ID: ${a.id.take(8)}…")
        }
        return ToolResult(true, sb.toString().take(4000))
    }

    private suspend fun agentStatus(target: String): ToolResult {
        val agent = findAgent(target) ?: return ToolResult(false, "Agent '$target' not found.", "not_found")

        val recentResults = resultDao.getByAgentId(agent.id, 5)
        val totalRuns = resultDao.countByAgent(agent.id)

        val sb = StringBuilder("Agent: ${agent.name}\n")
        sb.appendLine("═══════════════════════")
        sb.appendLine("Status: ${if (agent.enabled) "✅ Active" else "⏸️ Inactive"}")
        sb.appendLine("Type: ${agent.type}")
        sb.appendLine("URL: ${agent.url}")
        sb.appendLine("Interval: ${agent.intervalMinutes} minutes")
        sb.appendLine("Channel: ${agent.channel}")
        sb.appendLine("Extract: ${agent.extractPrompt.take(100).ifBlank { "(full content)" }}")
        sb.appendLine("Change-only: ${agent.onlyOnChange}")
        sb.appendLine("Fetch type: ${agent.safeFetchType}")
        sb.appendLine("Tracking: ${agent.safeTrackingMode}")
        sb.appendLine("Total runs: $totalRuns")
        sb.appendLine("Last run: ${if (agent.lastRunAt > 0) dtFmt.format(Date(agent.lastRunAt)) else "Never"}")
        sb.appendLine("Last status: ${agent.lastStatus}")
        sb.appendLine("ID: ${agent.id}")

        if (recentResults.isNotEmpty()) {
            sb.appendLine("\nRecent Results:")
            for (r in recentResults) {
                sb.appendLine("  [${r.status.uppercase()}] ${dtFmt.format(Date(r.timestamp))} — ${r.extractedContent.take(100).replace("\n", " ")}")
            }
        }

        return ToolResult(true, sb.toString().take(4000))
    }

    private suspend fun agentResults(target: String, limit: Int): ToolResult {
        val agent = findAgent(target) ?: return ToolResult(false, "Agent '$target' not found.", "not_found")

        val results = resultDao.getByAgentId(agent.id, limit.coerceIn(1, 50))
        val total = resultDao.countByAgent(agent.id)

        if (results.isEmpty()) {
            return ToolResult(true, "No results yet for agent '${agent.name}'.")
        }

        val sb = StringBuilder("Results for ${agent.name} (showing ${results.size} of $total)\n")
        sb.appendLine("═══════════════════════")
        for (r in results) {
            sb.appendLine("\n[${r.status.uppercase()}] ${dtFmt.format(Date(r.timestamp))}")
            if (r.usedApi) sb.appendLine("  Source: Free API")
            val delivered = try { org.json.JSONArray(r.deliveredTo).let { arr -> (0 until arr.length()).map { arr.getString(it) } } } catch (_: Exception) { emptyList() }
            if (delivered.isNotEmpty()) sb.appendLine("  Delivered: ${delivered.joinToString(", ")}")
            if (r.errorMessage.isNotBlank()) sb.appendLine("  Error: ${r.errorMessage}")
            if (r.extractedContent.isNotBlank()) {
                sb.appendLine("  Content: ${r.extractedContent.take(200).replace("\n", " ")}")
            }
        }

        return ToolResult(true, sb.toString().take(4000))
    }

    private fun toggleAgent(target: String, enabled: Boolean): ToolResult {
        val agent = findAgent(target) ?: return ToolResult(false, "Agent '$target' not found.", "not_found")
        agentManager.setEnabled(agent.id, enabled)
        val action = if (enabled) "enabled" else "disabled"
        return ToolResult(true, "Agent '${agent.name}' has been $action.")
    }

    private suspend fun runAgent(target: String): ToolResult {
        val agent = findAgent(target) ?: return ToolResult(false, "Agent '$target' not found.", "not_found")
        return withContext(Dispatchers.IO) {
            try {
                WebScraperAgent(context).run(agent)
                val updated = agentManager.loadAll().find { it.id == agent.id }
                ToolResult(true, "Agent '${agent.name}' executed. Result: ${updated?.lastStatus ?: "unknown"}")
            } catch (e: Exception) {
                ToolResult(false, "Failed to run agent '${agent.name}': ${e.message}", e.message)
            }
        }
    }

    private fun deleteAgent(target: String): ToolResult {
        val agent = findAgent(target) ?: return ToolResult(false, "Agent '$target' not found.", "not_found")
        agentManager.delete(agent.id)
        return ToolResult(true, "Agent '${agent.name}' has been deleted.")
    }

    private suspend fun createAgent(args: Map<String, String>): ToolResult {
        val inputName = args["agent_name"]?.trim()
        val url = args["url"]?.trim()
        val interval = args["interval"]?.toIntOrNull() ?: 60
        val channel = args["channel"]?.trim() ?: "telegram"
        val extractPrompt = args["extract_prompt"]?.trim() ?: ""
        val userRequest = args["_user_request"]?.trim() ?: ""

        // ── 1. User provided a URL → create directly ──
        if (!url.isNullOrBlank()) {
            val name = inputName ?: try {
                val host = java.net.URI(url).host?.removePrefix("www.") ?: "Agent"
                "Agent — $host"
            } catch (_: Exception) { "New Agent" }
            val agent = agentManager.createWebScraper(
                name = name, url = url, intervalMinutes = interval,
                channel = channel, chatId = "",
                extractPrompt = extractPrompt.ifBlank { userRequest },
                onlyOnChange = true
            )
            return ToolResult(true, "Agent '${agent.name}' created!\nURL: ${agent.url}\nInterval: ${agent.intervalMinutes}m\nChannel: $channel\n\nIt will run on its next cycle. Say 'run ${agent.name}' to trigger now.")
        }

        // ── 2. No URL — smart creation from concept ──
        val concept = when {
            !inputName.isNullOrBlank() -> inputName
            userRequest.isNotBlank() -> userRequest
            extractPrompt.isNotBlank() -> extractPrompt
            else -> ""
        }
        if (concept.isBlank()) {
            return ToolResult(true, "Tell me what you want to track! For example:\n" +
                "• \"track gold prices\"\n• \"monitor bitcoin\"\n• \"get latest tech news\"\n" +
                "• \"track weather in London\"\n• \"monitor earthquake alerts\"\n" +
                "• \"latest movies in india\"\n• \"track IPOs\"\n" +
                "I'll find the best sources and create agents automatically.")
        }

        val conceptLower = concept.lowercase()
        val collectedUrls = mutableListOf<String>()
        val sourceDescriptions = mutableListOf<String>()
        var finalName = inputName ?: concept.take(40)
        var finalPrompt = extractPrompt
        var finalInterval = interval
        var finalApiSource: String? = null
        var finalTemplateId: String? = null

        // ── 2a. Match against built-in templates (free APIs first) ──
        val matchedTemplates = findMatchingTemplates(conceptLower)

        // ── 2a-1. Get smart URLs first (they have better prompts for specific topics)
        val smartUrls = findSmartUrls(conceptLower)

        if (matchedTemplates.isNotEmpty()) {
            val tpl = matchedTemplates.first()
            val query = extractQueryForTemplate(conceptLower, tpl)
            val tplUrl = tpl.url.replace("{query}", java.net.URLEncoder.encode(query, "UTF-8"))
            // Only add template URL if smart URLs didn't clear the list (movies override templates)
            if (smartUrls.isEmpty() || smartUrls.none { it.first.contains("BookMyShow", true) }) {
                collectedUrls.add(tplUrl)
                sourceDescriptions.add("${tpl.emoji} ${tpl.name}${if (tpl.apiSource != null) " (Free API)" else ""}")
                finalApiSource = tpl.apiSource
                finalTemplateId = tpl.id
            }
            // Use the cleaned concept as name, not split words
            finalName = when {
                inputName != null -> inputName
                query.isNotBlank() && query.length > 3 -> "${tpl.name} — $query"
                else -> "${tpl.name} — ${concept.take(30)}"
            }
            finalInterval = interval.takeIf { it != 60 } ?: tpl.intervalMinutes
            // Use template prompt only if smart URLs don't have a better one
            if (finalPrompt.isNullOrBlank() && smartUrls.isEmpty()) {
                finalPrompt = tpl.extractPrompt.replace("{query}", query)
            }
        }

        // ── 2b. Add smart URLs (use their prompt if it's better) ──
        for ((siteName, siteUrl, sitePrompt) in smartUrls) {
            if (siteUrl !in collectedUrls) {
                collectedUrls.add(siteUrl)
                sourceDescriptions.add("🔗 $siteName")
            }
            // Smart URL prompts are topic-specific — prefer them
            if (finalPrompt.isNullOrBlank() && sitePrompt.isNotBlank()) {
                finalPrompt = sitePrompt
            }
        }

        // ── 2c. Web search to discover more relevant sites (only if we need more) ──
        if (collectedUrls.size < 3) {
            try {
                ZeroClawService.log("AGENT_CREATE: searching web for best sites → $concept")
                val searchResult = WebSearchTool().execute(mapOf("query" to "$concept best websites sources"))
                if (searchResult.success && searchResult.content.isNotBlank()) {
                    val urlPattern = Regex("https?://[^\\s\"'<>)]+")
                    val foundUrls = urlPattern.findAll(searchResult.content)
                        .map { it.value.removeSuffix(",").removeSuffix(")").removeSuffix(".") }
                        .filter { url ->
                            !url.contains("google.com/search") && !url.contains("duckduckgo.com") &&
                            !url.contains("facebook.com") && !url.contains("twitter.com") &&
                            !url.contains("instagram.com") && !url.contains("youtube.com") &&
                            url !in collectedUrls
                        }
                        .distinct()
                        .take(3 - collectedUrls.size)
                        .toList()

                    for (foundUrl in foundUrls) {
                        collectedUrls.add(foundUrl)
                        val host = try { java.net.URI(foundUrl).host?.removePrefix("www.") ?: foundUrl } catch (_: Exception) { foundUrl }
                        sourceDescriptions.add("🌐 $host (discovered)")
                    }
                }
            } catch (e: Exception) {
                ZeroClawService.log("AGENT_CREATE: web search failed — ${e.message}")
            }
        }

        if (collectedUrls.isEmpty()) {
            // Last resort: use Google Search
            val query = java.net.URLEncoder.encode(concept, "UTF-8")
            collectedUrls.add("https://www.google.com/search?q=$query")
            sourceDescriptions.add("🔍 Google Search")
        }

        // ── 3. Create ONE agent with all URLs ──
        val primaryUrl = collectedUrls.first()
        val extraUrls = if (collectedUrls.size > 1) JSONArray(collectedUrls.drop(1)).toString() else null

        if (finalPrompt.isNullOrBlank()) {
            finalPrompt = "Extract the most relevant and current data about $concept. " +
                "Show key facts, numbers, prices, dates, or headlines as a clean numbered list. " +
                "Use Telegram Markdown: *bold* for headers, bullets for items."
        }

        val agent = agentManager.createWebScraper(
            name = finalName, url = primaryUrl,
            intervalMinutes = finalInterval, channel = channel, chatId = "",
            extractPrompt = finalPrompt, onlyOnChange = true,
            templateId = finalTemplateId, apiSource = finalApiSource
        )
        // Save extra URLs
        if (extraUrls != null) {
            val updated = agent.copy(extraUrls = extraUrls)
            agentManager.save(updated)
        }

        // ── 4. Auto-run once to get preview ──
        val savedAgent = agentManager.loadAll().find { it.id == agent.id } ?: agent
        var previewContent = ""
        try {
            ZeroClawService.log("AGENT_CREATE: running preview fetch for '${agent.name}'")
            withContext(Dispatchers.IO) {
                WebScraperAgent(context).run(savedAgent)
            }
            // Get the latest result from DB
            val latestResult = resultDao.getByAgentId(agent.id, 1).firstOrNull()
            if (latestResult != null && latestResult.extractedContent.isNotBlank()) {
                previewContent = latestResult.extractedContent.take(1500)
            } else if (latestResult != null && latestResult.rawContent.isNotBlank()) {
                previewContent = latestResult.rawContent.take(1000)
            }
        } catch (e: Exception) {
            ZeroClawService.log("AGENT_CREATE: preview fetch failed — ${e.message}")
        }

        val sb = StringBuilder("Agent \"${agent.name}\" created!\n\n")
        sb.appendLine("📡 Sources (${collectedUrls.size}):")
        for ((i, desc) in sourceDescriptions.withIndex()) {
            sb.appendLine("  ${i + 1}. $desc")
        }
        sb.appendLine("\n⏱️ Checks every ${agent.intervalMinutes} minutes")
        sb.appendLine("📤 Delivers to: $channel")

        if (previewContent.isNotBlank()) {
            sb.appendLine("\n━━━ PREVIEW — Here's what the agent found ━━━")
            sb.appendLine(previewContent)
            sb.appendLine("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
            sb.appendLine("\nIs this what you wanted? You can say:")
            sb.appendLine("• \"looks good\" — keep the agent as is")
            sb.appendLine("• \"change to only remote jobs\" — I'll modify and show again")
            sb.appendLine("• \"delete this agent\" — remove it")
        } else {
            sb.appendLine("\n⚠️ Preview fetch returned no data. The sources might need time or the extraction prompt may need adjusting.")
            sb.appendLine("Say 'run ${agent.name}' to try again, or tell me what to change.")
        }

        return ToolResult(true, sb.toString())
    }

    /** Modify an existing agent's extraction prompt/settings and re-run preview */
    private suspend fun modifyAgent(target: String, args: Map<String, String>): ToolResult {
        val agent = findAgent(target)
            ?: return ToolResult(false, "Agent '$target' not found. Say 'list agents' to see all.", "not_found")

        val newPrompt = args["extract_prompt"]?.trim()
        val newUrl = args["url"]?.trim()
        val newInterval = args["interval"]?.toIntOrNull()
        val newChannel = args["channel"]?.trim()
        val userRequest = args["_user_request"]?.trim() ?: ""

        // Build updated agent
        var updated = agent
        if (!newPrompt.isNullOrBlank()) updated = updated.copy(extractPrompt = newPrompt)
        else if (userRequest.isNotBlank()) updated = updated.copy(extractPrompt = userRequest)
        if (!newUrl.isNullOrBlank()) updated = updated.copy(url = newUrl)
        if (newInterval != null) updated = updated.copy(intervalMinutes = newInterval.coerceAtLeast(1))
        if (!newChannel.isNullOrBlank()) updated = updated.copy(channel = newChannel)

        agentManager.save(updated)

        // Re-run to get new preview
        var previewContent = ""
        try {
            withContext(Dispatchers.IO) {
                WebScraperAgent(context).run(updated)
            }
            val latestResult = resultDao.getByAgentId(agent.id, 1).firstOrNull()
            if (latestResult != null && latestResult.extractedContent.isNotBlank()) {
                previewContent = latestResult.extractedContent.take(1500)
            } else if (latestResult != null && latestResult.rawContent.isNotBlank()) {
                previewContent = latestResult.rawContent.take(1000)
            }
        } catch (e: Exception) {
            ZeroClawService.log("AGENT_MODIFY: preview failed — ${e.message}")
        }

        val sb = StringBuilder("Agent \"${updated.name}\" updated!\n\n")
        if (!newPrompt.isNullOrBlank() || userRequest.isNotBlank()) sb.appendLine("🔍 New extraction: ${updated.extractPrompt.take(100)}")
        if (!newUrl.isNullOrBlank()) sb.appendLine("🔗 New URL: $newUrl")
        if (newInterval != null) sb.appendLine("⏱️ New interval: ${newInterval}m")
        if (!newChannel.isNullOrBlank()) sb.appendLine("📤 New channel: $newChannel")

        if (previewContent.isNotBlank()) {
            sb.appendLine("\n━━━ UPDATED PREVIEW ━━━")
            sb.appendLine(previewContent)
            sb.appendLine("━━━━━━━━━━━━━━━━━━━━━━━")
            sb.appendLine("\nIs this better? Say 'looks good' to keep, or tell me what else to change.")
        } else {
            sb.appendLine("\n⚠️ Preview returned no data. Try adjusting your request.")
        }

        return ToolResult(true, sb.toString())
    }

    /** Match user concept against built-in agent templates by keywords */
    private fun findMatchingTemplates(concept: String): List<AgentTemplate> {
        val keywordMap = mapOf(
            listOf("gold", "silver", "copper", "platinum", "palladium", "commodity", "metal") to "tpl_gold_tracker",
            listOf("crypto", "bitcoin", "btc", "ethereum", "eth", "coin", "token", "dogecoin", "solana") to "tpl_crypto_tracker",
            listOf("news", "headline", "breaking") to "tpl_latest_news",
            listOf("weather", "temperature", "forecast", "rain", "climate") to "tpl_weather",
            listOf("earthquake", "seismic", "quake") to "tpl_earthquake",
            listOf("forex", "exchange rate", "currency", "usd", "eur", "dollar", "rupee") to "tpl_forex",
            listOf("stock", "share", "market", "nifty", "sensex", "nasdaq", "dow", "s&p", "trade") to "tpl_trade_tracker",
            listOf("mutual fund", "sip", "nav") to "tpl_mutual_fund",
            listOf("github", "trending repo", "open source") to "tpl_github_trending",
            listOf("football", "soccer", "premier league", "champions league", "sport", "match", "score") to "tpl_sports",
            listOf("movie", "film", "cinema", "box office", "tv show", "series", "bollywood", "tollywood", "kollywood", "released", "release") to "tpl_movies",
            listOf("youtube", "trending video", "viral video") to "tpl_youtube_trending",
            listOf("fuel", "petrol", "diesel", "gas price", "gasoline") to "tpl_fuel_price",
            listOf("ipo", "listing", "public offering") to "tpl_ipo_tracker",
            listOf("flight", "airfare", "plane ticket", "airline") to "tpl_flights"
        )

        val matched = mutableListOf<AgentTemplate>()
        for ((keywords, templateId) in keywordMap) {
            if (keywords.any { concept.contains(it) }) {
                AGENT_TEMPLATES.find { it.id == templateId }?.let { matched.add(it) }
            }
        }
        return matched
    }

    /** Extract a query string relevant to a template from the user concept */
    private fun extractQueryForTemplate(concept: String, template: AgentTemplate): String {
        // Remove common action words using word boundaries to avoid breaking words like "tollywood"
        val stripped = concept
            .replace(Regex("\\b(create|make|add|set up|setup|track|monitor|watch|agent|scraper|for me|please|every|send to|send|telegram|discord|slack|whatsapp)\\b", RegexOption.IGNORE_CASE), " ")
            .replace(Regex("\\b(\\d+\\s*min(utes?)?)\\b", RegexOption.IGNORE_CASE), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
        // If template has subcategories, try to match
        if (template.subCategories.isNotEmpty()) {
            for (sub in template.subCategories) {
                if (concept.contains(sub.lowercase().split(" ").first())) return sub
            }
        }
        return stripped.ifBlank { template.name }
    }

    /** Generate smart URLs for concepts that don't match templates */
    private fun findSmartUrls(concept: String): List<Triple<String, String, String>> {
        val results = mutableListOf<Triple<String, String, String>>()
        val query = java.net.URLEncoder.encode(concept, "UTF-8")

        // Google Search as a universal fallback
        results.add(Triple(
            "Google Search",
            "https://www.google.com/search?q=$query",
            "Extract the most relevant and current data about $concept. Show key facts, numbers, prices, or headlines as a clean summary."
        ))

        // Add topic-specific sites
        when {
            concept.contains("price") || concept.contains("cost") -> {
                results.add(0, Triple(
                    "Google Finance",
                    "https://www.google.com/search?q=$query+price+today",
                    "Extract current price, change, percentage change, high, low for $concept."
                ))
            }
            concept.contains("news") || concept.contains("headline") -> {
                results.add(0, Triple(
                    "Google News RSS",
                    "https://news.google.com/rss/search?q=$query",
                    "Extract the top 10 latest headlines about $concept with brief summaries."
                ))
            }
            concept.contains("score") || concept.contains("match") || concept.contains("game") -> {
                results.add(0, Triple(
                    "ESPN",
                    "https://www.google.com/search?q=$query+live+score+today",
                    "Extract live scores, recent match results, and upcoming games for $concept."
                ))
            }
            concept.contains("movie") || concept.contains("film") || concept.contains("released") ||
                concept.contains("bollywood") || concept.contains("tollywood") || concept.contains("kollywood") -> {
                val language = when {
                    concept.contains("telugu") || concept.contains("tollywood") -> "telugu"
                    concept.contains("tamil") || concept.contains("kollywood") -> "tamil"
                    concept.contains("hindi") || concept.contains("bollywood") -> "hindi"
                    concept.contains("malayalam") || concept.contains("mollywood") -> "malayalam"
                    concept.contains("kannada") || concept.contains("sandalwood") -> "kannada"
                    else -> "indian"
                }
                val moviePrompt = """Extract ONLY the actual movie names that were recently released or are currently running in theaters.
DO NOT list article titles, page headings, or 'Top 10...' style list names.
I want the REAL movie names like 'Pushpa 2', 'Kalki 2898 AD', 'Devara', etc.
For each movie show: *Movie Name* — language, genre, release date if available.
Format as a numbered list. Only include $language movies."""

                results.clear() // movies need specific sources, not generic google
                results.add(Triple(
                    "BookMyShow $language",
                    "https://in.bookmyshow.com/explore/movies-$language",
                    moviePrompt
                ))
                results.add(Triple(
                    "Google Movies",
                    "https://www.google.com/search?q=latest+$language+movies+released+this+week+2024+2025",
                    moviePrompt
                ))
                results.add(Triple(
                    "FilmiBeat",
                    "https://www.google.com/search?q=site:filmibeat.com+latest+$language+movies+released",
                    moviePrompt
                ))
            }
            concept.contains("crypto") || concept.contains("bitcoin") || concept.contains("coin") -> {
                results.add(0, Triple(
                    "CoinMarketCap",
                    "https://www.google.com/search?q=$query+price+today",
                    "Extract current price, 24h change, market cap for $concept."
                ))
            }
            concept.contains("job") || concept.contains("hiring") || concept.contains("career") ||
                concept.contains("developer") || concept.contains("engineer") || concept.contains("vacancy") ||
                concept.contains("openings") || concept.contains("recruitment") -> {
                val jobQuery = java.net.URLEncoder.encode(concept.replace(Regex("\\b(job|jobs|hiring|career|vacancy|openings|recruitment|latest|get|all|details|with)\\b", RegexOption.IGNORE_CASE), " ").trim(), "UTF-8")
                val jobPrompt = """Extract ACTUAL job listings. For each job show:
• *Job Title/Post Name* — company name
• Location (remote/city)
• Requirements: key skills, experience needed
• Brief JD summary (1-2 lines)
Format as a numbered list. Only show REAL current job postings, not articles about jobs."""

                results.clear()
                results.add(Triple(
                    "LinkedIn Jobs",
                    "https://www.google.com/search?q=$jobQuery+jobs+hiring+site:linkedin.com",
                    jobPrompt
                ))
                results.add(Triple(
                    "Naukri",
                    "https://www.google.com/search?q=$jobQuery+jobs+site:naukri.com",
                    jobPrompt
                ))
                results.add(Triple(
                    "Indeed",
                    "https://www.google.com/search?q=$jobQuery+jobs+site:indeed.com",
                    jobPrompt
                ))
                results.add(Triple(
                    "Google Jobs",
                    "https://www.google.com/search?q=$jobQuery+jobs+hiring+latest",
                    jobPrompt
                ))
            }
            concept.contains("anime") || concept.contains("manga") -> {
                results.clear()
                results.add(Triple(
                    "MyAnimeList",
                    "https://myanimelist.net/anime/season",
                    "Extract currently airing anime this season. For each: *Anime Name* — genre, episodes, rating. Numbered list."
                ))
                results.add(Triple(
                    "Google Anime",
                    "https://www.google.com/search?q=latest+anime+releasing+this+week",
                    "Extract latest anime releases this week with names, episode numbers, and air dates."
                ))
            }
        }

        return results
    }

    private fun findAgent(target: String): AgentConfig? {
        if (target.isBlank()) return null
        val agents = agentManager.loadAll()
        // Try exact ID match
        agents.find { it.id == target }?.let { return it }
        // Try ID prefix match
        agents.find { it.id.startsWith(target, ignoreCase = true) }?.let { return it }
        // Try exact name match (case-insensitive)
        agents.find { it.name.equals(target, ignoreCase = true) }?.let { return it }
        // Try partial name match
        agents.find { it.name.contains(target, ignoreCase = true) }?.let { return it }
        return null
    }
}

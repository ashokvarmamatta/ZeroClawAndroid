package ai.zeroclaw.android.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ai.zeroclaw.android.agents.AgentConfig
import ai.zeroclaw.android.agents.AgentManager
import ai.zeroclaw.android.agents.AgentTemplate
import ai.zeroclaw.android.agents.NEWS_CATEGORIES
import ai.zeroclaw.android.data.AppSettings
import ai.zeroclaw.android.data.LlmRouter
import ai.zeroclaw.android.tools.WebFetchTool
import ai.zeroclaw.android.tools.WebViewTool
import ai.zeroclaw.android.tools.RssFeedTool
import java.util.UUID
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

// ─────────────────────────────────────────────────────────────────────────────
// AgentCreateSheet — bottom sheet form for creating / editing a Web Scraper agent
// ─────────────────────────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AgentCreateSheet(
    existing: AgentConfig? = null,
    template: AgentTemplate? = null,
    onDismiss: () -> Unit,
    onSave: (AgentConfig) -> Unit
) {
    val isEdit = existing != null
    val isTemplate = template != null
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val clipboardManager = LocalClipboardManager.current

    // Detect connected bots and known chat IDs
    var connectedBots by remember { mutableStateOf(mapOf<String, Boolean>()) }
    var knownChatIds by remember { mutableStateOf(mapOf<String, List<String>>()) }
    LaunchedEffect(Unit) {
        val prefs = AppSettings.dataStore(context).data.first()
        connectedBots = mapOf(
            "telegram" to (prefs[AppSettings.KEY_TELEGRAM_TOKEN] ?: "").isNotBlank(),
            "discord" to (prefs[AppSettings.KEY_DISCORD_TOKEN] ?: "").isNotBlank(),
            "slack" to (prefs[AppSettings.KEY_SLACK_TOKEN] ?: "").isNotBlank(),
            "whatsapp" to (prefs[AppSettings.KEY_TWILIO_SID] ?: "").isNotBlank(),
            "signal" to (prefs[AppSettings.KEY_SIGNAL_API_URL] ?: "").isNotBlank()
        )
        knownChatIds = try { LlmRouter.getInstance(context).getKnownChatIds() } catch (_: Exception) { emptyMap() }
    }

    // ── Parse existing agent's multi-channel data ──
    // channel field stores comma-separated bot names: "telegram,discord"
    // chatId field stores channel:id pairs: "discord:123,slack:C456" or legacy single ID
    val existingBots = remember(existing) {
        existing?.channel?.split(",")?.map { it.trim() }?.filter { it.isNotBlank() }?.toSet() ?: emptySet()
    }
    val existingChannelTargets = remember(existing) {
        parseChannelTargets(existing?.chatId ?: "")
    }

    // Form state — pre-fill from template or existing agent
    var name          by remember { mutableStateOf(existing?.name ?: template?.name ?: "") }
    var url           by remember { mutableStateOf(existing?.url ?: template?.url ?: "") }
    var intervalRaw   by remember { mutableStateOf((existing?.intervalMinutes ?: template?.intervalMinutes ?: 60).toString()) }
    var extractPrompt by remember { mutableStateOf(existing?.extractPrompt ?: template?.extractPrompt ?: "") }
    var onlyOnChange  by remember { mutableStateOf(existing?.onlyOnChange ?: template?.onlyOnChange ?: true) }
    var customInput   by remember { mutableStateOf("") }
    var selectedSubs  by remember { mutableStateOf(setOf<String>()) }

    // Live-update URL and extractPrompt when sub-categories or custom input change (new template only)
    LaunchedEffect(selectedSubs, customInput) {
        if (isTemplate && template != null && existing == null) {
            val query = when {
                customInput.isNotBlank() -> customInput.trim()
                selectedSubs.isNotEmpty() -> selectedSubs.joinToString("+")
                else -> ""
            }
            val queryLabel = when {
                customInput.isNotBlank() -> customInput.trim()
                selectedSubs.isNotEmpty() -> selectedSubs.joinToString(", ")
                else -> ""
            }
            if (query.isNotBlank()) {
                val encoded = java.net.URLEncoder.encode(query, "UTF-8")
                url = template.url.replace("{query}", encoded)
                extractPrompt = template.extractPrompt.replace("{query}", queryLabel)
                name = when {
                    selectedSubs.size == 1 -> "${template.name} — ${selectedSubs.first()}"
                    customInput.isNotBlank() -> "${template.name} — $customInput"
                    selectedSubs.size > 1 -> "${template.name} — ${selectedSubs.size} categories"
                    else -> template.name
                }
            } else {
                url = template.url.replace("{query}", "")
                extractPrompt = template.extractPrompt.replace("{query}", "")
                name = template.name
            }
        }
    }

    // ── Delivery state: Bots (multi-select) ──
    // For new agents: auto-check all connected bots. For edit: use saved selection.
    var selectedBots by remember(connectedBots) {
        mutableStateOf(
            if (existing != null) existingBots
            else connectedBots.filter { it.value }.keys  // all connected bots checked by default
        )
    }

    // ── Delivery state: Channels (specific targets with chat IDs) ──
    var channelTargets by remember { mutableStateOf(existingChannelTargets) }
    var showAddChannel by remember { mutableStateOf(false) }

    // Validation errors
    var nameError   by remember { mutableStateOf<String?>(null) }
    var urlError    by remember { mutableStateOf<String?>(null) }
    var deliveryError by remember { mutableStateOf<String?>(null) }

    // Test fetch state
    var testLoading  by remember { mutableStateOf(false) }
    var testResult   by remember { mutableStateOf<String?>(null) }
    var testSuccess  by remember { mutableStateOf(false) }

    // AI Smart Extract state — pre-fill from existing agent when editing
    var aiQuery by remember { mutableStateOf(existing?.extractPrompt ?: "") }
    var aiAnalyzing by remember { mutableStateOf(false) }
    var aiFetchType by remember { mutableStateOf(existing?.safeFetchType ?: "http") }
    var aiRawContent by remember { mutableStateOf<String?>(null) }
    var aiContentFound by remember { mutableStateOf(
        if (existing?.safeFormatPreview?.isNotBlank() == true) true else null
    ) }
    var aiFormatPreview by remember { mutableStateOf(existing?.safeFormatPreview?.ifBlank { null }) }
    var aiError by remember { mutableStateOf<String?>(null) }
    var aiValuesLoading by remember { mutableStateOf(false) }
    var aiValuesList by remember { mutableStateOf<String?>(null) }
    var copiedFetchData by remember { mutableStateOf(false) }

    // All bot definitions
    val allBots = listOf(
        BotDef("telegram", "Telegram", "✈️"),
        BotDef("discord", "Discord", "🎮"),
        BotDef("slack", "Slack", "💼"),
        BotDef("whatsapp", "WhatsApp", "💬"),
        BotDef("signal", "Signal", "🔒")
    )

    // All channel options (for the Channels section)
    val channelOptions = listOf(
        ChannelDef("telegram", "Telegram Chat/Group", "✈️", "Chat ID (e.g. 123456789 or -1001234567890)"),
        ChannelDef("discord", "Discord Channel", "🎮", "Channel ID (e.g. 1234567890123456789)"),
        ChannelDef("slack", "Slack Channel", "💼", "#channel or C1234567890"),
        ChannelDef("whatsapp", "WhatsApp Number", "💬", "+1234567890"),
        ChannelDef("email", "Email", "📧", "user@example.com")
    )

    val accentColor = Color(0xFF1E88E5)
    val greenColor = Color(0xFF2E7D32)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = Color(0xFF0D1117),
        dragHandle = {
            Row(modifier = Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 4.dp),
                horizontalArrangement = Arrangement.Center) {
                Surface(shape = RoundedCornerShape(2.dp),
                    color = Color.White.copy(alpha = 0.2f),
                    modifier = Modifier.width(36.dp).height(4.dp)) {}
            }
        }
    ) {
        LazyColumn(
            modifier = Modifier.fillMaxWidth().navigationBarsPadding(),
            contentPadding = PaddingValues(horizontal = 20.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Header
            item {
                Row(verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(template?.emoji ?: "🕷️", fontSize = 28.sp)
                    Column {
                        Text(
                            when {
                                isEdit -> "Edit Agent"
                                isTemplate -> template!!.name
                                else -> "New Web Scraper Agent"
                            },
                            fontWeight = FontWeight.Bold, fontSize = 18.sp, color = Color.White)
                        Text(
                            template?.description ?: "Periodically scrapes a URL and pushes updates to your channel",
                            fontSize = 11.sp, color = Color.White.copy(alpha = 0.5f))
                        // Phase 166: Show API source badge
                        if (template?.apiSource != null) {
                            Spacer(Modifier.height(4.dp))
                            Text(
                                "⚡ ${template.apiRateNote ?: "Direct API — no web scraping needed"}",
                                fontSize = 10.sp,
                                color = Color(0xFF4ADE80)
                            )
                        }
                    }
                }
            }

            // Template sub-categories & custom input
            if (isTemplate && !isEdit) {
                if (template!!.subCategories.isNotEmpty()) {
                    item {
                        SectionLabel("Categories", accentColor)
                    }
                    item {
                        Text("Select categories to track:", fontSize = 12.sp, color = accentColor)
                        Spacer(Modifier.height(8.dp))
                        val columns = 2
                        val rows = (template.subCategories.size + columns - 1) / columns
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            repeat(rows) { row ->
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    modifier = Modifier.fillMaxWidth()) {
                                    for (col in 0 until columns) {
                                        val idx = row * columns + col
                                        if (idx < template.subCategories.size) {
                                            val sub = template.subCategories[idx]
                                            val selected = sub in selectedSubs
                                            FilterChip(
                                                selected = selected,
                                                onClick = {
                                                    selectedSubs = if (selected) selectedSubs - sub else selectedSubs + sub
                                                },
                                                label = { Text(sub, fontSize = 11.sp) },
                                                modifier = Modifier.weight(1f),
                                                shape = RoundedCornerShape(8.dp),
                                                colors = FilterChipDefaults.filterChipColors(
                                                    selectedContainerColor = accentColor,
                                                    selectedLabelColor = Color.White
                                                )
                                            )
                                        } else {
                                            Spacer(Modifier.weight(1f))
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
                if (template!!.needsUserInput) {
                    item {
                        FormField(
                            label = "Custom Input",
                            value = customInput,
                            onValueChange = { customInput = it },
                            placeholder = template.customFieldHint,
                            error = null,
                            accent = accentColor,
                            leadingIcon = { Icon(Icons.Default.Edit, null, tint = accentColor, modifier = Modifier.size(18.dp)) }
                        )
                    }
                }
            }

            // ── Section: Identity ──────────────────────────────────────────
            item { SectionLabel("Identity", accentColor) }

            item {
                FormField(
                    label = "Agent Name",
                    value = name,
                    onValueChange = { name = it; nameError = null },
                    placeholder = "e.g. BBC News Monitor",
                    error = nameError,
                    accent = accentColor,
                    leadingIcon = { Icon(Icons.Default.Label, null, tint = accentColor, modifier = Modifier.size(18.dp)) }
                )
            }

            item {
                FormField(
                    label = "Target URL",
                    value = url,
                    onValueChange = { url = it; urlError = null },
                    placeholder = "https://example.com/news",
                    error = urlError,
                    accent = accentColor,
                    leadingIcon = { Icon(Icons.Default.Link, null, tint = accentColor, modifier = Modifier.size(18.dp)) }
                )
            }

            // ── Section: Schedule ──────────────────────────────────────────
            item { SectionLabel("Schedule", accentColor) }

            item {
                IntervalPicker(
                    value = intervalRaw,
                    onChange = { intervalRaw = it },
                    accent = accentColor
                )
            }

            item {
                Row(modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically) {
                    Column {
                        Text("Push only on change", fontSize = 13.sp,
                            fontWeight = FontWeight.Medium, color = Color.White)
                        Text("Skip delivery if page content hasn't changed",
                            fontSize = 11.sp, color = Color.White.copy(alpha = 0.5f))
                    }
                    Switch(
                        checked = onlyOnChange,
                        onCheckedChange = { onlyOnChange = it },
                        colors = SwitchDefaults.colors(checkedTrackColor = accentColor)
                    )
                }
            }

            // ── Section: Delivery ──────────────────────────────────────────
            item { SectionLabel("Delivery", accentColor) }

            // ── Bots sub-section ──
            item {
                Text("🤖 Bots", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = Color.White)
                Spacer(Modifier.height(2.dp))
                Text("Connected bots from Settings — delivers to your bot chat automatically",
                    fontSize = 10.sp, color = Color.White.copy(alpha = 0.4f), lineHeight = 14.sp)
                Spacer(Modifier.height(10.dp))

                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    allBots.forEach { bot ->
                        val connected = connectedBots[bot.id] == true
                        val checked = bot.id in selectedBots
                        Surface(
                            shape = RoundedCornerShape(10.dp),
                            color = when {
                                checked && connected -> greenColor.copy(alpha = 0.15f)
                                checked -> accentColor.copy(alpha = 0.1f)
                                else -> Color.White.copy(alpha = 0.03f)
                            },
                            modifier = Modifier.fillMaxWidth().clickable(enabled = connected) {
                                deliveryError = null
                                selectedBots = if (checked) selectedBots - bot.id else selectedBots + bot.id
                            }
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                Checkbox(
                                    checked = checked,
                                    onCheckedChange = if (connected) { _ ->
                                        deliveryError = null
                                        selectedBots = if (checked) selectedBots - bot.id else selectedBots + bot.id
                                    } else null,
                                    enabled = connected,
                                    colors = CheckboxDefaults.colors(
                                        checkedColor = if (connected) greenColor else accentColor,
                                        uncheckedColor = Color.White.copy(alpha = if (connected) 0.4f else 0.15f)
                                    ),
                                    modifier = Modifier.size(20.dp)
                                )
                                Text(bot.emoji, fontSize = 18.sp)
                                Text(bot.label, fontSize = 13.sp, fontWeight = FontWeight.Medium,
                                    color = if (connected) Color.White else Color.White.copy(alpha = 0.35f),
                                    modifier = Modifier.weight(1f))
                                if (connected) {
                                    Surface(shape = RoundedCornerShape(4.dp), color = greenColor.copy(alpha = 0.25f)) {
                                        Text("Connected", modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                            fontSize = 9.sp, color = Color(0xFF81C784), fontWeight = FontWeight.Bold)
                                    }
                                } else {
                                    Text("Not configured", fontSize = 10.sp, color = Color.White.copy(alpha = 0.25f))
                                }
                            }
                        }
                    }
                }
            }

            // ── Channels sub-section ──
            item {
                Spacer(Modifier.height(4.dp))
                Text("📢 Channels", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = Color.White)
                Spacer(Modifier.height(2.dp))
                Text("Send to specific chat IDs, group IDs, or channel IDs",
                    fontSize = 10.sp, color = Color.White.copy(alpha = 0.4f), lineHeight = 14.sp)
                Spacer(Modifier.height(10.dp))

                // List existing channel targets
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    channelTargets.forEachIndexed { index, target ->
                        val chDef = channelOptions.firstOrNull { it.id == target.channel }
                        Surface(
                            shape = RoundedCornerShape(10.dp),
                            color = accentColor.copy(alpha = 0.08f),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Text(chDef?.emoji ?: "📤", fontSize = 16.sp)
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(chDef?.label ?: target.channel, fontSize = 12.sp,
                                        fontWeight = FontWeight.Medium, color = Color.White)
                                    Text(target.chatId, fontSize = 10.sp,
                                        fontFamily = FontFamily.Monospace, color = accentColor)
                                }
                                IconButton(
                                    onClick = {
                                        channelTargets = channelTargets.toMutableList().also { it.removeAt(index) }
                                    },
                                    modifier = Modifier.size(28.dp)
                                ) {
                                    Icon(Icons.Default.Close, null, tint = Color(0xFFEF5350), modifier = Modifier.size(16.dp))
                                }
                            }
                        }
                    }

                    // Add channel button / form
                    if (showAddChannel) {
                        AddChannelForm(
                            channelOptions = channelOptions,
                            knownChatIds = knownChatIds,
                            accent = accentColor,
                            onAdd = { ch, id ->
                                channelTargets = channelTargets + ChannelTarget(ch, id)
                                showAddChannel = false
                                deliveryError = null
                            },
                            onCancel = { showAddChannel = false }
                        )
                    } else {
                        OutlinedButton(
                            onClick = { showAddChannel = true },
                            modifier = Modifier.fillMaxWidth().height(40.dp),
                            shape = RoundedCornerShape(10.dp),
                            border = androidx.compose.foundation.BorderStroke(1.dp, accentColor.copy(alpha = 0.3f)),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = accentColor)
                        ) {
                            Icon(Icons.Default.Add, null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Add Channel Target", fontSize = 12.sp)
                        }
                    }
                }

                // Delivery validation error
                deliveryError?.let { err ->
                    Spacer(Modifier.height(4.dp))
                    Text(err, fontSize = 10.sp, color = Color(0xFFEF5350))
                }
            }

            // ── Section: AI Smart Extract ─────────────────────────────────
            item { SectionLabel("AI Smart Extract", accentColor) }

            // What do you need? text field
            item {
                Text("What do you need from this URL?", fontSize = 12.sp, color = accentColor, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(6.dp))
                OutlinedTextField(
                    value = aiQuery,
                    onValueChange = { aiQuery = it; aiError = null; aiContentFound = null; aiFormatPreview = null },
                    placeholder = {
                        Text("e.g. I need today's top 5 tech news with title, summary, and link",
                            fontSize = 12.sp, color = Color.White.copy(alpha = 0.3f))
                    },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    minLines = 2, maxLines = 4,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = accentColor,
                        unfocusedBorderColor = Color.White.copy(alpha = 0.15f),
                        focusedTextColor = Color.White, unfocusedTextColor = Color.White,
                        cursorColor = accentColor,
                        focusedContainerColor = Color.White.copy(alpha = 0.04f),
                        unfocusedContainerColor = Color.White.copy(alpha = 0.02f)
                    )
                )
                Spacer(Modifier.height(4.dp))
                Text("Describe what data you want extracted. AI will fetch the URL, check if the content is available, and create an extraction format for you.",
                    fontSize = 10.sp, color = Color.White.copy(alpha = 0.35f), lineHeight = 14.sp)
            }

            // Fetch type selector
            item {
                Text("Fetch Method", fontSize = 12.sp, color = accentColor, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(6.dp))
                val fetchTypes = listOf(
                    Triple("http", "HTTP", "Standard web fetch — fast, works for most sites"),
                    Triple("rss", "RSS/Atom", "RSS feed parser — best for news/blog feeds"),
                    Triple("webview", "WebView", "Headless browser — for JavaScript-rendered pages")
                )
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    fetchTypes.forEach { (id, label, desc) ->
                        Surface(
                            shape = RoundedCornerShape(10.dp),
                            color = if (aiFetchType == id) accentColor.copy(alpha = 0.15f)
                                    else Color.White.copy(alpha = 0.03f),
                            modifier = Modifier.fillMaxWidth().clickable {
                                aiFetchType = id
                                // Reset results when switching fetch type
                                aiContentFound = null
                                aiFormatPreview = null
                                aiError = null
                                aiRawContent = null
                                testResult = null
                                testSuccess = false
                            }
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                RadioButton(
                                    selected = aiFetchType == id,
                                    onClick = {
                                        aiFetchType = id
                                        aiContentFound = null; aiFormatPreview = null; aiError = null; aiRawContent = null
                                        testResult = null; testSuccess = false
                                    },
                                    colors = RadioButtonDefaults.colors(selectedColor = accentColor)
                                )
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(label, fontSize = 13.sp, fontWeight = FontWeight.Medium, color = Color.White)
                                    Text(desc, fontSize = 10.sp, color = Color.White.copy(alpha = 0.45f))
                                }
                                if (aiFetchType == id) {
                                    Icon(Icons.Default.CheckCircle, null, tint = accentColor, modifier = Modifier.size(16.dp))
                                }
                            }
                        }
                    }
                }
            }

            // AI Analyze button
            item {
                val canAnalyze = url.startsWith("http://") || url.startsWith("https://")
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button(
                        onClick = {
                            if (!canAnalyze) {
                                urlError = "Enter a valid URL first"; return@Button
                            }
                            if (aiQuery.isBlank()) {
                                aiError = "Describe what you need first"; return@Button
                            }
                            aiAnalyzing = true
                            aiError = null
                            aiContentFound = null
                            aiFormatPreview = null
                            aiRawContent = null
                            testResult = null

                            scope.launch {
                                try {
                                    // Step 1: Fetch URL with selected method
                                    val fetchResult = when (aiFetchType) {
                                        "rss" -> {
                                            try {
                                                RssFeedTool().execute(mapOf("url" to url.trim(), "limit" to "10"))
                                            } catch (_: Exception) {
                                                WebFetchTool().execute(mapOf("url" to url.trim()))
                                            }
                                        }
                                        "webview" -> {
                                            try {
                                                WebViewTool(context).execute(mapOf("action" to "fetch", "url" to url.trim(), "wait_ms" to "3000"))
                                            } catch (_: Exception) {
                                                WebFetchTool().execute(mapOf("url" to url.trim()))
                                            }
                                        }
                                        else -> WebFetchTool().execute(mapOf("url" to url.trim()))
                                    }

                                    if (!fetchResult.success || fetchResult.content.isBlank()) {
                                        aiContentFound = false
                                        aiError = "Could not fetch content from this URL using ${aiFetchType.uppercase()}. Try a different fetch method."
                                        testResult = fetchResult.error ?: "Empty response"
                                        testSuccess = false
                                        aiAnalyzing = false
                                        return@launch
                                    }

                                    val rawContent = fetchResult.content
                                    aiRawContent = rawContent
                                    testResult = rawContent.take(600).trimEnd() +
                                        if (rawContent.length > 600) "\n\n…(${rawContent.length} chars total)" else ""
                                    testSuccess = true

                                    // Step 2: AI analyzes if content matches what user asked for
                                    val router = LlmRouter.getInstance(context)
                                    val analyzePrompt = """You are analyzing fetched web page content to check if it contains ACTUAL data the user needs.

USER WANTS: ${aiQuery.trim()}
URL: ${url.trim()}

FETCHED CONTENT (first 2500 chars):
${rawContent.take(2500)}

CRITICAL RULES FOR ANALYSIS:
- Check if the fetched content has ACTUAL numeric values, real data, real names — not just page titles or descriptions
- If the content only has page titles, navigation text, meta descriptions, or promotional text but NO actual data values → set found=false
- If you would need to use 0, 0.00, N/A, or placeholder values because the real values are NOT in the content → set found=false
- JavaScript-heavy sites (stock trackers, dashboards, SPAs) often return HTML without data via HTTP — if you see no real values, the page likely needs a browser/WebView to render
- ONLY set found=true if you can fill the format with REAL values from the fetched content

Respond in this EXACT JSON format (no markdown, no code blocks, just raw JSON):
{"found": true/false, "explanation": "what actual data was found OR why not (e.g. 'page is JavaScript-rendered, no actual values in HTML')", "suggest_webview": true/false, "format": "if found=true ONLY: formatted preview using Telegram Markdown with REAL values from the content. Use *bold* for labels, • for bullets. If found=false, leave empty string."}"""

                                    val aiReply = router.rawGenerate(analyzePrompt, jsonMode = true, maxTokens = 2048)

                                    // Parse AI response
                                    try {
                                        val json = org.json.JSONObject(aiReply)
                                        val found = json.optBoolean("found", false)
                                        val explanation = json.optString("explanation", "")
                                        val suggestWebview = json.optBoolean("suggest_webview", false)
                                        val format = json.optString("format", "")

                                        aiContentFound = found
                                        if (found && format.isNotBlank()) {
                                            aiFormatPreview = format
                                            if (extractPrompt.isBlank()) {
                                                extractPrompt = aiQuery.trim()
                                            }
                                        } else if (!found) {
                                            val suggestion = if (suggestWebview && aiFetchType != "webview") {
                                                "\n\nThis page likely uses JavaScript to load data. Switch to WebView fetch method and try again."
                                            } else if (aiFetchType == "webview") {
                                                "\n\nEven WebView couldn't get the data. Try a different URL that provides this data as static content or RSS."
                                            } else {
                                                "\n\nTry switching the fetch method or use a different URL."
                                            }
                                            aiError = "$explanation$suggestion"
                                            // Auto-switch to WebView if suggested
                                            if (suggestWebview && aiFetchType == "http") {
                                                aiFetchType = "webview"
                                            }
                                        }
                                    } catch (_: Exception) {
                                        // AI didn't return valid JSON — use raw reply
                                        aiContentFound = true
                                        aiFormatPreview = aiReply.take(1500)
                                        if (extractPrompt.isBlank()) {
                                            extractPrompt = aiQuery.trim()
                                        }
                                    }
                                } catch (e: Exception) {
                                    aiError = "Analysis failed: ${e.message}"
                                    aiContentFound = false
                                }
                                aiAnalyzing = false
                            }
                        },
                        enabled = !aiAnalyzing && canAnalyze,
                        modifier = Modifier.fillMaxWidth().height(48.dp),
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color(0xFF7C4DFF),
                            disabledContainerColor = Color(0xFF7C4DFF).copy(alpha = 0.3f)
                        )
                    ) {
                        if (aiAnalyzing) {
                            CircularProgressIndicator(modifier = Modifier.size(16.dp),
                                color = Color.White, strokeWidth = 2.dp)
                            Spacer(Modifier.width(8.dp))
                            Text("Analyzing…", fontWeight = FontWeight.Bold, color = Color.White)
                        } else {
                            Icon(Icons.Default.AutoAwesome, null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text("AI Analyze", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                        }
                    }

                    // Error message
                    aiError?.let { err ->
                        Surface(shape = RoundedCornerShape(10.dp), color = Color(0xFF2A0A0A)) {
                            Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    Icon(Icons.Default.Cancel, null, tint = Color(0xFFEF9A9A), modifier = Modifier.size(14.dp))
                                    Text("Not available", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Color(0xFFEF9A9A))
                                }
                                Text(err, fontSize = 11.sp, color = Color(0xFFEF9A9A).copy(alpha = 0.8f), lineHeight = 15.sp)
                                Spacer(Modifier.height(4.dp))
                                Text("Try switching the fetch method above and click AI Analyze again.",
                                    fontSize = 10.sp, color = Color.White.copy(alpha = 0.4f))
                            }
                        }
                    }

                    // Success — content found + format preview (editable)
                    if (aiContentFound == true && aiFormatPreview != null) {
                        Surface(shape = RoundedCornerShape(12.dp), color = Color(0xFF0A2A0A)) {
                            Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    Icon(Icons.Default.CheckCircle, null, tint = Color(0xFF81C784), modifier = Modifier.size(16.dp))
                                    Text("Content found! Output format:", fontSize = 12.sp,
                                        fontWeight = FontWeight.Bold, color = Color(0xFF81C784))
                                }
                                // Editable format preview
                                OutlinedTextField(
                                    value = aiFormatPreview!!,
                                    onValueChange = { aiFormatPreview = it },
                                    modifier = Modifier.fillMaxWidth(),
                                    shape = RoundedCornerShape(8.dp),
                                    minLines = 3, maxLines = 10,
                                    textStyle = androidx.compose.ui.text.TextStyle(
                                        fontSize = 11.sp, fontFamily = FontFamily.Monospace,
                                        color = Color(0xFFC9D1D9), lineHeight = 15.sp
                                    ),
                                    colors = OutlinedTextFieldDefaults.colors(
                                        focusedBorderColor = Color(0xFF81C784),
                                        unfocusedBorderColor = Color(0xFF81C784).copy(alpha = 0.3f),
                                        focusedContainerColor = Color(0xFF0D1117),
                                        unfocusedContainerColor = Color(0xFF0D1117),
                                        cursorColor = Color(0xFF81C784)
                                    )
                                )
                                Text("Edit the format above — this is exactly how data will appear in your chat messages.",
                                    fontSize = 10.sp, color = Color.White.copy(alpha = 0.4f))
                            }
                        }
                    }

                    // Fetched content preview (from any method)
                    testResult?.let { res ->
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = if (testSuccess) Color(0xFF0A2A0A) else Color(0xFF2A0A0A)
                        ) {
                            Column(modifier = Modifier.padding(12.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    Icon(
                                        if (testSuccess) Icons.Default.CheckCircle else Icons.Default.Cancel,
                                        null,
                                        tint = if (testSuccess) Color(0xFF81C784) else Color(0xFFEF9A9A),
                                        modifier = Modifier.size(14.dp)
                                    )
                                    Text(
                                        if (testSuccess) "Raw fetch preview (${aiFetchType.uppercase()}):" else "Fetch failed (${aiFetchType.uppercase()}):",
                                        fontSize = 11.sp, fontWeight = FontWeight.SemiBold,
                                        color = if (testSuccess) Color(0xFF81C784) else Color(0xFFEF9A9A)
                                    )
                                }
                                Text(
                                    res, fontSize = 10.sp, fontFamily = FontFamily.Monospace,
                                    color = Color.White.copy(alpha = 0.75f), lineHeight = 14.sp
                                )

                                // Action buttons for fetched data
                                if (testSuccess) {
                                    Spacer(Modifier.height(4.dp))
                                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                                        // "What can be fetched?" button
                                        Surface(
                                            shape = RoundedCornerShape(8.dp),
                                            color = Color(0xFF7C4DFF).copy(alpha = 0.2f),
                                            modifier = Modifier.weight(1f).clickable {
                                                if (aiRawContent != null && !aiValuesLoading) {
                                                    aiValuesLoading = true
                                                    aiValuesList = null
                                                    scope.launch {
                                                        try {
                                                            val router = LlmRouter.getInstance(context)
                                                            val valuesPrompt = """List ALL extractable data points from this fetched web content. For each data point, show its name and current value.

FETCHED CONTENT:
${aiRawContent!!.take(2500)}

List every piece of useful data found (names, numbers, dates, prices, titles, etc). Format as a simple list:
• data_name: actual_value
If a value appears to be 0, empty, or a placeholder (not real data), mark it as "[NOT AVAILABLE - needs WebView]"."""
                                                            val result = router.rawGenerate(valuesPrompt, maxTokens = 1500)
                                                            aiValuesList = result
                                                        } catch (e: Exception) {
                                                            aiValuesList = "Error: ${e.message}"
                                                        }
                                                        aiValuesLoading = false
                                                    }
                                                }
                                            }
                                        ) {
                                            Row(modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                                                verticalAlignment = Alignment.CenterVertically,
                                                horizontalArrangement = Arrangement.Center) {
                                                if (aiValuesLoading) {
                                                    CircularProgressIndicator(modifier = Modifier.size(12.dp), color = Color(0xFF7C4DFF), strokeWidth = 1.5.dp)
                                                } else {
                                                    Icon(Icons.Default.AutoAwesome, null, tint = Color(0xFF7C4DFF), modifier = Modifier.size(13.dp))
                                                }
                                                Spacer(Modifier.width(4.dp))
                                                Text("What can be fetched?", fontSize = 10.sp, fontWeight = FontWeight.Bold, color = Color(0xFFB388FF))
                                            }
                                        }
                                        // Copy full data button
                                        Surface(
                                            shape = RoundedCornerShape(8.dp),
                                            color = if (copiedFetchData) Color(0xFF4CAF50).copy(alpha = 0.2f) else accentColor.copy(alpha = 0.15f),
                                            modifier = Modifier.clickable {
                                                val fullData = aiRawContent ?: res
                                                clipboardManager.setText(AnnotatedString(fullData))
                                                copiedFetchData = true
                                            }
                                        ) {
                                            Row(modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                                                verticalAlignment = Alignment.CenterVertically) {
                                                Icon(
                                                    if (copiedFetchData) Icons.Default.CheckCircle else Icons.Default.ContentCopy,
                                                    null, tint = if (copiedFetchData) Color(0xFF81C784) else accentColor, modifier = Modifier.size(13.dp))
                                                Spacer(Modifier.width(4.dp))
                                                Text(
                                                    if (copiedFetchData) "Copied!" else "Copy all",
                                                    fontSize = 10.sp, fontWeight = FontWeight.Bold,
                                                    color = if (copiedFetchData) Color(0xFF81C784) else accentColor)
                                            }
                                        }
                                    }
                                }
                            }
                        }

                        // AI Values list
                        aiValuesList?.let { values ->
                            Spacer(Modifier.height(4.dp))
                            Surface(shape = RoundedCornerShape(10.dp), color = Color(0xFF1A1A2E)) {
                                Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                        Icon(Icons.Default.List, null, tint = Color(0xFFB388FF), modifier = Modifier.size(14.dp))
                                        Text("Extractable data points:", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Color(0xFFB388FF))
                                    }
                                    Text(values, fontSize = 10.sp, fontFamily = FontFamily.Monospace,
                                        color = Color(0xFFC9D1D9), lineHeight = 14.sp)
                                    // Warn about unavailable values
                                    if (values.contains("[NOT AVAILABLE") || values.contains("WebView")) {
                                        Spacer(Modifier.height(4.dp))
                                        Text("Some values marked [NOT AVAILABLE] need WebView fetch to retrieve. Switch fetch method above and retry.",
                                            fontSize = 10.sp, color = Color(0xFFFFB74D), lineHeight = 14.sp)
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // ── Section: Extraction Prompt (editable) ─────────────────────
            item { SectionLabel("Extraction Prompt", accentColor) }

            item {
                Text("What to extract", fontSize = 12.sp, color = accentColor, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(6.dp))
                OutlinedTextField(
                    value = extractPrompt,
                    onValueChange = { extractPrompt = it },
                    placeholder = {
                        Text("Auto-filled by AI Analyze, or type your own extraction prompt",
                            fontSize = 12.sp, color = Color.White.copy(alpha = 0.3f))
                    },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    minLines = 2, maxLines = 4,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = accentColor,
                        unfocusedBorderColor = Color.White.copy(alpha = 0.15f),
                        focusedTextColor = Color.White, unfocusedTextColor = Color.White,
                        cursorColor = accentColor,
                        focusedContainerColor = Color.White.copy(alpha = 0.04f),
                        unfocusedContainerColor = Color.White.copy(alpha = 0.02f)
                    )
                )
                Spacer(Modifier.height(4.dp))
                Text("This prompt tells the AI what to extract from the fetched page content. Edit to customize your output format.",
                    fontSize = 10.sp, color = Color.White.copy(alpha = 0.35f), lineHeight = 14.sp)
            }

            // Quick prompt templates
            item {
                val templates = listOf(
                    "extract the top 5 headlines and one-line summaries",
                    "find any price changes and highlight them",
                    "list all new job postings with title and company",
                    "summarize the main news stories in 3 bullet points",
                    "extract any new announcements or releases"
                )
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Quick templates", fontSize = 11.sp, color = Color.White.copy(alpha = 0.4f))
                    templates.forEach { t ->
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = accentColor.copy(alpha = 0.08f),
                            modifier = Modifier.fillMaxWidth().clickable { extractPrompt = t }
                        ) {
                            Text("› $t", modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                                fontSize = 11.sp, color = accentColor.copy(alpha = 0.8f))
                        }
                    }
                }
            }

            // ── Test Fetch (manual, all methods) ──────────────────────────
            item { SectionLabel("Manual Test", accentColor) }

            item {
                val canTest = url.startsWith("http://") || url.startsWith("https://")
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedButton(
                        onClick = {
                            if (!canTest) {
                                urlError = "Enter a valid URL first to test"
                                return@OutlinedButton
                            }
                            testResult = null
                            testLoading = true
                            scope.launch {
                                val r = when (aiFetchType) {
                                    "rss" -> try {
                                        RssFeedTool().execute(mapOf("url" to url.trim(), "limit" to "10"))
                                    } catch (_: Exception) { WebFetchTool().execute(mapOf("url" to url.trim())) }
                                    "webview" -> try {
                                        WebViewTool(context).execute(mapOf("action" to "fetch", "url" to url.trim(), "wait_ms" to "3000"))
                                    } catch (_: Exception) { WebFetchTool().execute(mapOf("url" to url.trim())) }
                                    else -> WebFetchTool().execute(mapOf("url" to url.trim()))
                                }
                                testSuccess = r.success
                                testResult = if (r.success) {
                                    r.content.take(600).trimEnd() +
                                    if (r.content.length > 600) "\n\n…(${r.content.length} chars total)" else ""
                                } else {
                                    r.error ?: "Unknown error"
                                }
                                testLoading = false
                            }
                        },
                        enabled = !testLoading,
                        modifier = Modifier.fillMaxWidth().height(46.dp),
                        shape = RoundedCornerShape(12.dp),
                        border = androidx.compose.foundation.BorderStroke(
                            1.dp, if (canTest) accentColor.copy(alpha = 0.6f) else Color.White.copy(alpha = 0.15f)
                        ),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = accentColor)
                    ) {
                        if (testLoading) {
                            CircularProgressIndicator(modifier = Modifier.size(16.dp),
                                color = accentColor, strokeWidth = 2.dp)
                            Spacer(Modifier.width(8.dp))
                            Text("Fetching via ${aiFetchType.uppercase()}…", fontWeight = FontWeight.SemiBold)
                        } else {
                            Icon(Icons.Default.PlayArrow, null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Test Fetch (${aiFetchType.uppercase()})", fontWeight = FontWeight.SemiBold)
                        }
                    }
                }
            }

            // ── Save button ────────────────────────────────────────────────
            item {
                Spacer(Modifier.height(8.dp))
                Button(
                    onClick = {
                        // Validate
                        var ok = true
                        if (name.isBlank()) { nameError = "Name is required"; ok = false }
                        if (url.isBlank() || (!url.startsWith("http://") && !url.startsWith("https://"))) {
                            urlError = "Enter a valid URL starting with http:// or https://"; ok = false
                        }
                        if (selectedBots.isEmpty() && channelTargets.isEmpty()) {
                            deliveryError = "Select at least one bot or add a channel target"; ok = false
                        }
                        // Validate channel target IDs
                        for (target in channelTargets) {
                            if (target.channel == "telegram" && target.chatId.trim().toLongOrNull() == null) {
                                deliveryError = "Telegram Chat ID must be a number"; ok = false; break
                            }
                            if (target.chatId.isBlank()) {
                                deliveryError = "Chat ID cannot be empty for channel targets"; ok = false; break
                            }
                        }
                        if (!ok) return@Button

                        val intervalMins = intervalRaw.toIntOrNull()?.coerceAtLeast(5) ?: 60
                        // Resolve template {query} placeholder
                        var finalUrl = url.trim()
                        var finalPrompt = extractPrompt.trim()
                        var finalName = name.trim()
                        if (isTemplate && template != null) {
                            val query = when {
                                customInput.isNotBlank() -> customInput.trim()
                                selectedSubs.isNotEmpty() -> selectedSubs.joinToString("+")
                                else -> ""
                            }
                            // Human-readable label for prompt (e.g. "Technology" or "Cricket, Football")
                            val queryLabel = when {
                                customInput.isNotBlank() -> customInput.trim()
                                selectedSubs.isNotEmpty() -> selectedSubs.joinToString(", ")
                                else -> ""
                            }
                            if (query.isNotBlank()) {
                                val encoded = java.net.URLEncoder.encode(query, "UTF-8")
                                finalUrl = finalUrl.replace("{query}", encoded)
                                // Replace {query} in extract prompt with readable label
                                finalPrompt = finalPrompt.replace("{query}", queryLabel)
                                finalName = if (selectedSubs.size == 1) "$finalName — ${selectedSubs.first()}"
                                            else if (customInput.isNotBlank()) "$finalName — $customInput"
                                            else finalName
                            } else {
                                finalUrl = finalUrl.replace("{query}", "")
                                finalPrompt = finalPrompt.replace("{query}", "")
                            }
                        }

                        // Encode delivery: channel = comma-separated bots, chatId = channel:id pairs
                        val channelStr = selectedBots.joinToString(",")
                        val chatIdStr = encodeChannelTargets(channelTargets)

                        val config = (existing ?: AgentConfig(
                            id = UUID.randomUUID().toString(),
                            name = "", type = AgentManager.TYPE_WEB_SCRAPER,
                            url = "", intervalMinutes = 60, channel = "",
                            chatId = "", extractPrompt = "", onlyOnChange = true,
                            enabled = true, createdAt = System.currentTimeMillis(),
                            lastRunAt = 0L, lastContentHash = 0, lastStatus = "Not run yet",
                            templateId = template?.id,
                            apiSource = template?.apiSource
                        )).copy(
                            name = finalName,
                            url = finalUrl,
                            intervalMinutes = intervalMins,
                            channel = channelStr,
                            chatId = chatIdStr,
                            extractPrompt = finalPrompt,
                            onlyOnChange = onlyOnChange,
                            apiSource = template?.apiSource ?: existing?.apiSource,
                            fetchType = aiFetchType,
                            formatPreview = aiFormatPreview
                        )
                        onSave(config)
                    },
                    modifier = Modifier.fillMaxWidth().height(52.dp),
                    shape = RoundedCornerShape(14.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = accentColor)
                ) {
                    Icon(if (isEdit) Icons.Default.Save else Icons.Default.Add, null)
                    Spacer(Modifier.width(8.dp))
                    Text(if (isEdit) "Save Changes" else "Create Agent",
                        fontWeight = FontWeight.Bold, fontSize = 15.sp)
                }
                Spacer(Modifier.height(16.dp))
            }
        }
    }

    // Reset copied state
    LaunchedEffect(copiedFetchData) {
        if (copiedFetchData) { kotlinx.coroutines.delay(2000); copiedFetchData = false }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Data types for delivery
// ─────────────────────────────────────────────────────────────────────────────

private data class BotDef(val id: String, val label: String, val emoji: String)
private data class ChannelDef(val id: String, val label: String, val emoji: String, val placeholder: String)
data class ChannelTarget(val channel: String, val chatId: String)

/** Encode channel targets to chatId string: "discord:123,slack:C456" */
fun encodeChannelTargets(targets: List<ChannelTarget>): String =
    targets.joinToString(",") { "${it.channel}:${it.chatId}" }

/** Parse chatId string back to channel targets. Handles legacy single-ID format. */
fun parseChannelTargets(chatIdStr: String): List<ChannelTarget> {
    if (chatIdStr.isBlank()) return emptyList()
    return chatIdStr.split(",").mapNotNull { part ->
        val trimmed = part.trim()
        if (trimmed.isBlank()) return@mapNotNull null
        val colonIdx = trimmed.indexOf(":")
        if (colonIdx > 0) {
            ChannelTarget(trimmed.substring(0, colonIdx), trimmed.substring(colonIdx + 1))
        } else {
            // Legacy format: bare ID — ignore (was the old single chatId)
            null
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Add Channel form (inline)
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun AddChannelForm(
    channelOptions: List<ChannelDef>,
    knownChatIds: Map<String, List<String>>,
    accent: Color,
    onAdd: (channel: String, chatId: String) -> Unit,
    onCancel: () -> Unit
) {
    var selectedChannel by remember { mutableStateOf(channelOptions.first().id) }
    var chatIdInput by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }

    val chDef = channelOptions.firstOrNull { it.id == selectedChannel } ?: channelOptions.first()

    Surface(
        shape = RoundedCornerShape(12.dp),
        color = accent.copy(alpha = 0.06f),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Add Channel Target", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Color.White)

            // Channel selector
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                channelOptions.forEach { ch ->
                    FilterChip(
                        selected = selectedChannel == ch.id,
                        onClick = { selectedChannel = ch.id; chatIdInput = ""; error = null },
                        label = { Text("${ch.emoji} ${ch.label.split(" ").first()}", fontSize = 10.sp) },
                        shape = RoundedCornerShape(6.dp),
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = accent,
                            selectedLabelColor = Color.White
                        )
                    )
                }
            }

            // Chat ID input
            OutlinedTextField(
                value = chatIdInput,
                onValueChange = { chatIdInput = it; error = null },
                placeholder = { Text(chDef.placeholder, fontSize = 12.sp, color = Color.White.copy(alpha = 0.3f)) },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(10.dp),
                singleLine = true,
                isError = error != null,
                leadingIcon = { Text(chDef.emoji, fontSize = 14.sp) },
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = accent,
                    unfocusedBorderColor = Color.White.copy(alpha = 0.15f),
                    errorBorderColor = Color(0xFFEF5350),
                    focusedTextColor = Color.White, unfocusedTextColor = Color.White,
                    cursorColor = accent,
                    focusedContainerColor = Color.White.copy(alpha = 0.04f),
                    unfocusedContainerColor = Color.White.copy(alpha = 0.02f)
                )
            )
            error?.let {
                Text(it, fontSize = 10.sp, color = Color(0xFFEF5350))
            }

            // Known chat ID suggestions
            val ids = knownChatIds[selectedChannel]
            if (!ids.isNullOrEmpty()) {
                Text("Recent chats:", fontSize = 10.sp, color = Color.White.copy(alpha = 0.4f))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    ids.take(4).forEach { id ->
                        Surface(
                            shape = RoundedCornerShape(6.dp),
                            color = if (chatIdInput == id) Color(0xFF2E7D32).copy(alpha = 0.3f) else accent.copy(alpha = 0.1f),
                            modifier = Modifier.clickable { chatIdInput = id; error = null }
                        ) {
                            Text(id, modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                fontSize = 10.sp, fontFamily = FontFamily.Monospace,
                                color = if (chatIdInput == id) Color(0xFF81C784) else accent)
                        }
                    }
                }
            }

            // Add / Cancel buttons
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = onCancel,
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(8.dp),
                    border = androidx.compose.foundation.BorderStroke(1.dp, Color.White.copy(alpha = 0.2f)),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White.copy(alpha = 0.6f))
                ) { Text("Cancel", fontSize = 12.sp) }
                Button(
                    onClick = {
                        if (chatIdInput.isBlank()) {
                            error = "Chat ID is required"; return@Button
                        }
                        if (selectedChannel == "telegram" && chatIdInput.trim().toLongOrNull() == null) {
                            error = "Telegram Chat ID must be a number"; return@Button
                        }
                        onAdd(selectedChannel, chatIdInput.trim())
                    },
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(8.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = accent)
                ) {
                    Icon(Icons.Default.Add, null, modifier = Modifier.size(14.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Add", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Sub-components
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun SectionLabel(text: String, accent: Color) {
    Row(verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        HorizontalDivider(modifier = Modifier.weight(1f), color = accent.copy(alpha = 0.2f))
        Text(text.uppercase(), fontSize = 10.sp, fontWeight = FontWeight.Bold, color = accent)
        HorizontalDivider(modifier = Modifier.weight(1f), color = accent.copy(alpha = 0.2f))
    }
}

@Composable
private fun FormField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    error: String?,
    accent: Color,
    leadingIcon: (@Composable () -> Unit)? = null
) {
    Column {
        Text(label, fontSize = 12.sp, color = accent, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(6.dp))
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            placeholder = { Text(placeholder, fontSize = 12.sp, color = Color.White.copy(alpha = 0.3f)) },
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp),
            singleLine = true,
            isError = error != null,
            leadingIcon = leadingIcon,
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = accent,
                unfocusedBorderColor = Color.White.copy(alpha = 0.15f),
                errorBorderColor = Color(0xFFEF5350),
                focusedTextColor = Color.White, unfocusedTextColor = Color.White,
                cursorColor = accent,
                focusedContainerColor = Color.White.copy(alpha = 0.04f),
                unfocusedContainerColor = Color.White.copy(alpha = 0.02f)
            )
        )
        if (error != null) {
            Text(error, fontSize = 10.sp, color = Color(0xFFEF5350),
                modifier = Modifier.padding(top = 2.dp, start = 4.dp))
        }
    }
}

@Composable
private fun IntervalPicker(value: String, onChange: (String) -> Unit, accent: Color) {
    val presets = listOf("15" to "15m", "30" to "30m", "60" to "1h",
                         "360" to "6h", "720" to "12h", "1440" to "24h")
    Column {
        Text("Check interval", fontSize = 12.sp, color = accent, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
            presets.forEach { (mins, label) ->
                FilterChip(
                    selected = value == mins,
                    onClick = { onChange(mins) },
                    label = { Text(label, fontSize = 11.sp) },
                    shape = RoundedCornerShape(8.dp),
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = accent,
                        selectedLabelColor = Color.White
                    ),
                    modifier = Modifier.weight(1f)
                )
            }
        }
        Spacer(Modifier.height(6.dp))
        OutlinedTextField(
            value = value,
            onValueChange = onChange,
            placeholder = { Text("Custom minutes…", fontSize = 12.sp, color = Color.White.copy(alpha = 0.3f)) },
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp),
            singleLine = true,
            suffix = { Text("min", fontSize = 12.sp, color = Color.White.copy(alpha = 0.4f)) },
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = accent,
                unfocusedBorderColor = Color.White.copy(alpha = 0.15f),
                focusedTextColor = Color.White, unfocusedTextColor = Color.White,
                cursorColor = accent,
                focusedContainerColor = Color.White.copy(alpha = 0.04f),
                unfocusedContainerColor = Color.White.copy(alpha = 0.02f)
            )
        )
        Text("Minimum 5 minutes.", fontSize = 10.sp, color = Color.White.copy(alpha = 0.3f),
            modifier = Modifier.padding(top = 2.dp, start = 4.dp))
    }
}

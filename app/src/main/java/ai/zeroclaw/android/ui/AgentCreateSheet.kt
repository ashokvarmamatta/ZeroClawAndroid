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
import androidx.compose.ui.platform.LocalContext
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

    // Detect connected channels and known chat IDs
    var connectedChannels by remember { mutableStateOf(mapOf<String, Boolean>()) }
    var knownChatIds by remember { mutableStateOf(mapOf<String, List<String>>()) }
    LaunchedEffect(Unit) {
        val prefs = AppSettings.dataStore(context).data.first()
        connectedChannels = mapOf(
            "telegram" to (prefs[AppSettings.KEY_TELEGRAM_TOKEN] ?: "").isNotBlank(),
            "discord" to (prefs[AppSettings.KEY_DISCORD_TOKEN] ?: "").isNotBlank(),
            "slack" to (prefs[AppSettings.KEY_SLACK_TOKEN] ?: "").isNotBlank(),
            "whatsapp" to (prefs[AppSettings.KEY_TWILIO_SID] ?: "").isNotBlank(),
            "email" to false
        )
        knownChatIds = try { LlmRouter.getInstance(context).getKnownChatIds() } catch (_: Exception) { emptyMap() }
    }

    // Auto-select first connected channel for new agents
    val defaultChannel = remember(connectedChannels) {
        if (existing != null) existing.channel
        else connectedChannels.entries.firstOrNull { it.value }?.key ?: "telegram"
    }

    // Form state — pre-fill from template or existing agent
    var name          by remember { mutableStateOf(existing?.name ?: template?.name ?: "") }
    var url           by remember { mutableStateOf(existing?.url ?: template?.url ?: "") }
    var intervalRaw   by remember { mutableStateOf((existing?.intervalMinutes ?: template?.intervalMinutes ?: 60).toString()) }
    var channel       by remember { mutableStateOf(existing?.channel ?: defaultChannel) }
    var chatId        by remember { mutableStateOf(existing?.chatId ?: "") }
    var extractPrompt by remember { mutableStateOf(existing?.extractPrompt ?: template?.extractPrompt ?: "") }
    var onlyOnChange  by remember { mutableStateOf(existing?.onlyOnChange ?: template?.onlyOnChange ?: true) }
    var customInput   by remember { mutableStateOf("") }
    var selectedSubs  by remember { mutableStateOf(setOf<String>()) }

    // Auto-fill chatId when channel changes and we have known IDs
    LaunchedEffect(channel, knownChatIds) {
        if (chatId.isBlank() && existing == null) {
            val ids = knownChatIds[channel]
            if (!ids.isNullOrEmpty()) chatId = ids.first()
        }
    }

    // Validation errors
    var nameError   by remember { mutableStateOf<String?>(null) }
    var urlError    by remember { mutableStateOf<String?>(null) }
    var chatIdError by remember { mutableStateOf<String?>(null) }

    // Test fetch state
    var testLoading  by remember { mutableStateOf(false) }
    var testResult   by remember { mutableStateOf<String?>(null) }
    var testSuccess  by remember { mutableStateOf(false) }

    val channels = listOf("telegram", "discord", "slack", "whatsapp", "email")
    val accentColor = Color(0xFF1E88E5)

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

            item {
                Text("Channel", fontSize = 12.sp, color = accentColor, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(4.dp))
                Text("Connected channels shown first", fontSize = 10.sp, color = Color.White.copy(alpha = 0.35f))
                Spacer(Modifier.height(8.dp))
                // Sort: connected channels first
                val sortedChannels = remember(connectedChannels) {
                    channels.sortedByDescending { connectedChannels[it] == true }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth()) {
                    sortedChannels.forEach { ch ->
                        val connected = connectedChannels[ch] == true
                        FilterChip(
                            selected = channel == ch,
                            onClick = {
                                channel = ch
                                // Auto-fill chat ID from known IDs when switching
                                if (chatId.isBlank() || chatId == (knownChatIds[channel]?.firstOrNull() ?: "")) {
                                    chatId = knownChatIds[ch]?.firstOrNull() ?: ""
                                }
                            },
                            label = {
                                Text(
                                    "${channelEmoji(ch)} ${ch.replaceFirstChar { it.uppercase() }}" +
                                    if (connected) " ✓" else "",
                                    fontSize = 11.sp
                                )
                            },
                            shape = RoundedCornerShape(8.dp),
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = if (connected) Color(0xFF2E7D32) else accentColor,
                                selectedLabelColor = Color.White
                            )
                        )
                    }
                }
            }

            item {
                val isConnected = connectedChannels[channel] == true
                FormField(
                    label = chatIdLabel(channel) + if (isConnected) " (optional)" else "",
                    value = chatId,
                    onValueChange = { chatId = it; chatIdError = null },
                    placeholder = if (isConnected) "Optional — uses default bot chat" else chatIdPlaceholder(channel),
                    error = chatIdError,
                    accent = accentColor,
                    leadingIcon = { Text(channelEmoji(channel), fontSize = 16.sp) }
                )
                // Show known chat IDs as quick-fill suggestions
                val channelIds = knownChatIds[channel]
                if (!channelIds.isNullOrEmpty()) {
                    Spacer(Modifier.height(6.dp))
                    Text("Recent chats (tap to use):", fontSize = 10.sp, color = Color.White.copy(alpha = 0.4f))
                    Spacer(Modifier.height(4.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        channelIds.take(5).forEach { id ->
                            Surface(
                                shape = RoundedCornerShape(6.dp),
                                color = if (chatId == id) Color(0xFF2E7D32).copy(alpha = 0.3f)
                                        else accentColor.copy(alpha = 0.1f),
                                modifier = Modifier.clickable { chatId = id; chatIdError = null }
                            ) {
                                Text(id, modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                    fontSize = 10.sp, fontFamily = FontFamily.Monospace,
                                    color = if (chatId == id) Color(0xFF81C784) else accentColor)
                            }
                        }
                    }
                } else if (connectedChannels[channel] == true) {
                    Spacer(Modifier.height(4.dp))
                    Text("${channelEmoji(channel)} ${channel.replaceFirstChar { it.uppercase() }} is connected — send a message to the bot first, then the Chat ID will auto-appear here.",
                        fontSize = 10.sp, color = Color(0xFF81C784).copy(alpha = 0.7f), lineHeight = 14.sp)
                }
            }

            // ── Section: Extraction (optional) ─────────────────────────────
            item { SectionLabel("Content Extraction (optional)", accentColor) }

            item {
                Text("What to extract", fontSize = 12.sp, color = accentColor, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(6.dp))
                OutlinedTextField(
                    value = extractPrompt,
                    onValueChange = { extractPrompt = it },
                    placeholder = {
                        Text("e.g. extract the top 5 headlines and their summaries",
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
                Text("Leave blank to push the full page text. When set, the AI will extract only the relevant content before delivering.",
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

            // ── Test Fetch ─────────────────────────────────────────────────
            item { SectionLabel("Test", accentColor) }

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
                                val r = WebFetchTool().execute(mapOf("url" to url.trim()))
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
                            Text("Fetching…", fontWeight = FontWeight.SemiBold)
                        } else {
                            Icon(Icons.Default.PlayArrow, null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Test Fetch URL", fontWeight = FontWeight.SemiBold)
                        }
                    }

                    // Result preview
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
                                        if (testSuccess) "Fetch successful — content preview:" else "Fetch failed:",
                                        fontSize = 11.sp, fontWeight = FontWeight.SemiBold,
                                        color = if (testSuccess) Color(0xFF81C784) else Color(0xFFEF9A9A)
                                    )
                                }
                                Text(
                                    res,
                                    fontSize = 10.sp,
                                    fontFamily = FontFamily.Monospace,
                                    color = Color.White.copy(alpha = 0.75f),
                                    lineHeight = 14.sp
                                )
                            }
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
                        if (chatId.isBlank() && connectedChannels[channel] != true) {
                            chatIdError = "${chatIdLabel(channel)} is required"; ok = false
                        } else if (chatId.isNotBlank() && channel == "telegram" && chatId.contains(":") && chatId.length > 20) {
                            chatIdError = "This looks like a bot token, not a Chat ID. Your Chat ID is a number like 123456789 or -1001234567890"; ok = false
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
                            if (query.isNotBlank()) {
                                val encoded = java.net.URLEncoder.encode(query, "UTF-8")
                                finalUrl = finalUrl.replace("{query}", encoded)
                                finalName = if (selectedSubs.size == 1) "$finalName — ${selectedSubs.first()}"
                                            else if (customInput.isNotBlank()) "$finalName — $customInput"
                                            else finalName
                            } else {
                                finalUrl = finalUrl.replace("{query}", "")
                            }
                        }
                        val config = (existing ?: AgentConfig(
                            id = UUID.randomUUID().toString(),
                            name = "", type = AgentManager.TYPE_WEB_SCRAPER,
                            url = "", intervalMinutes = 60, channel = "",
                            chatId = "", extractPrompt = "", onlyOnChange = true,
                            enabled = true, createdAt = System.currentTimeMillis(),
                            lastRunAt = 0L, lastContentHash = 0, lastStatus = "Not run yet",
                            templateId = template?.id
                        )).copy(
                            name = finalName,
                            url = finalUrl,
                            intervalMinutes = intervalMins,
                            channel = channel,
                            chatId = chatId.trim(),
                            extractPrompt = finalPrompt,
                            onlyOnChange = onlyOnChange
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

private fun chatIdLabel(channel: String) = when (channel) {
    "email"    -> "Recipient Email"
    "telegram" -> "Telegram Chat ID"
    "discord"  -> "Discord Channel ID"
    "slack"    -> "Slack Channel ID"
    "whatsapp" -> "WhatsApp Number"
    else       -> "Chat / Channel ID"
}

private fun chatIdPlaceholder(channel: String) = when (channel) {
    "email"    -> "user@example.com"
    "telegram" -> "123456789 or -1001234567890 (NOT your bot token)"
    "discord"  -> "1234567890123456789"
    "slack"    -> "#general or C1234567890"
    "whatsapp" -> "+1234567890"
    else       -> "ID or address"
}

private fun channelEmoji(channel: String) = when (channel.lowercase()) {
    "telegram"  -> "✈️"
    "discord"   -> "🎮"
    "slack"     -> "💼"
    "whatsapp"  -> "💬"
    "email"     -> "📧"
    else        -> "📤"
}

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
import ai.zeroclaw.android.tools.WebFetchTool
import java.util.UUID
import kotlinx.coroutines.launch

// ─────────────────────────────────────────────────────────────────────────────
// AgentCreateSheet — bottom sheet form for creating / editing a Web Scraper agent
// ─────────────────────────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AgentCreateSheet(
    existing: AgentConfig? = null,
    onDismiss: () -> Unit,
    onSave: (AgentConfig) -> Unit
) {
    val isEdit = existing != null
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // Form state
    var name          by remember { mutableStateOf(existing?.name ?: "") }
    var url           by remember { mutableStateOf(existing?.url ?: "") }
    var intervalRaw   by remember { mutableStateOf(existing?.intervalMinutes?.toString() ?: "60") }
    var channel       by remember { mutableStateOf(existing?.channel ?: "telegram") }
    var chatId        by remember { mutableStateOf(existing?.chatId ?: "") }
    var extractPrompt by remember { mutableStateOf(existing?.extractPrompt ?: "") }
    var onlyOnChange  by remember { mutableStateOf(existing?.onlyOnChange ?: true) }

    // Connected channels state — which connected bots are checked for delivery
    var selectedConnectedChannels by remember {
        mutableStateOf(existing?.connectedChannels?.toSet() ?: emptySet())
    }

    // Validation errors
    var nameError     by remember { mutableStateOf<String?>(null) }
    var urlError      by remember { mutableStateOf<String?>(null) }
    var chatIdError   by remember { mutableStateOf<String?>(null) }
    var deliveryError by remember { mutableStateOf<String?>(null) }

    // Test fetch state
    var testLoading  by remember { mutableStateOf(false) }
    var testResult   by remember { mutableStateOf<String?>(null) }
    var testSuccess  by remember { mutableStateOf(false) }

    // ── Load connected channels from AppSettings ────────────────────────────
    data class ConnectedChannelInfo(val key: String, val label: String, val connected: Boolean)

    var connectedChannelInfos by remember { mutableStateOf<List<ConnectedChannelInfo>>(emptyList()) }

    LaunchedEffect(Unit) {
        val settings = ai.zeroclaw.android.data.AppSettings(context).getAll()
        connectedChannelInfos = listOf(
            ConnectedChannelInfo("telegram",  "Telegram",  settings.telegramToken.isNotBlank()),
            ConnectedChannelInfo("discord",   "Discord",   settings.discordToken.isNotBlank()),
            ConnectedChannelInfo("slack",     "Slack",     settings.slackToken.isNotBlank()),
            ConnectedChannelInfo("whatsapp",  "WhatsApp",  settings.twilioSid.isNotBlank()),
            ConnectedChannelInfo("signal",    "Signal",    settings.signalApiUrl.isNotBlank()),
            ConnectedChannelInfo("matrix",    "Matrix",    settings.matrixConfig.isNotBlank()),
            ConnectedChannelInfo("irc",       "IRC",       settings.ircConfig.isNotBlank()),
            ConnectedChannelInfo("teams",     "Teams",     settings.teamsConfig.isNotBlank()),
            ConnectedChannelInfo("twitch",    "Twitch",    settings.twitchConfig.isNotBlank()),
            ConnectedChannelInfo("line",      "LINE",      settings.lineToken.isNotBlank()),
            ConnectedChannelInfo("webchat",   "WebChat",   settings.webChatEnabled),
        )
    }

    val channels = listOf("telegram", "discord", "slack", "whatsapp", "email")
    val accentColor = Color(0xFF1E88E5)
    val connectedColor = Color(0xFF4CAF50)

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
                    Text("🕷️", fontSize = 28.sp)
                    Column {
                        Text(if (isEdit) "Edit Agent" else "New Web Scraper Agent",
                            fontWeight = FontWeight.Bold, fontSize = 18.sp, color = Color.White)
                        Text("Periodically scrapes a URL and pushes updates to your channel",
                            fontSize = 11.sp, color = Color.White.copy(alpha = 0.5f))
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

            // ── Section: Delivery — Connected Bots ─────────────────────────
            item { SectionLabel("Delivery", accentColor) }

            // Connected Bots subsection
            item {
                val hasAnyConnected = connectedChannelInfos.any { it.connected }

                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Icon(Icons.Default.Hub, null, tint = connectedColor,
                            modifier = Modifier.size(16.dp))
                        Text("Connected Bots", fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold, color = Color.White)
                    }
                    Text("Select from bots you've already configured in Settings",
                        fontSize = 10.sp, color = Color.White.copy(alpha = 0.4f))

                    if (!hasAnyConnected) {
                        Surface(
                            shape = RoundedCornerShape(10.dp),
                            color = Color.White.copy(alpha = 0.04f)
                        ) {
                            Row(modifier = Modifier.padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Icon(Icons.Default.Info, null,
                                    tint = Color.White.copy(alpha = 0.4f),
                                    modifier = Modifier.size(16.dp))
                                Text("No bots connected yet — set up channels in Settings first",
                                    fontSize = 11.sp, color = Color.White.copy(alpha = 0.5f))
                            }
                        }
                    }

                    connectedChannelInfos.forEach { info ->
                        val isChecked = info.key in selectedConnectedChannels

                        Surface(
                            shape = RoundedCornerShape(10.dp),
                            color = if (info.connected) {
                                if (isChecked) connectedColor.copy(alpha = 0.12f)
                                else Color.White.copy(alpha = 0.04f)
                            } else Color.White.copy(alpha = 0.02f),
                            modifier = Modifier.fillMaxWidth().then(
                                if (info.connected) Modifier.clickable {
                                    deliveryError = null
                                    selectedConnectedChannels = if (isChecked) {
                                        selectedConnectedChannels - info.key
                                    } else {
                                        selectedConnectedChannels + info.key
                                    }
                                } else Modifier
                            )
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                Checkbox(
                                    checked = isChecked,
                                    onCheckedChange = if (info.connected) { checked ->
                                        deliveryError = null
                                        selectedConnectedChannels = if (checked) {
                                            selectedConnectedChannels + info.key
                                        } else {
                                            selectedConnectedChannels - info.key
                                        }
                                    } else null,
                                    enabled = info.connected,
                                    colors = CheckboxDefaults.colors(
                                        checkedColor = connectedColor,
                                        checkmarkColor = Color.White
                                    ),
                                    modifier = Modifier.size(20.dp)
                                )
                                Text(
                                    channelEmoji(info.key),
                                    fontSize = 18.sp,
                                    modifier = Modifier.width(24.dp)
                                )
                                Text(
                                    info.label,
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Medium,
                                    color = if (info.connected) Color.White else Color.White.copy(alpha = 0.35f),
                                    modifier = Modifier.weight(1f)
                                )
                                Surface(
                                    shape = RoundedCornerShape(6.dp),
                                    color = if (info.connected)
                                        connectedColor.copy(alpha = 0.15f)
                                    else Color.White.copy(alpha = 0.06f)
                                ) {
                                    Text(
                                        if (info.connected) "Connected" else "Not set up",
                                        fontSize = 9.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        color = if (info.connected)
                                            connectedColor
                                        else Color.White.copy(alpha = 0.3f),
                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // ── Custom delivery (optional manual channel + chatId) ─────────
            item {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Icon(Icons.Default.Send, null, tint = accentColor,
                            modifier = Modifier.size(14.dp))
                        Text("Custom Delivery (optional)", fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold, color = accentColor)
                    }
                    Text("Send to a specific chat/channel ID, in addition to the connected bots above",
                        fontSize = 10.sp, color = Color.White.copy(alpha = 0.4f))
                }
            }

            item {
                Text("Channel", fontSize = 12.sp, color = accentColor.copy(alpha = 0.7f), fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth()) {
                    channels.forEach { ch ->
                        FilterChip(
                            selected = channel == ch,
                            onClick = { channel = ch },
                            label = { Text("${channelEmoji(ch)} ${ch.replaceFirstChar { it.uppercase() }}", fontSize = 11.sp) },
                            shape = RoundedCornerShape(8.dp),
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = accentColor,
                                selectedLabelColor = Color.White
                            )
                        )
                    }
                }
            }

            item {
                FormField(
                    label = chatIdLabel(channel),
                    value = chatId,
                    onValueChange = { chatId = it; chatIdError = null; deliveryError = null },
                    placeholder = chatIdPlaceholder(channel),
                    error = chatIdError,
                    accent = accentColor,
                    leadingIcon = { Text(channelEmoji(channel), fontSize = 16.sp) }
                )
            }

            // Delivery error (no target selected)
            if (deliveryError != null) {
                item {
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = Color(0xFFEF5350).copy(alpha = 0.1f)
                    ) {
                        Row(modifier = Modifier.padding(10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Icon(Icons.Default.Warning, null,
                                tint = Color(0xFFEF5350), modifier = Modifier.size(14.dp))
                            Text(deliveryError!!, fontSize = 11.sp, color = Color(0xFFEF5350))
                        }
                    }
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
                        // Validate delivery: need at least one connected channel OR a manual chatId
                        val hasConnected = selectedConnectedChannels.isNotEmpty()
                        val hasManual = chatId.isNotBlank()
                        if (!hasConnected && !hasManual) {
                            deliveryError = "Select at least one connected bot or enter a custom Chat ID"
                            ok = false
                        }
                        if (hasManual && channel == "telegram" && chatId.contains(":") && chatId.length > 20) {
                            chatIdError = "This looks like a bot token, not a Chat ID. Your Chat ID is a number like 123456789 or -1001234567890"
                            ok = false
                        }
                        if (!ok) return@Button

                        val intervalMins = intervalRaw.toIntOrNull()?.coerceAtLeast(5) ?: 60
                        val config = (existing ?: AgentConfig(
                            id = UUID.randomUUID().toString(),
                            name = "", type = AgentManager.TYPE_WEB_SCRAPER,
                            url = "", intervalMinutes = 60, channel = "",
                            chatId = "", connectedChannels = emptyList(),
                            extractPrompt = "", onlyOnChange = true,
                            enabled = true, createdAt = System.currentTimeMillis(),
                            lastRunAt = 0L, lastContentHash = 0, lastStatus = "Not run yet"
                        )).copy(
                            name = name.trim(),
                            url = url.trim(),
                            intervalMinutes = intervalMins,
                            channel = if (hasManual) channel else "",
                            chatId = chatId.trim(),
                            connectedChannels = selectedConnectedChannels.toList(),
                            extractPrompt = extractPrompt.trim(),
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
    "signal"    -> "🔒"
    "matrix"    -> "🟢"
    "irc"       -> "💻"
    "teams"     -> "🏢"
    "twitch"    -> "🎬"
    "line"      -> "🟩"
    "webchat"   -> "🌐"
    else        -> "📤"
}

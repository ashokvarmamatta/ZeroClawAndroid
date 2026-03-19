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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ai.zeroclaw.android.agents.AgentConfig
import ai.zeroclaw.android.agents.AgentManager
import java.util.UUID

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

    // Form state
    var name          by remember { mutableStateOf(existing?.name ?: "") }
    var url           by remember { mutableStateOf(existing?.url ?: "") }
    var intervalRaw   by remember { mutableStateOf(existing?.intervalMinutes?.toString() ?: "60") }
    var channel       by remember { mutableStateOf(existing?.channel ?: "telegram") }
    var chatId        by remember { mutableStateOf(existing?.chatId ?: "") }
    var extractPrompt by remember { mutableStateOf(existing?.extractPrompt ?: "") }
    var onlyOnChange  by remember { mutableStateOf(existing?.onlyOnChange ?: true) }

    // Validation errors
    var nameError   by remember { mutableStateOf<String?>(null) }
    var urlError    by remember { mutableStateOf<String?>(null) }
    var chatIdError by remember { mutableStateOf<String?>(null) }

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

            // ── Section: Delivery ──────────────────────────────────────────
            item { SectionLabel("Delivery", accentColor) }

            item {
                Text("Channel", fontSize = 12.sp, color = accentColor, fontWeight = FontWeight.SemiBold)
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
                    onValueChange = { chatId = it; chatIdError = null },
                    placeholder = chatIdPlaceholder(channel),
                    error = chatIdError,
                    accent = accentColor,
                    leadingIcon = { Text(channelEmoji(channel), fontSize = 16.sp) }
                )
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
                        if (chatId.isBlank()) { chatIdError = "${chatIdLabel(channel)} is required"; ok = false }
                        if (!ok) return@Button

                        val intervalMins = intervalRaw.toIntOrNull()?.coerceAtLeast(5) ?: 60
                        val config = (existing ?: AgentConfig(
                            id = UUID.randomUUID().toString(),
                            name = "", type = AgentManager.TYPE_WEB_SCRAPER,
                            url = "", intervalMinutes = 60, channel = "",
                            chatId = "", extractPrompt = "", onlyOnChange = true,
                            enabled = true, createdAt = System.currentTimeMillis(),
                            lastRunAt = 0L, lastContentHash = 0, lastStatus = "Not run yet"
                        )).copy(
                            name = name.trim(),
                            url = url.trim(),
                            intervalMinutes = intervalMins,
                            channel = channel,
                            chatId = chatId.trim(),
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
    "telegram" -> "-1001234567890"
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

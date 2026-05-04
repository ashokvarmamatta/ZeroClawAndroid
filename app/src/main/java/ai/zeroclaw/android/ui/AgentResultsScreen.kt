package ai.zeroclaw.android.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.AutoDelete
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Inbox
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ai.zeroclaw.android.agents.AgentManager
import ai.zeroclaw.android.data.AgentResultDatabase
import ai.zeroclaw.android.data.AgentResultEntity
import kotlinx.coroutines.launch
import org.json.JSONArray
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * Phase 190 — Per-agent Results screen.
 *
 * Lists every persisted run for a single agent. Designed to be readable, not clamped:
 *  • Gradient header card with stats (total / success / failed) — matches AgentsScreen palette.
 *  • Sticky filter chips: All • ✅ Success • ⚠️ Partial • ❌ Failed • 💤 Skipped.
 *  • Each result is a tappable card. Collapsed view shows status, time, delivery, preview.
 *  • Tap to expand → full extracted content + error + raw URL + run id, with a copy button.
 *
 * Loaded from [AgentResultDatabase]. Sorted newest-first. The screen is launched from
 * the agent card in [AgentsScreen] and from the notification PendingIntent in
 * [ai.zeroclaw.android.infra.RichNotifications.showAgentCompletionNotification].
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AgentResultsScreen(agentId: String, onBack: () -> Unit) {
    val context = LocalContext.current
    val resultDao = remember { AgentResultDatabase.getInstance(context).agentResultDao() }
    val agentManager = remember { AgentManager.getInstance(context) }
    val scope = rememberCoroutineScope()

    var results by remember { mutableStateOf<List<AgentResultEntity>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var filter by remember { mutableStateOf<String?>(null) }   // null == all
    val agent = remember(agentId) { agentManager.loadAll().firstOrNull { it.id == agentId } }
    val agentName = agent?.name ?: "Agent"

    suspend fun reload() {
        loading = true
        results = resultDao.getByAgentId(agentId, limit = 500, offset = 0)
        loading = false
    }

    LaunchedEffect(agentId) { reload() }

    val filtered = remember(results, filter) {
        if (filter == null) results else results.filter { it.status == filter }
    }
    val total = results.size
    val successCount = remember(results) { results.count { it.status == "success" } }
    val failedCount = remember(results) { results.count { it.status == "failed" } }
    val partialCount = remember(results) { results.count { it.status == "partial" } }
    val skippedCount = remember(results) { results.count { it.status == "skipped" } }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(agentName, fontWeight = FontWeight.Bold, fontSize = 16.sp, maxLines = 1)
                        Text(
                            if (total == 0) "No runs yet" else "$total run${if (total == 1) "" else "s"}",
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f)
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, "Back",
                             tint = MaterialTheme.colorScheme.onPrimaryContainer)
                    }
                },
                actions = {
                    if (total > 0) {
                        IconButton(onClick = {
                            scope.launch {
                                resultDao.deleteOlderThan(System.currentTimeMillis() - 7L * 24 * 3600 * 1000)
                                reload()
                            }
                        }) {
                            Icon(Icons.Default.AutoDelete, "Delete results older than 7 days",
                                 tint = MaterialTheme.colorScheme.onPrimaryContainer)
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer
                )
            )
        }
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {

            // ── Header summary card ────────────────────────────────
            ResultsSummaryHeader(
                total = total,
                success = successCount,
                partial = partialCount,
                failed = failedCount,
                skipped = skippedCount,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp)
            )

            // ── Filter chips ───────────────────────────────────────
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                FilterChipPill("All", total, filter == null) { filter = null }
                FilterChipPill("✅ Success", successCount, filter == "success", successColor) { filter = "success" }
                FilterChipPill("⚠️ Partial", partialCount, filter == "partial", partialColor) { filter = "partial" }
                FilterChipPill("❌ Failed", failedCount, filter == "failed", failedColor) { filter = "failed" }
                FilterChipPill("💤 Skipped", skippedCount, filter == "skipped", skippedColor) { filter = "skipped" }
            }

            Spacer(Modifier.height(6.dp))

            // ── Body ───────────────────────────────────────────────
            when {
                loading -> LoadingCenter()
                total == 0 -> EmptyResultsState(agentName)
                filtered.isEmpty() -> NoMatchesState()
                else -> LazyColumn(
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    items(filtered, key = { it.id }) { result ->
                        ResultCard(result = result)
                    }
                    item { Spacer(Modifier.height(40.dp)) }
                }
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Header summary
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun ResultsSummaryHeader(
    total: Int, success: Int, partial: Int, failed: Int, skipped: Int,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier,
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = Color.Transparent)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    Brush.linearGradient(listOf(Color(0xFF0A2540), Color(0xFF0D3B6E))),
                    RoundedCornerShape(18.dp)
                )
                .padding(20.dp)
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Text("Run history", fontWeight = FontWeight.Bold, fontSize = 13.sp,
                     color = Color.White.copy(alpha = 0.55f), letterSpacing = 1.5.sp)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    StatColumn("Total", total.toString(), Color(0xFF64B5F6))
                    StatColumn("Success", success.toString(), successColor)
                    StatColumn("Partial", partial.toString(), partialColor)
                    StatColumn("Failed", failed.toString(), failedColor)
                    StatColumn("Skipped", skipped.toString(), skippedColor)
                }
            }
        }
    }
}

@Composable
private fun StatColumn(label: String, value: String, color: Color) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, fontSize = 22.sp, fontWeight = FontWeight.Bold, color = color)
        Text(label.uppercase(), fontSize = 9.sp,
             color = Color.White.copy(alpha = 0.55f), letterSpacing = 1.sp)
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Filter chip
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun FilterChipPill(
    label: String,
    count: Int,
    selected: Boolean,
    accent: Color = MaterialTheme.colorScheme.primary,
    onClick: () -> Unit
) {
    val bg = if (selected) accent.copy(alpha = 0.22f) else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
    val border = if (selected) accent else MaterialTheme.colorScheme.outline.copy(alpha = 0.3f)
    val textColor = if (selected) accent else MaterialTheme.colorScheme.onSurface
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = bg,
        border = BorderStroke(1.dp, border),
        modifier = Modifier.clickable(onClick = onClick)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(label, fontSize = 12.sp, color = textColor,
                 fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium)
            if (count > 0) {
                Surface(
                    shape = CircleShape,
                    color = accent.copy(alpha = if (selected) 0.4f else 0.18f)
                ) {
                    Text(count.toString(),
                         modifier = Modifier.padding(horizontal = 7.dp, vertical = 1.dp),
                         fontSize = 10.sp, fontWeight = FontWeight.Bold,
                         color = if (selected) Color.White else accent)
                }
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Result card
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun ResultCard(result: AgentResultEntity) {
    var expanded by remember { mutableStateOf(false) }
    val accent = statusAccent(result.status)
    val emoji = statusEmoji(result.status)
    val clipboard = LocalClipboardManager.current

    val deliveredList = remember(result.deliveredTo) { parseDeliveredJson(result.deliveredTo) }

    Card(
        modifier = Modifier.fillMaxWidth().clickable { expanded = !expanded },
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = Color.Transparent)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    Brush.linearGradient(listOf(Color(0xFF11151E), Color(0xFF1A1F2A))),
                    RoundedCornerShape(16.dp)
                )
        ) {
            // Left status accent stripe
            Box(
                modifier = Modifier
                    .width(4.dp)
                    .fillMaxHeight()
                    .background(accent, RoundedCornerShape(topStart = 16.dp, bottomStart = 16.dp))
                    .align(Alignment.CenterStart)
            )

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 16.dp, end = 14.dp, top = 14.dp, bottom = 14.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                // Header — status badge + timestamp + expand caret
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = accent.copy(alpha = 0.18f),
                            border = BorderStroke(1.dp, accent.copy(alpha = 0.3f))
                        ) {
                            Row(modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                                horizontalArrangement = Arrangement.spacedBy(4.dp),
                                verticalAlignment = Alignment.CenterVertically) {
                                Text(emoji, fontSize = 13.sp)
                                Text(result.status.replaceFirstChar { it.uppercase() },
                                     fontSize = 11.sp, color = accent,
                                     fontWeight = FontWeight.Bold,
                                     letterSpacing = 0.5.sp)
                            }
                        }
                        Column {
                            Text(formatRelative(result.timestamp),
                                 fontSize = 12.sp, color = Color.White,
                                 fontWeight = FontWeight.Medium)
                            Text(formatAbsolute(result.timestamp),
                                 fontSize = 9.sp, color = Color.White.copy(alpha = 0.4f),
                                 fontFamily = FontFamily.Monospace)
                        }
                    }
                    Icon(
                        if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                        contentDescription = if (expanded) "Collapse" else "Expand",
                        tint = Color.White.copy(alpha = 0.5f)
                    )
                }

                // Delivery channels
                if (deliveredList.isNotEmpty()) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        modifier = Modifier.horizontalScroll(rememberScrollState())
                    ) {
                        deliveredList.forEach { tag ->
                            Surface(
                                shape = RoundedCornerShape(6.dp),
                                color = Color(0xFF64B5F6).copy(alpha = 0.13f)
                            ) {
                                Text(tag,
                                     modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                                     fontSize = 10.sp, color = Color(0xFF90CAF9),
                                     fontWeight = FontWeight.Medium)
                            }
                        }
                        if (result.usedApi) {
                            Surface(
                                shape = RoundedCornerShape(6.dp),
                                color = Color(0xFFCE93D8).copy(alpha = 0.13f)
                            ) {
                                Text("⚡ Direct API",
                                     modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                                     fontSize = 10.sp, color = Color(0xFFCE93D8),
                                     fontWeight = FontWeight.Medium)
                            }
                        }
                    }
                }

                // Preview line — first ~3 lines of extracted content (collapsed only)
                if (!expanded && result.extractedContent.isNotBlank()) {
                    Text(
                        result.extractedContent.lines()
                            .filter { it.isNotBlank() }
                            .take(3)
                            .joinToString("\n")
                            .take(220),
                        fontSize = 12.sp,
                        color = Color.White.copy(alpha = 0.78f),
                        lineHeight = 17.sp,
                        maxLines = 3
                    )
                } else if (!expanded && result.errorMessage.isNotBlank()) {
                    Text(result.errorMessage.take(220),
                         fontSize = 12.sp,
                         color = failedColor.copy(alpha = 0.85f),
                         maxLines = 2)
                }

                // Expanded body
                AnimatedVisibility(
                    visible = expanded,
                    enter = expandVertically(),
                    exit = shrinkVertically()
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        HorizontalDivider(color = Color.White.copy(alpha = 0.06f))

                        // URL / source
                        if (result.url.isNotBlank()) {
                            DetailRow(label = "Source", value = result.url, mono = true)
                        }

                        // Extracted content (the meat)
                        if (result.extractedContent.isNotBlank()) {
                            DetailBlock(
                                label = "Extracted content",
                                value = result.extractedContent,
                                accent = accent,
                                onCopy = { clipboard.setText(AnnotatedString(result.extractedContent)) }
                            )
                        }

                        // Error (only on failed)
                        if (result.errorMessage.isNotBlank()) {
                            DetailBlock(
                                label = "Error",
                                value = result.errorMessage,
                                accent = failedColor,
                                onCopy = { clipboard.setText(AnnotatedString(result.errorMessage)) }
                            )
                        }

                        // Raw fetched content (only when we have it AND it's different from extracted)
                        if (result.rawContent.isNotBlank() && result.rawContent != result.extractedContent) {
                            DetailBlock(
                                label = "Raw fetched (truncated)",
                                value = result.rawContent,
                                accent = Color(0xFF78909C),
                                onCopy = { clipboard.setText(AnnotatedString(result.rawContent)) },
                                collapsedLines = 6
                            )
                        }

                        // Run id footer
                        if (result.runId.isNotBlank()) {
                            Text("run id: ${result.runId}",
                                 fontSize = 9.sp, color = Color.White.copy(alpha = 0.3f),
                                 fontFamily = FontFamily.Monospace)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DetailRow(label: String, value: String, mono: Boolean = false) {
    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Text(label.uppercase(), fontSize = 9.sp,
             color = Color.White.copy(alpha = 0.45f), letterSpacing = 1.2.sp)
        Text(value, fontSize = 12.sp, color = Color.White.copy(alpha = 0.85f),
             fontFamily = if (mono) FontFamily.Monospace else FontFamily.Default,
             lineHeight = 16.sp)
    }
}

@Composable
private fun DetailBlock(
    label: String,
    value: String,
    accent: Color,
    onCopy: () -> Unit,
    collapsedLines: Int = Int.MAX_VALUE
) {
    var fullyOpen by remember { mutableStateOf(collapsedLines == Int.MAX_VALUE) }
    val displayValue = if (fullyOpen) value
                       else value.lines().take(collapsedLines).joinToString("\n")
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
            modifier = Modifier.fillMaxWidth()) {
            Text(label.uppercase(), fontSize = 9.sp,
                 color = accent, letterSpacing = 1.2.sp,
                 fontWeight = FontWeight.Bold)
            IconButton(onClick = onCopy, modifier = Modifier.size(28.dp)) {
                Icon(Icons.Default.ContentCopy, "Copy",
                     tint = Color.White.copy(alpha = 0.55f),
                     modifier = Modifier.size(14.dp))
            }
        }
        Surface(
            shape = RoundedCornerShape(10.dp),
            color = accent.copy(alpha = 0.06f),
            border = BorderStroke(1.dp, accent.copy(alpha = 0.18f))
        ) {
            Column(modifier = Modifier.padding(12.dp)) {
                Text(displayValue,
                     fontSize = 12.sp,
                     color = Color.White.copy(alpha = 0.9f),
                     lineHeight = 18.sp,
                     fontFamily = FontFamily.Monospace)
                if (!fullyOpen && value.lines().size > collapsedLines) {
                    Spacer(Modifier.height(8.dp))
                    Text("Tap to show full ↓",
                         fontSize = 11.sp, color = accent,
                         fontWeight = FontWeight.Medium,
                         modifier = Modifier.clickable { fullyOpen = true })
                }
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Empty / loading states
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun LoadingCenter() {
    Box(modifier = Modifier.fillMaxSize().padding(48.dp), contentAlignment = Alignment.Center) {
        CircularProgressIndicator()
    }
}

@Composable
private fun EmptyResultsState(agentName: String) {
    Column(
        modifier = Modifier.fillMaxSize().padding(32.dp).verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Spacer(Modifier.height(48.dp))
        Icon(Icons.Default.Inbox, null,
             tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
             modifier = Modifier.size(72.dp))
        Spacer(Modifier.height(16.dp))
        Text("No runs yet for $agentName", fontWeight = FontWeight.Bold,
             fontSize = 18.sp, textAlign = TextAlign.Center)
        Spacer(Modifier.height(6.dp))
        Text("Run the agent or wait for its scheduled interval — results will appear here.",
             fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
             textAlign = TextAlign.Center, lineHeight = 18.sp)
    }
}

@Composable
private fun NoMatchesState() {
    Column(
        modifier = Modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text("🔍", fontSize = 36.sp)
        Spacer(Modifier.height(12.dp))
        Text("No results match this filter", fontWeight = FontWeight.Bold)
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Helpers
// ─────────────────────────────────────────────────────────────────────────────

private val successColor = Color(0xFF66BB6A)
private val partialColor = Color(0xFFFFB74D)
private val failedColor  = Color(0xFFEF5350)
private val skippedColor = Color(0xFF90A4AE)

private fun statusAccent(status: String): Color = when (status) {
    "success" -> successColor
    "partial" -> partialColor
    "failed"  -> failedColor
    "skipped" -> skippedColor
    else -> Color(0xFF64B5F6)
}

private fun statusEmoji(status: String): String = when (status) {
    "success" -> "✅"
    "partial" -> "⚠️"
    "failed"  -> "❌"
    "skipped" -> "💤"
    else -> "•"
}

private fun parseDeliveredJson(raw: String): List<String> {
    if (raw.isBlank()) return emptyList()
    return runCatching {
        val arr = JSONArray(raw)
        (0 until arr.length()).map { arr.getString(it) }
    }.getOrDefault(emptyList())
}

private fun formatRelative(ts: Long): String {
    val now = System.currentTimeMillis()
    val diff = now - ts
    return when {
        diff < 0 -> "in the future"
        diff < 60_000 -> "just now"
        diff < 3600_000 -> "${diff / 60_000}m ago"
        diff < 24 * 3600_000 -> {
            val cal = Calendar.getInstance().apply { timeInMillis = ts }
            val today = Calendar.getInstance()
            if (cal.get(Calendar.DAY_OF_YEAR) == today.get(Calendar.DAY_OF_YEAR) &&
                cal.get(Calendar.YEAR) == today.get(Calendar.YEAR)) {
                "${diff / 3600_000}h ago"
            } else {
                "Yesterday ${SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(ts))}"
            }
        }
        diff < 7L * 24 * 3600_000 -> "${diff / (24 * 3600_000)}d ago"
        else -> SimpleDateFormat("MMM d, yyyy", Locale.getDefault()).format(Date(ts))
    }
}

private fun formatAbsolute(ts: Long): String =
    SimpleDateFormat("MMM d • HH:mm:ss", Locale.getDefault()).format(Date(ts))

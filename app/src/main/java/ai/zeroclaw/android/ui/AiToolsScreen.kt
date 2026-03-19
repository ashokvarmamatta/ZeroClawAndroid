package ai.zeroclaw.android.ui

import androidx.compose.animation.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ai.zeroclaw.android.tools.Tool
import ai.zeroclaw.android.tools.ToolSystem
import kotlinx.coroutines.launch

// ─────────────────────────────────────────────────────────────────────────────
// Tool metadata — emoji + category for every registered tool
// ─────────────────────────────────────────────────────────────────────────────

private enum class ToolCategory(val label: String, val emoji: String, val color: Color) {
    CORE("Core AI", "🧠", Color(0xFF00BCD4)),
    DEVICE("Device", "📱", Color(0xFF4CAF50)),
    EXTERNAL("External Services", "🌐", Color(0xFFFF9800)),
    ADVANCED("Advanced / Agent", "⚡", Color(0xFFE91E63))
}

private data class ToolMeta(val emoji: String, val category: ToolCategory)

private val TOOL_META: Map<String, ToolMeta> = mapOf(
    "web_search"     to ToolMeta("🔍", ToolCategory.CORE),
    "web_fetch"      to ToolMeta("🌐", ToolCategory.CORE),
    "memory"         to ToolMeta("🧠", ToolCategory.CORE),
    "pdf_read"       to ToolMeta("📄", ToolCategory.CORE),
    "summarize"      to ToolMeta("📝", ToolCategory.CORE),
    "translate"      to ToolMeta("🌍", ToolCategory.CORE),
    "calculator"     to ToolMeta("🔢", ToolCategory.CORE),
    "qr_code"        to ToolMeta("📷", ToolCategory.CORE),
    "clipboard"      to ToolMeta("📋", ToolCategory.CORE),
    "status"         to ToolMeta("ℹ️", ToolCategory.CORE),
    "image_analysis" to ToolMeta("🖼️", ToolCategory.CORE),
    "calendar"       to ToolMeta("📅", ToolCategory.DEVICE),
    "contacts"       to ToolMeta("👥", ToolCategory.DEVICE),
    "location"       to ToolMeta("📍", ToolCategory.DEVICE),
    "text_to_speech" to ToolMeta("🔊", ToolCategory.DEVICE),
    "speech_to_text" to ToolMeta("🎤", ToolCategory.DEVICE),
    "file_manager"   to ToolMeta("📁", ToolCategory.DEVICE),
    "bookmark"       to ToolMeta("🔖", ToolCategory.DEVICE),
    "webview"        to ToolMeta("🖥️", ToolCategory.DEVICE),
    "cron"           to ToolMeta("⏰", ToolCategory.DEVICE),
    "media_pipeline" to ToolMeta("🎬", ToolCategory.DEVICE),
    "weather"        to ToolMeta("🌤️", ToolCategory.EXTERNAL),
    "github"         to ToolMeta("🐱", ToolCategory.EXTERNAL),
    "rss_feed"       to ToolMeta("📡", ToolCategory.EXTERNAL),
    "image_gen"      to ToolMeta("🎨", ToolCategory.EXTERNAL),
    "spotify"        to ToolMeta("🎵", ToolCategory.EXTERNAL),
    "smart_home"     to ToolMeta("🏠", ToolCategory.EXTERNAL),
    "brave_search"   to ToolMeta("🦁", ToolCategory.EXTERNAL),
    "notion"         to ToolMeta("📝", ToolCategory.EXTERNAL),
    "email"          to ToolMeta("📧", ToolCategory.EXTERNAL),
    "composio"       to ToolMeta("🔗", ToolCategory.ADVANCED),
    "delegate"       to ToolMeta("🤝", ToolCategory.ADVANCED),
    "spawn"          to ToolMeta("🚀", ToolCategory.ADVANCED),
    "message"        to ToolMeta("💬", ToolCategory.ADVANCED),
    "pushover"       to ToolMeta("📬", ToolCategory.ADVANCED),
    "a2a"            to ToolMeta("🤖", ToolCategory.ADVANCED),
)

private fun toolEmoji(name: String) = TOOL_META[name]?.emoji ?: "🔧"
private fun toolCategory(name: String) = TOOL_META[name]?.category ?: ToolCategory.CORE

// ─────────────────────────────────────────────────────────────────────────────
// Main Screen
// ─────────────────────────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AiToolsScreen(
    onBack: () -> Unit,
    onNavigateToInfo: (String) -> Unit = {}
) {
    val context = LocalContext.current
    val toolSystem = remember { ToolSystem.getInstance(context) }
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }

    var toolStates by remember { mutableStateOf<Map<String, Boolean>>(emptyMap()) }
    var selectedTab by remember { mutableIntStateOf(0) }
    var searchQuery by remember { mutableStateOf("") }

    LaunchedEffect(Unit) {
        val states = mutableMapOf<String, Boolean>()
        for (tool in toolSystem.allTools()) {
            states[tool.name] = toolSystem.isEnabled(tool.name)
        }
        toolStates = states
    }

    val enabledCount = toolStates.count { it.value }
    val totalCount = toolStates.size

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("AI Tools", fontWeight = FontWeight.Bold)
                        Text(
                            "$enabledCount / $totalCount enabled",
                            fontSize = 12.sp,
                            color = if (enabledCount > 0) Color(0xFF4CAF50)
                                    else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = { onNavigateToInfo("tools") }) {
                        Icon(Icons.Default.Info, contentDescription = "Learn more about tools")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        }
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            // Tab row
            TabRow(
                selectedTabIndex = selectedTab,
                containerColor = MaterialTheme.colorScheme.surface,
                contentColor = MaterialTheme.colorScheme.primary
            ) {
                Tab(
                    selected = selectedTab == 0,
                    onClick = { selectedTab = 0 },
                    text = { Text("🔧  Tools") }
                )
                Tab(
                    selected = selectedTab == 1,
                    onClick = { selectedTab = 1 },
                    text = { Text("🏗️  How It Works") }
                )
            }

            when (selectedTab) {
                0 -> ToolsListTab(
                    allTools = toolSystem.allTools(),
                    toolStates = toolStates,
                    searchQuery = searchQuery,
                    onSearchChange = { searchQuery = it },
                    onToggle = { name, enabled ->
                        toolStates = toolStates + (name to enabled)
                        scope.launch {
                            toolSystem.setEnabled(name, enabled)
                            snackbarHostState.showSnackbar(
                                if (enabled) "${toolEmoji(name)} ${name.replace("_", " ")} enabled"
                                else "${toolEmoji(name)} ${name.replace("_", " ")} disabled",
                                duration = SnackbarDuration.Short
                            )
                        }
                    }
                )
                1 -> FlowDiagramTab(
                    toolStates = toolStates,
                    allTools = toolSystem.allTools()
                )
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Tab 1 — Tool list with search + categories
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun ToolsListTab(
    allTools: List<Tool>,
    toolStates: Map<String, Boolean>,
    searchQuery: String,
    onSearchChange: (String) -> Unit,
    onToggle: (String, Boolean) -> Unit
) {
    val filtered = allTools.filter {
        searchQuery.isBlank() ||
        it.name.contains(searchQuery, ignoreCase = true) ||
        it.description.contains(searchQuery, ignoreCase = true)
    }

    val byCategory = ToolCategory.entries.associateWith { cat ->
        filtered.filter { toolCategory(it.name) == cat }
    }

    val enabledCount = toolStates.count { it.value }
    val totalCount = toolStates.size

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        // Search bar
        item {
            OutlinedTextField(
                value = searchQuery,
                onValueChange = onSearchChange,
                placeholder = { Text("Search tools…") },
                leadingIcon = { Icon(Icons.Default.Search, null) },
                trailingIcon = {
                    if (searchQuery.isNotEmpty()) {
                        IconButton(onClick = { onSearchChange("") }) {
                            Icon(Icons.Default.Close, null)
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(14.dp),
                singleLine = true
            )
        }

        // Summary bar
        item {
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    "$enabledCount of $totalCount tools active",
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                // Mini progress pill
                Surface(
                    shape = RoundedCornerShape(20.dp),
                    color = Color(0xFF4CAF50).copy(alpha = 0.15f)
                ) {
                    Text(
                        if (enabledCount == 0) "All off" else "✓ $enabledCount on",
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = if (enabledCount == 0) MaterialTheme.colorScheme.onSurfaceVariant
                                else Color(0xFF4CAF50)
                    )
                }
            }
        }

        // Per-category sections
        byCategory.forEach { (category, tools) ->
            if (tools.isEmpty()) return@forEach

            val catEnabled = tools.count { toolStates[it.name] == true }

            item(key = "header_${category.name}") {
                Spacer(Modifier.height(8.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(category.emoji, fontSize = 16.sp)
                    Text(
                        category.label,
                        fontWeight = FontWeight.Bold,
                        fontSize = 14.sp,
                        color = category.color
                    )
                    Spacer(Modifier.weight(1f))
                    Text(
                        "$catEnabled/${tools.size}",
                        fontSize = 11.sp,
                        color = if (catEnabled > 0) category.color
                                else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }

            item(key = "card_${category.name}") {
                Card(
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)
                    ),
                    border = BorderStroke(1.dp, category.color.copy(alpha = 0.15f))
                ) {
                    Column(modifier = Modifier.padding(vertical = 4.dp)) {
                        tools.forEachIndexed { index, tool ->
                            val isEnabled = toolStates[tool.name] ?: false
                            ToolRow(
                                tool = tool,
                                emoji = toolEmoji(tool.name),
                                accentColor = category.color,
                                isEnabled = isEnabled,
                                onToggle = { onToggle(tool.name, it) }
                            )
                            if (index < tools.lastIndex) {
                                HorizontalDivider(
                                    modifier = Modifier.padding(start = 56.dp),
                                    color = MaterialTheme.colorScheme.outline.copy(alpha = 0.12f)
                                )
                            }
                        }
                    }
                }
            }
        }

        item { Spacer(Modifier.height(16.dp)) }
    }
}

@Composable
private fun ToolRow(
    tool: Tool,
    emoji: String,
    accentColor: Color,
    isEnabled: Boolean,
    onToggle: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Emoji badge
        Surface(
            shape = CircleShape,
            color = if (isEnabled) accentColor.copy(alpha = 0.15f)
                    else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
            modifier = Modifier.size(38.dp)
        ) {
            Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                Text(emoji, fontSize = 18.sp)
            }
        }

        Spacer(Modifier.width(12.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                tool.name.replace("_", " ").replaceFirstChar { it.uppercase() },
                fontWeight = FontWeight.Medium,
                fontSize = 14.sp,
                color = if (isEnabled) MaterialTheme.colorScheme.onSurface
                        else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
            )
            Text(
                tool.description.take(90) + if (tool.description.length > 90) "…" else "",
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(
                    alpha = if (isEnabled) 0.8f else 0.4f
                ),
                lineHeight = 15.sp,
                maxLines = 2
            )
        }

        Spacer(Modifier.width(8.dp))

        Switch(
            checked = isEnabled,
            onCheckedChange = onToggle,
            colors = SwitchDefaults.colors(
                checkedTrackColor = accentColor,
                checkedThumbColor = Color.White
            )
        )
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Tab 2 — Architecture flow diagram
// ─────────────────────────────────────────────────────────────────────────────

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FlowDiagramTab(
    toolStates: Map<String, Boolean>,
    allTools: List<Tool>
) {
    val anyToolEnabled = toolStates.any { it.value }
    val enabledTools = allTools.filter { toolStates[it.name] == true }

    // Group enabled tools by category for the diagram
    val enabledByCategory = ToolCategory.entries.associateWith { cat ->
        enabledTools.filter { toolCategory(it.name) == cat }
    }.filter { it.value.isNotEmpty() }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(0.dp)
    ) {
        // Legend
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
            )
        ) {
            Row(
                modifier = Modifier.padding(12.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("ℹ️", fontSize = 14.sp)
                Text(
                    "Toggle tools in the Tools tab to see how your prompt pipeline changes in real-time.",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    lineHeight = 16.sp
                )
            }
        }

        Spacer(Modifier.height(20.dp))

        // ── Node 1: User Message ──────────────────────────────────────────
        FlowNode(
            emoji = "👤",
            title = "User Message",
            subtitle = "Prompt sent via Telegram, Discord, WhatsApp, or any connected channel",
            active = true,
            accentColor = Color(0xFF9C27B0)
        )

        FlowArrow(active = true)

        // ── Node 2: Intent Router ─────────────────────────────────────────
        FlowNode(
            emoji = "🧭",
            title = "Intent Router",
            subtitle = "Classifies the message — detects if a tool call, command, or plain chat",
            active = true,
            accentColor = Color(0xFF3F51B5)
        )

        FlowArrow(active = true)

        // ── Node 3: LLM Provider ──────────────────────────────────────────
        FlowNode(
            emoji = "🤖",
            title = "LLM Provider",
            subtitle = "Claude / GPT / Gemini / Offline model generates a response or tool call request",
            active = true,
            accentColor = Color(0xFF00BCD4),
            badge = if (!anyToolEnabled) "DIRECT RESPONSE" else null
        ) {
            if (!anyToolEnabled) {
                Spacer(Modifier.height(8.dp))
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = Color(0xFF444444).copy(alpha = 0.4f),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        "No tools enabled — the LLM answers directly from training data only.\nEnable tools in the Tools tab to unlock real-time data.",
                        modifier = Modifier.padding(10.dp),
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        lineHeight = 16.sp
                    )
                }
            }
        }

        FlowArrow(active = anyToolEnabled, label = if (anyToolEnabled) "tool call" else "skipped")

        // ── Node 4: Tool Dispatcher ───────────────────────────────────────
        FlowNode(
            emoji = "🔧",
            title = "Tool Dispatcher",
            subtitle = if (anyToolEnabled)
                "${enabledTools.size} tool${if (enabledTools.size != 1) "s" else ""} active — LLM picks the right one based on intent"
            else
                "No tools active — this step is bypassed",
            active = anyToolEnabled,
            accentColor = Color(0xFFFF9800)
        ) {
            if (anyToolEnabled && enabledByCategory.isNotEmpty()) {
                Spacer(Modifier.height(10.dp))

                enabledByCategory.forEach { (category, tools) ->
                    Text(
                        category.label,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        color = category.color,
                        modifier = Modifier.padding(bottom = 4.dp)
                    )
                    FlowRow(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        tools.forEach { tool ->
                            ToolChip(
                                emoji = toolEmoji(tool.name),
                                name = tool.name.replace("_", " "),
                                color = category.color
                            )
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                }
            }
        }

        FlowArrow(active = anyToolEnabled, label = if (anyToolEnabled) "results" else "skipped")

        // ── Node 5: Tool Execution ────────────────────────────────────────
        FlowNode(
            emoji = "⚙️",
            title = "Tool Execution",
            subtitle = if (anyToolEnabled)
                "Tool runs in background (web search, calendar lookup, memory read…) and returns structured data"
            else
                "No tools to execute — step bypassed",
            active = anyToolEnabled,
            accentColor = Color(0xFF4CAF50)
        )

        FlowArrow(active = anyToolEnabled, label = if (anyToolEnabled) "data → LLM" else "skipped")

        // ── Node 6: Pass 2 — LLM Synthesis ───────────────────────────────
        FlowNode(
            emoji = "🔄",
            title = "Pass 2 — LLM Synthesis",
            subtitle = if (anyToolEnabled)
                "LLM receives tool results as context and generates a final, grounded answer"
            else
                "Skipped — no tool results to synthesize",
            active = anyToolEnabled,
            accentColor = Color(0xFF00BCD4)
        )

        FlowArrow(active = true)

        // ── Node 7: Final Response ────────────────────────────────────────
        FlowNode(
            emoji = "💬",
            title = "Final Response",
            subtitle = "Answer delivered back to the user through the same channel they used to ask",
            active = true,
            accentColor = Color(0xFF9C27B0)
        )

        Spacer(Modifier.height(24.dp))

        // ── Example prompt trace ──────────────────────────────────────────
        ExamplePromptCard(anyToolEnabled = anyToolEnabled, enabledTools = enabledTools)

        Spacer(Modifier.height(24.dp))
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Flow diagram helper composables
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun FlowNode(
    emoji: String,
    title: String,
    subtitle: String,
    active: Boolean,
    accentColor: Color,
    badge: String? = null,
    content: (@Composable ColumnScope.() -> Unit)? = null
) {
    val activeColor = accentColor
    val inactiveColor = Color(0xFF555555)
    val nodeColor = if (active) activeColor else inactiveColor

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (active) nodeColor.copy(alpha = 0.12f)
                            else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.15f)
        ),
        border = BorderStroke(
            width = if (active) 1.5.dp else 1.dp,
            color = nodeColor.copy(alpha = if (active) 0.5f else 0.2f)
        )
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                // Emoji bubble
                Surface(
                    shape = CircleShape,
                    color = nodeColor.copy(alpha = if (active) 0.2f else 0.08f),
                    modifier = Modifier.size(42.dp)
                ) {
                    Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                        Text(
                            emoji,
                            fontSize = 20.sp,
                            modifier = Modifier.alpha(if (active) 1f else 0.4f)
                        )
                    }
                }

                Spacer(Modifier.width(12.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        title,
                        fontWeight = FontWeight.Bold,
                        fontSize = 14.sp,
                        color = if (active) MaterialTheme.colorScheme.onSurface
                                else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.35f)
                    )
                    Text(
                        subtitle,
                        fontSize = 11.sp,
                        color = if (active) MaterialTheme.colorScheme.onSurfaceVariant
                                else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.35f),
                        lineHeight = 15.sp
                    )
                }

                if (badge != null && active) {
                    Spacer(Modifier.width(8.dp))
                    Surface(
                        shape = RoundedCornerShape(6.dp),
                        color = nodeColor.copy(alpha = 0.2f)
                    ) {
                        Text(
                            badge,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp),
                            fontSize = 9.sp,
                            fontWeight = FontWeight.Bold,
                            color = nodeColor
                        )
                    }
                }

                if (!active) {
                    Spacer(Modifier.width(8.dp))
                    Surface(
                        shape = RoundedCornerShape(6.dp),
                        color = Color(0xFF444444).copy(alpha = 0.3f)
                    ) {
                        Text(
                            "BYPASSED",
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp),
                            fontSize = 9.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFF888888)
                        )
                    }
                }
            }

            content?.invoke(this)
        }
    }
}

@Composable
private fun FlowArrow(active: Boolean = true, label: String? = null) {
    val color = if (active) Color(0xFF00BCD4) else Color(0xFF444444)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            modifier = Modifier
                .width(2.dp)
                .height(14.dp)
                .background(color.copy(alpha = if (active) 1f else 0.35f))
        )
        if (label != null) {
            Surface(
                shape = RoundedCornerShape(4.dp),
                color = color.copy(alpha = if (active) 0.15f else 0.07f)
            ) {
                Text(
                    label,
                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 1.dp),
                    fontSize = 9.sp,
                    color = color.copy(alpha = if (active) 0.9f else 0.4f),
                    fontWeight = FontWeight.SemiBold
                )
            }
        }
        Text(
            "▼",
            fontSize = 10.sp,
            color = color.copy(alpha = if (active) 1f else 0.35f)
        )
    }
}

@Composable
private fun ToolChip(emoji: String, name: String, color: Color) {
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = color.copy(alpha = 0.18f),
        border = BorderStroke(1.dp, color.copy(alpha = 0.35f))
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(emoji, fontSize = 12.sp)
            Text(
                name,
                fontSize = 10.sp,
                fontWeight = FontWeight.Medium,
                color = color
            )
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Example prompt trace card
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun ExamplePromptCard(anyToolEnabled: Boolean, enabledTools: List<Tool>) {
    val exampleTool = enabledTools.firstOrNull { it.name == "web_search" }
        ?: enabledTools.firstOrNull { it.name == "weather" }
        ?: enabledTools.firstOrNull()

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
        )
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                "📋  Example Trace",
                fontWeight = FontWeight.Bold,
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.primary
            )
            Spacer(Modifier.height(10.dp))

            if (!anyToolEnabled) {
                TraceRow("👤", "User", "\"What's the capital of France?\"", Color(0xFF9C27B0))
                TraceRow("🤖", "LLM", "Answers directly: \"Paris.\"", Color(0xFF00BCD4))
                TraceRow("💬", "Response", "\"Paris is the capital of France.\"", Color(0xFF9C27B0))
                Spacer(Modifier.height(8.dp))
                Text(
                    "Enable tools to let the AI fetch real-time data, read files, check your calendar, and more.",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    lineHeight = 15.sp
                )
            } else if (exampleTool != null) {
                val (prompt, toolDesc, result) = when (exampleTool.name) {
                    "web_search"  -> Triple("\"Latest AI news today\"", "Calls web_search(\"AI news today\")", "Returns top 5 articles")
                    "weather"     -> Triple("\"Weather in London?\"", "Calls weather(city=\"London\")", "Returns current conditions + forecast")
                    "memory"      -> Triple("\"What did I ask yesterday?\"", "Calls memory(query=\"yesterday\")", "Retrieves stored conversation context")
                    "calendar"    -> Triple("\"What's on my schedule?\"", "Calls calendar(date=\"today\")", "Returns today's events")
                    "email"       -> Triple("\"Check my inbox\"", "Calls email(action=\"list\")", "Returns unread messages")
                    "github"      -> Triple("\"Show open PRs in my repo\"", "Calls github(action=\"list_prs\")", "Returns open pull requests")
                    else          -> Triple("\"${exampleTool.name.replace("_", " ").replaceFirstChar { it.uppercase() }} request\"",
                                            "Calls ${exampleTool.name}(…)", "Returns structured result")
                }
                TraceRow("👤", "User", prompt, Color(0xFF9C27B0))
                TraceRow("🤖", "LLM Pass 1", "Decides a tool is needed", Color(0xFF00BCD4))
                TraceRow("🔧", "Tool", toolDesc, Color(0xFFFF9800))
                TraceRow("⚙️", "Execution", result, Color(0xFF4CAF50))
                TraceRow("🔄", "LLM Pass 2", "Synthesizes result into a natural answer", Color(0xFF00BCD4))
                TraceRow("💬", "Response", "Delivers final answer to the user", Color(0xFF9C27B0))
            }
        }
    }
}

@Composable
private fun TraceRow(emoji: String, label: String, text: String, color: Color) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.Top
    ) {
        Text(emoji, fontSize = 14.sp, modifier = Modifier.width(22.dp))
        Spacer(Modifier.width(6.dp))
        Surface(
            shape = RoundedCornerShape(4.dp),
            color = color.copy(alpha = 0.12f)
        ) {
            Text(
                label,
                modifier = Modifier.padding(horizontal = 5.dp, vertical = 2.dp),
                fontSize = 9.sp,
                fontWeight = FontWeight.Bold,
                color = color
            )
        }
        Spacer(Modifier.width(6.dp))
        Text(
            text,
            fontSize = 11.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
            lineHeight = 15.sp
        )
    }
}

package ai.zeroclaw.android.ui

import androidx.compose.animation.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.foundation.layout.heightIn
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ai.zeroclaw.android.agents.AgentConfig
import ai.zeroclaw.android.agents.AgentManager
import ai.zeroclaw.android.agents.AgentTemplate
import ai.zeroclaw.android.agents.AGENT_TEMPLATES
import ai.zeroclaw.android.agents.getTemplatesByCategory
import ai.zeroclaw.android.agents.WebScraperAgent
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*

// ─────────────────────────────────────────────────────────────────────────────
// Agents Screen — list + create agents
// ─────────────────────────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AgentsScreen(
    onBack: () -> Unit,
    onViewResults: (String) -> Unit = {}
) {
    val context = LocalContext.current
    val agentManager = remember { AgentManager.getInstance(context) }
    val scope = rememberCoroutineScope()

    var agents by remember { mutableStateOf(agentManager.loadAll()) }
    var showCreateSheet by remember { mutableStateOf(false) }
    var editAgent by remember { mutableStateOf<AgentConfig?>(null) }
    var showTemplates by remember { mutableStateOf(false) }
    var selectedTemplate by remember { mutableStateOf<AgentTemplate?>(null) }
    var showGuide by remember { mutableStateOf(false) }

    // Poll agent list every 5 s so lastStatus updates reflect live runs
    LaunchedEffect(Unit) {
        while (true) {
            agents = agentManager.loadAll()
            delay(5_000)
        }
    }

    // ── Create / Edit screen (full screen, replaces agents list) ──
    if (showCreateSheet || editAgent != null || selectedTemplate != null) {
        AgentCreateSheet(
            existing = editAgent,
            template = selectedTemplate,
            onDismiss = { showCreateSheet = false; editAgent = null; selectedTemplate = null },
            onSave = { config ->
                agentManager.save(config)
                agents = agentManager.loadAll()
                showCreateSheet = false
                editAgent = null
                selectedTemplate = null
            }
        )
        return
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text("🤖", fontSize = 22.sp)
                        Column {
                            Text("Agents", fontWeight = FontWeight.Bold)
                            Text("Autonomous background tasks",
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f))
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, "Back")
                    }
                },
                actions = {
                    IconButton(onClick = { showGuide = true }) {
                        Icon(Icons.Default.Info, "Help",
                            tint = MaterialTheme.colorScheme.onPrimaryContainer)
                    }
                    IconButton(onClick = { showTemplates = true }) {
                        Icon(Icons.Default.Dashboard, "Templates",
                            tint = MaterialTheme.colorScheme.onPrimaryContainer)
                    }
                    IconButton(onClick = { showCreateSheet = true }) {
                        Icon(Icons.Default.Add, "New Agent",
                            tint = MaterialTheme.colorScheme.onPrimaryContainer)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer
                )
            )
        },
        floatingActionButton = {
            if (agents.isNotEmpty()) {
                ExtendedFloatingActionButton(
                    onClick = { showTemplates = true },
                    icon = { Icon(Icons.Default.Add, null) },
                    text = { Text("Add Agent") },
                    containerColor = Color(0xFF1565C0),
                    contentColor = Color.White
                )
            }
        }
    ) { padding ->
        if (agents.isEmpty()) {
            EmptyAgentsPlaceholder(
                modifier = Modifier.fillMaxSize().padding(padding),
                onCreateClick = { showTemplates = true },
                onCustomClick = { showCreateSheet = true }
            )
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                item {
                    AgentSummaryBanner(agents)
                }
                items(agents, key = { it.id }) { agent ->
                    AgentCard(
                        agent = agent,
                        onToggle = {
                            agentManager.setEnabled(agent.id, !agent.enabled)
                            agents = agentManager.loadAll()
                        },
                        onEdit = { editAgent = agent },
                        onDelete = {
                            agentManager.delete(agent.id)
                            agents = agentManager.loadAll()
                        },
                        onRunNow = {
                            scope.launch {
                                WebScraperAgent(context).run(agent)
                                agents = agentManager.loadAll()
                            }
                        },
                        onViewResults = { onViewResults(agent.id) },
                        onReset = if (agent.templateId != null) {{
                            agentManager.resetToTemplate(agent.id)
                            agents = agentManager.loadAll()
                        }} else null
                    )
                }
                item { Spacer(Modifier.height(80.dp)) }
            }
        }
    }

    // Agent Guide
    if (showGuide) {
        AgentGuideDialog(onDismiss = { showGuide = false })
    }

    // Template gallery sheet
    if (showTemplates) {
        AgentTemplateGallery(
            onDismiss = { showTemplates = false },
            onSelectTemplate = { template ->
                selectedTemplate = template
                showTemplates = false
            },
            onCustom = {
                showTemplates = false
                showCreateSheet = true
            }
        )
    }

}

// ─────────────────────────────────────────────────────────────────────────────
// Summary banner
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun AgentSummaryBanner(agents: List<AgentConfig>) {
    val active = agents.count { it.enabled }
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF0D1B2A))
    ) {
        Row(modifier = Modifier.padding(16.dp).fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly) {
            StatBubble("Total", agents.size.toString(), Color(0xFF64B5F6))
            StatBubble("Active", active.toString(), Color(0xFF81C784))
            StatBubble("Paused", (agents.size - active).toString(), Color(0xFFFFB74D))
        }
    }
}

@Composable
private fun StatBubble(label: String, value: String, color: Color) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, fontSize = 26.sp, fontWeight = FontWeight.Bold, color = color)
        Text(label, fontSize = 11.sp, color = Color.White.copy(alpha = 0.6f))
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Agent card
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun AgentCard(
    agent: AgentConfig,
    onToggle: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onRunNow: () -> Unit,
    onViewResults: () -> Unit,
    onReset: (() -> Unit)? = null
) {
    var showDelete by remember { mutableStateOf(false) }
    var running by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val gradientStart = if (agent.enabled) Color(0xFF0A2540) else Color(0xFF1A1A1A)
    val gradientEnd   = if (agent.enabled) Color(0xFF0D3B6E) else Color(0xFF252525)
    val accentColor   = if (agent.enabled) Color(0xFF1E88E5) else Color(0xFF555555)

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = Color.Transparent)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(Brush.linearGradient(colors = listOf(gradientStart, gradientEnd)),
                    RoundedCornerShape(18.dp))
                .padding(16.dp)
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                // Header row
                Row(modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically) {
                    Row(verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        // Type badge
                        Surface(shape = CircleShape,
                            color = accentColor.copy(alpha = 0.15f),
                            modifier = Modifier.size(40.dp)) {
                            Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                                Text(agentEmoji(agent.type, agent.templateId), fontSize = 20.sp)
                            }
                        }
                        Column {
                            Text(agent.name, fontWeight = FontWeight.Bold,
                                fontSize = 15.sp, color = Color.White)
                            Text(agentTypeLabel(agent.type, agent.templateId, agent.apiSource),
                                fontSize = 10.sp, color = accentColor)
                        }
                    }
                    Switch(
                        checked = agent.enabled,
                        onCheckedChange = { onToggle() },
                        colors = SwitchDefaults.colors(
                            checkedTrackColor = Color(0xFF1565C0),
                            uncheckedTrackColor = Color(0xFF333333)
                        )
                    )
                }

                // URL row — for search_only agents, show tool usage instead of empty URL
                Row(verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Icon(Icons.Default.Link, null,
                        tint = accentColor, modifier = Modifier.size(13.dp))
                    val targetLabel = if (agent.type == ai.zeroclaw.android.agents.AgentManager.TYPE_SEARCH_ONLY)
                        "using web_search + web_fetch"
                    else agent.url
                    Text(targetLabel, fontSize = 11.sp,
                        color = Color.White.copy(alpha = 0.7f),
                        maxLines = 1, fontFamily = FontFamily.Monospace)
                }

                // Channel(s) + interval
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.horizontalScroll(rememberScrollState())) {
                    // Show each bot channel
                    val botChannels = agent.channel.split(",").map { it.trim() }.filter { it.isNotBlank() }
                    botChannels.forEach { ch ->
                        InfoChip("${channelEmoji(ch)} ${ch.replaceFirstChar { it.uppercase() }}", accentColor)
                    }
                    // Show channel targets count if any
                    val targets = parseChannelTargets(agent.chatId)
                    if (targets.isNotEmpty()) {
                        InfoChip("📢 ${targets.size} target${if (targets.size > 1) "s" else ""}", Color(0xFFFFB74D))
                    }
                    InfoChip("⏱ every ${formatInterval(agent.intervalMinutes)}", accentColor)
                    if (agent.onlyOnChange) InfoChip("△ on change", accentColor)
                    // Show fetch type if not default HTTP
                    val fetchLabel = when (agent.safeFetchType) {
                        "rss" -> "RSS"
                        "webview" -> "WebView"
                        else -> null
                    }
                    if (fetchLabel != null) InfoChip("🔗 $fetchLabel", Color(0xFF64B5F6))
                    // Show format indicator if AI format was saved
                    if (agent.safeFormatPreview.isNotBlank()) InfoChip("✨ AI Format", Color(0xFFCE93D8))
                }

                // Last status
                if (agent.lastStatus.isNotBlank()) {
                    val statusColor = when {
                        agent.lastStatus.contains("✓") || agent.lastStatus.contains("Delivered") -> Color(0xFF81C784)
                        agent.lastStatus.contains("✗") || agent.lastStatus.contains("fail", true) -> Color(0xFFEF9A9A)
                        else -> Color.White.copy(alpha = 0.5f)
                    }
                    Row(verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        Icon(Icons.Default.Info, null, tint = statusColor, modifier = Modifier.size(12.dp))
                        Text(agent.lastStatus, fontSize = 10.sp, color = statusColor,
                            fontFamily = FontFamily.Monospace, maxLines = 2)
                    }
                }

                // Last run
                if (agent.lastRunAt > 0) {
                    val fmt = SimpleDateFormat("MMM d HH:mm", Locale.getDefault())
                    Text("Last run: ${fmt.format(Date(agent.lastRunAt))}",
                        fontSize = 9.sp, color = Color.White.copy(alpha = 0.3f),
                        fontFamily = FontFamily.Monospace)
                }

                HorizontalDivider(color = Color.White.copy(alpha = 0.07f))

                // Primary action row — Run Now + View Results, side by side
                Row(modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = {
                            running = true
                            scope.launch {
                                onRunNow()
                                running = false
                            }
                        },
                        enabled = !running,
                        modifier = Modifier.weight(1f).height(42.dp),
                        shape = RoundedCornerShape(10.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color(0xFF1565C0),
                            disabledContainerColor = Color(0xFF0D2A4A)
                        )
                    ) {
                        if (running) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(16.dp),
                                color = Color.White, strokeWidth = 2.dp
                            )
                            Spacer(Modifier.width(8.dp))
                            Text("Scraping…", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        } else {
                            Icon(Icons.Default.Refresh, null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Run Now", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                    OutlinedButton(
                        onClick = onViewResults,
                        modifier = Modifier.weight(1f).height(42.dp),
                        shape = RoundedCornerShape(10.dp),
                        border = BorderStroke(1.dp, accentColor),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = accentColor)
                    ) {
                        Icon(Icons.Default.Inbox, null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Results", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    }
                }

                // Edit / Reset / Delete row
                Row(modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = onEdit,
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(10.dp),
                        border = BorderStroke(1.dp, accentColor.copy(alpha = 0.4f)),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = accentColor)
                    ) {
                        Icon(Icons.Default.Edit, null, modifier = Modifier.size(14.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Edit", fontSize = 11.sp)
                    }
                    if (onReset != null) {
                        OutlinedButton(
                            onClick = onReset,
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(10.dp),
                            border = BorderStroke(1.dp, Color(0xFFFFB74D).copy(alpha = 0.4f)),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFFFFB74D))
                        ) {
                            Icon(Icons.Default.RestartAlt, null, modifier = Modifier.size(14.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("Reset", fontSize = 11.sp)
                        }
                    }
                    OutlinedButton(
                        onClick = { showDelete = true },
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(10.dp),
                        border = BorderStroke(1.dp, Color(0xFFEF5350).copy(alpha = 0.4f)),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFFEF5350))
                    ) {
                        Icon(Icons.Default.Delete, null, modifier = Modifier.size(14.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Delete", fontSize = 11.sp)
                    }
                }
            }
        }
    }

    if (showDelete) {
        AlertDialog(
            onDismissRequest = { showDelete = false },
            title = { Text("Delete agent?") },
            text = { Text("'${agent.name}' will be removed permanently.") },
            confirmButton = {
                TextButton(onClick = { showDelete = false; onDelete() },
                    colors = ButtonDefaults.textButtonColors(contentColor = Color(0xFFEF5350))) {
                    Text("Delete")
                }
            },
            dismissButton = {
                TextButton(onClick = { showDelete = false }) { Text("Cancel") }
            }
        )
    }
}

@Composable
private fun InfoChip(text: String, accent: Color) {
    Surface(shape = RoundedCornerShape(6.dp), color = accent.copy(alpha = 0.12f)) {
        Text(text, modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
            fontSize = 10.sp, color = accent, fontWeight = FontWeight.Medium)
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Empty state
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun EmptyAgentsPlaceholder(modifier: Modifier, onCreateClick: () -> Unit, onCustomClick: () -> Unit) {
    LazyColumn(
        modifier = modifier,
        contentPadding = PaddingValues(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        item {
            Spacer(Modifier.height(32.dp))
            Text("🤖", fontSize = 60.sp, modifier = Modifier.fillMaxWidth(),
                textAlign = androidx.compose.ui.text.style.TextAlign.Center)
            Spacer(Modifier.height(12.dp))
            Text("No Agents Yet", fontWeight = FontWeight.Bold, fontSize = 22.sp,
                modifier = Modifier.fillMaxWidth(),
                textAlign = androidx.compose.ui.text.style.TextAlign.Center)
            Spacer(Modifier.height(8.dp))
            Text("Agents run in the background and automatically\nfetch data from the web for you.",
                fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                lineHeight = 19.sp, modifier = Modifier.fillMaxWidth())
        }

        item {
            Button(
                onClick = onCreateClick,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1565C0))
            ) {
                Icon(Icons.Default.Dashboard, null)
                Spacer(Modifier.width(8.dp))
                Text("Browse 25+ Agent Templates", fontWeight = FontWeight.Bold)
            }
        }

        item {
            OutlinedButton(onClick = onCustomClick, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Default.Add, null)
                Spacer(Modifier.width(8.dp))
                Text("Create Custom Agent")
            }
        }

        // Quick guide
        item { AgentQuickGuide() }
    }
}

@Composable
private fun AgentQuickGuide() {
    val guideColor = Color(0xFF161b22)
    val borderColor = Color(0xFF30363d)
    val accentBlue = Color(0xFF58A6FF)
    val accentGreen = Color(0xFF3FB950)
    val textDim = Color(0xFF8B949E)
    val textBright = Color(0xFFF0F6FC)

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("How to Create Agents", fontWeight = FontWeight.Bold, fontSize = 16.sp,
            color = textBright, modifier = Modifier.padding(top = 8.dp))

        // Method 1
        Surface(shape = RoundedCornerShape(12.dp), color = guideColor,
            border = BorderStroke(1.dp, borderColor), modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("Method 1: From App", fontWeight = FontWeight.Bold, fontSize = 14.sp, color = accentBlue)
                Spacer(Modifier.height(8.dp))
                GuideStep("1.", "Tap the + button or \"Browse Templates\" above")
                GuideStep("2.", "Pick a template (News, Crypto, Gold, Weather...)")
                GuideStep("3.", "Choose categories or enter custom topic")
                GuideStep("4.", "Set how often to check (e.g., every 30 min)")
                GuideStep("5.", "Pick where to send results (Telegram, Discord...)")
                GuideStep("6.", "Tap Save — agent starts running automatically!")
            }
        }

        // Method 2 — Agent Manager via Chat
        Surface(shape = RoundedCornerShape(12.dp), color = guideColor,
            border = BorderStroke(1.dp, Color(0xFF238636)), modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("Method 2: Agent Manager (Chat)", fontWeight = FontWeight.Bold, fontSize = 14.sp, color = accentGreen)
                Spacer(Modifier.height(8.dp))
                Text("ZeroClaw has a built-in Agent Manager that you can talk to from ANY channel — Telegram, Discord, Slack, WhatsApp, Web Chat, or the API. Just describe what you want and the AI does everything for you!",
                    fontSize = 12.sp, color = textDim, lineHeight = 17.sp)

                Spacer(Modifier.height(12.dp))
                Text("Step 1: Tell the AI what to track", fontSize = 13.sp, color = textBright, fontWeight = FontWeight.SemiBold)
                Text("No URL needed — just describe the topic:", fontSize = 11.sp, color = textDim)
                Spacer(Modifier.height(6.dp))
                ChatExample("\"Track latest tollywood movies\"")
                ChatExample("\"Create agent for android developer jobs\"")
                ChatExample("\"Monitor gold prices every 30 minutes\"")
                ChatExample("\"Watch earthquake alerts and send to discord\"")
                ChatExample("\"Track Bitcoin price\"")

                Spacer(Modifier.height(12.dp))
                Text("Step 2: AI finds sources + shows preview", fontSize = 13.sp, color = textBright, fontWeight = FontWeight.SemiBold)
                Text("The AI automatically searches the web, finds the best sites for your topic (LinkedIn, IMDB, CoinGecko, etc.), creates the agent, runs it once, and shows you a preview of the data it found.",
                    fontSize = 11.sp, color = textDim, lineHeight = 16.sp)

                Spacer(Modifier.height(12.dp))
                Text("Step 3: Refine until perfect", fontSize = 13.sp, color = textBright, fontWeight = FontWeight.SemiBold)
                Text("Not happy with the preview? Just tell the AI what to change:", fontSize = 11.sp, color = textDim)
                Spacer(Modifier.height(6.dp))
                ChatExample("\"Change to only remote jobs in Bangalore\"")
                ChatExample("\"Show only Hindi movies, not all Indian\"")
                ChatExample("\"Add Netflix releases too\"")
                Spacer(Modifier.height(4.dp))
                Text("The AI modifies the agent, re-runs it, and shows you an updated preview. Keep tweaking until it's exactly right!",
                    fontSize = 11.sp, color = textDim, lineHeight = 16.sp)

                Spacer(Modifier.height(12.dp))
                Text("Step 4: Agent runs automatically", fontSize = 13.sp, color = textBright, fontWeight = FontWeight.SemiBold)
                Text("Once you say \"looks good\", the agent runs on schedule and pushes updates to your chat. No need to open the app — data comes to you!",
                    fontSize = 11.sp, color = textDim, lineHeight = 16.sp)
            }
        }

        // Managing agents via chat
        Surface(shape = RoundedCornerShape(12.dp), color = guideColor,
            border = BorderStroke(1.dp, borderColor), modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("Control Agents from Chat", fontWeight = FontWeight.Bold, fontSize = 14.sp, color = Color(0xFFD29922))
                Spacer(Modifier.height(8.dp))
                Text("You don't need to open the app to manage agents. Just send a message:", fontSize = 12.sp, color = textDim)
                Spacer(Modifier.height(8.dp))
                ChatExample("\"Show my agents\" — list all with status")
                ChatExample("\"Status of crypto agent\" — details + recent data")
                ChatExample("\"Run news agent now\" — trigger a fetch")
                ChatExample("\"Disable weather agent\" — pause it")
                ChatExample("\"Enable gold agent\" — resume it")
                ChatExample("\"Show results from gold agent\" — view history")
                ChatExample("\"Change gold agent to check every 15 minutes\"")
                ChatExample("\"Delete old agent\" — remove it")
                Spacer(Modifier.height(8.dp))
                Text("Works from Telegram, Discord, Slack, WhatsApp, Web Chat, or any app connected to ZeroClaw's API.",
                    fontSize = 11.sp, color = textDim, lineHeight = 16.sp)
            }
        }

        // What agents can do
        Surface(shape = RoundedCornerShape(12.dp), color = guideColor,
            border = BorderStroke(1.dp, borderColor), modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("What Can Agents Track?", fontWeight = FontWeight.Bold, fontSize = 14.sp, color = textBright)
                Spacer(Modifier.height(8.dp))
                TrackItem("📰", "News", "Headlines from any topic")
                TrackItem("💰", "Prices", "Gold, crypto, stocks, forex, fuel")
                TrackItem("🌤️", "Weather", "Any city, daily forecasts")
                TrackItem("🌍", "Earthquakes", "Live USGS seismic alerts")
                TrackItem("📈", "Stocks & IPOs", "Market data, new listings")
                TrackItem("🎬", "Movies & YouTube", "Trending content")
                TrackItem("⚽", "Sports", "Live scores and results")
                TrackItem("🔗", "Any Website", "Track any URL for changes")
                Spacer(Modifier.height(6.dp))
                Text("Agents check automatically and push updates to your chat. No need to open the app!",
                    fontSize = 11.sp, color = textDim, lineHeight = 16.sp)
            }
        }
    }
}

@Composable
private fun GuideStep(number: String, text: String) {
    Row(modifier = Modifier.padding(vertical = 2.dp)) {
        Text(number, fontSize = 12.sp, color = Color(0xFF58A6FF), fontWeight = FontWeight.Bold,
            modifier = Modifier.width(20.dp))
        Text(text, fontSize = 12.sp, color = Color(0xFFC9D1D9), lineHeight = 17.sp)
    }
}

@Composable
private fun ChatExample(text: String) {
    Surface(shape = RoundedCornerShape(8.dp), color = Color(0xFF0D1117),
        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Text(text, fontSize = 12.sp, color = Color(0xFF79C0FF),
            fontFamily = FontFamily.Monospace, modifier = Modifier.padding(8.dp, 6.dp),
            lineHeight = 16.sp)
    }
}

@Composable
private fun TrackItem(emoji: String, title: String, desc: String) {
    Row(modifier = Modifier.padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(emoji, fontSize = 16.sp, modifier = Modifier.width(28.dp))
        Text(title, fontSize = 12.sp, color = Color(0xFFF0F6FC), fontWeight = FontWeight.SemiBold,
            modifier = Modifier.width(90.dp))
        Text(desc, fontSize = 11.sp, color = Color(0xFF8B949E))
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AgentGuideDialog(onDismiss: () -> Unit) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = Color(0xFF0D1117),
        dragHandle = null
    ) {
        LazyColumn(
            modifier = Modifier.fillMaxWidth().navigationBarsPadding(),
            contentPadding = PaddingValues(horizontal = 20.dp, vertical = 16.dp)
        ) {
            item {
                Row(verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()) {
                    Text("📖", fontSize = 24.sp)
                    Spacer(Modifier.width(10.dp))
                    Text("Agent Guide", fontWeight = FontWeight.Bold, fontSize = 20.sp,
                        color = Color(0xFFF0F6FC), modifier = Modifier.weight(1f))
                    IconButton(onClick = onDismiss, modifier = Modifier.size(36.dp)) {
                        Icon(Icons.Default.Close, "Close",
                            tint = Color.White.copy(alpha = 0.6f), modifier = Modifier.size(20.dp))
                    }
                }
                Spacer(Modifier.height(4.dp))
                Text("Everything you need to know about creating and managing agents.",
                    fontSize = 12.sp, color = Color(0xFF8B949E))
                Spacer(Modifier.height(16.dp))
            }

            item { AgentQuickGuide() }

            item { Spacer(Modifier.height(24.dp)) }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Helpers
// ─────────────────────────────────────────────────────────────────────────────

private fun agentEmoji(type: String, templateId: String? = null): String {
    if (templateId != null) {
        val tpl = AGENT_TEMPLATES.firstOrNull { it.id == templateId }
        if (tpl != null) return tpl.emoji
    }
    return when (type) {
        AgentManager.TYPE_WEB_SCRAPER -> "🕷️"
        else -> "🤖"
    }
}

private fun agentTypeLabel(type: String, templateId: String? = null, apiSource: String? = null): String {
    val suffix = if (apiSource != null) " ⚡API" else ""
    if (templateId != null) {
        val tpl = AGENT_TEMPLATES.firstOrNull { it.id == templateId }
        if (tpl != null) return tpl.category + suffix
    }
    return when (type) {
        AgentManager.TYPE_WEB_SCRAPER -> "Web Scraper$suffix"
        else -> type + suffix
    }
}

private fun channelEmoji(channel: String) = when (channel.lowercase()) {
    "telegram"  -> "✈️"
    "discord"   -> "🎮"
    "slack"     -> "💼"
    "whatsapp"  -> "💬"
    "email"     -> "📧"
    else        -> "📤"
}

private fun formatInterval(minutes: Int): String = when {
    minutes < 60   -> "${minutes}m"
    minutes % 60 == 0 -> "${minutes / 60}h"
    else           -> "${minutes / 60}h ${minutes % 60}m"
}

// ─────────────────────────────────────────────────────────────────────────────
// Template gallery — bottom sheet showing all 25 agent templates by category
// ─────────────────────────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AgentTemplateGallery(
    onDismiss: () -> Unit,
    onSelectTemplate: (AgentTemplate) -> Unit,
    onCustom: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val grouped = remember { getTemplatesByCategory() }

    // Separate API-powered agents from web scraping agents
    val apiTemplates = remember { AGENT_TEMPLATES.filter { it.apiSource != null } }
    val scrapingTemplates = remember { AGENT_TEMPLATES.filter { it.apiSource == null } }
    val apiByCategory = remember { apiTemplates.groupBy { it.category } }
    val scrapingByCategory = remember { scrapingTemplates.groupBy { it.category } }
    var selectedTab by remember { mutableIntStateOf(0) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = Color(0xFF0D1B2A),
        dragHandle = null
    ) {
        Column(modifier = Modifier.padding(horizontal = 16.dp).padding(bottom = 32.dp)) {
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Agent Templates", fontWeight = FontWeight.Bold, fontSize = 20.sp,
                        color = Color.White)
                    Text("${AGENT_TEMPLATES.size} ready-made agents — tap to activate",
                        fontSize = 12.sp, color = Color.White.copy(alpha = 0.6f))
                }
                IconButton(onClick = onDismiss) {
                    Icon(Icons.Default.Close, contentDescription = "Close",
                        tint = Color.White.copy(alpha = 0.6f))
                }
            }
            Spacer(Modifier.height(12.dp))

            // Tab row: API Agents vs Web Scraping
            TabRow(
                selectedTabIndex = selectedTab,
                containerColor = Color(0xFF152238),
                contentColor = Color.White,
                indicator = {
                    TabRowDefaults.SecondaryIndicator(
                        color = if (selectedTab == 0) Color(0xFF4CAF50) else Color(0xFF64B5F6)
                    )
                }
            ) {
                Tab(
                    selected = selectedTab == 0,
                    onClick = { selectedTab = 0 },
                    text = {
                        Row(verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text("⚡", fontSize = 14.sp)
                            Text("API Agents (${apiTemplates.size})", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        }
                    },
                    selectedContentColor = Color(0xFF4CAF50),
                    unselectedContentColor = Color.White.copy(alpha = 0.5f)
                )
                Tab(
                    selected = selectedTab == 1,
                    onClick = { selectedTab = 1 },
                    text = {
                        Row(verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text("🕷️", fontSize = 14.sp)
                            Text("Web Scraping (${scrapingTemplates.size})", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        }
                    },
                    selectedContentColor = Color(0xFF64B5F6),
                    unselectedContentColor = Color.White.copy(alpha = 0.5f)
                )
            }

            Spacer(Modifier.height(12.dp))

            // Description banner for selected tab
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(10.dp),
                colors = CardDefaults.cardColors(
                    containerColor = if (selectedTab == 0) Color(0xFF1B3A2F) else Color(0xFF1A2540)
                )
            ) {
                Row(modifier = Modifier.padding(10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(if (selectedTab == 0) "⚡" else "🕷️", fontSize = 18.sp)
                    Column {
                        Text(
                            if (selectedTab == 0) "Direct Free API — Fast & Reliable"
                            else "Web Scraping + LLM — Flexible",
                            fontWeight = FontWeight.Bold, fontSize = 12.sp,
                            color = if (selectedTab == 0) Color(0xFF81C784) else Color(0xFF90CAF9)
                        )
                        Text(
                            if (selectedTab == 0) "No API key needed. Data fetched directly from free public APIs."
                            else "Scrapes web pages and uses AI to extract data. Works for any site.",
                            fontSize = 10.sp, color = Color.White.copy(alpha = 0.6f)
                        )
                    }
                }
            }

            Spacer(Modifier.height(12.dp))

            val currentGrouped = if (selectedTab == 0) apiByCategory else scrapingByCategory

            LazyColumn(
                modifier = Modifier.weight(1f, fill = false).heightIn(max = 500.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                currentGrouped.forEach { (category, templates) ->
                    item {
                        Text(category.uppercase(), fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = if (selectedTab == 0) Color(0xFF81C784) else Color(0xFF64B5F6),
                            letterSpacing = 1.sp,
                            modifier = Modifier.padding(top = 4.dp, bottom = 4.dp))
                    }
                    items(templates, key = { it.id }) { template ->
                        TemplateItem(template = template, onClick = { onSelectTemplate(template) })
                    }
                }
            }

            Spacer(Modifier.height(16.dp))
            OutlinedButton(
                onClick = onCustom,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                border = BorderStroke(1.dp, Color(0xFF64B5F6).copy(alpha = 0.4f)),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFF64B5F6))
            ) {
                Icon(Icons.Default.Add, null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(8.dp))
                Text("Create Custom Agent", fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
private fun TemplateItem(template: AgentTemplate, onClick: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF152238))
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Surface(
                shape = RoundedCornerShape(10.dp),
                color = Color(0xFF1E88E5).copy(alpha = 0.15f),
                modifier = Modifier.size(44.dp)
            ) {
                Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                    Text(template.emoji, fontSize = 22.sp)
                }
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(template.name, fontWeight = FontWeight.Bold,
                    fontSize = 14.sp, color = Color.White)
                Text(template.description, fontSize = 11.sp,
                    color = Color.White.copy(alpha = 0.6f), maxLines = 2)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.padding(top = 4.dp)) {
                    if (template.apiSource != null) InfoChip("⚡ API", Color(0xFF4CAF50))
                    InfoChip("⏱ ${formatInterval(template.intervalMinutes)}", Color(0xFF64B5F6))
                    if (template.needsUserInput) InfoChip("✏️ Customizable", Color(0xFFFFB74D))
                    if (template.subCategories.isNotEmpty()) InfoChip("📂 ${template.subCategories.size} options", Color(0xFF81C784))
                }
                if (template.apiRateNote != null) {
                    Text(template.apiRateNote, fontSize = 9.sp,
                        color = Color(0xFF4CAF50).copy(alpha = 0.7f),
                        modifier = Modifier.padding(top = 2.dp))
                }
            }
            Icon(Icons.Default.ChevronRight, null,
                tint = Color.White.copy(alpha = 0.3f), modifier = Modifier.size(20.dp))
        }
    }
}

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
fun AgentsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val agentManager = remember { AgentManager.getInstance(context) }
    val scope = rememberCoroutineScope()

    var agents by remember { mutableStateOf(agentManager.loadAll()) }
    var showCreateSheet by remember { mutableStateOf(false) }
    var editAgent by remember { mutableStateOf<AgentConfig?>(null) }
    var showTemplates by remember { mutableStateOf(false) }
    var selectedTemplate by remember { mutableStateOf<AgentTemplate?>(null) }

    // Poll agent list every 5 s so lastStatus updates reflect live runs
    LaunchedEffect(Unit) {
        while (true) {
            agents = agentManager.loadAll()
            delay(5_000)
        }
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

    // Create / edit sheet (from template or custom)
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
                            Text(agentTypeLabel(agent.type, agent.templateId),
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

                // URL row
                Row(verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Icon(Icons.Default.Link, null,
                        tint = accentColor, modifier = Modifier.size(13.dp))
                    Text(agent.url, fontSize = 11.sp,
                        color = Color.White.copy(alpha = 0.7f),
                        maxLines = 1, fontFamily = FontFamily.Monospace)
                }

                // Channel + interval
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    InfoChip(channelEmoji(agent.channel) + " " + agent.channel.replaceFirstChar { it.uppercase() }, accentColor)
                    InfoChip("⏱ every ${formatInterval(agent.intervalMinutes)}", accentColor)
                    if (agent.onlyOnChange) InfoChip("△ on change", accentColor)
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

                // Run Now button — full width, prominent
                Button(
                    onClick = {
                        running = true
                        scope.launch {
                            onRunNow()
                            running = false
                        }
                    },
                    enabled = !running,
                    modifier = Modifier.fillMaxWidth().height(42.dp),
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
    Column(modifier = modifier,
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally) {
        Text("🤖", fontSize = 60.sp)
        Spacer(Modifier.height(16.dp))
        Text("No Agents Yet", fontWeight = FontWeight.Bold, fontSize = 20.sp)
        Spacer(Modifier.height(8.dp))
        Text("Choose from 25+ ready-made agent templates\nor create your own custom agent.",
            fontSize = 13.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            lineHeight = 19.sp)
        Spacer(Modifier.height(28.dp))
        Button(
            onClick = onCreateClick,
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1565C0))
        ) {
            Icon(Icons.Default.Dashboard, null)
            Spacer(Modifier.width(8.dp))
            Text("Browse Agent Templates", fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.height(12.dp))
        OutlinedButton(onClick = onCustomClick) {
            Icon(Icons.Default.Add, null)
            Spacer(Modifier.width(8.dp))
            Text("Create Custom Agent")
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

private fun agentTypeLabel(type: String, templateId: String? = null): String {
    if (templateId != null) {
        val tpl = AGENT_TEMPLATES.firstOrNull { it.id == templateId }
        if (tpl != null) return tpl.category
    }
    return when (type) {
        AgentManager.TYPE_WEB_SCRAPER -> "Web Scraper"
        else -> type
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

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = Color(0xFF0D1B2A)
    ) {
        Column(modifier = Modifier.padding(horizontal = 16.dp).padding(bottom = 32.dp)) {
            Text("Agent Templates", fontWeight = FontWeight.Bold, fontSize = 20.sp,
                color = Color.White)
            Text("${AGENT_TEMPLATES.size} ready-made agents — tap to activate",
                fontSize = 12.sp, color = Color.White.copy(alpha = 0.6f))
            Spacer(Modifier.height(16.dp))

            LazyColumn(
                modifier = Modifier.weight(1f, fill = false).heightIn(max = 500.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                grouped.forEach { (category, templates) ->
                    item {
                        Text(category.uppercase(), fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFF64B5F6),
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
                    InfoChip("⏱ ${formatInterval(template.intervalMinutes)}", Color(0xFF64B5F6))
                    if (template.needsUserInput) InfoChip("✏️ Customizable", Color(0xFFFFB74D))
                    if (template.subCategories.isNotEmpty()) InfoChip("📂 ${template.subCategories.size} options", Color(0xFF81C784))
                }
            }
            Icon(Icons.Default.ChevronRight, null,
                tint = Color.White.copy(alpha = 0.3f), modifier = Modifier.size(20.dp))
        }
    }
}

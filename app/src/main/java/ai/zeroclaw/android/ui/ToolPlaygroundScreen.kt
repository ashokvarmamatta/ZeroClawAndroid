package ai.zeroclaw.android.ui

import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.*
import ai.zeroclaw.android.tools.Tool
import ai.zeroclaw.android.tools.ToolSystem
import kotlinx.coroutines.launch

// ── Category definition (shared with AiToolsScreen) ──────────────────────────

internal enum class PlaygroundCategory(
    val label: String,
    val emoji: String,
    val gradient: List<Color>
) {
    ALL("All", "🧪", listOf(Color(0xFF1A1A2E), Color(0xFF16213E))),
    CORE("Core AI", "🧠", listOf(Color(0xFF006064), Color(0xFF00838F))),
    DEVICE("Device", "📱", listOf(Color(0xFF1B5E20), Color(0xFF2E7D32))),
    EXTERNAL("External", "🌐", listOf(Color(0xFFE65100), Color(0xFFF57C00))),
    ADVANCED("Advanced", "⚡", listOf(Color(0xFF4A148C), Color(0xFF6A1B9A)))
}

internal data class PlaygroundToolMeta(
    val emoji: String,
    val category: PlaygroundCategory,
    val tagline: String
)

internal val PLAYGROUND_META: Map<String, PlaygroundToolMeta> = mapOf(
    "web_search"     to PlaygroundToolMeta("🔍", PlaygroundCategory.CORE,    "Search the web in real-time"),
    "web_fetch"      to PlaygroundToolMeta("🌐", PlaygroundCategory.CORE,    "Fetch and read any URL"),
    "memory"         to PlaygroundToolMeta("🧠", PlaygroundCategory.CORE,    "Store and recall context"),
    "pdf_read"       to PlaygroundToolMeta("📄", PlaygroundCategory.CORE,    "Extract text from PDFs"),
    "summarize"      to PlaygroundToolMeta("📝", PlaygroundCategory.CORE,    "Condense long text instantly"),
    "translate"      to PlaygroundToolMeta("🌍", PlaygroundCategory.CORE,    "50+ language translation"),
    "calculator"     to PlaygroundToolMeta("🔢", PlaygroundCategory.CORE,    "Math & unit conversions"),
    "qr_code"        to PlaygroundToolMeta("📷", PlaygroundCategory.CORE,    "Generate QR codes"),
    "clipboard"      to PlaygroundToolMeta("📋", PlaygroundCategory.CORE,    "Read & write clipboard"),
    "status"         to PlaygroundToolMeta("ℹ️",  PlaygroundCategory.CORE,    "Service health dashboard"),
    "image_analysis" to PlaygroundToolMeta("🖼️", PlaygroundCategory.CORE,    "Analyse images with AI"),
    "calendar"       to PlaygroundToolMeta("📅", PlaygroundCategory.DEVICE,  "Read & create events"),
    "contacts"       to PlaygroundToolMeta("👥", PlaygroundCategory.DEVICE,  "Search your contacts"),
    "location"       to PlaygroundToolMeta("📍", PlaygroundCategory.DEVICE,  "GPS & geocoding"),
    "text_to_speech" to PlaygroundToolMeta("🔊", PlaygroundCategory.DEVICE,  "Text → spoken audio"),
    "speech_to_text" to PlaygroundToolMeta("🎤", PlaygroundCategory.DEVICE,  "Voice → transcription"),
    "file_manager"   to PlaygroundToolMeta("📁", PlaygroundCategory.DEVICE,  "Browse & read files"),
    "bookmark"       to PlaygroundToolMeta("🔖", PlaygroundCategory.DEVICE,  "Save & search bookmarks"),
    "webview"        to PlaygroundToolMeta("🖥️", PlaygroundCategory.DEVICE,  "Browser automation"),
    "cron"           to PlaygroundToolMeta("⏰", PlaygroundCategory.DEVICE,  "Schedule recurring tasks"),
    "media_pipeline" to PlaygroundToolMeta("🎬", PlaygroundCategory.DEVICE,  "Image & audio processing"),
    "weather"        to PlaygroundToolMeta("🌤️", PlaygroundCategory.EXTERNAL, "Live weather & forecasts"),
    "github"         to PlaygroundToolMeta("🐱", PlaygroundCategory.EXTERNAL, "Search repos & issues"),
    "rss_feed"       to PlaygroundToolMeta("📡", PlaygroundCategory.EXTERNAL, "Monitor feeds & blogs"),
    "image_gen"      to PlaygroundToolMeta("🎨", PlaygroundCategory.EXTERNAL, "Generate images from text"),
    "spotify"        to PlaygroundToolMeta("🎵", PlaygroundCategory.EXTERNAL, "Control Spotify playback"),
    "smart_home"     to PlaygroundToolMeta("🏠", PlaygroundCategory.EXTERNAL, "Control Hue & IoT devices"),
    "brave_search"   to PlaygroundToolMeta("🦁", PlaygroundCategory.EXTERNAL, "Brave Search API"),
    "notion"         to PlaygroundToolMeta("📝", PlaygroundCategory.EXTERNAL, "Notion workspace"),
    "email"          to PlaygroundToolMeta("📧", PlaygroundCategory.EXTERNAL, "Send emails"),
    "composio"       to PlaygroundToolMeta("🔗", PlaygroundCategory.ADVANCED, "1000+ OAuth integrations"),
    "delegate"       to PlaygroundToolMeta("🤝", PlaygroundCategory.ADVANCED, "Delegate to sub-agents"),
    "spawn"          to PlaygroundToolMeta("🚀", PlaygroundCategory.ADVANCED, "Spawn async agents"),
    "message"        to PlaygroundToolMeta("💬", PlaygroundCategory.ADVANCED, "Cross-channel messaging"),
    "pushover"       to PlaygroundToolMeta("📬", PlaygroundCategory.ADVANCED, "Push notifications"),
    "a2a"            to PlaygroundToolMeta("🤖", PlaygroundCategory.ADVANCED, "Agent-to-Agent protocol"),
)

internal fun pgEmoji(name: String) = PLAYGROUND_META[name]?.emoji ?: "🔧"
internal fun pgCategory(name: String) = PLAYGROUND_META[name]?.category ?: PlaygroundCategory.CORE
internal fun pgTagline(name: String) = PLAYGROUND_META[name]?.tagline ?: ""

// ─────────────────────────────────────────────────────────────────────────────
// Main Screen
// ─────────────────────────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ToolPlaygroundScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val toolSystem = remember { ToolSystem.getInstance(context) }
    val scope = rememberCoroutineScope()

    var toolStates by remember { mutableStateOf<Map<String, Boolean>>(emptyMap()) }
    var selectedCategory by remember { mutableStateOf(PlaygroundCategory.ALL) }
    var selectedTool by remember { mutableStateOf<Tool?>(null) }
    var selectedTab by remember { mutableIntStateOf(0) }

    LaunchedEffect(Unit) {
        val states = mutableMapOf<String, Boolean>()
        for (tool in toolSystem.allTools()) {
            states[tool.name] = toolSystem.isEnabled(tool.name)
        }
        toolStates = states
    }

    val allTools = toolSystem.allTools()
    val visibleTools = if (selectedCategory == PlaygroundCategory.ALL) allTools
                       else allTools.filter { pgCategory(it.name) == selectedCategory }

    val enabledCount = toolStates.count { it.value }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Tool Playground", fontWeight = FontWeight.Bold)
                        Text(
                            "$enabledCount tools enabled · tap any card to test",
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f)
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, "Back")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color(0xFF0D0D1A)
                )
            )
        },
        containerColor = Color(0xFF0D0D1A)
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            // Tab row — Tools / Chat
            TabRow(
                selectedTabIndex = selectedTab,
                containerColor = Color(0xFF0D0D1A),
                contentColor = Color(0xFF00BCD4)
            ) {
                Tab(selected = selectedTab == 0, onClick = { selectedTab = 0 },
                    text = { Text("🔧  Tools") })
                Tab(selected = selectedTab == 1, onClick = { selectedTab = 1 },
                    text = { Text("💬  Chat") })
            }

            when (selectedTab) {
                0 -> ToolGridTab(
                    tools = visibleTools,
                    toolStates = toolStates,
                    selectedCategory = selectedCategory,
                    onCategorySelect = { selectedCategory = it },
                    onToolSelect = { selectedTool = it }
                )
                1 -> ToolChatTab(
                    toolSystem = toolSystem,
                    toolStates = toolStates
                )
            }
        }
    }

    // Bottom sheet for selected tool
    selectedTool?.let { tool ->
        ModalBottomSheet(
            onDismissRequest = { selectedTool = null },
            containerColor = Color(0xFF12122A),
            dragHandle = {
                Box(
                    modifier = Modifier
                        .padding(top = 12.dp, bottom = 8.dp)
                        .width(40.dp)
                        .height(4.dp)
                        .clip(CircleShape)
                        .background(Color.White.copy(alpha = 0.2f))
                )
            }
        ) {
            ToolTestSheet(
                tool = tool,
                toolSystem = toolSystem,
                isEnabled = toolStates[tool.name] ?: false,
                onClose = { selectedTool = null }
            )
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Tab 1 — Tool Grid
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun ToolGridTab(
    tools: List<Tool>,
    toolStates: Map<String, Boolean>,
    selectedCategory: PlaygroundCategory,
    onCategorySelect: (PlaygroundCategory) -> Unit,
    onToolSelect: (Tool) -> Unit
) {
    Column(modifier = Modifier.fillMaxSize()) {
        // Category chips row
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            PlaygroundCategory.entries.forEach { cat ->
                val selected = cat == selectedCategory
                Surface(
                    onClick = { onCategorySelect(cat) },
                    shape = RoundedCornerShape(20.dp),
                    color = if (selected) Color(0xFF00BCD4).copy(alpha = 0.2f)
                            else Color.White.copy(alpha = 0.06f),
                    border = if (selected) BorderStroke(1.dp, Color(0xFF00BCD4))
                             else BorderStroke(1.dp, Color.White.copy(alpha = 0.1f))
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 7.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(cat.emoji, fontSize = 13.sp)
                        Text(
                            cat.label,
                            fontSize = 12.sp,
                            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                            color = if (selected) Color(0xFF00BCD4) else Color.White.copy(alpha = 0.7f)
                        )
                    }
                }
            }
        }

        // Tool grid
        LazyVerticalGrid(
            columns = GridCells.Fixed(2),
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            items(tools, key = { it.name }) { tool ->
                ToolCard(
                    tool = tool,
                    isEnabled = toolStates[tool.name] ?: false,
                    onClick = { onToolSelect(tool) }
                )
            }
            item(span = { GridItemSpan(2) }) { Spacer(Modifier.height(80.dp)) }
        }
    }
}

@Composable
private fun ToolCard(tool: Tool, isEnabled: Boolean, onClick: () -> Unit) {
    val cat = pgCategory(tool.name)
    val grad0 = cat.gradient[0]
    val grad1 = cat.gradient[1]

    Card(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .height(158.dp),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = Color.Transparent),
        border = BorderStroke(
            1.dp,
            if (isEnabled) grad1.copy(alpha = 0.6f)
            else Color.White.copy(alpha = 0.08f)
        )
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.linearGradient(
                        colors = if (isEnabled)
                            listOf(grad0.copy(alpha = 0.9f), grad1.copy(alpha = 0.6f))
                        else
                            listOf(Color(0xFF1A1A2E), Color(0xFF16213E))
                    )
                )
        ) {
            // Subtle top-right glow circle
            if (isEnabled) {
                Box(
                    modifier = Modifier
                        .size(80.dp)
                        .offset(x = 40.dp, y = (-20).dp)
                        .align(Alignment.TopEnd)
                        .background(
                            Brush.radialGradient(
                                colors = listOf(grad1.copy(alpha = 0.4f), Color.Transparent)
                            ),
                            CircleShape
                        )
                )
            }

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(14.dp),
                verticalArrangement = Arrangement.SpaceBetween
            ) {
                // Top: emoji + enabled dot
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.Top
                ) {
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = Color.White.copy(alpha = if (isEnabled) 0.15f else 0.07f),
                        modifier = Modifier.size(44.dp)
                    ) {
                        Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                            Text(pgEmoji(tool.name), fontSize = 22.sp)
                        }
                    }

                    // Enabled indicator
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .background(
                                if (isEnabled) Color(0xFF4CAF50) else Color(0xFF555555),
                                CircleShape
                            )
                    )
                }

                // Bottom: name + tagline + test button
                Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text(
                        tool.name.replace("_", " ").replaceFirstChar { it.uppercase() },
                        fontWeight = FontWeight.Bold,
                        fontSize = 13.sp,
                        color = Color.White,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        pgTagline(tool.name),
                        fontSize = 10.sp,
                        color = Color.White.copy(alpha = 0.55f),
                        maxLines = 2,
                        lineHeight = 13.sp,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(Modifier.height(4.dp))
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = if (isEnabled) grad1.copy(alpha = 0.35f)
                                else Color.White.copy(alpha = 0.07f)
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                if (isEnabled) "▶  Test" else "⚠  Disabled",
                                fontSize = 9.sp,
                                fontWeight = FontWeight.Bold,
                                color = if (isEnabled) Color.White else Color(0xFF888888)
                            )
                        }
                    }
                }
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Tab 2 — Chat Playground
// ─────────────────────────────────────────────────────────────────────────────

data class ChatMsg(val text: String, val isUser: Boolean, val toolName: String? = null, val isError: Boolean = false)

@Composable
fun ToolChatTab(toolSystem: ToolSystem, toolStates: Map<String, Boolean>) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var messages by remember { mutableStateOf(listOf<ChatMsg>(
        ChatMsg("👋 Hi! I'm your Tool Playground. Type a message and I'll automatically pick the right tool to answer it.\n\nExamples:\n• \"weather in Tokyo\"\n• \"search for Kotlin coroutines\"\n• \"translate Hello to Spanish\"\n• \"calculate 345 * 18.5\"\n• \"summarize [paste text here]\"", false)
    )) }
    var input by remember { mutableStateOf("") }
    var isThinking by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()

    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) listState.animateScrollToItem(messages.lastIndex)
    }

    Column(modifier = Modifier.fillMaxSize()) {
        // Messages
        LazyColumn(
            state = listState,
            modifier = Modifier.weight(1f),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            items(messages) { msg -> ChatBubble(msg) }
            if (isThinking) { item { ThinkingBubble() } }
        }

        // Input bar
        Surface(
            color = Color(0xFF12122A),
            tonalElevation = 8.dp
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(12.dp)
                    .navigationBarsPadding()
                    .imePadding(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                OutlinedTextField(
                    value = input,
                    onValueChange = { input = it },
                    placeholder = { Text("Ask anything…", fontSize = 13.sp, color = Color.White.copy(alpha = 0.35f)) },
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(16.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = Color(0xFF00BCD4),
                        unfocusedBorderColor = Color.White.copy(alpha = 0.15f),
                        focusedTextColor = Color.White,
                        unfocusedTextColor = Color.White,
                        cursorColor = Color(0xFF00BCD4),
                        focusedContainerColor = Color.White.copy(alpha = 0.05f),
                        unfocusedContainerColor = Color.White.copy(alpha = 0.03f)
                    ),
                    maxLines = 3
                )
                Surface(
                    onClick = {
                        if (input.isBlank() || isThinking) return@Surface
                        val userText = input.trim()
                        input = ""
                        messages = messages + ChatMsg(userText, true)
                        isThinking = true
                        scope.launch {
                            val result = dispatchChatTool(userText, toolSystem, toolStates)
                            messages = messages + result
                            isThinking = false
                        }
                    },
                    shape = CircleShape,
                    color = if (!isThinking) Color(0xFF00BCD4) else Color(0xFF333333),
                    modifier = Modifier.size(46.dp)
                ) {
                    Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                        Icon(
                            Icons.Default.Send,
                            contentDescription = "Send",
                            tint = Color.White,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ChatBubble(msg: ChatMsg) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = if (msg.isUser) Alignment.End else Alignment.Start
    ) {
        if (msg.toolName != null) {
            // Tool call indicator
            Surface(
                shape = RoundedCornerShape(10.dp),
                color = Color(0xFF1A1A3A),
                border = BorderStroke(1.dp, Color(0xFF00BCD4).copy(alpha = 0.3f)),
                modifier = Modifier.padding(bottom = 4.dp)
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(pgEmoji(msg.toolName), fontSize = 12.sp)
                    Text(
                        "Used: ${msg.toolName.replace("_", " ")}",
                        fontSize = 10.sp,
                        color = Color(0xFF00BCD4),
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
        }

        Surface(
            shape = RoundedCornerShape(
                topStart = if (msg.isUser) 18.dp else 4.dp,
                topEnd = if (msg.isUser) 4.dp else 18.dp,
                bottomStart = 18.dp,
                bottomEnd = 18.dp
            ),
            color = when {
                msg.isUser  -> Color(0xFF00838F)
                msg.isError -> Color(0xFF4E0000)
                else        -> Color(0xFF1E1E3A)
            },
            border = if (msg.isError) BorderStroke(1.dp, Color(0xFFB71C1C).copy(alpha = 0.5f)) else null,
            modifier = Modifier.widthIn(max = 300.dp)
        ) {
            Text(
                msg.text,
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                fontSize = 13.sp,
                color = Color.White,
                lineHeight = 18.sp
            )
        }
    }
}

@Composable
private fun ThinkingBubble() {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Start
    ) {
        Surface(
            shape = RoundedCornerShape(topStart = 4.dp, topEnd = 18.dp, bottomStart = 18.dp, bottomEnd = 18.dp),
            color = Color(0xFF1E1E3A)
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                repeat(3) { i ->
                    val infiniteTransition = rememberInfiniteTransition(label = "dot$i")
                    val alpha by infiniteTransition.animateFloat(
                        initialValue = 0.2f, targetValue = 1f,
                        animationSpec = infiniteRepeatable(
                            animation = tween(600, delayMillis = i * 200),
                            repeatMode = RepeatMode.Reverse
                        ), label = "dot${i}alpha"
                    )
                    Box(
                        modifier = Modifier
                            .size(7.dp)
                            .background(Color(0xFF00BCD4).copy(alpha = alpha), CircleShape)
                    )
                }
            }
        }
    }
}

// ── Simple keyword-based tool dispatch for the chat playground ───────────────

private suspend fun dispatchChatTool(
    input: String,
    toolSystem: ToolSystem,
    toolStates: Map<String, Boolean>
): ChatMsg {
    val lower = input.lowercase()

    fun isOn(name: String) = toolStates[name] == true

    return try {
        when {
            // Weather
            isOn("weather") && (lower.contains("weather") || lower.contains("temperature") ||
                lower.contains("forecast") || lower.contains("rain") || lower.contains("sunny")) -> {
                val city = extractAfter(input, listOf("weather in", "weather for", "forecast for", "temperature in")) ?: "London"
                val result = toolSystem.allTools().first { it.name == "weather" }
                    .execute(mapOf("location" to city, "action" to "current"))
                if (result.success) ChatMsg(result.content, false, "weather")
                else ChatMsg("⚠️ ${result.error ?: "Weather failed"}", false, isError = true)
            }

            // Web search
            isOn("web_search") && (lower.contains("search") || lower.contains("find") ||
                lower.contains("look up") || lower.contains("google") || lower.contains("who is") ||
                lower.contains("what is") || lower.contains("latest")) -> {
                val query = extractAfter(input, listOf("search for", "search", "find", "look up", "google")) ?: input
                val result = toolSystem.allTools().first { it.name == "web_search" }
                    .execute(mapOf("query" to query))
                if (result.success) ChatMsg(result.content, false, "web_search")
                else ChatMsg("⚠️ ${result.error ?: "Search failed"}", false, isError = true)
            }

            // Translate
            isOn("translate") && (lower.contains("translate") || lower.contains(" in french") ||
                lower.contains(" in spanish") || lower.contains(" in german") || lower.contains(" to ")) -> {
                val toLang = extractLanguage(lower) ?: "es"
                val text = extractAfter(input, listOf("translate", "say")) ?: input
                val result = toolSystem.allTools().first { it.name == "translate" }
                    .execute(mapOf("text" to text, "to" to toLang))
                if (result.success) ChatMsg(result.content, false, "translate")
                else ChatMsg("⚠️ ${result.error ?: "Translation failed"}", false, isError = true)
            }

            // Calculator
            isOn("calculator") && (lower.contains("calculat") || lower.contains("compute") ||
                lower.contains("convert") || lower.contains("how much is") ||
                input.any { it in "+-*/^%" } && input.any { it.isDigit() }) -> {
                val expr = extractAfter(input, listOf("calculate", "compute", "what is", "how much is")) ?: input
                val result = toolSystem.allTools().first { it.name == "calculator" }
                    .execute(mapOf("action" to "eval", "expression" to expr))
                if (result.success) ChatMsg(result.content, false, "calculator")
                else ChatMsg("⚠️ ${result.error ?: "Calculation failed"}", false, isError = true)
            }

            // Summarize
            isOn("summarize") && (lower.contains("summar") || lower.contains("tldr") ||
                lower.contains("shorten") || input.length > 200) -> {
                val text = extractAfter(input, listOf("summarize", "summarise", "tldr", "shorten")) ?: input
                val result = toolSystem.allTools().first { it.name == "summarize" }
                    .execute(mapOf("text" to text, "sentences" to "5"))
                if (result.success) ChatMsg(result.content, false, "summarize")
                else ChatMsg("⚠️ ${result.error ?: "Summarize failed"}", false, isError = true)
            }

            // Web fetch
            isOn("web_fetch") && (lower.contains("fetch") || lower.contains("read url") ||
                lower.startsWith("http") || lower.contains("open url") || lower.contains("get page")) -> {
                val url = extractUrl(input) ?: input.trim()
                val result = toolSystem.allTools().first { it.name == "web_fetch" }
                    .execute(mapOf("url" to url))
                if (result.success) ChatMsg(result.content.take(800) + "\n…", false, "web_fetch")
                else ChatMsg("⚠️ ${result.error ?: "Fetch failed"}", false, isError = true)
            }

            // Memory recall
            isOn("memory") && (lower.contains("remember") || lower.contains("recall") ||
                lower.contains("what did") || lower.contains("what do you know")) -> {
                val query = extractAfter(input, listOf("remember", "recall", "what did i say about")) ?: input
                val result = toolSystem.allTools().first { it.name == "memory" }
                    .execute(mapOf("action" to "recall", "query" to query))
                if (result.success) ChatMsg(result.content, false, "memory")
                else ChatMsg("⚠️ ${result.error ?: "Memory recall failed"}", false, isError = true)
            }

            // GitHub
            isOn("github") && (lower.contains("github") || lower.contains("repo") || lower.contains("code")) -> {
                val query = extractAfter(input, listOf("github", "search repo", "find repo")) ?: input
                val result = toolSystem.allTools().first { it.name == "github" }
                    .execute(mapOf("action" to "search", "query" to query))
                if (result.success) ChatMsg(result.content, false, "github")
                else ChatMsg("⚠️ ${result.error ?: "GitHub search failed"}", false, isError = true)
            }

            // Status
            isOn("status") && (lower.contains("status") || lower.contains("health") || lower.contains("service")) -> {
                val result = toolSystem.allTools().first { it.name == "status" }
                    .execute(mapOf("action" to "overview"))
                if (result.success) ChatMsg(result.content, false, "status")
                else ChatMsg("⚠️ ${result.error ?: "Status failed"}", false, isError = true)
            }

            // Brave search
            isOn("brave_search") && lower.contains("brave") -> {
                val query = extractAfter(input, listOf("brave search", "brave")) ?: input
                val result = toolSystem.allTools().first { it.name == "brave_search" }
                    .execute(mapOf("query" to query, "action" to "web"))
                if (result.success) ChatMsg(result.content, false, "brave_search")
                else ChatMsg("⚠️ ${result.error ?: "Brave search failed"}", false, isError = true)
            }

            else -> ChatMsg(
                "🤔 I couldn't match your message to an enabled tool.\n\n" +
                "Try:\n• \"weather in Paris\"\n• \"search for AI news\"\n• \"translate Hello to French\"\n• \"calculate 120 * 0.85\"\n• \"summarize [text]\"\n\nOr open the Tools tab to enable more tools.",
                false
            )
        }
    } catch (e: NoSuchElementException) {
        ChatMsg("⚠️ That tool isn't available. Make sure it's enabled in Settings → AI Tools.", false, isError = true)
    } catch (e: Exception) {
        ChatMsg("⚠️ Error: ${e.message}", false, isError = true)
    }
}

private fun extractAfter(input: String, prefixes: List<String>): String? {
    for (prefix in prefixes) {
        val idx = input.lowercase().indexOf(prefix.lowercase())
        if (idx >= 0) {
            val after = input.substring(idx + prefix.length).trim()
            if (after.isNotBlank()) return after
        }
    }
    return null
}

private fun extractUrl(input: String): String? =
    Regex("https?://[^\\s]+").find(input)?.value

private fun extractLanguage(lower: String): String? = when {
    lower.contains("spanish") || lower.contains("español") -> "es"
    lower.contains("french") || lower.contains("français") -> "fr"
    lower.contains("german") || lower.contains("deutsch")  -> "de"
    lower.contains("italian")                              -> "it"
    lower.contains("portuguese")                           -> "pt"
    lower.contains("japanese")                             -> "ja"
    lower.contains("chinese")                              -> "zh"
    lower.contains("arabic")                               -> "ar"
    lower.contains("hindi")                                -> "hi"
    lower.contains("russian")                              -> "ru"
    lower.contains("korean")                               -> "ko"
    lower.contains("dutch")                                -> "nl"
    lower.contains("turkish")                              -> "tr"
    lower.contains("polish")                               -> "pl"
    lower.contains(" to ") -> lower.substringAfterLast(" to ").trim().take(5)
    else -> null
}

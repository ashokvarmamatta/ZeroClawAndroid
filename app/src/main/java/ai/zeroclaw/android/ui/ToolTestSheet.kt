package ai.zeroclaw.android.ui

import android.graphics.BitmapFactory
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.ui.*
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.*
import ai.zeroclaw.android.service.ZeroClawService
import ai.zeroclaw.android.tools.Tool
import ai.zeroclaw.android.tools.ToolResult
import ai.zeroclaw.android.tools.ToolSystem
import kotlinx.coroutines.launch

// ─────────────────────────────────────────────────────────────────────────────
// Sheet container — dispatches to the right per-tool UI
// ─────────────────────────────────────────────────────────────────────────────

@Composable
fun ToolTestSheet(
    tool: Tool,
    toolSystem: ToolSystem,
    isEnabled: Boolean,
    onClose: () -> Unit
) {
    val cat = pgCategory(tool.name)
    val accentColor = cat.gradient[1]

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .navigationBarsPadding()
    ) {
        // Sheet header
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    Brush.horizontalGradient(
                        colors = listOf(
                            cat.gradient[0].copy(alpha = 0.8f),
                            cat.gradient[1].copy(alpha = 0.5f)
                        )
                    )
                )
                .padding(horizontal = 20.dp, vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Surface(shape = RoundedCornerShape(14.dp), color = Color.White.copy(alpha = 0.15f),
                modifier = Modifier.size(50.dp)) {
                Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                    Text(pgEmoji(tool.name), fontSize = 26.sp)
                }
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(tool.name.replace("_", " ").replaceFirstChar { it.uppercase() },
                    fontWeight = FontWeight.Bold, fontSize = 18.sp, color = Color.White)
                Text(pgTagline(tool.name), fontSize = 12.sp, color = Color.White.copy(alpha = 0.7f))
            }
            if (!isEnabled) {
                Surface(shape = RoundedCornerShape(8.dp), color = Color(0xFFB71C1C).copy(alpha = 0.4f)) {
                    Text("DISABLED", modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                        fontSize = 9.sp, fontWeight = FontWeight.Bold, color = Color(0xFFEF9A9A))
                }
            }
            IconButton(onClick = onClose) {
                Icon(Icons.Default.Close, contentDescription = "Close",
                    tint = Color.White.copy(alpha = 0.7f))
            }
        }

        // Disabled warning banner
        if (!isEnabled) {
            Surface(color = Color(0xFF2A1515)) {
                Row(modifier = Modifier.fillMaxWidth().padding(12.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Text("⚠️", fontSize = 14.sp)
                    Text("This tool is disabled. Enable it in Settings → AI Tools to use it.",
                        fontSize = 12.sp, color = Color(0xFFEF9A9A), lineHeight = 16.sp)
                }
            }
        }

        // Per-tool test UI
        Box(modifier = Modifier.fillMaxWidth().heightIn(min = 300.dp, max = 600.dp)) {
            when (tool.name) {
                "web_search"     -> WebSearchTestUI(tool, accentColor, isEnabled)
                "web_fetch"      -> WebFetchTestUI(tool, accentColor, isEnabled)
                "weather"        -> WeatherTestUI(tool, accentColor, isEnabled)
                "translate"      -> TranslateTestUI(tool, accentColor, isEnabled)
                "summarize"      -> SummarizeTestUI(tool, accentColor, isEnabled)
                "calculator"     -> CalculatorTestUI(tool, accentColor, isEnabled)
                "qr_code"        -> QrCodeTestUI(tool, accentColor, isEnabled)
                "image_gen"      -> ImageGenTestUI(tool, accentColor, isEnabled)
                "memory"         -> MemoryTestUI(tool, accentColor, isEnabled)
                "github"         -> GitHubTestUI(tool, accentColor, isEnabled)
                "brave_search"   -> BraveSearchTestUI(tool, accentColor, isEnabled)
                "image_analysis" -> ImageAnalysisTestUI(tool, accentColor, isEnabled)
                "status"         -> StatusTestUI(tool, accentColor, isEnabled)
                "clipboard"      -> ClipboardTestUI(tool, accentColor, isEnabled)
                "location"       -> LocationTestUI(tool, accentColor, isEnabled)
                "rss_feed"       -> RssFeedTestUI(tool, accentColor, isEnabled)
                "bookmark"       -> BookmarkTestUI(tool, accentColor, isEnabled)
                "email"          -> EmailTestUI(tool, accentColor, isEnabled)
                "cron"           -> CronTestUI(tool, accentColor, isEnabled)
                "text_to_speech" -> TtsTestUI(tool, accentColor, isEnabled)
                "calendar"       -> CalendarTestUI(tool, accentColor, isEnabled)
                "contacts"       -> ContactsTestUI(tool, accentColor, isEnabled)
                else             -> GenericTestUI(tool, accentColor, isEnabled)
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Shared helpers
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun RunButton(label: String, accent: Color, loading: Boolean, enabled: Boolean, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        enabled = enabled && !loading,
        modifier = Modifier.fillMaxWidth().height(50.dp),
        shape = RoundedCornerShape(14.dp),
        colors = ButtonDefaults.buttonColors(containerColor = accent)
    ) {
        if (loading) {
            CircularProgressIndicator(modifier = Modifier.size(20.dp), color = Color.White, strokeWidth = 2.dp)
            Spacer(Modifier.width(10.dp))
            Text("Running…", fontWeight = FontWeight.Bold)
        } else {
            Text(label, fontWeight = FontWeight.Bold, fontSize = 15.sp)
        }
    }
}

@Composable
private fun ResultCard(accent: Color, content: @Composable ColumnScope.() -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF1A1A2E)),
        border = BorderStroke(1.dp, accent.copy(alpha = 0.3f))
    ) {
        Column(modifier = Modifier.padding(14.dp), content = content)
    }
}

@Composable
private fun ResultLabel(accent: Color) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Box(modifier = Modifier.size(6.dp).background(accent, CircleShape))
        Text("Result", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = accent)
    }
    Spacer(Modifier.height(6.dp))
}

@Composable
private fun SheetInput(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    accent: Color,
    singleLine: Boolean = true,
    minLines: Int = 1,
    maxLines: Int = if (singleLine) 1 else 5,
    trailing: (@Composable () -> Unit)? = null
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        placeholder = { Text(placeholder, fontSize = 13.sp, color = Color.White.copy(alpha = 0.3f)) },
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        singleLine = singleLine,
        minLines = minLines,
        maxLines = maxLines,
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = accent,
            unfocusedBorderColor = Color.White.copy(alpha = 0.15f),
            focusedTextColor = Color.White,
            unfocusedTextColor = Color.White,
            cursorColor = accent,
            focusedContainerColor = Color.White.copy(alpha = 0.04f),
            unfocusedContainerColor = Color.White.copy(alpha = 0.02f)
        ),
        trailingIcon = trailing
    )
}

@Composable
private fun CopyButton(text: String, accent: Color) {
    val clipboard = LocalClipboardManager.current
    IconButton(onClick = { clipboard.setText(AnnotatedString(text)) }, modifier = Modifier.size(32.dp)) {
        Icon(Icons.Default.ContentCopy, "Copy", tint = accent, modifier = Modifier.size(16.dp))
    }
}

private fun SheetPad() = Modifier.padding(horizontal = 16.dp)

// ─────────────────────────────────────────────────────────────────────────────
// Logging wrapper — every tool call goes through this so activity shows on Home
// ─────────────────────────────────────────────────────────────────────────────

private suspend fun runTool(tool: Tool, args: Map<String, String>): ToolResult {
    val argSummary = args.entries.joinToString(" · ") { (k, v) ->
        "$k=${v.take(40).replace('\n', ' ')}"
    }
    ZeroClawService.log("[Lab] ▶ ${tool.name} — $argSummary")
    val result = tool.execute(args)
    if (result.success) {
        val preview = result.content.take(80).replace('\n', ' ')
        ZeroClawService.log("[Lab] ✓ ${tool.name} — $preview")
    } else {
        ZeroClawService.log("[Lab] ✗ ${tool.name} — ${result.error?.take(80)}")
    }
    return result
}

// ─────────────────────────────────────────────────────────────────────────────
// 1 · Web Search
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun WebSearchTestUI(tool: Tool, accent: Color, isEnabled: Boolean) {
    val scope = rememberCoroutineScope()
    var query by remember { mutableStateOf("") }
    var result by remember { mutableStateOf<ToolResult?>(null) }
    var loading by remember { mutableStateOf(false) }

    LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            SheetInput(query, { query = it }, "Search the web… e.g. Kotlin coroutines tutorial", accent,
                trailing = { if (query.isNotEmpty()) IconButton({ query = "" }) { Icon(Icons.Default.Close, null, tint = Color.White.copy(0.5f)) } })
        }
        item {
            RunButton("🔍  Search", accent, loading, isEnabled && query.isNotBlank()) {
                loading = true; scope.launch {
                    result = runTool(tool,mapOf("query" to query)); loading = false
                }
            }
        }
        result?.let { r ->
            if (r.success) {
                // Parse result lines into search result cards
                val lines = r.content.lines().filter { it.isNotBlank() }
                val blocks = mutableListOf<Triple<String, String, String>>()
                var i = 0
                while (i < lines.size) {
                    val title = lines.getOrNull(i) ?: ""; val snippet = lines.getOrNull(i + 1) ?: ""
                    val url = lines.getOrNull(i + 2)?.takeIf { it.startsWith("http") } ?: ""
                    if (title.isNotBlank()) blocks.add(Triple(title, snippet, url))
                    i += if (url.isNotEmpty()) 3 else 2
                }
                if (blocks.isNotEmpty()) {
                    items(blocks) { (title, snippet, url) ->
                        SearchResultCard(title, snippet, url, accent)
                    }
                } else {
                    item { ResultCard(accent) { ResultLabel(accent); Text(r.content, fontSize = 12.sp, color = Color.White, lineHeight = 17.sp) } }
                }
            } else {
                item { ErrorCard(r.error ?: "Search failed") }
            }
        }
        item { Spacer(Modifier.height(20.dp)) }
    }
}

@Composable
private fun SearchResultCard(title: String, snippet: String, url: String, accent: Color) {
    Card(shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF1A1A2E)),
        border = BorderStroke(1.dp, accent.copy(alpha = 0.2f))) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, fontWeight = FontWeight.SemiBold, fontSize = 13.sp, color = accent, maxLines = 2)
            if (snippet.isNotBlank()) Text(snippet, fontSize = 11.sp, color = Color.White.copy(0.7f), maxLines = 3, lineHeight = 15.sp)
            if (url.isNotBlank()) Text(url, fontSize = 10.sp, color = Color.White.copy(0.35f), maxLines = 1)
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// 2 · Web Fetch
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun WebFetchTestUI(tool: Tool, accent: Color, isEnabled: Boolean) {
    val scope = rememberCoroutineScope()
    var url by remember { mutableStateOf("") }
    var result by remember { mutableStateOf<ToolResult?>(null) }
    var loading by remember { mutableStateOf(false) }
    var showFull by remember { mutableStateOf(false) }

    LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { SheetInput(url, { url = it }, "https://example.com", accent) }
        item {
            RunButton("🌐  Fetch Page", accent, loading, isEnabled && url.isNotBlank()) {
                loading = true; scope.launch { result = runTool(tool,mapOf("url" to url.trim())); loading = false }
            }
        }
        result?.let { r ->
            if (r.success) {
                item {
                    ResultCard(accent) {
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                            ResultLabel(accent)
                            Row {
                                Text("${r.content.length} chars", fontSize = 10.sp, color = Color.White.copy(0.4f))
                                Spacer(Modifier.width(8.dp))
                                CopyButton(r.content, accent)
                            }
                        }
                        Text(if (showFull) r.content else r.content.take(600) + "…",
                            fontSize = 11.sp, color = Color.White, lineHeight = 17.sp,
                            fontFamily = FontFamily.Monospace)
                        if (!showFull) {
                            Spacer(Modifier.height(6.dp))
                            TextButton(onClick = { showFull = true }) {
                                Text("Show full content", fontSize = 11.sp, color = accent)
                            }
                        }
                    }
                }
            } else { item { ErrorCard(r.error ?: "Fetch failed") } }
        }
        item { Spacer(Modifier.height(20.dp)) }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// 3 · Weather
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun WeatherTestUI(tool: Tool, accent: Color, isEnabled: Boolean) {
    val scope = rememberCoroutineScope()
    var city by remember { mutableStateOf("") }
    var mode by remember { mutableStateOf("current") }
    var result by remember { mutableStateOf<ToolResult?>(null) }
    var loading by remember { mutableStateOf(false) }

    LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { SheetInput(city, { city = it }, "City name — e.g. Tokyo, New York, Mumbai", accent) }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("current" to "Current", "forecast" to "Forecast", "brief" to "Brief").forEach { (v, label) ->
                    FilterChip(selected = mode == v, onClick = { mode = v }, label = { Text(label, fontSize = 11.sp) },
                        colors = FilterChipDefaults.filterChipColors(selectedContainerColor = accent.copy(0.25f), selectedLabelColor = accent))
                }
            }
        }
        item {
            RunButton("🌤️  Get Weather", accent, loading, isEnabled && city.isNotBlank()) {
                loading = true; scope.launch { result = runTool(tool,mapOf("location" to city, "action" to mode)); loading = false }
            }
        }
        result?.let { r ->
            if (r.success) {
                item { WeatherResultCard(r.content, accent) }
            } else { item { ErrorCard(r.error ?: "Weather failed") } }
        }
        item { Spacer(Modifier.height(20.dp)) }
    }
}

@Composable
private fun WeatherResultCard(content: String, accent: Color) {
    val emoji = when {
        content.contains("sunny", true) || content.contains("clear", true) -> "☀️"
        content.contains("rain", true) || content.contains("drizzle", true) -> "🌧️"
        content.contains("cloud", true) || content.contains("overcast", true) -> "☁️"
        content.contains("snow", true) -> "❄️"
        content.contains("storm", true) || content.contains("thunder", true) -> "⛈️"
        content.contains("fog", true) || content.contains("mist", true) -> "🌫️"
        content.contains("wind", true) -> "💨"
        else -> "🌡️"
    }
    Card(shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF0D2137)),
        border = BorderStroke(1.dp, accent.copy(0.4f))) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(emoji, fontSize = 40.sp)
                Spacer(Modifier.width(12.dp))
                Column {
                    ResultLabel(accent)
                    CopyButton(content, accent)
                }
            }
            Spacer(Modifier.height(10.dp))
            Text(content, fontSize = 12.sp, color = Color.White, lineHeight = 18.sp)
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// 4 · Translate
// ─────────────────────────────────────────────────────────────────────────────

private val LANGUAGES = listOf(
    "es" to "🇪🇸 Spanish", "fr" to "🇫🇷 French", "de" to "🇩🇪 German",
    "it" to "🇮🇹 Italian", "pt" to "🇵🇹 Portuguese", "ru" to "🇷🇺 Russian",
    "ja" to "🇯🇵 Japanese", "zh" to "🇨🇳 Chinese", "ar" to "🇸🇦 Arabic",
    "hi" to "🇮🇳 Hindi", "ko" to "🇰🇷 Korean", "nl" to "🇳🇱 Dutch",
    "tr" to "🇹🇷 Turkish", "pl" to "🇵🇱 Polish", "sv" to "🇸🇪 Swedish"
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TranslateTestUI(tool: Tool, accent: Color, isEnabled: Boolean) {
    val scope = rememberCoroutineScope()
    var text by remember { mutableStateOf("") }
    var toLang by remember { mutableStateOf("es") }
    var result by remember { mutableStateOf<ToolResult?>(null) }
    var loading by remember { mutableStateOf(false) }
    var langExpanded by remember { mutableStateOf(false) }

    LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { SheetInput(text, { text = it }, "Type text to translate…", accent, singleLine = false, minLines = 2, maxLines = 4) }
        item {
            ExposedDropdownMenuBox(expanded = langExpanded, onExpandedChange = { langExpanded = it }) {
                OutlinedTextField(
                    value = LANGUAGES.firstOrNull { it.first == toLang }?.second ?: toLang,
                    onValueChange = {}, readOnly = true,
                    label = { Text("Translate to", color = Color.White.copy(0.6f)) },
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(langExpanded) },
                    modifier = Modifier.fillMaxWidth().menuAnchor(),
                    shape = RoundedCornerShape(12.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = accent, unfocusedBorderColor = Color.White.copy(0.15f),
                        focusedTextColor = Color.White, unfocusedTextColor = Color.White)
                )
                ExposedDropdownMenu(expanded = langExpanded, onDismissRequest = { langExpanded = false },
                    modifier = Modifier.background(Color(0xFF1A1A2E))) {
                    LANGUAGES.forEach { (code, name) ->
                        DropdownMenuItem(text = { Text(name, color = Color.White) },
                            onClick = { toLang = code; langExpanded = false })
                    }
                }
            }
        }
        item {
            RunButton("🌍  Translate", accent, loading, isEnabled && text.isNotBlank()) {
                loading = true; scope.launch { result = runTool(tool,mapOf("text" to text, "to" to toLang)); loading = false }
            }
        }
        result?.let { r ->
            if (r.success) {
                item {
                    ResultCard(accent) {
                        ResultLabel(accent)
                        Text(r.content, fontSize = 14.sp, color = Color.White, lineHeight = 20.sp)
                        Spacer(Modifier.height(6.dp))
                        Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                            CopyButton(r.content, accent)
                        }
                    }
                }
            } else { item { ErrorCard(r.error ?: "Translation failed") } }
        }
        item { Spacer(Modifier.height(20.dp)) }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// 5 · Summarize
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun SummarizeTestUI(tool: Tool, accent: Color, isEnabled: Boolean) {
    val scope = rememberCoroutineScope()
    var text by remember { mutableStateOf("") }
    var sentences by remember { mutableStateOf(5) }
    var result by remember { mutableStateOf<ToolResult?>(null) }
    var loading by remember { mutableStateOf(false) }

    LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            SheetInput(text, { text = it }, "Paste text to summarize…", accent, singleLine = false, minLines = 4, maxLines = 8)
            Spacer(Modifier.height(4.dp))
            Text("${text.split(" ").filter { it.isNotBlank() }.size} words", fontSize = 10.sp, color = Color.White.copy(0.4f))
        }
        item {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Sentences: $sentences", fontSize = 12.sp, color = Color.White.copy(0.7f))
                Slider(value = sentences.toFloat(), onValueChange = { sentences = it.toInt() },
                    valueRange = 2f..10f, steps = 7,
                    colors = SliderDefaults.colors(activeTrackColor = accent, thumbColor = accent),
                    modifier = Modifier.weight(1f))
            }
        }
        item {
            RunButton("📝  Summarize", accent, loading, isEnabled && text.length > 50) {
                loading = true; scope.launch { result = runTool(tool,mapOf("text" to text, "sentences" to sentences.toString())); loading = false }
            }
        }
        result?.let { r ->
            if (r.success) {
                item {
                    ResultCard(accent) {
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                            ResultLabel(accent)
                            val origWords = text.split(" ").filter { it.isNotBlank() }.size
                            val sumWords = r.content.split(" ").filter { it.isNotBlank() }.size
                            Surface(shape = RoundedCornerShape(6.dp), color = accent.copy(0.15f)) {
                                Text("${((1 - sumWords.toFloat() / origWords) * 100).toInt()}% shorter",
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                    fontSize = 10.sp, color = accent, fontWeight = FontWeight.Bold)
                            }
                        }
                        Text(r.content, fontSize = 13.sp, color = Color.White, lineHeight = 19.sp)
                        Spacer(Modifier.height(6.dp))
                        Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) { CopyButton(r.content, accent) }
                    }
                }
            } else { item { ErrorCard(r.error ?: "Summarize failed") } }
        }
        item { Spacer(Modifier.height(20.dp)) }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// 6 · Calculator
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun CalculatorTestUI(tool: Tool, accent: Color, isEnabled: Boolean) {
    val scope = rememberCoroutineScope()
    var expr by remember { mutableStateOf("") }
    var history by remember { mutableStateOf(listOf<Pair<String, String>>()) }
    var loading by remember { mutableStateOf(false) }

    val quickExprs = listOf("2^10", "sqrt(144)", "sin(45)", "100 * 1.08", "(5 + 3) * 12 / 4")

    LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            SheetInput(expr, { expr = it }, "e.g. 2^10 + sqrt(144) / 3", accent,
                trailing = { if (expr.isNotEmpty()) IconButton({ expr = "" }) { Icon(Icons.Default.Backspace, null, tint = Color.White.copy(0.5f)) } })
        }
        item {
            Row(modifier = Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                quickExprs.forEach { q ->
                    Surface(onClick = { expr = q }, shape = RoundedCornerShape(8.dp), color = accent.copy(0.12f),
                        border = BorderStroke(1.dp, accent.copy(0.25f))) {
                        Text(q, modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp),
                            fontSize = 11.sp, color = accent, fontFamily = FontFamily.Monospace)
                    }
                }
            }
        }
        item {
            RunButton("🔢  Calculate", accent, loading, isEnabled && expr.isNotBlank()) {
                loading = true; scope.launch {
                    val r = runTool(tool,mapOf("action" to "eval", "expression" to expr))
                    if (r.success) history = listOf(expr to r.content) + history.take(9)
                    else history = listOf(expr to "⚠️ ${r.error}") + history.take(9)
                    loading = false
                }
            }
        }
        if (history.isNotEmpty()) {
            item {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    history.forEachIndexed { i, (e, ans) ->
                        Card(shape = RoundedCornerShape(12.dp),
                            colors = CardDefaults.cardColors(containerColor = if (i == 0) accent.copy(0.15f) else Color(0xFF1A1A2E)),
                            border = BorderStroke(1.dp, if (i == 0) accent.copy(0.5f) else Color.White.copy(0.07f))) {
                            Row(modifier = Modifier.fillMaxWidth().padding(12.dp),
                                horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                                Text(e, fontSize = 13.sp, color = Color.White.copy(0.6f), fontFamily = FontFamily.Monospace, modifier = Modifier.weight(1f))
                                Text("= ${ans.lines().firstOrNull() ?: ans}", fontSize = 15.sp, fontWeight = FontWeight.Bold, color = if (i == 0) accent else Color.White)
                            }
                        }
                    }
                }
            }
        }
        item { Spacer(Modifier.height(20.dp)) }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// 7 · QR Code
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun QrCodeTestUI(tool: Tool, accent: Color, isEnabled: Boolean) {
    val scope = rememberCoroutineScope()
    var text by remember { mutableStateOf("") }
    var result by remember { mutableStateOf<ToolResult?>(null) }
    var loading by remember { mutableStateOf(false) }
    var qrBitmap by remember { mutableStateOf<android.graphics.Bitmap?>(null) }

    LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { SheetInput(text, { text = it }, "Text or URL to encode…", accent) }
        item {
            Row(modifier = Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf("https://github.com", "Hello, World!", "tel:+1234567890", "mailto:test@example.com").forEach { q ->
                    Surface(onClick = { text = q }, shape = RoundedCornerShape(8.dp), color = accent.copy(0.1f),
                        border = BorderStroke(1.dp, accent.copy(0.2f))) {
                        Text(q.take(20), modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp), fontSize = 10.sp, color = accent)
                    }
                }
            }
        }
        item {
            RunButton("📷  Generate QR", accent, loading, isEnabled && text.isNotBlank()) {
                loading = true; qrBitmap = null; scope.launch {
                    val r = runTool(tool,mapOf("text" to text, "action" to "generate"))
                    result = r
                    if (r.success) {
                        val pathMatch = Regex("/[^\\s]+\\.png").find(r.content)
                        if (pathMatch != null) {
                            qrBitmap = BitmapFactory.decodeFile(pathMatch.value)
                        }
                    }
                    loading = false
                }
            }
        }
        result?.let { r ->
            if (r.success) {
                item {
                    Column(horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                        modifier = Modifier.fillMaxWidth()) {
                        qrBitmap?.let { bmp ->
                            Card(shape = RoundedCornerShape(16.dp),
                                colors = CardDefaults.cardColors(containerColor = Color.White),
                                modifier = Modifier.size(200.dp)) {
                                Image(bitmap = bmp.asImageBitmap(), contentDescription = "QR Code",
                                    modifier = Modifier.fillMaxSize().padding(12.dp))
                            }
                        } ?: run {
                            ResultCard(accent) { ResultLabel(accent); Text(r.content, fontSize = 12.sp, color = Color.White) }
                        }
                    }
                }
            } else { item { ErrorCard(r.error ?: "QR generation failed") } }
        }
        item { Spacer(Modifier.height(20.dp)) }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// 8 · Image Generation
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun ImageGenTestUI(tool: Tool, accent: Color, isEnabled: Boolean) {
    val scope = rememberCoroutineScope()
    var prompt by remember { mutableStateOf("") }
    var provider by remember { mutableStateOf("pollinations") }
    var imageUrl by remember { mutableStateOf("") }
    var resultText by remember { mutableStateOf("") }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }

    LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            SheetInput(prompt, { prompt = it }, "Describe the image… e.g. a neon cyberpunk cityscape at night", accent, singleLine = false, minLines = 2, maxLines = 3)
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("pollinations" to "🆓 Pollinations", "dalle" to "💰 DALL-E 3").forEach { (v, label) ->
                    FilterChip(selected = provider == v, onClick = { provider = v }, label = { Text(label, fontSize = 11.sp) },
                        colors = FilterChipDefaults.filterChipColors(selectedContainerColor = accent.copy(0.25f), selectedLabelColor = accent))
                }
            }
        }
        item {
            RunButton("🎨  Generate Image", accent, loading, isEnabled && prompt.isNotBlank()) {
                loading = true; imageUrl = ""; error = ""; scope.launch {
                    val r = runTool(tool,mapOf("prompt" to prompt, "provider" to provider))
                    if (r.success) {
                        resultText = r.content
                        imageUrl = Regex("https?://[^\\s]+").find(r.content)?.value ?: ""
                    } else { error = r.error ?: "Generation failed" }
                    loading = false
                }
            }
        }
        if (imageUrl.isNotBlank()) {
            item {
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    // Download and show bitmap
                    var bmp by remember { mutableStateOf<android.graphics.Bitmap?>(null) }
                    LaunchedEffect(imageUrl) {
                        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                            runCatching {
                                val conn = java.net.URL(imageUrl).openConnection()
                                conn.connectTimeout = 15000; conn.readTimeout = 30000
                                BitmapFactory.decodeStream(conn.getInputStream())
                            }.onSuccess { bmp = it }
                        }
                    }
                    bmp?.let { bitmap ->
                        Card(shape = RoundedCornerShape(16.dp),
                            colors = CardDefaults.cardColors(containerColor = Color(0xFF1A1A2E)),
                            border = BorderStroke(1.dp, accent.copy(0.4f)),
                            modifier = Modifier.fillMaxWidth().height(220.dp)) {
                            Image(bitmap = bitmap.asImageBitmap(), contentDescription = "Generated image",
                                modifier = Modifier.fillMaxSize(),
                                contentScale = androidx.compose.ui.layout.ContentScale.Crop)
                        }
                    } ?: run {
                        Card(shape = RoundedCornerShape(16.dp),
                            colors = CardDefaults.cardColors(containerColor = Color(0xFF1A1A2E)),
                            modifier = Modifier.fillMaxWidth().height(120.dp)) {
                            Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                                CircularProgressIndicator(color = accent, strokeWidth = 2.dp)
                            }
                        }
                    }
                    if (resultText.isNotBlank()) {
                        ResultCard(accent) {
                            ResultLabel(accent)
                            Text(resultText, fontSize = 11.sp, color = Color.White.copy(0.7f))
                        }
                    }
                }
            }
        }
        if (error.isNotBlank()) { item { ErrorCard(error) } }
        item { Spacer(Modifier.height(20.dp)) }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// 9 · Memory
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun MemoryTestUI(tool: Tool, accent: Color, isEnabled: Boolean) {
    val scope = rememberCoroutineScope()
    var tabIdx by remember { mutableIntStateOf(0) }
    var storeKey by remember { mutableStateOf("") }
    var storeValue by remember { mutableStateOf("") }
    var recallQuery by remember { mutableStateOf("") }
    var result by remember { mutableStateOf<ToolResult?>(null) }
    var loading by remember { mutableStateOf(false) }

    Column {
        TabRow(selectedTabIndex = tabIdx, containerColor = Color.Transparent, contentColor = accent) {
            Tab(selected = tabIdx == 0, onClick = { tabIdx = 0 }, text = { Text("💾  Store") })
            Tab(selected = tabIdx == 1, onClick = { tabIdx = 1 }, text = { Text("🔍  Recall") })
            Tab(selected = tabIdx == 2, onClick = { tabIdx = 2 }, text = { Text("🗑️  Forget") })
        }
        LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            when (tabIdx) {
                0 -> {
                    item { SheetInput(storeKey, { storeKey = it }, "Memory key — e.g. my_api_key", accent) }
                    item { SheetInput(storeValue, { storeValue = it }, "Value to store…", accent, singleLine = false, minLines = 2) }
                    item { RunButton("💾  Store", accent, loading, isEnabled && storeKey.isNotBlank() && storeValue.isNotBlank()) {
                        loading = true; scope.launch { result = runTool(tool,mapOf("action" to "store", "key" to storeKey, "value" to storeValue)); loading = false }
                    } }
                }
                1 -> {
                    item { SheetInput(recallQuery, { recallQuery = it }, "Search query — e.g. api key", accent) }
                    item { RunButton("🔍  Recall", accent, loading, isEnabled && recallQuery.isNotBlank()) {
                        loading = true; scope.launch { result = runTool(tool,mapOf("action" to "recall", "query" to recallQuery)); loading = false }
                    } }
                }
                2 -> {
                    item { SheetInput(storeKey, { storeKey = it }, "Key to forget…", accent) }
                    item { RunButton("🗑️  Forget", accent, loading, isEnabled && storeKey.isNotBlank()) {
                        loading = true; scope.launch { result = runTool(tool,mapOf("action" to "forget", "key" to storeKey)); loading = false }
                    } }
                }
            }
            result?.let { r ->
                if (r.success) {
                    item { ResultCard(accent) { ResultLabel(accent); Text(r.content, fontSize = 12.sp, color = Color.White, lineHeight = 17.sp) } }
                } else { item { ErrorCard(r.error ?: "Memory operation failed") } }
            }
            item { Spacer(Modifier.height(20.dp)) }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// 10 · GitHub
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun GitHubTestUI(tool: Tool, accent: Color, isEnabled: Boolean) {
    val scope = rememberCoroutineScope()
    var query by remember { mutableStateOf("") }
    var action by remember { mutableStateOf("search") }
    var result by remember { mutableStateOf<ToolResult?>(null) }
    var loading by remember { mutableStateOf(false) }

    LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf("search" to "Search", "readme" to "README", "issues" to "Issues").forEach { (v, l) ->
                    FilterChip(selected = action == v, onClick = { action = v }, label = { Text(l, fontSize = 11.sp) },
                        colors = FilterChipDefaults.filterChipColors(selectedContainerColor = accent.copy(0.25f), selectedLabelColor = accent))
                }
            }
        }
        item { SheetInput(query, { query = it }, if (action == "search") "Search repos — e.g. Kotlin AI" else "owner/repo — e.g. google/accompanist", accent) }
        item { RunButton("🐱  ${action.replaceFirstChar { it.uppercase() }}", accent, loading, isEnabled && query.isNotBlank()) {
            loading = true; scope.launch {
                result = runTool(tool,mapOf("action" to action, if (action == "search") "query" to query else "repo" to query))
                loading = false
            }
        } }
        result?.let { r ->
            if (r.success) {
                item { ResultCard(accent) { ResultLabel(accent); Text(r.content, fontSize = 12.sp, color = Color.White, lineHeight = 17.sp) } }
            } else { item { ErrorCard(r.error ?: "GitHub request failed") } }
        }
        item { Spacer(Modifier.height(20.dp)) }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// 11 · Brave Search
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun BraveSearchTestUI(tool: Tool, accent: Color, isEnabled: Boolean) {
    val scope = rememberCoroutineScope()
    var query by remember { mutableStateOf("") }
    var mode by remember { mutableStateOf("web") }
    var result by remember { mutableStateOf<ToolResult?>(null) }
    var loading by remember { mutableStateOf(false) }

    LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("web" to "🌐 Web", "news" to "📰 News").forEach { (v, l) ->
                    FilterChip(selected = mode == v, onClick = { mode = v }, label = { Text(l, fontSize = 11.sp) },
                        colors = FilterChipDefaults.filterChipColors(selectedContainerColor = accent.copy(0.25f), selectedLabelColor = accent))
                }
            }
        }
        item { SheetInput(query, { query = it }, "Search query…", accent) }
        item { RunButton("🦁  Brave Search", accent, loading, isEnabled && query.isNotBlank()) {
            loading = true; scope.launch { result = runTool(tool,mapOf("query" to query, "action" to mode)); loading = false }
        } }
        result?.let { r ->
            if (r.success) {
                item { ResultCard(accent) { ResultLabel(accent); Text(r.content, fontSize = 12.sp, color = Color.White, lineHeight = 17.sp) } }
            } else { item { ErrorCard(r.error ?: "Brave search failed") } }
        }
        item { Spacer(Modifier.height(20.dp)) }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Image Analysis — with image upload
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun ImageAnalysisTestUI(tool: Tool, accent: Color, isEnabled: Boolean) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var selectedUri by remember { mutableStateOf<Uri?>(null) }
    var previewBitmap by remember { mutableStateOf<android.graphics.Bitmap?>(null) }
    var prompt by remember { mutableStateOf("Describe this image in detail.") }
    var result by remember { mutableStateOf<ToolResult?>(null) }
    var loading by remember { mutableStateOf(false) }

    // Image picker launcher
    val launcher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let {
            selectedUri = it
            // Load preview bitmap
            try {
                val inputStream = context.contentResolver.openInputStream(it)
                previewBitmap = BitmapFactory.decodeStream(inputStream)
                inputStream?.close()
            } catch (_: Exception) { previewBitmap = null }
        }
    }

    LazyColumn(
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // Image picker area
        item {
            Card(
                onClick = { launcher.launch("image/*") },
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(
                    containerColor = if (selectedUri != null) Color.Transparent
                                    else Color.White.copy(alpha = 0.04f)
                ),
                border = BorderStroke(
                    width = if (selectedUri != null) 1.5.dp else 1.dp,
                    color = if (selectedUri != null) accent.copy(alpha = 0.6f)
                            else Color.White.copy(alpha = 0.12f)
                ),
                modifier = Modifier.fillMaxWidth().height(if (previewBitmap != null) 220.dp else 140.dp)
            ) {
                if (previewBitmap != null) {
                    Image(
                        bitmap = previewBitmap!!.asImageBitmap(),
                        contentDescription = "Selected image",
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop
                    )
                } else {
                    Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Surface(
                                shape = CircleShape,
                                color = accent.copy(alpha = 0.15f),
                                modifier = Modifier.size(56.dp)
                            ) {
                                Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                                    Icon(Icons.Default.AddPhotoAlternate, null,
                                        tint = accent, modifier = Modifier.size(28.dp))
                                }
                            }
                            Text("Tap to upload image", fontWeight = FontWeight.SemiBold,
                                fontSize = 14.sp, color = Color.White.copy(0.8f))
                            Text("JPG, PNG, WebP supported",
                                fontSize = 11.sp, color = Color.White.copy(0.4f))
                        }
                    }
                }
            }
        }

        // Re-pick button when image selected
        if (selectedUri != null) {
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = { launcher.launch("image/*") },
                        shape = RoundedCornerShape(10.dp),
                        border = BorderStroke(1.dp, accent.copy(0.4f)),
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("🔄  Change Image", color = accent, fontSize = 12.sp)
                    }
                    OutlinedButton(
                        onClick = { selectedUri = null; previewBitmap = null; result = null },
                        shape = RoundedCornerShape(10.dp),
                        border = BorderStroke(1.dp, Color.White.copy(0.15f))
                    ) {
                        Icon(Icons.Default.Close, null, tint = Color.White.copy(0.6f), modifier = Modifier.size(16.dp))
                    }
                }
            }
        }

        // Prompt field
        item {
            SheetInput(
                value = prompt,
                onValueChange = { prompt = it },
                placeholder = "Ask about the image…",
                accent = accent,
                singleLine = false,
                minLines = 2,
                maxLines = 3
            )
        }

        // Quick prompts
        item {
            Row(
                modifier = Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                listOf(
                    "Describe in detail",
                    "What objects are here?",
                    "Read any text",
                    "What's the mood?",
                    "Identify colors"
                ).forEach { q ->
                    Surface(
                        onClick = { prompt = q },
                        shape = RoundedCornerShape(8.dp),
                        color = accent.copy(0.1f),
                        border = BorderStroke(1.dp, accent.copy(0.2f))
                    ) {
                        Text(q, modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp),
                            fontSize = 10.sp, color = accent)
                    }
                }
            }
        }

        // Analyze button
        item {
            RunButton(
                label = "🖼️  Analyse Image",
                accent = accent,
                loading = loading,
                enabled = isEnabled && selectedUri != null
            ) {
                loading = true
                scope.launch {
                    result = runTool(tool,mapOf(
                        "source" to (selectedUri.toString()),
                        "prompt" to prompt
                    ))
                    loading = false
                }
            }
        }

        if (selectedUri == null && !isEnabled) {
            item {
                Text(
                    "Upload an image above, then tap Analyse to see what the AI sees.",
                    fontSize = 12.sp,
                    color = Color.White.copy(0.4f),
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp)
                )
            }
        }

        // Result
        result?.let { r ->
            if (r.success) {
                item {
                    ResultCard(accent) {
                        ResultLabel(accent)
                        Text(r.content, fontSize = 13.sp, color = Color.White, lineHeight = 19.sp)
                        Spacer(Modifier.height(6.dp))
                        Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                            CopyButton(r.content, accent)
                        }
                    }
                }
            } else {
                item { ErrorCard(r.error ?: "Analysis failed — ensure a vision-capable API key is configured") }
            }
        }

        item { Spacer(Modifier.height(20.dp)) }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// 12 · Status
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun StatusTestUI(tool: Tool, accent: Color, isEnabled: Boolean) {
    val scope = rememberCoroutineScope()
    var action by remember { mutableStateOf("overview") }
    var result by remember { mutableStateOf<ToolResult?>(null) }
    var loading by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        if (isEnabled) { loading = true; result = runTool(tool,mapOf("action" to "overview")); loading = false }
    }

    LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Row(modifier = Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf("overview" to "Overview", "keys" to "Keys", "logs" to "Logs", "connections" to "Channels").forEach { (v, l) ->
                    FilterChip(selected = action == v, onClick = { action = v }, label = { Text(l, fontSize = 11.sp) },
                        colors = FilterChipDefaults.filterChipColors(selectedContainerColor = accent.copy(0.25f), selectedLabelColor = accent))
                }
            }
        }
        item { RunButton("ℹ️  Get Status", accent, loading, isEnabled) {
            loading = true; scope.launch { result = runTool(tool,mapOf("action" to action)); loading = false }
        } }
        result?.let { r ->
            if (r.success) {
                item {
                    ResultCard(accent) {
                        ResultLabel(accent)
                        Text(r.content, fontSize = 11.sp, color = Color.White, lineHeight = 17.sp, fontFamily = FontFamily.Monospace)
                    }
                }
            } else { item { ErrorCard(r.error ?: "Status check failed") } }
        }
        item { Spacer(Modifier.height(20.dp)) }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// 13 · Clipboard
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun ClipboardTestUI(tool: Tool, accent: Color, isEnabled: Boolean) {
    val scope = rememberCoroutineScope()
    var writeText by remember { mutableStateOf("") }
    var result by remember { mutableStateOf<ToolResult?>(null) }
    var loading by remember { mutableStateOf(false) }

    LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { loading = true; scope.launch { result = runTool(tool,mapOf("action" to "read")); loading = false } },
                    enabled = isEnabled && !loading, shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = accent)) {
                    Text("📋  Read Clipboard")
                }
                OutlinedButton(onClick = { loading = true; scope.launch { result = runTool(tool,mapOf("action" to "clear")); loading = false } },
                    enabled = isEnabled && !loading, shape = RoundedCornerShape(12.dp),
                    border = BorderStroke(1.dp, accent.copy(0.5f))) {
                    Text("🗑  Clear", color = accent)
                }
            }
        }
        item { HorizontalDivider(color = Color.White.copy(0.1f)) }
        item { SheetInput(writeText, { writeText = it }, "Text to write to clipboard…", accent, singleLine = false, minLines = 2) }
        item { RunButton("✍️  Write to Clipboard", accent, loading, isEnabled && writeText.isNotBlank()) {
            loading = true; scope.launch { result = runTool(tool,mapOf("action" to "write", "text" to writeText)); loading = false }
        } }
        result?.let { r ->
            if (r.success) {
                item { ResultCard(accent) { ResultLabel(accent); Text(r.content, fontSize = 13.sp, color = Color.White, lineHeight = 18.sp) } }
            } else { item { ErrorCard(r.error ?: "Clipboard failed") } }
        }
        item { Spacer(Modifier.height(20.dp)) }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// 14 · Location
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun LocationTestUI(tool: Tool, accent: Color, isEnabled: Boolean) {
    val scope = rememberCoroutineScope()
    var address by remember { mutableStateOf("") }
    var action by remember { mutableStateOf("current") }
    var result by remember { mutableStateOf<ToolResult?>(null) }
    var loading by remember { mutableStateOf(false) }

    LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Row(modifier = Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf("current" to "📍 GPS", "geocode" to "🗺 Geocode", "nearby" to "🏪 Nearby").forEach { (v, l) ->
                    FilterChip(selected = action == v, onClick = { action = v }, label = { Text(l, fontSize = 11.sp) },
                        colors = FilterChipDefaults.filterChipColors(selectedContainerColor = accent.copy(0.25f), selectedLabelColor = accent))
                }
            }
        }
        if (action != "current") {
            item { SheetInput(address, { address = it }, if (action == "geocode") "Enter address…" else "Search nearby (e.g. coffee shops)", accent) }
        }
        item { RunButton("📍  ${if (action == "current") "Get My Location" else action.replaceFirstChar { it.uppercase() }}", accent, loading, isEnabled) {
            loading = true; scope.launch {
                val args = when (action) {
                    "geocode" -> mapOf("action" to action, "address" to address)
                    "nearby"  -> mapOf("action" to action, "query" to address)
                    else      -> mapOf("action" to "current")
                }
                result = runTool(tool,args); loading = false
            }
        } }
        result?.let { r ->
            if (r.success) {
                item {
                    Card(shape = RoundedCornerShape(16.dp), colors = CardDefaults.cardColors(containerColor = Color(0xFF0D2137)),
                        border = BorderStroke(1.dp, accent.copy(0.4f))) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Text("📍", fontSize = 32.sp, modifier = Modifier.align(Alignment.CenterHorizontally))
                            Spacer(Modifier.height(8.dp))
                            ResultLabel(accent)
                            Text(r.content, fontSize = 12.sp, color = Color.White, lineHeight = 18.sp)
                            Spacer(Modifier.height(6.dp))
                            Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) { CopyButton(r.content, accent) }
                        }
                    }
                }
            } else { item { ErrorCard(r.error ?: "Location failed — permission may be required") } }
        }
        item { Spacer(Modifier.height(20.dp)) }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// 15 · RSS Feed
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun RssFeedTestUI(tool: Tool, accent: Color, isEnabled: Boolean) {
    val scope = rememberCoroutineScope()
    var feedUrl by remember { mutableStateOf("") }
    var result by remember { mutableStateOf<ToolResult?>(null) }
    var loading by remember { mutableStateOf(false) }

    val quickFeeds = listOf("https://feeds.bbci.co.uk/news/rss.xml" to "BBC", "https://rss.nytimes.com/services/xml/rss/nf/Technology.xml" to "NY Times Tech")

    LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { SheetInput(feedUrl, { feedUrl = it }, "RSS feed URL…", accent) }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                quickFeeds.forEach { (url, label) ->
                    Surface(onClick = { feedUrl = url }, shape = RoundedCornerShape(8.dp), color = accent.copy(0.1f), border = BorderStroke(1.dp, accent.copy(0.2f))) {
                        Text(label, modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp), fontSize = 11.sp, color = accent)
                    }
                }
            }
        }
        item { RunButton("📡  Fetch Feed", accent, loading, isEnabled && feedUrl.isNotBlank()) {
            loading = true; scope.launch { result = runTool(tool,mapOf("url" to feedUrl, "action" to "fetch")); loading = false }
        } }
        result?.let { r ->
            if (r.success) {
                item { ResultCard(accent) { ResultLabel(accent); Text(r.content, fontSize = 12.sp, color = Color.White, lineHeight = 17.sp) } }
            } else { item { ErrorCard(r.error ?: "RSS fetch failed") } }
        }
        item { Spacer(Modifier.height(20.dp)) }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// 16 · Bookmark
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun BookmarkTestUI(tool: Tool, accent: Color, isEnabled: Boolean) {
    val scope = rememberCoroutineScope()
    var url by remember { mutableStateOf("") }
    var title by remember { mutableStateOf("") }
    var listResult by remember { mutableStateOf<ToolResult?>(null) }
    var saveResult by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        if (isEnabled) { listResult = runTool(tool,mapOf("action" to "list")) }
    }

    LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { Text("Save Bookmark", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = accent) }
        item { SheetInput(url, { url = it }, "https://…", accent) }
        item { SheetInput(title, { title = it }, "Bookmark title (optional)", accent) }
        item { RunButton("🔖  Save Bookmark", accent, loading, isEnabled && url.isNotBlank()) {
            loading = true; scope.launch {
                val r = runTool(tool,mapOf("action" to "save", "url" to url, "title" to title.ifBlank { url }))
                saveResult = if (r.success) "✓ Saved!" else "⚠️ ${r.error}"
                if (r.success) listResult = runTool(tool,mapOf("action" to "list"))
                loading = false
            }
        } }
        saveResult?.let { item { Text(it, fontSize = 12.sp, color = if (it.startsWith("✓")) Color(0xFF4CAF50) else Color(0xFFEF5350)) } }
        item { HorizontalDivider(color = Color.White.copy(0.08f)) }
        item { Text("Your Bookmarks", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = accent) }
        listResult?.let { r ->
            if (r.success) {
                item { ResultCard(accent) { ResultLabel(accent); Text(r.content, fontSize = 12.sp, color = Color.White, lineHeight = 17.sp) } }
            }
        }
        item { Spacer(Modifier.height(20.dp)) }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// 17 · Email
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun EmailTestUI(tool: Tool, accent: Color, isEnabled: Boolean) {
    val scope = rememberCoroutineScope()
    var to by remember { mutableStateOf("") }
    var subject by remember { mutableStateOf("") }
    var body by remember { mutableStateOf("") }
    var result by remember { mutableStateOf<ToolResult?>(null) }
    var loading by remember { mutableStateOf(false) }

    LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { SheetInput(to, { to = it }, "To: recipient@example.com", accent) }
        item { SheetInput(subject, { subject = it }, "Subject…", accent) }
        item { SheetInput(body, { body = it }, "Email body…", accent, singleLine = false, minLines = 4, maxLines = 7) }
        item { RunButton("📧  Send Email", accent, loading, isEnabled && to.isNotBlank() && subject.isNotBlank()) {
            loading = true; scope.launch { result = runTool(tool,mapOf("to" to to, "subject" to subject, "body" to body)); loading = false }
        } }
        result?.let { r ->
            if (r.success) {
                item { Card(shape = RoundedCornerShape(14.dp), colors = CardDefaults.cardColors(containerColor = Color(0xFF1B3A1B)),
                    border = BorderStroke(1.dp, Color(0xFF4CAF50).copy(0.5f))) {
                    Row(modifier = Modifier.padding(14.dp), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("✅", fontSize = 20.sp); Text(r.content, fontSize = 13.sp, color = Color.White)
                    }
                } }
            } else { item { ErrorCard(r.error ?: "Email send failed") } }
        }
        item { Spacer(Modifier.height(20.dp)) }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// 18 · Cron
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun CronTestUI(tool: Tool, accent: Color, isEnabled: Boolean) {
    val scope = rememberCoroutineScope()
    var taskName by remember { mutableStateOf("") }
    var prompt by remember { mutableStateOf("") }
    var schedule by remember { mutableStateOf("0 9 * * *") }
    var result by remember { mutableStateOf<ToolResult?>(null) }
    var loading by remember { mutableStateOf(false) }

    val presets = listOf("Every hour" to "0 * * * *", "Daily 9am" to "0 9 * * *", "Every 30min" to "*/30 * * * *", "Weekly Mon" to "0 9 * * 1")

    LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { SheetInput(taskName, { taskName = it }, "Task name — e.g. morning_briefing", accent) }
        item { SheetInput(prompt, { prompt = it }, "AI prompt to run — e.g. Summarize the latest tech news", accent, singleLine = false, minLines = 2) }
        item {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Schedule (cron expression)", fontSize = 12.sp, color = Color.White.copy(0.6f))
                SheetInput(schedule, { schedule = it }, "cron expression", accent)
                Row(modifier = Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    presets.forEach { (label, expr) ->
                        Surface(onClick = { schedule = expr }, shape = RoundedCornerShape(8.dp), color = accent.copy(0.1f), border = BorderStroke(1.dp, accent.copy(0.2f))) {
                            Text(label, modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp), fontSize = 10.sp, color = accent)
                        }
                    }
                }
            }
        }
        item { RunButton("⏰  Schedule Task", accent, loading, isEnabled && taskName.isNotBlank() && prompt.isNotBlank()) {
            loading = true; scope.launch { result = runTool(tool,mapOf("action" to "schedule", "name" to taskName, "prompt" to prompt, "schedule" to schedule)); loading = false }
        } }
        result?.let { r ->
            if (r.success) {
                item { ResultCard(accent) { ResultLabel(accent); Text(r.content, fontSize = 12.sp, color = Color.White, lineHeight = 17.sp) } }
            } else { item { ErrorCard(r.error ?: "Cron schedule failed") } }
        }
        item { Spacer(Modifier.height(20.dp)) }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// 19 · Text to Speech
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun TtsTestUI(tool: Tool, accent: Color, isEnabled: Boolean) {
    val scope = rememberCoroutineScope()
    var text by remember { mutableStateOf("") }
    var result by remember { mutableStateOf<ToolResult?>(null) }
    var loading by remember { mutableStateOf(false) }

    val quickTexts = listOf("Hello! I am ZeroClaw, your AI assistant.", "The weather today is sunny and warm.", "Your meeting starts in 10 minutes.")

    LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { SheetInput(text, { text = it }, "Type text to speak aloud…", accent, singleLine = false, minLines = 3) }
        item {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                quickTexts.forEach { q ->
                    Surface(onClick = { text = q }, shape = RoundedCornerShape(10.dp), color = Color.White.copy(0.05f), border = BorderStroke(1.dp, Color.White.copy(0.1f))) {
                        Text(q, modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp), fontSize = 12.sp, color = Color.White.copy(0.7f))
                    }
                }
            }
        }
        item { RunButton("🔊  Speak", accent, loading, isEnabled && text.isNotBlank()) {
            loading = true; scope.launch { result = runTool(tool,mapOf("text" to text, "action" to "speak")); loading = false }
        } }
        result?.let { r ->
            if (r.success) {
                item { Card(shape = RoundedCornerShape(14.dp), colors = CardDefaults.cardColors(containerColor = Color(0xFF1B3A1B)),
                    border = BorderStroke(1.dp, Color(0xFF4CAF50).copy(0.5f))) {
                    Row(modifier = Modifier.padding(14.dp), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("🔊", fontSize = 20.sp); Text(r.content, fontSize = 13.sp, color = Color.White)
                    }
                } }
            } else { item { ErrorCard(r.error ?: "TTS failed") } }
        }
        item { Spacer(Modifier.height(20.dp)) }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// 20 · Calendar
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun CalendarTestUI(tool: Tool, accent: Color, isEnabled: Boolean) {
    val scope = rememberCoroutineScope()
    var result by remember { mutableStateOf<ToolResult?>(null) }
    var loading by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        if (isEnabled) { loading = true; result = runTool(tool,mapOf("action" to "list", "date" to "today")); loading = false }
    }

    LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("today" to "Today", "tomorrow" to "Tomorrow", "week" to "This Week").forEach { (d, l) ->
                    Surface(onClick = { loading = true; scope.launch { result = runTool(tool,mapOf("action" to "list", "date" to d)); loading = false } },
                        shape = RoundedCornerShape(10.dp), color = accent.copy(0.12f), border = BorderStroke(1.dp, accent.copy(0.25f))) {
                        Text(l, modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp), fontSize = 11.sp, color = accent)
                    }
                }
            }
        }
        if (loading) { item { Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = accent) } } }
        result?.let { r ->
            if (r.success) {
                item { ResultCard(accent) { ResultLabel(accent); Text(r.content, fontSize = 12.sp, color = Color.White, lineHeight = 17.sp) } }
            } else { item { ErrorCard(r.error ?: "Calendar access failed — check permissions") } }
        }
        item { Spacer(Modifier.height(20.dp)) }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// 21 · Contacts
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun ContactsTestUI(tool: Tool, accent: Color, isEnabled: Boolean) {
    val scope = rememberCoroutineScope()
    var query by remember { mutableStateOf("") }
    var result by remember { mutableStateOf<ToolResult?>(null) }
    var loading by remember { mutableStateOf(false) }

    LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { SheetInput(query, { query = it }, "Search contacts by name…", accent) }
        item { RunButton("👥  Search Contacts", accent, loading, isEnabled && query.isNotBlank()) {
            loading = true; scope.launch { result = runTool(tool,mapOf("action" to "search", "name" to query)); loading = false }
        } }
        result?.let { r ->
            if (r.success) {
                item { ResultCard(accent) { ResultLabel(accent); Text(r.content, fontSize = 12.sp, color = Color.White, lineHeight = 18.sp) } }
            } else { item { ErrorCard(r.error ?: "Contact search failed — check permissions") } }
        }
        item { Spacer(Modifier.height(20.dp)) }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Generic fallback for remaining tools
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun GenericTestUI(tool: Tool, accent: Color, isEnabled: Boolean) {
    val scope = rememberCoroutineScope()
    val params = tool.parameters
    val argState = remember { mutableStateMapOf<String, String>() }
    var result by remember { mutableStateOf<ToolResult?>(null) }
    var loading by remember { mutableStateOf(false) }

    LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Text("Parameters", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = accent)
        }
        items(params) { param ->
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(param.name, fontSize = 12.sp, fontWeight = FontWeight.Medium, color = Color.White.copy(0.8f))
                    if (!param.required) Surface(shape = RoundedCornerShape(4.dp), color = Color.White.copy(0.1f)) {
                        Text("optional", modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp), fontSize = 9.sp, color = Color.White.copy(0.5f))
                    }
                }
                SheetInput(argState[param.name] ?: "", { argState[param.name] = it }, param.description.take(60), accent)
            }
        }
        item {
            RunButton("▶  Run ${tool.name.replace("_", " ").replaceFirstChar { it.uppercase() }}", accent, loading, isEnabled) {
                loading = true; scope.launch {
                    result = runTool(tool,argState.toMap()); loading = false
                }
            }
        }
        result?.let { r ->
            if (r.success) {
                item { ResultCard(accent) { ResultLabel(accent); Text(r.content, fontSize = 12.sp, color = Color.White, lineHeight = 17.sp) } }
            } else { item { ErrorCard(r.error ?: "Tool execution failed") } }
        }
        item { Spacer(Modifier.height(20.dp)) }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Error card
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun ErrorCard(message: String) {
    Card(shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF3B1010)),
        border = BorderStroke(1.dp, Color(0xFFB71C1C).copy(0.5f))) {
        Row(modifier = Modifier.padding(14.dp), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.Top) {
            Text("⚠️", fontSize = 18.sp)
            Text(message, fontSize = 12.sp, color = Color(0xFFEF9A9A), lineHeight = 17.sp, modifier = Modifier.weight(1f))
        }
    }
}

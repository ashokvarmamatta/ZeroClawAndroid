package ai.zeroclaw.android.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
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
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.UriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ai.zeroclaw.android.data.AppSettings
import ai.zeroclaw.android.data.LlmKeyManager
import ai.zeroclaw.android.data.LlmProvider
import ai.zeroclaw.android.tools.ToolSystem
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    onNavigateToApiKeys: () -> Unit,
    onNavigateToAiTools: () -> Unit = {},
    onNavigateToInfo: (String) -> Unit = {}
) {
    val context = LocalContext.current
    val settings = remember { AppSettings(context) }
    val keyManager = remember { LlmKeyManager.getInstance(context) }
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }

    var zeroClawUrl by remember { mutableStateOf("http://127.0.0.1:3000") }
    var telegramToken by remember { mutableStateOf("") }
    var twilioSid by remember { mutableStateOf("") }
    var twilioToken by remember { mutableStateOf("") }
    var twilioFrom by remember { mutableStateOf("whatsapp:+14155238886") }
    var discordToken by remember { mutableStateOf("") }
    var signalApiUrl by remember { mutableStateOf("") }
    var slackToken by remember { mutableStateOf("") }
    var matrixConfig by remember { mutableStateOf("") }
    var ircConfig by remember { mutableStateOf("") }
    var teamsConfig by remember { mutableStateOf("") }
    var twitchConfig by remember { mutableStateOf("") }
    var lineToken by remember { mutableStateOf("") }
    var webChatEnabled by remember { mutableStateOf(false) }
    var autoStart by remember { mutableStateOf(true) }
    var optimizePrompt by remember { mutableStateOf(false) }
    var offlineWebSummarize by remember { mutableStateOf(true) }
    var keyCount by remember { mutableStateOf(0) }
    var activeKeyLabel by remember { mutableStateOf("") }

    // Tool count for display in nav button
    val toolSystem = remember { ToolSystem.getInstance(context) }
    var enabledToolCount by remember { mutableIntStateOf(0) }
    val totalToolCount = remember { toolSystem.allTools().size }

    LaunchedEffect(Unit) {
        enabledToolCount = toolSystem.allTools().count { toolSystem.isEnabled(it.name) }
        settings.getAll().let { s ->
            zeroClawUrl = s.zeroClawUrl
            telegramToken = s.telegramToken
            twilioSid = s.twilioSid
            twilioToken = s.twilioToken
            twilioFrom = s.twilioFrom
            discordToken = s.discordToken
            signalApiUrl = s.signalApiUrl
            slackToken = s.slackToken
            matrixConfig = s.matrixConfig
            ircConfig = s.ircConfig
            teamsConfig = s.teamsConfig
            twitchConfig = s.twitchConfig
            lineToken = s.lineToken
            webChatEnabled = s.webChatEnabled
            autoStart = s.autoStart
            optimizePrompt = s.optimizePrompt
            offlineWebSummarize = s.offlineWebSummarize
        }
        val keys = keyManager.loadKeys()
        keyCount = keys.count { it.enabled }
        activeKeyLabel = keys.firstOrNull { it.enabled }?.let {
            "${LlmProvider.fromId(it.provider).emoji} ${it.label}"
        } ?: "None"
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text("Settings", fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, "Back") }
                },
                actions = {
                    IconButton(onClick = {
                        scope.launch {
                            settings.save(
                                zeroClawUrl, telegramToken, twilioSid,
                                twilioToken, twilioFrom, "", "", autoStart, discordToken, signalApiUrl,
                                slackToken, matrixConfig, ircConfig, teamsConfig, twitchConfig,
                                lineToken, webChatEnabled, optimizePrompt, offlineWebSummarize
                            )
                            snackbarHostState.showSnackbar("Settings saved!")
                        }
                    }) { Icon(Icons.Default.Save, "Save") }
                }
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            item { SectionHeaderWithInfo("🧠 AI / LLM Provider", "how") { onNavigateToInfo("how") } }
            item {
                ApiKeysButton(keyCount = keyCount, activeKeyLabel = activeKeyLabel,
                    onClick = onNavigateToApiKeys)
            }
            item { OfflineModelSourcesSection() }
            item { ApiKeyProvidersSection() }
            // ── AI TOOLS NAV BUTTON ───────────────────────────────────
            item { SectionHeaderWithInfo("🔧 AI Tools", "tools") { onNavigateToInfo("tools") } }
            item { AiToolsNavButton(enabledCount = enabledToolCount, totalCount = totalToolCount, onClick = onNavigateToAiTools) }

            item { SectionHeader("⚙️ ZeroClaw Configuration") }
            item { SettingsTextField("ZeroClaw API URL", zeroClawUrl, false) { zeroClawUrl = it } }
            item { SectionHeaderWithInfo("✈️ Telegram Bot", "telegram") { onNavigateToInfo("telegram") } }
            item {
                SettingsTextField("Bot Token (from @BotFather)", telegramToken, true) { telegramToken = it }
            }
            item { SectionHeaderWithInfo("🎮 Discord Bot", "how") { onNavigateToInfo("how") } }
            item {
                SettingsTextField("Bot Token (from Discord Developer Portal)", discordToken, true) { discordToken = it }
            }
            item { SectionHeader("📡 Signal (via signal-cli)") }
            item {
                SettingsTextField("signal-cli REST API URL (e.g. http://192.168.1.100:8080)", signalApiUrl, false) { signalApiUrl = it }
            }
            item { SectionHeaderWithInfo("💬 WhatsApp (Twilio)", "whatsapp") { onNavigateToInfo("whatsapp") } }
            item { SettingsTextField("Twilio Account SID", twilioSid, false) { twilioSid = it } }
            item { SettingsTextField("Twilio Auth Token", twilioToken, true) { twilioToken = it } }
            item { SettingsTextField("WhatsApp From Number", twilioFrom, false) { twilioFrom = it } }
            item { SectionHeader("💼 Slack Bot") }
            item {
                SettingsTextField("Bot + App Token (xoxb-...|xapp-...)", slackToken, true) { slackToken = it }
            }
            item { SectionHeader("🟣 Matrix Bot") }
            item {
                SettingsTextField("Config (https://homeserver|access_token)", matrixConfig, true) { matrixConfig = it }
            }
            item { SectionHeader("📡 IRC Bot") }
            item {
                SettingsTextField("Config (server:port|nickname|#channel1,#channel2)", ircConfig, false) { ircConfig = it }
            }
            item { SectionHeader("💼 Microsoft Teams Bot") }
            item {
                SettingsTextField("Config (botId|botSecret)", teamsConfig, true) { teamsConfig = it }
            }
            item { SectionHeader("🎮 Twitch Bot") }
            item {
                SettingsTextField("Config (oauth:token|botname|#channel1,#channel2)", twitchConfig, true) { twitchConfig = it }
            }
            item { SectionHeader("💚 LINE Bot") }
            item {
                SettingsTextField("Channel Access Token", lineToken, true) { lineToken = it }
            }
            item { SectionHeader("🌐 Web Chat") }
            item {
                Row(modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Enable Web Chat Server", fontWeight = FontWeight.Medium)
                        Text("Serves a chat UI on port 8088", fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Switch(checked = webChatEnabled, onCheckedChange = { webChatEnabled = it })
                }
            }
            item { SectionHeaderWithInfo("🔧 Behavior", "config_ux") { onNavigateToInfo("config_ux") } }
            item {
                Row(modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Auto-start on Boot", fontWeight = FontWeight.Medium)
                        Text("Start ZeroClaw when phone restarts", fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Switch(checked = autoStart, onCheckedChange = { autoStart = it })
                }
            }
            item {
                Row(modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Optimize prompt before sending", fontWeight = FontWeight.Medium)
                        Text("Summarize long messages before sending to offline model (default: off)", fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Switch(checked = optimizePrompt, onCheckedChange = { optimizePrompt = it })
                }
            }
            item {
                Row(modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Summarize real-time data (offline)", fontWeight = FontWeight.Medium)
                        Text("Fetch web data when offline model can't answer, then let the model summarize it (default: on)", fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Switch(checked = offlineWebSummarize, onCheckedChange = { offlineWebSummarize = it })
                }
            }
            item { SectionHeaderWithInfo("🚀 Advanced Features", "nullclaw") { onNavigateToInfo("nullclaw") } }
            item {
                Card(
                    shape = RoundedCornerShape(14.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
                    )
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            "Composio, MCP, A2A, Delegate, Spawn, MessageTool, and more — tap ⓘ above to learn about each feature before enabling it.",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            lineHeight = 17.sp
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "Most advanced features are disabled by default and require API keys or server setup. Enable them in the AI Tools section above once configured.",
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f),
                            lineHeight = 16.sp
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun ApiKeysButton(keyCount: Int, activeKeyLabel: String, onClick: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f)
        ),
        onClick = onClick
    ) {
        Row(modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Icon(Icons.Default.Key, null,
                tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(28.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text("Manage API Keys", fontWeight = FontWeight.Bold, fontSize = 15.sp)
                if (keyCount == 0) {
                    Text("No keys configured — tap to add",
                        fontSize = 12.sp, color = MaterialTheme.colorScheme.error)
                } else {
                    Text("$keyCount key${if (keyCount > 1) "s" else ""} · failover active",
                        fontSize = 12.sp, color = Color(0xFF4CAF50))
                    if (activeKeyLabel.isNotBlank())
                        Text("Active: $activeKeyLabel", fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Icon(Icons.Default.ChevronRight, null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
fun SectionHeader(title: String) {
    Text(title, fontWeight = FontWeight.Bold, fontSize = 16.sp,
        color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 8.dp))
}

/**
 * Section header with an ⓘ button that opens the InfoScreen to a specific section.
 * The button helps users understand how a feature works before configuring it.
 */
@Composable
fun SectionHeaderWithInfo(title: String, sectionId: String, onInfoClick: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(title, fontWeight = FontWeight.Bold, fontSize = 16.sp,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.weight(1f))
        IconButton(
            onClick = onInfoClick,
            modifier = Modifier.size(32.dp)
        ) {
            Icon(
                Icons.Default.Info,
                contentDescription = "Learn more about $title",
                tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.7f),
                modifier = Modifier.size(18.dp)
            )
        }
    }
}

@Composable
fun SettingsTextField(label: String, value: String, secret: Boolean, onValueChange: (String) -> Unit) {
    var showSecret by remember { mutableStateOf(false) }
    OutlinedTextField(
        value = value, onValueChange = onValueChange,
        label = { Text(label) },
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        singleLine = true,
        visualTransformation = if (secret && !showSecret) PasswordVisualTransformation()
                               else VisualTransformation.None,
        trailingIcon = if (secret) {
            {
                IconButton(onClick = { showSecret = !showSecret }) {
                    Icon(if (showSecret) Icons.Default.VisibilityOff else Icons.Default.Visibility, null)
                }
            }
        } else null
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DropdownSetting(label: String, value: String, options: List<String>, onSelect: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
        OutlinedTextField(
            value = value, onValueChange = {}, readOnly = true,
            label = { Text(label) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
            modifier = Modifier.fillMaxWidth().menuAnchor(),
            shape = RoundedCornerShape(12.dp)
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.forEach { opt ->
                DropdownMenuItem(text = { Text(opt) },
                    onClick = { onSelect(opt); expanded = false })
            }
        }
    }
}

@Composable
fun AiToolsNavButton(enabledCount: Int, totalCount: Int, onClick: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
        ),
        onClick = onClick
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Text("🔧", fontSize = 26.sp)
            Column(modifier = Modifier.weight(1f)) {
                Text("Manage AI Tools", fontWeight = FontWeight.Bold, fontSize = 15.sp)
                if (enabledCount == 0) {
                    Text(
                        "All tools off — tap to enable tools and see the flow diagram",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    Text(
                        "$enabledCount / $totalCount tools active",
                        fontSize = 12.sp,
                        color = Color(0xFF4CAF50)
                    )
                    Text(
                        "Tap to configure tools + view AI pipeline",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            Icon(
                Icons.Default.ChevronRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

// ── Where to Get Models ──────────────────────────────────────────────────────

@Composable
fun OfflineModelSourcesSection() {
    var expanded by remember { mutableStateOf(false) }
    val uriHandler = LocalUriHandler.current

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
        ),
        shape = RoundedCornerShape(16.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp).animateContentSize()) {
            Surface(
                onClick = { expanded = !expanded },
                color = Color.Transparent,
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("📥", fontSize = 18.sp)
                        Spacer(modifier = Modifier.width(10.dp))
                        Column {
                            Text("Where to Get Models", fontWeight = FontWeight.Bold,
                                fontSize = 14.sp, color = Color(0xFF4CAF50))
                            Text("Download .bin model files for offline use",
                                fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    Text(if (expanded) "▲" else "▼", fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }

            AnimatedVisibility(visible = expanded) {
                Column(modifier = Modifier.padding(top = 12.dp)) {
                    ResourceLink("Kaggle — Google Gemma", "Official Gemma 2B/7B for MediaPipe. Recommended!",
                        Color(0xFF4CAF50), "https://kaggle.com/models/google/gemma", uriHandler, badge = "RECOMMENDED")
                    ResourceLink("Hugging Face Hub", "Largest model repository — GGUF, .bin, safetensors",
                        Color(0xFFFFB300), "https://huggingface.co/models", uriHandler)
                    ResourceLink("Ollama Library", "Curated models — Llama, Gemma, Phi, Mistral",
                        Color(0xFF00BCD4), "https://ollama.com/library", uriHandler)
                    ResourceLink("LM Studio", "Desktop app with model browser — GGUF models",
                        Color(0xFFE91E63), "https://lmstudio.ai", uriHandler)
                    ResourceLink("GPT4All", "Models optimized for consumer hardware",
                        Color(0xFF4CAF50), "https://gpt4all.io", uriHandler)
                    ResourceLink("Mozilla Llamafile", "Single-file executables — model + runtime",
                        Color(0xFFFFB300), "https://github.com/Mozilla-Ocho/llamafile", uriHandler)
                }
            }
        }
    }
}

// ── Where to Get API Keys ────────────────────────────────────────────────────

@Composable
fun ApiKeyProvidersSection() {
    var expanded by remember { mutableStateOf(false) }
    val uriHandler = LocalUriHandler.current

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
        ),
        shape = RoundedCornerShape(16.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp).animateContentSize()) {
            Surface(
                onClick = { expanded = !expanded },
                color = Color.Transparent,
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("🔑", fontSize = 18.sp)
                        Spacer(modifier = Modifier.width(10.dp))
                        Column {
                            Text("Where to Get API Keys", fontWeight = FontWeight.Bold,
                                fontSize = 14.sp, color = Color(0xFF00BCD4))
                            Text("Free & paid providers — tap to expand",
                                fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    Text(if (expanded) "▲" else "▼", fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }

            AnimatedVisibility(visible = expanded) {
                Column(modifier = Modifier.padding(top = 12.dp)) {
                    // Free tier providers
                    Text("Free Tier", fontWeight = FontWeight.Bold, fontSize = 12.sp,
                        color = Color(0xFF4CAF50), modifier = Modifier.padding(bottom = 6.dp))
                    ResourceLink("Google Gemini — AI Studio", "Most generous free tier — Gemini 2.0 Flash, 1.5 Pro",
                        Color(0xFF4CAF50), "https://aistudio.google.com/apikey", uriHandler, badge = "BEST FREE")
                    ResourceLink("Groq", "Blazing fast — Llama 3, Mixtral, Gemma",
                        Color(0xFF00BCD4), "https://console.groq.com/keys", uriHandler, badge = "FAST")
                    ResourceLink("OpenRouter", "Multi-model gateway — single key, many providers",
                        Color(0xFFFFB300), "https://openrouter.ai/keys", uriHandler, badge = "MULTI")
                    ResourceLink("NVIDIA NIM", "1000 free credits — Llama, Mistral",
                        Color(0xFF4CAF50), "https://build.nvidia.com", uriHandler)
                    ResourceLink("Together AI", "Free credits on signup — wide model selection",
                        Color(0xFF00BCD4), "https://api.together.ai/settings/api-keys", uriHandler)
                    ResourceLink("Mistral AI", "Free experiment tier — Mistral Small, Large",
                        Color(0xFFFFB300), "https://console.mistral.ai/api-keys", uriHandler)
                    ResourceLink("DeepSeek", "Free credits — V3, R1 reasoning, Coder",
                        Color(0xFF4CAF50), "https://platform.deepseek.com/api_keys", uriHandler)
                    ResourceLink("Cohere", "Free trial — Command R/R+, RAG, embeddings",
                        Color(0xFF00BCD4), "https://dashboard.cohere.com/api-keys", uriHandler)
                    ResourceLink("Hugging Face", "Free tier for thousands of models",
                        Color(0xFFFFB300), "https://huggingface.co/settings/tokens", uriHandler)
                    ResourceLink("SambaNova Cloud", "Free tier — fast custom hardware",
                        Color(0xFF4CAF50), "https://cloud.sambanova.ai/apis", uriHandler)
                    ResourceLink("Cerebras", "Free tier — extremely fast inference",
                        Color(0xFF00BCD4), "https://cloud.cerebras.ai", uriHandler)
                    ResourceLink("Fireworks AI", "Free credits — fast open models",
                        Color(0xFFFFB300), "https://fireworks.ai/account/api-keys", uriHandler)
                    ResourceLink("DeepInfra", "Free credits — serverless inference",
                        Color(0xFF4CAF50), "https://deepinfra.com/dash/api_keys", uriHandler)
                    ResourceLink("Cloudflare Workers AI", "10K free neurons/day — edge inference",
                        Color(0xFF00BCD4), "https://dash.cloudflare.com", uriHandler)
                    ResourceLink("GitHub Models", "Free — GPT-4o, Llama, Mistral via GitHub",
                        Color(0xFFFFB300), "https://github.com/marketplace/models", uriHandler)

                    Spacer(modifier = Modifier.height(12.dp))

                    // Paid providers
                    Text("Paid", fontWeight = FontWeight.Bold, fontSize = 12.sp,
                        color = Color(0xFFFF6D00), modifier = Modifier.padding(bottom = 6.dp))
                    ResourceLink("OpenAI", "GPT-4o, o1, o3, DALL-E — prepaid credits",
                        Color(0xFFFF6D00), "https://platform.openai.com/api-keys", uriHandler, badge = "PAID")
                    ResourceLink("Anthropic Claude", "Claude Opus 4, Sonnet 4 — billing required",
                        Color(0xFFFF6D00), "https://console.anthropic.com/settings/keys", uriHandler, badge = "PAID")
                    ResourceLink("Perplexity API", "Search-augmented LLMs with citations",
                        Color(0xFFFF6D00), "https://www.perplexity.ai/settings/api", uriHandler, badge = "PAID")
                    ResourceLink("Azure OpenAI", "Enterprise OpenAI on Azure — \$200 new account credits",
                        Color(0xFFFF6D00), "https://azure.microsoft.com/en-us/products/ai-services/openai-service", uriHandler, badge = "PAID")
                    ResourceLink("AWS Bedrock", "Claude, Llama, Mistral on AWS",
                        Color(0xFFFF6D00), "https://aws.amazon.com/bedrock", uriHandler, badge = "PAID")
                    ResourceLink("Google Vertex AI", "\$300 free GCP credits — Gemini, PaLM, Imagen",
                        Color(0xFFFF6D00), "https://console.cloud.google.com", uriHandler, badge = "PAID")
                    ResourceLink("Replicate", "Pay-per-second — open models + image/video/audio",
                        Color(0xFFFF6D00), "https://replicate.com/account/api-tokens", uriHandler, badge = "PAID")
                }
            }
        }
    }
}

// ── Resource Link composable ─────────────────────────────────────────────────

@Composable
fun ResourceLink(
    title: String,
    description: String,
    accentColor: Color,
    url: String,
    uriHandler: UriHandler,
    badge: String? = null
) {
    Surface(
        onClick = { uriHandler.openUri(url) },
        shape = RoundedCornerShape(10.dp),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.5f),
        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(title, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                        color = accentColor)
                    if (badge != null) {
                        Spacer(modifier = Modifier.width(6.dp))
                        Surface(
                            shape = RoundedCornerShape(4.dp),
                            color = accentColor.copy(alpha = 0.15f)
                        ) {
                            Text(badge,
                                modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp),
                                fontSize = 8.sp, fontWeight = FontWeight.Bold,
                                color = accentColor)
                        }
                    }
                }
                Text(description, fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f))
            }
            Text("↗", fontSize = 14.sp,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f))
        }
    }
}

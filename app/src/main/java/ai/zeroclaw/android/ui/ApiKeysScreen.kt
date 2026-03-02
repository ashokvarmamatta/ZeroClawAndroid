package ai.zeroclaw.android.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import ai.zeroclaw.android.data.ApiKeyEntry
import ai.zeroclaw.android.data.LlmKeyManager
import ai.zeroclaw.android.data.LlmProvider
import ai.zeroclaw.android.data.LlmRouter
import kotlinx.coroutines.launch

// ── Validation state ──────────────────────────────────────────────────────────

enum class ValidationState { IDLE, LOADING, SUCCESS, RATE_LIMITED, ERROR }

data class ValidationUi(
    val state: ValidationState = ValidationState.IDLE,
    val message: String = "",
    val models: List<String> = emptyList()
)

private const val GEMINI_DEFAULT_MODEL = "gemini-2.5-flash-preview-04-17"

// ── Main screen ───────────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ApiKeysScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val keyManager = remember { LlmKeyManager.getInstance(context) }
    var keys by remember { mutableStateOf(keyManager.loadKeys()) }
    var showAddDialog by remember { mutableStateOf(false) }
    var editingKey by remember { mutableStateOf<ApiKeyEntry?>(null) }

    fun refresh() { keys = keyManager.loadKeys() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("API Keys", fontWeight = FontWeight.Bold)
                        Text("${keys.count { it.enabled }} active · failover enabled",
                            fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, "Back") }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer
                )
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { showAddDialog = true },
                icon = { Icon(Icons.Default.Add, null) },
                text = { Text("Add Key") },
                containerColor = MaterialTheme.colorScheme.primary
            )
        }
    ) { padding ->
        if (keys.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize().padding(padding),
                contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("🔑", fontSize = 52.sp)
                    Text("No API keys yet", fontWeight = FontWeight.Bold, fontSize = 18.sp)
                    Text("Tap + to configure your first LLM provider.",
                        fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(8.dp))
                    Button(onClick = { showAddDialog = true }) {
                        Icon(Icons.Default.Add, null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Add First Key")
                    }
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                item { FailoverBanner() }
                itemsIndexed(keys) { index, entry ->
                    ApiKeyCard(
                        entry = entry,
                        index = index,
                        isActive = index == 0 && entry.enabled,
                        isFirst = index == 0,
                        isLast = index == keys.lastIndex,
                        onToggle = {
                            keyManager.updateKey(entry.copy(enabled = !entry.enabled))
                            refresh()
                        },
                        onEdit = { editingKey = entry },
                        onDelete = { keyManager.deleteKey(entry.id); refresh() },
                        onMoveUp = { keyManager.moveKey(entry.id, -1); refresh() },
                        onMoveDown = { keyManager.moveKey(entry.id, +1); refresh() },
                        onSetActive = { keyManager.setActiveKey(entry.id); refresh() }
                    )
                }
                item { Spacer(Modifier.height(80.dp)) }
            }
        }
    }

    if (showAddDialog) {
        KeyEditDialog(
            existing = null,
            onDismiss = { showAddDialog = false },
            onSave = { entry -> keyManager.addKey(entry); refresh(); showAddDialog = false }
        )
    }

    editingKey?.let { key ->
        KeyEditDialog(
            existing = key,
            onDismiss = { editingKey = null },
            onSave = { updated -> keyManager.updateKey(updated); refresh(); editingKey = null }
        )
    }
}

// ── Failover banner ───────────────────────────────────────────────────────────

@Composable
fun FailoverBanner() {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)
    ) {
        Row(modifier = Modifier.padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Icon(Icons.Default.Shield, null,
                tint = MaterialTheme.colorScheme.secondary, modifier = Modifier.size(22.dp))
            Column {
                Text("Auto Failover Active", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                Text("Keys are tried top → bottom. Use ↑↓ to reorder priority. Tap ★ to set the default active key.",
                    fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    lineHeight = 16.sp)
            }
        }
    }
}

@Composable
fun ApiKeyCard(
    entry: ApiKeyEntry,
    index: Int,
    isActive: Boolean,
    isFirst: Boolean,
    isLast: Boolean,
    onToggle: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    onSetActive: () -> Unit
) {
    val provider = LlmProvider.fromId(entry.safeProvider)
    val accentColor = providerColor(entry.safeProvider)

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (!entry.enabled)
                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
            else MaterialTheme.colorScheme.surface
        ),
        border = if (isActive && entry.enabled)
            CardDefaults.outlinedCardBorder().copy(
                brush = androidx.compose.ui.graphics.SolidColor(Color(0xFF4CAF50)),
                width = 1.5.dp)
        else null,
        elevation = CardDefaults.cardElevation(if (entry.enabled) 2.dp else 0.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            // ── Header row ────────────────────────────────────────────────────
            Row(verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)) {

                // Priority badge
                Box(modifier = Modifier.size(28.dp).clip(CircleShape)
                    .background(if (entry.enabled) accentColor else Color.Gray),
                    contentAlignment = Alignment.Center) {
                    Text("${index + 1}", color = Color.White,
                        fontWeight = FontWeight.Bold, fontSize = 11.sp)
                }

                Text(provider.emoji, fontSize = 18.sp)

                Column(modifier = Modifier.weight(1f)) {
                    Text(entry.safeLabel, fontWeight = FontWeight.SemiBold, fontSize = 14.sp,
                        color = if (entry.enabled) MaterialTheme.colorScheme.onSurface
                        else MaterialTheme.colorScheme.onSurfaceVariant)
                    val modelSuffix = if (entry.safePreferredModel.isNotBlank())
                        " · ${entry.safePreferredModel}" else ""
                    Text(provider.displayName + modelSuffix, fontSize = 11.sp,
                        color = accentColor.copy(alpha = if (entry.enabled) 1f else 0.5f),
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                }

                // Active badge
                if (isActive && entry.enabled) {
                    Surface(shape = RoundedCornerShape(20.dp),
                        color = Color(0xFF4CAF50).copy(alpha = 0.15f)) {
                        Text("ACTIVE",
                            modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.dp),
                            fontSize = 9.sp, fontWeight = FontWeight.Bold, color = Color(0xFF4CAF50))
                    }
                }

                Switch(checked = entry.enabled, onCheckedChange = { onToggle() },
                    modifier = Modifier.height(24.dp))
            }

            // ── Masked key row ────────────────────────────────────────────────
            Spacer(Modifier.height(8.dp))
            Row(modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .padding(horizontal = 10.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Key, null, modifier = Modifier.size(13.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.width(6.dp))
                Text(maskKey(entry.safeApiKey), fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f))
                if (entry.safeBaseUrl.isNotBlank()) {
                    Text("· ${entry.safeBaseUrl.removePrefix("https://").take(24)}",
                        fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }

            // ── Action buttons row ────────────────────────────────────────────
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically) {

                // ↑ Move up
                OutlinedButton(
                    onClick = onMoveUp,
                    enabled = !isFirst,
                    modifier = Modifier.size(36.dp),
                    contentPadding = PaddingValues(0.dp),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Icon(Icons.Default.KeyboardArrowUp, "Move up", modifier = Modifier.size(18.dp))
                }

                // ↓ Move down
                OutlinedButton(
                    onClick = onMoveDown,
                    enabled = !isLast,
                    modifier = Modifier.size(36.dp),
                    contentPadding = PaddingValues(0.dp),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Icon(Icons.Default.KeyboardArrowDown, "Move down", modifier = Modifier.size(18.dp))
                }

                // ★ Set as active
                OutlinedButton(
                    onClick = onSetActive,
                    enabled = !isActive && entry.enabled,
                    modifier = Modifier.height(36.dp),
                    contentPadding = PaddingValues(horizontal = 10.dp),
                    shape = RoundedCornerShape(8.dp),
                    colors = if (!isActive) ButtonDefaults.outlinedButtonColors(
                        contentColor = Color(0xFF4CAF50))
                    else ButtonDefaults.outlinedButtonColors()
                ) {
                    Icon(if (isActive) Icons.Default.Star else Icons.Default.StarBorder,
                        null, modifier = Modifier.size(14.dp))
                    Spacer(Modifier.width(3.dp))
                    Text(if (isActive) "Active" else "Set Active", fontSize = 11.sp)
                }

                Spacer(Modifier.weight(1f))

                // Edit
                IconButton(onClick = onEdit, modifier = Modifier.size(36.dp)) {
                    Icon(Icons.Default.Edit, "Edit", modifier = Modifier.size(18.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }

                // Delete
                IconButton(onClick = onDelete, modifier = Modifier.size(36.dp)) {
                    Icon(Icons.Default.Delete, "Delete", modifier = Modifier.size(18.dp),
                        tint = MaterialTheme.colorScheme.error)
                }
            }
        }
    }
}

// ── Add/Edit dialog with live validation + Gemini model picker + cURL input ───

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun KeyEditDialog(
    existing: ApiKeyEntry?,
    onDismiss: () -> Unit,
    onSave: (ApiKeyEntry) -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var label           by remember { mutableStateOf(existing?.safeLabel    ?: "") }
    var provider        by remember { mutableStateOf(existing?.safeProvider ?: "openai") }
    var apiKey          by remember { mutableStateOf(existing?.safeApiKey   ?: "") }
    var baseUrl         by remember { mutableStateOf(existing?.safeBaseUrl  ?: "") }
    var showKey         by remember { mutableStateOf(false) }
    var provExpanded    by remember { mutableStateOf(false) }

    var validation      by remember { mutableStateOf(ValidationUi()) }

    // Model picker state — used for ALL providers after successful Test Key
    var availableModels       by remember { mutableStateOf<List<String>>(emptyList()) }
    // selectedModel: always pre-fill from existing; LaunchedEffect below resets it if provider changes
    var selectedModel         by remember { mutableStateOf(existing?.safePreferredModel ?: "") }
    var modelPickerExpanded   by remember { mutableStateOf(false) }

    // OpenRouter browse-models sheet
    var showBrowseModels         by remember { mutableStateOf(false) }
    var browseLoading            by remember { mutableStateOf(false) }
    var openRouterCatalog        by remember { mutableStateOf<List<LlmRouter.OpenRouterModel>>(emptyList()) }
    var browseSearch             by remember { mutableStateOf("") }

    // cURL mode
    var curlMode        by remember { mutableStateOf(false) }
    var curlText        by remember { mutableStateOf("") }
    var parsedCurlToken by remember { mutableStateOf("") }
    var parsedCurlBase  by remember { mutableStateOf("") }
    var parsedCurlModel by remember { mutableStateOf("") }

    val selProvider = LlmProvider.fromId(provider)

    // Reset state when provider changes so stale model IDs from another provider don't carry over
    LaunchedEffect(provider) {
        validation = ValidationUi()
        availableModels = emptyList()
        // Only keep existing model if we're editing and provider hasn't changed
        if (existing?.safeProvider != provider) {
            selectedModel = ""
        }
    }
    LaunchedEffect(apiKey, baseUrl) {
        if (apiKey.isNotBlank() || baseUrl.isNotBlank()) {
            validation = ValidationUi()
            availableModels = emptyList()
        }
    }

    /** Parse a cURL command and populate fields */
    fun parseCurl(curl: String) {
        // Extract Bearer token from -H "Authorization: Bearer ..."
        val bearerRegex = Regex("""Authorization:\s*Bearer\s+([^\s"'\\]+)""", RegexOption.IGNORE_CASE)
        parsedCurlToken = bearerRegex.find(curl)?.groupValues?.get(1) ?: ""

        // Extract URL — first URL-like string after "curl"
        val urlRegex = Regex("""https?://[^\s"'\\]+""")
        val rawUrl = urlRegex.find(curl)?.value ?: ""
        // Strip path after /v1 so we keep the base URL
        parsedCurlBase = rawUrl.replace(Regex("""/chat/completions.*"""), "")
                               .replace(Regex("""/v1/.*"""), "/v1")

        // Extract model from -d JSON
        val modelRegex = Regex(""""model"\s*:\s*"([^"]+)"""")
        parsedCurlModel = modelRegex.find(curl)?.groupValues?.get(1) ?: ""
    }

    Dialog(onDismissRequest = onDismiss) {
        Card(shape = RoundedCornerShape(20.dp), modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier
                    .padding(22.dp)
                    .heightIn(max = 700.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {

                // ── Title + mode toggle ───────────────────────────────────────
                Row(verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(if (curlMode) "🔗" else selProvider.emoji, fontSize = 26.sp)
                    Text(if (existing == null) "Add API Key" else "Edit API Key",
                        fontWeight = FontWeight.Bold, fontSize = 18.sp,
                        modifier = Modifier.weight(1f))
                    // Toggle between key mode and cURL mode
                    FilterChip(
                        selected = curlMode,
                        onClick = {
                            curlMode = !curlMode
                            validation = ValidationUi()
                        },
                        label = { Text("cURL", fontSize = 11.sp) },
                        leadingIcon = { Text("⌨️", fontSize = 12.sp) }
                    )
                }

                if (curlMode) {
                    // ── cURL MODE ────────────────────────────────────────────
                    Text("Paste your cURL command below — the key, base URL and model are extracted automatically.",
                        fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        lineHeight = 16.sp)

                    OutlinedTextField(
                        value = curlText,
                        onValueChange = {
                            curlText = it
                            parseCurl(it)
                            validation = ValidationUi()
                        },
                        label = { Text("cURL Command") },
                        placeholder = { Text("curl -X POST \"https://...\" -H \"Authorization: Bearer sk-...\" -d '{\"model\":\"...\"}'") },
                        modifier = Modifier.fillMaxWidth().heightIn(min = 120.dp),
                        shape = RoundedCornerShape(12.dp),
                        maxLines = 8
                    )

                    // Show parsed fields preview
                    if (parsedCurlToken.isNotBlank() || parsedCurlBase.isNotBlank()) {
                        Surface(shape = RoundedCornerShape(10.dp),
                            color = MaterialTheme.colorScheme.surfaceVariant) {
                            Column(modifier = Modifier.padding(10.dp),
                                verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text("Parsed from cURL:", fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                                if (parsedCurlBase.isNotBlank())
                                    Text("🌐 Base URL: $parsedCurlBase", fontSize = 11.sp,
                                        fontFamily = FontFamily.Monospace)
                                if (parsedCurlToken.isNotBlank())
                                    Text("🔑 Token: ${maskKey(parsedCurlToken)}", fontSize = 11.sp,
                                        fontFamily = FontFamily.Monospace)
                                if (parsedCurlModel.isNotBlank())
                                    Text("🤖 Model: $parsedCurlModel", fontSize = 11.sp,
                                        fontFamily = FontFamily.Monospace)
                            }
                        }
                    }

                    // Validation result
                    AnimatedVisibility(visible = validation.state != ValidationState.IDLE) {
                        ValidationCard(validation)
                    }

                    // Nickname for saving
                    OutlinedTextField(value = label, onValueChange = { label = it },
                        label = { Text("Key Nickname (optional)") },
                        placeholder = { Text("e.g. Modal GLM-5") },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp), singleLine = true)

                    // Buttons
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        OutlinedButton(
                            onClick = {
                                scope.launch {
                                    if (parsedCurlToken.isBlank()) {
                                        validation = ValidationUi(ValidationState.ERROR,
                                            "❌ No Bearer token found in cURL. Make sure it has -H \"Authorization: Bearer YOUR_KEY\"")
                                        return@launch
                                    }
                                    if (parsedCurlBase.isBlank()) {
                                        validation = ValidationUi(ValidationState.ERROR,
                                            "❌ Could not parse base URL from cURL command")
                                        return@launch
                                    }
                                    validation = ValidationUi(ValidationState.LOADING, "Testing cURL endpoint…")
                                    val router = LlmRouter.getInstance(context)
                                    val result = router.validateKeyWithCurl(
                                        parsedCurlToken, parsedCurlBase,
                                        parsedCurlModel.ifBlank { "gpt-3.5-turbo" }
                                    )
                                    validation = if (result.isValid)
                                        ValidationUi(ValidationState.SUCCESS, result.message)
                                    else
                                        ValidationUi(ValidationState.ERROR, result.message)
                                }
                            },
                            modifier = Modifier.weight(1f),
                            enabled = curlText.isNotBlank() && validation.state != ValidationState.LOADING
                        ) {
                            if (validation.state == ValidationState.LOADING)
                                CircularProgressIndicator(modifier = Modifier.size(14.dp),
                                    strokeWidth = 2.dp)
                            else Icon(Icons.Default.CheckCircle, null, modifier = Modifier.size(14.dp))
                            Spacer(Modifier.width(4.dp))
                            Text(if (validation.state == ValidationState.LOADING) "Testing…" else "Test cURL", fontSize = 12.sp)
                        }

                        Button(
                            onClick = {
                                if (parsedCurlToken.isBlank()) return@Button
                                val nick = label.ifBlank {
                                    parsedCurlModel.ifBlank { parsedCurlBase.substringAfterLast("/").take(20) }
                                }
                                val base = existing ?: ApiKeyEntry(label = "", provider = "openai", apiKey = "")
                                onSave(base.copy(
                                    label          = nick,
                                    provider       = "openai",  // OpenAI-compatible
                                    apiKey         = parsedCurlToken,
                                    baseUrl        = parsedCurlBase,
                                    preferredModel = parsedCurlModel
                                ))
                            },
                            modifier = Modifier.weight(1f),
                            enabled = parsedCurlToken.isNotBlank()
                        ) {
                            Icon(Icons.Default.Save, null, modifier = Modifier.size(14.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("Save", fontSize = 12.sp)
                        }
                    }

                } else {
                    // ── NORMAL KEY MODE ──────────────────────────────────────

                    // Nickname
                    OutlinedTextField(value = label, onValueChange = { label = it },
                        label = { Text("Key Nickname") },
                        placeholder = { Text("e.g. My Gemini key") },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp), singleLine = true)

                    // Provider dropdown
                    ExposedDropdownMenuBox(expanded = provExpanded,
                        onExpandedChange = { provExpanded = it }) {
                        OutlinedTextField(
                            value = "${selProvider.emoji} ${selProvider.displayName}",
                            onValueChange = {}, readOnly = true,
                            label = { Text("LLM Provider") },
                            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(provExpanded) },
                            modifier = Modifier.fillMaxWidth().menuAnchor(),
                            shape = RoundedCornerShape(12.dp))
                        ExposedDropdownMenu(expanded = provExpanded,
                            onDismissRequest = { provExpanded = false }) {
                            LlmProvider.entries.forEach { p ->
                                DropdownMenuItem(
                                    text = {
                                        Row(verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                            Text(p.emoji, fontSize = 16.sp)
                                            Column {
                                                Text(p.displayName, fontSize = 13.sp,
                                                    fontWeight = FontWeight.Medium)
                                                Text(p.keyHint, fontSize = 10.sp,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                                            }
                                        }
                                    },
                                    onClick = { provider = p.id; provExpanded = false }
                                )
                            }
                        }
                    }

                    // Provider hint
                    if (provider != "ollama") {
                        Surface(shape = RoundedCornerShape(8.dp),
                            color = providerColor(provider).copy(alpha = 0.1f)) {
                            Row(modifier = Modifier.padding(10.dp),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalAlignment = Alignment.Top) {
                                Icon(Icons.Default.Info, null, modifier = Modifier.size(14.dp),
                                    tint = providerColor(provider))
                                Text(selProvider.keyHint, fontSize = 11.sp,
                                    color = MaterialTheme.colorScheme.onSurface, lineHeight = 16.sp)
                            }
                        }
                    }

                    // API Key field
                    OutlinedTextField(
                        value = apiKey, onValueChange = { apiKey = it },
                        label = { Text("API Key") },
                        placeholder = { Text(selProvider.keyPlaceholder) },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp), singleLine = true,
                        visualTransformation = if (showKey) VisualTransformation.None
                                               else PasswordVisualTransformation(),
                        trailingIcon = {
                            IconButton(onClick = { showKey = !showKey }) {
                                Icon(if (showKey) Icons.Default.VisibilityOff
                                     else Icons.Default.Visibility, null)
                            }
                        })

                    // Base URL field (optional, for custom/modal providers)
                    OutlinedTextField(
                        value = baseUrl, onValueChange = { baseUrl = it },
                        label = { Text("Base URL (optional)") },
                        placeholder = { Text("https://api.us-west-2.modal.direct/v1") },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp), singleLine = true,
                        leadingIcon = { Icon(Icons.Default.Language, null, modifier = Modifier.size(18.dp)) },
                        supportingText = {
                            Text("Leave blank for default. Set for custom/proxy endpoints.",
                                fontSize = 10.sp)
                        })

                    // ── OpenRouter: Browse Models button ─────────────────────
                    AnimatedVisibility(visible = provider == "openrouter" && apiKey.isNotBlank()) {
                        Button(
                            onClick = {
                                scope.launch {
                                    showBrowseModels = true
                                    if (openRouterCatalog.isEmpty()) {
                                        browseLoading = true
                                        val router = LlmRouter.getInstance(context)
                                        openRouterCatalog = router.listOpenRouterModels(apiKey.trim())
                                        browseLoading = false
                                    }
                                }
                            },
                            modifier = Modifier.fillMaxWidth(),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = Color(0xFF7C4DFF)
                            )
                        ) {
                            Icon(Icons.Default.AutoAwesome, null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(8.dp))
                            Text(
                                if (selectedModel.isNotBlank()) "Model: $selectedModel" else "Browse 400+ Models",
                                fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis
                            )
                        }
                    }

                    // ── Universal model picker — shown for ALL providers after successful Test Key ──
                    AnimatedVisibility(visible = availableModels.isNotEmpty() && provider != "ollama" || (provider == "ollama" && availableModels.isNotEmpty())) {
                        val pickerLabel = when (provider) {
                            "gemini"    -> "Gemini Model"
                            "anthropic" -> "Claude Model"
                            "ollama"    -> "Ollama Model"
                            "openrouter"-> "OpenRouter Model"
                            else        -> "Model"
                        }
                        val accentCol = providerColor(provider)
                        ExposedDropdownMenuBox(expanded = modelPickerExpanded,
                            onExpandedChange = { modelPickerExpanded = it }) {
                            OutlinedTextField(
                                value = selectedModel.ifBlank { "Select model…" },
                                onValueChange = {}, readOnly = true,
                                label = { Text(pickerLabel) },
                                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(modelPickerExpanded) },
                                modifier = Modifier.fillMaxWidth().menuAnchor(),
                                shape = RoundedCornerShape(12.dp),
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedBorderColor = accentCol,
                                    unfocusedBorderColor = accentCol.copy(alpha = 0.5f)))
                            ExposedDropdownMenu(expanded = modelPickerExpanded,
                                onDismissRequest = { modelPickerExpanded = false }) {
                                availableModels.forEach { model ->
                                    val clean = model.substringBefore(" (")
                                    val isBest = isBestModel(clean, provider)
                                    DropdownMenuItem(
                                        text = {
                                            Row(verticalAlignment = Alignment.CenterVertically,
                                                horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                                if (isBest) {
                                                    Surface(shape = RoundedCornerShape(4.dp),
                                                        color = accentCol.copy(alpha = 0.15f)) {
                                                        Text("★ BEST",
                                                            modifier = Modifier.padding(
                                                                horizontal = 4.dp, vertical = 2.dp),
                                                            fontSize = 9.sp,
                                                            fontWeight = FontWeight.Bold,
                                                            color = accentCol)
                                                    }
                                                }
                                                Text(clean, fontSize = 12.sp,
                                                    fontFamily = FontFamily.Monospace,
                                                    fontWeight = if (clean == selectedModel) FontWeight.Bold else FontWeight.Normal)
                                            }
                                        },
                                        onClick = { selectedModel = clean; modelPickerExpanded = false }
                                    )
                                }
                            }
                        }
                    }

                    // Validation result
                    AnimatedVisibility(visible = validation.state != ValidationState.IDLE) {
                        ValidationCard(validation)
                    }

                    // Buttons row
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {

                        // TEST KEY — now passes selectedModel so it tests what user picked
                        OutlinedButton(
                            onClick = {
                                scope.launch {
                                    validation = ValidationUi(ValidationState.LOADING, "Checking key…")
                                    val router = LlmRouter.getInstance(context)
                                    val testEntry = ApiKeyEntry(
                                        label          = label.ifBlank { "test" },
                                        provider       = provider,
                                        apiKey         = if (provider == "ollama") "local" else apiKey.trim(),
                                        baseUrl        = baseUrl.trim(),
                                        preferredModel = selectedModel.ifBlank { null }
                                    )
                                    val result = router.validateKey(testEntry)
                                    when {
                                        result.isValid -> {
                                            validation = ValidationUi(ValidationState.SUCCESS, result.message)
                                            if (result.availableModels.isNotEmpty()) {
                                                availableModels = result.availableModels
                                                val currentClean = selectedModel.substringBefore(" (")
                                                val stillValid = result.availableModels
                                                    .any { it.substringBefore(" (") == currentClean }
                                                if (!stillValid) {
                                                    selectedModel = pickBestModel(result.availableModels, provider)
                                                }
                                            }
                                        }
                                        result.message.contains("429") ||
                                        result.message.lowercase().contains("quota") ||
                                        result.message.lowercase().contains("rate") ->
                                            validation = ValidationUi(
                                                ValidationState.RATE_LIMITED,
                                                "⚠️ Key valid but quota exceeded — save anyway.")
                                        else ->
                                            validation = ValidationUi(ValidationState.ERROR, result.message)
                                    }
                                }
                            },
                            modifier = Modifier.weight(1f),
                            enabled = (provider == "ollama" || apiKey.isNotBlank()) &&
                                      validation.state != ValidationState.LOADING
                        ) {
                            if (validation.state == ValidationState.LOADING) {
                                CircularProgressIndicator(modifier = Modifier.size(14.dp),
                                    strokeWidth = 2.dp, color = MaterialTheme.colorScheme.primary)
                            } else {
                                Icon(Icons.Default.CheckCircle, null, modifier = Modifier.size(14.dp))
                            }
                            Spacer(Modifier.width(4.dp))
                            Text(when (validation.state) {
                                ValidationState.LOADING -> "Checking…"
                                ValidationState.SUCCESS -> "✓ Valid"
                                else                    -> "Test Key"
                            }, fontSize = 12.sp)
                        }

                        // SAVE
                        Button(
                            onClick = {
                                if (label.isBlank()) return@Button
                                val key = if (provider == "ollama" && apiKey.isBlank()) "local" else apiKey
                                val base = existing ?: ApiKeyEntry(label = "", provider = "", apiKey = "")
                                onSave(base.copy(
                                    label          = label.trim(),
                                    provider       = provider,
                                    apiKey         = key.trim(),
                                    baseUrl        = baseUrl.trim().ifBlank { null },
                                    preferredModel = selectedModel.ifBlank { null }
                                ))
                            },
                            modifier = Modifier.weight(1f),
                            enabled = label.isNotBlank() &&
                                      (provider == "ollama" || apiKey.isNotBlank()) &&
                                      validation.state != ValidationState.LOADING
                        ) {
                            Icon(Icons.Default.Save, null, modifier = Modifier.size(14.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("Save", fontSize = 12.sp)
                        }
                    }
                }

                // Cancel
                TextButton(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) {
                    Text("Cancel")
                }
            }
        }
    }

    // ── OpenRouter Browse Models dialog ───────────────────────────────────────
    if (showBrowseModels) {
        OpenRouterBrowseDialog(
            loading      = browseLoading,
            models       = openRouterCatalog,
            search       = browseSearch,
            onSearchChange = { browseSearch = it },
            selectedId   = selectedModel,
            apiKey       = apiKey.trim(),
            onSelect     = { modelId ->
                selectedModel = modelId
                showBrowseModels = false
                browseSearch = ""
                // Auto-test the picked model
                scope.launch {
                    validation = ValidationUi(ValidationState.LOADING, "Testing $modelId…")
                    val router = LlmRouter.getInstance(context)
                    val result = router.validateKey(ApiKeyEntry(
                        label    = "test",
                        provider = "openrouter",
                        apiKey   = apiKey.trim(),
                        preferredModel = modelId
                    ))
                    validation = when {
                        result.isValid -> ValidationUi(ValidationState.SUCCESS,
                            "✅ $modelId works — ready to save!")
                        result.message.contains("429") || result.message.lowercase().contains("quota") ->
                            ValidationUi(ValidationState.RATE_LIMITED,
                                "⚠️ Rate limited — key valid, quota hit. Save anyway?")
                        else -> ValidationUi(ValidationState.ERROR, result.message)
                    }
                }
            },
            onDismiss = { showBrowseModels = false; browseSearch = "" }
        )
    }
}

// ── OpenRouter Browse Models dialog ──────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OpenRouterBrowseDialog(
    loading: Boolean,
    models: List<LlmRouter.OpenRouterModel>,
    search: String,
    onSearchChange: (String) -> Unit,
    selectedId: String,
    apiKey: String,
    onSelect: (String) -> Unit,
    onDismiss: () -> Unit
) {
    val filtered = remember(search, models) {
        if (search.isBlank()) models
        else models.filter {
            it.id.contains(search, ignoreCase = true) ||
            it.name.contains(search, ignoreCase = true)
        }
    }
    val freeModels    = filtered.filter { it.isFree }
    val paidModels    = filtered.filter { !it.isFree }

    Dialog(onDismissRequest = onDismiss) {
        Card(
            shape = RoundedCornerShape(20.dp),
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.9f)
        ) {
            Column(modifier = Modifier.fillMaxSize()) {

                // ── Header ────────────────────────────────────────────────────
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color(0xFF7C4DFF))
                        .padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Icon(Icons.Default.AutoAwesome, null, tint = Color.White,
                        modifier = Modifier.size(22.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text("OpenRouter Models", color = Color.White,
                            fontWeight = FontWeight.Bold, fontSize = 16.sp)
                        Text(
                            if (loading) "Loading catalog…"
                            else "${models.size} models · ${models.count { it.isFree }} free",
                            color = Color.White.copy(alpha = 0.8f), fontSize = 11.sp
                        )
                    }
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Default.Close, null, tint = Color.White)
                    }
                }

                // ── Search bar ────────────────────────────────────────────────
                OutlinedTextField(
                    value = search,
                    onValueChange = onSearchChange,
                    placeholder = { Text("Search models…", fontSize = 13.sp) },
                    leadingIcon = { Icon(Icons.Default.Search, null, modifier = Modifier.size(18.dp)) },
                    trailingIcon = {
                        if (search.isNotBlank())
                            IconButton(onClick = { onSearchChange("") }) {
                                Icon(Icons.Default.Clear, null, modifier = Modifier.size(16.dp))
                            }
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    shape = RoundedCornerShape(12.dp),
                    singleLine = true
                )

                // ── Body ──────────────────────────────────────────────────────
                if (loading) {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            CircularProgressIndicator(color = Color(0xFF7C4DFF))
                            Text("Fetching model catalog…", fontSize = 13.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                } else if (filtered.isEmpty()) {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text("No models match \"$search\"", fontSize = 13.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        // Free models section
                        if (freeModels.isNotEmpty()) {
                            item {
                                Text("🆓 FREE MODELS (${freeModels.size})",
                                    fontSize = 10.sp, fontWeight = FontWeight.Bold,
                                    color = Color(0xFF4CAF50),
                                    modifier = Modifier.padding(vertical = 4.dp))
                            }
                            items(freeModels) { model ->
                                OpenRouterModelCard(
                                    model = model,
                                    isSelected = model.id == selectedId,
                                    onClick = { onSelect(model.id) }
                                )
                            }
                        }
                        // Paid models section
                        if (paidModels.isNotEmpty()) {
                            item {
                                Spacer(Modifier.height(4.dp))
                                Text("💳 PAID MODELS (${paidModels.size})",
                                    fontSize = 10.sp, fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(vertical = 4.dp))
                            }
                            items(paidModels) { model ->
                                OpenRouterModelCard(
                                    model = model,
                                    isSelected = model.id == selectedId,
                                    onClick = { onSelect(model.id) }
                                )
                            }
                        }
                        item { Spacer(Modifier.height(8.dp)) }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OpenRouterModelCard(
    model: LlmRouter.OpenRouterModel,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    val accentColor = Color(0xFF7C4DFF)
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(10.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (isSelected)
                accentColor.copy(alpha = 0.12f)
            else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
        ),
        border = if (isSelected) CardDefaults.outlinedCardBorder().copy(
            brush = androidx.compose.ui.graphics.SolidColor(accentColor), width = 1.5.dp
        ) else null
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            // Selection indicator
            if (isSelected) {
                Icon(Icons.Default.CheckCircle, null,
                    tint = accentColor, modifier = Modifier.size(18.dp))
            } else {
                Spacer(Modifier.size(18.dp))
            }

            Column(modifier = Modifier.weight(1f)) {
                Text(model.name, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(model.id, fontSize = 10.sp, fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
            }

            Column(horizontalAlignment = Alignment.End) {
                // Price badge
                Surface(
                    shape = RoundedCornerShape(6.dp),
                    color = if (model.isFree) Color(0xFF4CAF50).copy(alpha = 0.15f)
                            else MaterialTheme.colorScheme.surfaceVariant
                ) {
                    Text(
                        model.priceLabel,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                        fontSize = 10.sp, fontWeight = FontWeight.Bold,
                        color = if (model.isFree) Color(0xFF4CAF50)
                                else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Spacer(Modifier.height(2.dp))
                Text(model.contextLabel, fontSize = 9.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

// ── Validation card ───────────────────────────────────────────────────────────

@Composable
fun ValidationCard(v: ValidationUi) {
    val bg   = when (v.state) {
        ValidationState.SUCCESS      -> Color(0xFF1B5E20).copy(alpha = 0.2f)
        ValidationState.RATE_LIMITED -> Color(0xFFE65100).copy(alpha = 0.2f)
        ValidationState.ERROR        -> Color(0xFFB71C1C).copy(alpha = 0.2f)
        else                         -> MaterialTheme.colorScheme.surfaceVariant
    }
    val tint = when (v.state) {
        ValidationState.SUCCESS      -> Color(0xFF4CAF50)
        ValidationState.RATE_LIMITED -> Color(0xFFFF6D00)
        ValidationState.ERROR        -> Color(0xFFEF5350)
        else                         -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    val icon = when (v.state) {
        ValidationState.SUCCESS      -> Icons.Default.CheckCircle
        ValidationState.RATE_LIMITED -> Icons.Default.Warning
        ValidationState.ERROR        -> Icons.Default.Cancel
        else                         -> Icons.Default.HourglassTop
    }
    Surface(shape = RoundedCornerShape(10.dp), color = bg) {
        Row(modifier = Modifier.padding(10.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.Top) {
            if (v.state == ValidationState.LOADING) {
                CircularProgressIndicator(modifier = Modifier.size(16.dp),
                    strokeWidth = 2.dp, color = tint)
            } else {
                Icon(icon, null, modifier = Modifier.size(16.dp), tint = tint)
            }
            Text(v.message, fontSize = 12.sp, lineHeight = 17.sp,
                color = MaterialTheme.colorScheme.onSurface)
        }
    }
}

// ── Helpers ───────────────────────────────────────────────────────────────────

fun maskKey(key: String): String {
    if (key.length <= 8) return "••••••••"
    return "${key.take(6)}${"•".repeat(minOf(key.length - 10, 20))}${key.takeLast(4)}"
}

/** Returns true if this model ID should get the ★ BEST badge for a given provider */
fun isBestModel(modelId: String, provider: String): Boolean = when (provider) {
    "gemini"     -> modelId.contains("2.5-flash")
    "anthropic"  -> modelId.contains("claude-sonnet-4") || modelId.contains("claude-3-5-sonnet")
    "openrouter" -> modelId.contains("gpt-4o") && !modelId.contains("mini")
    "ollama"     -> false // local — no universal "best"
    else         -> modelId.contains("gpt-4o") && !modelId.contains("mini")
}

/** Pick the best default model from a list for a given provider */
fun pickBestModel(models: List<String>, provider: String): String {
    val clean = { m: String -> m.substringBefore(" (") }
    return when (provider) {
        "gemini" -> models.firstOrNull { it.contains("2.5-flash") }
            ?: models.firstOrNull { it.contains("2.0-flash") }
            ?: models.firstOrNull { it.contains("1.5-flash") }
            ?: models.first()
        "anthropic" -> models.firstOrNull { it.contains("claude-sonnet-4") }
            ?: models.firstOrNull { it.contains("claude-3-5-sonnet") }
            ?: models.firstOrNull { it.contains("haiku") }
            ?: models.first()
        "openrouter" -> models.firstOrNull { it.contains("gpt-4o") && !it.contains("mini") }
            ?: models.firstOrNull { it.contains("gpt-4o-mini") }
            ?: models.first()
        else -> models.firstOrNull { it.contains("gpt-4o") && !it.contains("mini") }
            ?: models.firstOrNull { it.contains("gpt-4o") }
            ?: models.first()
    }.let { clean(it) }
}

fun providerColor(provider: String): Color = when (provider) {
    "openai"     -> Color(0xFF10A37F)
    "anthropic"  -> Color(0xFFD97757)
    "gemini"     -> Color(0xFF4285F4)
    "openrouter" -> Color(0xFF7C4DFF)
    "ollama"     -> Color(0xFF607D8B)
    else         -> Color(0xFF9E9E9E)
}

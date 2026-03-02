package ai.zeroclaw.android.ui

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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ai.zeroclaw.android.data.AppSettings
import ai.zeroclaw.android.data.LlmKeyManager
import ai.zeroclaw.android.data.LlmProvider
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    onNavigateToApiKeys: () -> Unit
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
    var autoStart by remember { mutableStateOf(true) }
    var keyCount by remember { mutableStateOf(0) }
    var activeKeyLabel by remember { mutableStateOf("") }

    LaunchedEffect(Unit) {
        settings.getAll().let { s ->
            zeroClawUrl = s.zeroClawUrl
            telegramToken = s.telegramToken
            twilioSid = s.twilioSid
            twilioToken = s.twilioToken
            twilioFrom = s.twilioFrom
            autoStart = s.autoStart
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
                                twilioToken, twilioFrom, "", "", autoStart
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
            item { SectionHeader("🧠 AI / LLM Provider") }
            item {
                ApiKeysButton(keyCount = keyCount, activeKeyLabel = activeKeyLabel,
                    onClick = onNavigateToApiKeys)
            }
            item { SectionHeader("⚙️ ZeroClaw Configuration") }
            item { SettingsTextField("ZeroClaw API URL", zeroClawUrl, false) { zeroClawUrl = it } }
            item { SectionHeader("✈️ Telegram Bot") }
            item {
                SettingsTextField("Bot Token (from @BotFather)", telegramToken, true) { telegramToken = it }
            }
            item { SectionHeader("💬 WhatsApp (Twilio)") }
            item { SettingsTextField("Twilio Account SID", twilioSid, false) { twilioSid = it } }
            item { SettingsTextField("Twilio Auth Token", twilioToken, true) { twilioToken = it } }
            item { SettingsTextField("WhatsApp From Number", twilioFrom, false) { twilioFrom = it } }
            item { SectionHeader("🔧 Behavior") }
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

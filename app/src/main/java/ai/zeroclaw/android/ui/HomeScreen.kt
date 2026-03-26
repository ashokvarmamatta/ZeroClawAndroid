package ai.zeroclaw.android.ui

import android.content.Context
import android.content.Intent
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
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ai.zeroclaw.android.data.LlmKeyManager
import ai.zeroclaw.android.data.LlmProvider
import ai.zeroclaw.android.data.OfflineModelManager
import ai.zeroclaw.android.service.ZeroClawService
import kotlinx.coroutines.delay

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    onNavigateToSettings: () -> Unit,
    onNavigateToInfo: () -> Unit,
    onNavigateToPlayground: () -> Unit = {},
    onNavigateToAgents: () -> Unit = {}
) {
    val context = LocalContext.current
    val keyManager = remember { LlmKeyManager.getInstance(context) }
    val offlineManager = remember { OfflineModelManager.getInstance(context) }

    var isServiceRunning by remember { mutableStateOf(false) }
    var tunnelUrl by remember { mutableStateOf("Not started") }
    var statusLogs by remember { mutableStateOf(listOf("ZeroClaw ready. Tap Start.")) }
    var detailLogs by remember { mutableStateOf(listOf<String>()) }
    var telegramStatus by remember { mutableStateOf(false) }
    var whatsappStatus by remember { mutableStateOf(false) }
    var discordStatus by remember { mutableStateOf(false) }
    var signalStatus by remember { mutableStateOf(false) }
    var activeKeyLabel by remember { mutableStateOf("None") }
    var keyCount by remember { mutableStateOf(0) }
    var failedKeys by remember { mutableStateOf(0) }
    var offlineModelActive by remember { mutableStateOf(false) }
    var offlineModelName by remember { mutableStateOf("") }

    LaunchedEffect(Unit) {
        while (true) {
            isServiceRunning   = ZeroClawService.isRunning
            tunnelUrl          = ZeroClawService.tunnelUrl ?: "Not connected"
            val newLogs        = ZeroClawService.recentLogs.toList()
            if (newLogs.isNotEmpty()) statusLogs = newLogs
            detailLogs = ZeroClawService.conversationLogs.toList()
            telegramStatus     = ZeroClawService.telegramConnected
            whatsappStatus     = ZeroClawService.whatsappConnected
            discordStatus      = ZeroClawService.discordConnected
            signalStatus       = ZeroClawService.signalConnected

            // Refresh key stats every poll cycle
            val allKeys = keyManager.loadKeys()
            val onlineKeys = allKeys.filter { it.enabled && it.safeProvider != "offline" }
            val offlineKey = allKeys.firstOrNull { it.enabled && it.safeProvider == "offline" }
            keyCount   = onlineKeys.size
            val active = keyManager.nextUsableKey()
            activeKeyLabel = active?.let {
                if (it.safeProvider == "offline") null else "${LlmProvider.fromId(it.safeProvider).emoji} ${it.safeLabel}"
            } ?: if (onlineKeys.isEmpty()) "No keys configured" else "All keys failed"
            failedKeys = keyManager.failedCount()

            // Offline model status
            offlineModelActive = offlineKey != null && offlineManager.isModelLoaded()
            offlineModelName = offlineManager.loadedModelName.value ?: offlineKey?.safePreferredModel ?: ""

            delay(2000)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("🦀 ", fontSize = 22.sp)
                        Text("ZeroClaw", fontWeight = FontWeight.Bold)
                    }
                },
                actions = {
                    IconButton(onClick = onNavigateToAgents) {
                        Icon(Icons.Default.SmartToy, "Agents",
                            tint = MaterialTheme.colorScheme.onPrimaryContainer)
                    }
                    IconButton(onClick = onNavigateToPlayground) {
                        Icon(Icons.Default.Science, "Tool Playground",
                            tint = MaterialTheme.colorScheme.onPrimaryContainer)
                    }
                    IconButton(onClick = onNavigateToInfo) {
                        Icon(Icons.Default.Info, "Setup Guide",
                            tint = MaterialTheme.colorScheme.onPrimaryContainer)
                    }
                    IconButton(onClick = onNavigateToSettings) {
                        Icon(Icons.Default.Settings, "Settings",
                            tint = MaterialTheme.colorScheme.onPrimaryContainer)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer
                )
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            if (!isServiceRunning && keyCount == 0) {
                item { FirstRunBanner(onNavigateToInfo) }
            }
            item { ServiceControlCard(context, isServiceRunning) }
            item {
                StatusCard(
                    isRunning      = isServiceRunning,
                    tunnelUrl      = tunnelUrl,
                    telegramOk     = telegramStatus,
                    whatsappOk     = whatsappStatus,
                    discordOk      = discordStatus,
                    signalOk       = signalStatus,
                    activeKeyLabel = activeKeyLabel,
                    keyCount       = keyCount,
                    failedKeys     = failedKeys,
                    offlineActive  = offlineModelActive,
                    offlineModel   = offlineModelName
                )
            }
            item { LogCard(statusLogs, tunnelUrl) }
            item { DetailedLogCard(detailLogs) }
        }
    }
}

@Composable
fun FirstRunBanner(onNavigateToInfo: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer
        )
    ) {
        Row(modifier = Modifier.padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Icon(Icons.Default.Lightbulb, null, tint = Color(0xFFFFB300),
                modifier = Modifier.size(28.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text("New here?", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                Text("Read the setup guide to connect Telegram, WhatsApp & more.",
                    fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    lineHeight = 17.sp)
            }
            TextButton(onClick = onNavigateToInfo) {
                Text("Guide", fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
fun ServiceControlCard(context: Context, isRunning: Boolean) {
    // Use explicit dark card so buttons are always readable regardless of theme accent color
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (isRunning) Color(0xFF1B3A2F) else Color(0xFF1E1E2E)
        )
    ) {
        Column(modifier = Modifier.padding(20.dp)) {
            Text("Agent Service", fontWeight = FontWeight.Bold, fontSize = 18.sp, color = Color.White)
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    if (isRunning) Icons.Default.CheckCircle else Icons.Default.RadioButtonUnchecked,
                    null,
                    tint = if (isRunning) Color(0xFF4CAF50) else Color(0xFFAAAAAA),
                    modifier = Modifier.size(20.dp)
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    if (isRunning) "Running — ZeroClaw is live" else "Stopped",
                    fontSize = 14.sp,
                    color = if (isRunning) Color(0xFF81C784) else Color(0xFFBBBBBB)
                )
            }
            Spacer(Modifier.height(16.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                // ▶ Start — always green with white text
                Button(
                    onClick = {
                        context.startForegroundService(
                            Intent(context, ZeroClawService::class.java).apply {
                                action = ZeroClawService.ACTION_START
                            }
                        )
                    },
                    enabled = !isRunning,
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFF2E7D32),
                        contentColor = Color.White,
                        disabledContainerColor = Color(0xFF1C2B1C),
                        disabledContentColor = Color(0xFF4A6B4A)
                    )
                ) {
                    Icon(Icons.Default.PlayArrow, null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Start", fontWeight = FontWeight.Bold)
                }
                // ■ Stop — always red with white text
                Button(
                    onClick = {
                        context.startService(
                            Intent(context, ZeroClawService::class.java).apply {
                                action = ZeroClawService.ACTION_STOP
                            }
                        )
                    },
                    enabled = isRunning,
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFFC62828),
                        contentColor = Color.White,
                        disabledContainerColor = Color(0xFF2B1C1C),
                        disabledContentColor = Color(0xFF6B4A4A)
                    )
                ) {
                    Icon(Icons.Default.Stop, null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Stop", fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

@Composable
fun StatusCard(
    isRunning: Boolean,
    tunnelUrl: String,
    telegramOk: Boolean,
    whatsappOk: Boolean,
    discordOk: Boolean,
    signalOk: Boolean,
    activeKeyLabel: String,
    keyCount: Int,
    failedKeys: Int,
    offlineActive: Boolean,
    offlineModel: String
) {
    Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp)) {
        Column(modifier = Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Connections", fontWeight = FontWeight.Bold, fontSize = 18.sp)
            StatusRow("🌐 Tunnel",
                if (tunnelUrl.startsWith("https://")) tunnelUrl else if (isRunning) "Connecting…" else "Not started",
                tunnelUrl.startsWith("https://"))
            StatusRow("✈️ Telegram",
                when {
                    telegramOk -> "Connected"
                    isRunning -> "Connecting…"
                    else -> "Not started"
                },
                telegramOk)
            StatusRow("💬 WhatsApp",
                if (whatsappOk) "Connected" else "Not configured",
                whatsappOk)
            StatusRow("🎮 Discord",
                when {
                    discordOk -> "Connected"
                    isRunning -> "Not configured"
                    else -> "Not started"
                },
                discordOk)
            StatusRow("📡 Signal",
                when {
                    signalOk -> "Connected"
                    isRunning -> "Not configured"
                    else -> "Not started"
                },
                signalOk)

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

            // ── Offline Model status ──────────────────────────────────────
            Row(modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically) {
                Text("📱 Offline Model", fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        if (offlineActive) Icons.Default.CheckCircle else Icons.Default.Cancel,
                        null,
                        tint = if (offlineActive) Color(0xFF4CAF50) else Color(0xFF9E9E9E),
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(Modifier.width(4.dp))
                    Column(horizontalAlignment = Alignment.End) {
                        Text(
                            if (offlineActive) "Active & Running"
                            else if (offlineModel.isNotBlank()) "Loaded (service off)"
                            else "Not configured",
                            fontSize = 12.sp, fontFamily = FontFamily.Monospace,
                            color = if (offlineActive) Color(0xFF4CAF50) else MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1)
                        if (offlineModel.isNotBlank()) {
                            Text(offlineModel, fontSize = 10.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1)
                        }
                    }
                }
            }

            // ── Online LLM Key status ─────────────────────────────────────
            Row(modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically) {
                Text("🧠 Online LLM", fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Column(horizontalAlignment = Alignment.End) {
                    Text(activeKeyLabel, fontSize = 12.sp, fontFamily = FontFamily.Monospace,
                        color = if (failedKeys > 0 && failedKeys >= keyCount) Color(0xFFE53935)
                                else if (keyCount > 0) MaterialTheme.colorScheme.onSurface
                                else MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1)
                    if (keyCount > 1) {
                        Text(
                            "$keyCount keys · ${keyCount - failedKeys} available",
                            fontSize = 10.sp,
                            color = if (failedKeys > 0) Color(0xFFFF6D00) else Color(0xFF4CAF50)
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun StatusRow(label: String, value: String, ok: Boolean) {
    Row(modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically) {
        Text(label, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                if (ok) Icons.Default.CheckCircle else Icons.Default.Cancel,
                null,
                tint = if (ok) Color(0xFF4CAF50) else Color(0xFFE53935),
                modifier = Modifier.size(16.dp)
            )
            Spacer(Modifier.width(4.dp))
            Text(value, fontSize = 12.sp, fontFamily = FontFamily.Monospace, maxLines = 1)
        }
    }
}

@Composable
fun LogCard(logs: List<String>, tunnelUrl: String = "Not started") {
    var expanded by remember { mutableStateOf(false) }
    var copied by remember { mutableStateOf(false) }
    var showServerAddress by remember { mutableStateOf(false) }
    val clipboardManager = LocalClipboardManager.current
    val context = LocalContext.current
    val displayed = if (expanded) logs.reversed() else logs.takeLast(20).reversed()

    // Resolve ZeroClaw service URL and local IP
    val settings = remember { ai.zeroclaw.android.data.AppSettings(context) }
    val zeroClawUrl = remember {
        try { kotlinx.coroutines.runBlocking { settings.getAll().zeroClawUrl } }
        catch (_: Exception) { "http://127.0.0.1:3000" }
    }
    val localIp = remember {
        try {
            java.net.NetworkInterface.getNetworkInterfaces()?.toList()
                ?.flatMap { it.inetAddresses.toList() }
                ?.firstOrNull { !it.isLoopbackAddress && it is java.net.Inet4Address }
                ?.hostAddress ?: "127.0.0.1"
        } catch (_: Exception) { "127.0.0.1" }
    }
    val lanUrl = remember(localIp) {
        val port = zeroClawUrl.substringAfterLast(":").takeWhile { it.isDigit() }
        "http://$localIp:${port.ifBlank { "3000" }}"
    }

    // Derive WebChat URL from local IP
    val webChatUrl = remember(localIp) { "http://$localIp:8088" }
    val isServiceLive = ZeroClawService.isRunning

    // Server address dialog
    if (showServerAddress) {
        AlertDialog(
            onDismissRequest = { showServerAddress = false },
            icon = { Icon(Icons.Default.Dns, contentDescription = null, tint = MaterialTheme.colorScheme.primary) },
            title = { Text("Live Server Address", fontWeight = FontWeight.Bold) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    if (!isServiceLive) {
                        Text("Service is not running. Start the service to generate live URLs.",
                            fontSize = 12.sp, color = Color(0xFFE53935))
                    } else {
                        Text("Connect other apps to ZeroClaw using:",
                            fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    ServerAddressRow("ZeroClaw Service", lanUrl, clipboardManager)
                    if (ZeroClawService.webChatRunning) {
                        ServerAddressRow("WebChat Server", webChatUrl, clipboardManager)
                    }
                    if (tunnelUrl.startsWith("http")) {
                        ServerAddressRow("Public URL (tunnel)", tunnelUrl, clipboardManager)
                    }
                    ServerAddressRow("Localhost", zeroClawUrl, clipboardManager)
                }
            },
            confirmButton = {
                TextButton(onClick = { showServerAddress = false }) {
                    Text("Close")
                }
            }
        )
    }

    Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp)) {
        Column(modifier = Modifier.padding(20.dp)) {
            Row(modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically) {
                Text("Live Logs", fontWeight = FontWeight.Bold, fontSize = 18.sp)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    // Show Live Server Address button
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = MaterialTheme.colorScheme.tertiaryContainer,
                        modifier = Modifier.clickable { showServerAddress = true }
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Icon(Icons.Default.Dns, null,
                                tint = MaterialTheme.colorScheme.onTertiaryContainer,
                                modifier = Modifier.size(12.dp))
                            Text(
                                "Server Address",
                                fontSize = 10.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onTertiaryContainer
                            )
                        }
                    }
                    if (logs.isNotEmpty()) {
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = if (copied) MaterialTheme.colorScheme.primaryContainer
                                    else MaterialTheme.colorScheme.secondaryContainer,
                            modifier = Modifier.clickable {
                                clipboardManager.setText(AnnotatedString(logs.joinToString("\n")))
                                copied = true
                            }
                        ) {
                            Text(
                                if (copied) "Copied!" else "Copy",
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                fontSize = 10.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = if (copied) MaterialTheme.colorScheme.onPrimaryContainer
                                        else MaterialTheme.colorScheme.onSecondaryContainer
                            )
                        }
                    }
                    if (logs.size > 20) {
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = MaterialTheme.colorScheme.secondaryContainer,
                            modifier = Modifier.clickable { expanded = !expanded }
                        ) {
                            Text(
                                if (expanded) "Show less" else "View all ${logs.size}",
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                fontSize = 10.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onSecondaryContainer
                            )
                        }
                    }
                    Icon(Icons.Default.Terminal, null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(18.dp))
                }
            }
            Spacer(Modifier.height(8.dp))
            displayed.forEach { log ->
                val isPlayground = log.contains("[Lab]")
                Text(
                    "› $log",
                    fontSize = 12.sp,
                    fontFamily = FontFamily.Monospace,
                    color = if (isPlayground) Color(0xFF64B5F6)
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 1.dp)
                )
            }
            if (!expanded && logs.size > 20) {
                Spacer(Modifier.height(6.dp))
                Text(
                    "… ${logs.size - 20} more entries — tap 'View all' to expand",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                    fontFamily = FontFamily.Monospace
                )
            }
        }
    }

    // Reset copied state after delay
    LaunchedEffect(copied) {
        if (copied) { delay(2000); copied = false }
    }
}

@Composable
fun DetailedLogCard(logs: List<String>) {
    var expanded by remember { mutableStateOf(false) }
    var copied by remember { mutableStateOf(false) }
    val clipboardManager = LocalClipboardManager.current

    Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp)) {
        Column(modifier = Modifier.padding(20.dp)) {
            // Header — tap to expand/collapse
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Detailed Operation Log", fontWeight = FontWeight.Bold, fontSize = 18.sp)
                    Text(
                        if (logs.isEmpty()) "No activity yet" else "${logs.size} entries — last conversation trace",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                if (expanded && logs.isNotEmpty()) {
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = if (copied) MaterialTheme.colorScheme.primaryContainer
                                else MaterialTheme.colorScheme.secondaryContainer,
                        modifier = Modifier.clickable {
                            clipboardManager.setText(AnnotatedString(logs.joinToString("\n")))
                            copied = true
                        }
                    ) {
                        Text(
                            if (copied) "Copied!" else "Copy",
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                            fontSize = 10.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = if (copied) MaterialTheme.colorScheme.onPrimaryContainer
                                    else MaterialTheme.colorScheme.onSecondaryContainer
                        )
                    }
                    Spacer(Modifier.width(4.dp))
                }
                IconButton(onClick = { expanded = !expanded }) {
                    Icon(
                        if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                        contentDescription = if (expanded) "Collapse" else "Expand",
                        tint = MaterialTheme.colorScheme.primary
                    )
                }
            }

            if (expanded) {
                Spacer(Modifier.height(8.dp))
                if (logs.isEmpty()) {
                    Text("Send a message to see the full operation trace here.",
                        fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else {
                    logs.forEach { entry ->
                        val isSectionHeader = entry.contains("━━━")
                        Text(
                            entry,
                            fontSize = if (isSectionHeader) 13.sp else 12.sp,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = if (isSectionHeader) FontWeight.Bold else FontWeight.Normal,
                            color = if (isSectionHeader)
                                MaterialTheme.colorScheme.primary
                            else
                                MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(vertical = 1.dp)
                        )
                    }
                }
            }
        }
    }

    // Reset copied state after delay
    LaunchedEffect(copied) {
        if (copied) { delay(2000); copied = false }
    }
}

@Composable
private fun ServerAddressRow(
    label: String,
    address: String,
    clipboardManager: androidx.compose.ui.platform.ClipboardManager
) {
    var copied by remember { mutableStateOf(false) }
    Column {
        Text(label, fontSize = 11.sp, fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                address,
                fontSize = 13.sp,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.weight(1f)
            )
            Surface(
                shape = RoundedCornerShape(6.dp),
                color = if (copied) MaterialTheme.colorScheme.primaryContainer
                        else MaterialTheme.colorScheme.secondaryContainer,
                modifier = Modifier.clickable {
                    clipboardManager.setText(AnnotatedString(address))
                    copied = true
                }
            ) {
                Text(
                    if (copied) "Copied!" else "Copy",
                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                    fontSize = 10.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = if (copied) MaterialTheme.colorScheme.onPrimaryContainer
                            else MaterialTheme.colorScheme.onSecondaryContainer
                )
            }
        }
    }
    LaunchedEffect(copied) {
        if (copied) { delay(1500); copied = false }
    }
}

package ai.zeroclaw.android.ui

import android.content.Context
import android.content.Intent
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ai.zeroclaw.android.data.LlmKeyManager
import ai.zeroclaw.android.data.LlmProvider
import ai.zeroclaw.android.service.ZeroClawService
import kotlinx.coroutines.delay

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    onNavigateToSettings: () -> Unit,
    onNavigateToInfo: () -> Unit
) {
    val context = LocalContext.current
    val keyManager = remember { LlmKeyManager.getInstance(context) }

    var isServiceRunning by remember { mutableStateOf(false) }
    var tunnelUrl by remember { mutableStateOf("Not started") }
    var statusLogs by remember { mutableStateOf(listOf("ZeroClaw ready. Tap Start.")) }
    var telegramStatus by remember { mutableStateOf(false) }
    var whatsappStatus by remember { mutableStateOf(false) }
    var activeKeyLabel by remember { mutableStateOf("None") }
    var keyCount by remember { mutableStateOf(0) }
    var failedKeys by remember { mutableStateOf(0) }

    LaunchedEffect(Unit) {
        while (true) {
            isServiceRunning   = ZeroClawService.isRunning
            tunnelUrl          = ZeroClawService.tunnelUrl ?: "Not connected"
            val newLogs        = ZeroClawService.recentLogs.toList()
            if (newLogs.isNotEmpty()) statusLogs = newLogs
            telegramStatus     = ZeroClawService.telegramConnected
            whatsappStatus     = ZeroClawService.whatsappConnected

            // Refresh key stats every poll cycle
            val keys   = keyManager.loadKeys().filter { it.enabled }
            keyCount   = keys.size
            val active = keyManager.nextUsableKey()
            activeKeyLabel = active?.let {
                "${LlmProvider.fromId(it.safeProvider).emoji} ${it.safeLabel}"
            } ?: if (keys.isEmpty()) "No keys configured" else "All keys failed"
            failedKeys = keyManager.failedCount()

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
                    tunnelUrl      = tunnelUrl,
                    telegramOk     = telegramStatus,
                    whatsappOk     = whatsappStatus,
                    activeKeyLabel = activeKeyLabel,
                    keyCount       = keyCount,
                    failedKeys     = failedKeys
                )
            }
            item { LogCard(statusLogs) }
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
    tunnelUrl: String,
    telegramOk: Boolean,
    whatsappOk: Boolean,
    activeKeyLabel: String,
    keyCount: Int,
    failedKeys: Int
) {
    Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp)) {
        Column(modifier = Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Connections", fontWeight = FontWeight.Bold, fontSize = 18.sp)
            StatusRow("🌐 Tunnel URL", tunnelUrl, tunnelUrl.startsWith("https://"))
            StatusRow("✈️ Telegram", if (telegramOk) "Connected" else "Disconnected", telegramOk)
            StatusRow("💬 WhatsApp", if (whatsappOk) "Connected" else "Not configured", whatsappOk)

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

            // LLM key status row
            Row(modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically) {
                Text("🧠 LLM Key", fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Column(horizontalAlignment = Alignment.End) {
                    Text(activeKeyLabel, fontSize = 12.sp, fontFamily = FontFamily.Monospace,
                        color = if (failedKeys > 0 && failedKeys >= keyCount) Color(0xFFE53935)
                                else MaterialTheme.colorScheme.onSurface,
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
fun LogCard(logs: List<String>) {
    Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp)) {
        Column(modifier = Modifier.padding(20.dp)) {
            Row(modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically) {
                Text("Live Logs", fontWeight = FontWeight.Bold, fontSize = 18.sp)
                Icon(Icons.Default.Terminal, null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp))
            }
            Spacer(Modifier.height(8.dp))
            logs.takeLast(20).reversed().forEach { log ->
                Text("› $log", fontSize = 11.sp, fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 1.dp))
            }
        }
    }
}

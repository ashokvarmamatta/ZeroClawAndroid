package ai.zeroclaw.android.ui

import android.graphics.Bitmap
import android.graphics.Color as AndroidColor
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ai.zeroclaw.android.data.AppSettings
import ai.zeroclaw.android.tools.QrEncoder
import ai.zeroclaw.android.whatsapp.WhatsAppNativeManager
import kotlinx.coroutines.launch

/**
 * Phase 189 — pairing screen for the bundled whatsmeow Go bridge.
 *
 * Surfaces three pieces of UI:
 *   • A live QR code (rendered via the existing [QrEncoder]) when the bridge
 *     reports `STATUS qr_ready`.
 *   • An 8-digit pair code path for users who can't scan (enter phone, get code,
 *     type it into WhatsApp → Linked Devices).
 *   • Connection status + Start/Stop control.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WhatsAppNativeScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val manager = remember { WhatsAppNativeManager.getInstance(context) }
    val settings = remember { AppSettings(context) }
    val scope = rememberCoroutineScope()
    val state by manager.state.collectAsState()

    var phoneInput by remember { mutableStateOf("") }
    var modeNative by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        modeNative = settings.getAll().whatsappMode == "native"
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("💬 WhatsApp (Native)") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Spacer(Modifier.height(4.dp))

            // ── Mode toggle ─────────────────────────────────────────
            Card(
                shape = RoundedCornerShape(14.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
                )
            ) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("WhatsApp backend", fontWeight = FontWeight.Bold)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Switch(
                            checked = modeNative,
                            onCheckedChange = { enabled ->
                                modeNative = enabled
                                scope.launch {
                                    settings.setWhatsAppMode(if (enabled) "native" else "twilio")
                                }
                            }
                        )
                        Spacer(Modifier.width(12.dp))
                        Text(
                            if (modeNative) "Native (libwhatsmeow.so) — restart service to apply"
                            else "Twilio (cloud) — toggle to switch to native"
                        )
                    }
                    if (!state.binaryAvailable) {
                        Text(
                            "⚠️ libwhatsmeow.so not bundled — see whatsmeow-bridge/BUILD.md",
                            color = MaterialTheme.colorScheme.error,
                            fontSize = 12.sp
                        )
                    }
                }
            }

            // ── Status + start/stop ────────────────────────────────
            StatusCard(state = state, onStart = { manager.start() }, onStop = { manager.stop() })

            // ── QR or pair code body ───────────────────────────────
            when (state.phase) {
                "qr_ready" -> QrPanel(payload = state.qrPayload.orEmpty())
                "pair_ready" -> PairCodePanel(code = state.pairCode.orEmpty())
                "connected" -> ConnectedPanel(jid = state.jid.orEmpty())
                "error" -> ErrorPanel(msg = state.lastError.orEmpty())
                else -> {
                    Text(
                        "Tap Start to spawn the native bridge. Scan the QR with WhatsApp → Linked Devices, or use the pair-code option below.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 13.sp
                    )
                }
            }

            // ── Pair-code path (always available pre-connection) ───
            if (state.phase != "connected") {
                Card(
                    shape = RoundedCornerShape(14.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f)
                    )
                ) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Use 8-digit pair code instead of QR", fontWeight = FontWeight.Bold)
                        Text(
                            "Enter your phone in international format (e.g. 15551234567 — no `+`).",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        OutlinedTextField(
                            value = phoneInput,
                            onValueChange = { phoneInput = it.filter(Char::isDigit) },
                            label = { Text("Phone (E.164, digits only)") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                        Button(
                            enabled = state.running && phoneInput.length >= 8,
                            onClick = { manager.requestPairCode(phoneInput) }
                        ) { Text("Request pair code") }
                    }
                }
            }

            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun StatusCard(
    state: WhatsAppNativeManager.NativeState,
    onStart: () -> Unit,
    onStop: () -> Unit
) {
    Card(
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                StatusDot(state.phase)
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(state.phase.replace('_', ' ').replaceFirstChar { it.uppercase() },
                         fontWeight = FontWeight.Bold)
                    if (state.lastEvent.isNotBlank()) {
                        Text(state.lastEvent, fontSize = 12.sp,
                             color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                if (state.running) {
                    OutlinedButton(onClick = onStop) { Text("Stop") }
                } else {
                    Button(onClick = onStart) { Text("Start") }
                }
            }
        }
    }
}

@Composable
private fun StatusDot(phase: String) {
    val color = when (phase) {
        "connected" -> Color(0xFF4CAF50)
        "qr_ready", "pair_ready" -> Color(0xFFFFB74D)
        "starting" -> Color(0xFF42A5F5)
        "error" -> MaterialTheme.colorScheme.error
        else -> MaterialTheme.colorScheme.outline
    }
    Box(
        modifier = Modifier
            .size(12.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(color)
    )
}

@Composable
private fun QrPanel(payload: String) {
    val bitmap = remember(payload) { renderQrBitmap(payload, sizePx = 720) }
    Card(
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(
            containerColor = Color.White
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text("Scan with WhatsApp",
                 color = Color(0xFF222222),
                 fontWeight = FontWeight.Bold)
            Text("Linked Devices → Link a Device → point camera at this code",
                 color = Color(0xFF666666),
                 fontSize = 12.sp)
            if (bitmap != null) {
                Image(bitmap = bitmap.asImageBitmap(), contentDescription = "WhatsApp QR")
            } else {
                Text("Failed to render QR — payload too long",
                     color = MaterialTheme.colorScheme.error)
            }
        }
    }
}

@Composable
private fun PairCodePanel(code: String) {
    Card(
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.tertiaryContainer
        )
    ) {
        Column(Modifier.padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text("Enter this code in WhatsApp", fontSize = 13.sp,
                 color = MaterialTheme.colorScheme.onTertiaryContainer)
            Spacer(Modifier.height(10.dp))
            Text(formatPairCode(code),
                 fontSize = 36.sp,
                 fontFamily = FontFamily.Monospace,
                 fontWeight = FontWeight.Bold,
                 color = MaterialTheme.colorScheme.onTertiaryContainer)
            Spacer(Modifier.height(8.dp))
            Text("WhatsApp → Linked Devices → Link with phone number",
                 fontSize = 12.sp,
                 color = MaterialTheme.colorScheme.onTertiaryContainer)
        }
    }
}

@Composable
private fun ConnectedPanel(jid: String) {
    Card(
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(
            containerColor = Color(0xFFDCF8C6)
        )
    ) {
        Column(Modifier.padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text("✅ Linked", fontWeight = FontWeight.Bold,
                 color = Color(0xFF1B5E20),
                 fontSize = 18.sp)
            if (jid.isNotBlank()) {
                Spacer(Modifier.height(6.dp))
                Text(jid, fontSize = 12.sp, color = Color(0xFF1B5E20),
                     fontFamily = FontFamily.Monospace)
            }
        }
    }
}

@Composable
private fun ErrorPanel(msg: String) {
    Card(
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer
        )
    ) {
        Column(Modifier.padding(16.dp)) {
            Text("Error", fontWeight = FontWeight.Bold,
                 color = MaterialTheme.colorScheme.onErrorContainer)
            Spacer(Modifier.height(4.dp))
            Text(msg.ifBlank { "Unknown error" },
                 color = MaterialTheme.colorScheme.onErrorContainer,
                 fontSize = 12.sp)
        }
    }
}

private fun formatPairCode(code: String): String {
    val clean = code.filter(Char::isLetterOrDigit)
    return if (clean.length == 8) "${clean.substring(0, 4)} ${clean.substring(4)}" else clean
}

/** Render the whatsmeow QR payload via the bundled [QrEncoder] (no extra dep). */
private fun renderQrBitmap(payload: String, sizePx: Int): Bitmap? {
    val matrix = QrEncoder.encode(payload) ?: return null
    val moduleCount = matrix.size
    val pixelSize = (sizePx / moduleCount).coerceAtLeast(2)
    val actual = pixelSize * moduleCount
    val bm = Bitmap.createBitmap(actual, actual, Bitmap.Config.ARGB_8888)
    for (y in 0 until moduleCount) {
        for (x in 0 until moduleCount) {
            val color = if (matrix[y][x]) AndroidColor.BLACK else AndroidColor.WHITE
            for (py in 0 until pixelSize) for (px in 0 until pixelSize) {
                bm.setPixel(x * pixelSize + px, y * pixelSize + py, color)
            }
        }
    }
    return bm
}

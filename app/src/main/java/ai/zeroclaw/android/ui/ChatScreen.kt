package ai.zeroclaw.android.ui

import android.graphics.BitmapFactory
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.foundation.*
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
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
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ai.zeroclaw.android.data.*
import ai.zeroclaw.android.service.ZeroClawService
import ai.zeroclaw.android.tools.DocReadTool
import ai.zeroclaw.android.tools.ImageAnalysisTool
import ai.zeroclaw.android.tools.PdfReadTool
import ai.zeroclaw.android.tools.ToolSystem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.*

// ─────────────────────────────────────────────────────────────────────────────
// ChatScreen — Full in-app AI chat with history, model selector, and tools
// ─────────────────────────────────────────────────────────────────────────────

data class ChatMessage(
    val id: String = UUID.randomUUID().toString(),
    val role: String, // "user" | "assistant" | "system"
    val content: String,
    val timestamp: Long = System.currentTimeMillis(),
    val imageUri: String? = null,
    val isLoading: Boolean = false,
    val isError: Boolean = false,
    // Token stats (Edge Gallery style)
    val tokenCount: Int = 0,
    val latencyMs: Long = 0,
    val tokensPerSec: Float = 0f,
    val timeToFirstTokenMs: Long = 0,
    val provider: String = ""      // "offline", "openai", "gemini", etc.
)

/** Model option shown in model selector — ties a model name to its API key */
data class ModelOption(
    val keyId: String,
    val keyLabel: String,
    val provider: String,
    val model: String
) {
    val displayName: String get() = if (model.isNotBlank()) model else "(default)"
    val subtitle: String get() {
        val providerEmoji = LlmProvider.fromId(provider).emoji
        return "$providerEmoji $keyLabel"
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    onBack: () -> Unit,
    onNavigateToAgents: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val clipboardManager = LocalClipboardManager.current
    val keyboardController = LocalSoftwareKeyboardController.current
    val listState = rememberLazyListState()

    // ── Chat state ───────────────────────────────────────────────────────────
    var messages by remember { mutableStateOf(listOf<ChatMessage>()) }
    var inputText by remember { mutableStateOf("") }
    var isGenerating by remember { mutableStateOf(false) }
    var attachedImageUri by remember { mutableStateOf<Uri?>(null) }
    var attachedFileUri by remember { mutableStateOf<Uri?>(null) }
    var attachedFileName by remember { mutableStateOf<String?>(null) }
    var showAttachMenu by remember { mutableStateOf(false) }

    // ── Session tracking ─────────────────────────────────────────────────────
    var currentSessionId by remember { mutableStateOf("chat_${System.currentTimeMillis()}") }
    val chatId = currentSessionId  // used as LlmRouter chatId

    // ── History state ────────────────────────────────────────────────────────
    var showHistory by remember { mutableStateOf(false) }
    val chatDb = remember { ChatDatabase.getInstance(context) }
    val sessions by chatDb.chatDao().getAllSessions().collectAsState(initial = emptyList())

    // ── Model selector state ─────────────────────────────────────────────────
    var showModelSelector by remember { mutableStateOf(false) }
    var selectedModel by remember { mutableStateOf<ModelOption?>(null) } // null = auto (default failover)
    var showConfigDialog by remember { mutableStateOf(false) }
    val modelOptions = remember { mutableStateOf(listOf<ModelOption>()) }

    // Load model options from all API keys
    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            val keyManager = LlmKeyManager(context)
            val keys = keyManager.loadKeys().filter { it.enabled }
            val options = mutableListOf<ModelOption>()
            for (key in keys) {
                val models = key.safeSelectedModels.ifEmpty {
                    if (key.safePreferredModel.isNotBlank()) listOf(key.safePreferredModel)
                    else listOf("") // default
                }
                for (m in models) {
                    options.add(ModelOption(key.id, key.safeLabel, key.safeProvider, m))
                }
            }
            modelOptions.value = options
        }
    }

    // ── Tools state ──────────────────────────────────────────────────────────
    var showToolsSheet by remember { mutableStateOf(false) }
    var enabledToolNames by remember { mutableStateOf(listOf<String>()) }

    LaunchedEffect(Unit) {
        val toolSystem = ToolSystem.getInstance(context)
        enabledToolNames = toolSystem.enabledTools().map { it.name }
    }

    // ── Pickers ──────────────────────────────────────────────────────────────
    val imagePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        if (uri != null) {
            attachedImageUri = uri
            attachedFileUri = null
            attachedFileName = null
        }
    }

    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        if (uri != null) {
            attachedFileUri = uri
            attachedImageUri = null
            val cursor = context.contentResolver.query(uri, null, null, null, null)
            attachedFileName = cursor?.use {
                if (it.moveToFirst()) {
                    val idx = it.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                    if (idx >= 0) it.getString(idx) else uri.lastPathSegment
                } else uri.lastPathSegment
            } ?: uri.lastPathSegment
        }
    }

    // ── Graph ingest picker ────────────────────────────────────────────────
    val graphIngestLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        if (uri != null) {
            val cursor = context.contentResolver.query(uri, null, null, null, null)
            val fName = cursor?.use {
                if (it.moveToFirst()) {
                    val idx = it.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                    if (idx >= 0) it.getString(idx) else uri.lastPathSegment
                } else uri.lastPathSegment
            } ?: uri.lastPathSegment ?: "document"
            // Add user message showing ingest action
            val userMsg = ChatMessage(role = "user", content = "\uD83D\uDCCA Ingest to Knowledge Graph: $fName")
            messages = messages + userMsg
            isGenerating = true
            scope.launch {
                val result = try {
                    val tool = ai.zeroclaw.android.tools.DocumentGraphTool(context)
                    tool.execute(mapOf("action" to "ingest", "source" to uri.toString()))
                } catch (e: Exception) {
                    ai.zeroclaw.android.tools.ToolResult(false, "", "Ingest failed: ${e.message}")
                }
                val reply = if (result.success) result.content else "Error: ${result.error}"
                val aiMsg = ChatMessage(role = "assistant", content = reply)
                messages = messages + aiMsg
                isGenerating = false
            }
        }
    }

    // Auto-scroll to bottom on new messages
    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) {
            listState.animateScrollToItem(messages.size - 1)
        }
    }

    // Service status
    val isServiceRunning = ZeroClawService.isRunning

    val bgColor = Color(0xFF0D1117)
    val surfaceColor = Color(0xFF161b22)
    val accentColor = Color(0xFF58A6FF)
    val userBubbleColor = Color(0xFF1E88E5)
    val aiBubbleColor = Color(0xFF1A1F2E)

    // ── Save current session to DB ───────────────────────────────────────────
    fun saveCurrentSession() {
        if (messages.isEmpty()) return
        val sessionMessages = messages.toList()
        val sessionId = currentSessionId
        val title = sessionMessages.firstOrNull { it.role == "user" }?.content?.take(80) ?: "New Chat"
        val model = selectedModel?.displayName

        scope.launch(Dispatchers.IO) {
            chatDb.chatDao().insertSession(
                ChatSessionEntity(
                    id = sessionId,
                    title = title,
                    createdAt = sessionMessages.first().timestamp,
                    updatedAt = sessionMessages.last().timestamp,
                    messageCount = sessionMessages.size,
                    modelUsed = model
                )
            )
            chatDb.chatDao().insertMessages(
                sessionMessages.map { msg ->
                    ChatMessageEntity(
                        id = msg.id,
                        sessionId = sessionId,
                        role = msg.role,
                        content = msg.content,
                        timestamp = msg.timestamp,
                        imageUri = msg.imageUri,
                        isError = msg.isError
                    )
                }
            )
        }
    }

    // ── Auto-save on message changes (debounced by storing after AI reply) ──
    // We save after each AI reply completes
    fun saveAfterReply() {
        if (messages.isNotEmpty()) saveCurrentSession()
    }

    Scaffold(
        containerColor = bgColor,
        topBar = {
            TopAppBar(
                title = {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Text("\uD83E\uDD80", fontSize = 22.sp)
                        Column {
                            Text("ZeroClaw Chat", fontWeight = FontWeight.Bold, fontSize = 16.sp)
                            // Show selected model or auto mode
                            Text(
                                selectedModel?.let { "${it.displayName}" }
                                    ?: if (isServiceRunning) "Auto — all tools available" else "Offline — start service first",
                                fontSize = 11.sp,
                                color = if (isServiceRunning) Color(0xFF4ADE80) else Color(0xFFEF5350),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, "Back")
                    }
                },
                actions = {
                    // Config (only for offline models)
                    if (selectedModel?.model?.let { it.endsWith(".litertlm") || it.endsWith(".bin") || it.endsWith(".task") } == true) {
                        IconButton(onClick = { showConfigDialog = true }) {
                            Icon(Icons.Default.Settings, "Config", tint = Color(0xFFFFB74D))
                        }
                    }
                    // Tools
                    IconButton(onClick = { showToolsSheet = true }) {
                        Icon(Icons.Default.Build, "Tools", tint = Color(0xFF4ADE80))
                    }
                    // History
                    IconButton(onClick = { showHistory = true }) {
                        Icon(Icons.Default.History, "History", tint = accentColor)
                    }
                    // New chat
                    IconButton(onClick = {
                        saveCurrentSession()
                        messages = emptyList()
                        val router = LlmRouter.getInstance(context)
                        router.clearHistory(chatId)
                        currentSessionId = "chat_${System.currentTimeMillis()}"
                    }) {
                        Icon(Icons.Default.Add, "New chat", tint = accentColor)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = surfaceColor,
                    titleContentColor = Color.White,
                    navigationIconContentColor = Color(0xFFC9D1D9)
                )
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            // ═══ Model selector + Tools bar ═══
            Surface(color = surfaceColor.copy(alpha = 0.7f)) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    // Model selector chip
                    AssistChip(
                        onClick = { showModelSelector = true },
                        label = {
                            Text(
                                selectedModel?.let { it.displayName.take(20) } ?: "Auto",
                                fontSize = 11.sp, maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        },
                        leadingIcon = {
                            Icon(Icons.Default.Memory, null, modifier = Modifier.size(14.dp))
                        },
                        modifier = Modifier.weight(1f, fill = false),
                        shape = RoundedCornerShape(16.dp),
                        colors = AssistChipDefaults.assistChipColors(
                            containerColor = Color.White.copy(alpha = 0.06f),
                            labelColor = Color.White.copy(alpha = 0.8f),
                            leadingIconContentColor = accentColor
                        ),
                        border = BorderStroke(1.dp, accentColor.copy(alpha = 0.2f))
                    )

                    // Tools chip
                    AssistChip(
                        onClick = { showToolsSheet = true },
                        label = {
                            Text(
                                "Tools (${enabledToolNames.size})",
                                fontSize = 11.sp
                            )
                        },
                        leadingIcon = {
                            Icon(Icons.Default.Build, null, modifier = Modifier.size(14.dp))
                        },
                        shape = RoundedCornerShape(16.dp),
                        colors = AssistChipDefaults.assistChipColors(
                            containerColor = Color.White.copy(alpha = 0.06f),
                            labelColor = Color.White.copy(alpha = 0.8f),
                            leadingIconContentColor = Color(0xFF4ADE80)
                        ),
                        border = BorderStroke(1.dp, Color(0xFF4ADE80).copy(alpha = 0.2f))
                    )
                }
            }

            // ═══ Messages list ═══
            LazyColumn(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                state = listState,
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // Welcome message when empty
                if (messages.isEmpty()) {
                    item {
                        WelcomeCard(accentColor, onToolsClick = { showToolsSheet = true })
                    }
                }

                items(messages, key = { it.id }) { msg ->
                    ChatBubble(
                        msg = msg,
                        userBubbleColor = userBubbleColor,
                        aiBubbleColor = aiBubbleColor,
                        accentColor = accentColor,
                        onCopy = {
                            clipboardManager.setText(AnnotatedString(msg.content))
                        },
                        onRetry = if (msg.isError) {
                            {
                                val lastUserMsg = messages.lastOrNull { it.role == "user" }
                                if (lastUserMsg != null) {
                                    messages = messages.filter { it.id != msg.id }
                                    scope.launch {
                                        sendMessage(
                                            context, chatId, lastUserMsg.content,
                                            null, null, null,
                                            messages, isGenerating,
                                            selectedModel,
                                            onMessagesChange = { messages = it },
                                            onGeneratingChange = { isGenerating = it },
                                            onAfterReply = { saveAfterReply() }
                                        )
                                    }
                                }
                            }
                        } else null
                    )
                }

                // Typing indicator
                if (isGenerating) {
                    item {
                        TypingIndicator(aiBubbleColor)
                    }
                }
            }

            // ═══ Attachment preview ═══
            if (attachedImageUri != null || attachedFileUri != null) {
                Surface(
                    color = surfaceColor,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        if (attachedImageUri != null) {
                            Icon(Icons.Default.Image, null, tint = Color(0xFF4ADE80),
                                modifier = Modifier.size(20.dp))
                            Text("Image attached", fontSize = 12.sp, color = Color(0xFF4ADE80),
                                modifier = Modifier.weight(1f))
                        } else {
                            val lName = attachedFileName?.lowercase() ?: ""
                            val isPdf = lName.endsWith(".pdf")
                            val isDoc = lName.endsWith(".docx") || lName.endsWith(".doc")
                            val isXls = lName.endsWith(".xlsx") || lName.endsWith(".xls")
                            val toolHint = when {
                                isPdf -> "Will use PDF Reader tool"
                                isDoc -> "Will use Document Reader tool"
                                isXls -> "Will use Spreadsheet Reader tool"
                                else -> null
                            }
                            val fileColor = when {
                                isPdf -> Color(0xFFFF7043)
                                isDoc -> Color(0xFF42A5F5)
                                isXls -> Color(0xFF66BB6A)
                                else -> accentColor
                            }
                            Icon(
                                when {
                                    isPdf -> Icons.Default.PictureAsPdf
                                    isDoc || isXls -> Icons.Default.Description
                                    else -> Icons.Default.AttachFile
                                },
                                null,
                                tint = fileColor,
                                modifier = Modifier.size(20.dp)
                            )
                            Column(modifier = Modifier.weight(1f)) {
                                Text(attachedFileName ?: "File attached", fontSize = 12.sp,
                                    color = accentColor, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                if (toolHint != null) {
                                    Text(toolHint, fontSize = 10.sp,
                                        color = fileColor.copy(alpha = 0.7f))
                                }
                            }
                        }
                        IconButton(
                            onClick = {
                                attachedImageUri = null
                                attachedFileUri = null
                                attachedFileName = null
                            },
                            modifier = Modifier.size(24.dp)
                        ) {
                            Icon(Icons.Default.Close, "Remove", tint = Color(0xFFEF5350),
                                modifier = Modifier.size(16.dp))
                        }
                    }
                }
            }

            // ═══ Input bar ═══
            Surface(
                color = surfaceColor,
                shadowElevation = 8.dp
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp, vertical = 8.dp)
                        .imePadding(),
                    verticalAlignment = Alignment.Bottom,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    // Attach button
                    Box {
                        IconButton(
                            onClick = { showAttachMenu = !showAttachMenu },
                            modifier = Modifier.size(40.dp)
                        ) {
                            Icon(Icons.Default.Add, "Attach",
                                tint = if (showAttachMenu) accentColor else Color(0xFF8B949E),
                                modifier = Modifier.size(24.dp))
                        }

                        DropdownMenu(
                            expanded = showAttachMenu,
                            onDismissRequest = { showAttachMenu = false }
                        ) {
                            DropdownMenuItem(
                                text = { Text("\uD83D\uDCF7 Image") },
                                onClick = {
                                    showAttachMenu = false
                                    imagePickerLauncher.launch("image/*")
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("\uD83D\uDCC4 File") },
                                onClick = {
                                    showAttachMenu = false
                                    filePickerLauncher.launch("*/*")
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("\uD83D\uDCC3 PDF") },
                                onClick = {
                                    showAttachMenu = false
                                    filePickerLauncher.launch("application/pdf")
                                }
                            )
                            androidx.compose.material3.HorizontalDivider()
                            DropdownMenuItem(
                                text = { Text("\uD83D\uDCCA Ingest to Graph") },
                                onClick = {
                                    showAttachMenu = false
                                    graphIngestLauncher.launch("*/*")
                                }
                            )
                        }
                    }

                    // Text input
                    OutlinedTextField(
                        value = inputText,
                        onValueChange = { inputText = it },
                        placeholder = {
                            Text("Message ZeroClaw...", fontSize = 14.sp,
                                color = Color.White.copy(alpha = 0.3f))
                        },
                        modifier = Modifier
                            .weight(1f)
                            .heightIn(min = 44.dp, max = 160.dp),
                        shape = RoundedCornerShape(22.dp),
                        maxLines = 6,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = accentColor.copy(alpha = 0.5f),
                            unfocusedBorderColor = Color.White.copy(alpha = 0.1f),
                            focusedTextColor = Color.White,
                            unfocusedTextColor = Color.White,
                            cursorColor = accentColor,
                            focusedContainerColor = Color.White.copy(alpha = 0.04f),
                            unfocusedContainerColor = Color.White.copy(alpha = 0.02f)
                        )
                    )

                    // Send button
                    val canSend = inputText.isNotBlank() || attachedImageUri != null || attachedFileUri != null
                    IconButton(
                        onClick = {
                            if (!canSend || isGenerating) return@IconButton
                            val text = inputText.trim()
                            val imgUri = attachedImageUri
                            val fUri = attachedFileUri
                            val fName = attachedFileName
                            inputText = ""
                            attachedImageUri = null
                            attachedFileUri = null
                            attachedFileName = null
                            keyboardController?.hide()

                            scope.launch {
                                sendMessage(
                                    context, chatId, text,
                                    imgUri, fUri, fName,
                                    messages, isGenerating,
                                    selectedModel,
                                    onMessagesChange = { messages = it },
                                    onGeneratingChange = { isGenerating = it },
                                    onAfterReply = { saveAfterReply() }
                                )
                            }
                        },
                        enabled = canSend && !isGenerating,
                        modifier = Modifier
                            .size(44.dp)
                            .clip(CircleShape)
                            .background(
                                if (canSend && !isGenerating) accentColor
                                else Color.White.copy(alpha = 0.1f)
                            )
                    ) {
                        if (isGenerating) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(20.dp),
                                color = Color.White,
                                strokeWidth = 2.dp
                            )
                        } else {
                            Icon(Icons.Default.Send, "Send",
                                tint = if (canSend) Color.White else Color(0xFF8B949E),
                                modifier = Modifier.size(20.dp))
                        }
                    }
                }
            }
        }
    }

    // ═══ Model Configuration Dialog ═══
    if (showConfigDialog) {
        val manager = remember { ai.zeroclaw.android.data.OfflineModelManager.getInstance(context) }
        val catalogModel = selectedModel?.model?.let { m ->
            ai.zeroclaw.android.data.ModelCatalog.models.firstOrNull { it.fileName == m || m.contains(it.id) }
        }
        val supportsGpu = catalogModel?.supportsGpu ?: false
        val supportsThinking = catalogModel?.supportsThinking ?: false

        var cfgMaxTokens by remember { mutableStateOf(manager.getMaxTokens().toFloat()) }
        var cfgTopK by remember { mutableStateOf(manager.topK.toFloat()) }
        var cfgTopP by remember { mutableStateOf(manager.topP.toFloat()) }
        var cfgTemperature by remember { mutableStateOf(manager.temperature.toFloat()) }
        var cfgThinking by remember { mutableStateOf(manager.enableThinking) }

        AlertDialog(
            onDismissRequest = { showConfigDialog = false },
            containerColor = Color(0xFF161b22),
            title = { Text("Configurations", fontWeight = FontWeight.Bold, color = Color.White) },
            text = {
                Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                    Text("Max tokens", fontWeight = FontWeight.SemiBold, color = Color(0xFFC9D1D9), fontSize = 14.sp)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("${cfgMaxTokens.toInt()}", fontSize = 12.sp, color = Color(0xFF8B949E), modifier = Modifier.width(40.dp))
                        Slider(value = cfgMaxTokens, onValueChange = { cfgMaxTokens = it }, valueRange = 256f..8192f, steps = 15, modifier = Modifier.weight(1f))
                        Text("${cfgMaxTokens.toInt()}", fontSize = 12.sp, color = Color(0xFF8B949E), modifier = Modifier.width(40.dp))
                    }
                    Spacer(modifier = Modifier.height(12.dp))

                    Text("TopK", fontWeight = FontWeight.SemiBold, color = Color(0xFFC9D1D9), fontSize = 14.sp)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("${cfgTopK.toInt()}", fontSize = 12.sp, color = Color(0xFF8B949E), modifier = Modifier.width(40.dp))
                        Slider(value = cfgTopK, onValueChange = { cfgTopK = it }, valueRange = 1f..128f, steps = 126, modifier = Modifier.weight(1f))
                        Text("${cfgTopK.toInt()}", fontSize = 12.sp, color = Color(0xFF8B949E), modifier = Modifier.width(40.dp))
                    }
                    Spacer(modifier = Modifier.height(12.dp))

                    Text("TopP", fontWeight = FontWeight.SemiBold, color = Color(0xFFC9D1D9), fontSize = 14.sp)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("${"%.2f".format(cfgTopP)}", fontSize = 12.sp, color = Color(0xFF8B949E), modifier = Modifier.width(40.dp))
                        Slider(value = cfgTopP, onValueChange = { cfgTopP = it }, valueRange = 0f..1f, modifier = Modifier.weight(1f))
                        Text("${"%.2f".format(cfgTopP)}", fontSize = 12.sp, color = Color(0xFF8B949E), modifier = Modifier.width(40.dp))
                    }
                    Spacer(modifier = Modifier.height(12.dp))

                    Text("Temperature", fontWeight = FontWeight.SemiBold, color = Color(0xFFC9D1D9), fontSize = 14.sp)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("${"%.2f".format(cfgTemperature)}", fontSize = 12.sp, color = Color(0xFF8B949E), modifier = Modifier.width(40.dp))
                        Slider(value = cfgTemperature, onValueChange = { cfgTemperature = it }, valueRange = 0f..2f, modifier = Modifier.weight(1f))
                        Text("${"%.2f".format(cfgTemperature)}", fontSize = 12.sp, color = Color(0xFF8B949E), modifier = Modifier.width(40.dp))
                    }
                    Spacer(modifier = Modifier.height(16.dp))

                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Enable thinking", fontWeight = FontWeight.SemiBold, color = Color(0xFFC9D1D9), fontSize = 14.sp, modifier = Modifier.weight(1f))
                        Switch(checked = cfgThinking, onCheckedChange = { if (supportsThinking) cfgThinking = it }, enabled = supportsThinking)
                    }
                    if (!supportsThinking) {
                        Text("Not available for this model", fontSize = 11.sp, color = Color(0xFF8B949E))
                    }
                }
            },
            dismissButton = { TextButton(onClick = { showConfigDialog = false }) { Text("Cancel", color = Color(0xFF8B949E)) } },
            confirmButton = {
                TextButton(onClick = {
                    manager.topK = cfgTopK.toInt()
                    manager.topP = cfgTopP.toDouble()
                    manager.temperature = cfgTemperature.toDouble()
                    manager.enableThinking = cfgThinking
                    // Reload engine with new config
                    scope.launch(Dispatchers.IO) {
                        val path = manager.getLoadedModelPath()
                        if (path != null) {
                            manager.destroyEngine()
                            manager.loadModel(path, maxTokens = cfgMaxTokens.toInt())
                        }
                    }
                    showConfigDialog = false
                }) { Text("OK", color = Color(0xFF58A6FF)) }
            }
        )
    }

    // ═══ History Bottom Sheet ═══
    if (showHistory) {
        ModalBottomSheet(
            onDismissRequest = { showHistory = false },
            containerColor = Color(0xFF161b22),
            dragHandle = null
        ) {
            Column(modifier = Modifier.fillMaxWidth()) {
                // Header
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Chat History", fontWeight = FontWeight.Bold, fontSize = 18.sp,
                        color = Color.White, modifier = Modifier.weight(1f))
                    if (sessions.isNotEmpty()) {
                        TextButton(onClick = {
                            scope.launch(Dispatchers.IO) {
                                chatDb.chatDao().deleteAllSessions()
                            }
                        }) {
                            Text("Clear All", color = Color(0xFFEF5350), fontSize = 12.sp)
                        }
                    }
                    IconButton(onClick = { showHistory = false }) {
                        Icon(Icons.Default.Close, "Close", tint = Color(0xFF8B949E))
                    }
                }

                HorizontalDivider(color = Color.White.copy(alpha = 0.1f))

                if (sessions.isEmpty()) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(48.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("\uD83D\uDCAC", fontSize = 36.sp)
                            Spacer(Modifier.height(8.dp))
                            Text("No chat history yet", color = Color.White.copy(alpha = 0.5f),
                                fontSize = 14.sp)
                        }
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier.heightIn(max = 500.dp),
                        contentPadding = PaddingValues(8.dp)
                    ) {
                        items(sessions, key = { it.id }) { session ->
                            HistoryItem(
                                session = session,
                                isActive = session.id == currentSessionId,
                                onClick = {
                                    // Load this session
                                    scope.launch {
                                        val msgs = withContext(Dispatchers.IO) {
                                            chatDb.chatDao().getMessages(session.id)
                                        }
                                        messages = msgs.map { entity ->
                                            ChatMessage(
                                                id = entity.id,
                                                role = entity.role,
                                                content = entity.content,
                                                timestamp = entity.timestamp,
                                                imageUri = entity.imageUri,
                                                isError = entity.isError
                                            )
                                        }
                                        currentSessionId = session.id
                                        // Restore LlmRouter history for this session
                                        val router = LlmRouter.getInstance(context)
                                        router.clearHistory(session.id)
                                        msgs.forEach { entity ->
                                            router.addToHistory(session.id, entity.role, entity.content)
                                        }
                                        showHistory = false
                                    }
                                },
                                onDelete = {
                                    scope.launch(Dispatchers.IO) {
                                        chatDb.chatDao().deleteSession(session.id)
                                    }
                                }
                            )
                        }
                    }
                }

                Spacer(Modifier.height(16.dp))
            }
        }
    }

    // ═══ Model Selector Bottom Sheet ═══
    if (showModelSelector) {
        ModalBottomSheet(
            onDismissRequest = { showModelSelector = false },
            containerColor = Color(0xFF161b22),
            dragHandle = null
        ) {
            Column(modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Select Model", fontWeight = FontWeight.Bold, fontSize = 18.sp,
                        color = Color.White, modifier = Modifier.weight(1f))
                    IconButton(onClick = { showModelSelector = false }) {
                        Icon(Icons.Default.Close, "Close", tint = Color(0xFF8B949E))
                    }
                }

                HorizontalDivider(color = Color.White.copy(alpha = 0.1f))

                // Auto option
                Surface(
                    onClick = {
                        selectedModel = null
                        showModelSelector = false
                    },
                    color = if (selectedModel == null) accentColor.copy(alpha = 0.12f) else Color.Transparent,
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp, vertical = 2.dp)
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Icon(Icons.Default.AutoAwesome, null,
                            tint = if (selectedModel == null) accentColor else Color(0xFF8B949E),
                            modifier = Modifier.size(20.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Auto (Failover)", fontWeight = FontWeight.Medium,
                                fontSize = 14.sp, color = Color.White)
                            Text("Uses best available key with automatic failover",
                                fontSize = 11.sp, color = Color.White.copy(alpha = 0.5f))
                        }
                        if (selectedModel == null) {
                            Icon(Icons.Default.CheckCircle, null, tint = accentColor,
                                modifier = Modifier.size(18.dp))
                        }
                    }
                }

                Spacer(Modifier.height(4.dp))

                // Model list
                LazyColumn(
                    modifier = Modifier.heightIn(max = 400.dp),
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
                ) {
                    items(modelOptions.value) { option ->
                        val isSelected = selectedModel?.keyId == option.keyId && selectedModel?.model == option.model
                        Surface(
                            onClick = {
                                selectedModel = option
                                showModelSelector = false
                            },
                            color = if (isSelected) accentColor.copy(alpha = 0.12f) else Color.Transparent,
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 2.dp)
                        ) {
                            Row(
                                modifier = Modifier.padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                Text(LlmProvider.fromId(option.provider).emoji, fontSize = 16.sp)
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(option.displayName, fontWeight = FontWeight.Medium,
                                        fontSize = 13.sp, color = Color.White,
                                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Text(option.keyLabel, fontSize = 11.sp,
                                        color = Color.White.copy(alpha = 0.5f),
                                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                                }
                                if (isSelected) {
                                    Icon(Icons.Default.CheckCircle, null, tint = accentColor,
                                        modifier = Modifier.size(18.dp))
                                }
                            }
                        }
                    }
                }

                if (modelOptions.value.isEmpty()) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(32.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text("No API keys configured", color = Color.White.copy(alpha = 0.5f),
                            fontSize = 14.sp)
                    }
                }

                Spacer(Modifier.height(16.dp))
            }
        }
    }

    // ═══ Tools Bottom Sheet — ALL tools with toggles ═══
    if (showToolsSheet) {
        ModalBottomSheet(
            onDismissRequest = { showToolsSheet = false },
            containerColor = Color(0xFF161b22),
            dragHandle = null
        ) {
            val toolSystem = remember { ToolSystem.getInstance(context) }
            var toolStates by remember { mutableStateOf<Map<String, Boolean>>(emptyMap()) }

            LaunchedEffect(Unit) {
                val states = mutableMapOf<String, Boolean>()
                for (tool in toolSystem.allTools()) {
                    states[tool.name] = toolSystem.isEnabled(tool.name)
                }
                toolStates = states
            }

            val enabledCount = toolStates.count { it.value }
            val totalCount = toolStates.size

            // Group tools by category
            val toolMetaMap = mapOf(
                "web_search" to ("\uD83D\uDD0D" to "Core AI"), "web_fetch" to ("\uD83C\uDF10" to "Core AI"),
                "memory" to ("\uD83E\uDDE0" to "Core AI"), "pdf_read" to ("\uD83D\uDCC4" to "Core AI"),
                "summarize" to ("\uD83D\uDCDD" to "Core AI"), "translate" to ("\uD83C\uDF0D" to "Core AI"),
                "calculator" to ("\uD83D\uDD22" to "Core AI"), "qr_code" to ("\uD83D\uDCF7" to "Core AI"),
                "clipboard" to ("\uD83D\uDCCB" to "Core AI"), "status" to ("\u2139\uFE0F" to "Core AI"),
                "image_analysis" to ("\uD83D\uDDBC\uFE0F" to "Core AI"),
                "calendar" to ("\uD83D\uDCC5" to "Device"), "contacts" to ("\uD83D\uDC65" to "Device"),
                "location" to ("\uD83D\uDCCD" to "Device"), "text_to_speech" to ("\uD83D\uDD0A" to "Device"),
                "speech_to_text" to ("\uD83C\uDFA4" to "Device"), "file_manager" to ("\uD83D\uDCC1" to "Device"),
                "bookmark" to ("\uD83D\uDD16" to "Device"), "webview" to ("\uD83D\uDDA5\uFE0F" to "Device"),
                "cron" to ("\u23F0" to "Device"), "media_pipeline" to ("\uD83C\uDFAC" to "Device"),
                "weather" to ("\uD83C\uDF24\uFE0F" to "External"), "github" to ("\uD83D\uDC31" to "External"),
                "rss_feed" to ("\uD83D\uDCE1" to "External"), "image_gen" to ("\uD83C\uDFA8" to "External"),
                "spotify" to ("\uD83C\uDFB5" to "External"), "smart_home" to ("\uD83C\uDFE0" to "External"),
                "brave_search" to ("\uD83E\uDD81" to "External"), "notion" to ("\uD83D\uDCDD" to "External"),
                "email" to ("\uD83D\uDCE7" to "External"),
                "composio" to ("\uD83D\uDD17" to "Advanced"), "delegate" to ("\uD83E\uDD1D" to "Advanced"),
                "spawn" to ("\uD83D\uDE80" to "Advanced"), "message" to ("\uD83D\uDCAC" to "Advanced"),
                "pushover" to ("\uD83D\uDCEC" to "Advanced"), "a2a" to ("\uD83E\uDD16" to "Advanced"),
                "nostr" to ("\uD83D\uDD17" to "Advanced"), "mcp" to ("\u2699\uFE0F" to "Advanced"),
                "agent" to ("\uD83D\uDD77\uFE0F" to "Advanced"),
                "doc_read" to ("\uD83D\uDCC3" to "Core AI")
            )

            val categoryOrder = listOf("Core AI", "Device", "External", "Advanced")
            val categoryColors = mapOf(
                "Core AI" to Color(0xFF00BCD4), "Device" to Color(0xFF4CAF50),
                "External" to Color(0xFFFF9800), "Advanced" to Color(0xFFE91E63)
            )

            val allToolsList = toolSystem.allTools().sortedBy { tool ->
                val cat = toolMetaMap[tool.name]?.second ?: "Advanced"
                categoryOrder.indexOf(cat) * 1000 + tool.name.hashCode().and(999)
            }

            val grouped = allToolsList.groupBy { tool ->
                toolMetaMap[tool.name]?.second ?: "Advanced"
            }

            Column(modifier = Modifier.fillMaxWidth()) {
                // Header
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("AI Tools", fontWeight = FontWeight.Bold, fontSize = 18.sp,
                            color = Color.White)
                        Text(
                            "$enabledCount / $totalCount enabled",
                            fontSize = 12.sp,
                            color = if (enabledCount > 0) Color(0xFF4ADE80) else Color.White.copy(alpha = 0.4f)
                        )
                    }
                    IconButton(onClick = { showToolsSheet = false }) {
                        Icon(Icons.Default.Close, "Close", tint = Color(0xFF8B949E))
                    }
                }

                HorizontalDivider(color = Color.White.copy(alpha = 0.1f))

                Text(
                    "Toggle tools on/off. Enabled tools are available to the AI during chat.",
                    fontSize = 12.sp,
                    color = Color.White.copy(alpha = 0.4f),
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                )

                LazyColumn(
                    modifier = Modifier.heightIn(max = 450.dp),
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
                ) {
                    for (category in categoryOrder) {
                        val tools = grouped[category] ?: continue
                        val catColor = categoryColors[category] ?: accentColor

                        item(key = "header_$category") {
                            Text(
                                category,
                                fontWeight = FontWeight.Bold,
                                fontSize = 13.sp,
                                color = catColor,
                                modifier = Modifier.padding(start = 8.dp, top = 12.dp, bottom = 4.dp)
                            )
                        }

                        items(tools, key = { it.name }) { tool ->
                            val isEnabled = toolStates[tool.name] ?: false
                            val emoji = toolMetaMap[tool.name]?.first ?: "\uD83D\uDD27"

                            Surface(
                                color = if (isEnabled) catColor.copy(alpha = 0.08f) else Color.White.copy(alpha = 0.02f),
                                shape = RoundedCornerShape(8.dp),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 1.dp)
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                                ) {
                                    Text(emoji, fontSize = 16.sp)
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(tool.name, fontWeight = FontWeight.Medium,
                                            fontSize = 13.sp,
                                            color = if (isEnabled) Color.White else Color.White.copy(alpha = 0.5f))
                                        Text(tool.description.take(100), fontSize = 10.sp,
                                            color = Color.White.copy(alpha = 0.35f),
                                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    }
                                    Switch(
                                        checked = isEnabled,
                                        onCheckedChange = { newVal ->
                                            toolStates = toolStates.toMutableMap().apply {
                                                put(tool.name, newVal)
                                            }
                                            scope.launch {
                                                toolSystem.setEnabled(tool.name, newVal)
                                                // Refresh enabled count for the chip
                                                enabledToolNames = toolSystem.enabledTools().map { it.name }
                                            }
                                        },
                                        modifier = Modifier.height(24.dp),
                                        colors = SwitchDefaults.colors(
                                            checkedThumbColor = Color.White,
                                            checkedTrackColor = catColor,
                                            uncheckedThumbColor = Color(0xFF8B949E),
                                            uncheckedTrackColor = Color.White.copy(alpha = 0.1f)
                                        )
                                    )
                                }
                            }
                        }
                    }
                }

                Spacer(Modifier.height(16.dp))
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Send message logic
// ─────────────────────────────────────────────────────────────────────────────

private suspend fun sendMessage(
    context: android.content.Context,
    chatId: String,
    text: String,
    imageUri: Uri?,
    fileUri: Uri?,
    fileName: String?,
    currentMessages: List<ChatMessage>,
    currentGenerating: Boolean,
    selectedModel: ModelOption?,
    onMessagesChange: (List<ChatMessage>) -> Unit,
    onGeneratingChange: (Boolean) -> Unit,
    onAfterReply: () -> Unit = {}
) {
    if (currentGenerating) return

    // Build user message content
    val displayText = buildString {
        if (imageUri != null) append("[Image attached] ")
        if (fileUri != null) append("[File: ${fileName ?: "attachment"}] ")
        append(text)
    }.trim()

    if (displayText.isBlank()) return

    // Add user message
    val userMsg = ChatMessage(role = "user", content = displayText, imageUri = imageUri?.toString())
    var msgs = currentMessages + userMsg
    onMessagesChange(msgs)
    onGeneratingChange(true)

    try {
        val router = LlmRouter.getInstance(context)
        val startTime = System.currentTimeMillis()

        // Handle image analysis
        val reply = if (imageUri != null) {
            val prompt = text.ifBlank { "Describe this image in detail" }
            val isOfflineModel = selectedModel?.model?.let { m ->
                m.endsWith(".litertlm") || m.endsWith(".bin") || m.endsWith(".task")
            } ?: false

            if (isOfflineModel) {
                // Route to offline Gemma 4 vision (visionBackend=GPU)
                try {
                    val manager = ai.zeroclaw.android.data.OfflineModelManager.getInstance(context)
                    val inputStream = context.contentResolver.openInputStream(imageUri)
                    val bitmap = android.graphics.BitmapFactory.decodeStream(inputStream)
                    inputStream?.close()
                    if (bitmap != null) {
                        // Downscale to max 512px, encode as PNG
                        val maxDim = 512
                        val scaled = if (maxOf(bitmap.width, bitmap.height) > maxDim) {
                            val scale = maxDim.toFloat() / maxOf(bitmap.width, bitmap.height)
                            android.graphics.Bitmap.createScaledBitmap(bitmap, (bitmap.width * scale).toInt(), (bitmap.height * scale).toInt(), true)
                        } else bitmap
                        val stream = java.io.ByteArrayOutputStream()
                        scaled.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, stream)
                        val imageBytes = stream.toByteArray()
                        if (scaled !== bitmap) scaled.recycle()
                        bitmap.recycle()
                        manager.generateResponseWithImage(imageBytes, prompt)
                    } else {
                        "Failed to load image"
                    }
                } catch (e: Exception) {
                    "Offline image analysis error: ${e.message}"
                }
            } else {
                // Route to online vision API
                try {
                    val imageTool = ImageAnalysisTool(context)
                    val result = imageTool.execute(mapOf(
                        "source" to imageUri.toString(),
                        "prompt" to prompt
                    ))
                    if (result.success) result.content else "Image analysis failed: ${result.error}"
                } catch (e: Exception) {
                    "Image analysis error: ${e.message}"
                }
            }
        } else if (fileUri != null) {
            val lowerName = fileName?.lowercase() ?: ""
            val isPdf = lowerName.endsWith(".pdf")
            val isDoc = lowerName.endsWith(".docx") || lowerName.endsWith(".doc") ||
                        lowerName.endsWith(".xlsx") || lowerName.endsWith(".xls")

            if (isPdf) {
                // Use PdfReadTool for PDF files
                try {
                    val pdfTool = PdfReadTool(context)
                    val pdfResult = pdfTool.execute(mapOf("source" to fileUri.toString()))
                    if (pdfResult.success) {
                        val pdfContent = pdfResult.content.take(8000)
                        val pdfPrompt = if (text.isNotBlank()) {
                            "PDF: $fileName\n\nExtracted text:\n$pdfContent\n\nUser request: $text"
                        } else {
                            "PDF: $fileName\n\nExtracted text:\n$pdfContent\n\nSummarize and explain this PDF."
                        }
                        router.call(pdfPrompt, chatId,
                            overrideKeyId = selectedModel?.keyId,
                            overrideModel = selectedModel?.model)
                    } else {
                        "PDF extraction failed: ${pdfResult.error}"
                    }
                } catch (e: Exception) {
                    "PDF read error: ${e.message}"
                }
            } else if (isDoc) {
                // Use DocReadTool for Word/Excel files
                try {
                    val docTool = DocReadTool(context)
                    val docResult = docTool.execute(mapOf("source" to fileUri.toString()))
                    if (docResult.success) {
                        val docContent = docResult.content.take(8000)
                        val fileType = when {
                            lowerName.endsWith(".xlsx") || lowerName.endsWith(".xls") -> "Spreadsheet"
                            else -> "Document"
                        }
                        val docPrompt = if (text.isNotBlank()) {
                            "$fileType: $fileName\n\nExtracted text:\n$docContent\n\nUser request: $text"
                        } else {
                            "$fileType: $fileName\n\nExtracted text:\n$docContent\n\nSummarize and explain this $fileType."
                        }
                        router.call(docPrompt, chatId,
                            overrideKeyId = selectedModel?.keyId,
                            overrideModel = selectedModel?.model)
                    } else {
                        "Document extraction failed: ${docResult.error}"
                    }
                } catch (e: Exception) {
                    "Document read error: ${e.message}"
                }
            } else {
                // Plain text files — read directly
                val fileContent = try {
                    context.contentResolver.openInputStream(fileUri)?.bufferedReader()?.use {
                        it.readText().take(8000)
                    } ?: "Could not read file"
                } catch (e: Exception) {
                    "Error reading file: ${e.message}"
                }
                val filePrompt = if (text.isNotBlank()) {
                    "File: $fileName\n\nContent:\n$fileContent\n\nUser request: $text"
                } else {
                    "File: $fileName\n\nContent:\n$fileContent\n\nSummarize and explain this file."
                }
                router.call(filePrompt, chatId,
                    overrideKeyId = selectedModel?.keyId,
                    overrideModel = selectedModel?.model)
            }
        } else {
            router.call(text, chatId,
                overrideKeyId = selectedModel?.keyId,
                overrideModel = selectedModel?.model)
        }

        val elapsed = System.currentTimeMillis() - startTime
        // Rough token estimate: ~0.75 tokens per word (good enough for stats display)
        val estimatedTokens = reply.split("\\s+".toRegex()).size.let { (it * 0.75).toInt().coerceAtLeast(1) }
        val tps = if (elapsed > 0) (estimatedTokens * 1000f) / elapsed else 0f
        val providerLabel = selectedModel?.model?.let { m ->
            when {
                m.endsWith(".litertlm") || m.endsWith(".bin") || m.endsWith(".task") -> "offline"
                selectedModel.keyId.isBlank() -> ""
                else -> {
                    val keys = ai.zeroclaw.android.data.LlmKeyManager(context).loadKeys()
                    keys.firstOrNull { it.id == selectedModel.keyId }?.safeProvider ?: ""
                }
            }
        } ?: ""

        val aiMsg = ChatMessage(
            role = "assistant",
            content = reply,
            tokenCount = estimatedTokens,
            latencyMs = elapsed,
            tokensPerSec = tps,
            provider = providerLabel
        )
        msgs = msgs + aiMsg
        onMessagesChange(msgs)
        onAfterReply()
    } catch (e: Exception) {
        val errorMsg = ChatMessage(
            role = "assistant",
            content = "Error: ${e.message ?: "Something went wrong"}",
            isError = true
        )
        msgs = msgs + errorMsg
        onMessagesChange(msgs)
        onAfterReply()
    } finally {
        onGeneratingChange(false)
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// History item
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun HistoryItem(
    session: ChatSessionEntity,
    isActive: Boolean,
    onClick: () -> Unit,
    onDelete: () -> Unit
) {
    val dateFormat = remember { SimpleDateFormat("MMM d, HH:mm", Locale.getDefault()) }

    Surface(
        onClick = onClick,
        color = if (isActive) Color(0xFF58A6FF).copy(alpha = 0.1f) else Color.White.copy(alpha = 0.04f),
        shape = RoundedCornerShape(10.dp),
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp)
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Icon(
                if (isActive) Icons.Default.ChatBubble else Icons.Default.ChatBubbleOutline,
                null,
                tint = if (isActive) Color(0xFF58A6FF) else Color(0xFF8B949E),
                modifier = Modifier.size(20.dp)
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    session.title,
                    fontWeight = FontWeight.Medium,
                    fontSize = 13.sp,
                    color = Color.White,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        dateFormat.format(Date(session.updatedAt)),
                        fontSize = 11.sp,
                        color = Color.White.copy(alpha = 0.4f)
                    )
                    Text(
                        "${session.messageCount} msgs",
                        fontSize = 11.sp,
                        color = Color.White.copy(alpha = 0.4f)
                    )
                    if (session.modelUsed != null) {
                        Text(
                            session.modelUsed,
                            fontSize = 11.sp,
                            color = Color(0xFF58A6FF).copy(alpha = 0.6f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
            if (isActive) {
                Surface(
                    shape = RoundedCornerShape(4.dp),
                    color = Color(0xFF58A6FF).copy(alpha = 0.2f)
                ) {
                    Text("Active", fontSize = 10.sp, color = Color(0xFF58A6FF),
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp))
                }
            }
            IconButton(
                onClick = onDelete,
                modifier = Modifier.size(28.dp)
            ) {
                Icon(Icons.Default.Delete, "Delete",
                    tint = Color(0xFFEF5350).copy(alpha = 0.6f),
                    modifier = Modifier.size(16.dp))
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Chat bubble
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun ChatBubble(
    msg: ChatMessage,
    userBubbleColor: Color,
    aiBubbleColor: Color,
    accentColor: Color,
    onCopy: () -> Unit,
    onRetry: (() -> Unit)?
) {
    val isUser = msg.role == "user"
    val timeFormat = remember { SimpleDateFormat("HH:mm", Locale.getDefault()) }
    var showActions by remember { mutableStateOf(false) }
    var copied by remember { mutableStateOf(false) }

    LaunchedEffect(copied) {
        if (copied) { kotlinx.coroutines.delay(1500); copied = false }
    }

    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = if (isUser) Alignment.End else Alignment.Start
    ) {
        // Avatar + Name
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier.padding(bottom = 2.dp)
        ) {
            if (!isUser) {
                Text("\uD83E\uDD80", fontSize = 14.sp)
                Text("ZeroClaw", fontSize = 10.sp, fontWeight = FontWeight.Bold,
                    color = Color(0xFF58A6FF))
            } else {
                Text("You", fontSize = 10.sp, fontWeight = FontWeight.Bold,
                    color = Color.White.copy(alpha = 0.6f))
                Text("\uD83D\uDC64", fontSize = 14.sp)
            }
        }

        // Bubble
        Surface(
            shape = RoundedCornerShape(
                topStart = if (isUser) 18.dp else 4.dp,
                topEnd = if (isUser) 4.dp else 18.dp,
                bottomStart = 18.dp,
                bottomEnd = 18.dp
            ),
            color = when {
                msg.isError -> Color(0xFF2D1B1B)
                isUser -> userBubbleColor
                else -> aiBubbleColor
            },
            border = if (msg.isError) BorderStroke(1.dp, Color(0xFFEF5350).copy(alpha = 0.3f)) else null,
            modifier = Modifier
                .widthIn(max = 320.dp)
                .clickable { showActions = !showActions }
        ) {
            Column(modifier = Modifier.padding(12.dp)) {
                // Image indicator for user-attached images
                if (msg.imageUri != null && isUser) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        modifier = Modifier.padding(bottom = 6.dp)
                    ) {
                        Icon(Icons.Default.Image, null, tint = Color(0xFF4ADE80),
                            modifier = Modifier.size(14.dp))
                        Text("Image", fontSize = 10.sp, color = Color(0xFF4ADE80))
                    }
                }

                // Detect generated images in AI responses (QR codes, image gen, etc.)
                if (!isUser) {
                    val imagePaths = extractImagePaths(msg.content)
                    imagePaths.forEach { path ->
                        InlineImage(path)
                        Spacer(Modifier.height(8.dp))
                    }
                }

                // Message text
                Text(
                    msg.content,
                    fontSize = 14.sp,
                    color = if (msg.isError) Color(0xFFEF9A9A) else Color.White,
                    lineHeight = 20.sp
                )

                // Timestamp
                Text(
                    timeFormat.format(Date(msg.timestamp)),
                    fontSize = 9.sp,
                    color = Color.White.copy(alpha = 0.3f),
                    modifier = Modifier.padding(top = 4.dp)
                )

                // Token stats bar (Edge Gallery style) — only for AI responses
                if (!isUser && msg.tokenCount > 0) {
                    Row(
                        modifier = Modifier.padding(top = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        val statColor = Color.White.copy(alpha = 0.35f)
                        // Tokens
                        Text("${msg.tokenCount} tokens", fontSize = 8.sp, color = statColor)
                        Text("·", fontSize = 8.sp, color = statColor)
                        // Speed
                        Text("${"%.1f".format(msg.tokensPerSec)} tok/s", fontSize = 8.sp, color = statColor)
                        Text("·", fontSize = 8.sp, color = statColor)
                        // Latency
                        val latencyLabel = if (msg.latencyMs >= 1000) {
                            "${"%.1f".format(msg.latencyMs / 1000f)}s"
                        } else "${msg.latencyMs}ms"
                        Text(latencyLabel, fontSize = 8.sp, color = statColor)
                        // Provider badge
                        if (msg.provider.isNotBlank()) {
                            Text("·", fontSize = 8.sp, color = statColor)
                            val provColor = when (msg.provider) {
                                "offline" -> Color(0xFF4CAF50)
                                "gemini" -> Color(0xFF4285F4)
                                "openai" -> Color(0xFF10A37F)
                                "anthropic" -> Color(0xFFD4A574)
                                "openrouter" -> Color(0xFF9C27B0)
                                else -> statColor
                            }
                            Text(msg.provider.uppercase(), fontSize = 7.sp,
                                fontWeight = FontWeight.Bold, color = provColor)
                        }
                    }
                }
            }
        }

        // Action buttons (show on tap)
        AnimatedVisibility(visible = showActions) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                modifier = Modifier.padding(top = 4.dp)
            ) {
                // Copy
                Surface(
                    onClick = {
                        onCopy()
                        copied = true
                    },
                    shape = RoundedCornerShape(8.dp),
                    color = Color.White.copy(alpha = 0.08f)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Icon(
                            if (copied) Icons.Default.Check else Icons.Default.ContentCopy,
                            null, modifier = Modifier.size(12.dp),
                            tint = if (copied) Color(0xFF4ADE80) else accentColor
                        )
                        Text(
                            if (copied) "Copied" else "Copy",
                            fontSize = 10.sp,
                            color = if (copied) Color(0xFF4ADE80) else accentColor
                        )
                    }
                }

                // Retry (only for errors)
                if (onRetry != null) {
                    Surface(
                        onClick = onRetry,
                        shape = RoundedCornerShape(8.dp),
                        color = Color(0xFFEF5350).copy(alpha = 0.15f)
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Icon(Icons.Default.Refresh, null, modifier = Modifier.size(12.dp),
                                tint = Color(0xFFEF5350))
                            Text("Retry", fontSize = 10.sp, color = Color(0xFFEF5350))
                        }
                    }
                }
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Typing indicator
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun TypingIndicator(bgColor: Color) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Start
    ) {
        Surface(
            shape = RoundedCornerShape(18.dp),
            color = bgColor
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("\uD83E\uDD80", fontSize = 14.sp)
                Text("Thinking...", fontSize = 13.sp, color = Color.White.copy(alpha = 0.5f))
                CircularProgressIndicator(
                    modifier = Modifier.size(12.dp),
                    color = Color(0xFF58A6FF),
                    strokeWidth = 1.5.dp
                )
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Welcome card (shown when chat is empty)
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun WelcomeCard(accentColor: Color, onToolsClick: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 40.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text("\uD83E\uDD80", fontSize = 48.sp)
        Text("ZeroClaw", fontSize = 24.sp, fontWeight = FontWeight.Bold, color = Color.White)
        Text("Your AI assistant with 30+ tools",
            fontSize = 14.sp, color = Color.White.copy(alpha = 0.5f))

        Spacer(Modifier.height(8.dp))

        // Quick action chips
        Text("Try something:", fontSize = 12.sp, color = Color.White.copy(alpha = 0.4f))

        val suggestions = listOf(
            "\uD83D\uDD0D Search the web for today's news",
            "\uD83C\uDF24\uFE0F What's the weather like?",
            "\uD83C\uDF10 Summarize https://example.com",
            "\uD83E\uDDEE Calculate 15% tip on \$85",
            "\uD83D\uDDBC\uFE0F Generate an image of a sunset",
            "\uD83D\uDCC4 Attach a PDF to analyze it"
        )

        Column(
            verticalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier.padding(horizontal = 24.dp)
        ) {
            suggestions.forEach { suggestion ->
                SuggestionChip(suggestion, accentColor)
            }
        }

        Spacer(Modifier.height(16.dp))

        // Tools button
        OutlinedButton(
            onClick = onToolsClick,
            shape = RoundedCornerShape(12.dp),
            border = BorderStroke(1.dp, Color(0xFF4ADE80).copy(alpha = 0.3f))
        ) {
            Icon(Icons.Default.Build, null, tint = Color(0xFF4ADE80),
                modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(6.dp))
            Text("View Available Tools", color = Color(0xFF4ADE80), fontSize = 13.sp)
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Inline image display — renders images from file paths or URLs in chat
// ─────────────────────────────────────────────────────────────────────────────

/** Extract local image file paths from AI response text */
private fun extractImagePaths(text: String): List<String> {
    val paths = mutableListOf<String>()

    // Match "File: /path/to/image.png" pattern (QrCodeTool, etc.)
    val fileRegex = Regex("""File:\s*(/[^\s\n]+\.(?:png|jpg|jpeg|gif|webp))""", RegexOption.IGNORE_CASE)
    fileRegex.findAll(text).forEach { paths.add(it.groupValues[1]) }

    // Match standalone absolute paths to images
    val pathRegex = Regex("""(/data/[^\s\n]+\.(?:png|jpg|jpeg|gif|webp))""", RegexOption.IGNORE_CASE)
    pathRegex.findAll(text).forEach { match ->
        if (match.groupValues[1] !in paths) paths.add(match.groupValues[1])
    }

    // Match "Image URL: https://..." pattern (ImageGenTool)
    val urlRegex = Regex("""Image URL:\s*(https?://[^\s\n]+)""", RegexOption.IGNORE_CASE)
    urlRegex.findAll(text).forEach { paths.add(it.groupValues[1]) }

    return paths.distinct()
}

/** Display an image inline in chat from a local file path or URL */
@Composable
private fun InlineImage(source: String) {
    val context = LocalContext.current

    if (source.startsWith("http://") || source.startsWith("https://")) {
        // Remote URL — load async
        var bitmap by remember(source) { mutableStateOf<android.graphics.Bitmap?>(null) }
        var loading by remember(source) { mutableStateOf(true) }
        var failed by remember(source) { mutableStateOf(false) }

        LaunchedEffect(source) {
            withContext(Dispatchers.IO) {
                try {
                    val client = okhttp3.OkHttpClient.Builder()
                        .connectTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
                        .readTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
                        .build()
                    val request = okhttp3.Request.Builder().url(source)
                        .header("User-Agent", "ZeroClaw/1.0").build()
                    val response = client.newCall(request).execute()
                    val bytes = response.body?.bytes()
                    if (bytes != null) {
                        bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                    }
                } catch (_: Exception) {
                    failed = true
                }
                loading = false
            }
        }

        when {
            loading -> {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(120.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(Color.White.copy(alpha = 0.05f)),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(24.dp),
                        color = Color(0xFF58A6FF),
                        strokeWidth = 2.dp
                    )
                }
            }
            bitmap != null -> {
                Image(
                    bitmap = bitmap!!.asImageBitmap(),
                    contentDescription = "Generated image",
                    modifier = Modifier
                        .widthIn(max = 280.dp)
                        .clip(RoundedCornerShape(8.dp))
                )
            }
            failed -> {
                Text("\uD83D\uDDBC\uFE0F Image: $source",
                    fontSize = 11.sp, color = Color(0xFF58A6FF))
            }
        }
    } else {
        // Local file path
        val bitmap = remember(source) {
            try {
                val file = java.io.File(source)
                if (file.exists()) BitmapFactory.decodeFile(source) else null
            } catch (_: Exception) { null }
        }

        if (bitmap != null) {
            Image(
                bitmap = bitmap.asImageBitmap(),
                contentDescription = "Generated image",
                modifier = Modifier
                    .widthIn(max = 280.dp)
                    .clip(RoundedCornerShape(8.dp))
            )
        }
    }
}

@Composable
private fun SuggestionChip(text: String, accentColor: Color) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = accentColor.copy(alpha = 0.08f),
        border = BorderStroke(1.dp, accentColor.copy(alpha = 0.15f)),
        modifier = Modifier.fillMaxWidth()
    ) {
        Text(
            text,
            fontSize = 13.sp,
            color = Color.White.copy(alpha = 0.7f),
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)
        )
    }
}

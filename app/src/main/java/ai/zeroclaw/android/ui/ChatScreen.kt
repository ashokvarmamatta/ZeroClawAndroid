package ai.zeroclaw.android.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.foundation.*
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
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ai.zeroclaw.android.data.LlmRouter
import ai.zeroclaw.android.service.ZeroClawService
import ai.zeroclaw.android.tools.ImageAnalysisTool
import ai.zeroclaw.android.tools.ToolSystem
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*

// ─────────────────────────────────────────────────────────────────────────────
// ChatScreen — Full in-app AI chat (like ChatGPT / Gemini)
// ─────────────────────────────────────────────────────────────────────────────

data class ChatMessage(
    val id: String = UUID.randomUUID().toString(),
    val role: String, // "user" | "assistant" | "system"
    val content: String,
    val timestamp: Long = System.currentTimeMillis(),
    val imageUri: String? = null,
    val isLoading: Boolean = false,
    val isError: Boolean = false
)

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

    // Chat state
    var messages by remember { mutableStateOf(listOf<ChatMessage>()) }
    var inputText by remember { mutableStateOf("") }
    var isGenerating by remember { mutableStateOf(false) }
    var attachedImageUri by remember { mutableStateOf<Uri?>(null) }
    var attachedFileUri by remember { mutableStateOf<Uri?>(null) }
    var attachedFileName by remember { mutableStateOf<String?>(null) }
    var showAttachMenu by remember { mutableStateOf(false) }
    val chatId = remember { "local_chat_${System.currentTimeMillis()}" }

    // Image picker
    val imagePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        if (uri != null) {
            attachedImageUri = uri
            attachedFileUri = null
            attachedFileName = null
        }
    }

    // File picker
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

    Scaffold(
        containerColor = bgColor,
        topBar = {
            TopAppBar(
                title = {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Text("🦀", fontSize = 22.sp)
                        Column {
                            Text("ZeroClaw Chat", fontWeight = FontWeight.Bold, fontSize = 16.sp)
                            Text(
                                if (isServiceRunning) "Online — all tools available" else "Offline — start service first",
                                fontSize = 11.sp,
                                color = if (isServiceRunning) Color(0xFF4ADE80) else Color(0xFFEF5350)
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
                    // New chat
                    IconButton(onClick = {
                        messages = emptyList()
                        val router = LlmRouter.getInstance(context)
                        router.clearHistory(chatId)
                    }) {
                        Icon(Icons.Default.Add, "New chat", tint = accentColor)
                    }
                    // Agents shortcut
                    IconButton(onClick = onNavigateToAgents) {
                        Icon(Icons.Default.BugReport, "Agents", tint = accentColor)
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
                        WelcomeCard(accentColor, onNavigateToAgents)
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
                                // Remove error message and resend last user message
                                val lastUserMsg = messages.lastOrNull { it.role == "user" }
                                if (lastUserMsg != null) {
                                    messages = messages.filter { it.id != msg.id }
                                    // Re-trigger send
                                    scope.launch {
                                        sendMessage(
                                            context, chatId, lastUserMsg.content,
                                            null, null, null,
                                            messages, isGenerating,
                                            onMessagesChange = { messages = it },
                                            onGeneratingChange = { isGenerating = it }
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
                            Icon(Icons.Default.AttachFile, null, tint = accentColor,
                                modifier = Modifier.size(20.dp))
                            Text(attachedFileName ?: "File attached", fontSize = 12.sp,
                                color = accentColor, modifier = Modifier.weight(1f),
                                maxLines = 1, overflow = TextOverflow.Ellipsis)
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
                                text = { Text("📷 Image") },
                                onClick = {
                                    showAttachMenu = false
                                    imagePickerLauncher.launch("image/*")
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("📄 File") },
                                onClick = {
                                    showAttachMenu = false
                                    filePickerLauncher.launch("*/*")
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
                            val fileUri = attachedFileUri
                            val fileName = attachedFileName
                            inputText = ""
                            attachedImageUri = null
                            attachedFileUri = null
                            attachedFileName = null
                            keyboardController?.hide()

                            scope.launch {
                                sendMessage(
                                    context, chatId, text,
                                    imgUri, fileUri, fileName,
                                    messages, isGenerating,
                                    onMessagesChange = { messages = it },
                                    onGeneratingChange = { isGenerating = it }
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
    onMessagesChange: (List<ChatMessage>) -> Unit,
    onGeneratingChange: (Boolean) -> Unit
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

        // Handle image analysis
        val reply = if (imageUri != null) {
            val prompt = text.ifBlank { "Describe this image in detail" }
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
        } else if (fileUri != null) {
            // Read file content and include in prompt
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
            router.call(filePrompt, chatId)
        } else {
            router.call(text, chatId)
        }

        val aiMsg = ChatMessage(role = "assistant", content = reply)
        msgs = msgs + aiMsg
        onMessagesChange(msgs)
    } catch (e: Exception) {
        val errorMsg = ChatMessage(
            role = "assistant",
            content = "Error: ${e.message ?: "Something went wrong"}",
            isError = true
        )
        msgs = msgs + errorMsg
        onMessagesChange(msgs)
    } finally {
        onGeneratingChange(false)
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
                Text("🦀", fontSize = 14.sp)
                Text("ZeroClaw", fontSize = 10.sp, fontWeight = FontWeight.Bold,
                    color = Color(0xFF58A6FF))
            } else {
                Text("You", fontSize = 10.sp, fontWeight = FontWeight.Bold,
                    color = Color.White.copy(alpha = 0.6f))
                Text("👤", fontSize = 14.sp)
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
                // Image indicator
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
                Text("🦀", fontSize = 14.sp)
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
private fun WelcomeCard(accentColor: Color, onNavigateToAgents: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(vertical = 40.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text("🦀", fontSize = 48.sp)
        Text("ZeroClaw", fontSize = 24.sp, fontWeight = FontWeight.Bold, color = Color.White)
        Text("Your AI assistant with 30+ tools",
            fontSize = 14.sp, color = Color.White.copy(alpha = 0.5f))

        Spacer(Modifier.height(8.dp))

        // Quick action chips
        Text("Try something:", fontSize = 12.sp, color = Color.White.copy(alpha = 0.4f))

        val suggestions = listOf(
            "🔍 Search the web for today's news",
            "🌤️ What's the weather like?",
            "🌐 Summarize https://example.com",
            "🧮 Calculate 15% tip on $85",
            "🖼️ Generate an image of a sunset",
            "🕷️ Show my active agents"
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

        // Agents button
        OutlinedButton(
            onClick = onNavigateToAgents,
            shape = RoundedCornerShape(12.dp),
            border = BorderStroke(1.dp, accentColor.copy(alpha = 0.3f))
        ) {
            Text("🕷️ Manage Agents", color = accentColor, fontSize = 13.sp)
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

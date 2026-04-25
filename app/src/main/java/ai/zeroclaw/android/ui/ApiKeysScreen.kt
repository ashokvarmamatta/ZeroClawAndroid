package ai.zeroclaw.android.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import android.widget.Toast
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.ui.platform.LocalUriHandler
import androidx.work.WorkInfo
import androidx.work.WorkManager
import ai.zeroclaw.android.data.ApiKeyEntry
import ai.zeroclaw.android.data.CatalogModel
import ai.zeroclaw.android.data.LlmKeyManager
import ai.zeroclaw.android.data.LlmProvider
import ai.zeroclaw.android.data.LlmRouter
import ai.zeroclaw.android.data.ModelCatalog
import ai.zeroclaw.android.data.ModelDownloadProgress
import ai.zeroclaw.android.data.ModelDownloadState
import ai.zeroclaw.android.data.ModelDownloadWorker
import ai.zeroclaw.android.data.OfflineModelManager
import kotlinx.coroutines.launch

// ── Validation state ──────────────────────────────────────────────────────────

enum class ValidationState { IDLE, LOADING, SUCCESS, RATE_LIMITED, ERROR }

data class ValidationUi(
    val state: ValidationState = ValidationState.IDLE,
    val message: String = "",
    val models: List<String> = emptyList(),
    val responseText: String = ""   // AI reply from test prompt
)

data class ModelCheckState(
    val isChecking: Boolean = false,
    val totalModels: Int = 0,
    val checkedCount: Int = 0,
    val currentModel: String = "",
    val results: Map<String, String?> = emptyMap()  // modelId → null=pass, string=error
)

private const val GEMINI_DEFAULT_MODEL = "gemini-2.5-flash-preview-04-17"

// ── Main screen ───────────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ApiKeysScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val keyManager = remember { LlmKeyManager.getInstance(context) }
    val offlineManager = remember { OfflineModelManager.getInstance(context) }
    var keys by remember { mutableStateOf(keyManager.loadKeys()) }
    var showAddDialog by remember { mutableStateOf(false) }
    var editingKey by remember { mutableStateOf<ApiKeyEntry?>(null) }

    // Model checking state (per-key)
    var modelCheckStates by remember { mutableStateOf<Map<String, ModelCheckState>>(emptyMap()) }
    var expandedModelLists by remember { mutableStateOf<Set<String>>(emptySet()) }
    var retestingModel by remember { mutableStateOf("") }  // "keyId:modelId"
    val router = remember { LlmRouter.getInstance(context) }

    // Offline model state
    var offlineModelsApp by remember { mutableStateOf(offlineManager.listAppModels()) }
    var offlineLoading by remember { mutableStateOf(false) }
    var offlineImporting by remember { mutableStateOf(false) }
    var showDeleteModel by remember { mutableStateOf<OfflineModelManager.ModelFile?>(null) }
    var offlineValidation by remember { mutableStateOf(ValidationUi()) }

    // Pending picked file — shown in import dialog
    var pendingPickedUri by remember { mutableStateOf<Uri?>(null) }
    var pendingPickedName by remember { mutableStateOf("") }

    // Check which offline key exists
    val offlineKey = keys.firstOrNull { it.safeProvider == "offline" }
    val onlineKeys = keys.filter { it.safeProvider != "offline" }

    // ── SAF file picker — no storage permission needed ───────────────────
    val modelPickerLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        // Get file name from URI
        val fileName = context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            cursor.moveToFirst()
            if (nameIndex >= 0) cursor.getString(nameIndex) else null
        } ?: uri.lastPathSegment?.substringAfterLast('/') ?: "model.bin"

        // Show dialog asking whether to import or use from current location
        pendingPickedUri = uri
        pendingPickedName = fileName
    }

    fun refresh() {
        keys = keyManager.loadKeys()
        offlineModelsApp = offlineManager.listAppModels()
    }

    // ── Check All Models handler ──────────────────────────────────────────
    fun checkAllModels(entry: ApiKeyEntry) {
        val models = entry.safeAvailableModels
            .map { it.substringBefore(" (") }  // clean "model-id (Display Name)" format
            .filter { it.isNotBlank() }
            .let { if (entry.safeProvider == "openrouter") it.take(20) else it }
        if (models.isEmpty()) return

        modelCheckStates = modelCheckStates + (entry.id to ModelCheckState(
            isChecking = true, totalModels = models.size
        ))

        scope.launch {
            val results = mutableMapOf<String, String?>()
            for ((index, model) in models.withIndex()) {
                modelCheckStates = modelCheckStates + (entry.id to ModelCheckState(
                    isChecking = true,
                    totalModels = models.size,
                    checkedCount = index,
                    currentModel = model,
                    results = results.toMap()
                ))

                val result = router.testSingleModel(entry, model)
                results[model] = if (result.isSuccess) null else
                    (result.exceptionOrNull()?.message?.take(80) ?: "Unknown error")

                kotlinx.coroutines.delay(1500) // 1.5s between tests to avoid rate limits
            }

            // Save results — auto-select all passing models
            val workingModels = results.filter { it.value == null }.keys.toList()
            keyManager.updateKey(entry.copy(
                checkedModels = results,
                selectedModels = workingModels
            ))

            modelCheckStates = modelCheckStates + (entry.id to ModelCheckState(
                isChecking = false,
                totalModels = models.size,
                checkedCount = models.size,
                results = results
            ))

            // Auto-expand model list so user sees results immediately
            expandedModelLists = expandedModelLists + entry.id

            refresh()
        }
    }

    // ── Toggle model selection handler ─────────────────────────────────────
    fun toggleModel(entry: ApiKeyEntry, modelId: String) {
        val current = entry.safeSelectedModels.toMutableList()
        if (modelId in current) current.remove(modelId) else current.add(modelId)
        // Ensure model stays in checkedModels so it remains visible in the list
        val checked = entry.safeCheckedModels.toMutableMap()
        if (modelId !in checked) checked[modelId] = null // treat as OK
        keyManager.updateKey(entry.copy(selectedModels = current, checkedModels = checked))
        // If user selected a model, clear any "failed" mark so the key is usable again
        if (current.isNotEmpty()) keyManager.unmarkFailed(entry.id)
        refresh()
    }

    // ── Retest single model handler ────────────────────────────────────────
    fun retestModel(entry: ApiKeyEntry, modelId: String) {
        retestingModel = "${entry.id}:$modelId"
        scope.launch {
            val result = router.testSingleModel(entry, modelId)
            val currentChecked = entry.safeCheckedModels.toMutableMap()
            currentChecked[modelId] = if (result.isSuccess) null else
                (result.exceptionOrNull()?.message?.take(80) ?: "Unknown error")

            val currentSelected = entry.safeSelectedModels.toMutableList()
            if (result.isSuccess && modelId !in currentSelected) currentSelected.add(modelId)
            if (result.isFailure) currentSelected.remove(modelId)

            keyManager.updateKey(entry.copy(
                checkedModels = currentChecked,
                selectedModels = currentSelected
            ))
            retestingModel = ""
            refresh()
        }
    }

    LaunchedEffect(Unit) { refresh() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("AI Configuration", fontWeight = FontWeight.Bold)
                        val onlineCount = onlineKeys.count { it.enabled }
                        val hasOffline = offlineKey?.enabled == true
                        val parts = mutableListOf<String>()
                        if (onlineCount > 0) parts.add("$onlineCount online key${if (onlineCount != 1) "s" else ""}")
                        if (hasOffline) parts.add("offline active")
                        if (parts.isEmpty()) parts.add("not configured")
                        Text(parts.joinToString(" · "),
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
                text = { Text("Add Online Key") },
                containerColor = MaterialTheme.colorScheme.primary
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // ── ONLINE MODE SECTION (first — primary) ───────────────────
            item {
                Spacer(Modifier.height(4.dp))
                OnlineModeHeader(
                    keyCount = onlineKeys.size,
                    activeCount = onlineKeys.count { it.enabled },
                    onAddKey = { showAddDialog = true }
                )
            }

            if (onlineKeys.isNotEmpty()) {
                item { FailoverBanner() }
                val allKeys = keys // need full list for index
                itemsIndexed(onlineKeys) { _, entry ->
                    val globalIndex = allKeys.indexOf(entry)
                    val onlineIndex = onlineKeys.indexOf(entry)
                    ApiKeyCard(
                        entry = entry,
                        index = onlineIndex,
                        isActive = onlineIndex == 0 && entry.enabled,
                        isFirst = onlineIndex == 0,
                        isLast = onlineIndex == onlineKeys.lastIndex,
                        onToggle = {
                            keyManager.updateKey(entry.copy(enabled = !entry.enabled))
                            refresh()
                        },
                        onEdit = { editingKey = entry },
                        onDelete = { keyManager.deleteKey(entry.id); refresh() },
                        onMoveUp = { keyManager.moveKey(entry.id, -1); refresh() },
                        onMoveDown = { keyManager.moveKey(entry.id, +1); refresh() },
                        onSetActive = { keyManager.setActiveKey(entry.id); refresh() },
                        checkState = modelCheckStates[entry.id],
                        isModelListExpanded = entry.id in expandedModelLists,
                        retestingModel = retestingModel,
                        onCheckAllModels = { checkAllModels(entry) },
                        onToggleModelList = {
                            expandedModelLists = if (entry.id in expandedModelLists)
                                expandedModelLists - entry.id
                            else expandedModelLists + entry.id
                        },
                        onToggleModel = { modelId -> toggleModel(entry, modelId) },
                        onRetestModel = { modelId -> retestModel(entry, modelId) },
                        onToggleGoogleSearch = {
                            keyManager.updateKey(entry.copy(googleSearch = !entry.safeGoogleSearch))
                            refresh()
                        }
                    )
                }
            } else {
                item {
                    Surface(shape = RoundedCornerShape(12.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)) {
                        Column(
                            modifier = Modifier.fillMaxWidth().padding(24.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Text("🌐", fontSize = 36.sp)
                            Text("No online API keys", fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                            Text("Add an API key to use cloud AI models (OpenAI, Claude, Gemini, etc.)",
                                fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                                lineHeight = 17.sp,
                                modifier = Modifier.padding(horizontal = 16.dp))
                            Button(onClick = { showAddDialog = true }) {
                                Icon(Icons.Default.Add, null, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(6.dp))
                                Text("Add API Key", fontSize = 13.sp)
                            }
                        }
                    }
                }
            }

            // ── OFFLINE MODE SECTION (fallback — after online) ──────────
            item {
                Spacer(Modifier.height(4.dp))
                OfflineModeSection(
                    offlineKey = offlineKey,
                    appModels = offlineModelsApp,
                    offlineManager = offlineManager,
                    isLoading = offlineLoading,
                    isImporting = offlineImporting,
                    validation = offlineValidation,
                    onLoadModel = { model ->
                        offlineLoading = true
                        scope.launch {
                            val result = offlineManager.loadModel(model.path)
                            if (result.isSuccess) {
                                if (offlineKey != null) {
                                    keyManager.updateKey(offlineKey.copy(
                                        preferredModel = model.name,
                                        enabled = true
                                    ))
                                } else {
                                    keyManager.addKey(ApiKeyEntry(
                                        label = "Offline: ${model.name}",
                                        provider = "offline",
                                        apiKey = "offline",
                                        preferredModel = model.name,
                                        enabled = true
                                    ))
                                }
                                offlineValidation = ValidationUi(ValidationState.SUCCESS,
                                    "✅ Model loaded: ${model.name} (${model.sizeMB})")
                            } else {
                                offlineValidation = ValidationUi(ValidationState.ERROR,
                                    "❌ Failed: ${result.exceptionOrNull()?.message}")
                            }
                            offlineLoading = false
                            refresh()
                        }
                    },
                    onPickModel = {
                        modelPickerLauncher.launch(arrayOf("application/octet-stream", "*/*"))
                    },
                    onDeleteModel = { showDeleteModel = it },
                    onDisableOffline = {
                        if (offlineKey != null) {
                            keyManager.updateKey(offlineKey.copy(enabled = false))
                            offlineManager.destroyEngine()
                            offlineValidation = ValidationUi()
                            refresh()
                        }
                    },
                    onEnableOffline = {
                        if (offlineKey != null) {
                            keyManager.updateKey(offlineKey.copy(enabled = true))
                            refresh()
                        }
                    }
                )
            }

            item { Spacer(Modifier.height(80.dp)) }
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

    // ── Import or use-in-place dialog ──────────────────────────────────
    if (pendingPickedUri != null) {
        val pickedUri = pendingPickedUri!!
        val pickedName = pendingPickedName
        val resolvedPath = remember(pickedUri) { offlineManager.resolveFilePath(pickedUri) }
        val canUseInPlace = resolvedPath != null && java.io.File(resolvedPath).exists()

        AlertDialog(
            onDismissRequest = { pendingPickedUri = null },
            icon = { Text("📁", fontSize = 28.sp) },
            title = { Text("Import Model?", fontWeight = FontWeight.Bold, fontSize = 16.sp) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(pickedName, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                    Text(
                        "Save to app storage? This is recommended for reliable background use — " +
                        "the model stays available even if the original file is deleted.",
                        fontSize = 12.sp, lineHeight = 17.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            },
            confirmButton = {
                Button(onClick = {
                    pendingPickedUri = null
                    offlineImporting = true
                    offlineValidation = ValidationUi(ValidationState.LOADING, "Importing $pickedName…")
                    scope.launch {
                        try {
                            val imported = offlineManager.importModel(pickedUri, pickedName)
                            offlineValidation = ValidationUi(ValidationState.SUCCESS,
                                "✅ Saved: ${imported.name} (${imported.sizeMB})")
                            offlineModelsApp = offlineManager.listAppModels()
                        } catch (e: Exception) {
                            offlineValidation = ValidationUi(ValidationState.ERROR,
                                "❌ Import failed: ${e.message}")
                        }
                        offlineImporting = false
                    }
                }) { Text("Save to App") }
            },
            dismissButton = {
                if (canUseInPlace) {
                    TextButton(onClick = {
                        pendingPickedUri = null
                        // Use from current location — load directly
                        offlineLoading = true
                        scope.launch {
                            val result = offlineManager.loadModel(resolvedPath!!)
                            if (result.isSuccess) {
                                val file = java.io.File(resolvedPath)
                                val model = OfflineModelManager.ModelFile(
                                    file.name, file.absolutePath, file.length(), isInAppStorage = false
                                )
                                if (offlineKey != null) {
                                    keyManager.updateKey(offlineKey.copy(
                                        preferredModel = file.name, enabled = true
                                    ))
                                } else {
                                    keyManager.addKey(ApiKeyEntry(
                                        label = "Offline: ${file.name}",
                                        provider = "offline", apiKey = "offline",
                                        preferredModel = file.name, enabled = true
                                    ))
                                }
                                offlineValidation = ValidationUi(ValidationState.SUCCESS,
                                    "✅ Loaded from: ${file.name} (${model.sizeMB})")
                            } else {
                                offlineValidation = ValidationUi(ValidationState.ERROR,
                                    "❌ Failed: ${result.exceptionOrNull()?.message}")
                            }
                            offlineLoading = false
                            refresh()
                        }
                    }) { Text("Use from Current Location") }
                } else {
                    TextButton(onClick = { pendingPickedUri = null }) { Text("Cancel") }
                }
            }
        )
    }

    // ── Delete confirmation dialog ───────────────────────────────────────
    showDeleteModel?.let { model ->
        AlertDialog(
            onDismissRequest = { showDeleteModel = null },
            icon = { Icon(Icons.Default.Delete, null, tint = MaterialTheme.colorScheme.error) },
            title = { Text("Delete Model?", fontWeight = FontWeight.Bold) },
            text = { Text("Delete ${model.name} (${model.sizeMB}) from app storage? This cannot be undone.") },
            confirmButton = {
                Button(
                    onClick = {
                        offlineManager.deleteAppModel(model.name)
                        // If this was the active offline model, clear it
                        if (offlineKey?.safePreferredModel == model.name) {
                            keyManager.updateKey(offlineKey.copy(preferredModel = null, enabled = false))
                        }
                        showDeleteModel = null
                        refresh()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) { Text("Delete") }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteModel = null }) { Text("Cancel") }
            }
        )
    }
}

// ── Offline Mode Section ─────────────────────────────────────────────────────

@Composable
fun OfflineModeSection(
    offlineKey: ApiKeyEntry?,
    appModels: List<OfflineModelManager.ModelFile>,
    offlineManager: OfflineModelManager,
    isLoading: Boolean,
    isImporting: Boolean,
    validation: ValidationUi,
    onLoadModel: (OfflineModelManager.ModelFile) -> Unit,
    onPickModel: () -> Unit,
    onDeleteModel: (OfflineModelManager.ModelFile) -> Unit,
    onDisableOffline: () -> Unit,
    onEnableOffline: () -> Unit
) {
    val isActive = offlineKey?.enabled == true
    val loadedModel = offlineManager.loadedModelName.collectAsState().value
    val context = LocalContext.current
    val uriHandler = LocalUriHandler.current

    // Track download progress for catalog models
    val downloadProgress = remember { mutableStateMapOf<String, ModelDownloadProgress>() }
    // Check files on disk directly — appModels may be stale after download
    val modelsDir = remember { java.io.File(context.filesDir, "offline_models") }
    // Refresh trigger: changes when appModels changes OR when a download completes
    val downloadedCount = downloadProgress.values.count { it.state == ModelDownloadState.DOWNLOADED }
    val existingFiles = remember(appModels, downloadedCount) {
        (modelsDir.listFiles()?.map { it.name }?.toSet() ?: emptySet())
    }

    // Observe WorkManager for active downloads
    LaunchedEffect(Unit) {
        ModelCatalog.models.forEach { catalogModel ->
            val workName = "download_${catalogModel.id}"
            WorkManager.getInstance(context)
                .getWorkInfosForUniqueWorkLiveData(workName)
                .observeForever { workInfos ->
                    val info = workInfos?.firstOrNull() ?: return@observeForever
                    when (info.state) {
                        WorkInfo.State.RUNNING -> {
                            val p = info.progress.getFloat(ModelDownloadWorker.KEY_PROGRESS, 0f)
                            val recv = info.progress.getLong(ModelDownloadWorker.KEY_RECEIVED_BYTES, 0L)
                            val speed = info.progress.getLong(ModelDownloadWorker.KEY_SPEED, 0L)
                            downloadProgress[catalogModel.id] = ModelDownloadProgress(
                                state = ModelDownloadState.DOWNLOADING, progress = p,
                                bytesDownloaded = recv, speedBytesPerSec = speed
                            )
                        }
                        WorkInfo.State.SUCCEEDED -> {
                            downloadProgress[catalogModel.id] = ModelDownloadProgress(
                                state = ModelDownloadState.DOWNLOADED, progress = 1f
                            )
                        }
                        WorkInfo.State.FAILED -> {
                            downloadProgress[catalogModel.id] = ModelDownloadProgress(
                                state = ModelDownloadState.FAILED,
                                error = info.outputData.getString("error")
                            )
                        }
                        WorkInfo.State.CANCELLED -> {
                            downloadProgress.remove(catalogModel.id)
                        }
                        else -> {}
                    }
                }
        }
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (isActive) Color(0xFF795548).copy(alpha = 0.08f)
            else MaterialTheme.colorScheme.surface
        ),
        border = if (isActive) CardDefaults.outlinedCardBorder().copy(
            brush = androidx.compose.ui.graphics.SolidColor(Color(0xFF795548)),
            width = 1.5.dp
        ) else null,
        elevation = CardDefaults.cardElevation(2.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)) {

            // ── Header ───────────────────────────────────────────────────
            Row(verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("📱", fontSize = 24.sp)
                Column(modifier = Modifier.weight(1f)) {
                    Text("Offline Mode", fontWeight = FontWeight.Bold, fontSize = 16.sp)
                    Text("Run AI models locally on your device — no internet needed",
                        fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        lineHeight = 15.sp)
                }
                if (offlineKey != null) {
                    Switch(
                        checked = isActive,
                        onCheckedChange = { if (it) onEnableOffline() else onDisableOffline() },
                        colors = SwitchDefaults.colors(
                            checkedTrackColor = Color(0xFF795548)
                        )
                    )
                }
            }

            // ── Currently loaded model badge ─────────────────────────────
            if (isActive && loadedModel != null) {
                Surface(shape = RoundedCornerShape(8.dp),
                    color = Color(0xFF4CAF50).copy(alpha = 0.12f)) {
                    Row(modifier = Modifier.fillMaxWidth().padding(10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("🟢", fontSize = 12.sp)
                        Text("Active: $loadedModel", fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold, color = Color(0xFF2E7D32))
                    }
                }
            }

            // ── Loading / Importing indicators ───────────────────────────
            if (isLoading) {
                Surface(shape = RoundedCornerShape(8.dp),
                    color = MaterialTheme.colorScheme.primaryContainer) {
                    Row(modifier = Modifier.fillMaxWidth().padding(10.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                        Text("Loading model... this may take a moment", fontSize = 12.sp)
                    }
                }
            }
            if (isImporting) {
                Surface(shape = RoundedCornerShape(8.dp),
                    color = MaterialTheme.colorScheme.primaryContainer) {
                    Row(modifier = Modifier.fillMaxWidth().padding(10.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
                        Text("Importing model to app storage...", fontSize = 11.sp)
                    }
                }
            }

            // ── Validation result ────────────────────────────────────────
            AnimatedVisibility(visible = validation.state != ValidationState.IDLE) {
                ValidationCard(validation)
            }

            // ── Downloaded Models (in App Storage) ───────────────────────
            if (appModels.isNotEmpty()) {
                Text("📦 My Models", fontSize = 13.sp,
                    fontWeight = FontWeight.Bold, color = Color(0xFF4CAF50))
                appModels.forEach { model ->
                    OfflineModelRow(
                        model = model,
                        isLoaded = offlineManager.getLoadedModelPath() == model.path,
                        isSelected = offlineKey?.safePreferredModel == model.name,
                        onLoad = { onLoadModel(model) },
                        onDelete = { onDeleteModel(model) },
                        loadingThis = isLoading && offlineKey?.safePreferredModel != model.name
                    )
                }
                Spacer(Modifier.height(4.dp))
            }

            // ── Model Catalog (Available for Download) ──────────────────
            Text("🌐 Available Models", fontSize = 13.sp,
                fontWeight = FontWeight.Bold, color = Color(0xFF1976D2))
            Text("Tap Download to get a model. One-time download, then works offline forever.",
                fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                lineHeight = 14.sp)

            ModelCatalog.models.forEach { catalogModel ->
                val fileOnDisk = existingFiles.contains(catalogModel.fileName)
                val progress = downloadProgress[catalogModel.id]
                val isDownloading = progress?.state == ModelDownloadState.DOWNLOADING
                val isLoaded2 = loadedModel == catalogModel.fileName

                CatalogModelCard(
                    model = catalogModel,
                    isDownloaded = fileOnDisk,
                    isDownloading = isDownloading,
                    isActive = isLoaded2,
                    progress = progress,
                    onDownload = {
                        downloadProgress[catalogModel.id] = ModelDownloadProgress(
                            state = ModelDownloadState.DOWNLOADING
                        )
                        ModelDownloadWorker.enqueue(context, catalogModel)
                    },
                    onCancel = {
                        ModelDownloadWorker.cancel(context, catalogModel.id)
                        downloadProgress.remove(catalogModel.id)
                    },
                    onOpenLink = { uriHandler.openUri(catalogModel.learnMoreUrl) },
                    onActivate = {
                        // Load from disk — build ModelFile and call onLoadModel
                        val file = java.io.File(modelsDir, catalogModel.fileName)
                        if (file.exists()) {
                            val mf = OfflineModelManager.ModelFile(
                                file.name, file.absolutePath, file.length(), true
                            )
                            onLoadModel(mf)
                        }
                    }
                )
            }

            // ── Manual Import (legacy) ───────────────────────────────────
            Spacer(Modifier.height(2.dp))
            OutlinedButton(
                onClick = onPickModel,
                enabled = !isImporting,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(10.dp),
                border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF795548).copy(alpha = 0.5f))
            ) {
                Icon(Icons.Default.FolderOpen, null, modifier = Modifier.size(16.dp),
                    tint = Color(0xFF795548))
                Spacer(Modifier.width(8.dp))
                Text("Import Model from File", fontSize = 12.sp, color = Color(0xFF795548))
            }
            Text("Have a .bin or .litertlm file already? Import it directly.",
                fontSize = 10.sp, lineHeight = 14.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 4.dp))
        }
    }
}

// ── Catalog Model Card ──────────────────────────────────────────────────────

@Composable
fun CatalogModelCard(
    model: CatalogModel,
    isDownloaded: Boolean,
    isDownloading: Boolean,
    isActive: Boolean,
    progress: ModelDownloadProgress?,
    onDownload: () -> Unit,
    onCancel: () -> Unit,
    onOpenLink: () -> Unit,
    onActivate: () -> Unit
) {
    var showSettings by remember { mutableStateOf(false) }
    // Edge Gallery-style config state
    var selectedAccelerator by remember { mutableStateOf("CPU") }
    var temperature by remember { mutableFloatStateOf(model.defaultTemperature) }
    var topK by remember { mutableIntStateOf(model.defaultTopK) }
    var maxTokens by remember { mutableIntStateOf(model.defaultMaxTokens) }
    val badgeColor = when (model.badge) {
        "BEST" -> Color(0xFF4CAF50)
        "PRO" -> Color(0xFF1976D2)
        "TINY" -> Color(0xFFFF9800)
        "NEW" -> Color(0xFFE91E63)
        else -> null
    }

    // Clean state-based colors
    val surfaceColor = MaterialTheme.colorScheme.surface
    val cardBg = when {
        isActive -> surfaceColor  // clean white/dark, border does the work
        isDownloaded -> surfaceColor
        isDownloading -> surfaceColor
        else -> surfaceColor
    }
    val borderColor = when {
        isActive -> Color(0xFF4CAF50)
        isDownloaded -> Color(0xFF78909C).copy(alpha = 0.3f)
        isDownloading -> Color(0xFF1976D2).copy(alpha = 0.4f)
        else -> Color.Transparent
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = cardBg),
        border = CardDefaults.outlinedCardBorder().copy(
            brush = androidx.compose.ui.graphics.SolidColor(borderColor),
            width = if (isActive) 2.dp else 1.dp
        ),
        elevation = CardDefaults.cardElevation(if (isActive) 3.dp else 1.dp)
    ) {
        Column(modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)) {

            // Row 1: Name + badges + size
            Row(verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(model.name, fontSize = 14.sp, fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f, fill = false),
                    maxLines = 1, overflow = TextOverflow.Ellipsis)

                if (model.badge != null && badgeColor != null) {
                    Surface(shape = RoundedCornerShape(4.dp),
                        color = badgeColor.copy(alpha = 0.12f)) {
                        Text(model.badge,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                            fontSize = 9.sp, fontWeight = FontWeight.ExtraBold,
                            color = badgeColor)
                    }
                }

                // Status badge
                when {
                    isActive -> Surface(shape = RoundedCornerShape(4.dp),
                        color = Color(0xFF4CAF50)) {
                        Text("ACTIVE",
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                            fontSize = 8.sp, fontWeight = FontWeight.Bold,
                            color = Color.White)
                    }
                    isDownloaded -> Surface(shape = RoundedCornerShape(4.dp),
                        color = Color(0xFF78909C).copy(alpha = 0.15f)) {
                        Text("READY",
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                            fontSize = 8.sp, fontWeight = FontWeight.Bold,
                            color = Color(0xFF546E7A))
                    }
                }

                Text(model.sizeLabel, fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }

            // Row 2: Description
            Text(model.description, fontSize = 11.sp, lineHeight = 15.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2)

            // Row 3: Feature tags
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp),
                modifier = Modifier.horizontalScroll(rememberScrollState())) {
                model.featureTags.forEach { tag ->
                    val tagColor = when (tag) {
                        "Thinking" -> Color(0xFF9C27B0)
                        "Vision" -> Color(0xFF1976D2)
                        "Audio" -> Color(0xFFFF5722)
                        else -> Color(0xFF607D8B)
                    }
                    Surface(shape = RoundedCornerShape(4.dp),
                        color = tagColor.copy(alpha = 0.08f)) {
                        Text(tag,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                            fontSize = 9.sp, color = tagColor, fontWeight = FontWeight.Medium)
                    }
                }
            }

            // Row 4: Settings panel (Edge Gallery style) — shown when downloaded
            if (isDownloaded) {
                // Settings toggle
                Row(
                    modifier = Modifier.fillMaxWidth().clickable { showSettings = !showSettings }
                        .padding(vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Icon(
                        if (showSettings) Icons.Default.ExpandLess else Icons.Default.Settings,
                        null, modifier = Modifier.size(14.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        if (showSettings) "Hide settings" else "Model settings",
                        fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                AnimatedVisibility(visible = showSettings) {
                    Column(
                        modifier = Modifier.fillMaxWidth()
                            .background(
                                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
                                RoundedCornerShape(8.dp)
                            )
                            .padding(10.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        // Accelerator selector
                        Text("Accelerator", fontSize = 10.sp, fontWeight = FontWeight.Bold)
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            val accOptions = model.accelerators.split(",").map { it.trim().uppercase() }
                            accOptions.forEach { acc ->
                                val selected = selectedAccelerator == acc
                                FilterChip(
                                    selected = selected,
                                    onClick = { selectedAccelerator = acc },
                                    label = { Text(acc, fontSize = 10.sp) },
                                    modifier = Modifier.height(28.dp),
                                    colors = FilterChipDefaults.filterChipColors(
                                        selectedContainerColor = Color(0xFF1976D2).copy(alpha = 0.15f)
                                    )
                                )
                            }
                        }
                        if (selectedAccelerator == "GPU") {
                            Text("Warning: GPU loads full model into VRAM. May crash on phones with < 12GB RAM.",
                                fontSize = 9.sp, color = Color(0xFFE65100), lineHeight = 13.sp)
                        }

                        // Temperature
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("Temperature", fontSize = 10.sp, fontWeight = FontWeight.Bold,
                                modifier = Modifier.width(85.dp))
                            Slider(
                                value = temperature,
                                onValueChange = { temperature = it },
                                valueRange = 0f..2f,
                                modifier = Modifier.weight(1f).height(20.dp)
                            )
                            Text("${"%.1f".format(temperature)}", fontSize = 10.sp,
                                modifier = Modifier.width(30.dp))
                        }

                        // Top-K
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("Top-K", fontSize = 10.sp, fontWeight = FontWeight.Bold,
                                modifier = Modifier.width(85.dp))
                            Slider(
                                value = topK.toFloat(),
                                onValueChange = { topK = it.toInt() },
                                valueRange = 1f..100f,
                                modifier = Modifier.weight(1f).height(20.dp)
                            )
                            Text("$topK", fontSize = 10.sp,
                                modifier = Modifier.width(30.dp))
                        }

                        // Max Tokens
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("Max Tokens", fontSize = 10.sp, fontWeight = FontWeight.Bold,
                                modifier = Modifier.width(85.dp))
                            Slider(
                                value = maxTokens.toFloat(),
                                onValueChange = { maxTokens = it.toInt() },
                                valueRange = 256f..model.maxContext.toFloat(),
                                modifier = Modifier.weight(1f).height(20.dp)
                            )
                            Text("$maxTokens", fontSize = 10.sp,
                                modifier = Modifier.width(40.dp))
                        }
                    }
                }
            }

            // Row 5: Download progress bar
            if (isDownloading && progress != null) {
                Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    LinearProgressIndicator(
                        progress = { progress.progress.coerceIn(0f, 1f) },
                        modifier = Modifier.fillMaxWidth().height(6.dp)
                            .clip(RoundedCornerShape(3.dp)),
                        color = Color(0xFF1976D2),
                        trackColor = Color(0xFF1976D2).copy(alpha = 0.12f)
                    )
                    Row(horizontalArrangement = Arrangement.SpaceBetween,
                        modifier = Modifier.fillMaxWidth()) {
                        val pct = (progress.progress * 100).toInt()
                        val recvMb = "%.0f".format(progress.bytesDownloaded / (1024.0 * 1024.0))
                        val totalMb = "%.0f".format(model.sizeBytes / (1024.0 * 1024.0))
                        Text("$pct% — $recvMb / $totalMb MB",
                            fontSize = 9.sp, color = Color(0xFF1976D2))
                        if (progress.speedBytesPerSec > 0) {
                            Text("${"%.1f".format(progress.speedBytesPerSec / (1024.0 * 1024.0))} MB/s",
                                fontSize = 9.sp, color = Color(0xFF1976D2))
                        }
                    }
                }
            }

            // Row 5: Error with retry
            if (progress?.state == ModelDownloadState.FAILED && progress.error != null) {
                Surface(shape = RoundedCornerShape(6.dp),
                    color = Color(0xFFD32F2F).copy(alpha = 0.08f)) {
                    Row(modifier = Modifier.fillMaxWidth().padding(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("Failed: ${progress.error?.take(60)}",
                            fontSize = 10.sp, color = Color(0xFFD32F2F),
                            modifier = Modifier.weight(1f), maxLines = 2)
                        TextButton(onClick = onDownload,
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)) {
                            Text("Retry", fontSize = 10.sp, fontWeight = FontWeight.Bold,
                                color = Color(0xFFD32F2F))
                        }
                    }
                }
            }

            // Row 6: Action buttons
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()) {

                // HuggingFace link
                TextButton(
                    onClick = onOpenLink,
                    contentPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp)
                ) {
                    Text("View on HuggingFace", fontSize = 10.sp,
                        color = Color(0xFFFFB300))
                    Spacer(Modifier.width(2.dp))
                    Icon(Icons.Default.OpenInNew, null, modifier = Modifier.size(11.dp),
                        tint = Color(0xFFFFB300))
                }

                Spacer(Modifier.weight(1f))

                when {
                    isActive -> {
                        // Already active — show checkmark
                        Surface(shape = RoundedCornerShape(8.dp),
                            color = Color(0xFF4CAF50).copy(alpha = 0.1f)) {
                            Row(modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                Icon(Icons.Default.CheckCircle, null,
                                    modifier = Modifier.size(14.dp), tint = Color(0xFF4CAF50))
                                Text("Active", fontSize = 11.sp, fontWeight = FontWeight.Bold,
                                    color = Color(0xFF4CAF50))
                            }
                        }
                    }
                    isDownloaded -> {
                        // Downloaded but not active — activate button
                        Button(
                            onClick = onActivate,
                            modifier = Modifier.height(34.dp),
                            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 0.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = Color(0xFF4CAF50)),
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Icon(Icons.Default.PlayArrow, null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("Activate", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                    isDownloading -> {
                        // Downloading — cancel button
                        OutlinedButton(
                            onClick = onCancel,
                            modifier = Modifier.height(34.dp),
                            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 0.dp),
                            shape = RoundedCornerShape(8.dp),
                            border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFFD32F2F).copy(alpha = 0.5f))
                        ) {
                            Icon(Icons.Default.Close, null, modifier = Modifier.size(14.dp),
                                tint = Color(0xFFD32F2F))
                            Spacer(Modifier.width(4.dp))
                            Text("Cancel", fontSize = 11.sp, color = Color(0xFFD32F2F))
                        }
                    }
                    else -> {
                        // Not downloaded — download button
                        Button(
                            onClick = onDownload,
                            modifier = Modifier.height(34.dp),
                            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 0.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = Color(0xFF1976D2)),
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Icon(Icons.Default.Download, null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("Download", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }
    }
}

// ── Online Mode Header ───────────────────────────────────────────────────────

@Composable
fun OnlineModeHeader(keyCount: Int, activeCount: Int, onAddKey: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("🌐", fontSize = 22.sp)
        Column(modifier = Modifier.weight(1f)) {
            Text("Online Mode", fontWeight = FontWeight.Bold, fontSize = 16.sp)
            if (keyCount > 0) {
                Text("$activeCount active key${if (activeCount != 1) "s" else ""} · failover enabled",
                    fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                Text("Add API keys for cloud AI providers",
                    fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

// ── Offline Model Row ────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OfflineModelRow(
    model: OfflineModelManager.ModelFile,
    isLoaded: Boolean,
    isSelected: Boolean,
    onLoad: () -> Unit,
    onDelete: (() -> Unit)?,
    loadingThis: Boolean
) {
    val accent = Color(0xFF795548)
    Card(
        onClick = onLoad,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(10.dp),
        colors = CardDefaults.cardColors(
            containerColor = when {
                isLoaded -> Color(0xFF4CAF50).copy(alpha = 0.08f)
                isSelected -> accent.copy(alpha = 0.1f)
                else -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
            }
        ),
        border = if (isLoaded) CardDefaults.outlinedCardBorder().copy(
            brush = androidx.compose.ui.graphics.SolidColor(Color(0xFF4CAF50)), width = 1.5.dp
        ) else null
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // Status icon
            when {
                isLoaded -> Icon(Icons.Default.CheckCircle, null,
                    tint = Color(0xFF4CAF50), modifier = Modifier.size(20.dp))
                loadingThis -> CircularProgressIndicator(
                    modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                else -> Icon(Icons.Default.PlayCircleOutline, "Load model",
                    tint = accent, modifier = Modifier.size(20.dp))
            }

            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(model.name, fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false))
                    if (isLoaded) {
                        Surface(shape = RoundedCornerShape(4.dp),
                            color = Color(0xFF4CAF50).copy(alpha = 0.15f)) {
                            Text("LOADED", modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp),
                                fontSize = 8.sp, fontWeight = FontWeight.Bold, color = Color(0xFF4CAF50))
                        }
                    }
                }
                Text(model.sizeMB, fontSize = 10.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }

            // Delete button
            onDelete?.let {
                IconButton(onClick = it, modifier = Modifier.size(32.dp)) {
                    Icon(Icons.Default.Delete, "Delete",
                        modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.error)
                }
            }
        }
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
    onSetActive: () -> Unit,
    checkState: ModelCheckState? = null,
    isModelListExpanded: Boolean = false,
    retestingModel: String = "",
    onCheckAllModels: () -> Unit = {},
    onToggleModelList: () -> Unit = {},
    onToggleModel: (String) -> Unit = {},
    onRetestModel: (String) -> Unit = {},
    onToggleGoogleSearch: () -> Unit = {}
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

            // ── Card-level Test Connection (works for every provider, even before models load) ──
            // Gemini already exposes per-model Test buttons inside its model list, but for
            // OpenAI / Anthropic / OpenRouter / Grok / Ollama users, this is the only way
            // to confirm the key actually works without picking a specific model first.
            if (entry.safeProvider != "offline") {
                val cardCtx = LocalContext.current
                val cardScope = rememberCoroutineScope()
                var testing by remember(entry.id) { mutableStateOf(false) }
                var testOk by remember(entry.id) { mutableStateOf<Boolean?>(null) }
                var testMessage by remember(entry.id) { mutableStateOf("") }

                val pillColor = when (testOk) {
                    true -> Color(0xFF4CAF50)
                    false -> Color(0xFFE53935)
                    null -> Color(0xFF03A9F4)
                }

                val cardClipboard = LocalClipboardManager.current

                Spacer(Modifier.height(8.dp))
                Column(modifier = Modifier.fillMaxWidth()) {
                    Surface(
                        onClick = onClick@{
                            if (testing) return@onClick
                            val model = entry.safePreferredModel.ifBlank { defaultTestModelFor(entry.safeProvider) }
                            if (model.isBlank() && entry.safeProvider != "ollama") {
                                testOk = false
                                testMessage = "No model configured — set a preferred model first"
                                return@onClick
                            }
                            testing = true
                            testOk = null
                            testMessage = "Testing ${model.ifBlank { "default" }}…"
                            cardScope.launch {
                                val router = LlmRouter.getInstance(cardCtx)
                                val result = router.testSingleModel(entry, model)
                                testing = false
                                testOk = result.isSuccess
                                testMessage = if (result.isSuccess) {
                                    "✓ Working — \"${result.getOrNull().orEmpty().trim().take(120)}\""
                                } else {
                                    // Show the FULL error — no truncation. Long errors wrap to multiple
                                    // lines below the pill so the user can read what the provider rejected.
                                    "✗ ${result.exceptionOrNull()?.message ?: "Failed"}"
                                }
                            }
                        },
                        shape = RoundedCornerShape(8.dp),
                        color = pillColor.copy(alpha = 0.12f),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            if (testing) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(14.dp),
                                    strokeWidth = 2.dp,
                                    color = pillColor,
                                )
                            } else {
                                Icon(Icons.Default.PlayArrow, null,
                                    modifier = Modifier.size(16.dp), tint = pillColor)
                            }
                            Spacer(Modifier.width(6.dp))
                            // Short label inside the pill — full text shows below if it's long
                            Text(
                                when {
                                    testing -> testMessage
                                    testMessage.isBlank() -> "Test Connection"
                                    testOk == true -> "✓ Working — tap below to copy reply"
                                    testOk == false -> "✗ Failed — full error below (tap to copy)"
                                    else -> testMessage
                                },
                                fontSize = 11.sp, fontWeight = FontWeight.Bold,
                                maxLines = 1, overflow = TextOverflow.Ellipsis,
                                color = pillColor,
                            )
                        }
                    }
                    // Full result/error body — wraps, copy-on-tap, no truncation. Hidden when
                    // the user hasn't tested yet or while a request is in flight.
                    if (!testing && testMessage.isNotBlank() && testOk != null) {
                        Surface(
                            onClick = {
                                cardClipboard.setText(androidx.compose.ui.text.AnnotatedString(testMessage))
                                Toast.makeText(cardCtx, "Copied", Toast.LENGTH_SHORT).show()
                            },
                            shape = RoundedCornerShape(6.dp),
                            color = pillColor.copy(alpha = 0.06f),
                            modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                        ) {
                            Text(
                                testMessage,
                                fontSize = 10.sp,
                                fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                                color = pillColor.copy(alpha = 0.85f),
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                            )
                        }
                    }
                }
            }

            // ── Check All Models section ────────────────────────────────────
            if (entry.safeAvailableModels.isNotEmpty() && entry.safeProvider != "offline") {
                val isChecking = checkState?.isChecking == true

                Spacer(Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    // Check All Models button
                    Surface(
                        onClick = { if (!isChecking) onCheckAllModels() },
                        shape = RoundedCornerShape(8.dp),
                        color = Color(0xFF00BCD4).copy(alpha = 0.12f),
                        modifier = Modifier.weight(1f)
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                            horizontalArrangement = Arrangement.Center,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            if (isChecking) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(14.dp),
                                    strokeWidth = 2.dp,
                                    color = Color(0xFF00BCD4)
                                )
                                Spacer(Modifier.width(8.dp))
                                val done = checkState?.checkedCount ?: 0
                                val total = checkState?.totalModels ?: 0
                                Text(
                                    "Checking… $done/$total",
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = Color(0xFF00BCD4)
                                )
                            } else {
                                Icon(Icons.Default.Refresh, null,
                                    modifier = Modifier.size(14.dp),
                                    tint = Color(0xFF00BCD4))
                                Spacer(Modifier.width(6.dp))
                                Text(
                                    if (entry.safeCheckedModels.isNotEmpty()) "Re-check All" else "Check All Models",
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = Color(0xFF00BCD4)
                                )
                            }
                        }
                    }

                    // Show/Hide models toggle
                    if (entry.safeCheckedModels.isNotEmpty() && !isChecking) {
                        Surface(
                            onClick = onToggleModelList,
                            shape = RoundedCornerShape(8.dp),
                            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)
                        ) {
                            Text(
                                if (isModelListExpanded) "Hide"
                                else "${entry.workingModels.size}/${entry.safeCheckedModels.size} OK",
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                            )
                        }
                    }
                }

                // ── Live progress during checking ───────────────────────────
                if (isChecking && checkState != null && checkState.results.isNotEmpty()) {
                    Spacer(Modifier.height(6.dp))
                    Column(modifier = Modifier.padding(start = 4.dp)) {
                        checkState.results.forEach { (modelId, result) ->
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(vertical = 1.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                when {
                                    result == null -> // pass
                                        Icon(Icons.Default.Check, null,
                                            modifier = Modifier.size(12.dp),
                                            tint = Color(0xFF4CAF50))
                                    else -> // fail
                                        Icon(Icons.Default.Close, null,
                                            modifier = Modifier.size(12.dp),
                                            tint = Color(0xFFEF5350))
                                }
                                Spacer(Modifier.width(6.dp))
                                Text(
                                    modelId,
                                    fontSize = 10.sp,
                                    color = if (result == null) Color(0xFF4CAF50)
                                    else Color(0xFFEF5350).copy(alpha = 0.7f),
                                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f)
                                )
                                if (result != null) {
                                    Text(
                                        result.take(30),
                                        fontSize = 9.sp,
                                        color = Color(0xFFEF5350).copy(alpha = 0.5f),
                                        maxLines = 1
                                    )
                                }
                            }
                        }
                        // Show pending model with spinner
                        if (checkState.checkedCount < checkState.totalModels) {
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(vertical = 1.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(12.dp),
                                    strokeWidth = 1.5.dp,
                                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f)
                                )
                                Spacer(Modifier.width(6.dp))
                                Text(
                                    checkState.currentModel,
                                    fontSize = 10.sp,
                                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f),
                                    maxLines = 1
                                )
                            }
                        }
                    }
                }

                // ── Model selection list (after checking) ───────────────────
                if (isModelListExpanded && entry.safeCheckedModels.isNotEmpty() && !isChecking) {
                    Spacer(Modifier.height(6.dp))
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.5f)
                        ),
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Column(modifier = Modifier.padding(8.dp)) {
                            Text(
                                "Select models for this key:",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                                modifier = Modifier.padding(bottom = 4.dp)
                            )
                            // Merge checkedModels with selectedModels that may be missing
                            // (recovery for data saved before serializeNulls fix)
                            val mergedModels = buildMap {
                                putAll(entry.safeCheckedModels)
                                for (m in entry.safeSelectedModels) {
                                    if (m !in this) put(m, null) // selected but missing → treat as OK
                                }
                            }
                            mergedModels.toSortedMap().forEach { (modelId, errorMsg) ->
                                val works = errorMsg == null
                                val isSelected = modelId in entry.safeSelectedModels
                                val isRetesting = retestingModel == "${entry.id}:$modelId"

                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable(enabled = works) { onToggleModel(modelId) }
                                        .padding(vertical = 3.dp, horizontal = 4.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Checkbox(
                                        checked = isSelected,
                                        onCheckedChange = { if (works) onToggleModel(modelId) },
                                        enabled = works,
                                        modifier = Modifier.size(20.dp),
                                        colors = CheckboxDefaults.colors(
                                            checkedColor = Color(0xFF00BCD4),
                                            uncheckedColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.4f)
                                        )
                                    )
                                    Spacer(Modifier.width(8.dp))
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            modelId,
                                            fontSize = 11.sp,
                                            color = if (works) MaterialTheme.colorScheme.onSurface
                                            else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f),
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                        if (errorMsg != null) {
                                            Text(
                                                errorMsg.take(60),
                                                fontSize = 9.sp,
                                                color = Color(0xFFEF5350).copy(alpha = 0.6f),
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                        }
                                    }
                                    // Status badge
                                    Surface(
                                        shape = RoundedCornerShape(4.dp),
                                        color = if (works) Color(0xFF4CAF50).copy(alpha = 0.15f)
                                        else Color(0xFFEF5350).copy(alpha = 0.15f)
                                    ) {
                                        Text(
                                            if (works) "OK" else "FAIL",
                                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                            fontSize = 9.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = if (works) Color(0xFF4CAF50) else Color(0xFFEF5350)
                                        )
                                    }
                                    Spacer(Modifier.width(4.dp))
                                    // Retest button
                                    Surface(
                                        onClick = { onRetestModel(modelId) },
                                        shape = RoundedCornerShape(4.dp),
                                        color = Color(0xFFFF8F00).copy(alpha = 0.12f)
                                    ) {
                                        Box(modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)) {
                                            if (isRetesting) {
                                                CircularProgressIndicator(
                                                    modifier = Modifier.size(10.dp),
                                                    strokeWidth = 1.5.dp,
                                                    color = Color(0xFFFF8F00)
                                                )
                                            } else {
                                                Text(
                                                    "Test",
                                                    fontSize = 9.sp,
                                                    fontWeight = FontWeight.Bold,
                                                    color = Color(0xFFFF8F00)
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // ── Google Search grounding toggle (Gemini only) ────────────────
            if (entry.safeProvider == "gemini") {
                Spacer(Modifier.height(8.dp))
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = Color(0xFF4285F4).copy(alpha = 0.08f)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onToggleGoogleSearch() }
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("🔍", fontSize = 16.sp)
                        Spacer(Modifier.width(8.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                "Google Search Grounding",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                if (entry.safeGoogleSearch) "Replies include real-time web info"
                                else "Replies use training data only",
                                fontSize = 10.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(
                            checked = entry.safeGoogleSearch,
                            onCheckedChange = { onToggleGoogleSearch() },
                            modifier = Modifier.height(20.dp),
                            colors = SwitchDefaults.colors(
                                checkedTrackColor = Color(0xFF4285F4),
                                checkedThumbColor = Color.White
                            )
                        )
                    }
                }
            }

            // ── Selected models summary with Edit Selection button ──────────
            if (entry.safeSelectedModels.isNotEmpty() && entry.safeCheckedModels.isNotEmpty()
                && !isModelListExpanded && checkState?.isChecking != true) {
                Spacer(Modifier.height(6.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "Using: ${entry.safeSelectedModels.joinToString(", ")}",
                        fontSize = 10.sp,
                        color = Color(0xFF4CAF50).copy(alpha = 0.7f),
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    Spacer(Modifier.width(8.dp))
                    Surface(
                        onClick = onToggleModelList,
                        shape = RoundedCornerShape(6.dp),
                        color = Color(0xFF00BCD4).copy(alpha = 0.12f)
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(Icons.Default.Edit, null,
                                modifier = Modifier.size(12.dp),
                                tint = Color(0xFF00BCD4))
                            Spacer(Modifier.width(4.dp))
                            Text(
                                "Edit Selection",
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color(0xFF00BCD4)
                            )
                        }
                    }
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

    // Model picker state — pre-fill from existing entry if available
    var availableModels       by remember { mutableStateOf(existing?.safeAvailableModels ?: emptyList()) }
    // selectedModel: always pre-fill from existing; LaunchedEffect below resets it if provider changes
    var selectedModel         by remember { mutableStateOf(existing?.safePreferredModel ?: "") }
    var modelPickerExpanded   by remember { mutableStateOf(false) }

    // OpenRouter browse-models sheet
    var showBrowseModels         by remember { mutableStateOf(false) }
    var browseLoading            by remember { mutableStateOf(false) }
    var openRouterCatalog        by remember { mutableStateOf<List<LlmRouter.OpenRouterModel>>(emptyList()) }
    var browseSearch             by remember { mutableStateOf("") }

    // Dialog model testing state
    var dialogCheckedModels by remember { mutableStateOf(
        existing?.safeSelectedModels?.toSet() ?: emptySet()
    ) }
    var dialogTestResults by remember { mutableStateOf(
        existing?.safeCheckedModels ?: emptyMap<String, String?>()
    ) }
    var isTestingAll by remember { mutableStateOf(false) }
    var testingAllProgress by remember { mutableStateOf(0) }
    var testingAllTotal by remember { mutableStateOf(0) }
    var singleTestingModel by remember { mutableStateOf("") }

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
        dialogCheckedModels = emptySet()
        dialogTestResults = emptyMap()
        if (existing?.safeProvider != provider) {
            selectedModel = ""
        }
    }
    LaunchedEffect(apiKey, baseUrl) {
        if (apiKey.isNotBlank() || baseUrl.isNotBlank()) {
            validation = ValidationUi()
            availableModels = emptyList()
            dialogCheckedModels = emptySet()
            dialogTestResults = emptyMap()
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
                            LlmProvider.entries.filter { it.id != "offline" }.forEach { p ->
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

                    // ── Load Models button — fetch models without testing ────────
                    var isLoadingModels by remember { mutableStateOf(false) }

                    AnimatedVisibility(visible = provider != "offline" &&
                        (provider == "ollama" || apiKey.isNotBlank()) &&
                        availableModels.isEmpty() && !isLoadingModels) {
                        OutlinedButton(
                            onClick = {
                                scope.launch {
                                    isLoadingModels = true
                                    validation = ValidationUi(ValidationState.LOADING, "Fetching models…")
                                    val router = LlmRouter.getInstance(context)
                                    val entry = ApiKeyEntry(
                                        label = "fetch",
                                        provider = provider,
                                        apiKey = if (provider == "ollama") "local" else apiKey.trim(),
                                        baseUrl = baseUrl.trim()
                                    )
                                    try {
                                        val models = router.fetchModelsOnly(entry)
                                        if (models.isNotEmpty()) {
                                            availableModels = models
                                            dialogCheckedModels = emptySet()
                                            dialogTestResults = emptyMap()
                                            validation = ValidationUi(ValidationState.SUCCESS,
                                                "✅ Found ${models.size} models. Test them or select and save.")
                                        } else {
                                            validation = ValidationUi(ValidationState.ERROR,
                                                "❌ No models found. Check your API key.")
                                        }
                                    } catch (e: Exception) {
                                        validation = ValidationUi(ValidationState.ERROR,
                                            "❌ ${e.message}")
                                    }
                                    isLoadingModels = false
                                }
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(Icons.Default.CloudDownload, null, modifier = Modifier.size(14.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Load Models", fontSize = 12.sp)
                        }
                    }

                    // Loading indicator
                    AnimatedVisibility(visible = isLoadingModels) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                            horizontalArrangement = Arrangement.Center,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            CircularProgressIndicator(modifier = Modifier.size(16.dp),
                                strokeWidth = 2.dp)
                            Spacer(Modifier.width(8.dp))
                            Text("Fetching models…", fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }

                    // ── Model list with checkboxes + testing ──────────────────
                    AnimatedVisibility(visible = availableModels.isNotEmpty()) {
                        val accentCol = providerColor(provider)
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            // Header row: model count + Test All Models button
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    "${availableModels.size} models",
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.SemiBold
                                )

                                if (!isTestingAll) {
                                    Surface(
                                        onClick = {
                                            val models = availableModels.map { it.substringBefore(" (") }
                                                .filter { it.isNotBlank() }
                                            if (models.isEmpty()) return@Surface
                                            isTestingAll = true
                                            testingAllTotal = models.size
                                            testingAllProgress = 0
                                            scope.launch {
                                                val results = mutableMapOf<String, String?>()
                                                val router = LlmRouter.getInstance(context)
                                                val testEntry = ApiKeyEntry(
                                                    label = "test", provider = provider,
                                                    apiKey = if (provider == "ollama") "local" else apiKey.trim(),
                                                    baseUrl = baseUrl.trim()
                                                )
                                                for ((idx, model) in models.withIndex()) {
                                                    testingAllProgress = idx
                                                    val result = router.testSingleModel(testEntry, model)
                                                    results[model] = if (result.isSuccess) null
                                                        else (result.exceptionOrNull()?.message?.take(80) ?: "Error")
                                                    dialogTestResults = results.toMap()
                                                    kotlinx.coroutines.delay(1500) // 1.5s between tests to avoid rate limits
                                                }
                                                testingAllProgress = models.size
                                                // Auto-check all passing models
                                                dialogCheckedModels = results.filter { it.value == null }.keys
                                                isTestingAll = false
                                                validation = ValidationUi(
                                                    ValidationState.SUCCESS,
                                                    "✅ ${dialogCheckedModels.size}/${models.size} models passed"
                                                )
                                            }
                                        },
                                        shape = RoundedCornerShape(8.dp),
                                        color = Color(0xFF00BCD4).copy(alpha = 0.12f)
                                    ) {
                                        Row(
                                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Icon(Icons.Default.PlayArrow, null,
                                                modifier = Modifier.size(14.dp),
                                                tint = Color(0xFF00BCD4))
                                            Spacer(Modifier.width(4.dp))
                                            Text(
                                                if (dialogTestResults.isNotEmpty()) "Re-test All" else "Test All Models",
                                                fontSize = 11.sp,
                                                fontWeight = FontWeight.Bold,
                                                color = Color(0xFF00BCD4)
                                            )
                                        }
                                    }
                                } else {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        CircularProgressIndicator(
                                            modifier = Modifier.size(14.dp),
                                            strokeWidth = 2.dp,
                                            color = Color(0xFF00BCD4)
                                        )
                                        Spacer(Modifier.width(6.dp))
                                        Text(
                                            "Testing $testingAllProgress/$testingAllTotal…",
                                            fontSize = 11.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = Color(0xFF00BCD4)
                                        )
                                    }
                                }
                            }

                            // Scrollable model list with checkboxes
                            Card(
                                modifier = Modifier.fillMaxWidth().heightIn(max = 250.dp),
                                shape = RoundedCornerShape(10.dp),
                                colors = CardDefaults.cardColors(
                                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
                                )
                            ) {
                                Column(
                                    modifier = Modifier
                                        .verticalScroll(rememberScrollState())
                                        .padding(8.dp)
                                ) {
                                    availableModels.forEach { model ->
                                        val clean = model.substringBefore(" (")
                                        val isChecked = clean in dialogCheckedModels
                                        val isTested = clean in dialogTestResults
                                        val testResult = dialogTestResults[clean]
                                        val isSingleTesting = singleTestingModel == clean

                                        Row(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .clickable {
                                                    dialogCheckedModels = if (isChecked)
                                                        dialogCheckedModels - clean
                                                    else dialogCheckedModels + clean
                                                }
                                                .padding(vertical = 3.dp, horizontal = 4.dp),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Checkbox(
                                                checked = isChecked,
                                                onCheckedChange = {
                                                    dialogCheckedModels = if (isChecked)
                                                        dialogCheckedModels - clean
                                                    else dialogCheckedModels + clean
                                                },
                                                modifier = Modifier.size(20.dp),
                                                colors = CheckboxDefaults.colors(
                                                    checkedColor = accentCol,
                                                    uncheckedColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.4f)
                                                )
                                            )
                                            Spacer(Modifier.width(6.dp))
                                            Column(modifier = Modifier.weight(1f)) {
                                                Text(
                                                    clean, fontSize = 11.sp,
                                                    maxLines = 1,
                                                    overflow = TextOverflow.Ellipsis,
                                                    color = if (isTested && testResult != null)
                                                        MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f)
                                                    else MaterialTheme.colorScheme.onSurface
                                                )
                                                // Show error message for failed models
                                                if (isTested && testResult != null) {
                                                    Text(
                                                        testResult.take(60),
                                                        fontSize = 9.sp,
                                                        color = Color(0xFFEF5350).copy(alpha = 0.7f),
                                                        maxLines = 2,
                                                        overflow = TextOverflow.Ellipsis,
                                                        lineHeight = 12.sp
                                                    )
                                                }
                                            }

                                            // Status: spinner during test, OK/FAIL badge after
                                            if (isTestingAll && !isTested && clean == availableModels
                                                .map { it.substringBefore(" (") }
                                                .getOrNull(testingAllProgress)) {
                                                // Currently being tested
                                                CircularProgressIndicator(
                                                    modifier = Modifier.size(12.dp),
                                                    strokeWidth = 1.5.dp,
                                                    color = Color(0xFF00BCD4)
                                                )
                                                Spacer(Modifier.width(4.dp))
                                            } else if (isTested) {
                                                Surface(
                                                    shape = RoundedCornerShape(4.dp),
                                                    color = if (testResult == null)
                                                        Color(0xFF4CAF50).copy(alpha = 0.15f)
                                                    else Color(0xFFEF5350).copy(alpha = 0.15f)
                                                ) {
                                                    Text(
                                                        if (testResult == null) "OK" else "FAIL",
                                                        modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp),
                                                        fontSize = 9.sp,
                                                        fontWeight = FontWeight.Bold,
                                                        color = if (testResult == null) Color(0xFF4CAF50)
                                                                else Color(0xFFEF5350)
                                                    )
                                                }
                                                Spacer(Modifier.width(4.dp))
                                            }

                                            // Per-model Test button — shown when model is checked
                                            if (isChecked && !isTestingAll) {
                                                Surface(
                                                    onClick = {
                                                        singleTestingModel = clean
                                                        scope.launch {
                                                            val router = LlmRouter.getInstance(context)
                                                            val testEntry = ApiKeyEntry(
                                                                label = "test", provider = provider,
                                                                apiKey = if (provider == "ollama") "local" else apiKey.trim(),
                                                                baseUrl = baseUrl.trim()
                                                            )
                                                            val result = router.testSingleModel(testEntry, clean)
                                                            val newResults = dialogTestResults.toMutableMap()
                                                            newResults[clean] = if (result.isSuccess) null
                                                                else (result.exceptionOrNull()?.message?.take(80) ?: "Error")
                                                            dialogTestResults = newResults
                                                            // Single model test: select only this one, unselect all others
                                                            if (result.isSuccess) {
                                                                dialogCheckedModels = setOf(clean)
                                                            } else {
                                                                dialogCheckedModels = dialogCheckedModels - clean
                                                            }
                                                            singleTestingModel = ""
                                                        }
                                                    },
                                                    shape = RoundedCornerShape(4.dp),
                                                    color = Color(0xFFFF8F00).copy(alpha = 0.12f)
                                                ) {
                                                    Box(modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)) {
                                                        if (isSingleTesting) {
                                                            CircularProgressIndicator(
                                                                modifier = Modifier.size(10.dp),
                                                                strokeWidth = 1.5.dp,
                                                                color = Color(0xFFFF8F00)
                                                            )
                                                        } else {
                                                            Text("Test", fontSize = 9.sp,
                                                                fontWeight = FontWeight.Bold,
                                                                color = Color(0xFFFF8F00))
                                                        }
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                            }

                            // Selected count summary
                            if (dialogCheckedModels.isNotEmpty()) {
                                Text(
                                    "${dialogCheckedModels.size} model${if (dialogCheckedModels.size != 1) "s" else ""} selected",
                                    fontSize = 11.sp,
                                    color = Color(0xFF4CAF50),
                                    fontWeight = FontWeight.SemiBold
                                )
                            }
                        }
                    }

                    // Validation result
                    AnimatedVisibility(visible = validation.state != ValidationState.IDLE) {
                        ValidationCard(validation)
                    }

                    // Buttons row
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {

                        // SAVE
                        Button(
                            onClick = {
                                if (label.isBlank()) return@Button
                                val key = when {
                                    provider == "ollama" && apiKey.isBlank() -> "local"
                                    else -> apiKey
                                }
                                val base = existing ?: ApiKeyEntry(label = "", provider = "", apiKey = "")
                                val checkedList = dialogCheckedModels.toList()
                                onSave(base.copy(
                                    label          = label.trim(),
                                    provider       = provider,
                                    apiKey         = key.trim(),
                                    baseUrl        = baseUrl.trim().ifBlank { null },
                                    preferredModel = checkedList.firstOrNull()
                                        ?: selectedModel.ifBlank { null },
                                    availableModels = availableModels.ifEmpty { null },
                                    checkedModels  = dialogTestResults.ifEmpty { null },
                                    selectedModels = checkedList.ifEmpty { null }
                                ))
                            },
                            modifier = Modifier.weight(1f),
                            enabled = label.isNotBlank() &&
                                      (provider == "ollama" || apiKey.isNotBlank())
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
                // Add browsed model to available list if not already there
                if (!availableModels.any { it.substringBefore(" (") == modelId }) {
                    availableModels = availableModels + modelId
                }
                // Check this model in the list
                dialogCheckedModels = dialogCheckedModels + modelId
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
        Column(modifier = Modifier.padding(10.dp)) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.Top
            ) {
                if (v.state == ValidationState.LOADING) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp, color = tint)
                } else {
                    Icon(icon, null, modifier = Modifier.size(16.dp), tint = tint)
                }
                Text(v.message, fontSize = 12.sp, lineHeight = 17.sp,
                    color = MaterialTheme.colorScheme.onSurface)
            }
            // Show AI response from test prompt
            if (v.responseText.isNotBlank()) {
                Spacer(Modifier.height(8.dp))
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)
                ) {
                    Column(modifier = Modifier.padding(10.dp)) {
                        Text("AI Response:", fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.height(3.dp))
                        Text(v.responseText, fontSize = 12.sp, lineHeight = 17.sp,
                            color = tint, fontWeight = FontWeight.Medium)
                    }
                }
            }
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
    "grok"       -> Color(0xFF1DA1F2)
    "ollama"     -> Color(0xFF607D8B)
    "offline"    -> Color(0xFF795548)
    else         -> Color(0xFF9E9E9E)
}

/**
 * Cheapest / fastest sensible model for a quick "is this key alive?" check when
 * the user hasn't picked a preferred model yet. Used by the card-level Test
 * Connection button so users can verify a key works before committing to a
 * specific model.
 */
fun defaultTestModelFor(provider: String): String = when (provider) {
    "openai"     -> "gpt-4o-mini"
    "anthropic"  -> "claude-haiku-4-5-20251001"
    "openrouter" -> "openai/gpt-4o-mini"
    "grok"       -> "grok-3-mini"
    "gemini"     -> "gemini-2.5-flash"
    "ollama"     -> ""   // local, model is implied by what's running
    else         -> ""
}


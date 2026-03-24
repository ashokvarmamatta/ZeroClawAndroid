package ai.zeroclaw.android.data

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.os.Environment
import ai.zeroclaw.android.service.ZeroClawService
import com.google.mediapipe.tasks.genai.llminference.LlmInference
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

/**
 * Manages offline .bin model files — scanning, copying, loading via MediaPipe LlmInference.
 * Singleton so the loaded model persists across calls while the service is running.
 */
class OfflineModelManager private constructor(private val context: Context) {

    companion object {
        private const val MAX_TOKENS = 1024
        private const val MODELS_DIR = "offline_models"

        @Volatile private var INSTANCE: OfflineModelManager? = null
        fun getInstance(context: Context): OfflineModelManager {
            return INSTANCE ?: synchronized(this) {
                OfflineModelManager(context.applicationContext).also { INSTANCE = it }
            }
        }
    }

    data class ModelFile(
        val name: String,
        val path: String,
        val sizeBytes: Long,
        val isInAppStorage: Boolean
    ) {
        val sizeMB: String get() {
            val mb = sizeBytes / (1024.0 * 1024.0)
            return if (mb >= 1024) "%.1f GB".format(mb / 1024.0)
            else "%.0f MB".format(mb)
        }
    }

    // Mutex prevents concurrent JNI access — MediaPipe LlmInference is NOT thread-safe
    // and will crash with "invalid global reference" if called from multiple coroutines
    private val inferenceMutex = Mutex()

    private var llmInference: LlmInference? = null
    private var loadedModelPath: String? = null

    private val _loadedModelName = MutableStateFlow<String?>(null)
    val loadedModelName: StateFlow<String?> = _loadedModelName.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    /** Directory inside app's internal storage for copied models */
    private fun modelsDir(): File {
        val dir = File(context.filesDir, MODELS_DIR)
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    /** List models already saved in app internal storage */
    fun listAppModels(): List<ModelFile> {
        val dir = modelsDir()
        return dir.listFiles()
            ?.filter { it.isFile && it.extension.equals("bin", ignoreCase = true) && it.length() > 0 }
            ?.map { ModelFile(it.name, it.absolutePath, it.length(), isInAppStorage = true) }
            ?.sortedBy { it.name.lowercase() }
            ?: emptyList()
    }

    /**
     * Import a model file from a SAF content URI into app storage.
     * No storage permission needed — the user picks the file via system file picker.
     * @return the imported ModelFile in app storage
     */
    suspend fun importModel(uri: Uri, fileName: String): ModelFile =
        withContext(Dispatchers.IO) {
            val destFile = File(modelsDir(), fileName)
            ZeroClawService.log("Offline: importing $fileName to app storage…")

            context.contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(destFile).use { output ->
                    val buffer = ByteArray(8192)
                    var bytesRead: Int
                    while (input.read(buffer).also { bytesRead = it } != -1) {
                        output.write(buffer, 0, bytesRead)
                    }
                }
            } ?: throw Exception("Cannot read file: $fileName")

            ZeroClawService.log("Offline: $fileName imported (${destFile.length() / (1024 * 1024)} MB)")
            ModelFile(destFile.name, destFile.absolutePath, destFile.length(), isInAppStorage = true)
        }

    /** Delete a model from app internal storage */
    fun deleteAppModel(name: String): Boolean {
        val file = File(modelsDir(), name)
        val deleted = file.delete()
        if (deleted && loadedModelPath == file.absolutePath) {
            destroyEngine()
        }
        return deleted
    }

    /**
     * Try to resolve a real file path from a content URI.
     * Works for files picked from Downloads via most file managers.
     * Returns null if the path cannot be resolved.
     */
    fun resolveFilePath(uri: Uri): String? {
        try {
            // Handle document URIs (content://com.android.providers.downloads.documents/...)
            if (DocumentsContract.isDocumentUri(context, uri)) {
                val docId = DocumentsContract.getDocumentId(uri)
                val authority = uri.authority ?: ""

                // Downloads provider
                if (authority == "com.android.providers.downloads.documents") {
                    // docId might be "raw:/storage/emulated/0/Download/model.bin"
                    if (docId.startsWith("raw:")) {
                        return docId.removePrefix("raw:")
                    }
                }

                // External storage provider (e.g. "primary:Download/model.bin")
                if (authority == "com.android.externalstorage.documents") {
                    val parts = docId.split(":")
                    if (parts.size == 2 && parts[0] == "primary") {
                        return "${Environment.getExternalStorageDirectory()}/${parts[1]}"
                    }
                }
            }

            // Try file:// scheme
            if (uri.scheme == "file") {
                return uri.path
            }
        } catch (e: Exception) {
            ZeroClawService.log("Offline: could not resolve path from URI — ${e.message}")
        }
        return null
    }

    /** Initialize the LlmInference engine with the given model file */
    suspend fun loadModel(modelPath: String): Result<Unit> = inferenceMutex.withLock { withContext(Dispatchers.IO) {
        try {
            _isLoading.value = true
            // Destroy previous engine if different model
            if (loadedModelPath != modelPath) {
                destroyEngine()
            }
            if (llmInference != null) {
                _isLoading.value = false
                return@withContext Result.success(Unit) // already loaded
            }

            val file = File(modelPath)
            if (!file.exists()) {
                _isLoading.value = false
                return@withContext Result.failure(Exception("Model file not found: ${file.name}"))
            }

            ZeroClawService.log("Offline: loading model ${file.name}…")
            val options = LlmInference.LlmInferenceOptions.builder()
                .setModelPath(modelPath)
                .setMaxTokens(MAX_TOKENS)
                .build()
            llmInference = LlmInference.createFromOptions(context, options)
            loadedModelPath = modelPath
            _loadedModelName.value = file.name
            ZeroClawService.log("Offline: ✓ model ${file.name} loaded")
            _isLoading.value = false
            Result.success(Unit)
        } catch (e: Exception) {
            _isLoading.value = false
            ZeroClawService.log("Offline: ✗ failed to load model — ${e.message}")
            Result.failure(e)
        }
    } }

    /** Generate a response from the loaded model */
    suspend fun generateResponse(prompt: String): String = inferenceMutex.withLock {
        withContext(Dispatchers.IO) {
            val inference = llmInference
                ?: throw Exception("No offline model loaded — select one in Settings → API Keys")

            // Guard against JNI crash: MediaPipe aborts the process if input > maxTokens.
            // Rough estimate: 3 chars per token (conservative for English). Keep 80 % of budget
            // for the prompt, leaving 20 % headroom for the output tokens.
            val maxSafeChars = (MAX_TOKENS * 3 * 0.80).toInt()   // ≈ 2457 chars
            val safePrompt = if (prompt.length > maxSafeChars) {
                ZeroClawService.log("Offline: prompt truncated ${prompt.length} → $maxSafeChars chars (model limit)")
                prompt.take(maxSafeChars)
            } else prompt

            try {
                inference.generateResponse(safePrompt)
            } catch (e: Exception) {
                ZeroClawService.log("Offline: generation error — ${e.message}")
                // If JNI state is corrupted, destroy and let next call reload
                if (e.message?.contains("JNI") == true || e.message?.contains("global reference") == true) {
                    ZeroClawService.log("Offline: JNI error detected — destroying engine to recover")
                    destroyEngine()
                }
                throw e
            }
        }
    }

    /** Check if a model is currently loaded and ready */
    fun isModelLoaded(): Boolean = llmInference != null

    /** Get the currently loaded model path */
    fun getLoadedModelPath(): String? = loadedModelPath

    /** Release the model and free memory */
    fun destroyEngine() {
        try {
            llmInference?.close()
        } catch (_: Exception) {}
        llmInference = null
        loadedModelPath = null
        _loadedModelName.value = null
        ZeroClawService.log("Offline: engine destroyed")
    }
}

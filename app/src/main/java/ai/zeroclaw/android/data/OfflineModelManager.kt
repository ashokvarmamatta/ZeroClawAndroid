package ai.zeroclaw.android.data

import android.content.Context
import android.os.Environment
import ai.zeroclaw.android.service.ZeroClawService
import com.google.mediapipe.tasks.genai.llminference.LlmInference
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
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

    /** Scan Downloads folder for .bin model files */
    fun scanDownloads(): List<ModelFile> {
        val downloads = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        if (!downloads.exists() || !downloads.canRead()) return emptyList()
        return downloads.listFiles()
            ?.filter { it.isFile && it.extension.equals("bin", ignoreCase = true) && it.length() > 0 }
            ?.map { ModelFile(it.name, it.absolutePath, it.length(), isInAppStorage = false) }
            ?.sortedBy { it.name.lowercase() }
            ?: emptyList()
    }

    /** List models already copied to app internal storage */
    fun listAppModels(): List<ModelFile> {
        val dir = modelsDir()
        return dir.listFiles()
            ?.filter { it.isFile && it.extension.equals("bin", ignoreCase = true) && it.length() > 0 }
            ?.map { ModelFile(it.name, it.absolutePath, it.length(), isInAppStorage = true) }
            ?.sortedBy { it.name.lowercase() }
            ?: emptyList()
    }

    /** All available models (app storage + Downloads) */
    fun allModels(): List<ModelFile> {
        val appModels = listAppModels()
        val downloadModels = scanDownloads()
        // Don't show download models that are already copied
        val appNames = appModels.map { it.name }.toSet()
        val uniqueDownloads = downloadModels.filter { it.name !in appNames }
        return appModels + uniqueDownloads
    }

    /**
     * Copy a model file from Downloads to app internal storage.
     * @param deleteSource if true, delete the original file from Downloads after successful copy.
     * @return the new ModelFile in app storage
     */
    suspend fun copyToAppStorage(source: ModelFile, deleteSource: Boolean = false): ModelFile =
        withContext(Dispatchers.IO) {
            val destFile = File(modelsDir(), source.name)
            ZeroClawService.log("Offline: copying ${source.name} (${source.sizeMB}) to app storage…")

            FileInputStream(File(source.path)).use { input ->
                FileOutputStream(destFile).use { output ->
                    val buffer = ByteArray(8192)
                    var bytesRead: Int
                    while (input.read(buffer).also { bytesRead = it } != -1) {
                        output.write(buffer, 0, bytesRead)
                    }
                }
            }

            if (deleteSource) {
                val deleted = File(source.path).delete()
                ZeroClawService.log("Offline: source ${if (deleted) "deleted" else "NOT deleted"} from Downloads")
            }

            ZeroClawService.log("Offline: ${source.name} copied to app storage")
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

    /** Initialize the LlmInference engine with the given model file */
    suspend fun loadModel(modelPath: String): Result<Unit> = withContext(Dispatchers.IO) {
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
    }

    /** Generate a response from the loaded model */
    suspend fun generateResponse(prompt: String): String = withContext(Dispatchers.IO) {
        val inference = llmInference
            ?: throw Exception("No offline model loaded — select one in Settings → API Keys")
        try {
            inference.generateResponse(prompt)
        } catch (e: Exception) {
            ZeroClawService.log("Offline: generation error — ${e.message}")
            throw e
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

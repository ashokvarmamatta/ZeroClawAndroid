package ai.zeroclaw.android.data

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.os.Environment
import ai.zeroclaw.android.service.ZeroClawService
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.Conversation
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.Message
import com.google.ai.edge.litertlm.MessageCallback
import com.google.ai.edge.litertlm.SamplerConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Manages offline model files — scanning, copying, loading via LiteRT LM Engine+Conversation.
 * Supports both legacy .bin (MediaPipe) and new .litertlm model formats.
 * Singleton so the loaded model persists across calls while the service is running.
 *
 * Phase 178: Migrated from MediaPipe LlmInference to LiteRT LM SDK.
 * Key improvements: streaming, 32K context, thinking mode, image/audio input, GPU/NPU backends.
 */
class OfflineModelManager private constructor(private val context: Context) {

    companion object {
        private const val DEFAULT_MAX_TOKENS = 4096
        private const val MODELS_DIR = "offline_models"
        /** Supported model file extensions */
        private val MODEL_EXTENSIONS = setOf("bin", "litertlm", "task")

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
        /** True if this is a LiteRT LM format model (not legacy MediaPipe .bin) */
        val isLiteRtFormat: Boolean get() {
            val ext = name.substringAfterLast('.', "").lowercase()
            return ext == "litertlm" || ext == "task"
        }
    }

    /** Holds the LiteRT LM engine and conversation state for the loaded model */
    private class LlmModelInstance(
        val engine: Engine,
        var conversation: Conversation
    )

    private var modelInstance: LlmModelInstance? = null
    private var loadedModelPath: String? = null
    private var currentMaxTokens: Int = DEFAULT_MAX_TOKENS

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

    /** List models already saved in app internal storage (.bin, .litertlm, .task) */
    fun listAppModels(): List<ModelFile> {
        val dir = modelsDir()
        return dir.listFiles()
            ?.filter { f ->
                f.isFile && f.length() > 0 &&
                MODEL_EXTENSIONS.contains(f.extension.lowercase())
            }
            ?.map { ModelFile(it.name, it.absolutePath, it.length(), isInAppStorage = true) }
            ?.sortedBy { it.name.lowercase() }
            ?: emptyList()
    }

    /**
     * Import a model file from a SAF content URI into app storage.
     * No storage permission needed — the user picks the file via system file picker.
     */
    suspend fun importModel(uri: Uri, fileName: String): ModelFile =
        withContext(Dispatchers.IO) {
            val destFile = File(modelsDir(), fileName)
            ZeroClawService.log("Offline: importing $fileName to app storage...")

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
     */
    fun resolveFilePath(uri: Uri): String? {
        try {
            if (DocumentsContract.isDocumentUri(context, uri)) {
                val docId = DocumentsContract.getDocumentId(uri)
                val authority = uri.authority ?: ""
                if (authority == "com.android.providers.downloads.documents") {
                    if (docId.startsWith("raw:")) return docId.removePrefix("raw:")
                }
                if (authority == "com.android.externalstorage.documents") {
                    val parts = docId.split(":")
                    if (parts.size == 2 && parts[0] == "primary") {
                        return "${Environment.getExternalStorageDirectory()}/${parts[1]}"
                    }
                }
            }
            if (uri.scheme == "file") return uri.path
        } catch (e: Exception) {
            ZeroClawService.log("Offline: could not resolve path from URI - ${e.message}")
        }
        return null
    }

    /**
     * Initialize the LiteRT LM Engine with the given model file.
     * Creates Engine + Conversation. Supports GPU acceleration.
     *
     * @param modelPath absolute path to model file (.bin, .litertlm, .task)
     * @param maxTokens max tokens for this model (default 4096)
     * @param useGpu whether to try GPU backend (WARNING: GPU loads entire model into VRAM,
     *               causing SIGSEGV on devices with < 12GB RAM for large models like Gemma 4.
     *               Default is CPU which is safer. GPU is only recommended for small models
     *               or high-end devices with 12GB+ RAM.)
     * @param systemInstruction optional system prompt baked into the conversation
     */
    suspend fun loadModel(
        modelPath: String,
        maxTokens: Int = DEFAULT_MAX_TOKENS,
        useGpu: Boolean = false,
        systemInstruction: String? = null
    ): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            _isLoading.value = true

            // Destroy previous engine if different model
            if (loadedModelPath != modelPath) {
                destroyEngine()
            }
            if (modelInstance != null) {
                _isLoading.value = false
                return@withContext Result.success(Unit) // already loaded
            }

            val file = File(modelPath)
            if (!file.exists()) {
                _isLoading.value = false
                return@withContext Result.failure(Exception("Model file not found: ${file.name}"))
            }

            // Default to CPU — GPU loads entire model into VRAM which causes
            // SIGSEGV (Fatal signal 11) on most devices when the model is > 1GB.
            // The RenderThread also uses GPU, so competing for VRAM crashes the app.
            val backendLabel = if (useGpu) "GPU" else "CPU"
            ZeroClawService.log("Offline: loading ${file.name} (LiteRT LM, $backendLabel, maxTokens=$maxTokens)...")

            val backend = if (useGpu) Backend.GPU() else Backend.CPU()

            val engineConfig = EngineConfig(
                modelPath = modelPath,
                backend = backend,
                maxNumTokens = maxTokens
            )

            val engine = Engine(engineConfig)
            engine.initialize()

            // Create conversation with sampling config
            val samplerConfig = SamplerConfig(
                topK = 64,
                topP = 0.95,
                temperature = 1.0
            )

            val convConfig = if (systemInstruction != null) {
                ConversationConfig(
                    samplerConfig = samplerConfig,
                    systemInstruction = Contents.of(listOf(Content.Text(systemInstruction)))
                )
            } else {
                ConversationConfig(samplerConfig = samplerConfig)
            }

            val conversation = engine.createConversation(convConfig)

            modelInstance = LlmModelInstance(engine, conversation)
            loadedModelPath = modelPath
            currentMaxTokens = maxTokens
            _loadedModelName.value = file.name
            ZeroClawService.log("Offline: model ${file.name} loaded (LiteRT LM, maxTokens=$maxTokens)")
            _isLoading.value = false
            Result.success(Unit)
        } catch (e: Exception) {
            _isLoading.value = false
            ZeroClawService.log("Offline: failed to load model - ${e.message}")
            destroyEngine()
            Result.failure(e)
        }
    }

    /**
     * Reset the conversation (clear history) without reloading the model.
     * The Engine stays in memory; only the Conversation is recreated.
     */
    fun resetConversation(systemInstruction: String? = null) {
        val instance = modelInstance ?: return
        try {
            instance.conversation.close()
        } catch (_: Exception) {}

        val samplerConfig = SamplerConfig(topK = 64, topP = 0.95, temperature = 1.0)
        val convConfig = if (systemInstruction != null) {
            ConversationConfig(
                samplerConfig = samplerConfig,
                systemInstruction = Contents.of(listOf(Content.Text(systemInstruction)))
            )
        } else {
            ConversationConfig(samplerConfig = samplerConfig)
        }

        instance.conversation = instance.engine.createConversation(convConfig)
        ZeroClawService.log("Offline: conversation reset")
    }

    /**
     * Generate a response using streaming (LiteRT LM async API).
     * Returns the complete response as a string.
     * The Conversation object maintains multi-turn history automatically.
     *
     * @param input text prompt
     * @param onPartialResult optional callback for streaming tokens
     * @param enableThinking whether to request thinking/chain-of-thought output
     * @return complete generated response text
     */
    suspend fun generateResponse(
        input: String,
        onPartialResult: ((partialText: String, thinkingText: String?) -> Unit)? = null,
        enableThinking: Boolean = false
    ): String = withContext(Dispatchers.IO) {
        val instance = modelInstance
            ?: throw Exception("No offline model loaded - select one in Settings -> API Keys")

        ZeroClawService.log("Offline: generating response (${input.length} chars input)")

        val contents = Contents.of(listOf(Content.Text(input)))

        val extraContext: Map<String, Any> = if (enableThinking) {
            mapOf("enable_thinking" to "true")
        } else emptyMap()

        suspendCancellableCoroutine { cont ->
            val fullResponse = StringBuilder()
            val fullThinking = StringBuilder()

            val callback = object : MessageCallback {
                override fun onMessage(message: Message) {
                    val text = message.toString()
                    if (text.isNotEmpty()) {
                        fullResponse.append(text)
                    }
                    val thought = try { message.channels["thought"]?.toString() } catch (_: Exception) { null }
                    if (!thought.isNullOrEmpty()) {
                        fullThinking.append(thought)
                    }
                    onPartialResult?.invoke(
                        text,
                        if (thought.isNullOrEmpty()) null else thought
                    )
                }

                override fun onDone() {
                    if (cont.isActive) {
                        cont.resume(fullResponse.toString())
                    }
                }

                override fun onError(throwable: Throwable) {
                    if (cont.isActive) {
                        if (throwable is kotlinx.coroutines.CancellationException) {
                            // Cancelled by user — return what we have so far
                            cont.resume(fullResponse.toString())
                        } else {
                            ZeroClawService.log("Offline: generation error - ${throwable.message}")
                            cont.resumeWithException(throwable)
                        }
                    }
                }
            }

            try {
                instance.conversation.sendMessageAsync(contents, callback, extraContext)
            } catch (e: Exception) {
                if (cont.isActive) {
                    ZeroClawService.log("Offline: sendMessageAsync failed - ${e.message}")
                    cont.resumeWithException(e)
                }
            }

            cont.invokeOnCancellation {
                try {
                    instance.conversation.cancelProcess()
                } catch (_: Exception) {}
            }
        }
    }

    /**
     * Legacy blocking generateResponse for backward compatibility.
     * Calls the streaming version internally but waits for completion.
     */
    suspend fun generateResponseBlocking(prompt: String): String {
        return generateResponse(prompt)
    }

    /** Stop any in-progress generation */
    fun stopGeneration() {
        try {
            modelInstance?.conversation?.cancelProcess()
        } catch (_: Exception) {}
    }

    /** Check if a model is currently loaded and ready */
    fun isModelLoaded(): Boolean = modelInstance != null

    /** Get the currently loaded model path */
    fun getLoadedModelPath(): String? = loadedModelPath

    /** Get the max tokens configured for the current model */
    fun getMaxTokens(): Int = currentMaxTokens

    /** Release the model and free memory */
    fun destroyEngine() {
        try {
            modelInstance?.conversation?.close()
        } catch (_: Exception) {}
        try {
            modelInstance?.engine?.close()
        } catch (_: Exception) {}
        modelInstance = null
        loadedModelPath = null
        _loadedModelName.value = null
        ZeroClawService.log("Offline: engine destroyed")
    }
}

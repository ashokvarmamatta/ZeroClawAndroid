package ai.zeroclaw.android.data

/**
 * Phase 179: Curated catalog of on-device LLM models available for download from HuggingFace.
 * Inspired by Google AI Edge Gallery's model allowlist system.
 */

data class CatalogModel(
    val id: String,
    val name: String,
    val description: String,
    val sizeBytes: Long,
    val fileName: String,
    val huggingFaceRepo: String,
    val commitHash: String,
    val supportsImage: Boolean = false,
    val supportsAudio: Boolean = false,
    val supportsThinking: Boolean = false,
    val maxContext: Int = 4096,
    val minRamGb: Int = 6,
    val badge: String? = null,          // "NEW", "BEST", "TINY" etc.
    val accelerators: String = "gpu,cpu",
    // Default inference config (Edge Gallery style)
    val defaultTopK: Int = 64,
    val defaultTopP: Float = 0.95f,
    val defaultTemperature: Float = 1.0f,
    val defaultMaxTokens: Int = 4096
) {
    val downloadUrl: String
        get() = "https://huggingface.co/$huggingFaceRepo/resolve/$commitHash/$fileName?download=true"

    val learnMoreUrl: String
        get() = "https://huggingface.co/$huggingFaceRepo"

    val sizeLabel: String get() {
        val mb = sizeBytes / (1024.0 * 1024.0)
        return if (mb >= 1024) "%.1f GB".format(mb / 1024.0) else "%.0f MB".format(mb)
    }

    val featureTags: List<String> get() = buildList {
        if (supportsThinking) add("Thinking")
        if (supportsImage) add("Vision")
        if (supportsAudio) add("Audio")
        add("${maxContext / 1024}K ctx")
        add("${minRamGb}GB+ RAM")
    }
}

/** Download state for a catalog model */
enum class ModelDownloadState {
    NOT_DOWNLOADED,
    DOWNLOADING,
    DOWNLOADED,
    FAILED
}

data class ModelDownloadProgress(
    val state: ModelDownloadState = ModelDownloadState.NOT_DOWNLOADED,
    val progress: Float = 0f,           // 0.0 to 1.0
    val bytesDownloaded: Long = 0L,
    val speedBytesPerSec: Long = 0L,
    val error: String? = null
)

/**
 * The curated model catalog.
 * URLs and commit hashes sourced from google-ai-edge/gallery model_allowlists/1_0_11.json
 */
object ModelCatalog {

    val models: List<CatalogModel> = listOf(
        // ── Gemma 4 (latest, best) ──────────────────────────────
        CatalogModel(
            id = "gemma-4-e2b",
            name = "Gemma 4 E2B",
            description = "Google's latest. Vision, audio, thinking mode. Best all-rounder.",
            sizeBytes = 2_583_085_056L,
            fileName = "gemma-4-E2B-it.litertlm",
            huggingFaceRepo = "litert-community/gemma-4-E2B-it-litert-lm",
            commitHash = "main",
            supportsImage = true,
            supportsAudio = true,
            supportsThinking = true,
            maxContext = 32768,
            minRamGb = 8,
            badge = "BEST"
        ),
        CatalogModel(
            id = "gemma-4-e4b",
            name = "Gemma 4 E4B",
            description = "Larger Gemma 4. Smarter but needs 12GB RAM.",
            sizeBytes = 3_654_467_584L,
            fileName = "gemma-4-E4B-it.litertlm",
            huggingFaceRepo = "litert-community/gemma-4-E4B-it-litert-lm",
            commitHash = "main",
            supportsImage = true,
            supportsAudio = true,
            supportsThinking = true,
            maxContext = 32768,
            minRamGb = 12,
            badge = "PRO"
        ),

        // ── Gemma 3n (previous gen, solid) ──────────────────────
        CatalogModel(
            id = "gemma-3n-e2b",
            name = "Gemma 3n E2B",
            description = "Vision + audio. Reliable previous-gen model.",
            sizeBytes = 3_655_827_456L,
            fileName = "gemma-3n-E2B-it-int4.litertlm",
            huggingFaceRepo = "google/gemma-3n-E2B-it-litert-lm",
            commitHash = "main",
            supportsImage = true,
            supportsAudio = true,
            maxContext = 4096,
            minRamGb = 8
        ),

        // ── Small text-only models ──────────────────────────────
        CatalogModel(
            id = "gemma3-1b",
            name = "Gemma 3 1B",
            description = "Tiny & fast. Text only. Great for low-end phones.",
            sizeBytes = 584_417_280L,
            fileName = "gemma3-1b-it-int4.litertlm",
            huggingFaceRepo = "litert-community/Gemma3-1B-IT",
            commitHash = "main",
            maxContext = 1024,
            minRamGb = 6,
            badge = "TINY"
        ),
        CatalogModel(
            id = "deepseek-r1-1.5b",
            name = "DeepSeek R1 1.5B",
            description = "DeepSeek reasoning model, text only.",
            sizeBytes = 1_833_451_520L,
            fileName = "DeepSeek-R1-Distill-Qwen-1.5B_multi-prefill-seq_q8_ekv4096.litertlm",
            huggingFaceRepo = "litert-community/DeepSeek-R1-Distill-Qwen-1.5B",
            commitHash = "main",
            maxContext = 4096,
            minRamGb = 6,
            accelerators = "cpu"
        )
    )

    fun findById(id: String): CatalogModel? = models.firstOrNull { it.id == id }
}

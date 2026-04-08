package ai.zeroclaw.android.data

import java.util.UUID

/**
 * A single LLM API key entry.
 * The user gives each key a friendly label, picks the provider,
 * and pastes the raw key string. Multiple entries can share the same
 * provider — e.g. two OpenAI keys from different accounts.
 */
data class ApiKeyEntry(
    val id: String = UUID.randomUUID().toString(),
    val label: String,
    val provider: String,
    val apiKey: String,
    val enabled: Boolean = true,
    val preferredModel: String? = null,  // nullable — old Gson entries won't have this field
    val baseUrl: String? = null,         // optional custom base URL (e.g. Modal, local proxy)
    val availableModels: List<String>? = null,       // all models fetched from provider
    val checkedModels: Map<String, String?>? = null, // modelId → null=pass, "error"=fail
    val selectedModels: List<String>? = null,         // user-chosen subset of working models
    val googleSearch: Boolean? = null                  // Gemini: enable Google Search grounding
) {
    // Safe accessors — Gson may deserialize missing fields as null even on non-null Kotlin types
    val safeLabel: String get() = label ?: ""
    val safeProvider: String get() = provider ?: ""
    val safeApiKey: String get() = apiKey ?: ""
    val safePreferredModel: String get() = preferredModel ?: ""
    val safeBaseUrl: String get() = baseUrl ?: ""
    val safeAvailableModels: List<String> get() = availableModels ?: emptyList()
    val safeCheckedModels: Map<String, String?> get() = checkedModels ?: emptyMap()
    val safeSelectedModels: List<String> get() = selectedModels ?: emptyList()
    val workingModels: List<String> get() = safeCheckedModels.filter { it.value == null }.keys.toList()
    val safeGoogleSearch: Boolean get() = googleSearch ?: false
}

/** All providers the app supports, with display metadata. */
enum class LlmProvider(
    val id: String,
    val displayName: String,
    val emoji: String,
    val keyPlaceholder: String,
    val keyHint: String
) {
    OPENAI(
        "openai", "OpenAI", "🟢",
        "sk-proj-...",
        "Get from: platform.openai.com → API Keys"
    ),
    ANTHROPIC(
        "anthropic", "Anthropic Claude", "🟠",
        "sk-ant-api03-...",
        "Get from: console.anthropic.com → API Keys"
    ),
    GEMINI(
        "gemini", "Google Gemini", "🔵",
        "AIzaSy...",
        "Get from: aistudio.google.com → Get API Key"
    ),
    OPENROUTER(
        "openrouter", "OpenRouter (400+ models)", "🟣",
        "sk-or-v1-...",
        "Get from: openrouter.ai → Keys"
    ),
    OLLAMA(
        "ollama", "Ollama (local, no key)", "⚫",
        "no-key-needed",
        "Runs locally — enter any value or 'local'"
    ),
    OFFLINE(
        "offline", "Offline Model (on-device)", "📱",
        "no-key-needed",
        "Runs .litertlm/.bin models on-device via LiteRT LM — no internet needed"
    );

    companion object {
        fun fromId(id: String) = entries.firstOrNull { it.id == id } ?: OPENAI
        fun allIds() = entries.map { it.id }
        fun allDisplayNames() = entries.map { it.displayName }
    }
}

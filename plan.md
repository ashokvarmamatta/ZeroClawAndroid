# ZeroClawAndroid — Master Build Plan
> **Purpose:** If this chat crashes, open a new chat, share this file, and development continues from last completed step.
> **Project:** Android app that runs ZeroClaw AI agent, connects to Telegram & WhatsApp, deployable via Android Studio.
> **Location:** `H:\Antigravity\ZeroClawAndroid`

---

## 🗂️ Skills Used (see SKILL.md for details)
- Android Native (Kotlin + Jetpack Compose)
- Foreground Service (background daemon)
- Retrofit + OkHttp (HTTP client for ZeroClaw API)
- Cloudflare Tunnel / ngrok (public URL exposure)
- Telegram Bot API (via polling or webhook)
- WhatsApp via Twilio API
- Android WorkManager (keep-alive scheduling)
- Room Database (local storage)
- DataStore Preferences (settings)
- Material Design 3 (UI)

---

## 📋 Phase Overview

| Phase | Description | Status |
|-------|-------------|--------|
| 1  | Project structure & config files | ✅ DONE |
| 2  | plan.md + SKILL.md documentation | ✅ DONE |
| 3  | Gradle build files (app + root) | ✅ DONE |
| 4  | AndroidManifest.xml | ✅ DONE |
| 5  | MainActivity + Jetpack Compose UI | ✅ DONE |
| 6  | ZeroClawService (Foreground Service) | ✅ DONE |
| 7  | ZeroClaw HTTP API client (Retrofit) | ✅ DONE |
| 8  | Telegram Bot integration | ✅ DONE |
| 9  | WhatsApp/Twilio integration | ✅ DONE |
| 10 | TunnelManager (Cloudflare/ngrok) | ✅ DONE |
| 11 | Settings screen + DataStore | ✅ DONE |
| 12 | Room DB (message history) | ✅ DONE |
| 13 | Notification channels + boot receiver | ✅ DONE |
| 14 | Theme + colors + strings resources | ✅ DONE |
| 15 | Final review & build instructions | ✅ DONE |
| 16 | Info/Help system — InfoScreen.kt (full guide) | ✅ DONE |
| 17 | InfoData.kt — all guide content & step data | ✅ DONE |
| 18 | HomeScreen.kt — add ℹ️ button → InfoScreen nav | ✅ DONE |
| 19 | MainActivity.kt — add info route to NavHost | ✅ DONE |
| 20 | strings.xml — add info-related strings | ✅ DONE |
| 21 | ApiKeyEntry.kt data model (label+key+provider per entry) | ✅ DONE |
| 22 | LlmKeyManager.kt — multi-key store, failover, Gson serialize | ✅ DONE |
| 23 | LlmRouter.kt — waterfall caller with per-provider dispatch | ✅ DONE |
| 24 | AppSettings.kt — extend to store JSON key list + active index | ✅ DONE |
| 25 | ApiKeysScreen.kt — full key manager UI (add/edit/delete/reorder) | ✅ DONE |
| 26 | SettingsScreen.kt — replace single key field → "Manage API Keys" button | ✅ DONE |
| 27 | TelegramBotManager.kt — use LlmRouter instead of direct call | ✅ DONE |
| 28 | TwilioWhatsAppManager.kt — use LlmRouter instead of direct call | ✅ DONE |
| 29 | MainActivity.kt — add api_keys route to NavHost | ✅ DONE |
| 30 | HomeScreen.kt — show active key label + failover status in StatusCard | ✅ DONE |
| 31 | LlmRouter.kt — add validateKey() + Gemini model listing via /v1beta/models | ✅ DONE |
| 32 | ApiKeysScreen.kt — "Test Key" button with live validation card + Gemini model picker | ✅ DONE |
| 33 | ApiKeyEntry.kt — add nullable preferredModel field (Gson-safe) + safe accessors | ✅ DONE |
| 34 | LlmRouter.kt — use preferredModel for Gemini dispatch; switch default to gemini-1.5-flash | ✅ DONE |
| 35 | LlmRouter.kt — smart 429/quota detection: log as rate-limit, don't hard-fail key; better error messages | ✅ DONE |
| 36 | HomeScreen.kt — ServiceControlCard: explicit dark background + green/red buttons with white text | ✅ DONE |
| 37 | ApiKeyEntry.kt + all callers — guard all fields with safeLabel/safeProvider/safeApiKey/safePreferredModel to prevent NPE on Gson null deserialization | ✅ DONE |
| 38 | ApiKeyEntry.kt — add nullable `baseUrl` field + `safeBaseUrl` accessor | ✅ DONE |
| 39 | LlmRouter.kt — use `safeBaseUrl` override in OpenAI-compatible dispatch + validation | ✅ DONE |
| 40 | ApiKeysScreen.kt — add optional Base URL field in KeyEditDialog (shown for custom providers) | ✅ DONE |
| 41 | ApiKeysScreen.kt — fix Test Key bug: pass currently selectedModel as preferredModel so it tests the actual model user picked | ✅ DONE |
| 42 | ApiKeysScreen.kt — add cURL input mode: parse cURL command → extract bearer token + base URL + model; test live; auto-fill fields; save on success | ✅ DONE |
| 43 | LlmRouter.kt — validateKeyWithCurl(): live HTTP call using parsed cURL params to confirm valid | ✅ DONE |
| 44 | LlmKeyManager.kt — add moveKey(id, direction) for up/down reorder + setActiveKey(id) to pin a specific key as the default starting point | ✅ DONE |
| 45 | ApiKeysScreen.kt — add ↑↓ reorder buttons on each key card so user can drag priority order up/down | ✅ DONE |
| 46 | ApiKeysScreen.kt — add "Set Active" button/tap on key card: sets that key as the current active (index 0 in failover chain); shown with green ACTIVE badge | ✅ DONE |
| 47 | LlmKeyManager.kt — persist active key id in DataStore so the chosen active key survives app restart | ✅ DONE |
| 48 | LlmRouter.kt — listOpenAIModels(): fetch /v1/models, return sorted list for OpenAI + OpenRouter + custom endpoints | ✅ DONE |
| 49 | LlmRouter.kt — listAnthropicModels(): return hardcoded curated Anthropic model list (API has no public list endpoint) | ✅ DONE |
| 50 | LlmRouter.kt — listOllamaModels(): fetch /api/tags, return local model names | ✅ DONE |
| 51 | LlmRouter.kt — validateKey() extended: for openai/openrouter/custom call listOpenAIModels(); for anthropic return curated list; for ollama return /api/tags list — all providers now return availableModels in ValidationResult | ✅ DONE |
| 52 | LlmRouter.kt — callOpenAICompatible() uses safePreferredModel if set, falls back to provider default | ✅ DONE |
| 53 | LlmRouter.kt — callAnthropic() uses safePreferredModel if set, falls back to claude-haiku-4-5-20251001 | ✅ DONE |
| 54 | ApiKeysScreen.kt — model picker shown for ALL providers after Test Key succeeds (not just Gemini); label and best-model highlight logic per provider | ✅ DONE |
| 55 | ApiKeysScreen.kt — fix stale model bug: when editing an existing key, reset selectedModel to "" if provider changes so old cross-provider model IDs don't carry over | ✅ DONE |
| 56 | LlmRouter.kt — listOpenRouterModels(): call GET /v1/models with Bearer token, parse full model list including id + name + context_length + pricing, return as OpenRouterModel data objects | ✅ DONE |
| 57 | ApiKeysScreen.kt — "Browse Models" button for OpenRouter: opens full-screen bottom sheet/dialog showing live model catalog; searchable by name; shows context size + pricing; user taps model → sets it as selectedModel | ✅ DONE |
| 58 | ApiKeysScreen.kt — after Browse Models picks a model, auto-run Test Key against that model; show result inline so user knows it works before saving | ✅ DONE |
| 59 | Offline mode — OfflineModelManager.kt for MediaPipe .bin models, SAF file picker, import/use-in-place dialog | ✅ DONE |
| 60 | Per-model testing — Check All Models button, per-model pass/fail, auto-select working models | ✅ DONE |
| 61 | Model selection persistence — serializeNulls fix, Edit Selection button, deselect keeps in list | ✅ DONE |
| 62 | Skip key when all models deselected, unmarkFailed on re-select | ✅ DONE |
| 63 | Per-chat conversation history — separate context per Telegram/WhatsApp user | ✅ DONE |
| 64 | Google Search grounding — per-key toggle for Gemini API calls | ✅ DONE |
| 65 | Detailed live logs — mode/provider/key/model in every log entry | ✅ DONE |
| 66 | Screenshots + README update with all features, app icon, ZeroClaw Labs credit | ✅ DONE |
| 67 | ToolSystem.kt — tool registry & dispatcher | ✅ DONE |
| 68 | WebSearchTool.kt — DuckDuckGo web search | ✅ DONE |
| 69 | WebFetchTool.kt — URL content extraction | ✅ DONE |
| 70 | LlmRouter.kt — tool system integration | ✅ DONE |
| 71 | SettingsScreen.kt — tool toggle UI | ✅ DONE |
| 72 | MemoryTool.kt — persistent per-user memory (Room/SQLite) | ✅ DONE |
| 73 | PdfReadTool.kt — PDF text extraction | ✅ DONE |
| 74 | ImageAnalysisTool.kt — vision model image analysis | ✅ DONE |
| 75 | CronTool.kt — scheduled recurring AI tasks | ✅ DONE |
| 76 | StatusTool.kt — service diagnostics & health reporting | ✅ DONE |
| 77 | GitHubTool.kt — GitHub repo search, issues, READMEs | ✅ DONE |
| 78 | InfoData.kt — AI Tools guide tab with all 8 tools documented | ✅ DONE |
| **79** | **NotionTool.kt — query/create/update Notion pages via API** | 🔲 TODO |
| **80** | **EmailTool.kt — send emails via SMTP or SendGrid** | 🔲 TODO |
| **81** | **DiscordChannel.kt — Discord bot integration as messaging channel** | 🔲 TODO |
| **82** | **SignalChannel.kt — Signal messaging integration** | 🔲 TODO |

---

## 🆕 Phase 67-79 Detail: Tool System & Skills (from ZeroClaw upstream)

### Architecture — Tool System
```
User sends message via Telegram/WhatsApp
         ↓
   LlmRouter builds system prompt with available tools
         ↓
   LLM decides: reply directly OR call a tool
         ↓
   If tool_use → ToolSystem.dispatch(toolName, args)
         ↓
   Tool executes (web search, fetch URL, read PDF, etc.)
         ↓
   Result fed back to LLM as tool_result
         ↓
   LLM generates final reply using tool result
         ↓
   Reply sent back to user
```

### Tool Interface
```kotlin
interface Tool {
    val name: String
    val description: String       // shown to LLM in system prompt
    val parameters: String        // JSON schema for args
    suspend fun execute(args: Map<String, String>): ToolResult
}

data class ToolResult(
    val success: Boolean,
    val content: String,          // text result fed back to LLM
    val error: String? = null
)
```

### Phase 67 — ToolSystem.kt (registry & dispatcher)
- `ToolSystem` singleton: register tools, list enabled tools, dispatch by name
- `buildToolsPrompt()`: generates tool descriptions for system prompt injection
- `parseToolCalls(response)`: extracts tool_use blocks from LLM response
- `executeToolCall(name, args)`: runs tool, returns ToolResult
- Per-tool enable/disable stored in DataStore

### Phase 68 — WebSearchTool (DuckDuckGo, no key needed)
- Searches DuckDuckGo HTML endpoint, parses top 5 results
- Returns: title + snippet + URL for each result
- LLM uses results to answer user's question with real-time info

### Phase 69 — WebFetchTool (URL content extraction)
- Fetches URL, strips HTML tags, extracts readable text
- Truncates to ~4000 chars to fit context
- Useful for "summarize this article" type requests

### Phase 70 — LlmRouter integration
- System prompt includes tool definitions when tools are enabled
- After LLM response, check for tool_use → execute → send tool_result → get final reply
- Works with all providers: OpenAI (function calling), Anthropic (tool_use), Gemini (function declarations)
- Max 3 tool rounds per message to prevent infinite loops

### Phase 71 — Settings UI for tools
- New "Tools" section in Settings or AI Configuration
- Toggle switch per tool (Web Search, Web Fetch, Memory, etc.)
- Tool status shown on Home screen

---

## 🆕 Phase 21-30 Detail: Multi-Key LLM Failover System

### Architecture
```
User adds N keys (any mix of providers):
  [OpenAI key1] [Anthropic key2] [Gemini key3] [OpenRouter key4] ...

LlmRouter.call(message):
  try key[0] → success → return reply
  try key[0] → fail    → log warning, try key[1]
  try key[1] → fail    → log warning, try key[2]
  ...
  all fail             → return "All API keys exhausted" error message

Active key index tracked in memory + DataStore.
Resets to 0 each service start (so recovered keys get re-tried).
```

### Providers supported
| Provider | ID | Auth Header | Base URL |
|---|---|---|---|
| OpenAI | `openai` | Bearer | https://api.openai.com/v1 |
| Anthropic | `anthropic` | x-api-key | https://api.anthropic.com/v1 |
| Google Gemini | `gemini` | Bearer / ?key= | https://generativelanguage.googleapis.com/v1beta |
| OpenRouter | `openrouter` | Bearer | https://openrouter.ai/api/v1 |
| Ollama (local) | `ollama` | none | http://127.0.0.1:11434 |

### UI for key management
- Settings screen shows "🔑 Manage API Keys (N configured)" button
- ApiKeysScreen: full-page list of all keys
  - Each card: label (nickname), provider chip, masked key, status badge
  - Swipe or trash button to delete
  - "+" FAB to add new key (shows label + provider dropdown + key field)
  - Active key highlighted with green badge
  - Failed keys shown with red warning badge (reset on restart)

### Key data model
```kotlin
data class ApiKeyEntry(
    val id: String,                    // UUID
    val label: String,                 // user nickname e.g. "My OpenAI key"
    val provider: String,              // "openai" | "anthropic" | "gemini" | "openrouter" | "ollama"
    val apiKey: String,                // the actual key
    val enabled: Boolean = true,
    val preferredModel: String? = null // nullable! Gson sets missing fields to null, not "" 
)
// Safe accessors (always use these — guards against Gson null deserialization on old saved entries):
// entry.safeLabel, entry.safeProvider, entry.safeApiKey, entry.safePreferredModel
```

### Key validation flow (Phase 31-32)
- "Test Key" button in KeyEditDialog calls `LlmRouter.validateKey(entry)`
- **Gemini**: calls `/v1beta/models?key=...` → lists all `generateContent` models
  - On success: shows model count, auto-selects `gemini-2.5-flash` (★ BEST badge), user can change
  - Selected model stored in `preferredModel`, used by `callGemini()` for every real message
- **OpenAI/OpenRouter**: tiny 5-token test completion
- **Anthropic**: tiny 5-token test message
- **Ollama**: checks `/api/tags`
- HTTP 429 / "quota" / "rate" in error → shown as ⚠️ RATE_LIMITED (orange), NOT ❌ ERROR
  - Key is still saveable; failover skips it this session but retries on service restart

### NPE fix (Phase 37)
- **Root cause**: Gson deserializes JSON that was saved before `preferredModel` existed,
  setting it to `null` at runtime even though Kotlin declares it non-null
- **Fix**: Changed `preferredModel` to `String?` (nullable), added `.safe*` computed properties
  on `ApiKeyEntry` that return `""` if null — all UI/router code uses these safe accessors

---

## 📁 File Checklist

### Root Project Files
- [x] `build.gradle.kts` (root)
- [x] `settings.gradle.kts`
- [x] `gradle.properties`
- [x] `gradle/libs.versions.toml`

### App Module
- [x] `app/build.gradle.kts`
- [x] `app/src/main/AndroidManifest.xml`

### Kotlin Source Files (`app/src/main/java/ai/zeroclaw/android/`)
- [x] `MainActivity.kt`
- [x] `ui/HomeScreen.kt`
- [x] `ui/InfoScreen.kt`
- [x] `ui/InfoData.kt`
- [x] `ui/SettingsScreen.kt`
- [x] `ui/ApiKeysScreen.kt`            ← NEW Phase 25
- [x] `ui/theme/Theme.kt`
- [x] `ui/theme/Color.kt`
- [x] `service/ZeroClawService.kt`
- [x] `service/BootReceiver.kt`
- [x] `telegram/TelegramBotManager.kt`
- [x] `whatsapp/TwilioWhatsAppManager.kt`
- [x] `tunnel/TunnelManager.kt`
- [x] `data/AppSettings.kt`
- [x] `data/ApiKeyEntry.kt`            ← NEW Phase 21
- [x] `data/LlmKeyManager.kt`          ← NEW Phase 22
- [x] `data/LlmRouter.kt`              ← NEW Phase 23
- [x] `data/MessageDatabase.kt`
- [x] `data/OfflineModelManager.kt`    ← Phase 59
- [x] `tools/ToolSystem.kt`             ← Phase 67 (tool registry & dispatcher)
- [x] `tools/WebSearchTool.kt`         ← Phase 68 (DuckDuckGo search)
- [x] `tools/WebFetchTool.kt`          ← Phase 69 (URL content extraction)
- [x] `tools/MemoryTool.kt`            ← Phase 72 (persistent memory)
- [x] `tools/PdfReadTool.kt`           ← Phase 73 (PDF text extraction)
- [x] `tools/ImageAnalysisTool.kt`     ← Phase 74 (vision models)
- [x] `tools/CronTool.kt`              ← Phase 75 (scheduled tasks)
- [x] `tools/StatusTool.kt`            ← Phase 76 (service diagnostics)
- [x] `tools/GitHubTool.kt`            ← Phase 77 (GitHub integration)
- [x] `data/MemoryDatabase.kt`         ← Phase 72 (Room DB for memory)
- [ ] `tools/NotionTool.kt`            ← Phase 79 (Notion API)
- [ ] `tools/EmailTool.kt`             ← Phase 80 (email send)

### Resources
- [x] `app/src/main/res/values/strings.xml`
- [x] `app/src/main/res/values/colors.xml`
- [x] `app/src/main/res/drawable/ic_zeroclaw.xml`
- [x] `app/src/main/res/xml/network_security_config.xml`

---

## 🚀 Build & Run Instructions (Android Studio)

1. Open Android Studio → **File → Open** → Select `H:\Antigravity\ZeroClawAndroid`
2. Wait for Gradle sync to complete
3. Connect Android device (USB debugging ON) or create AVD emulator
4. Click ▶️ **Run**
5. On first launch, tap ℹ️ for setup guide, then ⚙️ for settings
6. In Settings tap **Manage API Keys** → add your keys (OpenAI, Gemini, Anthropic, etc.)
7. Add Telegram token and Twilio credentials
8. Tap **Start** — service begins, failover is automatic

---

## 🔧 Resumption Instructions (if chat crashes)
1. Open new Claude chat
2. Upload this `plan.md` file
3. Say: *"Continue building ZeroClawAndroid from plan.md — resume at first step that is NOT marked ✅ DONE"*
4. Claude will read the plan and continue exactly where you left off.

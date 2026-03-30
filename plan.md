# ZeroClawAndroid — Master Build Plan
> **Purpose:** If this chat crashes, open a new chat, share this file, and development continues from last completed step.
> **Project:** Android app that runs ZeroClaw AI agent, connects to Telegram & WhatsApp, deployable via Android Studio.
> **Location:** `D:\apps\ZeroClawAndroid`

---

## 🔗 Source Repositories (IMPORTANT — analyse these before implementing new features)

| Repo | URL | Purpose |
|------|-----|---------|
| **ZeroClaw** | https://github.com/zeroclaw-labs/zeroclaw.git | Original upstream — skills, tools, agents, channels. Our app is the Android port of this. |
| **OpenClaw** | https://github.com/openclaw/openclaw.git | Master/parent project — 50+ skills, 73 extensions, multi-agent, vector memory, hooks, workflows. Phases 85-140 are inspired by this. |

> **For AI assistants:** When starting a new conversation and implementing phases 85+, clone/analyse these repos first to understand the original implementation before porting to Android/Kotlin. Don't guess — read the source.

---

## 📋 Dev Workflow Rules (follow this order for EVERY feature)

1. **Implement** the feature (new tool, channel, UI, etc.)
2. **Add info entry** in `InfoData.kt` — add a `GuideStep` with `isNew = true` in the relevant `GuideSection`
3. **Add auto-tool detection** in `LlmRouter.kt` → `autoToolEnrich()` if it's a tool (so offline models can use it)
4. **Update tool count** in `InfoData.kt` `APP_FEATURES` if a new tool was added
5. **Mark phase done** in `plan.md` with ✅
6. **Build** with `JAVA_HOME="C:/Users/DELL/AppData/Local/Programs/Android Studio/jbr" ./gradlew assembleDebug`
7. **Commit & push** to GitHub after every successful build by updating readme
8. **Screenshots** for testing go to `test_screenshots/` (gitignored) — analyse then delete before commit
9. When setting `isNew = true` on a GuideStep, it shows a **NEW** badge until the user taps it (tracked via SharedPreferences)

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
| 79 | NotionTool.kt — Notion workspace integration | ✅ DONE |
| 80 | EmailTool.kt — send emails via SendGrid/Mailgun | ✅ DONE |
| 81 | DiscordBotManager.kt — Discord Gateway WebSocket + REST | ✅ DONE |
| 82 | SignalBridgeManager.kt — Signal via signal-cli REST API | ✅ DONE |
| 83 | LlmRouter — online-first failover (online keys → offline fallback) | ✅ DONE |
| 84 | ApiKeysScreen — online section top, offline section bottom | ✅ DONE |
| | | |
| | **═══ OpenClaw-Inspired Features (Phase 85+) ═══** | |
| | | |
| | **── NEW TOOLS (Skills) ──** | |
| **85** | **WeatherTool.kt — weather via wttr.in (free, no key)** | ✅ DONE |
| **86** | **SummarizeTool.kt — extractive summarization (no LLM needed)** | ✅ DONE |
| **87** | **TranslateTool.kt — 50+ languages via MyMemory API (free)** | ✅ DONE |
| **88** | **ImageGenTool.kt — Pollinations.ai (free) + DALL-E 3** | ✅ DONE |
| **89** | **SpeechToTextTool.kt — Whisper API transcription** | ✅ DONE |
| **90** | **TextToSpeechTool.kt — Android native TTS (free, offline)** | ✅ DONE |
| **91** | **CalendarTool.kt — read/create Android calendar events** | ✅ DONE |
| **92** | **ContactsTool.kt — search/read Android contacts** | ✅ DONE |
| **93** | **LocationTool.kt — GPS location, nearby places, geocoding** | ✅ DONE |
| **94** | **CalculatorTool.kt — evaluate math expressions & unit conversions** | ✅ DONE |
| **95** | **RssFeedTool.kt — monitor RSS/Atom feeds, blog watcher** | ✅ DONE |
| **96** | **QrCodeTool.kt — generate & scan QR codes** | ✅ DONE |
| **97** | **FileManagerTool.kt — list, read, write local files on device** | ✅ DONE |
| **98** | **ClipboardTool.kt — read/write device clipboard** | ✅ DONE |
| **99** | **SpotifyTool.kt — Spotify playback control (play, pause, next, search)** | ✅ DONE |
| **100** | **SmartHomeTool.kt — Philips Hue / generic IoT device control** | ✅ DONE |
| **101** | **BraveTool.kt — Brave Search API (alternative to DuckDuckGo)** | ✅ DONE |
| **102** | **BookmarkTool.kt — save, list, search bookmarks per user** | ✅ DONE |
| | | |
| | **── MESSAGING CHANNELS ──** | |
| **103** | **SlackBotManager.kt — Slack workspace bot (Socket Mode WebSocket)** | ✅ DONE |
| **104** | **MatrixBotManager.kt — Matrix/Element protocol integration** | ✅ DONE |
| **105** | **IrcBotManager.kt — IRC protocol bot (raw TCP)** | ✅ DONE |
| **106** | **TeamsBotManager.kt — Microsoft Teams bot (Bot Framework webhook)** | ✅ DONE |
| **107** | **TwitchBotManager.kt — Twitch chat bot (IRC over SSL/TMI)** | ✅ DONE |
| **108** | **LineBotManager.kt — LINE messaging integration (webhook)** | ✅ DONE |
| **109** | **WebChatServer.kt — built-in web chat UI served on :8088** | ✅ DONE |
| | | |
| | **── ADVANCED AI FEATURES ──** | |
| **110** | **SystemPromptManager.kt — per-channel/per-user configurable persona** | ✅ DONE |
| **111** | **StreamingResponseManager.kt — stream LLM output via SSE (OpenAI/Anthropic)** | ✅ DONE |
| **112** | **MultiAgentManager.kt — spawn sub-agents for parallel tasks** | ✅ DONE |
| **113** | **AgentProfileManager.kt — named AI personas (6 built-in + custom)** | ✅ DONE |
| **114** | **WorkflowEngine.kt — multi-step automated pipelines with conditions** | ✅ DONE |
| **115** | **ToolLoopDetector.kt — prevent infinite tool call chains (4 detection methods)** | ✅ DONE |
| **116** | **ThinkingMode.kt — extended chain-of-thought reasoning for complex problems** | ✅ DONE |
| **117** | **ConversationSummarizer.kt — auto-compress long chat histories** | ✅ DONE |
| | | |
| | **── MEMORY & CONTEXT (Vector Search) ──** | |
| **118** | **VectorMemory.kt — semantic embeddings (OpenAI/Gemini/TF-IDF fallback)** | ✅ DONE |
| **119** | **HybridSearch.kt — RRF fusion of vector + keyword search (60/40 weights)** | ✅ DONE |
| **120** | **QueryExpansion.kt — synonym expansion + stop-word filtering** | ✅ DONE |
| **121** | **TemporalDecay.kt — exponential decay, 30-day half-life, pinned tags** | ✅ DONE |
| **122** | **SessionManager.kt — named sessions, switching, export as JSON** | ✅ DONE |
| | | |
| | **── INFRASTRUCTURE & PLATFORM ──** | |
| **123** | **HooksSystem.kt — before/after tool call middleware (request/response transform)** | ✅ DONE |
| **124** | **PluginSystem.kt — user-installable plugin packages (.zip skills)** | ✅ DONE |
| **125** | **WebViewTool.kt — browser automation via Android WebView** | ✅ DONE |
| **126** | **MediaPipeline.kt — image resize, audio convert, video frame extraction** | ✅ DONE |
| **127** | **RichNotifications.kt — per-channel push notifications with quick reply** | ✅ DONE |
| **128** | **BiometricLock.kt — fingerprint/face auth to protect the app** | ✅ DONE |
| **129** | **DevicePairing.kt — pair with desktop/other devices via QR code** | ✅ DONE |
| **130** | **AutoRecovery.kt — WorkManager watchdog to restart service on crash** | ✅ DONE |
| | | |
| | **── CONFIGURATION & UX ──** | |
| **131** | **ExportImportConfig.kt — backup/restore all settings & keys as JSON** | ✅ DONE |
| **132** | **ThemeToggle — manual dark/light/system theme switch in Settings** | ✅ DONE |
| **133** | **PerChannelPrompt — custom system prompt per Telegram/Discord/etc** | ✅ DONE |
| **134** | **RateLimiting.kt — per-user message rate limits (anti-abuse)** | ✅ DONE |
| **135** | **UsageTracking.kt — track token usage & costs per key/model** | ✅ DONE |
| **136** | **ApprovalSystem.kt — require user approval for dangerous tool actions** | ✅ DONE |
| **137** | **ConversationLabels.kt — tag/label/pin conversations** | ✅ DONE |
| **138** | **HomeWidget.kt — Android home screen widget (service status + quick start)** | ✅ DONE |
| **139** | **VoiceInput.kt — voice message transcription from Telegram/WhatsApp** | ✅ DONE |
| **140** | **GroupChatSupport.kt — handle Telegram/Discord group messages with @mention** | ✅ DONE |
| | | |
| | **── NULLCLAW-INSPIRED FEATURES (Phase 141+) ──** | |
| **141** | **ComposioTool.kt — 1000+ OAuth integrations via Composio API v3/v2** | ✅ DONE |
| **142** | **DelegateTool.kt + SpawnTool.kt — named agent delegation + async task spawn** | ✅ DONE |
| **143** | **MessageTool.kt — proactive cross-channel messaging from agents/crons** | ✅ DONE |
| **144** | **McpClient.kt — MCP JSON-RPC 2.0 stdio+HTTP client, tool discovery** | ✅ DONE |
| **145** | **MmrReranker.kt + AdaptiveRetrieval.kt — MMR diversity + auto strategy** | ✅ DONE |
| **146** | **MemoryHygiene.kt + SemanticCache.kt — archive/purge lifecycle + LRU cache** | ✅ DONE |
| **147** | **Context Compaction — auto/force/trim modes in ConversationSummarizer** | ✅ DONE |
| **148** | **A2AServer.kt — Agent-to-Agent protocol (Google A2A JSON-RPC 2.0)** | ✅ DONE |
| **149** | **ProviderRouter.kt — hint-based routing + per-model fallback chains** | ✅ DONE |
| **150** | **AgentIdentity.kt — AIEOS v1.1 identity (MBTI/OCEAN/catchphrases)** | ✅ DONE |
| **151** | **PushoverTool.kt — push notifications to any device via Pushover API** | ✅ DONE |
| **152** | **NostrTool.kt — publish/subscribe on Nostr decentralized protocol** | ✅ DONE |
| **153** | **MattermostManager.kt — Mattermost self-hosted team chat integration** | ✅ DONE |
| **154** | **DingTalkLarkManager.kt — DingTalk + Lark/Feishu webhook integration** | ✅ DONE |
| **155** | **SkillForge.kt — runtime skill creation from LLM output (save & run)** | ✅ DONE |
| **156** | **AuditLog.kt — tamper-evident JSON Lines log of all tool actions** | ✅ DONE |
| **157** | **OpenTelemetry.kt — OTLP span export for tracing + metrics** | ✅ DONE |
| | | |
| | **── BUG FIXES & UI IMPROVEMENTS (Phase 158+) ──** | |
| **158** | **Offline summarizer refusal fallback — isSummarizerRefusal() detects useless "can't answer from context" replies from small models, falls back to direct web data reply** | ✅ DONE |
| **159** | **Garbage offline reply detection — isGarbageOfflineReply() catches hallucinated URLs and irrelevant responses with zero keyword overlap, triggers Pass 2** | ✅ DONE |
| | | |
| | **── AGENTS SYSTEM (Phase 160) ──** | |
| **160** | **Web Scraper Agent system — AgentConfig, AgentManager, WebScraperAgent, AgentsScreen, AgentCreateSheet, Run Now, Test Fetch, chatId validation** | ✅ DONE |
| | | |
| | **── UI & UX IMPROVEMENTS (Phase 161+) ──** | |
| **161** | **Summarizer prompt improvement — simpler directive language for small models ("Do NOT say the data is missing"), removed FETCHED DATA delimiters** | ✅ DONE |
| **162** | **Extended summarizer refusal patterns — "available in the text", "has not occurred", "based on...the text...not", "no evidence/indication"** | ✅ DONE |
| **163** | **Log UI improvements — increased DetailedLogCard font 10/11sp→12/13sp, LiveLogs 11sp→12sp; added Copy button to both log cards with 2s "Copied!" feedback** | ✅ DONE |
| | | |
| | **── CURL API ACCESS GENERATOR (Phase 164) ──** | |
| **164** | **HomeScreen LiveLog — "Generate cURL" button in Server Address dialog: generates OpenAI-compatible cURL commands for /api/chat and /api/generate endpoints so any external app (GPT wrappers, IDE plugins, scripts) can connect to ZeroClaw as an AI backend. Adds /v1/chat/completions & /v1/models OpenAI-compatible endpoints to WebChatServer. Curl dialog with copy-to-clipboard for chat, generate, and OpenAI-compatible formats** | ✅ DONE |
| | | |
| | **── AI SMART EXTRACTION (Phase 165) ──** | |
| **165** | **AgentCreateSheet — AI Smart Extract: text field for user to describe what they need, "AI Analyze" button that fetches URL → AI checks if content exists → shows formatted preview with editable output template → fetch type selector (HTTP/RSS/WebView) with retry → WebView headless fallback when HTTP fails** | ✅ DONE |
| **166** | **Agent extraction formatting — extractWithLlm() strict prompt: output ONLY requested data in Telegram Markdown (*bold*, bullets, numbered lists), no preamble/disclaimers. cleanExtractedReply() post-processor strips leaked filler. AI Analyze preview matches delivery format** | ✅ DONE |
| **167** | **Persist fetch type & format — AgentConfig gets fetchType (http/rss/webview) and formatPreview fields. Edit mode pre-fills all AI Smart Extract state. WebScraperAgent uses saved fetchType for fetching. Agent cards show fetch type & AI Format badges** | ✅ DONE |
| **168** | **JS-rendered page detection — AI Analyze detects when HTTP fetch returns empty values (SPAs like groww.in) and sets found=false with suggest_webview. Auto-switches to WebView. Editable format preview (OutlinedTextField). "Show live data" button lists all extractable values. "Copy all" button for raw fetched data. WebView warning panel** | ✅ DONE |
| **169** | **Bottom sheet scroll fix — all ModalBottomSheets (AgentCreateSheet, AgentTemplateGallery, ToolTestSheet, ModelSelector) set dragHandle=null to prevent dismiss-on-scroll conflict. Added explicit close (X) buttons to all sheet headers instead** | ✅ DONE |

---

## 🔌 Phase 164 Detail: Connect Any App to ZeroClaw API

### Quick Connect (any app that supports custom OpenAI base URL)
```
Base URL:  http://<DEVICE_IP>:8088/v1
API Key:   zc-no-key-needed
Model:     zeroclaw
```
Replace `<DEVICE_IP>` with the phone's LAN IP (shown in app: Live Logs → Server Address).
If using a tunnel (ngrok/Cloudflare), use the tunnel URL instead for remote access.

### Endpoints
| Method | Path | Format | What It Does |
|--------|------|--------|-------------|
| `POST` | `/v1/chat/completions` | OpenAI-compatible | **Full agent pipeline** — 36+ tools, memory, thinking mode, multi-provider failover. Drop-in OpenAI replacement. |
| `GET` | `/v1/models` | OpenAI-compatible | Returns model list (`zeroclaw`). |
| `POST` | `/api/chat` | ZeroClaw native | Simple chat with session memory. |
| `POST` | `/api/generate` | ZeroClaw native | Raw LLM output — no tools, no history. Supports `json_mode`. |
| `GET` | `/api/discover` | ZeroClaw native | Service discovery — version, port, all endpoints. |
| `GET` | `/` | HTML | Browser-based chat UI. |

### cURL Examples

**OpenAI-compatible (recommended — works with any GPT wrapper):**
```bash
curl -X POST "http://<DEVICE_IP>:8088/v1/chat/completions" \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer zc-no-key-needed" \
  -d '{"model":"zeroclaw","messages":[{"role":"user","content":"Hello!"}],"max_tokens":8192}'
```

**Response format (standard OpenAI):**
```json
{
  "id": "chatcmpl-zc1719849600000",
  "object": "chat.completion",
  "created": 1719849600,
  "model": "zeroclaw",
  "choices": [{"index":0,"message":{"role":"assistant","content":"Hello! How can I help?"},"finish_reason":"stop"}],
  "usage": {"prompt_tokens":10,"completion_tokens":20,"total_tokens":30}
}
```

**Simple chat API:**
```bash
curl -X POST "http://<DEVICE_IP>:8088/api/chat" \
  -H "Content-Type: application/json" \
  -d '{"message":"Hello!","session_id":"my_app"}'
# Response: {"reply":"Hello! How can I help?"}
```

**Raw generate (no tools, no history):**
```bash
curl -X POST "http://<DEVICE_IP>:8088/api/generate" \
  -H "Content-Type: application/json" \
  -d '{"prompt":"List 5 colors as JSON","json_mode":true,"max_tokens":8192}'
# Response: {"text":"[\"red\",\"blue\",\"green\",\"yellow\",\"purple\"]"}
```

### SDK Integration

**Python (OpenAI SDK):**
```python
from openai import OpenAI
client = OpenAI(base_url="http://<DEVICE_IP>:8088/v1", api_key="zc-no-key-needed")
r = client.chat.completions.create(model="zeroclaw", messages=[{"role":"user","content":"Hello"}])
print(r.choices[0].message.content)
```

**JavaScript (OpenAI SDK):**
```javascript
import OpenAI from 'openai';
const client = new OpenAI({baseURL:'http://<DEVICE_IP>:8088/v1', apiKey:'zc-no-key-needed'});
const r = await client.chat.completions.create({model:'zeroclaw', messages:[{role:'user',content:'Hello'}]});
console.log(r.choices[0].message.content);
```

### Compatible Apps & Tools
Continue.dev, Cursor, Open WebUI, LangChain, LlamaIndex, AutoGen, CrewAI, Aider, any app with `OPENAI_API_BASE` env var support — just set the base URL and it works.

### What the connected app gets
All 36+ AI tools (web search, weather, translate, image gen, calculator, RSS, GitHub, email, smart home, etc.), multi-provider LLM failover, vector memory, thinking mode, conversation history, and the full agent pipeline — the connected app doesn't need to know about any of this, it just sends messages and gets intelligent responses.

### Files Changed
- `WebChatServer.kt` — added `POST /v1/chat/completions`, `GET /v1/models`, `OPTIONS` CORS preflight
- `HomeScreen.kt` — added `CurlGeneratorDialog` composable with 3 tabs (OpenAI / Chat / Generate), copy-to-clipboard, Python/JS code snippets

---

## 🆕 Phase 85-140 Detail: OpenClaw-Inspired Features

### Source: [OpenClaw](https://github.com/openclaw/openclaw.git)
OpenClaw is a production-grade personal AI framework with 50+ skills, 73 extensions,
20+ messaging channels, and 13+ model providers. The features below are adapted for
Android-native implementation in ZeroClaw Android.

### Priority Tiers

**🔴 HIGH PRIORITY (Phases 85-102) — New Tools**
These directly enhance what the AI can do in conversations.

| Phase | Tool | What It Does | API/Source |
|-------|------|-------------|-----------|
| 85 | Weather ✅ | Current weather & forecasts | wttr.in (free, no key) |
| 86 | Summarize ✅ | Condense long text/articles | Extractive (no LLM needed) |
| 87 | Translate ✅ | Multi-language translation | MyMemory API (free, no key) |
| 88 | Image Gen ✅ | Generate images from text | Pollinations.ai (free) + DALL-E 3 |
| 89 | Speech-to-Text ✅ | Transcribe voice/audio | Whisper API (OpenAI/Groq) |
| 90 | Text-to-Speech ✅ | Read replies aloud | Android TTS (free, offline) |
| 91 | Calendar ✅ | Read/create calendar events | Android CalendarProvider |
| 92 | Contacts ✅ | Search device contacts | Android ContactsProvider |
| 93 | Location ✅ | GPS, places, geocoding | Android LocationManager |
| 94 | Calculator ✅ | Math expressions & units | Local eval (no API) |
| 95 | RSS Feed ✅ | Monitor blogs & feeds | Direct HTTP + XML parsing |
| 96 | QR Code ✅ | Generate & scan QR codes | Built-in encoder (no library) |
| 97 | File Manager ✅ | List/read/write files | Android File API |
| 98 | Clipboard ✅ | Read/write clipboard | Android ClipboardManager |
| 99 | Spotify ✅ | Music playback control | Spotify Web API |
| 100 | Smart Home ✅ | Control Hue/IoT devices | Hue REST API |
| 101 | Brave Search ✅ | Alternative web search | Brave Search API |
| 102 | Bookmarks ✅ | Save/search bookmarks | Room DB per user |

**🟡 MEDIUM PRIORITY (Phases 103-117) — Channels & AI**
New messaging channels and smarter AI behavior.

| Phase | Feature | Description |
|-------|---------|-------------|
| 103 | Slack | Slack workspace bot via Events/RTM API |
| 104 | Matrix | Matrix/Element decentralized messaging |
| 105 | IRC | IRC protocol bot (raw TCP) |
| 106 | MS Teams | Microsoft Teams bot via Bot Framework |
| 107 | Twitch | Twitch chat integration (IRC-based) |
| 108 | LINE | LINE Messaging API bot |
| 109 | Web Chat | Built-in web chat UI served via tunnel URL |
| 110 | Custom Prompts | Per-channel & per-user AI persona/instructions |
| 111 | Streaming | Stream LLM responses to chat in real-time (edit-in-place) |
| 112 | Multi-Agent | Spawn sub-agents for parallel/specialized tasks |
| 113 | Agent Profiles | Named personas (coder, assistant, creative, etc.) |
| 114 | Workflows | Multi-step automation pipelines with conditions |
| 115 | Loop Detection | Prevent infinite tool call chains |
| 116 | Thinking Mode | Extended reasoning for complex problems |
| 117 | Auto-Summarize | Compress long conversation histories |

**🟢 QUALITY OF LIFE (Phases 118-140) — Memory, Infra, UX**
Advanced memory, platform features, and polish.

| Phase | Feature | Description |
|-------|---------|-------------|
| 118 | Vector Memory | Semantic search with embeddings |
| 119 | Hybrid Search | Semantic + keyword for better recall |
| 120 | Query Expansion | Auto-generate related search queries |
| 121 | Temporal Decay | Weight recent memories higher |
| 122 | Sessions | Named sessions, switching, export |
| 123 | Hooks | Before/after middleware for tool calls |
| 124 | Plugins | User-installable skill packages |
| 125 | WebView | Browser automation tool |
| 126 | Media Pipeline | Image/audio/video processing |
| 127 | Rich Notifs | Per-channel push with quick reply |
| 128 | Biometric Lock | Fingerprint/face auth |
| 129 | Device Pairing | QR-based desktop pairing |
| 130 | Auto-Recovery | WorkManager crash watchdog |
| 131 | Config Export | Backup/restore settings as JSON |
| 132 | Theme Toggle | Manual dark/light/system switch |
| 133 | Channel Prompts | Per-channel system prompts |
| 134 | Rate Limiting | Per-user anti-abuse limits |
| 135 | Usage Tracking | Token costs per key/model |
| 136 | Approval System | Confirm dangerous tool actions |
| 137 | Conv Labels | Tag/pin conversations |
| 138 | Home Widget | Android widget for status |
| 139 | Voice Input | Transcribe voice messages |
| 140 | Group Chat | @mention-based group support |

---

## 🆕 Phase 67-82 Detail: Tool System & Skills (from ZeroClaw upstream)

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

## 🧪 Tool Playground & UI Enhancements (branch: `tools-playground` / `offline-websearch-fix`)

### Tool Playground Screen (`ui/ToolPlaygroundScreen.kt`) ✅ DONE
- Interactive screen to test any of the 36 built-in tools individually
- Per-tool toggle switches (enable/disable per playground session, seeded from ToolSystem)
- `ModelSelectorSheet` — choose which LLM model to use for playground test calls
- **PlaygroundCategory grid** — tools grouped by category for easy browsing
- Logs every tool invocation to `ZeroClawService.log()` so results appear in HomeScreen log card

### Tool Test Sheet (`ui/ToolTestSheet.kt`) ✅ DONE
- Bottom sheet form for configuring and running a single tool with custom args
- `runTool()` helper wraps `tool.execute()` with before/after logging
- Supports image picker for `ImageAnalysisTestUI`
- All tool test runs logged to HomeScreen activity feed

### AI Tools Screen (`ui/AiToolsScreen.kt`) ✅ DONE
- Browseable list of all available tools with enable/disable toggles
- Shows tool name, description, and parameter list

### Home Screen UX Improvements ✅ DONE
- **LogCard expand** — "View all N / Show less" pill toggle when log entries exceed 20; playground tool logs highlighted blue; trailing "…" pill when collapsed
- **DetailedLogCard** — second log panel below LogCard showing full `conversationLogs` (from `offline-websearch-fix` merge) — detailed LLM call traces, tool results, web search steps
- **🤖 Agents button** in TopAppBar — `Icons.Default.SmartToy` IconButton navigates to Agents screen

---

## 🤖 Phase 160+ — Agents System

### Phase 160 — Web Scraper Agent (branch: `agents-feature`) ✅ DONE
First autonomous agent type. User creates an agent that watches a URL and pushes updates to a chosen channel.

**Architecture:**
```
AgentManager (SharedPreferences)
    └── AgentConfig (id, name, type, url, interval, channel, chatId, extractPrompt, onlyOnChange)

ZeroClawService polling loop (every 60s)
    └── AgentManager.getDueAgents()
         └── WebScraperAgent.run(agent)
              ├── WebFetchTool.execute(url)           ← fetch page
              ├── LlmRouter.call(extractPrompt)       ← optional AI extraction
              ├── change detection (content hash)     ← skip if unchanged
              └── ZeroClawService.sendProactive()     ← deliver to channel
```

**Files created:**
- `agents/AgentConfig.kt` — data class for agent definition
- `agents/AgentManager.kt` — CRUD + getDueAgents() + markRun()
- `agents/WebScraperAgent.kt` — scrape → extract → detect change → deliver
- `ui/AgentsScreen.kt` — list agents (summary banner, agent cards with status/last-run)
- `ui/AgentCreateSheet.kt` — create/edit form (URL, interval presets, channel selector, extract prompt templates)
- `ZeroClawService.kt` — added agent polling coroutine alongside cron loop
- `MainActivity.kt` — added `agents` nav route
- `HomeScreen.kt` — added 🤖 SmartToy icon button in TopAppBar → Agents screen

**Supported delivery channels:** Telegram, Discord, Slack, WhatsApp, Email
**Supported intervals:** 15m / 30m / 1h / 6h / 12h / 24h (or custom, min 5m)
**Change detection:** SHA hash of fetched content — only pushes if page actually changed (configurable)
**AI extraction:** Optional extract prompt passed to LlmRouter — e.g. "extract top 5 headlines"

**UX additions (post-launch fixes):**
- **Run Now button** on each AgentCard — triggers a fresh scrape instantly, shows "Scraping…" spinner while running, refreshes card status on completion
- **Test Fetch button** in AgentCreateSheet — calls WebFetchTool inline while user is still on the create form, shows green preview card (first 600 chars + total count) on success or red error card on failure
- **Telegram chatId validation** — real-time check: if chatId contains ':' and length > 20, shows "This looks like a bot token, not a Chat ID" error inline
- **Offline model token guard** — three-layer defense against JNI SIGABRT: OfflineModelManager truncates at 2457 chars, LlmRouter budgets message vs system+history overhead, WebScraperAgent passes at most 600 chars to extraction
- **Web fetch gzip fix** — removed manual `Accept-Encoding` header so OkHttp auto-handles transparent gzip decompression

### Phase 161 — RSS Monitor Agent (planned)
Auto-detect RSS feed from URL, only push new items since last run.

### Phase 162 — Price Tracker Agent (planned)
Extract a price from a page using regex + LLM, alert only when price drops below threshold.

### Phase 163 — Social Feed Agent (planned)
Monitor Twitter/Reddit/HN via API, push trending or filtered posts.

### Phase 164 — Agent reliability fixes ✅ DONE
- **Chat ID optional for connected channels**: `AgentCreateSheet` now allows blank Chat ID when the channel is already connected (has a token configured). Field label shows "(optional)", placeholder updated. At delivery time, `WebScraperAgent` resolves blank chatId from `LlmRouter.getKnownChatIds()` (most recent conversation for that channel).
- **JNI crash prevention (MediaPipe Mutex)**: Added `kotlinx.coroutines.sync.Mutex` to `OfflineModelManager` — both `loadModel()` and `generateResponse()` are now serialized. Prevents concurrent JNI access that caused `nativeRemoveCallback` crash with "invalid global reference". Added JNI error detection in catch block that auto-destroys the engine so next call can recover.
- **Agent extraction error handling**: `WebScraperAgent.extractWithLlm()` now catches `Throwable` (not just `Exception`) to handle JNI `Error` types. Falls back gracefully to raw content.
- **Agent extraction pipeline rewrite**: `extractWithLlm()` now calls `LlmRouter.extractOnly()` — a new direct-to-model method that bypasses Pass 2 web search, tool enrichment, and chat history. Prevents agents from getting irrelevant web scraping tutorials instead of actual news. Content increased from 600→2000 chars with RSS boilerplate stripping.

---

## 🐛 Bug Log (Summary)
> Full details, root causes, and lessons in [BUGS.md](BUGS.md)

| # | Phase | Bug Summary | Status |
|---|-------|-------------|--------|
| BUG-01 | 37 | Gson null deserialization → NPE crash on `ApiKeyEntry` fields after save/reload | ✅ Fixed |
| BUG-02 | 35 | HTTP 429 treated as hard key failure — valid key permanently skipped after one rate-limit | ✅ Fixed |
| BUG-03 | 41 | Test Key always tested provider default model, not the model user actually selected | ✅ Fixed |
| BUG-04 | 55 | Stale model ID carried over when switching providers — OpenAI model sent to Anthropic | ✅ Fixed |
| BUG-05 | 68–70 | LLM response leaked raw `tool_call` JSON blocks into chat — users saw internal calls | ✅ Fixed |
| BUG-06 | 68 | DuckDuckGo search returned 0 results — raw query contained punctuation/quotes that broke URL | ✅ Fixed |
| BUG-07 | 70 | Offline model skipped Pass 2 synthesis — replied with raw tool JSON instead of natural answer | ✅ Fixed |
| BUG-08 | 83 | Online-first failover routed to offline model even when online keys were available | ✅ Fixed |
| BUG-09 | 117 | Conversation summarizer ran on Pass 2 call — corrupted context by summarizing tool results mid-response | ✅ Fixed |
| BUG-10 | — | `web_search` query not sanitized — special chars caused HTTP 400 / empty result set | ✅ Fixed |
| BUG-11 | — | Refusal detection false-positive — short AI replies flagged as refusals, triggering unnecessary retry | ✅ Fixed |
| BUG-12 | — | System date injected incorrectly — LLM received wrong year in system prompt | ✅ Fixed |
| BUG-13 | 160 | `runTool()` infinite recursion — global replace of `tool.execute(` → `runTool(tool,` also hit the call inside `runTool` itself, causing StackOverflow crash | ✅ Fixed |
| BUG-14 | 160 | Offline model JNI SIGABRT — `input_size(3386) >= maxTokens(1024)`. MediaPipe aborts the process via JNI (not catchable). Three-layer fix: OfflineModelManager guard (2457-char cap), LlmRouter budget, WebScraperAgent content cap (600 chars) | ✅ Fixed |
| BUG-15 | 160 | Web fetch returned binary garbage (`����gM`) — manually setting `Accept-Encoding: gzip` disables OkHttp's transparent decompression. Fixed by removing the header entirely | ✅ Fixed |
| BUG-16 | 160 | Telegram chatId validation — user entered bot token (`8143…:AAEe…`) as chat ID. Added real-time validation + updated placeholder text in AgentCreateSheet | ✅ Fixed |
| BUG-17 | 164 | Agent creation blocked when chatId blank even though channel is connected. Validation now skips chatId requirement for connected channels | ✅ Fixed |
| BUG-18 | 164 | JNI `nativeRemoveCallback` crash — MediaPipe LlmInference not thread-safe, concurrent agent + chat calls corrupted JNI global reference. Fixed with Mutex serialization + auto-destroy on JNI error | ✅ Fixed |
| BUG-19 | 164 | Agent extraction returned web scraping tutorials instead of news — `router.call()` triggered Pass 2 web search with extraction prompt as query. Fixed by adding `LlmRouter.extractOnly()` that bypasses Pass 2 entirely | ✅ Fixed |
| BUG-20 | 164 | RSS content truncated to 600 chars — only got XML copyright header, zero headlines. Increased to 2000 chars with boilerplate stripping | ✅ Fixed |
| BUG-21 | 165 | Agent proactive messages not delivered to Telegram — chatId stored as non-numeric `"g"`, `toLongOrNull()` failed silently. Fixed with 3-tier chatId resolution + TelegramBotManager persisted last chat + seed from API on start | ✅ Fixed |

---

### Phase 165 — Multi-channel agent delivery + template improvements ✅ DONE
- **Bots + Channels delivery UI**: Completely redesigned the Delivery section in `AgentCreateSheet` with two sub-sections:
  - **Bots**: Shows all bots configured in Settings (Telegram, Discord, Slack, WhatsApp, Signal) with checkboxes. Connected bots shown with green "Connected" badge. All connected bots auto-checked by default. No chat ID required — auto-resolves to bot's last known chat.
  - **Channels**: Add specific chat/channel targets with IDs (e.g., specific Telegram group, Discord channel ID). Inline form with channel selector, chat ID input, known ID suggestions, and validation.
- **Multi-channel delivery in WebScraperAgent**: Agent now delivers to ALL selected bots + channel targets in a single run. Status shows which channels succeeded/failed. Backward-compatible with legacy single-channel agents.
- **Data model (backward-compatible)**: `channel` field stores comma-separated bot names (`"telegram,discord"`), `chatId` field stores `channel:id` pairs for specific targets (`"discord:123,slack:C456"`). Old single-channel values still work via legacy fallback path.
- **TelegramBotManager seed**: On first polling start, fetches the most recent update from Telegram API (`offset=-1`) to seed the bot's chat ID immediately — agents can deliver without waiting for a new message. Chat ID persisted to SharedPreferences and restored on app restart.
- **AgentsScreen**: Agent card now shows multiple channel chips with horizontal scroll, plus channel target count badge.
- **Template category system fix**: Added `{query}` placeholder to all template URLs and extractPrompts. Categories now produce different results (e.g., "Technology" vs "AI & Machine Learning" in Latest News fetches different content). Live-updates URL, extractPrompt, and agent name as user toggles categories in `AgentCreateSheet`.
- **LLM extraction "no real-time access" fix**: LLM was refusing to extract data from fetched content (saying "I don't have access to real-time data"). Fixed by updating system prompt in `LlmRouter.extractOnly()` to explicitly state "this data was JUST FETCHED from a live website" and wrapping content with `BEGIN/END FETCHED CONTENT` markers in `WebScraperAgent`. Increased content snippet from 1000→2500 chars.
- **Gold Price Tracker template**: Expanded from single USD gold price to 17 sub-categories including: Gold (India INR/gram, INR/10g, USD/oz, GBP, EUR, AED), Silver (India INR/kg, USD/oz), Copper, Platinum, Palladium, Crude Oil (WTI + Brent), Natural Gas, Aluminium, Zinc, Nickel. Uses Google Search with `{query}` so each metal/country gets its own specific search.
- **GitHub Actions workflow**: Added `build-and-release.yml` — builds debug + release APK, creates GitHub Release with artifacts, supports signed/unsigned builds, manual dispatch.

---

### Phase 166 — Free API Agents System ✅ DONE
> **Branch:** `agents-api-integration`
> **Goal:** Research all free APIs that agents can use, document rate limits, and integrate them as proper agent data sources. Replace Google Search scraping with direct APIs where possible for more reliable data.
>
> **Implemented:**
> - Created `agents/api/` package with 10 free API data sources:
>   - **CoinGecko** — Crypto prices (30 req/min, no key)
>   - **CoinCap** — Alternative crypto data (200 req/min, no key)
>   - **Metals.live** — Gold, silver, platinum, palladium spot prices (no key)
>   - **ExchangeRate-API** — Currency exchange rates (~50 req/day free, no key)
>   - **USGS Earthquake** — Earthquake data (unlimited, public government API)
>   - **MFAPI.in** — Indian mutual fund NAV data (unlimited, no key)
>   - **GitHub Trending** — Trending repos via search API (60 req/hr unauthenticated)
>   - **wttr.in** — Weather data (unlimited, no key)
>   - **Football-Data.org** — Football/soccer scores & standings (10 req/min free)
>   - **Numbers API** — Fun number/date facts (unlimited)
> - Created `ApiDataSource` interface + `ApiResult` + `ApiRateLimit` data classes
> - Created `ApiRateLimiter` — per-API sliding window rate limit tracker
> - Created `FreeApiRegistry` — maps template IDs to API sources, handles rate checking
> - Created `HttpUtil.kt` — shared HTTP GET helper for all API sources
> - Updated `AgentConfig` with `apiSource` field
> - Updated `AgentManager.createWebScraper()` to accept `apiSource`
> - Updated `AgentTemplate` with `apiSource` and `apiRateNote` fields
> - Updated 8 existing templates with free API sources (crypto, gold, forex, weather, sports, earthquake, GitHub trending, mutual funds)
> - Updated `WebScraperAgent.run()`: tries direct API first → falls back to web scraping + LLM extraction if API fails/rate-limited
> - Updated `AgentCreateSheet` — shows green API badge with rate limit info
> - Updated `AgentsScreen` — agent cards show ⚡API indicator for API-powered agents
> - All API data sources return pre-formatted text → NO LLM extraction needed → faster, cheaper, more reliable

**Research checklist — find free (unlimited or generous rate-limit) APIs for:**

| Agent | API Candidate | Rate Limit | Notes |
|-------|--------------|------------|-------|
| **Gold/Commodities** | GoldAPI.io, Metals.live, MetalpriceAPI | ? | Need: gold, silver, copper, platinum by country/currency |
| **Crypto Prices** | CoinGecko API (free), CoinCap, Binance public | 30 req/min (CoinGecko) | BTC, ETH, SOL, XRP etc. |
| **Stock/Index** | Yahoo Finance API, Alpha Vantage (free tier), Twelve Data | 5 req/min (Alpha Vantage) | NIFTY, SENSEX, S&P 500 etc. |
| **Currency/Forex** | ExchangeRate-API, Fixer.io (free), Open Exchange Rates | 1500/month (ExchangeRate) | USD/INR, EUR/USD etc. |
| **Weather** | wttr.in (unlimited), OpenWeatherMap (free 1000/day) | Unlimited / 1000/day | Already using wttr.in |
| **News** | NewsAPI.org (free 100/day), GNews.io, Currents API | 100/day (NewsAPI) | Category-filtered news |
| **Earthquake/Disaster** | USGS GeoJSON (unlimited, public) | Unlimited | Already using this |
| **Fuel Prices** | collectAPI, government open data portals | ? | India-specific needed |
| **IPO/Stock Events** | Finnhub (free tier), SEC EDGAR | 60 req/min (Finnhub) | IPO calendar, earnings |
| **GitHub Trending** | GitHub REST API (unauthenticated 60/hr, auth 5000/hr) | 60-5000/hr | Trending repos |
| **Sports Scores** | ESPN API (unofficial), CricAPI, Football-Data.org | Varies | Cricket, football, F1 |
| **Mutual Funds** | MFAPI.in (India, free, unlimited) | Unlimited | NAV data for Indian MFs |
| **Flights** | Skyscanner API, Kiwi.com Tequila | ? | Price tracking |
| **Movies/TV** | TMDb API (free, unlimited) | 40 req/10s | New releases, trending |
| **YouTube Trending** | YouTube Data API v3 (free 10k units/day) | 10k units/day | Trending videos |

**Implementation plan:**
1. Create `agents/api/` package with one data source class per API
2. Each data source: `suspend fun fetch(params: Map<String,String>): ApiResult`
3. Add API key fields in Settings for APIs that need keys (most are free/no-key)
4. `WebScraperAgent` gets a new path: if agent template has `apiSource` field → use direct API instead of web scrape + LLM extraction
5. Add rate limit tracking per API: store last call timestamp, enforce minimum interval
6. Add condition notes in agent creation UI: "This API allows 1 fetch per day" / "100 requests per day" etc.
7. Fallback: if API fails or rate-limited → fall back to current Google Search scraping method

**Conditions to document per API:**
- Free tier limits (requests per minute/hour/day/month)
- Whether API key is required (and how to get one)
- Data freshness (real-time vs 15-min delay vs daily)
- Geographic coverage (global vs India-only vs US-only)

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
d
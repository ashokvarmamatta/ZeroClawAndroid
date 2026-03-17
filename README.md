<div align="center">

<img src="screenshots/app_icon.png" width="120" alt="ZeroClaw Icon" />

# ZeroClaw Android

**A 24/7 AI agent daemon for Android — 11 messaging channels, 30 built-in AI tools, advanced AI orchestration, vector memory, full infrastructure, and polished configuration & UX. Runs entirely on your phone.**

[![Android](https://img.shields.io/badge/Platform-Android%2026%2B-green?logo=android)](https://developer.android.com)
[![Kotlin](https://img.shields.io/badge/Language-Kotlin-purple?logo=kotlin)](https://kotlinlang.org)
[![Compose](https://img.shields.io/badge/UI-Jetpack%20Compose-blue?logo=jetpack-compose)](https://developer.android.com/compose)
[![License](https://img.shields.io/badge/License-MIT-yellow)](LICENSE)

</div>

---

## 📖 What is ZeroClaw?

ZeroClaw is an **Android-native AI agent daemon** that turns your phone into an always-on AI backend. It runs as a foreground service across **11 messaging channels**, with **30 built-in AI tools**, **advanced AI orchestration** (streaming, multi-agent, workflows, thinking mode), **vector memory with RAG**, a **complete infrastructure platform** (hooks, plugins, biometric lock, device pairing, auto-recovery), and a fully polished **configuration & UX layer** — export/import config, custom themes, per-channel prompts, rate limiting, usage tracking, an approval system, conversation labels, a home screen widget, voice input, and group chat support.

No cloud subscription. No always-on PC. Just your Android device.

```
You → 11 messaging channels (Telegram / Slack / Matrix / Discord / Teams / ...)
              ↓
    ZeroClaw Service (background daemon)
              ↓
    Config & UX: ThemeManager → RateLimiting → ApprovalSystem → ConversationLabels
              ↓
    Infrastructure: HooksSystem → PluginSystem → BiometricLock → AutoRecovery
              ↓
    Advanced AI: SystemPromptManager → MultiAgent → WorkflowEngine → ThinkingMode
              ↓
    Vector Memory: VectorMemory → HybridSearch+RRF → QueryExpansion → SessionManager
              ↓
    Tool System (30 tools) → LLM Router → Reply
```

---

## 📸 Screenshots

| Home | Settings | AI Configuration |
|------|----------|------------------|
| ![Home](screenshots/01_home.png) | ![Settings](screenshots/02_settings.png) | ![AI Config](screenshots/03_ai_config.png) |

| AI Config (Model Selection) | Info & Setup Guide |
|-----------------------------|-------------------|
| ![AI Config Scrolled](screenshots/04_ai_config_scrolled.png) | ![Info Guide](screenshots/05_info_guide.png) |

---

## ✨ Features

### ⚙️ Configuration & UX (Phases 131-140)

#### Phase 131 — ExportImportConfig
- **Full config backup** — export all API keys, channel credentials, system prompts, agent profiles, tool settings, and app preferences to a single encrypted JSON file
- **One-tap restore** — import the config file on a new device or after reinstall to instantly restore the full setup
- **Selective export** — choose which categories to include (keys only, channels only, full backup)
- Config files are AES-256 encrypted with a user-set passphrase before being written to storage
- Export shares via Android share sheet (save to Drive, send via email, etc.)

#### Phase 132 — ThemeManager
- **Custom color themes** — choose from 10 built-in Material You palettes or create a fully custom theme with your own primary/secondary/background colors
- **Dynamic color** — optionally follow Android 12+ wallpaper-based dynamic color
- **Dark / Light / System theme** — manual override independent of system setting
- Per-theme typography scale (compact, standard, large for accessibility)
- Theme preferences persist across app restarts; export/import includes theme config

#### Phase 133 — PerChannelPrompts UI
- **Dedicated UI** for configuring per-channel and per-user system prompts (exposes the Phase 110 SystemPromptManager through a first-class Settings screen)
- **Prompt editor** with syntax highlighting, variable picker (`{{username}}`, `{{channel}}`, `{{date}}`), and live character count
- **Template gallery** — browse and apply built-in prompt templates (Assistant, Coder, Analyst, Translator, Creative Writer)
- Per-channel prompts shown on the main Settings screen for quick discovery

#### Phase 134 — RateLimiting
- **Per-user rate limits** — configure maximum messages per user per time window (e.g., 10 messages/hour)
- **Per-channel limits** — set channel-wide throughput caps to prevent overload
- **Soft limits** — warn users when approaching their limit, hard-block when exceeded
- Rate limit state persists in Room DB; resets automatically at window expiry
- Admin users (configurable by user ID) are exempt from rate limits
- `/ratelimit status` command lets users check their remaining quota

#### Phase 135 — UsageTracking
- **Per-key usage stats** — tracks call count, token count, success rate, average latency, and last-used timestamp for every API key
- **Per-user stats** — message count and tool invocation count per user per channel
- **Usage dashboard** — new Settings screen showing charts for daily/weekly usage, top users, most-used tools
- Token cost estimation based on per-provider pricing tables (configurable)
- Data exported as CSV in the ExportImportConfig backup

#### Phase 136 — ApprovalSystem
- **Human-in-the-loop** — flag specific tool calls or LLM actions for manual approval before execution
- Configurable approval triggers: tools with side effects (Email, Calendar write, SmartHome), messages above a token threshold, or all actions in a high-security mode
- **Approval notifications** — pending approvals appear as Android notifications with Approve/Deny actions directly in the shade
- Approval decisions are logged with timestamp and approver identity
- Timeout behavior: auto-approve, auto-deny, or hold indefinitely (configurable)

#### Phase 137 — HomeWidget
- **Android home screen widget** — place on any launcher home screen
- Shows: service status (Running/Stopped), active channel count, last message timestamp, and total messages today
- **Quick actions** — Start/Stop service directly from the widget without opening the app
- Resizable: 2×1 (compact status only) and 4×2 (full stats + quick actions)
- Widget updates every 60 seconds via WorkManager

#### Phase 138 — VoiceInput
- **Voice-to-text input** in the WebChat channel — users can hold a microphone button and speak; message is transcribed via SpeechToText (Whisper) and sent as text
- **TTS playback toggle** — users can request AI responses to be read aloud via TextToSpeech in any channel that supports audio output
- Wake word detection (optional) — "Hey ZeroClaw" activates voice input in WebChat without pressing a button
- Voice input settings: language selection, Whisper model size, silence detection threshold

#### Phase 139 — ConversationLabels
- **Label any conversation** — tag conversations with colored labels (Work, Personal, Project X, Urgent, etc.)
- Labels stored per channel+userId; visible in a conversations list view in the app
- **Filter by label** — view all conversations with a specific label across channels
- **Auto-label rules** — keyword-triggered auto-labeling (e.g., messages containing "invoice" → label "Finance")
- Labels included in session summaries and searchable via the Memory tool

#### Phase 140 — GroupChatSupport
- **Telegram group support** — bot can be added to group chats and responds to @mentions or configured trigger words
- **Discord server channels** — responds to messages in any text channel the bot has access to, with optional `@ZeroClaw` mention requirement
- **Slack channel posting** — responds to messages in channels as well as DMs
- **Group context isolation** — per-group conversation history separate from private chat history
- **Admin commands** in groups: `/group prompt <text>` to set a group-specific system prompt; `/group ratelimit <n>` to set group-wide message limits
- **Thread awareness** — in Discord and Slack, replies are posted in-thread to keep group chats clean

### 🏗️ Infrastructure & Platform (Phases 123-130)
- **HooksSystem** — pre/post-message hook pipeline (filter, transform, notify, log)
- **PluginSystem** — user-installable sandboxed plugin packages (.zip import)
- **WebViewTool + MediaPipelineTool** — headless WebView scraping + media transcoding
- **RichNotifications + QuickReply** — rich Android notifications with reply-from-shade
- **BiometricLock** — fingerprint/face authentication guard with configurable timeout
- **DevicePairing** — multi-device mDNS discovery + encrypted config sync
- **AutoRecovery** — watchdog, crash reporter, circuit breaker, dead-letter queue
- **Platform hardening** — Doze awareness, exponential backoff, metrics endpoint

### 🔮 Vector Memory & RAG (Phases 118-122)
- **VectorMemory** — embedding-based semantic memory (OpenAI or local sentence-transformer)
- **HybridSearch + RRF** — BM25 + cosine similarity fused with Reciprocal Rank Fusion
- **QueryExpansion** — LLM query variants + HyDE for higher precision recall
- **TemporalDecay** — exponential memory freshness scoring with reinforcement
- **SessionManager** — session tracking, summaries, cross-session recall

### 🧠 Advanced AI Systems (Phases 110-117)
- **SystemPromptManager** — per-channel/user prompts with templates
- **StreamingResponse** — token-level streaming with typing indicators
- **MultiAgent** — pipeline orchestration (linear, fan-out, fan-in, conditional)
- **AgentProfiles** — named personas with per-profile tool/model config
- **WorkflowEngine** — visual multi-step workflow composer
- **ToolLoopDetector** — infinite loop prevention with auto-recovery
- **ThinkingMode** — extended reasoning (Claude extended thinking, OpenAI o1/o3)
- **ConversationSummarizer** — automatic context compression

### 💬 11 Messaging Channels (Phases 103-109)
- **Telegram** (+ group chat support via Phase 140)
- **WhatsApp** (Twilio)
- **Discord** (+ server channel support via Phase 140)
- **Signal**
- **Slack** (+ channel posting via Phase 140)
- **Matrix** — federated Matrix protocol client
- **IRC** — classic IRC bot via TCP socket
- **Microsoft Teams** — Bot Framework integration
- **Twitch** — Twitch Chat bot with !command support
- **LINE** — LINE Messaging API
- **WebChat** — built-in browser-accessible WebSocket chat (+ voice input via Phase 138)

### 🔧 AI Tools — 30 Built-in Tools

#### Core Tools (10)
Web Search (DuckDuckGo), Web Fetch, Memory (vector-backed), PDF Reader, Image Analysis, Cron/Scheduled Tasks, Status/Diagnostics, GitHub, Notion, Email

#### Extended Toolbox (18 — Phases 85-102)
Summarize, Translate (50+ languages), ImageGen (Pollinations + DALL-E), SpeechToText (Whisper), TextToSpeech (Android TTS), Calendar, Contacts, Location/Geocoding, Calculator, RSS, QR Code, FileManager, Clipboard, Spotify, SmartHome (Home Assistant), BraveTool, Bookmark

#### Infrastructure Tools (2 — Phase 125)
WebViewTool (headless JS-rendered scraping), MediaPipelineTool (media download + transcode)

### 🔑 Multi-Provider API Key Manager
- Unlimited keys, cURL import, live key testing, Gemini model picker
- Priority reordering, per-model selection, Set Active key, auto failover
- **Usage stats** per key (Phase 135) — call count, token usage, success rate

### 📱 Native Android UI (Material Design 3)
- Custom themes (Phase 132), export/import config (Phase 131)
- Home screen widget (Phase 137), biometric lock (Phase 127)
- Rich notifications with quick-reply (Phase 126)
- Usage dashboard, approval system, conversation labels

---

## 🏗️ Architecture

```
app/src/main/java/ai/zeroclaw/android/
│
├── MainActivity.kt
│
├── ui/
│   ├── HomeScreen.kt                    # Dashboard + usage stats + widget data
│   ├── ApiKeysScreen.kt                 # Key manager + per-key usage stats
│   ├── SettingsScreen.kt                # All settings inc. themes, prompts, rate limits
│   ├── InfoScreen.kt + InfoData.kt
│   ├── UsageDashboardScreen.kt          # Phase 135 — charts and stats
│   ├── ApprovalScreen.kt               # Phase 136 — pending approvals queue
│   ├── ConversationLabelsScreen.kt      # Phase 139 — label management
│   ├── PluginManagerScreen.kt           # Phase 124 — installed plugins
│   ├── DevicePairingScreen.kt           # Phase 128 — paired devices
│   └── theme/
│       └── ThemeManager.kt              # Phase 132 — custom color themes
│
├── data/
│   ├── ApiKeyEntry.kt, LlmKeyManager.kt, LlmRouter.kt
│   ├── OfflineModelManager.kt, AppSettings.kt
│   ├── MessageDatabase.kt, MemoryDatabase.kt
│   ├── UsageDatabase.kt                 # Phase 135 — per-key and per-user stats
│   └── LabelDatabase.kt                 # Phase 139 — conversation label store
│
├── config/                              # Phase 131-140
│   ├── ExportImportConfig.kt            # AES-encrypted full config backup/restore
│   ├── RateLimiter.kt                   # Per-user and per-channel rate limiting
│   ├── UsageTracker.kt                  # API call tracking and cost estimation
│   ├── ApprovalSystem.kt                # Human-in-the-loop action approval
│   ├── ConversationLabels.kt            # Label CRUD and auto-label rules
│   ├── HomeWidget.kt                    # Android AppWidgetProvider (2×1 + 4×2)
│   ├── VoiceInput.kt                    # WebChat voice-to-text + TTS playback
│   └── GroupChatSupport.kt              # Group context, @mention, admin commands
│
├── infra/                               # Phase 123-130
│   ├── HooksSystem.kt, PluginSystem.kt
│   ├── RichNotifications.kt, BiometricLock.kt
│   ├── DevicePairing.kt, AutoRecovery.kt
│   └── PlatformHardening.kt
│
├── memory/                              # Phase 118-122
│   ├── VectorMemory.kt, HybridSearch.kt
│   ├── QueryExpansion.kt, TemporalDecay.kt
│   └── SessionManager.kt
│
├── intelligence/                        # Phase 110-117
│   ├── SystemPromptManager.kt, StreamingResponse.kt
│   ├── MultiAgent.kt, AgentProfiles.kt
│   ├── WorkflowEngine.kt, ToolLoopDetector.kt
│   ├── ThinkingMode.kt, ConversationSummarizer.kt
│
├── tools/
│   ├── ToolSystem.kt
│   ├── [30 tool files — WebSearch through MediaPipelineTool]
│
├── service/
│   ├── ZeroClawService.kt
│   └── BootReceiver.kt
│
├── telegram/, whatsapp/, discord/, signal/
├── slack/, matrix/, irc/, teams/, twitch/, line/, webchat/
└── tunnel/
    └── TunnelManager.kt
```

---

## 🚀 Getting Started

### Prerequisites
- Android Studio Hedgehog or newer
- Android device or emulator running **Android 8.0 (API 26)+**
- At least one LLM API key (OpenAI, Gemini, Anthropic, etc.)
- (Recommended) Android 12+ for Dynamic Color theming
- (Optional) OpenAI API key for high-quality embeddings

### Build & Run

```bash
git clone https://github.com/ashokvarmamatta/ZeroClawAndroid.git
cd ZeroClawAndroid
# Open in Android Studio → File → Open → ZeroClawAndroid
# Wait for Gradle sync, then click ▶ Run
```

### First-Time Setup

1. Tap **ℹ️** on the home screen for the full setup walkthrough
2. Go to **Settings → Manage API Keys** → **+ Add Online Key**
3. Add your LLM key and tap **Test Key**, then **Check All Models**
4. Select which models to use and optionally enable Google Search Grounding
5. Set your preferred **Theme** in Settings → Appearance
6. Configure **Per-Channel Prompts** for each messaging platform
7. Add channel credentials (Telegram token, Slack token, etc.)
8. (Optional) Enable **BiometricLock**, **Device Pairing**, and **Rate Limiting**
9. Add the **Home Screen Widget** from your launcher's widget picker
10. Tap **▶ Start** — the full ZeroClaw platform is now running

### Export / Restore Config

```bash
# In-app: Settings → Export Config → choose categories → set passphrase → share
# Restore: Settings → Import Config → select file → enter passphrase
```

### Using Group Chats

```
# Telegram group: add your bot, then:
@YourBot what's the weather today?

# Discord server: invite bot, then in any channel:
@ZeroClaw summarize this thread

# Admin commands (group admins only):
/group prompt You are a concise technical assistant for our engineering team.
/group ratelimit 20
```

---

## 🔑 Supported LLM Providers

| Provider | Auth | Default Base URL | Notes |
|---|---|---|---|
| **OpenAI** | Bearer | `https://api.openai.com/v1` | GPT-4o, o1, o3-mini; Whisper; DALL-E; embeddings |
| **Google Gemini** | API key | `https://generativelanguage.googleapis.com/v1beta` | Streaming, Google Search grounding, model picker |
| **Anthropic Claude** | x-api-key | `https://api.anthropic.com/v1` | Extended thinking (claude-3-7-sonnet) |
| **OpenRouter** | Bearer | `https://openrouter.ai/api/v1` | 400+ models from all providers |
| **Ollama** | None | `http://127.0.0.1:11434` | Local models on device |
| **Offline** | None | On-device | MediaPipe `.bin` models, no internet needed |
| **Custom endpoint** | Bearer | *(your Base URL)* | Modal, LiteLLM, vLLM, any OpenAI-compatible API |

---

## 📦 Tech Stack

| Layer | Technology |
|---|---|
| Language | Kotlin |
| UI | Jetpack Compose + Material Design 3 + Dynamic Color |
| Background | Android Foreground Service + WorkManager (watchdog + widget updates) |
| HTTP | OkHttp + Retrofit + SSE streaming |
| WebView | Android WebView (headless) |
| Storage | Room (messages + vectors + sessions + usage + labels) + DataStore |
| Vector Search | BM25 + cosine similarity + RRF (on-device) |
| Embeddings | OpenAI `text-embedding-3-small` / local sentence-transformer |
| Security | BiometricPrompt + AES-256-GCM (config export + device pairing) |
| Notifications | NotificationCompat + RemoteInput (quick-reply) |
| Widget | AppWidgetProvider + RemoteViews |
| Plugins | Custom ClassLoader sandbox |
| Serialization | Gson |
| Navigation | Jetpack Navigation Compose |
| Offline AI | MediaPipe LlmInference |
| Image Gen | Pollinations.ai (free) / DALL-E 3 |
| Speech | OpenAI Whisper (STT) + Android TTS + wake word detection |
| Messaging | 11 channel integrations |
| Tunnel | Cloudflare Tunnel / ngrok |

---

## 🛣️ Roadmap — Complete Feature Set

All phases are implemented on this branch.

### ✅ Core Foundation
- [x] Multi-provider API key manager with unlimited keys ✅
- [x] cURL import mode ✅
- [x] Live key testing + Gemini model picker ✅
- [x] Per-model testing and selection ✅
- [x] Priority reordering and Set Active key ✅
- [x] Auto failover (waterfall) ✅
- [x] Google Search grounding (Gemini) ✅
- [x] Offline mode (MediaPipe `.bin` models) ✅
- [x] Live log viewer ✅
- [x] Cloudflare Tunnel / ngrok integration ✅
- [x] Starts on device reboot (BootReceiver) ✅
- [x] Native Material Design 3 UI ✅

### ✅ Core AI Tools (10 tools)
- [x] Web Search (DuckDuckGo) ✅
- [x] Web Fetch (URL + HTML extraction) ✅
- [x] Memory (persistent per-user, Room/SQLite) ✅
- [x] PDF Reader (local, URI, remote URL) ✅
- [x] Image Analysis (vision models) ✅
- [x] Cron / Scheduled Tasks ✅
- [x] Status / Diagnostics ✅
- [x] GitHub (search, READMEs, issues) ✅
- [x] Notion (search, read, create, append) ✅
- [x] Email (SendGrid / Mailgun) ✅

### ✅ Extended Toolbox — Phases 85-102 (18 tools)
- [x] SummarizeTool — extractive summarization ✅
- [x] TranslateTool — 50+ languages, MyMemory API ✅
- [x] ImageGenTool — Pollinations.ai + DALL-E 3 ✅
- [x] SpeechToTextTool — OpenAI Whisper transcription ✅
- [x] TextToSpeechTool — Android TTS engine ✅
- [x] CalendarTool — Android calendar events ✅
- [x] ContactsTool — Android contacts lookup ✅
- [x] LocationTool — GPS + reverse geocoding ✅
- [x] CalculatorTool — math expression evaluator ✅
- [x] RssTool — RSS/Atom feed fetcher ✅
- [x] QrCodeTool — QR generate + decode ✅
- [x] FileManagerTool — app storage file ops ✅
- [x] ClipboardTool — Android clipboard ✅
- [x] SpotifyTool — Spotify playback control ✅
- [x] SmartHomeTool — Home Assistant integration ✅
- [x] BraveTool — Brave Search API ✅
- [x] BookmarkTool — URL bookmark manager ✅

### ✅ Messaging Channels — Phases 103-109
- [x] Slack Bot (Events API) ✅
- [x] Matrix Bot (federated protocol) ✅
- [x] IRC Bot (TCP socket) ✅
- [x] Microsoft Teams Bot (Bot Framework) ✅
- [x] Twitch Chat Bot (IRC/TMI) ✅
- [x] LINE Bot (Messaging API) ✅
- [x] WebChat (built-in WebSocket server) ✅

### ✅ Advanced AI Systems — Phases 110-117
- [x] SystemPromptManager — per-channel/user prompts + templates ✅
- [x] StreamingResponse — token-level streaming + typing indicators ✅
- [x] MultiAgent — pipeline orchestration with handoff protocol ✅
- [x] AgentProfiles — named personas with tool/model config ✅
- [x] WorkflowEngine — visual multi-step workflow composer ✅
- [x] ToolLoopDetector — infinite loop prevention ✅
- [x] ThinkingMode — extended reasoning (Claude + o1/o3) ✅
- [x] ConversationSummarizer — auto context compression ✅

### ✅ Vector Memory & RAG — Phases 118-122
- [x] VectorMemory — embedding-based semantic store ✅
- [x] HybridSearch + RRF — BM25 + vector fusion ✅
- [x] QueryExpansion — LLM variants + HyDE ✅
- [x] TemporalDecay — exponential memory freshness ✅
- [x] SessionManager — session tracking + cross-session recall ✅

### ✅ Infrastructure & Platform — Phases 123-130
- [x] HooksSystem — pre/post-message pipeline ✅
- [x] PluginSystem — user-installable sandboxed plugins ✅
- [x] WebViewTool + MediaPipelineTool ✅
- [x] RichNotifications + QuickReply ✅
- [x] BiometricLock ✅
- [x] DevicePairing — multi-device mDNS + encrypted sync ✅
- [x] AutoRecovery — watchdog + crash reporter + circuit breaker ✅
- [x] Platform hardening — Doze, backoff, metrics endpoint ✅

### ✅ Configuration & UX — Phases 131-140
- [x] ExportImportConfig — AES-encrypted full config backup/restore ✅
- [x] ThemeManager — 10+ palettes, dark/light/system, dynamic color ✅
- [x] PerChannelPrompts UI — first-class prompt editor with template gallery ✅
- [x] RateLimiting — per-user and per-channel message rate limits ✅
- [x] UsageTracking — token usage, call stats, cost estimation, dashboard ✅
- [x] ApprovalSystem — human-in-the-loop with notification approve/deny ✅
- [x] ConversationLabels — colored labels, auto-label rules, cross-channel filter ✅
- [x] HomeWidget — launcher widget with service status + quick start/stop ✅
- [x] VoiceInput — WebChat mic input + TTS playback + wake word ✅
- [x] GroupChatSupport — Telegram/Discord/Slack groups with @mention + admin commands ✅

---

## 🤝 Contributing

Contributions are welcome! Here's how to get started:

1. Fork the repo
2. Create a feature branch: `git checkout -b feature/my-feature`
3. Commit your changes: `git commit -m 'Add my feature'`
4. Push: `git push origin feature/my-feature`
5. Open a Pull Request

Please open an issue first for large changes so we can discuss the approach.

---

## 📄 License

This project is licensed under the MIT License — see the [LICENSE](LICENSE) file for details.

---

## 🙏 Acknowledgements

### Built on ZeroClaw
This Android app is built on top of the [**ZeroClaw**](https://github.com/zeroclaw-labs/zeroclaw) project by [ZeroClaw Labs](https://github.com/zeroclaw-labs). ZeroClaw Android extends it into a production-grade, fully self-hosted AI agent platform — 11 messaging channels, 30 AI tools, advanced AI orchestration, vector memory with RAG, complete infrastructure platform, and polished configuration & UX. All running on your Android device.

### Libraries & Services
- [Telegram Bot API](https://core.telegram.org/bots/api) — [Twilio](https://www.twilio.com) — [Discord Gateway](https://discord.com/developers/docs/topics/gateway)
- [Slack API](https://api.slack.com) — [Matrix Spec](https://spec.matrix.org) — [Microsoft Bot Framework](https://dev.botframework.com)
- [Twitch TMI](https://dev.twitch.tv) — [LINE Messaging API](https://developers.line.biz)
- [OpenAI API](https://platform.openai.com) (GPT + Whisper + DALL-E + Embeddings)
- [Google Gemini API](https://ai.google.dev) — [Anthropic API](https://docs.anthropic.com)
- [OpenRouter](https://openrouter.ai) — [Ollama](https://ollama.com)
- [MediaPipe](https://ai.google.dev/edge/mediapipe/solutions/genai/llm_inference)
- [Pollinations.ai](https://pollinations.ai) — free AI image generation
- [Brave Search API](https://brave.com/search/api/) — [MyMemory Translation](https://mymemory.translated.net)
- [Cloudflare Tunnel](https://developers.cloudflare.com/cloudflare-one/connections/connect-networks/)
- [Android BiometricPrompt](https://developer.android.com/training/sign-in/biometric-auth)
- [WorkManager](https://developer.android.com/topic/libraries/architecture/workmanager)
- [Jetpack Compose](https://developer.android.com/compose) — [Material Design 3](https://m3.material.io)
- [Room](https://developer.android.com/training/data-storage/room) — [DataStore](https://developer.android.com/topic/libraries/architecture/datastore)
- [OkHttp](https://square.github.io/okhttp/) — [Retrofit](https://square.github.io/retrofit/)

---

<div align="center">
Made with ❤️ — built to run on your pocket supercomputer
</div>

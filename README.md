<div align="center">

<img src="screenshots/app_icon.png" width="120" alt="ZeroClaw Icon" />

# ZeroClaw Android

**A 24/7 AI agent daemon for Android — 11 messaging channels, 30 built-in AI tools, advanced AI orchestration, vector memory, and full infrastructure. Runs entirely on your phone.**

[![Android](https://img.shields.io/badge/Platform-Android%2026%2B-green?logo=android)](https://developer.android.com)
[![Kotlin](https://img.shields.io/badge/Language-Kotlin-purple?logo=kotlin)](https://kotlinlang.org)
[![Compose](https://img.shields.io/badge/UI-Jetpack%20Compose-blue?logo=jetpack-compose)](https://developer.android.com/compose)
[![License](https://img.shields.io/badge/License-MIT-yellow)](LICENSE)

</div>

---

## 📖 What is ZeroClaw?

ZeroClaw is an **Android-native AI agent daemon** that turns your phone into an always-on AI backend. It operates across **11 messaging channels**, with **30 built-in AI tools**, **advanced AI orchestration**, **vector memory with RAG**, and now a complete **infrastructure and platform layer** — an event-driven hooks system, a user-installable plugin system, a WebView browser tool, a media pipeline tool, rich notifications with quick-reply, biometric lock, device pairing for multi-device sync, and automatic crash recovery. Everything runs on your phone.

```
You → 11 messaging channels
              ↓
    ZeroClaw Service (background daemon)
              ↓
    Infrastructure: HooksSystem → PluginSystem → AutoRecovery
              ↓
    Advanced AI: StreamingResponse → MultiAgent → WorkflowEngine → ThinkingMode
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

### 🏗️ Infrastructure & Platform (Phases 123-130)

#### Phase 123 — HooksSystem
- **Pre/post message hooks** — intercept every incoming message and outgoing reply with a configurable hook pipeline
- Hook types: **Filter** (block/allow), **Transform** (modify content), **Notify** (side-effect), **Log** (structured audit trail)
- Hooks are ordered by priority and chained; a hook can short-circuit the pipeline
- Built-in hooks: profanity filter, message length limiter, PII redactor, structured logger
- **Hook SDK** — developers can write custom hooks as Kotlin classes and load them via the plugin system

#### Phase 124 — PluginSystem
- **User-installable plugins** — import `.zip` plugin packages from the file picker
- Each plugin declares: name, version, hooks registered, tools provided, and required permissions
- **Plugin manager UI** — list installed plugins, enable/disable per-plugin, view manifest
- Plugins are sandboxed in a separate ClassLoader; they cannot access core app internals directly
- Hot-load/unload without restarting the service
- Example plugins: custom LLM provider adapter, new tool integrations, channel-specific formatters

#### Phase 125 — WebViewTool + MediaPipelineTool
- **WebViewTool** — headless Android WebView that can load any URL, execute JavaScript, and extract rendered DOM content; enables scraping JS-rendered pages that WebFetch cannot handle
- **MediaPipelineTool** — download, transcode, and process media files (images, audio, video); resize images, convert audio to MP3, extract thumbnails from video; integrates with SpeechToText and ImageAnalysis tools
- Both tools added to the tool registry and available via chat commands

#### Phase 126 — RichNotifications + QuickReply
- **Rich notifications** — incoming messages trigger persistent Android notifications with sender name, channel icon, and message preview
- **Quick-reply action** — reply directly from the notification shade without opening the app
- **Notification channels** — separate Android notification channels per messaging platform (Telegram, Slack, etc.) for granular Do Not Disturb control
- **Bundled notifications** — multiple messages from the same conversation grouped into one notification
- Long-press on notification shows action buttons: Reply, Clear History, Mute User

#### Phase 127 — BiometricLock
- **Biometric authentication** — require fingerprint or face unlock before the app opens
- Configurable lock timeout: lock immediately, after 1 minute, after 5 minutes, or never
- **Sensitive screens** — API Keys screen and Settings screen optionally require biometric re-authentication
- Falls back to PIN/pattern if biometric hardware is unavailable
- Lock state persists across app restarts

#### Phase 128 — DevicePairing
- **Multi-device pairing** — pair multiple Android devices running ZeroClaw over your local network (mDNS discovery) or via QR code
- **Config sync** — share API keys, channel credentials, and settings between paired devices
- **Message mirroring** — optionally mirror incoming messages and replies to all paired devices
- Pairing uses end-to-end encrypted channels (AES-256-GCM + ECDH key exchange)
- Paired device list managed in Settings → Device Pairing

#### Phase 129 — AutoRecovery
- **Watchdog service** — WorkManager periodic task checks if ZeroClawService is alive every 5 minutes
- Automatically restarts the service if it has crashed or been killed by the OS
- **Crash reporting** — captures stack traces on crashes, stores locally, viewable in Settings → Diagnostics
- **Circuit breaker** — if a specific channel or tool fails repeatedly, it is automatically disabled and the user notified, preventing cascading failures
- **Graceful degradation** — service continues operating with remaining healthy channels if one channel manager crashes

#### Phase 130 — Additional Platform Hardening
- **Memory pressure handling** — automatic conversation history trimming when available heap drops below threshold
- **Battery optimization awareness** — detects when Doze mode is active and adjusts polling intervals accordingly
- **Network resilience** — exponential backoff with jitter on failed API calls; dead-letter queue for undelivered replies
- **Metrics endpoint** — exposes a lightweight JSON metrics endpoint at `tunnel-url/metrics` for external monitoring

### 🔮 Vector Memory & RAG (Phases 118-122)
- **VectorMemory** — embedding-based semantic memory with cosine similarity search
- **HybridSearch + RRF** — BM25 keyword + vector retrieval fused with Reciprocal Rank Fusion
- **QueryExpansion** — LLM-generated query variants + HyDE for higher recall
- **TemporalDecay** — exponential freshness scoring; recent memories rank higher
- **SessionManager** — session tracking, summaries, cross-session continuity

### 🧠 Advanced AI Systems (Phases 110-117)
- **SystemPromptManager** — per-channel/user prompts with templates and variable injection
- **StreamingResponse** — token-level streaming with typing indicators
- **MultiAgent** — pipeline orchestration (linear, fan-out, fan-in, conditional)
- **AgentProfiles** — named personas with per-profile tool/model config
- **WorkflowEngine** — visual multi-step workflow composer (Trigger → Condition → Action)
- **ToolLoopDetector** — infinite tool-call loop prevention
- **ThinkingMode** — extended reasoning / chain-of-thought (Claude, o1, o3-mini)
- **ConversationSummarizer** — automatic context compression

### 💬 11 Messaging Channels (Phases 103-109)
- Telegram, WhatsApp, Discord, Signal, Slack, Matrix, IRC, Teams, Twitch, LINE, WebChat

### 🔧 AI Tools — 30 Built-in Tools

#### Core Tools (10)
Web Search, Web Fetch, Memory, PDF Reader, Image Analysis, Cron, Status/Diagnostics, GitHub, Notion, Email

#### Extended Toolbox (18 — Phases 85-102)
Summarize, Translate, ImageGen, SpeechToText, TextToSpeech, Calendar, Contacts, Location, Calculator, RSS, QR Code, FileManager, Clipboard, Spotify, SmartHome, BraveTool, Bookmark

#### Infrastructure Tools (2 — Phases 125)
- **WebViewTool** — headless WebView for JS-rendered page scraping
- **MediaPipelineTool** — download, transcode, and process media files

### 🔑 Multi-Provider API Key Manager + Auto Failover
- Unlimited keys, cURL import, live testing, Gemini model picker, priority reordering, per-model selection

### 📱 Native Android UI (Material Design 3)
- Rich notifications with quick-reply, biometric lock, auto-recovery diagnostics panel
- All original screens plus Plugin Manager, Device Pairing, and Metrics views

---

## 🏗️ Architecture

```
app/src/main/java/ai/zeroclaw/android/
│
├── MainActivity.kt
├── ui/
│   ├── HomeScreen.kt, ApiKeysScreen.kt, SettingsScreen.kt
│   ├── InfoScreen.kt, InfoData.kt
│   └── theme/
│
├── data/
│   ├── ApiKeyEntry.kt, LlmKeyManager.kt, LlmRouter.kt
│   ├── OfflineModelManager.kt, AppSettings.kt
│   ├── MessageDatabase.kt, MemoryDatabase.kt
│
├── infra/                               # Phase 123-130
│   ├── HooksSystem.kt                   # Pre/post-message hook pipeline
│   ├── PluginSystem.kt                  # User-installable plugin loader + sandbox
│   ├── RichNotifications.kt             # Rich notifications + quick-reply actions
│   ├── BiometricLock.kt                 # Fingerprint/face authentication guard
│   ├── DevicePairing.kt                 # Multi-device mDNS discovery + config sync
│   ├── AutoRecovery.kt                  # Watchdog, crash reporter, circuit breaker
│   └── PlatformHardening.kt             # Memory pressure, Doze, backoff, metrics
│
├── memory/                              # Phase 118-122
│   ├── VectorMemory.kt
│   ├── HybridSearch.kt
│   ├── QueryExpansion.kt
│   ├── TemporalDecay.kt
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
│   ├── [28 original tools]
│   ├── WebViewTool.kt                   # Phase 125 — headless WebView scraper
│   └── MediaPipelineTool.kt             # Phase 125 — media download + transcode
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
- At least one LLM API key
- (Optional) OpenAI API key for high-quality embeddings

### Build & Run

```bash
git clone https://github.com/ashokvarmamatta/ZeroClawAndroid.git
cd ZeroClawAndroid
# Open in Android Studio → File → Open → ZeroClawAndroid
# Wait for Gradle sync, then click ▶ Run
```

### First-Time Setup

1. Tap **ℹ️** for the setup walkthrough
2. Add your LLM API key in **Settings → Manage API Keys**
3. (Optional) Enable **BiometricLock** in Settings → Security
4. Configure channel credentials in Settings
5. (Optional) Set up **Device Pairing** in Settings → Device Pairing for multi-device sync
6. Tap **▶ Start** — the daemon is now running with full infrastructure support

---

## 🔑 Supported LLM Providers

| Provider | Auth | Default Base URL | Notes |
|---|---|---|---|
| **OpenAI** | Bearer | `https://api.openai.com/v1` | GPT-4o, o1, o3-mini; embeddings |
| **Google Gemini** | API key | `https://generativelanguage.googleapis.com/v1beta` | Streaming + Search grounding |
| **Anthropic Claude** | x-api-key | `https://api.anthropic.com/v1` | Extended thinking |
| **OpenRouter** | Bearer | `https://openrouter.ai/api/v1` | 400+ models |
| **Ollama** | None | `http://127.0.0.1:11434` | Local models |
| **Offline** | None | On-device | MediaPipe `.bin` models |
| **Custom endpoint** | Bearer | *(your Base URL)* | Any OpenAI-compatible API |

---

## 📦 Tech Stack

| Layer | Technology |
|---|---|
| Language | Kotlin |
| UI | Jetpack Compose + Material Design 3 |
| Background | Android Foreground Service + WorkManager (watchdog) |
| HTTP | OkHttp + Retrofit + SSE streaming |
| WebView | Android WebView (headless, PhantomJS-style) |
| Storage | Room (messages + vectors + sessions + plugins) + DataStore |
| Vector Search | BM25 + cosine similarity + RRF (on-device) |
| Security | Android BiometricPrompt + AES-256-GCM (device pairing) |
| Notifications | NotificationCompat + RemoteInput (quick-reply) |
| Plugins | Custom ClassLoader sandbox |
| Serialization | Gson |
| Navigation | Jetpack Navigation Compose |
| Offline AI | MediaPipe LlmInference |
| Messaging | 11 channel integrations |
| Tunnel | Cloudflare Tunnel / ngrok |

---

## 🛣️ Roadmap

### ✅ Core Foundation
- [x] Multi-provider API key manager with auto failover ✅
- [x] Per-model testing & selection ✅
- [x] Google Search grounding (Gemini) ✅
- [x] Offline mode (MediaPipe) ✅
- [x] Live log viewer ✅
- [x] Cloudflare Tunnel / ngrok ✅

### ✅ Core Tools + Extended Toolbox (28 tools)
- [x] All 28 original tools implemented ✅

### ✅ Messaging Channels — Phases 103-109
- [x] All 11 channels ✅

### ✅ Advanced AI Systems — Phases 110-117
- [x] SystemPromptManager, StreamingResponse, MultiAgent, AgentProfiles ✅
- [x] WorkflowEngine, ToolLoopDetector, ThinkingMode, ConversationSummarizer ✅

### ✅ Vector Memory & RAG — Phases 118-122
- [x] VectorMemory, HybridSearch+RRF, QueryExpansion, TemporalDecay, SessionManager ✅

### ✅ Infrastructure & Platform — Phases 123-130
- [x] HooksSystem — pre/post-message hook pipeline (Phase 123) ✅
- [x] PluginSystem — user-installable sandboxed plugins (Phase 124) ✅
- [x] WebViewTool + MediaPipelineTool (Phase 125) ✅
- [x] RichNotifications + QuickReply (Phase 126) ✅
- [x] BiometricLock (Phase 127) ✅
- [x] DevicePairing — multi-device mDNS sync (Phase 128) ✅
- [x] AutoRecovery — watchdog + crash reporter + circuit breaker (Phase 129) ✅
- [x] Platform hardening — Doze awareness, backoff, metrics endpoint (Phase 130) ✅

### 🔲 Upcoming
- [ ] ExportImportConfig — backup/restore all settings as JSON (Phase 131)
- [ ] ThemeManager — custom color themes (Phase 132)
- [ ] PerChannelPrompts UI (Phase 133)
- [ ] RateLimiting + UsageTracking (Phase 134-135)
- [ ] ApprovalSystem + ConversationLabels (Phase 136)
- [ ] HomeWidget — launcher widget with service status (Phase 137)
- [ ] VoiceInput for WebChat (Phase 138)
- [ ] GroupChatSupport (Phase 140)

---

## 🤝 Contributing

Contributions are welcome! Here's how to get started:

1. Fork the repo
2. Create a feature branch: `git checkout -b feature/my-feature`
3. Commit your changes: `git commit -m 'Add my feature'`
4. Push: `git push origin feature/my-feature`
5. Open a Pull Request

Please open an issue first for large changes.

---

## 📄 License

This project is licensed under the MIT License — see the [LICENSE](LICENSE) file for details.

---

## 🙏 Acknowledgements

### Built on ZeroClaw
This Android app is built on top of the [**ZeroClaw**](https://github.com/zeroclaw-labs/zeroclaw) project by [ZeroClaw Labs](https://github.com/zeroclaw-labs). ZeroClaw Android extends it into a production-grade native Android AI agent with 11 channels, 30 tools, advanced AI orchestration, vector memory, and a complete infrastructure platform.

### Libraries & Services
- [Telegram Bot API](https://core.telegram.org/bots/api) — [Twilio](https://www.twilio.com) — [Discord Gateway](https://discord.com/developers/docs/topics/gateway)
- [Slack API](https://api.slack.com) — [Matrix Spec](https://spec.matrix.org) — [Microsoft Bot Framework](https://dev.botframework.com)
- [Twitch TMI](https://dev.twitch.tv) — [LINE Messaging API](https://developers.line.biz)
- [OpenAI API](https://platform.openai.com) — [Google Gemini API](https://ai.google.dev) — [Anthropic API](https://docs.anthropic.com)
- [OpenRouter](https://openrouter.ai) — [Ollama](https://ollama.com)
- [MediaPipe](https://ai.google.dev/edge/mediapipe/solutions/genai/llm_inference)
- [Pollinations.ai](https://pollinations.ai) — [Brave Search API](https://brave.com/search/api/)
- [Cloudflare Tunnel](https://developers.cloudflare.com/cloudflare-one/connections/connect-networks/)
- [Android BiometricPrompt](https://developer.android.com/training/sign-in/biometric-auth)
- [WorkManager](https://developer.android.com/topic/libraries/architecture/workmanager)

---

<div align="center">
Made with ❤️ — built to run on your pocket supercomputer
</div>

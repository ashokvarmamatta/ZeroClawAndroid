<div align="center">

<img src="screenshots/app_icon.png" width="120" alt="ZeroClaw Icon" />

# ZeroClaw Android

**A 24/7 AI agent daemon for Android — 11 messaging channels, 28 built-in AI tools, multi-provider failover. Runs entirely on your phone.**

[![Android](https://img.shields.io/badge/Platform-Android%2026%2B-green?logo=android)](https://developer.android.com)
[![Kotlin](https://img.shields.io/badge/Language-Kotlin-purple?logo=kotlin)](https://kotlinlang.org)
[![Compose](https://img.shields.io/badge/UI-Jetpack%20Compose-blue?logo=jetpack-compose)](https://developer.android.com/compose)
[![License](https://img.shields.io/badge/License-MIT-yellow)](LICENSE)

</div>

---

## 📖 What is ZeroClaw?

ZeroClaw is an **Android-native AI agent daemon** that turns your phone into an always-on AI backend. It runs as a foreground service, listening across **11 messaging channels** simultaneously — Telegram, WhatsApp, Discord, Signal, Slack, Matrix, IRC, Microsoft Teams, Twitch, LINE, and a built-in WebChat. Every message is routed through your configured LLM providers with automatic failover, augmented by 28 built-in AI tools, and replied to automatically — all without a server.

No cloud subscription. No always-on PC. Just your Android device.

```
You → Telegram / WhatsApp / Discord / Signal / Slack / Matrix / IRC
    → Teams / Twitch / LINE / WebChat
              ↓
    ZeroClaw Service (background daemon)
              ↓
    Tool System (28 built-in tools)
              ↓
    LLM Router (OpenAI / Gemini / Anthropic / OpenRouter / Ollama / Offline)
              ↓
    Auto-reply back to originating channel
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

### 💬 11 Messaging Channels (Phases 103-109)

#### Original 4 Channels
- **Telegram Bot** — polling-based integration, responds to any message sent to your bot; `/clear`, `/new`, `/tools` commands
- **WhatsApp** — integration via Twilio API; send and receive messages on your WhatsApp number
- **Discord Bot** — native Gateway WebSocket integration with real-time event handling
- **Signal** — integration via signal-cli REST API bridge

#### New Channels (Phases 103-109)
- **Slack** (Phase 103) — Slack Bot via Events API and Web API; responds in channels and DMs, supports slash commands
- **Matrix** (Phase 104) — Matrix protocol client via matrix-nio / Synapse; join rooms and respond to messages in the decentralized federated network
- **IRC** (Phase 105) — classic IRC bot using TCP socket connection; joins channels, responds to PRIVMSG, supports nicknames
- **Microsoft Teams** (Phase 106) — Teams Bot Framework integration; handles messages in teams, channels, and 1:1 chats via Bot Service webhooks
- **Twitch** (Phase 107) — Twitch Chat bot via IRC/TMI; responds to chat messages in your stream channel, supports !commands
- **LINE** (Phase 108) — LINE Messaging API integration; responds to LINE friends and group messages via webhook
- **WebChat** (Phase 109) — built-in HTTP WebSocket chat server; browser-accessible chat interface at your tunnel URL, no account needed

All channels share:
- **Per-chat conversation history** — separate context per user per channel
- **Tool access** — all 28 tools available from any channel
- **Multi-round tool calling** — up to 3 rounds per message

### 🔑 Multi-Provider API Key Manager
- Add unlimited API keys from **any provider**: OpenAI, Google Gemini, Anthropic Claude, OpenRouter, Ollama (local), or any OpenAI-compatible endpoint
- **Custom Base URL** support — Modal, self-hosted models, local proxies, any custom endpoint
- **cURL import mode** — paste a raw `curl` command and the app auto-extracts Bearer token, base URL, and model
- **Live key testing** — validates each key against the real API before saving
- **Gemini model picker** — lists all available models on your key, recommends the best one (★)
- **Priority reordering** — ↑↓ buttons to set the failover chain order
- **Set Active key** — pin any key as the default starting point

### 🔍 Per-Model Testing & Selection
- **Check All Models** — tests every model available on your API key, shows pass/fail
- **Per-model selection** — choose exactly which models to use (persists across restarts)
- **Edit Selection** — reselect models anytime without re-checking
- **Individual model retest** — re-test a single model without re-checking all

### 🔍 Google Search Grounding (Gemini)
- **Per-key toggle** to enable/disable Google Search grounding on Gemini API calls
- When enabled, replies include real-time web information

### ⚡ Auto Failover
- Keys tried in order (top → bottom); silent fallback on failure or quota hit
- Rate-limited (429) keys skipped this session, re-tried on restart

### 🔧 AI Tools — 28 Built-in Tools

#### Core Tools (10)
- **Web Search** — DuckDuckGo (no API key), real-time results
- **Web Fetch** — URL content fetching & HTML text extraction
- **Memory** — persistent per-user memory store/recall/forget (Room/SQLite)
- **PDF Reader** — extract text from local files, URIs, or remote PDFs
- **Image Analysis** — vision model image analysis (GPT-4o, Gemini, Claude)
- **Cron / Scheduled Tasks** — recurring AI prompts per user, 1min–7day intervals
- **Status / Diagnostics** — AI self-checks service health and key status
- **GitHub** — search repos, read READMEs, list/create issues
- **Notion** — search, read, create, and append to Notion pages
- **Email** — send emails via SendGrid or Mailgun with draft preview

#### Extended Toolbox (18 — Phases 85-102)
- **Summarize** — extractive text summarization without an LLM call
- **Translate** — 50+ languages via MyMemory API (free, no key needed)
- **ImageGen** — generate images via Pollinations.ai (free) or DALL-E 3
- **SpeechToText** — transcribe voice messages via OpenAI Whisper
- **TextToSpeech** — AI responses as spoken audio via Android TTS
- **Calendar** — read, create, delete Android calendar events
- **Contacts** — look up and manage Android contacts
- **Location** — GPS coordinates, reverse geocoding, nearby places
- **Calculator** — evaluate math expressions and unit conversions
- **RSS** — fetch and parse RSS/Atom feeds, surface latest headlines
- **QR Code** — generate QR images, decode QR from images or URLs
- **FileManager** — list, read, write, delete files in app storage
- **Clipboard** — read from and write to Android clipboard
- **Spotify** — search tracks, control playback, get now-playing info
- **SmartHome** — control Home Assistant devices and automations
- **BraveTool** — web search via Brave Search API
- **Bookmark** — save, list, search, and delete bookmarked URLs

### 📱 Offline Mode
- Run AI **completely offline** using on-device `.bin` models via MediaPipe LlmInference
- Import models via file picker (SAF — no storage permissions needed)

### 📊 Live Logs
- Real-time log viewer showing mode, provider, key label, and model for every call
- Logs fallbacks, rate limits, failures, and tool invocations

### 🌐 Public URL Exposure
- Built-in **Cloudflare Tunnel** / **ngrok** support (TunnelManager)
- Required for webhooks — Teams, LINE, WebChat, Slack Events API

### 📱 Native Android UI (Material Design 3)
- Home dashboard with service status, active key info, live logs
- AI Configuration screen with per-provider key and model management
- Settings screen with per-channel token configuration
- In-app setup guide with tabbed walkthrough
- Auto-starts on device reboot (BootReceiver)

---

## 🏗️ Architecture

```
app/src/main/java/ai/zeroclaw/android/
│
├── MainActivity.kt
│
├── ui/
│   ├── HomeScreen.kt
│   ├── ApiKeysScreen.kt
│   ├── SettingsScreen.kt
│   ├── InfoScreen.kt
│   ├── InfoData.kt
│   └── theme/
│
├── data/
│   ├── ApiKeyEntry.kt
│   ├── LlmKeyManager.kt
│   ├── LlmRouter.kt
│   ├── OfflineModelManager.kt
│   ├── AppSettings.kt
│   ├── MessageDatabase.kt
│   └── MemoryDatabase.kt
│
├── tools/
│   ├── ToolSystem.kt
│   ├── WebSearchTool.kt
│   ├── WebFetchTool.kt
│   ├── MemoryTool.kt
│   ├── PdfReadTool.kt
│   ├── ImageAnalysisTool.kt
│   ├── CronTool.kt
│   ├── StatusTool.kt
│   ├── GitHubTool.kt
│   ├── NotionTool.kt
│   ├── EmailTool.kt
│   ├── SummarizeTool.kt             # Phase 85
│   ├── TranslateTool.kt             # Phase 86
│   ├── ImageGenTool.kt              # Phase 87
│   ├── SpeechToTextTool.kt          # Phase 88
│   ├── TextToSpeechTool.kt          # Phase 89-90
│   ├── CalendarTool.kt              # Phase 91
│   ├── ContactsTool.kt              # Phase 92
│   ├── LocationTool.kt              # Phase 93
│   ├── CalculatorTool.kt            # Phase 94
│   ├── RssTool.kt                   # Phase 95
│   ├── QrCodeTool.kt                # Phase 96
│   ├── FileManagerTool.kt           # Phase 97
│   ├── ClipboardTool.kt             # Phase 98
│   ├── SpotifyTool.kt               # Phase 99
│   ├── SmartHomeTool.kt             # Phase 100
│   ├── BraveTool.kt                 # Phase 101
│   └── BookmarkTool.kt              # Phase 102
│
├── service/
│   ├── ZeroClawService.kt
│   └── BootReceiver.kt
│
├── telegram/
│   └── TelegramBotManager.kt
│
├── whatsapp/
│   └── TwilioWhatsAppManager.kt
│
├── discord/
│   └── DiscordBotManager.kt
│
├── signal/
│   └── SignalBridgeManager.kt
│
├── slack/
│   └── SlackBotManager.kt           # Phase 103 — Slack Events API
│
├── matrix/
│   └── MatrixBotManager.kt          # Phase 104 — Matrix protocol
│
├── irc/
│   └── IrcBotManager.kt             # Phase 105 — IRC TCP bot
│
├── teams/
│   └── TeamsBotManager.kt           # Phase 106 — MS Teams Bot Framework
│
├── twitch/
│   └── TwitchBotManager.kt          # Phase 107 — Twitch Chat IRC/TMI
│
├── line/
│   └── LineBotManager.kt            # Phase 108 — LINE Messaging API
│
├── webchat/
│   └── WebChatServer.kt             # Phase 109 — Built-in WebSocket chat server
│
└── tunnel/
    └── TunnelManager.kt
```

---

## 🚀 Getting Started

### Prerequisites
- Android Studio Hedgehog or newer
- Android device or emulator running **Android 8.0 (API 26)+**
- At least one LLM API key (OpenAI, Gemini, Anthropic, etc.)
- (For webhooks) A Cloudflare Tunnel or ngrok URL

### Build & Run

```bash
git clone https://github.com/ashokvarmamatta/ZeroClawAndroid.git
cd ZeroClawAndroid
# Open in Android Studio → File → Open → ZeroClawAndroid
# Wait for Gradle sync, then click ▶ Run
```

### First-Time Setup

1. Tap **ℹ️** on the home screen for the full setup walkthrough
2. Go to **Settings** → **Manage API Keys** → **+ Add Online Key**
3. Add your LLM key and tap **Test Key**
4. Run **Check All Models** and select models to use
5. Add credentials for your desired messaging channels in Settings
6. Configure tunnel URL if using webhook-based channels (Teams, LINE, WebChat, Slack)
7. Tap **▶ Start** — the daemon is now running across all configured channels

### Channel-Specific Setup

| Channel | Credentials Needed | Notes |
|---|---|---|
| Telegram | Bot token (BotFather) | Polling — no webhook needed |
| WhatsApp | Twilio Account SID + Auth Token + number | Twilio webhook → tunnel URL |
| Discord | Bot token + Application ID | Gateway WebSocket — no webhook |
| Signal | signal-cli REST API URL | Self-hosted bridge required |
| Slack | Bot token + Signing secret | Events API webhook → tunnel URL |
| Matrix | Homeserver URL + access token | Federation-compatible |
| IRC | Server, port, nickname, channels | TCP — no webhook |
| Teams | App ID + password (Bot Framework) | Webhook → tunnel URL |
| Twitch | OAuth token + channel name | IRC/TMI — no webhook |
| LINE | Channel Access Token + Channel Secret | Webhook → tunnel URL |
| WebChat | None | Built-in — served at tunnel URL |

---

## 🔑 Supported LLM Providers

| Provider | Auth | Default Base URL | Notes |
|---|---|---|---|
| **OpenAI** | Bearer | `https://api.openai.com/v1` | GPT-4o, GPT-4o-mini, etc. |
| **Google Gemini** | API key | `https://generativelanguage.googleapis.com/v1beta` | Lists all models, Google Search grounding |
| **Anthropic Claude** | x-api-key | `https://api.anthropic.com/v1` | Claude Opus, Sonnet, Haiku |
| **OpenRouter** | Bearer | `https://openrouter.ai/api/v1` | 400+ models |
| **Ollama** | None | `http://127.0.0.1:11434` | Local models on device |
| **Offline** | None | On-device | MediaPipe `.bin` models, no internet needed |
| **Custom endpoint** | Bearer | *(your Base URL)* | Modal, LiteLLM, vLLM, any OpenAI-compatible API |

---

## 📦 Tech Stack

| Layer | Technology |
|---|---|
| Language | Kotlin |
| UI | Jetpack Compose + Material Design 3 |
| Background | Android Foreground Service + WorkManager |
| HTTP | OkHttp + Retrofit |
| WebSocket | OkHttp WebSocket (Discord Gateway, WebChat) |
| Storage | Room (messages + memory) + DataStore + SharedPreferences |
| Serialization | Gson |
| Navigation | Jetpack Navigation Compose |
| Offline AI | MediaPipe LlmInference |
| Image Gen | Pollinations.ai (free) / DALL-E 3 |
| Speech | OpenAI Whisper (STT) + Android TTS |
| Messaging | Telegram, Twilio, Discord, Signal, Slack, Matrix, IRC, Teams, Twitch, LINE |
| Tunnel | Cloudflare Tunnel / ngrok |

---

## 🛣️ Roadmap

### ✅ Core Foundation
- [x] Multi-provider API key manager with failover ✅
- [x] Per-model testing & selection ✅
- [x] Google Search grounding (Gemini) ✅
- [x] Offline mode (MediaPipe) ✅
- [x] Live log viewer ✅
- [x] Cloudflare Tunnel / ngrok integration ✅

### ✅ Core Tools (10 tools)
- [x] Web Search, Web Fetch, Memory, PDF Reader, Image Analysis ✅
- [x] Cron, Status/Diagnostics, GitHub, Notion, Email ✅

### ✅ Extended Toolbox — Phases 85-102 (18 tools)
- [x] Summarize, Translate, ImageGen, SpeechToText, TextToSpeech ✅
- [x] Calendar, Contacts, Location, Calculator, RSS ✅
- [x] QR Code, FileManager, Clipboard, Spotify, SmartHome ✅
- [x] BraveTool, Bookmark ✅

### ✅ Messaging Channels — Phases 103-109
- [x] Telegram, WhatsApp, Discord, Signal ✅
- [x] Slack Bot (Phase 103) ✅
- [x] Matrix Bot (Phase 104) ✅
- [x] IRC Bot (Phase 105) ✅
- [x] Microsoft Teams Bot (Phase 106) ✅
- [x] Twitch Chat Bot (Phase 107) ✅
- [x] LINE Bot (Phase 108) ✅
- [x] WebChat server (Phase 109) ✅

### 🔲 Upcoming
- [ ] SystemPromptManager + StreamingResponse (Phase 110-111)
- [ ] MultiAgent orchestration (Phase 112)
- [ ] AgentProfiles + WorkflowEngine (Phase 113-114)
- [ ] Vector memory with embeddings (Phase 118)
- [ ] BiometricLock + DevicePairing (Phase 126-127)
- [ ] Home screen widget (Phase 137)
- [ ] Group chat support (Phase 140)

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
This Android app is built on top of the [**ZeroClaw**](https://github.com/zeroclaw-labs/zeroclaw) project by [ZeroClaw Labs](https://github.com/zeroclaw-labs). ZeroClaw Android extends it into a native Android experience spanning 11 messaging channels, 28 AI tools, and a full Material Design 3 UI.

### Libraries & Services
- [Telegram Bot API](https://core.telegram.org/bots/api)
- [Twilio](https://www.twilio.com)
- [Discord Gateway API](https://discord.com/developers/docs/topics/gateway)
- [Slack API](https://api.slack.com)
- [Matrix Spec](https://spec.matrix.org)
- [Microsoft Bot Framework](https://dev.botframework.com)
- [Twitch TMI](https://dev.twitch.tv)
- [LINE Messaging API](https://developers.line.biz/en/services/messaging-api/)
- [OpenAI API](https://platform.openai.com)
- [Google Gemini API](https://ai.google.dev)
- [Anthropic API](https://docs.anthropic.com)
- [OpenRouter](https://openrouter.ai)
- [Ollama](https://ollama.com)
- [MediaPipe](https://ai.google.dev/edge/mediapipe/solutions/genai/llm_inference)
- [Pollinations.ai](https://pollinations.ai)
- [Cloudflare Tunnel](https://developers.cloudflare.com/cloudflare-one/connections/connect-networks/)

---

<div align="center">
Made with ❤️ — built to run on your pocket supercomputer
</div>

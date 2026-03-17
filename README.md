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

ZeroClaw is an **Android-native AI agent daemon** that turns your phone into an always-on AI backend. It runs as a foreground service, listens across your configured messaging channels, routes every message through your LLM providers with automatic failover, and replies automatically — all without a server. With 28 built-in AI tools, it can search the web, read PDFs, generate images, transcribe speech, manage calendars, query RSS feeds, scan QR codes, and much more, all triggered naturally from conversation.

No cloud subscription. No always-on PC. Just your Android device.

```
You → Telegram / WhatsApp / Discord / Signal
              ↓
    ZeroClaw Service (background daemon)
              ↓
    Tool System (28 built-in tools)
              ↓
    LLM Router (OpenAI / Gemini / Anthropic / OpenRouter / Ollama / Offline)
              ↓
    Auto-reply back to your messaging channel
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

### 🤖 AI Messaging (4 Channels)
- **Telegram Bot** — polling-based integration, responds to any message sent to your bot
- **WhatsApp** — integration via Twilio API
- **Discord Bot** — native Gateway WebSocket integration with real-time messaging
- **Signal** — integration via signal-cli REST API bridge
- **Per-chat conversation history** — separate context per user across all channels
- Automatic AI replies powered by any LLM you configure
- `/clear` or `/new` commands to reset chat history per user
- `/tools` command to list enabled tools in chat

### 🔑 Multi-Provider API Key Manager
- Add unlimited API keys from **any provider**: OpenAI, Google Gemini, Anthropic Claude, OpenRouter, Ollama (local), or any OpenAI-compatible endpoint
- **Custom Base URL** support — works with Modal, self-hosted models, local proxies, any custom endpoint
- **cURL import mode** — paste a raw `curl` command and the app auto-extracts Bearer token, base URL, and model
- **Live key testing** — validates each key against the real API before saving
- **Gemini model picker** — lists all available models on your key, recommends the best one (★)
- **Priority reordering** — use ↑↓ buttons to set the failover chain order
- **Set Active key** — pin any key as the default starting point (persists across restarts)

### 🔍 Per-Model Testing & Selection
- **Check All Models** — tests every model available on your API key and shows pass/fail status
- **Per-model selection** — choose exactly which models to use via checkboxes (persists across restarts)
- **Edit Selection** — reselect models anytime without re-checking
- **Individual model retest** — re-test a single failed model without re-checking all
- Deselecting all models on a key **skips that key entirely**

### 🔍 Google Search Grounding (Gemini)
- **Per-key toggle** to enable/disable Google Search grounding on Gemini API calls
- When enabled, Gemini replies include **real-time web information**

### ⚡ Auto Failover
- Keys are tried in order (top → bottom)
- If a key fails or hits quota, ZeroClaw silently moves to the next one
- Rate-limited (429) keys are skipped this session but re-tried on restart
- Full session failure stats shown on the home screen

### 🔧 AI Tools — 28 Built-in Tools (Phases 85-102)

#### Core Tools (Phase 1 baseline)
- **Web Search** — DuckDuckGo search (no API key needed), returns top results with real-time info
- **Web Fetch** — fetch any URL, strip HTML, extract readable text
- **Memory** — persistent per-user memory store/recall/forget via Room/SQLite
- **PDF Reader** — extract text from local files, content URIs, or remote PDF URLs
- **Image Analysis** — analyze images using vision-capable models (GPT-4o, Gemini, Claude)
- **Scheduled Tasks (Cron)** — recurring AI prompts per user, auto-executed by the daemon
- **Status / Diagnostics** — AI self-checks service health, key status, connections
- **GitHub** — search repos, read READMEs, list/create issues from chat
- **Notion** — search, read, create, and append to Notion pages
- **Email** — send emails via SendGrid or Mailgun with draft preview mode

#### Extended Toolbox (Phases 85-102)
- **Summarize** (Phase 85) — extractive text summarization without requiring an LLM call
- **Translate** (Phase 86) — translate text across 50+ languages using MyMemory API (free, no key needed)
- **ImageGen** (Phase 87) — generate images via Pollinations.ai (free) or DALL-E 3; returns direct URLs
- **SpeechToText** (Phase 88) — transcribe voice messages and audio files using OpenAI Whisper
- **TextToSpeech** (Phase 89-90) — convert AI responses to spoken audio using Android TTS engine
- **Calendar** (Phase 91) — read, create, and delete Android calendar events without leaving chat
- **Contacts** (Phase 92) — look up and manage Android contacts by name or phone number
- **Location** (Phase 93) — get current GPS coordinates, reverse geocode to address, query nearby places
- **Calculator** (Phase 94) — evaluate mathematical expressions and unit conversions safely
- **RSS** (Phase 95) — fetch and parse RSS/Atom feeds, surface latest headlines from any feed URL
- **QR Code** (Phase 96) — generate QR codes as images, decode QR codes from images or URLs
- **FileManager** (Phase 97) — list, read, write, and delete files in app-accessible storage
- **Clipboard** (Phase 98) — read from and write to the Android clipboard
- **Spotify** (Phase 99) — search tracks, control playback, get currently playing song via Spotify API
- **SmartHome** (Phase 100) — control Home Assistant devices, read sensor states, trigger automations
- **BraveTool** (Phase 101) — web search via Brave Search API for ad-free, privacy-respecting results
- **Bookmark** (Phase 102) — save, list, search, and delete bookmarked URLs with per-user storage

All 28 tools:
- Work across **all providers** (OpenAI, Anthropic, Gemini, OpenRouter, Ollama)
- Have per-tool **enable/disable toggles** in Settings
- Support multi-round tool calling — LLM can chain multiple calls per message (max 3 rounds)

### 📱 Offline Mode
- Run AI **completely offline** using on-device `.bin` models via MediaPipe LlmInference
- **Import models** from file picker (SAF — no storage permissions needed)
- Toggle offline mode on/off independently of online keys

### 📊 Live Logs
- Real-time log viewer on the home screen
- Shows mode, provider, key label, and exact model for every LLM call
- Logs model fallbacks, rate limits, failures, and skipped keys

### 🌐 Public URL Exposure
- Built-in **Cloudflare Tunnel** / **ngrok** support (TunnelManager)
- Exposes your device to the internet so webhooks and bots can reach it

### 📱 Native Android UI (Material Design 3)
- Home screen with live service status, active key info, failover indicator, and live logs
- Full AI Configuration screen with online/offline mode management
- Settings screen for bot tokens, credentials, tunnel config, and tool toggles
- In-app help/guide system (InfoScreen) with tabbed walkthrough
- Starts automatically on device reboot (BootReceiver)

---

## 🏗️ Architecture

```
app/src/main/java/ai/zeroclaw/android/
│
├── MainActivity.kt                  # Nav host, all routes
│
├── ui/
│   ├── HomeScreen.kt                # Dashboard — start/stop service, status, live logs
│   ├── ApiKeysScreen.kt             # AI Configuration — online/offline, model testing
│   ├── SettingsScreen.kt            # Bot tokens, Twilio, tunnel, tool toggles
│   ├── InfoScreen.kt                # In-app setup guide (tabbed)
│   ├── InfoData.kt                  # Guide content data
│   └── theme/                       # Material 3 theme, colors
│
├── data/
│   ├── ApiKeyEntry.kt               # Key data model
│   ├── LlmKeyManager.kt             # Key persistence, active key, reordering, failover
│   ├── LlmRouter.kt                 # Waterfall failover, per-provider dispatch
│   ├── OfflineModelManager.kt       # Offline .bin model management via MediaPipe
│   ├── AppSettings.kt               # DataStore preferences
│   ├── MessageDatabase.kt           # Room DB — message history
│   └── MemoryDatabase.kt            # Room DB — persistent per-user memory
│
├── tools/
│   ├── ToolSystem.kt                # Tool registry, dispatcher, prompt injection
│   ├── WebSearchTool.kt             # DuckDuckGo web search
│   ├── WebFetchTool.kt              # URL content fetching & HTML text extraction
│   ├── MemoryTool.kt                # Persistent per-user memory
│   ├── PdfReadTool.kt               # PDF text extraction
│   ├── ImageAnalysisTool.kt         # Vision model image analysis
│   ├── CronTool.kt                  # Scheduled recurring AI tasks
│   ├── StatusTool.kt                # Service diagnostics & health reporting
│   ├── GitHubTool.kt                # GitHub repo search and issues
│   ├── NotionTool.kt                # Notion workspace integration
│   ├── EmailTool.kt                 # Send emails via SendGrid or Mailgun
│   ├── SummarizeTool.kt             # Extractive text summarization (Phase 85)
│   ├── TranslateTool.kt             # 50+ language translation (Phase 86)
│   ├── ImageGenTool.kt              # Image generation via Pollinations/DALL-E (Phase 87)
│   ├── SpeechToTextTool.kt          # Whisper audio transcription (Phase 88)
│   ├── TextToSpeechTool.kt          # Android TTS voice output (Phase 89-90)
│   ├── CalendarTool.kt              # Android Calendar read/write (Phase 91)
│   ├── ContactsTool.kt              # Android Contacts lookup (Phase 92)
│   ├── LocationTool.kt              # GPS location & geocoding (Phase 93)
│   ├── CalculatorTool.kt            # Math expression evaluator (Phase 94)
│   ├── RssTool.kt                   # RSS/Atom feed fetcher (Phase 95)
│   ├── QrCodeTool.kt                # QR code generate & decode (Phase 96)
│   ├── FileManagerTool.kt           # App storage file operations (Phase 97)
│   ├── ClipboardTool.kt             # Android clipboard read/write (Phase 98)
│   ├── SpotifyTool.kt               # Spotify playback control (Phase 99)
│   ├── SmartHomeTool.kt             # Home Assistant integration (Phase 100)
│   ├── BraveTool.kt                 # Brave Search API (Phase 101)
│   └── BookmarkTool.kt              # URL bookmark manager (Phase 102)
│
├── service/
│   ├── ZeroClawService.kt           # Foreground service — main daemon loop
│   └── BootReceiver.kt              # Auto-start on device reboot
│
├── telegram/
│   └── TelegramBotManager.kt        # Telegram Bot API polling + reply
│
├── whatsapp/
│   └── TwilioWhatsAppManager.kt     # Twilio WhatsApp send/receive
│
├── discord/
│   └── DiscordBotManager.kt         # Discord Gateway WebSocket + REST
│
├── signal/
│   └── SignalBridgeManager.kt       # Signal via signal-cli REST API
│
└── tunnel/
    └── TunnelManager.kt             # Cloudflare Tunnel / ngrok integration
```

---

## 🚀 Getting Started

### Prerequisites
- Android Studio Hedgehog or newer
- Android device or emulator running **Android 8.0 (API 26)+**
- At least one LLM API key (OpenAI, Gemini, Anthropic, etc.)

### Build & Run

```bash
# 1. Clone the repo
git clone https://github.com/ashokvarmamatta/ZeroClawAndroid.git
cd ZeroClawAndroid

# 2. Open in Android Studio
#    File → Open → select the ZeroClawAndroid folder

# 3. Wait for Gradle sync

# 4. Connect your Android device (USB debugging ON) or start an emulator

# 5. Click ▶ Run
```

### First-Time Setup

1. Tap **ℹ️** on the home screen for the full setup walkthrough
2. Go to **⚙️ Settings** → **Manage API Keys** → tap **+ Add Online Key**
3. Add your LLM provider key (or paste a cURL command)
4. Tap **Test Key** to verify it works
5. Run **Check All Models** to see which models work on your key
6. Select the models you want to use (checkboxes)
7. (Gemini) Enable **Google Search Grounding** for real-time web answers
8. (Optional) Add your Telegram Bot token in Settings
9. Tap **▶ Start** on the home screen — the service is now running

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
| Storage | Room (messages + memory) + DataStore + SharedPreferences |
| Serialization | Gson |
| Navigation | Jetpack Navigation Compose |
| Offline AI | MediaPipe LlmInference |
| Image Gen | Pollinations.ai (free) / DALL-E 3 |
| Speech | OpenAI Whisper (STT) + Android TTS |
| Messaging | Telegram Bot API, Twilio API |
| Tunnel | Cloudflare Tunnel / ngrok |

---

## 🛣️ Roadmap

### ✅ Core Foundation
- [x] Telegram Bot integration ✅
- [x] WhatsApp (Twilio) integration ✅
- [x] Discord Bot integration ✅
- [x] Signal integration ✅
- [x] Multi-provider API key manager ✅
- [x] Per-model testing & selection ✅
- [x] Google Search grounding (Gemini) ✅
- [x] Auto failover across providers ✅
- [x] Offline mode (MediaPipe) ✅
- [x] Live log viewer ✅
- [x] Cloudflare Tunnel / ngrok integration ✅

### ✅ Core Tools (10 tools)
- [x] Web Search tool ✅
- [x] Web Fetch tool ✅
- [x] Memory tool ✅
- [x] PDF Reader tool ✅
- [x] Image Analysis tool ✅
- [x] Cron / Scheduled Tasks tool ✅
- [x] Status / Diagnostics tool ✅
- [x] GitHub tool ✅
- [x] Notion tool ✅
- [x] Email tool ✅

### ✅ Extended Toolbox (Phases 85-102 — 18 new tools)
- [x] SummarizeTool — extractive summarization ✅
- [x] TranslateTool — 50+ languages, MyMemory API ✅
- [x] ImageGenTool — Pollinations.ai + DALL-E 3 ✅
- [x] SpeechToTextTool — Whisper transcription ✅
- [x] TextToSpeechTool — Android TTS engine ✅
- [x] CalendarTool — Android calendar read/write ✅
- [x] ContactsTool — Android contacts lookup ✅
- [x] LocationTool — GPS + geocoding ✅
- [x] CalculatorTool — math expression evaluator ✅
- [x] RssTool — RSS/Atom feed reader ✅
- [x] QrCodeTool — QR generate & decode ✅
- [x] FileManagerTool — app storage file ops ✅
- [x] ClipboardTool — Android clipboard ✅
- [x] SpotifyTool — Spotify playback control ✅
- [x] SmartHomeTool — Home Assistant integration ✅
- [x] BraveTool — Brave Search API ✅
- [x] BookmarkTool — URL bookmark manager ✅

### 🔲 Upcoming
- [ ] Slack channel integration (Phase 103)
- [ ] Matrix / IRC / Teams / Twitch / LINE / WebChat channels (Phase 104-109)
- [ ] SystemPromptManager + StreamingResponse (Phase 110-111)
- [ ] MultiAgent orchestration (Phase 112)
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

Please open an issue first for large changes so we can discuss the approach.

---

## 📄 License

This project is licensed under the MIT License — see the [LICENSE](LICENSE) file for details.

---

## 🙏 Acknowledgements

### Built on ZeroClaw
This Android app is built on top of the [**ZeroClaw**](https://github.com/zeroclaw-labs/zeroclaw) project by [ZeroClaw Labs](https://github.com/zeroclaw-labs). ZeroClaw Android extends the original project into a native Android experience with offline model support, per-model testing & selection, Google Search grounding, a full Material Design 3 UI, and an extensive built-in toolbox.

### Libraries & Services
- [Telegram Bot API](https://core.telegram.org/bots/api)
- [Twilio](https://www.twilio.com)
- [OpenAI API](https://platform.openai.com) — GPT models + Whisper + DALL-E
- [Google Gemini API](https://ai.google.dev)
- [Anthropic API](https://docs.anthropic.com)
- [OpenRouter](https://openrouter.ai)
- [Ollama](https://ollama.com)
- [MediaPipe](https://ai.google.dev/edge/mediapipe/solutions/genai/llm_inference)
- [Pollinations.ai](https://pollinations.ai) — free image generation
- [MyMemory Translation API](https://mymemory.translated.net)
- [Brave Search API](https://brave.com/search/api/)
- [Cloudflare Tunnel](https://developers.cloudflare.com/cloudflare-one/connections/connect-networks/)

---

<div align="center">
Made with ❤️ — built to run on your pocket supercomputer
</div>

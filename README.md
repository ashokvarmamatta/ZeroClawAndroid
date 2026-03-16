<div align="center">

<img src="screenshots/app_icon.png" width="120" alt="ZeroClaw Icon" />

# ZeroClaw Android

**A powerful AI agent that runs 24/7 on your Android phone — connecting your Telegram & WhatsApp to any LLM provider, with automatic failover across multiple API keys.**

[![Android](https://img.shields.io/badge/Platform-Android%2026%2B-green?logo=android)](https://developer.android.com)
[![Kotlin](https://img.shields.io/badge/Language-Kotlin-purple?logo=kotlin)](https://kotlinlang.org)
[![Compose](https://img.shields.io/badge/UI-Jetpack%20Compose-blue?logo=jetpack-compose)](https://developer.android.com/compose)
[![License](https://img.shields.io/badge/License-MIT-yellow)](LICENSE)

</div>

---

## 📖 What is ZeroClaw?

ZeroClaw is an **Android-native AI agent daemon** that turns your phone into an always-on AI backend. It runs as a foreground service in the background, listens to your Telegram bot and WhatsApp number, routes every message through your configured LLM providers, and replies automatically — all without a server.

No cloud subscription. No always-on PC. Just your Android device.

```
You → Telegram / WhatsApp
         ↓
   ZeroClaw Service (background daemon)
         ↓
   LLM Router (OpenAI / Gemini / Anthropic / OpenRouter / Ollama / Offline)
         ↓
   Auto-reply back to Telegram / WhatsApp
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

### 🤖 AI Messaging
- **Telegram Bot** integration via polling — responds to any message sent to your bot
- **WhatsApp** integration via Twilio API
- **Per-chat conversation history** — maintains separate context per Telegram/WhatsApp user across messages
- Automatic AI replies powered by any LLM you configure
- `/clear` or `/new` commands to reset chat history per user

### 🔑 Multi-Provider API Key Manager
- Add unlimited API keys from **any provider**: OpenAI, Google Gemini, Anthropic Claude, OpenRouter, Ollama (local), or any OpenAI-compatible endpoint
- **Custom Base URL** support — works with Modal, self-hosted models, local proxies, any custom endpoint
- **cURL import mode** — paste a raw `curl` command and the app auto-extracts the Bearer token, base URL, and model
- **Live key testing** — validates each key against the real API before saving
- **Gemini model picker** — lists all available models on your key, recommends the best one (★)
- **Priority reordering** — use ↑↓ buttons to set the failover chain order
- **Set Active key** — pin any key as the default starting point (persists across restarts)

### 🔍 Per-Model Testing & Selection
- **Check All Models** — tests every model available on your API key and shows pass/fail status
- **Per-model selection** — choose exactly which models to use via checkboxes (persists across app restarts)
- **Edit Selection** — reselect models anytime without re-checking
- **Individual model retest** — re-test a single failed model without re-checking all
- Deselecting all models on a key **skips that key entirely** — respects your intent
- Re-selecting a model **immediately re-enables the key** — no service restart needed

### 🔍 Google Search Grounding (Gemini)
- **Per-key toggle** to enable/disable Google Search grounding on Gemini API calls
- When enabled, Gemini replies include **real-time web information** — same as the Gemini app
- When disabled, replies use training data only

### ⚡ Auto Failover
- Keys are tried in order (top → bottom)
- If a key fails or hits quota, ZeroClaw silently moves to the next one
- Rate-limited (429) keys are skipped this session but re-tried on restart
- Full session failure stats shown in the home screen

### 🔧 AI Tools (Extensible)
- **Tool system** inspired by [ZeroClaw upstream](https://github.com/zeroclaw-labs/zeroclaw) — LLM can invoke tools during conversations
- **Web Search** — DuckDuckGo search (no API key needed), returns top results with real-time info
- **Web Fetch** — fetch any URL, strip HTML, extract readable text for summarization
- Tools work across **all providers** (OpenAI, Anthropic, Gemini, OpenRouter, Ollama)
- Per-tool **enable/disable toggles** in Settings
- Multi-round tool calling — LLM can chain multiple tool calls per message (max 3 rounds)
- `/tools` command to list enabled tools in chat

### 📱 Offline Mode
- Run AI **completely offline** using on-device `.bin` models via MediaPipe LlmInference
- **Import models** from file picker (SAF — no storage permissions needed)
- Choose to **save to app storage** or **use from current location**
- Toggle offline mode on/off independently of online keys

### 📊 Live Logs
- Real-time log viewer on the home screen
- Shows **mode** (ONLINE/OFFLINE), **provider**, **key label**, and **exact model** for every LLM call
- Logs model fallbacks, rate limits, failures, and skipped keys
- Telegram message receipt and reply confirmations

### 🌐 Public URL Exposure
- Built-in **Cloudflare Tunnel** / **ngrok** support (TunnelManager)
- Exposes your device to the internet so webhooks and bots can reach it

### 📱 Native Android UI (Material Design 3)
- Home screen with live service status, active key info, failover indicator, and live logs
- Full AI Configuration screen with online/offline mode management
- Settings screen for Telegram token, Twilio credentials, tunnel config
- In-app help/guide system (InfoScreen) with tabbed walkthrough
- Starts automatically on device reboot (BootReceiver)
- Keeps running in background with a persistent notification

---

## 🏗️ Architecture

```
app/src/main/java/ai/zeroclaw/android/
│
├── MainActivity.kt                  # Nav host, all routes
│
├── ui/
│   ├── HomeScreen.kt                # Dashboard — start/stop service, status, live logs
│   ├── ApiKeysScreen.kt             # AI Configuration — online/offline, model testing, selection
│   ├── SettingsScreen.kt            # Bot tokens, Twilio, tunnel, model/key directories
│   ├── InfoScreen.kt                # In-app setup guide (tabbed)
│   ├── InfoData.kt                  # Guide content data
│   └── theme/                       # Material 3 theme, colors
│
├── data/
│   ├── ApiKeyEntry.kt               # Key data model (provider, key, baseUrl, model, googleSearch)
│   ├── LlmKeyManager.kt             # Key persistence, active key, reordering, failover tracking
│   ├── LlmRouter.kt                 # Waterfall failover, per-provider dispatch, tool integration
│   ├── OfflineModelManager.kt        # Offline .bin model management via MediaPipe
│   ├── AppSettings.kt               # DataStore preferences
│   └── MessageDatabase.kt           # Room DB — message history
│
├── tools/
│   ├── ToolSystem.kt                # Tool registry, dispatcher, prompt injection, call parsing
│   ├── WebSearchTool.kt             # DuckDuckGo web search (no API key needed)
│   └── WebFetchTool.kt              # URL content fetching & HTML text extraction
│
├── service/
│   ├── ZeroClawService.kt           # Foreground service — main daemon loop, live logging
│   └── BootReceiver.kt              # Auto-start on device reboot
│
├── telegram/
│   └── TelegramBotManager.kt        # Telegram Bot API polling + reply
│
├── whatsapp/
│   └── TwilioWhatsAppManager.kt     # Twilio WhatsApp send/receive
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
9. (Optional) Add Twilio credentials for WhatsApp
10. Tap **▶ Start** on the home screen — the service is now running

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
| Storage | Room (messages) + DataStore + SharedPreferences |
| Serialization | Gson (with serializeNulls for map persistence) |
| Navigation | Jetpack Navigation Compose |
| Offline AI | MediaPipe LlmInference |
| Messaging | Telegram Bot API, Twilio API |
| Tunnel | Cloudflare Tunnel / ngrok |

---

## 🛣️ Roadmap — What Should Be Added

These are the planned features and improvements for future development:

### 🔴 High Priority
- [x] **Per-key model selection** — model picker after Test Key for all providers ✅
- [x] **Per-model testing** — check all models, select/deselect, persist across restarts ✅
- [x] **Per-chat conversation history** — per-user chat context across messages ✅
- [x] **Google Search grounding** — real-time web info for Gemini API calls ✅
- [x] **Offline mode** — on-device AI via MediaPipe `.bin` models ✅
- [ ] **WhatsApp direct API** — replace Twilio with WhatsApp Business Cloud API (Meta) for free messaging
- [ ] **Custom system prompt** — let the user configure the AI's personality/instructions from the Settings screen
- [ ] **Webhook mode** — switch Telegram from polling to webhook using the tunnel URL for lower latency and battery savings

### 🟡 Medium Priority
- [ ] **Voice message support** — transcribe Telegram voice notes via Whisper API, reply with text or TTS audio
- [ ] **Image/file handling** — receive and process images sent to the bot (pass to vision-capable models)
- [ ] **Group chat support** — handle Telegram group messages with @mention detection
- [ ] **Rate limiting per user** — prevent abuse by limiting how many messages a user can send per hour
- [ ] **Message queue & retry** — buffer outgoing replies if the network drops, retry on reconnect
- [ ] **Multi-bot support** — run multiple Telegram bots on one device with different personas/keys

### 🟢 Quality of Life
- [ ] **Dark/light theme toggle** — currently follows system theme; add manual override in Settings
- [x] **Live log viewer** — real-time logs with mode, provider, key, and model details ✅
- [ ] **Key usage stats** — show per-key call count, success rate, last used time
- [ ] **Export/import config** — backup and restore all settings and keys as a JSON file
- [ ] **Notification quick-reply** — reply to messages directly from the notification shade
- [ ] **Home screen widget** — show service status and quick start/stop from the launcher
- [ ] **Auto-restart on crash** — WorkManager periodic check to restart the service if it dies

### 🔵 Advanced / Future
- [x] **Tool system** — extensible tool framework with Web Search (DuckDuckGo) and Web Fetch, per-tool toggles in Settings ✅
- [ ] **More tools** — Memory (persistent), PDF reading, image analysis, Notion, email, scheduled tasks
- [ ] **RAG / document Q&A** — index local files and answer questions about them
- [ ] **Plugin system** — user-installable plugins that add new skills to the agent
- [ ] **Multi-device sync** — sync key list and config across multiple Android devices
- [ ] **iOS companion app** — SwiftUI port of the Android app
- [ ] **Web dashboard** — browser UI accessible over the tunnel URL to manage the agent remotely
- [ ] **Scheduled tasks** — let the AI run recurring tasks on a cron-like schedule (daily summaries, reminders, etc.)

---

## ⚙️ Configuration Reference

### API Keys (stored in SharedPreferences)
All keys are stored locally on-device. Nothing is sent to any server except the LLM provider you configure.

### Required for Telegram
1. Create a bot via [@BotFather](https://t.me/BotFather) on Telegram
2. Copy the bot token
3. Paste in **Settings → Telegram Bot Token**

### Required for WhatsApp (Twilio)
1. Create a [Twilio](https://twilio.com) account
2. Set up a WhatsApp Sandbox or Business number
3. Add Account SID, Auth Token, and WhatsApp number in Settings

### Custom LLM Endpoints
Any OpenAI-compatible API works. Set the **Base URL** field to your endpoint's base (e.g. `https://api.us-west-2.modal.direct/v1`). You can also paste a full `curl` command and the app will parse out the token, URL, and model automatically.

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
This Android app is built on top of the [**ZeroClaw**](https://github.com/zeroclaw-labs/zeroclaw) project by [ZeroClaw Labs](https://github.com/zeroclaw-labs). ZeroClaw Android extends the original project into a native Android experience with offline model support, per-model testing & selection, Google Search grounding, and a full Material Design 3 UI.

### Libraries & Services
- [Telegram Bot API](https://core.telegram.org/bots/api)
- [Twilio](https://www.twilio.com)
- [OpenAI API](https://platform.openai.com)
- [Google Gemini API](https://ai.google.dev)
- [Anthropic API](https://docs.anthropic.com)
- [OpenRouter](https://openrouter.ai)
- [Ollama](https://ollama.com)
- [MediaPipe](https://ai.google.dev/edge/mediapipe/solutions/genai/llm_inference)
- [Cloudflare Tunnel](https://developers.cloudflare.com/cloudflare-one/connections/connect-networks/)

---

<div align="center">
Made with ❤️ — built to run on your pocket supercomputer
</div>

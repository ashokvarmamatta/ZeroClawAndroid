<div align="center">

# 🦾 ZeroClaw Android

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
   LLM Router (OpenAI / Gemini / Anthropic / OpenRouter / Ollama / custom)
         ↓
   Auto-reply back to Telegram / WhatsApp
```

---

## ✨ Features

### 🤖 AI Messaging
- **Telegram Bot** integration via polling — responds to any message sent to your bot
- **WhatsApp** integration via Twilio API
- Automatic AI replies powered by any LLM you configure

### 🔑 Multi-Provider API Key Manager
- Add unlimited API keys from **any provider**: OpenAI, Google Gemini, Anthropic Claude, OpenRouter, Ollama (local), or any OpenAI-compatible endpoint
- **Custom Base URL** support — works with Modal, self-hosted models, local proxies, any custom endpoint
- **cURL import mode** — paste a raw `curl` command and the app auto-extracts the Bearer token, base URL, and model
- **Live key testing** — validates each key against the real API before saving
- **Gemini model picker** — lists all available models on your key, recommends the best one (★)
- **Priority reordering** — use ↑↓ buttons to set the failover chain order
- **Set Active key** — pin any key as the default starting point (persists across restarts)

### ⚡ Auto Failover
- Keys are tried in order (top → bottom)
- If a key fails or hits quota, ZeroClaw silently moves to the next one
- Rate-limited (429) keys are skipped this session but re-tried on restart
- Full session failure stats shown in the home screen

### 🌐 Public URL Exposure
- Built-in **Cloudflare Tunnel** / **ngrok** support (TunnelManager)
- Exposes your device to the internet so webhooks and bots can reach it

### 📱 Native Android UI (Material Design 3)
- Home screen with live service status, active key info, failover indicator
- Full API key management screen
- Settings screen for Telegram token, Twilio credentials, tunnel config
- In-app help/guide system (InfoScreen)
- Starts automatically on device reboot (BootReceiver)
- Keeps running in background with a persistent notification

---

## 📸 Screens

| Home | API Keys | Settings |
|------|----------|----------|
| Service control, active key, failover status | Add/edit/reorder keys, set active, test live | Telegram, Twilio, tunnel config |

---

## 🏗️ Architecture

```
app/src/main/java/ai/zeroclaw/android/
│
├── MainActivity.kt                  # Nav host, all routes
│
├── ui/
│   ├── HomeScreen.kt                # Dashboard — start/stop service, status
│   ├── ApiKeysScreen.kt             # Full key manager UI
│   ├── SettingsScreen.kt            # Bot tokens, Twilio, tunnel
│   ├── InfoScreen.kt                # In-app setup guide
│   ├── InfoData.kt                  # Guide content data
│   └── theme/                       # Material 3 theme, colors
│
├── data/
│   ├── ApiKeyEntry.kt               # Key data model (provider, key, baseUrl, model)
│   ├── LlmKeyManager.kt             # Key persistence, active key, reordering, failover tracking
│   ├── LlmRouter.kt                 # Waterfall failover caller, per-provider dispatch, validation
│   ├── AppSettings.kt               # DataStore preferences
│   └── MessageDatabase.kt           # Room DB — message history
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
git clone https://github.com/YOUR_USERNAME/ZeroClawAndroid.git
cd ZeroClawAndroid

# 2. Open in Android Studio
#    File → Open → select the ZeroClawAndroid folder

# 3. Wait for Gradle sync

# 4. Connect your Android device (USB debugging ON) or start an emulator

# 5. Click ▶ Run
```

### First-Time Setup

1. Tap **ℹ️** on the home screen for the full setup walkthrough
2. Go to **⚙️ Settings** → **Manage API Keys** → tap **+ Add Key**
3. Add your LLM provider key (or paste a cURL command)
4. Tap **Test Key** to verify it works
5. (Optional) Add your Telegram Bot token in Settings
6. (Optional) Add Twilio credentials for WhatsApp
7. Tap **▶ Start** on the home screen — the service is now running

---

## 🔑 Supported LLM Providers

| Provider | Auth | Default Base URL | Notes |
|---|---|---|---|
| **OpenAI** | Bearer | `https://api.openai.com/v1` | GPT-4o, GPT-4o-mini, etc. |
| **Google Gemini** | API key | `https://generativelanguage.googleapis.com/v1beta` | Lists all available models |
| **Anthropic Claude** | x-api-key | `https://api.anthropic.com/v1` | Claude 3.5, Haiku, etc. |
| **OpenRouter** | Bearer | `https://openrouter.ai/api/v1` | 400+ models |
| **Ollama** | None | `http://127.0.0.1:11434` | Local models on device |
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
| Serialization | Gson |
| Navigation | Jetpack Navigation Compose |
| Messaging | Telegram Bot API, Twilio API |
| Tunnel | Cloudflare Tunnel / ngrok |

---

## 🛣️ Roadmap — What Should Be Added

These are the planned features and improvements for future development:

### 🔴 High Priority
- [ ] **Conversation memory** — maintain per-user chat history so the AI remembers context across messages, not just single-turn replies
- [ ] **WhatsApp direct API** — replace Twilio with WhatsApp Business Cloud API (Meta) for free messaging
- [ ] **Custom system prompt** — let the user configure the AI's personality/instructions from the Settings screen
- [ ] **Per-key model selection** — for OpenAI / Anthropic / OpenRouter keys, add a model picker (not just Gemini)
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
- [ ] **Log viewer screen** — in-app scrollable log of all messages and API calls
- [ ] **Key usage stats** — show per-key call count, success rate, last used time
- [ ] **Export/import config** — backup and restore all settings and keys as a JSON file
- [ ] **Notification quick-reply** — reply to messages directly from the notification shade
- [ ] **Home screen widget** — show service status and quick start/stop from the launcher
- [ ] **Auto-restart on crash** — WorkManager periodic check to restart the service if it dies

### 🔵 Advanced / Future
- [ ] **Tool use / function calling** — let the AI call local device functions (read contacts, calendar, send SMS)
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

- [Telegram Bot API](https://core.telegram.org/bots/api)
- [Twilio](https://www.twilio.com)
- [OpenAI API](https://platform.openai.com)
- [Google Gemini API](https://ai.google.dev)
- [Anthropic API](https://docs.anthropic.com)
- [OpenRouter](https://openrouter.ai)
- [Ollama](https://ollama.com)
- [Cloudflare Tunnel](https://developers.cloudflare.com/cloudflare-one/connections/connect-networks/)

---

<div align="center">
Made with ❤️ — built to run on your pocket supercomputer
</div>

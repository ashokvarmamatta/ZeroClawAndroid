<div align="center">

<img src="screenshots/app_icon.png" width="120" alt="ZeroClaw Icon" />

# ZeroClaw Android

**A 24/7 AI agent daemon for Android — 11 messaging channels, 28 built-in AI tools, advanced AI orchestration. Runs entirely on your phone.**

[![Android](https://img.shields.io/badge/Platform-Android%2026%2B-green?logo=android)](https://developer.android.com)
[![Kotlin](https://img.shields.io/badge/Language-Kotlin-purple?logo=kotlin)](https://kotlinlang.org)
[![Compose](https://img.shields.io/badge/UI-Jetpack%20Compose-blue?logo=jetpack-compose)](https://developer.android.com/compose)
[![License](https://img.shields.io/badge/License-MIT-yellow)](LICENSE)

</div>

---

## 📖 What is ZeroClaw?

ZeroClaw is an **Android-native AI agent daemon** that turns your phone into an always-on AI backend. It runs as a foreground service, listens across **11 messaging channels**, routes messages through your LLM providers with automatic failover, and now features **advanced AI orchestration** — streaming responses, multi-agent pipelines, configurable agent profiles, a visual workflow engine, tool-loop detection, extended thinking mode, and automatic conversation summarization. All of this runs on your pocket supercomputer, with no server required.

```
You → 11 messaging channels (Telegram / Slack / Matrix / Discord / ...)
              ↓
    ZeroClaw Service (background daemon)
              ↓
    Advanced AI Layer:
      SystemPromptManager → ThinkingMode → StreamingResponse
      MultiAgent → AgentProfiles → WorkflowEngine
      ToolLoopDetector → ConversationSummarizer
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

### 🧠 Advanced AI Systems (Phases 110-117)

#### Phase 110 — SystemPromptManager
- **Per-channel system prompts** — configure a distinct AI personality and instructions for each messaging channel
- **Per-user overrides** — administrators can assign custom prompts to individual users
- **Prompt templates** — built-in library of reusable system prompt templates (Assistant, Coder, Analyst, Creative Writer, etc.)
- **Variable injection** — use `{{username}}`, `{{channel}}`, `{{date}}` placeholders in prompts
- Hot-reload — prompt changes take effect on the next message, no service restart needed

#### Phase 111 — StreamingResponse
- **Token-level streaming** — responses stream word-by-word into chat as the LLM generates them
- Supports all streaming-capable providers: OpenAI, Anthropic, Gemini, OpenRouter
- **Typing indicator** — sends "typing…" status while streaming to supported channels
- Graceful fallback to full response for channels that don't support streaming edits

#### Phase 112 — MultiAgent
- **Agent pipeline orchestration** — chain multiple AI agents in sequence or parallel
- Each agent has its own system prompt, tool set, and provider assignment
- **Handoff protocol** — agent A's output is automatically passed as input to agent B
- Configurable pipeline topology: linear chain, fan-out, fan-in, and conditional branching
- Pipeline results are assembled and delivered as a single unified reply

#### Phase 113 — AgentProfiles
- **Named agent personas** — define reusable agent profiles (Researcher, Summarizer, Code Reviewer, etc.)
- Each profile specifies: system prompt, allowed tools, preferred model, max tokens, temperature
- **Profile switching** — users can invoke a specific profile with `/agent <profile-name>`
- Profiles persist to storage and survive app restarts

#### Phase 114 — WorkflowEngine
- **Visual workflow designer** — compose multi-step AI workflows in the Settings UI
- Workflow steps: Trigger → Condition → Action (LLM call, tool call, channel message, delay)
- **Scheduled workflows** — trigger workflows on a cron schedule or on-demand
- Supports loops, conditional branches, and variable passing between steps
- Example workflows: "Daily news digest → summarize → post to Telegram"

#### Phase 115 — ToolLoopDetector
- **Infinite loop prevention** — detects when the LLM is calling the same tool repeatedly with identical arguments
- Configurable loop detection threshold (default: 3 identical calls)
- Automatically breaks the loop and instructs the LLM to try a different approach
- Loop events are logged and surfaced in the live log viewer

#### Phase 116 — ThinkingMode
- **Extended reasoning** — enables Claude's extended thinking / OpenAI o1-style chain-of-thought mode
- Thinking tokens are hidden from the user by default (configurable to show)
- **Thinking budget** — configurable max thinking tokens per request (1k–32k)
- Automatically selects thinking-capable models (claude-3-7-sonnet, o1, o3-mini)
- Toggle per-conversation or globally in Settings

#### Phase 117 — ConversationSummarizer
- **Automatic context compression** — when conversation history exceeds the token threshold, older messages are summarized into a compact summary
- Summary is injected as a system message, preserving context without blowing up the token budget
- **Configurable threshold** — set the sliding window size (default: last 20 messages before summarizing)
- Manual `/summarize` command available in any channel
- Summaries stored to Room DB and retrievable with the Memory tool

### 💬 11 Messaging Channels

- **Telegram**, **WhatsApp** (Twilio), **Discord**, **Signal**
- **Slack** — Slack Bot via Events API and Web API
- **Matrix** — Matrix protocol client, federated network support
- **IRC** — classic IRC bot via TCP socket
- **Microsoft Teams** — Bot Framework integration
- **Twitch** — Twitch Chat bot via IRC/TMI
- **LINE** — LINE Messaging API
- **WebChat** — built-in HTTP WebSocket chat server at your tunnel URL

All channels share per-chat history, all 28 tools, and now all advanced AI features.

### 🔧 AI Tools — 28 Built-in Tools

#### Core Tools (10)
- Web Search, Web Fetch, Memory, PDF Reader, Image Analysis
- Cron/Scheduled Tasks, Status/Diagnostics, GitHub, Notion, Email

#### Extended Toolbox (18 — Phases 85-102)
- Summarize, Translate, ImageGen, SpeechToText, TextToSpeech
- Calendar, Contacts, Location, Calculator, RSS
- QR Code, FileManager, Clipboard, Spotify, SmartHome
- BraveTool, Bookmark

### 🔑 Multi-Provider API Key Manager
- Unlimited keys, cURL import, live testing, Gemini model picker, priority reordering
- **Set Active key**, per-model selection, auto failover

### 📱 Offline Mode + Live Logs + Tunnel Support
- MediaPipe `.bin` offline models, real-time log viewer, Cloudflare Tunnel / ngrok

---

## 🏗️ Architecture

```
app/src/main/java/ai/zeroclaw/android/
│
├── MainActivity.kt
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
├── intelligence/                        # Phase 110-117
│   ├── SystemPromptManager.kt           # Per-channel/user system prompts + templates
│   ├── StreamingResponse.kt             # Token-level streaming responses
│   ├── MultiAgent.kt                    # Multi-agent pipeline orchestration
│   ├── AgentProfiles.kt                 # Named agent personas with tool/model config
│   ├── WorkflowEngine.kt                # Visual multi-step workflow composer
│   ├── ToolLoopDetector.kt              # Infinite tool-call loop prevention
│   ├── ThinkingMode.kt                  # Extended reasoning / chain-of-thought
│   └── ConversationSummarizer.kt        # Auto context compression with summaries
│
├── tools/
│   ├── ToolSystem.kt
│   ├── WebSearchTool.kt, WebFetchTool.kt, MemoryTool.kt
│   ├── PdfReadTool.kt, ImageAnalysisTool.kt, CronTool.kt
│   ├── StatusTool.kt, GitHubTool.kt, NotionTool.kt, EmailTool.kt
│   ├── SummarizeTool.kt, TranslateTool.kt, ImageGenTool.kt
│   ├── SpeechToTextTool.kt, TextToSpeechTool.kt
│   ├── CalendarTool.kt, ContactsTool.kt, LocationTool.kt
│   ├── CalculatorTool.kt, RssTool.kt, QrCodeTool.kt
│   ├── FileManagerTool.kt, ClipboardTool.kt, SpotifyTool.kt
│   ├── SmartHomeTool.kt, BraveTool.kt, BookmarkTool.kt
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

### Build & Run

```bash
git clone https://github.com/ashokvarmamatta/ZeroClawAndroid.git
cd ZeroClawAndroid
# Open in Android Studio → File → Open → ZeroClawAndroid
# Wait for Gradle sync, then click ▶ Run
```

### First-Time Setup

1. Tap **ℹ️** on the home screen for the full setup walkthrough
2. Add your LLM API key in **Settings → Manage API Keys**
3. Configure system prompts per channel in **Settings → Agent Profiles**
4. Enable ThinkingMode or MultiAgent pipelines for power users in **Settings → Advanced AI**
5. Add channel credentials (Telegram token, Slack token, etc.)
6. Tap **▶ Start** — all channels and AI systems are now active

---

## 🔑 Supported LLM Providers

| Provider | Auth | Default Base URL | Notes |
|---|---|---|---|
| **OpenAI** | Bearer | `https://api.openai.com/v1` | GPT-4o, o1, o3-mini (ThinkingMode) |
| **Google Gemini** | API key | `https://generativelanguage.googleapis.com/v1beta` | Streaming + Google Search grounding |
| **Anthropic Claude** | x-api-key | `https://api.anthropic.com/v1` | Extended thinking (claude-3-7-sonnet) |
| **OpenRouter** | Bearer | `https://openrouter.ai/api/v1` | 400+ models |
| **Ollama** | None | `http://127.0.0.1:11434` | Local models on device |
| **Offline** | None | On-device | MediaPipe `.bin` models |
| **Custom endpoint** | Bearer | *(your Base URL)* | Any OpenAI-compatible API |

---

## 📦 Tech Stack

| Layer | Technology |
|---|---|
| Language | Kotlin |
| UI | Jetpack Compose + Material Design 3 |
| Background | Android Foreground Service + WorkManager |
| HTTP | OkHttp + Retrofit |
| Streaming | OkHttp SSE / Server-Sent Events |
| Storage | Room (messages + memory + summaries) + DataStore |
| Serialization | Gson |
| Navigation | Jetpack Navigation Compose |
| Offline AI | MediaPipe LlmInference |
| Advanced AI | SystemPromptManager, MultiAgent, WorkflowEngine, ThinkingMode |
| Messaging | Telegram, Twilio, Discord, Signal, Slack, Matrix, IRC, Teams, Twitch, LINE |
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

### ✅ Core Tools (10 tools)
- [x] Web Search, Web Fetch, Memory, PDF Reader, Image Analysis ✅
- [x] Cron, Status/Diagnostics, GitHub, Notion, Email ✅

### ✅ Extended Toolbox — Phases 85-102 (18 tools)
- [x] Summarize, Translate, ImageGen, SpeechToText, TextToSpeech ✅
- [x] Calendar, Contacts, Location, Calculator, RSS ✅
- [x] QR Code, FileManager, Clipboard, Spotify, SmartHome, BraveTool, Bookmark ✅

### ✅ Messaging Channels — Phases 103-109
- [x] Telegram, WhatsApp, Discord, Signal ✅
- [x] Slack, Matrix, IRC, Teams, Twitch, LINE, WebChat ✅

### ✅ Advanced AI Systems — Phases 110-117
- [x] SystemPromptManager — per-channel/user prompts + templates (Phase 110) ✅
- [x] StreamingResponse — token-level streaming to all channels (Phase 111) ✅
- [x] MultiAgent — pipeline orchestration with handoff protocol (Phase 112) ✅
- [x] AgentProfiles — named personas with tool/model config (Phase 113) ✅
- [x] WorkflowEngine — visual multi-step workflow composer (Phase 114) ✅
- [x] ToolLoopDetector — infinite loop prevention (Phase 115) ✅
- [x] ThinkingMode — extended reasoning / chain-of-thought (Phase 116) ✅
- [x] ConversationSummarizer — auto context compression (Phase 117) ✅

### 🔲 Upcoming
- [ ] Vector memory with embeddings + HybridSearch (Phase 118-119)
- [ ] QueryExpansion + TemporalDecay + SessionManager (Phase 120-122)
- [ ] HooksSystem + PluginSystem (Phase 123-124)
- [ ] BiometricLock + DevicePairing (Phase 126-127)
- [ ] ExportImportConfig + ThemeManager (Phase 131-132)
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
This Android app is built on top of the [**ZeroClaw**](https://github.com/zeroclaw-labs/zeroclaw) project by [ZeroClaw Labs](https://github.com/zeroclaw-labs). ZeroClaw Android extends it into a native Android experience with 11 channels, 28 tools, advanced AI orchestration, and a full Material Design 3 UI.

### Libraries & Services
- [Telegram Bot API](https://core.telegram.org/bots/api) — [Twilio](https://www.twilio.com) — [Discord Gateway](https://discord.com/developers/docs/topics/gateway)
- [Slack API](https://api.slack.com) — [Matrix Spec](https://spec.matrix.org) — [Microsoft Bot Framework](https://dev.botframework.com)
- [Twitch TMI](https://dev.twitch.tv) — [LINE Messaging API](https://developers.line.biz)
- [OpenAI API](https://platform.openai.com) — [Google Gemini API](https://ai.google.dev) — [Anthropic API](https://docs.anthropic.com)
- [OpenRouter](https://openrouter.ai) — [Ollama](https://ollama.com)
- [MediaPipe](https://ai.google.dev/edge/mediapipe/solutions/genai/llm_inference)
- [Pollinations.ai](https://pollinations.ai) — [Brave Search API](https://brave.com/search/api/)
- [Cloudflare Tunnel](https://developers.cloudflare.com/cloudflare-one/connections/connect-networks/)

---

<div align="center">
Made with ❤️ — built to run on your pocket supercomputer
</div>

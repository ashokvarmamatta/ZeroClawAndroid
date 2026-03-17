<div align="center">

<img src="screenshots/app_icon.png" width="120" alt="ZeroClaw Icon" />

# ZeroClaw Android

**A 24/7 AI agent daemon for Android — 11 messaging channels, 28 built-in AI tools, advanced AI orchestration, and vector memory with RAG. Runs entirely on your phone.**

[![Android](https://img.shields.io/badge/Platform-Android%2026%2B-green?logo=android)](https://developer.android.com)
[![Kotlin](https://img.shields.io/badge/Language-Kotlin-purple?logo=kotlin)](https://kotlinlang.org)
[![Compose](https://img.shields.io/badge/UI-Jetpack%20Compose-blue?logo=jetpack-compose)](https://developer.android.com/compose)
[![License](https://img.shields.io/badge/License-MIT-yellow)](LICENSE)

</div>

---

## 📖 What is ZeroClaw?

ZeroClaw is an **Android-native AI agent daemon** that turns your phone into an always-on AI backend. It runs as a foreground service across **11 messaging channels**, with **28 built-in AI tools**, **advanced AI orchestration** (streaming, multi-agent pipelines, workflow engine, thinking mode), and now a **full vector memory and RAG system** — embedding-based semantic search, hybrid BM25+vector retrieval with RRF re-ranking, query expansion, temporal memory decay, and per-user session management. All running on your pocket supercomputer.

```
You → 11 messaging channels
              ↓
    ZeroClaw Service (background daemon)
              ↓
    Advanced AI: SystemPromptManager → ThinkingMode → StreamingResponse
                 MultiAgent → WorkflowEngine → ToolLoopDetector
              ↓
    Vector Memory & RAG:
      VectorMemory (embeddings) → HybridSearch + RRF
      QueryExpansion → TemporalDecay → SessionManager
              ↓
    Tool System (28 tools) → LLM Router → Reply
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

### 🔮 Vector Memory & RAG (Phases 118-122)

#### Phase 118 — VectorMemory with Embeddings
- **Semantic memory storage** — all saved memories are embedded using a local sentence-transformer model (or OpenAI `text-embedding-3-small`)
- Embeddings stored in a Room-backed vector table alongside the raw text
- **Semantic similarity search** — recall memories by meaning, not just keyword matching; "what do I know about Python?" finds memories tagged "coding", "scripts", "programming"
- Per-user vector spaces — each user's memories are isolated
- Configurable embedding dimension (default: 384 for local, 1536 for OpenAI)

#### Phase 119 — HybridSearch + RRF
- **Hybrid retrieval** — combines BM25 keyword search with vector cosine similarity search in a single query
- **Reciprocal Rank Fusion (RRF)** re-ranking merges both result sets into one optimally ordered list
- Configurable RRF constant k (default: 60) and alpha blend weight between BM25 and vector scores
- Falls back to pure keyword search when embeddings are unavailable (offline mode)
- Dramatically higher retrieval precision vs. keyword-only or vector-only search

#### Phase 120 — QueryExpansion
- **Automatic query expansion** — before searching memory, the LLM generates 3-5 semantically related query variants
- All variants are searched in parallel, results merged and de-duplicated
- **HyDE (Hypothetical Document Embedding)** — generates a hypothetical answer to the query, embeds it, and uses it as the search vector for higher semantic precision
- Expansion is transparent to the user; the `/recall` command benefits automatically

#### Phase 121 — TemporalDecay
- **Time-aware memory scoring** — older memories are down-weighted using an exponential decay function (configurable half-life, default: 30 days)
- Recent memories surface higher in retrieval results without forgetting older ones entirely
- **Memory reinforcement** — accessing or mentioning a memory resets its decay clock, keeping frequently referenced information fresh
- Decay weights are factored into the final RRF score

#### Phase 122 — SessionManager
- **Conversation sessions** — each chat session (bounded by `/new` or a configurable inactivity timeout) is tracked as a named session
- **Session summaries** — at session end, a ConversationSummarizer summary is saved as a high-priority memory with session metadata
- **Session replay** — `/session list` shows all sessions; `/session load <id>` restores the context of a previous session
- Cross-session continuity — the agent automatically recalls relevant memories from past sessions to answer current questions
- Session data stored in Room DB, queryable via the Memory tool

### 🧠 Advanced AI Systems (Phases 110-117)
- **SystemPromptManager** — per-channel/user system prompts with templates and variable injection
- **StreamingResponse** — token-level streaming with typing indicators across all channels
- **MultiAgent** — pipeline orchestration with linear, fan-out, fan-in, and conditional topologies
- **AgentProfiles** — named personas with per-profile tool sets, models, temperature, and max tokens
- **WorkflowEngine** — visual multi-step workflow composer (Trigger → Condition → Action)
- **ToolLoopDetector** — infinite tool-call loop prevention with auto-recovery
- **ThinkingMode** — extended reasoning / chain-of-thought (Claude extended thinking, OpenAI o1)
- **ConversationSummarizer** — automatic context compression when history exceeds token threshold

### 💬 11 Messaging Channels
- **Telegram**, **WhatsApp** (Twilio), **Discord**, **Signal** (original 4)
- **Slack** — Events API bot with slash commands
- **Matrix** — federated Matrix protocol client
- **IRC** — classic IRC bot via TCP socket
- **Microsoft Teams** — Bot Framework integration via webhook
- **Twitch** — Twitch Chat bot with !command support
- **LINE** — LINE Messaging API via webhook
- **WebChat** — built-in browser-accessible WebSocket chat at your tunnel URL

### 🔧 AI Tools — 28 Built-in Tools

#### Core Tools (10)
Web Search, Web Fetch, Memory, PDF Reader, Image Analysis, Cron, Status/Diagnostics, GitHub, Notion, Email

#### Extended Toolbox (18 — Phases 85-102)
Summarize, Translate, ImageGen, SpeechToText, TextToSpeech, Calendar, Contacts, Location, Calculator, RSS, QR Code, FileManager, Clipboard, Spotify, SmartHome, BraveTool, Bookmark

**Memory tool now powered by VectorMemory** — `/recall` uses hybrid semantic + keyword search with temporal decay ranking.

### 🔑 Multi-Provider API Key Manager
- Unlimited keys, cURL import, live testing, Gemini model picker, priority reordering, auto failover

### 📱 Offline Mode + Live Logs + Tunnel
- MediaPipe `.bin` models, real-time log viewer, Cloudflare Tunnel / ngrok

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
│   ├── MessageDatabase.kt
│   └── MemoryDatabase.kt            # Extended: now stores embeddings + sessions
│
├── memory/                              # Phase 118-122 — Vector Memory & RAG
│   ├── VectorMemory.kt                  # Embedding generation + cosine similarity store
│   ├── HybridSearch.kt                  # BM25 keyword + vector search with RRF re-ranking
│   ├── QueryExpansion.kt                # LLM-driven query expansion + HyDE
│   ├── TemporalDecay.kt                 # Exponential decay scoring for memory freshness
│   └── SessionManager.kt               # Session tracking, summaries, cross-session recall
│
├── intelligence/                        # Phase 110-117
│   ├── SystemPromptManager.kt
│   ├── StreamingResponse.kt
│   ├── MultiAgent.kt
│   ├── AgentProfiles.kt
│   ├── WorkflowEngine.kt
│   ├── ToolLoopDetector.kt
│   ├── ThinkingMode.kt
│   └── ConversationSummarizer.kt
│
├── tools/
│   ├── ToolSystem.kt
│   ├── [28 tool files — WebSearch through Bookmark]
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
- (Optional) OpenAI API key for high-quality `text-embedding-3-small` embeddings

### Build & Run

```bash
git clone https://github.com/ashokvarmamatta/ZeroClawAndroid.git
cd ZeroClawAndroid
# Open in Android Studio → File → Open → ZeroClawAndroid
# Wait for Gradle sync, then click ▶ Run
```

### First-Time Setup

1. Tap **ℹ️** for the full setup walkthrough
2. Add your LLM API key in **Settings → Manage API Keys**
3. (Optional) Add an embedding API key in **Settings → Vector Memory** for semantic search
4. Configure system prompts and agent profiles in **Settings → Advanced AI**
5. Add credentials for your messaging channels
6. Tap **▶ Start** — all channels, AI systems, and memory are now active

### Using Vector Memory

```
/remember My name is Alice and I work on robotics
/recall Do I know anyone who works in engineering?
# → Finds "Alice ... robotics" via semantic similarity

/session list
# → Lists all past conversation sessions

/session load 3
# → Restores context from session 3
```

---

## 🔑 Supported LLM Providers

| Provider | Auth | Default Base URL | Notes |
|---|---|---|---|
| **OpenAI** | Bearer | `https://api.openai.com/v1` | GPT-4o, o1, o3-mini; `text-embedding-3-small` for RAG |
| **Google Gemini** | API key | `https://generativelanguage.googleapis.com/v1beta` | Streaming + Google Search grounding |
| **Anthropic Claude** | x-api-key | `https://api.anthropic.com/v1` | Extended thinking (claude-3-7-sonnet) |
| **OpenRouter** | Bearer | `https://openrouter.ai/api/v1` | 400+ models |
| **Ollama** | None | `http://127.0.0.1:11434` | Local models; local embeddings fallback |
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
| Storage | Room (messages + vector memory + sessions) + DataStore |
| Vector Search | Custom BM25 + cosine similarity + RRF (on-device) |
| Embeddings | OpenAI `text-embedding-3-small` / local sentence-transformer |
| Serialization | Gson |
| Navigation | Jetpack Navigation Compose |
| Offline AI | MediaPipe LlmInference |
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

### ✅ Core Tools + Extended Toolbox (28 tools — Phases baseline + 85-102)
- [x] All 28 tools implemented and available across all channels ✅

### ✅ Messaging Channels — Phases 103-109
- [x] All 11 channels: Telegram, WhatsApp, Discord, Signal, Slack, Matrix, IRC, Teams, Twitch, LINE, WebChat ✅

### ✅ Advanced AI Systems — Phases 110-117
- [x] SystemPromptManager, StreamingResponse, MultiAgent ✅
- [x] AgentProfiles, WorkflowEngine, ToolLoopDetector ✅
- [x] ThinkingMode, ConversationSummarizer ✅

### ✅ Vector Memory & RAG — Phases 118-122
- [x] VectorMemory — embedding-based semantic memory store (Phase 118) ✅
- [x] HybridSearch + RRF — BM25 + vector retrieval fusion (Phase 119) ✅
- [x] QueryExpansion — LLM query variants + HyDE (Phase 120) ✅
- [x] TemporalDecay — exponential memory freshness scoring (Phase 121) ✅
- [x] SessionManager — session tracking, summaries, cross-session recall (Phase 122) ✅

### 🔲 Upcoming
- [ ] HooksSystem — pre/post-message hook pipeline (Phase 123)
- [ ] PluginSystem — installable user plugins (Phase 124)
- [ ] WebViewTool + MediaPipelineTool (Phase 125)
- [ ] RichNotifications + QuickReply (Phase 126)
- [ ] BiometricLock + DevicePairing (Phase 127-128)
- [ ] AutoRecovery (Phase 130)
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
This Android app is built on top of the [**ZeroClaw**](https://github.com/zeroclaw-labs/zeroclaw) project by [ZeroClaw Labs](https://github.com/zeroclaw-labs). ZeroClaw Android extends it with 11 channels, 28 tools, advanced AI orchestration, vector memory & RAG, and a full Material Design 3 UI.

### Libraries & Services
- [Telegram Bot API](https://core.telegram.org/bots/api) — [Twilio](https://www.twilio.com) — [Discord Gateway](https://discord.com/developers/docs/topics/gateway)
- [Slack API](https://api.slack.com) — [Matrix Spec](https://spec.matrix.org) — [Microsoft Bot Framework](https://dev.botframework.com)
- [Twitch TMI](https://dev.twitch.tv) — [LINE Messaging API](https://developers.line.biz)
- [OpenAI API](https://platform.openai.com) (GPT + Whisper + DALL-E + Embeddings)
- [Google Gemini API](https://ai.google.dev) — [Anthropic API](https://docs.anthropic.com)
- [OpenRouter](https://openrouter.ai) — [Ollama](https://ollama.com)
- [MediaPipe](https://ai.google.dev/edge/mediapipe/solutions/genai/llm_inference)
- [Pollinations.ai](https://pollinations.ai) — [Brave Search API](https://brave.com/search/api/)
- [Cloudflare Tunnel](https://developers.cloudflare.com/cloudflare-one/connections/connect-networks/)

---

<div align="center">
Made with ❤️ — built to run on your pocket supercomputer
</div>

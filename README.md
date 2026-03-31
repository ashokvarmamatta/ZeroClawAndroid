<div align="center">

<img src="screenshots/app_icon.png" width="120" alt="ZeroClaw Icon" />

# ZeroClaw Android

**A 24/7 AI agent daemon for Android — 11 messaging channels, 30 built-in AI tools (all opt-in), 25+ autonomous agent templates with 21 free API sources, interactive Tool Playground, and a modular architecture where you enable only what you need. Runs entirely on your phone.**

[![Android](https://img.shields.io/badge/Platform-Android%2026%2B-green?logo=android)](https://developer.android.com)
[![Kotlin](https://img.shields.io/badge/Language-Kotlin-purple?logo=kotlin)](https://kotlinlang.org)
[![Compose](https://img.shields.io/badge/UI-Jetpack%20Compose-blue?logo=jetpack-compose)](https://developer.android.com/compose)
[![License](https://img.shields.io/badge/License-MIT-yellow)](LICENSE)

</div>

---

## Why This Project Exists

This is a **personal project** built out of curiosity — to see how far I can push Android as a platform for running AI agents. It's not about writing every line from scratch. It's about **figuring out what's actually possible on a phone** and making it work:

- **Can an Android foreground service reliably run an AI daemon 24/7?** Yes, with watchdog recovery, Doze awareness, and exponential backoff.
- **Can a phone serve as an OpenAI-compatible API server?** Yes, on port 8088 with full tool pipeline.
- **Can MediaPipe run LLMs offline on-device without crashing?** Yes, but you need Mutex serialization to prevent JNI concurrent access crashes — learned that the hard way.
- **Can you route AI responses to 11 different messaging channels?** Yes, each with its own protocol quirks (Telegram long-polling, Discord Gateway, Slack Events API, Matrix federation, IRC sockets, etc.)
- **Can autonomous agents scrape websites, call free APIs, detect changes, and push updates on a schedule?** Yes, with hash-based change detection and multi-channel delivery.

Every feature started as a question like these. Some worked on the first try. Many didn't — the [bugs.md](bugs.md) file has the full history of real bugs found and fixed, from JNI crashes to garbage offline model replies to silent API fallback failures.

I use Claude as a coding tool the same way I'd use Stack Overflow or documentation — to move faster on implementation so I can spend more time on **architecture decisions, debugging real device behavior, and figuring out what Android actually allows**. The interesting part of this project isn't the Kotlin syntax. It's deciding that agents should use direct API calls instead of web scraping, that tools should be disabled by default for security, that the LLM router should waterfall through providers on failure, and that the whole thing should work as a drop-in OpenAI replacement.

This project has real bugs, real limitations, and is actively being worked on.

---

## What is ZeroClaw?

ZeroClaw is an **Android-native AI agent daemon** that turns your phone into an always-on AI backend. Everything is **modular and opt-in** — you enable only the channels, tools, and features you need.

No cloud subscription. No always-on PC. Just your Android device.

```
You → Enable your channels (Telegram / Discord / Slack / WhatsApp / ...)
              ↓
    ZeroClaw Service (background daemon)
              ↓
    LLM Router → Multi-provider failover (Gemini, OpenAI, Anthropic, Ollama, OpenRouter)
              ↓
    Tool System (30 tools — all disabled by default, enable what you need)
              ↓
    Optional modules (enable in Settings):
      ├── Agents: 25+ templates, 21 free APIs, scheduled web scraping + delivery
      ├── Tool Playground: test any tool live before using it
      ├── Advanced AI: multi-agent, delegate/spawn, workflows, hint routing
      ├── Memory: vector search, semantic cache, MMR reranking
      ├── Infrastructure: hooks, plugins, biometric lock, audit log, rate limiting
      └── Integrations: Composio (1000+ apps), MCP servers, A2A protocol
              ↓
    Reply → your enabled channels
```

---

## Screenshots

| Home | Settings | AI Configuration |
|------|----------|------------------|
| ![Home](screenshots/01_home.png) | ![Settings](screenshots/02_settings.png) | ![AI Config](screenshots/03_ai_config.png) |

| AI Config (Model Selection) | Info & Setup Guide |
|-----------------------------|-------------------|
| ![AI Config Scrolled](screenshots/04_ai_config_scrolled.png) | ![Info Guide](screenshots/05_info_guide.png) |

---

## Features

### Autonomous Agents

- **25+ ready-made agent templates** — crypto prices, gold tracker, stock market, weather, news, sports scores, GitHub trending, YouTube trending, IPO tracker, earthquake alerts, and more
- **21 free API data sources** — CoinGecko, AlphaVantage, Metals.live, wttr.in, Football-Data.org, USGS, MFAPI.in, etc. No API keys needed for most
- **Web scraping agents** — monitor any URL on a schedule, AI extracts what you want, pushes to your channel
- **Delivery channels:** Telegram, Discord, Slack, WhatsApp, Email
- **Flexible intervals:** 5 min to 24 h (or custom)
- **AI extraction** — optional prompt to extract specific data (e.g. "top 5 headlines")
- **Change detection** — only pushes when content actually changed
- **Run Now** — instant test from the agent card
- **Test Fetch** — preview URL content before saving
- **Multi-channel delivery** — send to multiple channels with per-channel chat IDs
- **API-first agents** — direct API calls (no web scraping needed) for faster, more reliable data

### Tool Playground

- **Interactive tool testing** — test any of the 30 tools individually before using them in real conversations
- Per-tool enable/disable toggles, model selector, category grid
- Bottom sheet form with custom arguments per tool
- Image picker for ImageAnalysis testing
- All test runs logged to Home Screen activity feed

### 11 Messaging Channels

All channels are opt-in — enable and configure only what you use:

| Channel | How |
|---------|-----|
| **Telegram** | Bot token + long polling. Group chat support with @mention |
| **Discord** | Bot token + Gateway. Server channel support |
| **Slack** | Bot token + Events API. Channel posting |
| **WhatsApp** | Twilio API |
| **Matrix** | Federated protocol client |
| **IRC** | TCP socket bot |
| **Microsoft Teams** | Bot Framework |
| **Twitch** | Chat bot with !command support |
| **LINE** | Messaging API |
| **Signal** | Signal bridge |
| **WebChat** | Built-in browser chat (voice input + TTS playback) |

### 30 AI Tools (all disabled by default)

Enable only what you need in Settings → AI Tools:

**Core (10):** Web Search (DuckDuckGo), Web Fetch, Memory (vector-backed), PDF Reader, Image Analysis, Cron/Scheduled Tasks, Status/Diagnostics, GitHub, Notion, Email

**Extended (14):** Summarize, Translate (50+ languages), ImageGen (Pollinations + DALL-E), SpeechToText (Whisper), TextToSpeech (Android TTS), Calendar, Contacts, Location/Geocoding, Calculator, RSS, QR Code, FileManager, Clipboard, Bookmark

**Advanced (6):** ComposioTool (1000+ app integrations), DelegateTool (named agent delegation), SpawnTool (async background agents), MessageTool (proactive messaging), McpClient (MCP server tools), PushoverTool (push notifications)

### Multi-Provider LLM Support

| Provider | Notes |
|----------|-------|
| **Google Gemini** | Streaming, Google Search grounding, model picker |
| **OpenAI** | GPT-4o, o1, o3-mini; Whisper; DALL-E; embeddings |
| **Anthropic Claude** | Extended thinking support |
| **OpenRouter** | 400+ models from all providers |
| **Ollama** | Local models on device |
| **Offline** | MediaPipe `.bin` models, no internet needed |
| **Custom endpoint** | Any OpenAI-compatible API (Modal, LiteLLM, vLLM, etc.) |

- **Unlimited API keys** with priority reordering and automatic failover
- **cURL import** — paste a cURL command to add a key
- **Live key testing** + Gemini model picker with "Check All Models"
- **Per-key usage stats** — call count, token usage, success rate, average latency
- **Hint-based routing** — prefix messages with `hint:reasoning`, `hint:vision`, `hint:fast`, `hint:code`, `hint:creative`, `hint:long` to route to the best provider

### API Server (Port 8088)

ZeroClaw exposes an HTTP API that any app can connect to — chat, tools, memory, everything you've enabled.

**Quick Connect — Any OpenAI-compatible app:**

| Setting | Value |
|---------|-------|
| **Base URL** | `http://<DEVICE_IP>:8088/v1` |
| **API Key** | `zc-no-key-needed` (any non-empty string) |
| **Model** | `zeroclaw` |

**Endpoints:**

| Method | Endpoint | Description |
|--------|----------|-------------|
| `POST` | `/v1/chat/completions` | OpenAI-compatible — full AI pipeline with tools, memory, thinking mode |
| `GET` | `/v1/models` | Available models |
| `POST` | `/api/chat` | Simple chat with session memory |
| `POST` | `/api/generate` | Raw LLM generation (no tools, no history). Supports `json_mode` |
| `GET` | `/api/discover` | Service discovery — version, port, endpoints |
| `GET` | `/` or `/chat` | Built-in web chat UI |

**Works with:** Continue.dev, Cursor, Open WebUI, LangChain, LlamaIndex, AutoGen, CrewAI, Aider, any OpenAI SDK app.

**Generate cURL from the app:** Home Screen → Live Logs → Server Address → Generate cURL.

<details>
<summary>Code examples (Python, JavaScript)</summary>

```python
from openai import OpenAI

client = OpenAI(
    base_url="http://<DEVICE_IP>:8088/v1",
    api_key="zc-no-key-needed"
)

response = client.chat.completions.create(
    model="zeroclaw",
    messages=[
        {"role": "user", "content": "Search the web for today's top tech news"}
    ]
)
print(response.choices[0].message.content)
```

```javascript
import OpenAI from 'openai';

const client = new OpenAI({
  baseURL: 'http://<DEVICE_IP>:8088/v1',
  apiKey: 'zc-no-key-needed'
});

const response = await client.chat.completions.create({
  model: 'zeroclaw',
  messages: [{ role: 'user', content: 'Translate "hello" to Japanese, Spanish, and French' }]
});
console.log(response.choices[0].message.content);
```

</details>

### Memory & RAG

- **VectorMemory** — embedding-based semantic memory (OpenAI or local TF-IDF fallback)
- **Hybrid search** — BM25 + cosine similarity fused with Reciprocal Rank Fusion
- **MMR reranking** — diverse, non-repetitive results via Maximal Marginal Relevance
- **Adaptive retrieval** — auto-selects keyword, vector, or hybrid search based on query type
- **Semantic cache** — avoids re-calling LLM for similar questions (80% cosine threshold, 30min TTL)
- **Memory hygiene** — auto-archives old memories (7d), purges archived (30d), pinned memories are permanent
- **Session tracking** — per-session history with automatic summarization

### Advanced AI

- **Multi-agent orchestration** — pipeline modes: linear, fan-out, fan-in, conditional
- **Agent profiles** — named personas (coder, analyst, creative, tutor) with per-profile tool/model config
- **Delegate & Spawn** — delegate to named agents (wait for result) or spawn background agents (fire-and-forget)
- **Workflow engine** — multi-step pipelines with LLM calls, tool calls, and conditional logic
- **Thinking mode** — extended reasoning (Claude extended thinking, OpenAI o1/o3)
- **Agent identity (AIEOS)** — MBTI personality, OCEAN traits, catchphrases, communication style
- **Tool loop detection** — infinite loop prevention with auto-recovery
- **Conversation summarizer** — automatic context compression

### Infrastructure

- **Hooks system** — 8 hook points (before/after tool call, before/after LLM call, message received/sending, session start/end)
- **Plugin system** — installable .zip plugin packages with manifest, tool definitions, and hooks
- **Biometric lock** — fingerprint/face authentication guard
- **Audit log** — tamper-evident JSONL log of every tool execution with hash chain
- **Rate limiting** — per-user and per-channel message rate limits with admin exemption
- **Approval system** — human-in-the-loop approval for dangerous tool actions (file write, email send, smart home)
- **Auto-recovery** — watchdog, crash reporter, circuit breaker, dead-letter queue
- **Device pairing** — multi-device mDNS discovery + encrypted config sync

### Configuration & UX

- **Custom themes** — 10+ Material You palettes, dark/light/system, Android 12+ dynamic color
- **Export/import config** — AES-256 encrypted full backup (keys, channels, prompts, agents, settings)
- **Per-channel system prompts** — prompt editor with variables, syntax highlighting, template gallery
- **Usage dashboard** — daily/weekly charts, top users, most-used tools, token cost estimation
- **Conversation labels** — colored tags with auto-label rules and cross-channel filter
- **Home screen widget** — service status, channel count, quick start/stop
- **Voice input** — WebChat mic input + TTS playback + optional wake word ("Hey ZeroClaw")
- **Group chat support** — Telegram/Discord/Slack groups with @mention, admin commands, per-group prompts
- **Rich notifications** — reply from notification shade without opening the app
- **Info guide** — every Settings section has an info button linking to a plain-language explanation

### Integrations

- **Composio** — 1000+ OAuth app integrations (GitHub, Gmail, Jira, Notion, Salesforce, etc.) via Composio API. Disabled by default
- **MCP Client** — connect to any MCP server and auto-discover its tools. Disabled by default
- **A2A Server** — Google Agent-to-Agent protocol. Other AI agents can discover and delegate tasks to ZeroClaw. Disabled by default
- **Pushover** — push notifications to iOS/Android/desktop. Disabled by default

### Offline Intelligence

- **MediaPipe on-device models** — run AI with zero internet
- **Smart summarizer** — detects refusal patterns from small models and falls back to direct data formatting
- **Garbage reply detection** — catches hallucinated URLs and irrelevant responses, triggers web data fetch instead
- **JNI crash prevention** — Mutex serialization prevents concurrent model access crashes

---

## Architecture

```
app/src/main/java/ai/zeroclaw/android/
│
├── ui/                  # Jetpack Compose screens
│   ├── HomeScreen, AgentsScreen, AgentCreateSheet
│   ├── ToolPlaygroundScreen, ToolTestSheet, AiToolsScreen
│   ├── ApiKeysScreen, SettingsScreen, InfoScreen
│   └── theme/ThemeManager
│
├── agents/              # Autonomous agent system
│   ├── AgentConfig, AgentManager, AgentTemplates
│   ├── WebScraperAgent
│   └── api/             # 21 free API data sources
│
├── tools/               # 30 AI tools
│   └── ToolSystem + individual tool files
│
├── data/                # LLM routing, keys, settings
│   ├── LlmRouter, LlmKeyManager, ProviderRouter
│   ├── OfflineModelManager, ThemeManager
│   └── Room databases (messages, vectors, sessions, usage, labels)
│
├── ai/                  # AI orchestration
│   ├── AgentIdentity, SystemPromptManager
│   ├── MultiAgent, AgentProfiles, WorkflowEngine
│   └── ThinkingMode, ConversationSummarizer
│
├── memory/              # Vector memory & RAG
│   ├── VectorMemory, HybridSearch, MmrReranker
│   ├── SemanticCache, MemoryHygiene
│   └── SessionManager, QueryExpansion
│
├── infra/               # Platform infrastructure
│   ├── HooksSystem, PluginSystem, BiometricLock
│   ├── AuditLog, RateLimiting, ApprovalSystem
│   ├── McpClient, A2AServer, AutoRecovery
│   └── DevicePairing
│
├── channels/            # 11 messaging channels
│   ├── telegram/, discord/, slack/, whatsapp/
│   ├── matrix/, irc/, signal/, teams/
│   ├── twitch/, line/, webchat/
│
├── service/
│   ├── ZeroClawService (foreground daemon)
│   └── BootReceiver
│
└── webchat/
    └── WebChatServer (HTTP API on port 8088)
```

---

## Getting Started

### Prerequisites
- Android Studio Hedgehog or newer
- Android device or emulator running **Android 8.0 (API 26)+**
- At least one LLM API key (OpenAI, Gemini, Anthropic, etc.)

### Build & Run

```bash
git clone https://github.com/ashokvarmamatta/ZeroClawAndroid.git
cd ZeroClawAndroid
# Open in Android Studio → File → Open → ZeroClawAndroid
# Wait for Gradle sync, then click Run
```

### First-Time Setup

1. Tap **info** on the home screen for the full setup walkthrough
2. Go to **Settings → Manage API Keys** → **+ Add Online Key**
3. Add your LLM key, tap **Test Key**, then **Check All Models**
4. Select which models to use
5. Add channel credentials (Telegram token, Slack token, etc.)
6. Enable any tools you want in Settings → AI Tools
7. Tap **Start** — the service begins running

---

## Tech Stack

| Layer | Technology |
|---|---|
| Language | Kotlin |
| UI | Jetpack Compose + Material Design 3 + Dynamic Color |
| Background | Android Foreground Service + WorkManager |
| HTTP | OkHttp + Retrofit + SSE streaming |
| Storage | Room + DataStore |
| Vector Search | BM25 + cosine similarity + RRF (on-device) |
| Embeddings | OpenAI `text-embedding-3-small` / local TF-IDF fallback |
| Security | BiometricPrompt + AES-256-GCM |
| Offline AI | MediaPipe LlmInference |
| Image Gen | Pollinations.ai (free) / DALL-E 3 |
| Speech | OpenAI Whisper (STT) + Android TTS |
| Serialization | Gson |
| Navigation | Jetpack Navigation Compose |

---

## Contributing

Contributions are welcome! Fork the repo, create a feature branch, and open a Pull Request.

---

## License

This project is licensed under the MIT License — see the [LICENSE](LICENSE) file for details.

---

## Acknowledgements

Built on [ZeroClaw](https://github.com/zeroclaw-labs/zeroclaw) by ZeroClaw Labs. Advanced features inspired by [NullClaw](https://github.com/nullclaw/nullclaw) and [OpenClaw](https://github.com/openclaw/openclaw).

### Libraries & Services
Telegram Bot API, Twilio, Discord Gateway, Slack API, Matrix Spec, Microsoft Bot Framework, Twitch TMI, LINE Messaging API, OpenAI API, Google Gemini API, Anthropic API, OpenRouter, Ollama, MediaPipe, Pollinations.ai, Brave Search API, MyMemory Translation, Composio, Pushover, Model Context Protocol, Cloudflare Tunnel, Android BiometricPrompt, WorkManager, Jetpack Compose, Material Design 3, Room, DataStore, OkHttp, Retrofit.

---

<div align="center">
Made with care — built to run on your pocket supercomputer
</div>

# ZeroClawAndroid — Architecture Blueprint

> **Purpose:** Complete map of every component, screen, service, class, resource, database table, and navigation flow.
> **Rule:** Update this file whenever adding/removing/renaming any component.

---

## App Identity

| Field | Value |
|-------|-------|
| Package | `ai.zeroclaw.android` |
| App Name | ZeroClaw |
| Version | 1.1.0 (versionCode 2) |
| Min SDK | 26 (Android 8.0) |
| Target/Compile SDK | 36 (Android 15) |
| Java | 17 |
| Compose Compiler | 1.5.13 |
| Active Branch | `feat/curl-api-generator` |

---

## Architecture Pattern

```
MVVM + Clean Architecture
├── UI Layer (Jetpack Compose Screens + ViewModels)
├── Domain Layer (Managers / Use Cases)
│   ├── ZeroClawService (Foreground Service — central orchestrator)
│   ├── 11 Channel Bot Managers
│   ├── AI Managers (LlmRouter, MultiAgent, Workflow, etc.)
│   └── Tool System (40+ tools)
└── Data Layer
    ├── Room Databases (memories, messages, bookmarks)
    ├── DataStore Preferences (settings, API keys)
    └── SharedPreferences (agent configs, chat IDs)
```

---

## Navigation Graph (Compose NavHost)

```
MainActivity (NavHost)
├── "home" (start) ──→ HomeScreen
│   ├── → "settings"
│   ├── → "info"
│   ├── → "tool_playground"
│   └── → "agents"
├── "settings" ──→ SettingsScreen
│   ├── → "api_keys"
│   ├── → "ai_tools"
│   └── → "info/{sectionId}"
├── "api_keys" ──→ ApiKeysScreen
├── "ai_tools" ──→ AiToolsScreen
│   └── → "info/{sectionId}"
├── "info" ──→ InfoScreen
├── "info/{sectionId}" ──→ InfoScreen (parametrized)
├── "tool_playground" ──→ ToolPlaygroundScreen
└── "agents" ──→ AgentsScreen
```

No XML navigation graph — fully programmatic via `NavController`.

---

## Source Files (123 Kotlin files)

All under `app/src/main/java/ai/zeroclaw/android/`

### Core
| File | Purpose |
|------|---------|
| `MainActivity.kt` | Entry point, NavHost setup |

### UI Layer (`ui/`)
| File | Purpose |
|------|---------|
| `HomeScreen.kt` | Dashboard: service start/stop, status, logs, connections |
| `SettingsScreen.kt` | All channel/provider configuration |
| `ApiKeysScreen.kt` | LLM key management (add/edit/delete/reorder/test/cURL import) |
| `ToolPlaygroundScreen.kt` | Interactive tool testing |
| `ToolTestSheet.kt` | Modal for detailed tool parameter entry |
| `AgentsScreen.kt` | Agent list, enable/disable, run now, delete |
| `AgentCreateSheet.kt` | Create agent: templates, AI Smart Extract, channel target |
| `AiToolsScreen.kt` | Browse & toggle tools by category |
| `InfoScreen.kt` | Help documentation viewer |
| `InfoData.kt` | Static guide content, sections, feature list |
| `theme/Color.kt` | Color definitions (zeroclaw_red, surface, green, etc.) |
| `theme/Theme.kt` | Material 3 theme (dark/light) |

### Services (`service/`)
| File | Purpose |
|------|---------|
| `ZeroClawService.kt` | Foreground service — manages all bot managers, polling, AI pipeline |
| `BootReceiver.kt` | BOOT_COMPLETED + MY_PACKAGE_REPLACED auto-start |
| `HomeWidget.kt` | Home screen widget (status + quick start/stop) |

### Chat Platform Integrations
| File | Channel | Protocol |
|------|---------|----------|
| `telegram/TelegramBotManager.kt` | Telegram | Long polling (getUpdates) |
| `whatsapp/TwilioWhatsAppManager.kt` | WhatsApp | Twilio REST API |
| `discord/DiscordBotManager.kt` | Discord | Gateway WebSocket + REST |
| `signal/SignalBridgeManager.kt` | Signal | signal-cli REST API bridge |
| `slack/SlackBotManager.kt` | Slack | Socket Mode WebSocket |
| `matrix/MatrixBotManager.kt` | Matrix | Matrix Client-Server API |
| `irc/IrcBotManager.kt` | IRC | Raw TCP socket |
| `teams/TeamsBotManager.kt` | Teams | Bot Framework webhook |
| `twitch/TwitchBotManager.kt` | Twitch | IRC over SSL (TMI) |
| `line/LineBotManager.kt` | LINE | Messaging API webhook |
| `webchat/WebChatServer.kt` | Web Chat | Embedded HTTP server (:8088) |
| `tunnel/TunnelManager.kt` | — | ngrok/Cloudflare tunnel |

### AI & Agent System (`ai/`)
| File | Purpose |
|------|---------|
| `MultiAgentManager.kt` | Spawn sub-agents (max 5 concurrent, depth 3, 2min timeout) |
| `AgentProfileManager.kt` | Named personas (6 built-in + custom) |
| `ConversationSummarizer.kt` | Auto-compress long histories (auto/force/trim modes) |
| `SystemPromptManager.kt` | Per-channel/per-user configurable system prompt |
| `ThinkingMode.kt` | Extended chain-of-thought reasoning |
| `ToolLoopDetector.kt` | 4 detection methods for infinite tool loops |
| `WorkflowEngine.kt` | Multi-step automated pipelines with conditions |
| `StreamingResponseManager.kt` | SSE streaming for OpenAI/Anthropic |

### Agents (`agents/`)
| File | Purpose |
|------|---------|
| `AgentManager.kt` | CRUD + persistence for custom agents |
| `AgentConfig.kt` | Agent data class (name, url, interval, channel, fetchType, format) |
| `AgentTemplates.kt` | 25+ pre-built agent templates |
| `WebScraperAgent.kt` | Periodic web scraping + LLM extraction + delivery |

### Free API Data Sources (`agents/api/`)
| File | Data Source |
|------|------------|
| `FreeApiRegistry.kt` | Registry of all free API sources |
| `ApiDataSource.kt` | Interface for API data sources |
| `ApiRateLimiter.kt` | Shared rate-limiting |
| `CoinGeckoApi.kt` | Cryptocurrency data |
| `CoinCapApi.kt` | Cryptocurrency prices |
| `MetalsLiveApi.kt` | Precious metals prices |
| `ExchangeRateApi.kt` | Foreign exchange rates |
| `UsgsEarthquakeApi.kt` | Real-time earthquake data |
| `MfApiIndiaApi.kt` | Indian mutual fund data |
| `GitHubTrendingApi.kt` | GitHub trending repos |
| `WttrWeatherApi.kt` | Weather forecasts |
| `FootballDataApi.kt` | Sports data |
| `NewsApi.kt` | News headlines |
| `AlphaVantageApi.kt` | Stock market data |
| `TmdbApi.kt` | Movie/TV database |
| `YouTubeTrendingApi.kt` | YouTube trending videos |
| `FuelPriceApi.kt` | Fuel price tracking |
| `IpoTrackerApi.kt` | IPO tracking |
| `FlightPriceApi.kt` | Flight price tracking |
| `HttpUtil.kt` | Shared HTTP utilities |

### Memory System (`memory/`)
| File | Purpose |
|------|---------|
| `VectorMemory.kt` | Embedding generation + semantic cosine search (threshold 0.35) |
| `HybridSearch.kt` | RRF fusion: keyword + semantic (60/40 weights) |
| `MemoryHygiene.kt` | Auto-cleanup of old/low-quality memories |
| `SemanticCache.kt` | LRU cache for semantically similar responses |
| `QueryExpansion.kt` | Synonym expansion + stop-word filtering |
| `AdaptiveRetrieval.kt` | Adaptive top-K based on relevance |
| `MmrReranker.kt` | Maximal Marginal Relevance diversity reranking |
| `TemporalDecay.kt` | Exponential decay, 30-day half-life, pinned tags |
| `SessionManager.kt` | Named sessions, switching, export as JSON |

### Tools (`tools/`) — 40+ AI-Enabled Capabilities
| File | Tool | API/Source |
|------|------|-----------|
| `ToolSystem.kt` | Registry & dispatcher | — |
| `WebSearchTool.kt` | Web search | DuckDuckGo HTML + Brave fallback |
| `WebFetchTool.kt` | Fetch & parse URLs | OkHttp |
| `MemoryTool.kt` | Semantic memory | Room DB + embeddings |
| `BookmarkTool.kt` | Save/search bookmarks | Room DB |
| `PdfReadTool.kt` | PDF text extraction | Android PdfRenderer |
| `ImageAnalysisTool.kt` | Vision analysis | Claude/GPT-4V |
| `ImageGenTool.kt` | Image generation | Pollinations.ai + DALL-E 3 |
| `SummarizeTool.kt` | Text summarization | Extractive (no LLM) |
| `TranslateTool.kt` | Translation (50+ langs) | MyMemory API |
| `CalculatorTool.kt` | Math evaluation | Local eval |
| `QrCodeTool.kt` | QR code gen/scan | Built-in encoder |
| `ClipboardTool.kt` | Read/write clipboard | Android ClipboardManager |
| `StatusTool.kt` | Service diagnostics | Internal |
| `CalendarTool.kt` | Calendar read/write | Android CalendarProvider |
| `ContactsTool.kt` | Contact lookup | Android ContactsProvider |
| `LocationTool.kt` | GPS, geocoding | Android LocationManager |
| `TextToSpeechTool.kt` | TTS | Android TTS (offline) |
| `SpeechToTextTool.kt` | STT | Whisper API |
| `FileManagerTool.kt` | File operations | Android File API (with approval) |
| `WeatherTool.kt` | Weather data | wttr.in |
| `GitHubTool.kt` | GitHub repos/issues | GitHub REST API |
| `RssFeedTool.kt` | RSS feed parsing | Direct HTTP + XML |
| `SpotifyTool.kt` | Playback control | Spotify Web API |
| `SmartHomeTool.kt` | IoT control | Hue REST API (with approval) |
| `EmailTool.kt` | Send emails | SendGrid/Mailgun |
| `NotionTool.kt` | Notion integration | Notion API |
| `ComposioTool.kt` | 1000+ OAuth integrations | Composio API v3/v2 |
| `BraveTool.kt` | Brave Search | Brave Search API |
| `WebViewTool.kt` | Browser automation | Android WebView |
| `CronTool.kt` | Scheduled tasks | Internal scheduler |
| `MediaPipelineTool.kt` | Media processing | FFmpeg-style |
| `MessageTool.kt` | Cross-channel messaging | All channel managers |
| `DelegateTool.kt` | Agent delegation | MultiAgentManager |
| `SpawnTool.kt` | Async task spawn | MultiAgentManager |
| `PushoverTool.kt` | Push notifications | Pushover API |
| `A2ATool.kt` | Agent-to-agent comms | A2A JSON-RPC |

### Data Layer (`data/`)
| File | Purpose |
|------|---------|
| `LlmRouter.kt` | Central LLM request router (OpenAI/Anthropic/Gemini/Ollama) |
| `LlmKeyManager.kt` | Persist & manage API keys (Gson + DataStore) |
| `ProviderRouter.kt` | Hint-based routing + per-model fallback chains |
| `AppSettings.kt` | Global config (DataStore Preferences) |
| `ThemeManager.kt` | Theme preference (dark/light/system) |
| `OfflineModelManager.kt` | Local MediaPipe .bin model management |
| `UsageTracking.kt` | Token usage & cost tracking |
| `ConversationLabels.kt` | Conversation tagging/pinning |
| `ExportImportConfig.kt` | Backup/restore settings as JSON |
| `MemoryDatabase.kt` | Room DB: memories table (with embeddings) |
| `MessageDatabase.kt` | Room DB: messages table (chat history) |
| `AgentResultDatabase.kt` | Room DB: agent_results table (every agent run result) |

### Infrastructure (`infra/`)
| File | Purpose |
|------|---------|
| `HooksSystem.kt` | Before/after middleware for tool/LLM calls |
| `PluginSystem.kt` | Load .zip skill packages |
| `ApprovalSystem.kt` | User approval for dangerous actions (5min timeout) |
| `BiometricLock.kt` | Fingerprint/face/iris auth |
| `AuditLog.kt` | Tamper-evident JSON Lines log |
| `AutoRecovery.kt` | WorkManager watchdog + crash recovery |
| `DevicePairing.kt` | QR-based device trust/pairing |
| `GroupChatSupport.kt` | Multi-user group chat handling |
| `RichNotifications.kt` | Per-channel actionable notifications |
| `QuickReplyReceiver.kt` | Quick-reply from notification shade |
| `VoiceInput.kt` | Voice-to-text input |
| `RateLimiting.kt` | Per-user/chat rate limits |
| `A2AServer.kt` | Agent-to-Agent JSON-RPC 2.0 server |
| `McpClient.kt` | MCP (Model Context Protocol) client |

### Additional Integrations
| File | Purpose |
|------|---------|
| `discord/DiscordBotManager.kt` | (listed above under Chat Platforms) |
| `NostrTool.kt` | Nostr decentralized protocol |
| `MattermostManager.kt` | Mattermost self-hosted team chat |
| `DingTalkLarkManager.kt` | DingTalk + Lark/Feishu webhooks |
| `SkillForge.kt` | Runtime skill creation from LLM output |
| `OpenTelemetry.kt` | OTLP span export for tracing + metrics |
| `AgentIdentity.kt` | AIEOS v1.1 identity (MBTI/OCEAN/catchphrases) |

---

## Database Schema

### 1. MemoryDatabase (`zeroclaw_memory`) — Version 2
```kotlin
@Entity(tableName = "memories")
data class MemoryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val userId: String,          // chat/user ID
    val key: String,             // memory key/label
    val value: String,           // memory content
    val embedding: String = "",  // JSON float array (1536d OpenAI / 768d Gemini)
    val sessionId: String = "",  // named session
    val tags: String = "",       // comma-separated tags
    val createdAt: Long,
    val updatedAt: Long
)
```

**DAO operations:** getAllForUser, getByKey, search, upsert, deleteByKey, deleteAllForUser, countForUser, getAllForSession, getAllWithEmbeddings, getSessionIds

### 2. MessageDatabase (`zeroclaw_messages`) — Version 1
```kotlin
@Entity(tableName = "messages")
data class MessageEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val channel: String,        // "telegram", "whatsapp", "discord", etc.
    val direction: String,      // "in" or "out"
    val sender: String,
    val content: String,
    val timestamp: Long
)
```

**DAO operations:** getRecentMessages (last 100, Flow), insert, deleteOlderThan, count

### 3. BookmarkDatabase (via BookmarkTool)
```kotlin
@Entity(tableName = "bookmarks")
data class BookmarkEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val userId: String,
    val url: String,
    val title: String,
    val tags: String = "",
    val createdAt: Long,
    val updatedAt: Long
)
```

**DAO operations:** insert, update, findByUrl, search, list

### 4. AgentResultDatabase (`zeroclaw_agent_results`) — Version 1
```kotlin
@Entity(tableName = "agent_results")
data class AgentResultEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val agentId: String,              // FK to AgentConfig.id
    val agentName: String,            // Snapshot of agent name at run time
    val runId: String,                // UUID per run
    val timestamp: Long,
    val status: String,               // "success" | "partial" | "failed" | "skipped"
    val url: String,                  // URL that was fetched
    val usedApi: Boolean,             // true if free API was used
    val rawContent: String,           // Raw fetched content (max 5000 chars)
    val extractedContent: String,     // Delivered content (max 3500 chars)
    val deliveredTo: String,          // JSON array ["telegram","discord"]
    val errorMessage: String,         // Error details if failed
    val contentHash: Int              // Hash for change detection
)
```

**Indices:** `agentId`, `timestamp`, composite `(agentId, timestamp)`

**DAO operations:** getAll (limit/offset), getByAgentId (limit/offset), getById, insert, deleteById, deleteByAgentId, deleteOlderThan, count, countByAgent, observeRecent (Flow), getDistinctAgents

All databases use singleton pattern, CoroutineScope.IO, and fallback to destructive migration.

---

## Resources

### Layouts
| File | Purpose |
|------|---------|
| `res/layout/home_widget.xml` | Widget UI (LinearLayout: status, buttons, connections) |

### Drawables
| File | Purpose |
|------|---------|
| `res/drawable/ic_zeroclaw.xml` | Red crab vector icon (108x108dp) |
| `res/drawable/widget_background.xml` | Widget background |

### Values
| File | Contents |
|------|----------|
| `res/values/strings.xml` | App strings (app_name="ZeroClaw", labels, status text) |
| `res/values/colors.xml` | Brand colors (red #E53935, surface #1A1A2E, green #4CAF50) |
| `res/values/themes.xml` | Theme.ZeroClawAndroid (Material Light, no action bar) |

### XML Config
| File | Purpose |
|------|---------|
| `res/xml/network_security_config.xml` | HTTP allowed for localhost/LAN, HTTPS for external |
| `res/xml/home_widget_info.xml` | Widget metadata & provider info |

---

## API Endpoints (WebChatServer :8088)

| Method | Path | Format | Purpose |
|--------|------|--------|---------|
| `POST` | `/v1/chat/completions` | OpenAI-compatible | Full agent pipeline (tools, memory, failover) |
| `GET` | `/v1/models` | OpenAI-compatible | Returns model list (`zeroclaw`) |
| `POST` | `/api/chat` | ZeroClaw native | Simple chat with session memory |
| `POST` | `/api/generate` | ZeroClaw native | Raw LLM output (no tools/history) |
| `GET` | `/api/discover` | ZeroClaw native | Service discovery (version, port, endpoints) |
| `GET` | `/` | HTML | Browser-based chat UI |
| `GET` | `/api/agents/results` | ZeroClaw native | Agent run results (filter: `?agent_id=`, `?id=`, `?limit=`, `?offset=`) |
| `DELETE` | `/api/agents/results` | ZeroClaw native | Delete results (`?id=`, `?agent_id=`, `?older_than=`) |

---

## LLM Providers

| Provider | ID | Auth | Base URL | Model Listing |
|----------|----|----|----------|---------------|
| OpenAI | `openai` | Bearer | `https://api.openai.com/v1` | GET /v1/models |
| Anthropic | `anthropic` | x-api-key | `https://api.anthropic.com/v1` | Hardcoded curated list |
| Gemini | `gemini` | Bearer / ?key= | `https://generativelanguage.googleapis.com/v1beta` | GET /v1beta/models |
| OpenRouter | `openrouter` | Bearer | `https://openrouter.ai/api/v1` | GET /v1/models (full catalog) |
| Ollama | `ollama` | none | `http://127.0.0.1:11434` | GET /api/tags |

---

## Tool Interface

```kotlin
interface Tool {
    val name: String
    val description: String       // shown to LLM in system prompt
    val parameters: String        // JSON schema for args
    suspend fun execute(args: Map<String, String>): ToolResult
}

data class ToolResult(
    val success: Boolean,
    val content: String,
    val error: String? = null
)
```

**Tool pipeline:** User message → LlmRouter (system prompt with tools) → LLM decides tool_use → ToolSystem.dispatch() → tool executes → result fed back → LLM generates final reply. Max 3 tool rounds per message.

---

*Update this file whenever any component is added, removed, or renamed.*

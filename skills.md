# ZeroClawAndroid — Skills & Technology Reference

## 🦀 What We're Building
A native Android app that:
1. Runs ZeroClaw AI agent as a persistent background service (24/7 daemon)
2. Connects to **11 messaging channels** (Telegram, Discord, Slack, WhatsApp, Matrix, Teams, Twitch, LINE, IRC, Signal, Email)
3. Provides **36 built-in AI tools** (web search, fetch, RSS, image gen, translate, etc.)
4. Runs **autonomous agents** — 25+ templates + 21 free API data sources + web scraping with AI extraction
5. Serves an **API for external apps** — `/api/generate`, `/api/chat`, OpenAI-compatible `/v1/chat/completions`
6. Has a full dashboard UI with agents screen, tool playground, live logs, multi-key management
7. Supports offline models via MediaPipe LLM Inference

**Active branch:** `feat/curl-api-generator` (Phases 1-169)

---

## 🛠️ Core Skills & Technologies

### 1. Kotlin + Jetpack Compose (UI)
- **What:** Android's modern declarative UI toolkit
- **Why:** Native performance, less boilerplate, Material 3 design
- **Key APIs:** `@Composable`, `remember`, `LaunchedEffect`, `ViewModel`, `StateFlow`
- **Docs:** https://developer.android.com/jetpack/compose

### 2. Android Foreground Service
- **What:** Long-running background service with persistent notification
- **Why:** Android kills background apps; foreground services survive
- **Key APIs:** `Service`, `startForeground()`, `NotificationChannel`
- **Pattern:** Start on boot via `BroadcastReceiver` (BOOT_COMPLETED)
- **Docs:** https://developer.android.com/guide/components/foreground-services

### 3. Retrofit + OkHttp (ZeroClaw API Client)
- **What:** Type-safe HTTP client for Android
- **Why:** ZeroClaw runs a local HTTP API (port 3000); we call it from the service
- **Key APIs:** `@GET`, `@POST`, `Retrofit.Builder`, `OkHttpClient`
- **Docs:** https://square.github.io/retrofit/

### 4. Telegram Bot API
- **What:** Telegram's official bot HTTP API
- **Why:** ZeroClaw receives messages from Telegram users
- **Pattern:** Long polling (`getUpdates`) inside the foreground service loop
- **Key endpoint:** `https://api.telegram.org/bot{TOKEN}/getUpdates`
- **Docs:** https://core.telegram.org/bots/api

### 5. Twilio WhatsApp API
- **What:** Twilio's messaging API for WhatsApp
- **Why:** Official, legal way to send/receive WhatsApp messages programmatically
- **Pattern:** Twilio webhook calls our tunnel URL → service processes → replies via Twilio REST API
- **Key:** Twilio Account SID, Auth Token, WhatsApp sandbox number
- **Docs:** https://www.twilio.com/docs/whatsapp

### 6. Cloudflare Tunnel / ngrok
- **What:** Creates a public HTTPS URL that tunnels to localhost
- **Why:** Telegram and Twilio webhooks need a public URL to reach our app
- **Options:**
  - **ngrok** (easiest): Binary, free tier, HTTP tunnel
  - **Cloudflare Tunnel** (more stable): Free, no rate limits, requires domain
- **Android Strategy:** Run as a subprocess via `ProcessBuilder` or use Termux companion

### 7. Room Database
- **What:** Android's SQLite ORM abstraction
- **Why:** Store message history, agent logs locally on device
- **Key APIs:** `@Entity`, `@Dao`, `@Database`, `Flow<List<T>>`
- **Docs:** https://developer.android.com/training/data-storage/room

### 8. DataStore Preferences
- **What:** Modern replacement for SharedPreferences
- **Why:** Store API keys, bot tokens, settings securely
- **Key APIs:** `PreferencesDataStore`, `stringPreferencesKey`, `edit {}`
- **Docs:** https://developer.android.com/topic/libraries/architecture/datastore

### 9. WorkManager
- **What:** Android's battery-friendly background task scheduler
- **Why:** Used as keep-alive watchdog — restarts service if it dies
- **Key APIs:** `PeriodicWorkRequest`, `Worker`, `WorkManager.enqueueUniquePeriodicWork`
- **Docs:** https://developer.android.com/topic/libraries/architecture/workmanager

### 10. Material Design 3
- **What:** Google's design system for Android
- **Why:** Modern, polished UI with dynamic color support
- **Key APIs:** `MaterialTheme`, `Scaffold`, `TopAppBar`, `Card`, `Switch`
- **Docs:** https://m3.material.io/develop/android

---

## 📐 Architecture Pattern
```
MVVM + Clean Architecture
├── UI Layer (Compose Screens + ViewModels)
├── Domain Layer (Use Cases / Managers)
│   ├── ZeroClawService (Foreground Service)
│   ├── TelegramBotManager
│   ├── TwilioWhatsAppManager
│   └── TunnelManager
└── Data Layer
    ├── ZeroClawApiClient (Retrofit)
    ├── AppSettings (DataStore)
    └── MessageDatabase (Room)
```

---

## 🔐 Security Notes
- API keys stored in DataStore (encrypted via EncryptedSharedPreferences optional)
- Network calls use OkHttp with certificate pinning option
- `network_security_config.xml` allows localhost HTTP (for ZeroClaw local API)
- Never hardcode tokens in source — always load from DataStore

---

## 📦 Minimum Requirements
- **Android:** 8.0+ (API 26+)
- **RAM:** 2GB+ recommended (ZeroClaw itself uses <5MB, but JVM overhead applies)
- **Storage:** ~50MB for app + ZeroClaw binary (if bundled)
- **Internet:** Required for Telegram/WhatsApp APIs and LLM provider

---

## 🔗 Resumption Tip
When resuming in a new chat, read files in this order:
1. `plan.md` — current state, what's done, what's pending
2. `blueprint.md` — full architecture, components, DB schema, navigation
3. `skills.md` (this file) — technology decisions
4. `permissions.md` — Android permissions & Play Store compliance
5. `bugs.md` — all past bugs and fixes

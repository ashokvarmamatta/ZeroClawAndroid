# ZeroClawAndroid — Build Plan

> **Before writing ANY code, read the full plan first.**

## Full Plan (Private Repo)

The complete master build plan (180+ phases, architecture, bugs, all details) is in a **private repo**:

**https://github.com/ashokvarmamatta/zeroclaw-docs/blob/main/ZeroClawAndroid/plan.md**

> This is a **private repository** — only accessible when logged in as the owner.
> If you're an AI assistant, read `PLAN_FULL.md` in this directory (gitignored local copy).

### Local copy

If `PLAN_FULL.md` exists in this directory, read that (it's the same content, gitignored).
To fetch/refresh from the private repo:
```bash
cd /tmp && git clone https://github.com/ashokvarmamatta/zeroclaw-docs.git
cp zeroclaw-docs/ZeroClawAndroid/plan.md PLAN_FULL.md
```

## Quick Reference

- **Project:** Android app that runs ZeroClaw AI agent with 10 messaging channels
- **Phases completed:** 190 (184: BUG-43 search_only agent, 185: /api/tts Gemini proxy, 186: security pass — redactKeys + Grok/Groq + Test Connection + /api/tts limits + full error parsing, 187: waterfall hardening — ConcurrentHashMap rate-limit + skip dead keys + single cooldown const, 188: agent-completion native notifications, 189: native WhatsApp via whatsmeow Go bridge — QR + pair code, 190: per-agent Results screen + notification deep-link)
- **Phase 190 — Implemented:** Per-agent Results screen. Each agent card now has a `View Results` action that opens a dedicated screen listing every persisted run for that agent (already stored in `AgentResultEntity`). Premium design: gradient header card with summary stats, status-filter chips (All / ✅ / ⚠️ / ❌ / 💤), each result shown as an expandable card with status badge, relative timestamp ("2h ago" / "Yesterday 14:05"), delivery-channel chips, content preview that taps to expand to full detail (raw URL, full extracted content, error message, raw fetched content fallback). Sorted newest-first; pull-to-clear-old via "Delete older than 7 days" action in top bar. The agent-completion notification from Phase 188 now wraps a PendingIntent that opens MainActivity with `navigate_to=agent_results` + `agent_id=<id>`, so tapping the notification jumps straight to that agent's Results screen instead of the generic home. New route `agent_results/{agentId}` in MainActivity NavHost.
- **Phase 189 — Implemented but pairing broken on device — see BUG-44 in BUGS.md:** Native WhatsApp connection via bundled `libwhatsmeow.so` Go binary (whatsmeow library). Same pattern as `libcloudflared.so`. End-to-end build + bundle works (binary in APK, UI renders QR + pair code, Kotlin↔Go bridge talks). What does **not** yet work: WhatsApp accepts neither the QR scan nor the 8-digit pair code — open BUG-44 for details/hypotheses. Feature is shipped behind a switch (`whatsappMode = native|twilio|off`, default `twilio`) so the Twilio path remains the recommended option. New `whatsmeow-bridge/` directory holds the Go source (`main.go`), `go.mod`, and `BUILD.md` with cross-compile instructions for `arm64-v8a`. Bridge protocol: line-based stdout (`STATUS <state>`, `QR <text>`, `PAIRCODE <code>`, `MSG <jid> <sender> <text>`, `LOG <text>`) ↔ stdin commands (`PAIR <phone>`, `SEND <jid> <text>`, `STOP`). Session is persisted in `<filesDir>/whatsmeow/session.db` (SQLite via whatsmeow's sqlstore). New `WhatsAppNativeManager.kt` spawns the binary on service start when `whatsappMode == "native"`, exposes `state: StateFlow<NativeState>` for UI, and routes incoming WhatsApp messages through `LlmRouter.call` → reply via `SEND`. New `WhatsAppNativeScreen.kt` shows live QR (rendered from existing `QrEncoder`) or 8-digit pair code, plus phone-number entry and Start/Stop. `AppSettings` adds `whatsappMode` (`twilio` | `native` | `off`, default `twilio` for backward compat) + `whatsappNativeJid` (last paired JID, display only). `ZeroClawService` branches startup on `whatsappMode`. **Binary requires user-side compile** — instructions in `whatsmeow-bridge/BUILD.md`. UI surfaces a clear "binary not bundled" state when `libwhatsmeow.so` is missing.
- **Phase 188 — Implemented:** Agent-completion native Android notification. Every successful (or partial) `WebScraperAgent` run (URL-fetch path AND `runSearchOnly` path) now triggers a high-priority notification on the new `notif_agents` channel. Title: `🕷️ <agent name> finished`. Body: delivered-to summary (e.g. `Delivered 1.2KB → ✈️telegram, 🎮discord`). Tapping opens MainActivity. New `RichNotifications.showAgentCompletionNotification(agent, status, deliveredTo, contentLen)` plus `CHANNEL_AGENTS = "notif_agents"` channel. No new manifest permission needed — `POST_NOTIFICATIONS` from Phase 127 already covers this.
- **Phase 187 — Implemented:** Waterfall audit + hardening. `rateLimitedModels` is now a `ConcurrentHashMap` (was plain `mutableMapOf` → could throw `ConcurrentModificationException` when background agents and foreground chat hit it together). `RATE_LIMIT_COOLDOWN_MS = 60_000L` is now a single companion constant, replacing the three inline `60_000L` literals in `rawGenerate`/`call`/`generateTtsAudio`. `rawGenerate` and `generateTtsAudio` now skip keys in `getFailedKeyIds()` so a permanently-dead key isn't retried on every request — matching `call()` behaviour.
- **Phase 186 — Implemented:** Security hardening pass on the LLM/TTS surface. `redactKeys()` helper scrubs `AIzaSy*` / `sk-*` / `xai-*` / `gsk_*` / `Bearer …` / `?key=…` patterns from any string before it lands in logcat or the user-facing Test Connection copy box (Gemini sometimes echoes the request URL back in error JSON). Applied at every error chokepoint. New first-class providers `Grok` (xAI) and `Groq` (gsk_, fast Llama/Mixtral) — they're confusingly-named different products on different hosts, now wired separately. `defaultOpenAICompatibleBaseUrl(provider)` collapses 4 inline if/else ladders. `defaultTestModelFor(provider)` picks a sensible cheap test model per provider. `/api/tts` adds a 4000-char text cap (HTTP 413 on overflow) and an allow-list for the `voice` param so a public-tunnel caller can't smuggle arbitrary strings into Gemini's `speechConfig.voiceName`. New card-level Test Connection pill on every API key (except offline) — fires a real prompt against the preferred/default model and shows the full untruncated response or error inline (tap to copy). `sendTestPrompt` OpenAI-compat branch now logs the full raw response body to logcat (tag `LlmRouter`) and parses every common provider error shape (`error.message`, `error` as string, `error.code`, `detail`, `message`) so users see why a key was rejected instead of a flat `[400] HTTP 400`.
- **Phase 185 — Implemented:** `POST /api/tts` Gemini TTS proxy in `WebChatServer` (called by autom's `tryZeroClawTts`). Picks first enabled `gemini` key, calls `gemini-2.5-flash-preview-tts:generateContent` with `responseModalities=["AUDIO"]` + `speechConfig.voiceConfig.prebuiltVoiceConfig.voiceName=<voice>`, returns `{audio_base64, sample_rate, channels, format:"pcm_s16le"}`. Endpoint added to `/api/discover` list. New `LlmRouter.generateTtsAudio(text, voice)` helper.
- **Phase 184 — Implemented (fix/bug-43-discovery-agent-url, awaiting device test):** BUG-43 — added `type = "search_only"` agent flavour that skips URL fetch and runs `web_search + web_fetch` via `LlmRouter.rawGenerateWithTools`. Code changes:
  - `AgentConfig.kt` — doc strings updated; `type` values now `"web_scraper"` | `"search_only"`
  - `AgentManager.kt` — `TYPE_SEARCH_ONLY` constant + new `type` param on `createWebScraper` (default `TYPE_WEB_SCRAPER` for wire compat)
  - `WebChatServer.handleAgentCreate` — accepts optional `type` field; validation relaxed so url/apiSource are only required for non-search agents; extract_prompt required for search_only
  - `WebScraperAgent.run` + new `runSearchOnly()` — skip fetch, call `rawGenerateWithTools(extractPrompt)`, reuse change-detection and delivery path
  - `WebScraperAgent.buildHeader` — delivered messages show `🔗 using web_search + web_fetch` for search_only agents
  - `AgentsScreen.kt` — target-URL row shows `using web_search + web_fetch` instead of empty URL for search_only agents
  - `AgentCreateSheet.kt` — new three-way target selector **URL / API Source / web_search + web_fetch** above the URL field; URL field hidden in search mode; validation branches on mode; type resolved at save time.
- **Tools:** 36 built-in AI tools (+ Document Knowledge Graph)
- **Channels:** Telegram, WhatsApp, Discord, Signal, Slack, Matrix, IRC, Teams, Twitch, LINE
- **Offline:** LiteRT LM SDK (Gemma 4 support, 32K context, streaming, thinking mode)
- **Build:** `JAVA_HOME="C:/Users/DELL/AppData/Local/Programs/Android Studio/jbr" ./gradlew assembleDebug`

## Session Rules

1. Read `PLAN_FULL.md` (local) or clone from private repo above
2. Read `RULES.md` in the private repo for full dev workflow rules
3. Read `BUGS.md` — avoid re-introducing fixed bugs
4. Mark phases done after implementation
5. Sync plan back to private repo after changes:
   ```bash
   cp PLAN_FULL.md /tmp/zeroclaw-docs/ZeroClawAndroid/plan.md
   cd /tmp/zeroclaw-docs && git add -A && git commit -m "sync plan" && git push
   ```

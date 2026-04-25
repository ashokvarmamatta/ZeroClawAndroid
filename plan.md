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
- **Phases completed:** 183 (178: LiteRT LM migration, 179: Model catalog + download, 180: Token stats UI, 181: Offline vision + config dialog, 182: Document Knowledge Graph, 183: Autom→ZeroClaw pipeline fixes)
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

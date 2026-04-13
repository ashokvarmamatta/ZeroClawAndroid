
# ZeroClawAndroid — Bug Tracker

> Every significant bug found during development is recorded here with full details.
> Summary rows are also in the **Bug Log** table in `plan.md`.
> Newest bugs at the top. Format: symptom → root cause → fix → lesson.

---

## BUG-36 — Offline Gemma 4 vision images routed to online APIs instead of on-device model
- **Phase:** 181 (Offline Vision + Config Dialog)
- **Status:** ✅ Fixed
- **Severity:** High
- **Symptom:** When user attached an image in chat with an offline Gemma 4 model selected (which supports vision), the image was always routed through `ImageAnalysisTool` → online APIs (Gemini/OpenAI/Anthropic) instead of being processed on-device. This meant images didn't work in airplane mode and required an API key even though the local model supports vision.
- **Root Cause:** `ChatViewModel.sendMessage()` unconditionally passed images to `ImageAnalysisTool.execute()` regardless of whether the active model was offline or online. There was no check for `isLiteRtFormat` or `supportsImage` on the current model.
- **Fix:** Added offline image routing: when the active model is a LiteRT format (`.litertlm`/`.bin`/`.task`) and supports vision, images are sent directly to the on-device Gemma 4 engine via `Content.ImageBytes`. Images are downscaled to 512px PNG before sending. Online models continue using `ImageAnalysisTool`. Also added `visionBackend = Backend.GPU()` and `audioBackend = Backend.CPU()` to `EngineConfig` for multimodal models to prevent SIGSEGV crashes.
- **Lesson:** When adding multimodal capabilities, always check whether the active model is local or cloud before routing — don't assume all images must go through the online tool pipeline.

---

## BUG-35 — LiteRT LM SDK requires Kotlin 2.2.0+ and Room 2.7+ (build failure)
- **Phase:** 178 (LiteRT LM Migration)
- **Status:** ✅ Fixed
- **Severity:** Build-blocking
- **Symptom:** After replacing `com.google.mediapipe:tasks-genai` with `com.google.ai.edge.litertlm:litertlm-android:0.10.0`, build fails with two sequential errors:
  1. `Provided Metadata instance has version 2.2.0, while maximum supported version is 2.0.0` — Room 2.6.1's kapt annotation processor can't read Kotlin 2.2.0 metadata.
  2. `Argument type mismatch: actual type is 'Map<String, String>?', but 'Map<String, Any>' was expected` — LiteRT LM's `sendMessageAsync` expects `Map<String, Any>`, not nullable `Map<String, String>?`.
- **Root Cause:** LiteRT LM SDK 0.10.0 is compiled with Kotlin 2.3.0 metadata, requiring Kotlin 2.2.0+ in the host project. This cascades: Kotlin 2.2.0 requires Room 2.7+ (or switch from kapt to KSP), and the old `composeOptions { kotlinCompilerExtensionVersion }` must be replaced with the `kotlin-compose` Gradle plugin.
- **Fix:**
  1. Upgraded Kotlin 1.9.23 → 2.2.0
  2. Upgraded Room 2.6.1 → 2.7.1
  3. Switched from `kapt` to `ksp` for Room compiler
  4. Added `kotlin-compose` plugin, removed `composeOptions` block
  5. Changed `extraContext` type from `Map<String, String>?` to `Map<String, Any>`
- **Lesson:** When adopting a new Google SDK, always check its Kotlin metadata version first. LiteRT LM requires a modern Kotlin toolchain. Plan for cascading dependency upgrades (Kotlin → Room → kapt→KSP → Compose plugin).

---

## BUG-33 — Chat image analysis fails with "Missing source parameter"
- **Phase:** 177 (Chat Screen)
- **Status:** ✅ Fixed
- **Severity:** Medium
- **Symptom:** User attaches an image in the Chat screen, sends "analyze this image". Gets error: "Image analysis failed: Missing 'source' parameter".
- **Root Cause:** `ChatScreen.sendMessage()` passed the image URI as `"image"` key, but `ImageAnalysisTool.execute()` expects `"source"` as the parameter name.
- **Fix:** Changed `mapOf("image" to imageUri.toString())` to `mapOf("source" to imageUri.toString())` in ChatScreen.kt.
- **Lesson:** Always check the tool's `parameters` list for exact param names before calling `execute()`. Tool param names are defined in the Tool class, not guessable.

---

## BUG-34 — Image analysis says "no vision-capable API key" even with Gemini connected
- **Phase:** 177 (Chat Screen)
- **Status:** ✅ Fixed
- **Severity:** High
- **Symptom:** User has Gemini key configured and working, attaches image in Chat screen, gets error: "No vision-capable API key available. Add an OpenAI (GPT-4o), Gemini, or Claude key."
- **Root Cause:** `ImageAnalysisTool.callVisionModel()` reads API keys from the wrong SharedPreferences. It used `getSharedPreferences("zeroclaw_api_keys")` with key `"api_keys"`, but `LlmKeyManager` stores keys in `getSharedPreferences("llm_keys")` with key `"api_key_list"`. The tool was reading from a non-existent file, always getting `null`, and concluding no vision-capable keys exist.
- **Fix:** Changed SharedPreferences name from `"zeroclaw_api_keys"` to `"llm_keys"` and key from `"api_keys"` to `"api_key_list"` in ImageAnalysisTool.kt line 134-135.
- **Lesson:** When accessing shared data (like API keys), always import from or reference the actual data manager class constants — don't hardcode storage names separately. A single source of truth for SharedPreferences names prevents this class of bug entirely.

---

## BUG-32 — Widget buttons all open Home screen instead of navigating
- **Phase:** 177 (Super Widget)
- **Status:** ✅ Fixed
- **Severity:** High
- **Symptom:** Tapping "Agents" or "Chat" buttons on the home screen widget opens the app but always lands on the Home screen. Connected bots (Telegram, Discord, etc.) never show in the widget even when connected.
- **Root Cause:** Two issues: (1) `setOnClickPendingIntent(R.id.widget_root, ...)` on the root layout swallowed all child button taps — Android RemoteViews propagates clicks to the nearest parent with a PendingIntent. (2) `HomeWidget.broadcastUpdate()` was never called after bots connected in `ZeroClawService.onCreate()`, so the widget only updated on its 30-min system cycle.
- **Fix:** (1) Removed root click handler. Each button and info area gets its own `PendingIntent.getActivity()` with unique request codes. Agents/Chat buttons deep-link via `navigate_to` intent extra. (2) Added `broadcastUpdate()` call 5s after service start + every 30s periodic refresh + on tunnel connect.
- **Lesson:** In RemoteViews, never set a click listener on the root layout if children need their own click handlers. Use unique request codes for each PendingIntent or Android will reuse/overwrite them.

---

## BUG-31 — Agent Results API has no authentication or access control
- **Phase:** 175 (Agent Results API)
- **Status:** 🔴 Open
- **Severity:** High
- **Symptom:** Anyone on the same network (or via tunnel) can read, query, and delete all agent results without any authentication. The API endpoints `GET /api/agents/results` and `DELETE /api/agents/results` are fully open. `Access-Control-Allow-Origin: *` allows any website to call the API. Raw fetched content, extracted data, delivery targets, and agent metadata are all exposed.
- **Root Cause:** No authentication layer was implemented for the WebChatServer HTTP API. All endpoints are publicly accessible.
- **Fix (planned):** Add token-based auth (API key in header or query param). Generate a random token on first launch, show it in Settings. Require `Authorization: Bearer <token>` header on all `/api/*` endpoints. Optionally add IP allowlist for local-only access.
- **Lesson:** Any HTTP API — even a local one — needs auth if it exposes user data. Tunnels make "local" APIs globally reachable.

---

## BUG-30 — AgentTool smart create splits words like "tollywood" and gets page titles instead of data
- **Phase:** 175 (Agent Results API + Agent Manager)
- **Status:** 🟡 Partially Fixed
- **Severity:** High
- **Symptom:** User says "create agent for latest tollywood movies". (1) "tollywood" gets split into "llywood" because regex strips "to" from inside the word. (2) Agent fetches page titles like "Top 10 Movies Lists" instead of actual movie names like "Pushpa 2". (3) When user says "find own" (meaning find URLs yourself), AI asks for URL instead of using AgentTool's smart creation.
- **Root Cause:** (1) `extractQueryForTemplate()` regex used `to` without word boundaries — matched inside "tollywood". (2) Extraction prompt was too generic ("Extract latest movie releases") — LLM grabbed headings not data. (3) Tool description said `url` was needed, so LLM asked for it instead of calling the tool with just a topic.
- **Fix (applied):** (1) Changed regex to use `\b` word boundaries. (2) Added movie-specific prompt demanding actual movie names. (3) Updated tool description to say URL is OPTIONAL, just pass a topic. (4) Added job/anime/movie-specific smart URLs (LinkedIn, Naukri, BookMyShow, IMDB, MyAnimeList).
- **Remaining:** Smart agent creation still unreliable — LLM sometimes doesn't call the tool, or the web-discovered URLs don't return useful data. Needs end-to-end testing with various topics. Preview+modify loop added but untested.
- **Lesson:** Word-boundary regex (`\b`) is essential when stripping keywords from user input. Tool descriptions must explicitly say what's optional or the LLM will ask for it.

---

## BUG-29 — Cloudflare edge discovery fails with "too many colons in address"
- **Phase:** 173 (Cloudflare Tunnel)
- **Status:** ✅ Fixed
- **Severity:** High
- **Symptom:** cloudflared exits with `failed to resolve to TCP address error="address 198.41.192.77:7844,...: too many colons in address"`. Edge IPs are resolved correctly but tunnel can't connect.
- **Root Cause:** `--edge` flag was passed a comma-separated list of `IP:port` values as a single argument. cloudflared treats each `--edge` value as ONE address, not a list.
- **Fix:** Pass separate `--edge IP:port` flags for each resolved edge IP (up to 4).
- **Files Changed:** TunnelManager.kt
- **Lesson:** CLI flags that accept addresses usually expect one address per flag instance, not a delimited list.

---

## BUG-28 — cloudflared edge connection fails with DNS lookup error on Android
- **Phase:** 173 (Cloudflare Tunnel)
- **Status:** ✅ Fixed
- **Severity:** Critical
- **Symptom:** After successful tunnel registration, cloudflared fails with `lookup _v2-origintunneld._tcp.argotunnel.com on [::1]:53: connection refused`. SRV record lookup for edge discovery fails.
- **Root Cause:** Same Go DNS issue as BUG-26 — cloudflared needs to resolve SRV records for edge server discovery, which requires DNS. Even though initial API call was handled by Java, edge discovery still used Go's broken DNS.
- **Fix:** Resolve `region1.v2.argotunnel.com` and `region2.v2.argotunnel.com` from Java using `InetAddress.getAllByName()`, pass resolved IPs via separate `--edge IP:7844` flags. cloudflared connects directly to edge IPs without DNS.
- **Files Changed:** TunnelManager.kt
- **Lesson:** Go binaries on Android need ALL DNS handled externally — not just the first API call, but every subsequent hostname lookup too.

---

## BUG-27 — HTTPS_PROXY environment variable ignored by cloudflared
- **Phase:** 173 (Cloudflare Tunnel)
- **Status:** ✅ Fixed (workaround)
- **Severity:** High
- **Symptom:** HTTP CONNECT proxy started on port 18053 and `HTTPS_PROXY` env var set, but cloudflared still resolved DNS directly via `[::1]:53`. Proxy received zero connections.
- **Root Cause:** cloudflared creates a custom `http.Transport{}` (not `http.DefaultTransport`) for its API calls. A zero-value `http.Transport` has `Proxy: nil` which means "no proxy". Only `http.DefaultTransport` has `Proxy: http.ProxyFromEnvironment`.
- **Fix:** Abandoned proxy approach. Instead, make the initial API call from Java/OkHttp (where Android DNS works), get tunnel credentials, write them to disk, and run cloudflared with `--credentials-file`.
- **Files Changed:** TunnelManager.kt
- **Lesson:** Don't assume Go binaries respect standard proxy env vars. Check if they use `http.DefaultTransport` or a custom transport.

---

## BUG-26 — Go DNS resolver fails on Android (no /etc/resolv.conf)
- **Phase:** 173 (Cloudflare Tunnel)
- **Status:** ✅ Fixed
- **Severity:** Critical
- **Symptom:** cloudflared fails with `lookup api.trycloudflare.com on [::1]:53: read udp [::1]:...->[::1]:53: read: connection refused`. DNS resolution completely broken.
- **Root Cause:** Go's pure-Go DNS resolver reads `/etc/resolv.conf` (hardcoded path) to find DNS servers. This file doesn't exist on Android (DNS is handled by Bionic's `getaddrinfo` via system properties). Go falls back to `127.0.0.1:53` and `[::1]:53` — neither has a DNS server. Port 53 binding requires root (EACCES). No env var exists to override the resolv.conf path.
- **Fix (multi-layered):**
  1. ~~DNS relay on port 53~~ → EACCES, needs root
  2. ~~HTTPS_PROXY with CONNECT proxy~~ → cloudflared ignores it (BUG-27)
  3. ~~RES_CONF env var~~ → Not a real Go thing, doesn't work
  4. **Final fix:** Make ALL DNS-requiring calls from Java/OkHttp where Android's resolver works. Register tunnel via API from Java. Resolve edge IPs from Java. Pass everything to cloudflared via config files and `--edge` flags.
- **Files Changed:** TunnelManager.kt
- **Lesson:** Go binaries compiled for Linux (not Android) cannot do DNS on Android. The ONLY fix is to handle DNS from Java and pass results to the binary.

---

## BUG-25 — cloudflared binary permission denied on Android (W^X policy)
- **Phase:** 173 (Cloudflare Tunnel)
- **Status:** ✅ Fixed
- **Severity:** Critical
- **Symptom:** Downloaded cloudflared binary to `filesDir`, set executable permission, but execution fails with `error=13, Permission denied`. Also tried dynamic linker (`/system/bin/linker64`) which fails with `unexpected e_type: 2`.
- **Root Cause:** Android 10+ enforces W^X (Write XOR Execute) policy — app data directories (`/data/user/0/.../files/`) are mounted with `noexec`. Can't execute any binary from there. The linker rejects it because it expects shared libraries (e_type=3 ET_DYN), not executables (e_type=2 ET_EXEC).
- **Fix:** Bundle cloudflared as `libcloudflared.so` in `jniLibs/arm64-v8a/`. Set `android:extractNativeLibs="true"` in AndroidManifest and `jniLibs.useLegacyPackaging = true` in build.gradle. Android extracts jniLibs to `nativeLibraryDir` on install, which HAS execute permission.
- **Files Changed:** TunnelManager.kt, build.gradle.kts, AndroidManifest.xml, jniLibs/arm64-v8a/libcloudflared.so
- **Lesson:** On Android, the ONLY way to execute a binary is from `nativeLibraryDir`. Bundle it as a `.so` file in jniLibs. Runtime-downloaded binaries CANNOT be executed on modern Android.

---

## BUG-24 — Agent extraction sends wrong values to Telegram despite correct format preview
- **Phase:** Agent extraction (WebScraperAgent)
- **Status:** ✅ Fixed
- **Severity:** High
- **Symptom:** Agent edit screen shows correct formatted preview (e.g. anime names + episode numbers), but when agent runs and delivers to Telegram, the anime names are replaced with random/wrong text from the page.
- **Root Cause:** `extractWithLlm()` only sent the user's extraction prompt (e.g. "all latest anime episodes") but never included the saved `formatPreview`. The LLM had no reference format to follow, so it guessed which values to extract and often grabbed wrong text.
- **Fix:** `extractWithLlm()` now includes `agent.safeFormatPreview` as a REFERENCE FORMAT in the prompt, with instructions to: follow the exact structure, match fields by meaning, find REAL current values from fetched content, skip items rather than guessing.
- **Files Changed:** WebScraperAgent.kt
- **Lesson:** Always pass the format template to the extraction LLM — the prompt alone is too ambiguous for structured output.

---

## BUG-23 — Agent proactive delivery only works for 3 of 10 channels
- **Phase:** Agent delivery (WebScraperAgent / ZeroClawService)
- **Status:** ✅ Fixed
- **Severity:** Critical
- **Symptom:** Agents configured to deliver to WhatsApp, Signal, Matrix, IRC, Teams, Twitch, or LINE silently fail with "PROACTIVE: unknown channel X" in logs. Only Telegram, Discord, and Slack work.
- **Root Cause:** `ZeroClawService.sendProactive()` only had `when` branches for telegram/discord/slack. The remaining 7 channel managers lacked public `sendProactiveMessage()` methods. The AgentCreateSheet UI also only listed 5 channels as bot options.
- **Fix:** Added routing for all 10 channels in `sendProactive()`. Added public `sendProactiveMessage()` to all channel managers. AgentCreateSheet now shows all 10 channels.
- **Files Changed:** ZeroClawService.kt, TwilioWhatsAppManager.kt, SignalBridgeManager.kt, MatrixBotManager.kt, IrcBotManager.kt, TeamsBotManager.kt, TwitchBotManager.kt, LineBotManager.kt, AgentCreateSheet.kt, WebScraperAgent.kt
- **Lesson:** When adding new channels, always wire up proactive/agent delivery — not just interactive reply handling.

---

## BUG-22 — Codex branch `codex/zeroclaw-api-metagen-fix` destroyed agents UI
- **Phase:** Branch management
- **Status:** ✅ Fixed (deleted)
- **Severity:** Critical
- **Symptom:** Agents screen, HomeScreen, AgentCreateSheet, WebScraperAgent all had massive code deletions. All 21 free API sources removed. UI appeared broken/empty.
- **Root Cause:** Codex auto-generated branch stripped 5,310 lines including the entire agent template system, API agents, and most UI components. Only had 1 commit: "enable build workflows".
- **Fix:** Deleted the branch from GitHub (2026-03-31). The active branch `feat/curl-api-generator` has the correct, complete code. Also cleaned up 14 stale merged branches.
- **Lesson:** Always verify Codex-generated branches before relying on them. They can destructively rewrite large parts of the codebase.

---

## BUG-21 — Agent proactive messages not delivered to Telegram chat
- **Phase:** Agent delivery (WebScraperAgent / TelegramBotManager)
- **Status:** ✅ Fixed
- **Severity:** High
- **Symptom:** Agent logs show "Delivered 1601 chars → telegram/g" and "Telegram: invalid chatId 'g' for proactive message" — agent extraction and pipeline work correctly, but the message never appears in Telegram chat.
- **Root Cause:** The agent's chatId was stored as `"g"` (non-numeric), which fails `toLongOrNull()` in `TelegramBotManager.sendProactiveMessage()`. The method silently returned without sending. No fallback mechanism existed to resolve a valid chatId from the connected bot.
- **Fix (4 layers):**
  1. **TelegramBotManager** — persists `lastKnownChatId` to SharedPreferences (`zeroclaw_telegram`) on every incoming message. Survives app restarts. `sendProactiveMessage()` auto-resolves blank/invalid chatId to this persisted chat — so agents "just work" when the bot is connected and the user has chatted with it at least once.
  2. **ZeroClawService.onCreate()** — calls `TelegramBotManager.restoreLastChatId()` on startup so the persisted chat ID is available immediately, even before the first poll completes.
  3. **WebScraperAgent** — new `resolveEffectiveChatId()` with 3-tier resolution: valid agent chatId → LlmRouter conversation history → TelegramBotManager persisted last chat.
  4. **AgentCreateSheet** — added numeric validation for Telegram chatId field (rejects non-numeric values like `"g"`).
- **Lesson:** Chat ID should be truly optional for connected channels. Persist the bot's last known chat so proactive delivery works without manual configuration.

---

## BUG-12 — Wrong date injected into system prompt
- **Phase:** AI pipeline (LlmRouter / SystemPromptManager)
- **Status:** ✅ Fixed
- **Severity:** Medium
- **Symptom:** LLM responded with the wrong year when asked "what's today's date?" — returned a date from a previous year or compile-time constant.
- **Root Cause:** System prompt used a hardcoded or build-time date string instead of calling `LocalDate.now()` at request time.
- **Fix:** Changed system prompt construction to call `java.time.LocalDate.now().toString()` at the moment each request is built, not at class initialization time.
- **Lesson:** Never hardcode or cache the current date. Always evaluate it at request time.

---

## BUG-11 — Refusal detection false-positive caused unnecessary retries
- **Phase:** LlmRouter (refusal detection logic)
- **Status:** ✅ Fixed
- **Severity:** Medium
- **Symptom:** Short valid AI replies (e.g. "Sure!" or "Done.") were flagged as refusals. The app retried with a different key/model, wasting quota and slowing response.
- **Root Cause:** Refusal detection checked for keywords like "I cannot" and "I'm sorry" but also triggered on very short responses (< 20 chars) regardless of content, assuming short = refusal.
- **Fix:** Separated length check from keyword check. Short responses only flag as refusal if they also contain explicit refusal keywords. Added a minimum-confidence threshold.
- **Lesson:** Refusal detection needs both keyword AND context checks. Short positive replies are not refusals.

---

## BUG-10 — `web_search` HTTP 400 / empty results from special characters in query
- **Phase:** 68 (WebSearchTool) / offline web search pipeline
- **Status:** ✅ Fixed
- **Severity:** High
- **Symptom:** Web search returned 0 results or HTTP 400 for queries containing quotes, question marks, or punctuation copied directly from user messages.
- **Root Cause:** The raw user message (or LLM-extracted query) was URL-encoded but still contained characters like `"`, `?`, `#` that broke the DuckDuckGo HTML endpoint query string.
- **Fix:** Added `cleanSearchQuery()` that strips quotes, trims to key keywords, and double-encodes the query before appending to the URL. Added a retry path that falls back to extracting only nouns/verbs if the first attempt returns empty results.
- **Lesson:** Always sanitize tool arguments before using them in HTTP calls. User text is never safe to embed directly in a URL.

---

## BUG-09 — ConversationSummarizer corrupted context by running during Pass 2
- **Phase:** 117 (ConversationSummarizer)
- **Status:** ✅ Fixed
- **Severity:** High
- **Symptom:** When a tool was called, the Pass 2 LLM call (which synthesizes tool results) sometimes received a summarized/truncated history that omitted the tool result itself. The final reply ignored the tool data entirely.
- **Root Cause:** The summarizer ran on every LLM call, including the Pass 2 internal call. It summarized the conversation history before the tool result was injected, so the tool result was never included in the summary context.
- **Fix:** Added a `isSynthesisPass` boolean flag to the LLM call path. When `true`, the summarizer is skipped entirely and the full history + tool results are sent as-is.
- **Lesson:** Summarization must never run on internal synthesis passes. Only summarize on user-facing first-pass calls.

---

## BUG-08 — Online-first failover incorrectly routed to offline model
- **Phase:** 83 (online-first failover in LlmRouter)
- **Status:** ✅ Fixed
- **Severity:** High
- **Symptom:** Even when valid online API keys were configured and responding, the router would sometimes call the on-device offline model instead, causing much slower/lower-quality responses.
- **Root Cause:** The failover list was built by concatenating online keys + offline model. When checking key validity, a transient network error on the first key was treated as "all online keys failed" and the router jumped to offline. The check did not retry other online keys first.
- **Fix:** Restructured the failover loop to try ALL enabled online keys before falling back to offline. Each key failure increments a counter; offline is only used when the counter equals the total online key count.
- **Lesson:** Failover must exhaust the entire tier before dropping to the next tier. A single key failure ≠ tier failure.

---

## BUG-07 — Offline model replied with raw tool JSON instead of natural answer
- **Phase:** 70 (tool system integration with LlmRouter)
- **Status:** ✅ Fixed
- **Severity:** High
- **Symptom:** When an offline (MediaPipe) model called a tool, the final reply sent to the user was the raw tool result JSON (e.g. `{"success": true, "content": "Paris, 18°C, cloudy"}`), not a natural language answer.
- **Root Cause:** Offline models don't natively support the tool_use / tool_result message format. After executing the tool, Pass 2 sent the tool result back as a user message but the offline model was not given instructions to synthesize it into a reply — it just echoed back the JSON.
- **Fix:** For offline models, Pass 2 now wraps the tool result in an explicit synthesis prompt: `"Based on this data: {toolResult}\n\nAnswer the user's original question in plain language: {originalQuery}"`. This forces the model to produce a natural reply.
- **Lesson:** Offline models need explicit synthesis prompts. They cannot infer "turn this JSON into a sentence" without being told to do so.

---

## BUG-06 — DuckDuckGo web search returned 0 results
- **Phase:** 68 (WebSearchTool)
- **Status:** ✅ Fixed
- **Severity:** High
- **Symptom:** Web search consistently returned 0 results for many queries. The tool reported success but with empty content, causing the LLM to say "I couldn't find anything."
- **Root Cause:** The DuckDuckGo HTML scraping endpoint changed its response structure. The CSS selectors used to extract result titles and snippets no longer matched the new HTML layout. Additionally, some queries with quotes were being rejected by DDG with a redirect instead of results.
- **Fix:** Updated HTML parsing to use updated selectors. Added a Brave Search API fallback: if DDG returns < 2 results, automatically retry with BraveTool if a Brave API key is configured. Added debug logging of raw HTML response length to catch future breakage early.
- **Lesson:** HTML scraping is fragile — always have a fallback. Log raw response size so zero-result failures are immediately visible in logcat.

---

## BUG-05 — Raw `tool_call` JSON leaked into chat messages
- **Phase:** 68–70 (tool pipeline integration)
- **Status:** ✅ Fixed
- **Severity:** Critical
- **Symptom:** Users received messages containing raw JSON blocks like:
  ```
  ```tool_call
  {"tool": "web_search", "args": {"query": "..."}}
  ```
  ```
  This happened instead of (or before) the actual answer.
- **Root Cause:** The LLM response parser did not strip the `tool_call` block from the response before sending it to the user. The code sent the full raw LLM output — including the embedded tool call — as the reply.
- **Fix:** Added `stripToolCallBlocks(response)` which removes all ` ```tool_call ... ``` ` blocks from the response string before it is returned to any channel. Also added stripping of partial/malformed blocks. The cleaned response is used for display; the raw response is used only for tool parsing.
- **Lesson:** Always strip internal LLM formatting artifacts before showing output to users. Parse and display are two separate concerns.

---

## BUG-04 — Stale model ID carried over when switching providers
- **Phase:** 55 (ApiKeysScreen model picker)
- **Status:** ✅ Fixed
- **Severity:** Medium
- **Symptom:** When a user edited an existing key and changed the provider (e.g. from OpenAI to Anthropic), the previously selected OpenAI model ID (e.g. `gpt-4o`) remained as `preferredModel`. This caused Anthropic API calls to fail with "unknown model" errors.
- **Root Cause:** The `selectedModel` state in `KeyEditDialog` was only reset when the dialog opened fresh. When the user tapped a different provider in the edit form, `selectedModel` kept its old value.
- **Fix:** Added an `onProviderChange` side effect: whenever the provider dropdown changes value, `selectedModel` is reset to `""` so the user must re-pick a model for the new provider.
- **Lesson:** When dependent fields change, always reset downstream fields. Provider → model is a dependency chain.

---

## BUG-03 — Test Key always tested the provider default model, not the selected one
- **Phase:** 41 (ApiKeysScreen Test Key button)
- **Status:** ✅ Fixed
- **Severity:** Medium
- **Symptom:** Tapping "Test Key" always called the provider's default model (e.g. `gpt-3.5-turbo`) even when the user had selected `gpt-4o` in the picker. A key could show "✓ Valid" but actually fail on the intended model.
- **Root Cause:** The `testKey()` call was passing `preferredModel = null` instead of the currently chosen `selectedModel` from the UI state. The validator then fell back to the provider default.
- **Fix:** Changed the Test Key button handler to pass `preferredModel = selectedModel` explicitly so the validation call uses the exact model the user intends to save.
- **Lesson:** Test/validate with the exact configuration the user will save, not with defaults.

---

## BUG-02 — HTTP 429 permanently disabled valid API key
- **Phase:** 35 (LlmRouter rate limit handling)
- **Status:** ✅ Fixed
- **Severity:** High
- **Symptom:** After a single 429 (rate limit) response from an API, the key was marked as failed and removed from the failover rotation for the rest of the session. Users had to restart the app to recover their keys.
- **Root Cause:** The error handler treated all non-2xx responses as hard failures (`markFailed(key)`). A 429 is a soft, temporary limit — not a credential failure.
- **Fix:** Added specific 429 detection: instead of marking the key failed, the router logs it as a rate-limit event, waits a short backoff, and moves to the next key in the rotation. The rate-limited key remains in the pool and is retried after all other keys are exhausted.
- **Lesson:** HTTP errors have different semantics. 401/403 = hard fail (bad credentials). 429 = soft fail (try again later). 5xx = transient (retry with backoff). Handle each category differently.

---

## BUG-01 — NPE crash on `ApiKeyEntry` fields after Gson deserialization
- **Phase:** 37 (ApiKeyEntry + all callers)
- **Status:** ✅ Fixed
- **Severity:** Critical
- **Symptom:** App crashed with `NullPointerException` on `key.label`, `key.provider`, or `key.apiKey` after loading keys from DataStore. Only occurred after keys were saved with an older app version that lacked certain fields.
- **Root Cause:** Gson by default deserializes missing JSON fields as `null` even for non-nullable Kotlin properties. When the stored JSON was written by an older build that didn't have `baseUrl` or `preferredModel`, those fields came back as `null` at runtime despite Kotlin's `String` type declaration.
- **Fix:** Added safe accessor properties to `ApiKeyEntry`:
  ```kotlin
  val safeLabel: String get() = label ?: ""
  val safeProvider: String get() = provider ?: "openai"
  val safeApiKey: String get() = apiKey ?: ""
  val safePreferredModel: String get() = preferredModel ?: ""
  val safeBaseUrl: String get() = baseUrl ?: ""
  ```
  Replaced all direct field accesses with safe accessors throughout the codebase. Also added `GsonBuilder().serializeNulls()` to ensure all fields are always written.
- **Lesson:** Never trust Gson/JSON deserialization for Kotlin non-nullable types. Always provide null-safe accessors for any class that is serialized to persistent storage. Schema evolution (adding fields) will always produce nulls for old data.

---

*Add new bugs above this line using the template below.*

---

## Bug Entry Template

```
## BUG-[N] — [Short title]
- **Phase:** [phase number or area]
- **Status:** 🔴 Open / 🟡 In Progress / ✅ Fixed
- **Severity:** Critical / High / Medium / Low
- **Symptom:** [Exact error or wrong behavior the user/developer sees]
- **Root Cause:** [What actually caused it — be specific]
- **Fix:** [What was changed — file, function, what was wrong and what replaced it]
- **Lesson:** [What to watch out for in future phases]
```

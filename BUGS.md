
# ZeroClawAndroid — Bug Tracker

> Every significant bug found during development is recorded here with full details.
> Summary rows are also in the **Bug Log** table in `plan.md`.
> Newest bugs at the top. Format: symptom → root cause → fix → lesson.

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

package ai.zeroclaw.android.webchat

import android.content.Context
import ai.zeroclaw.android.agents.AgentManager
import ai.zeroclaw.android.data.AgentResultDatabase
import ai.zeroclaw.android.data.AgentResultEntity
import ai.zeroclaw.android.data.IotlAnimeDatabase
import ai.zeroclaw.android.data.IotlAnimeEntity
import ai.zeroclaw.android.data.LlmRouter
import ai.zeroclaw.android.service.HomeWidget
import ai.zeroclaw.android.service.ZeroClawService
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.ServerSocket
import java.net.URLDecoder

/**
 * WebChatServer — built-in web chat UI served via HTTP.
 *
 * Serves a simple HTML chat interface accessible via browser.
 * Users can chat with the AI directly through their browser
 * at the tunnel URL or local IP.
 * Port default: 8088
 */
class WebChatServer(private val context: Context) {

    private val llmRouter = LlmRouter.getInstance(context)
    private val agentResultDao = AgentResultDatabase.getInstance(context).agentResultDao()
    private var running = false
    private var serverSocket: ServerSocket? = null

    /**
     * Start the web chat server on the given port.
     */
    suspend fun start(port: Int = 8088) = withContext(Dispatchers.IO) {
        running = true
        ZeroClawService.log("WebChat: listening on port $port...")
        serverSocket = ServerSocket(port)

        while (running) {
            try {
                val socket = serverSocket!!.accept()
                val acceptTime = System.currentTimeMillis()
                // Launch on IO so handleRequest runs concurrently and accept() returns fast
                launch(Dispatchers.IO) {
                    val launchDelay = System.currentTimeMillis() - acceptTime
                    if (launchDelay > 100) {
                        ZeroClawService.log("WebChat: socket launched ${launchDelay}ms after accept (dispatcher busy?)")
                    }
                    handleRequest(socket)
                }
            } catch (e: Exception) {
                if (running) ZeroClawService.log("WebChat: server error — ${e.message}")
            }
        }
    }

    private suspend fun handleRequest(socket: java.net.Socket) {
        val t0 = System.currentTimeMillis()
        val reqId = "wc${t0.toString().takeLast(6)}"
        try {
            // Set SO_TIMEOUT so a silent client can't block the socket read forever.
            // This is short — if no bytes arrive in 30s during reading, we bail.
            socket.soTimeout = 30_000

            // Read raw bytes — avoids Content-Length bytes/chars mismatch that causes hangs.
            // Headers are ASCII so safe to read as bytes line-by-line.
            val input = socket.getInputStream().buffered()
            val out = socket.getOutputStream()

            val requestLine = readLine(input) ?: return
            val tAfterRequestLine = System.currentTimeMillis()
            if (requestLine.startsWith("POST")) {
                ZeroClawService.log("WebChat[$reqId]: ← ${requestLine.take(60)} from ${socket.inetAddress?.hostAddress} (requestLine read in ${tAfterRequestLine - t0}ms)")
            }
            val headers = mutableMapOf<String, String>()
            var contentLength = 0
            while (true) {
                val hLine = readLine(input) ?: break
                if (hLine.isBlank()) break
                val colonIdx = hLine.indexOf(':')
                if (colonIdx > 0) {
                    val key = hLine.substring(0, colonIdx).trim().lowercase()
                    val value = hLine.substring(colonIdx + 1).trim()
                    headers[key] = value
                    if (key == "content-length") contentLength = value.toIntOrNull() ?: 0
                }
            }
            val tAfterHeaders = System.currentTimeMillis()
            if (requestLine.startsWith("POST")) {
                ZeroClawService.log("WebChat[$reqId]: headers read in ${tAfterHeaders - tAfterRequestLine}ms (${headers.size} headers, contentLength=$contentLength)")
            }

            // Read body as raw bytes — Content-Length is in bytes (correct unit now).
            val body = if (requestLine.startsWith("POST") && contentLength > 0) {
                val bodyBytes = ByteArray(contentLength)
                var totalRead = 0
                var readIterations = 0
                val bodyStart = System.currentTimeMillis()
                while (totalRead < contentLength) {
                    readIterations++
                    val iterStart = System.currentTimeMillis()
                    val n = input.read(bodyBytes, totalRead, contentLength - totalRead)
                    val iterElapsed = System.currentTimeMillis() - iterStart
                    if (n <= 0) {
                        ZeroClawService.log("WebChat[$reqId]: body read ABORTED at $totalRead/$contentLength bytes (read returned $n after ${iterElapsed}ms)")
                        break
                    }
                    // Log slow chunks so we can see if body arrives in chunks over time
                    if (iterElapsed > 500) {
                        ZeroClawService.log("WebChat[$reqId]: body read slow chunk — got $n bytes in ${iterElapsed}ms (progress $totalRead→${totalRead + n}/$contentLength)")
                    }
                    totalRead += n
                }
                val bodyElapsed = System.currentTimeMillis() - bodyStart
                ZeroClawService.log("WebChat[$reqId]: body read complete — $totalRead bytes in ${bodyElapsed}ms ($readIterations reads)")
                String(bodyBytes, 0, totalRead, Charsets.UTF_8)
            } else ""

            val tAfterBody = System.currentTimeMillis()
            if (requestLine.startsWith("POST")) {
                ZeroClawService.log("WebChat[$reqId]: total pre-handler time = ${tAfterBody - t0}ms → dispatching to handler")
            }

            // Handle OPTIONS preflight for CORS
            if (requestLine.startsWith("OPTIONS")) {
                val resp = "HTTP/1.1 204 No Content\r\nAccess-Control-Allow-Origin: *\r\nAccess-Control-Allow-Methods: GET, POST, OPTIONS\r\nAccess-Control-Allow-Headers: Content-Type, Authorization\r\nContent-Length: 0\r\nConnection: close\r\n\r\n"
                out.write(resp.toByteArray())
            }

            when {
                requestLine.startsWith("GET /api/discover") -> {
                    val toolNames = llmRouter.getEnabledToolNames()
                    val toolsArray = org.json.JSONArray().also { arr -> toolNames.forEach { arr.put(it) } }
                    val agentsArray = JSONArray()
                    try {
                        val agents = AgentManager.getInstance(context).loadAll()
                        for (a in agents) {
                            agentsArray.put(JSONObject()
                                .put("id", a.id)
                                .put("name", a.name)
                                .put("url", a.url)
                                .put("enabled", a.enabled)
                                .put("type", a.type)
                                .put("interval_minutes", a.intervalMinutes)
                                .put("channel", a.channel)
                                .put("last_status", a.lastStatus)
                            )
                        }
                    } catch (_: Exception) {}
                    sendJson(out, JSONObject()
                        .put("service", "zeroclaw")
                        .put("version", "1.0")
                        .put("port", 8088)
                        .put("endpoints", org.json.JSONArray()
                            .put("/api/chat").put("/api/generate").put("/api/discover")
                            .put("/v1/chat/completions").put("/v1/models")
                            .put("/api/agents/results").put("/api/iotlanime")
                            .put("/api/agents/list").put("/api/agents/create")
                            .put("/api/agents/update").put("/api/agents/delete")
                            .put("/api/agents/toggle").put("/api/tts"))
                        .put("tools_available", toolsArray)
                        .put("agents", agentsArray)
                    )
                }
                // OpenAI-compatible: GET /v1/models
                requestLine.startsWith("GET /v1/models") -> {
                    handleOpenAIModels(out)
                }
                // OpenAI-compatible: POST /v1/chat/completions
                requestLine.startsWith("POST /v1/chat/completions") -> {
                    handleOpenAIChatCompletions(out, body)
                }
                requestLine.startsWith("GET / ") || requestLine.startsWith("GET /chat") -> {
                    sendHtml(out, chatPage())
                }
                requestLine.startsWith("POST /api/generate") || requestLine.startsWith("POST /api/chat") -> {
                    if (requestLine.startsWith("POST /api/generate")) {
                        handleGenerateApi(out, body)
                    } else {
                        handleChatApi(out, body)
                    }
                }
                // IotlAnime API
                requestLine.startsWith("GET /api/iotlanime") -> {
                    handleIotlAnimeGet(out, requestLine)
                }
                requestLine.startsWith("DELETE /api/iotlanime") -> {
                    handleIotlAnimeDelete(out, requestLine)
                }
                // Agent Results API — Phase 175
                requestLine.startsWith("GET /api/agents/results") -> {
                    handleAgentResultsGet(out, requestLine)
                }
                requestLine.startsWith("DELETE /api/agents/results") -> {
                    handleAgentResultsDelete(out, requestLine)
                }
                // Agent CRUD API — for autom remote management
                requestLine.startsWith("GET /api/agents/list") -> {
                    handleAgentsList(out)
                }
                requestLine.startsWith("POST /api/agents/create") -> {
                    handleAgentCreate(out, body)
                }
                requestLine.startsWith("POST /api/agents/update") -> {
                    handleAgentUpdate(out, body)
                }
                requestLine.startsWith("DELETE /api/agents/delete") -> {
                    handleAgentDelete(out, requestLine)
                }
                requestLine.startsWith("POST /api/agents/toggle") -> {
                    handleAgentToggle(out, body)
                }
                // Phase 185 — Gemini TTS proxy (called by autom's tryZeroClawTts)
                requestLine.startsWith("POST /api/tts") -> {
                    handleTtsApi(out, body)
                }
                else -> {
                    val response = "HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\n\r\n"
                    out.write(response.toByteArray())
                }
            }

            val tAfterHandler = System.currentTimeMillis()
            out.flush()
            val tAfterFlush = System.currentTimeMillis()
            if (requestLine.startsWith("POST")) {
                ZeroClawService.log("WebChat[$reqId]: done — handler=${tAfterHandler - tAfterBody}ms, flush=${tAfterFlush - tAfterHandler}ms, total=${tAfterFlush - t0}ms")
            }
            socket.close()
        } catch (e: Exception) {
            val elapsed = System.currentTimeMillis() - t0
            ZeroClawService.log("WebChat[$reqId]: request error after ${elapsed}ms — ${e.javaClass.simpleName}: ${e.message}")
            runCatching { socket.close() }
        }
    }

    private suspend fun handleChatApi(out: java.io.OutputStream, body: String) {
        try {
            val json = JSONObject(body)
            val message = json.optString("message", "").trim()
            val sessionId = json.optString("session_id", "webchat_default")

            if (message.isBlank()) {
                sendJson(out, JSONObject().put("error", "Empty message"))
                return
            }

            ZeroClawService.log("WebChat @$sessionId: $message")
            val reply = llmRouter.call(message, chatId = "webchat_$sessionId")
            ZeroClawService.log("WebChat: reply sent (${reply.length} chars)")

            sendJson(out, JSONObject().put("reply", reply))
        } catch (e: Exception) {
            ZeroClawService.log("WebChat: API error — ${e.message}")
            sendJson(out, JSONObject().put("error", e.message ?: "Unknown error"))
        }
    }

    /**
     * POST /api/generate — raw LLM generation, no agent pipeline.
     * Request:  {"prompt": "...", "session_id": "optional", "publish_to_iotlanime": false}
     * Response: {"text": "raw LLM output"}
     */
    private suspend fun handleGenerateApi(out: java.io.OutputStream, body: String) {
        try {
            val json = JSONObject(body)
            val prompt = json.optString("prompt", json.optString("message", "")).trim()
            val jsonMode = json.optBoolean("json_mode", false)
            val maxTokens = json.optInt("max_tokens", 32768)
            val useTools = json.optBoolean("use_tools", false)
            val publishToIotlAnime = json.optBoolean("publish_to_iotlanime", false)
            val iotlanimeTitle = json.optString("iotlanime_title", "").trim()

            if (prompt.isBlank()) {
                sendJson(out, JSONObject().put("error", "Empty prompt"))
                return
            }

            ZeroClawService.log("Generate: ${prompt.take(100)}… (json=$jsonMode, maxTokens=$maxTokens, tools=$useTools, iotlanime=$publishToIotlAnime)")
            val text = if (useTools) {
                llmRouter.rawGenerateWithTools(prompt, jsonMode = jsonMode, maxTokens = maxTokens)
            } else {
                llmRouter.rawGenerate(prompt, jsonMode = jsonMode, maxTokens = maxTokens)
            }
            ZeroClawService.log("Generate: response sent (${text.length} chars)")

            // Save to IotlAnime database if requested
            if (publishToIotlAnime && text.isNotBlank()) {
                try {
                    val dao = IotlAnimeDatabase.getInstance(context).iotlAnimeDao()
                    val entryTitle = iotlanimeTitle.ifBlank { extractUserTopic(prompt) }
                    dao.insert(IotlAnimeEntity(
                        title = entryTitle,
                        jsonContent = text
                    ))
                    ZeroClawService.log("IotlAnime: 📥 saved \"$entryTitle\" (${text.length} chars)")
                    HomeWidget.broadcastUpdate(context)
                } catch (e: Exception) {
                    ZeroClawService.log("IotlAnime: save failed — ${e.message}")
                }
            }

            sendJson(out, JSONObject().put("text", text))
        } catch (e: Exception) {
            ZeroClawService.log("Generate: error — ${e.message}")
            sendJson(out, JSONObject().put("error", e.message ?: "Unknown error"))
        }
    }

    /**
     * GET /api/iotlanime — returns all saved IotlAnime entries.
     * Optional query params: ?limit=N&offset=N&id=N
     */
    private suspend fun handleIotlAnimeGet(out: java.io.OutputStream, requestLine: String) {
        try {
            val dao = IotlAnimeDatabase.getInstance(context).iotlAnimeDao()
            val queryString = requestLine.substringAfter("?", "").substringBefore(" HTTP")
            val params = parseQueryParams(queryString)

            // If ?id=N, return single entry
            val singleId = params["id"]?.toLongOrNull()
            if (singleId != null) {
                val all = dao.getAll()
                val entry = all.firstOrNull { it.id == singleId }
                if (entry != null) {
                    ZeroClawService.log("IotlAnime: 📋 GET entry #$singleId")
                    HomeWidget.broadcastUpdate(context)
                    sendJson(out, JSONObject()
                        .put("id", entry.id)
                        .put("title", entry.title)
                        .put("content", entry.jsonContent)
                        .put("timestamp", entry.timestamp)
                        .put("synced", entry.syncedToCloud)
                    )
                } else {
                    sendJson(out, JSONObject().put("error", "Not found"))
                }
                return
            }

            val entries = dao.getAll()
            val arr = JSONArray()
            for (e in entries) {
                arr.put(JSONObject()
                    .put("id", e.id)
                    .put("title", e.title)
                    .put("content", e.jsonContent)
                    .put("timestamp", e.timestamp)
                    .put("synced", e.syncedToCloud)
                )
            }
            ZeroClawService.log("IotlAnime: 📋 GET ${entries.size} entries")
            HomeWidget.broadcastUpdate(context)
            sendJson(out, JSONObject()
                .put("count", entries.size)
                .put("entries", arr)
            )
        } catch (e: Exception) {
            ZeroClawService.log("IotlAnime GET: error — ${e.message}")
            sendJson(out, JSONObject().put("error", e.message ?: "Unknown error"))
        }
    }

    /**
     * DELETE /api/iotlanime — delete entries.
     * Query params: ?id=N (single) or ?all=true (delete all)
     */
    private suspend fun handleIotlAnimeDelete(out: java.io.OutputStream, requestLine: String) {
        try {
            val dao = IotlAnimeDatabase.getInstance(context).iotlAnimeDao()
            val queryString = requestLine.substringAfter("?", "").substringBefore(" HTTP")
            val params = parseQueryParams(queryString)

            val id = params["id"]?.toLongOrNull()
            val deleteAll = params["all"]?.toBoolean() ?: false

            when {
                id != null -> {
                    dao.deleteById(id)
                    ZeroClawService.log("IotlAnime: 🗑️ deleted entry #$id")
                    HomeWidget.broadcastUpdate(context)
                    sendJson(out, JSONObject().put("deleted", id))
                }
                deleteAll -> {
                    val count = dao.count()
                    dao.deleteAll()
                    ZeroClawService.log("IotlAnime: 🗑️ deleted all $count entries")
                    HomeWidget.broadcastUpdate(context)
                    sendJson(out, JSONObject().put("deleted", "all"))
                }
                else -> {
                    sendJson(out, JSONObject().put("error", "Specify ?id=N or ?all=true"))
                }
            }
        } catch (e: Exception) {
            ZeroClawService.log("IotlAnime DELETE: error — ${e.message}")
            sendJson(out, JSONObject().put("error", e.message ?: "Unknown error"))
        }
    }

    private fun sendHtml(out: java.io.OutputStream, html: String) {
        val bytes = html.toByteArray()
        val response = "HTTP/1.1 200 OK\r\nContent-Type: text/html; charset=utf-8\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n"
        out.write(response.toByteArray())
        out.write(bytes)
    }

    /** Read one HTTP header line (CRLF or LF terminated) from a raw byte stream — no BufferedReader needed. */
    private fun readLine(input: java.io.InputStream): String? {
        val buf = ByteArray(8192)
        var pos = 0
        while (pos < buf.size) {
            val b = input.read()
            if (b == -1) return if (pos > 0) String(buf, 0, pos, Charsets.UTF_8) else null
            if (b == '\n'.code) {
                // Strip trailing CR if present
                val end = if (pos > 0 && buf[pos - 1] == '\r'.code.toByte()) pos - 1 else pos
                return String(buf, 0, end, Charsets.UTF_8)
            }
            buf[pos++] = b.toByte()
        }
        return String(buf, 0, pos, Charsets.UTF_8) // line longer than 8K — return what we have
    }

    private fun sendJson(out: java.io.OutputStream, json: JSONObject) {
        val bytes = json.toString().toByteArray()
        val response = "HTTP/1.1 200 OK\r\nContent-Type: application/json; charset=utf-8\r\nContent-Length: ${bytes.size}\r\nAccess-Control-Allow-Origin: *\r\nConnection: close\r\n\r\n"
        out.write(response.toByteArray())
        out.write(bytes)
    }

    private fun sendJsonStatus(out: java.io.OutputStream, statusLine: String, json: JSONObject) {
        val bytes = json.toString().toByteArray()
        val response = "HTTP/1.1 $statusLine\r\nContent-Type: application/json; charset=utf-8\r\nContent-Length: ${bytes.size}\r\nAccess-Control-Allow-Origin: *\r\nConnection: close\r\n\r\n"
        out.write(response.toByteArray())
        out.write(bytes)
    }

    /**
     * POST /api/tts — Gemini TTS proxy for autom (and any other client wanting to
     * route TTS through ZeroClaw's higher-quota Gemini key).
     *
     * Request:  {"text": string, "voice": string, "tone": string?}
     *   tone is informational only — autom prepends "Say in a {tone} tone:" to text
     *   before calling, so we forward `text` to Gemini verbatim.
     * Response (200): {"audio_base64", "sample_rate": 24000, "channels": 1, "format": "pcm_s16le"}
     * Errors:
     *   400 — empty text
     *   502 — every Gemini key failed (waterfall) → autom falls back to direct Gemini
     */
    private suspend fun handleTtsApi(out: java.io.OutputStream, body: String) {
        try {
            val json = JSONObject(body)
            val text = json.optString("text", "").trim()
            val voice = json.optString("voice", "Puck").ifBlank { "Puck" }
            if (text.isBlank()) {
                sendJsonStatus(out, "400 Bad Request", JSONObject().put("error", "text required"))
                return
            }
            ZeroClawService.log("TTS: ${text.length} chars, voice=$voice")
            val result = llmRouter.generateTtsAudio(text, voice)
            sendJson(out, JSONObject()
                .put("audio_base64", result.audioBase64)
                .put("sample_rate", result.sampleRate)
                .put("channels", result.channels)
                .put("format", "pcm_s16le"))
        } catch (e: Exception) {
            ZeroClawService.log("TTS: error — ${e.message}")
            sendJsonStatus(out, "502 Bad Gateway", JSONObject().put("error", e.message ?: "Unknown error"))
        }
    }

    private fun chatPage(): String = """
<!DOCTYPE html>
<html lang="en">
<head>
<meta charset="UTF-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>ZeroClaw AI</title>
<style>
*{margin:0;padding:0;box-sizing:border-box}
body{font-family:-apple-system,BlinkMacSystemFont,'Segoe UI',sans-serif;background:#0d1117;color:#c9d1d9;height:100vh;display:flex;flex-direction:column}
header{background:#161b22;padding:12px 20px;border-bottom:1px solid #30363d;display:flex;align-items:center;gap:12px}
header h1{font-size:18px;color:#e53935}
header span{font-size:13px;color:#8b949e}
/* Tabs */
.tabs{display:flex;background:#161b22;border-bottom:1px solid #30363d}
.tab{flex:1;padding:12px;text-align:center;font-size:14px;font-weight:600;cursor:pointer;color:#8b949e;border-bottom:2px solid transparent;transition:all .2s}
.tab:hover{color:#c9d1d9;background:#1c2028}
.tab.active{color:#e53935;border-bottom-color:#e53935}
.tab-content{display:none;flex:1;flex-direction:column;overflow:hidden}
.tab-content.active{display:flex}
/* Chat */
#messages{flex:1;overflow-y:auto;padding:16px;display:flex;flex-direction:column;gap:12px}
.msg{max-width:80%;padding:10px 14px;border-radius:12px;line-height:1.5;white-space:pre-wrap;word-wrap:break-word;font-size:14px}
.user{background:#1f6feb;color:#fff;align-self:flex-end;border-bottom-right-radius:4px}
.bot{background:#21262d;color:#c9d1d9;align-self:flex-start;border-bottom-left-radius:4px;border:1px solid #30363d}
.typing{color:#8b949e;font-style:italic;align-self:flex-start;padding:8px 14px}
#input-bar{background:#161b22;padding:12px 16px;border-top:1px solid #30363d;display:flex;gap:8px}
#msg-input{flex:1;padding:10px 14px;border-radius:8px;border:1px solid #30363d;background:#0d1117;color:#c9d1d9;font-size:14px;outline:none}
#msg-input:focus{border-color:#e53935}
#send-btn{padding:10px 20px;border-radius:8px;border:none;background:#238636;color:#fff;font-weight:600;cursor:pointer;font-size:14px}
#send-btn:hover{background:#2ea043}
#send-btn:disabled{background:#21262d;color:#484f58;cursor:not-allowed}
/* Agents */
.agents-wrap{flex:1;overflow-y:auto;padding:16px}
.agent-card{background:#161b22;border:1px solid #21262d;border-radius:10px;padding:14px 16px;margin-bottom:8px;cursor:pointer;display:flex;align-items:center;gap:12px;transition:all .2s}
.agent-card:hover{border-color:#e53935;background:#1a1520}
.agent-card.sel{border-color:#e53935;background:#1a1520}
.ag-dot{width:10px;height:10px;border-radius:50%;flex-shrink:0}
.ag-dot.on{background:#3fb950}
.ag-dot.off{background:#484f58}
.ag-info{flex:1;min-width:0}
.ag-name{font-weight:600;font-size:14px;color:#f0f6fc;white-space:nowrap;overflow:hidden;text-overflow:ellipsis}
.ag-meta{font-size:11px;color:#8b949e;margin-top:2px;display:flex;gap:10px;flex-wrap:wrap}
.ag-badge{font-size:10px;font-weight:700;padding:2px 8px;border-radius:10px}
.b-on{background:#0d2818;color:#3fb950}
.b-off{background:#21262d;color:#8b949e}
.agents-empty{text-align:center;color:#484f58;padding:40px 16px;font-size:14px}
/* Results Panel */
.results-overlay{display:none;position:fixed;top:0;left:0;right:0;bottom:0;background:rgba(0,0,0,.6);z-index:10}
.results-overlay.open{display:flex;justify-content:center;align-items:start;padding:20px}
.results-panel{background:#0d1117;border:1px solid #30363d;border-radius:14px;width:100%;max-width:700px;max-height:90vh;display:flex;flex-direction:column;overflow:hidden}
.rp-header{display:flex;align-items:center;justify-content:space-between;padding:16px 20px;border-bottom:1px solid #21262d;background:#161b22;border-radius:14px 14px 0 0}
.rp-header h2{font-size:16px;color:#f0f6fc}
.rp-close{background:none;border:none;color:#8b949e;font-size:22px;cursor:pointer;padding:0 4px}
.rp-close:hover{color:#f85149}
.rp-stats{display:flex;gap:10px;padding:14px 20px;border-bottom:1px solid #21262d;flex-wrap:wrap}
.stat{background:#161b22;border:1px solid #21262d;border-radius:8px;padding:8px 14px;text-align:center;flex:1;min-width:70px}
.stat-val{font-size:18px;font-weight:700;color:#f0f6fc}
.stat-lbl{font-size:9px;color:#8b949e;text-transform:uppercase;margin-top:2px}
.rp-list{flex:1;overflow-y:auto;padding:14px 20px}
.r-card{background:#161b22;border:1px solid #21262d;border-radius:10px;padding:14px;margin-bottom:8px}
.r-top{display:flex;align-items:center;justify-content:space-between;margin-bottom:6px}
.r-status{font-size:10px;font-weight:700;padding:2px 10px;border-radius:10px;text-transform:uppercase}
.r-status.success{background:#0d2818;color:#3fb950}
.r-status.failed{background:#2d1215;color:#f85149}
.r-status.skipped{background:#1c1e24;color:#d29922}
.r-status.partial{background:#1c1e24;color:#d29922}
.r-time{font-size:11px;color:#8b949e}
.r-url{font-size:11px;color:#58a6ff;word-break:break-all;margin-bottom:4px}
.r-api{font-size:10px;font-weight:700;background:#1c1e24;color:#d29922;padding:2px 8px;border-radius:10px;display:inline-block;margin-bottom:4px}
.r-delivered{font-size:11px;color:#8b949e;margin-top:4px}
.r-error{font-size:12px;color:#f85149;margin-top:4px}
.r-toggle{background:none;border:none;color:#58a6ff;font-size:12px;cursor:pointer;padding:0;margin-top:6px}
.r-toggle:hover{text-decoration:underline}
.r-content{font-size:12px;color:#c9d1d9;white-space:pre-wrap;word-wrap:break-word;max-height:200px;overflow-y:auto;background:#0d1117;padding:10px;border-radius:6px;border:1px solid #21262d;margin-top:6px;display:none}
.rp-pages{display:flex;justify-content:center;gap:8px;padding:12px 20px;border-top:1px solid #21262d}
.pg-btn{background:#21262d;color:#c9d1d9;padding:6px 14px;border:1px solid #30363d;border-radius:6px;font-size:12px;cursor:pointer}
.pg-btn:hover{background:#30363d}
.pg-btn:disabled{opacity:.4;cursor:not-allowed}
.rp-empty{text-align:center;color:#484f58;padding:30px;font-size:13px}
/* APIs tab */
.apis-wrap{flex:1;overflow-y:auto;padding:16px}
.api-section{margin-bottom:20px}
.api-section h3{font-size:14px;color:#e53935;margin-bottom:10px;text-transform:uppercase;letter-spacing:.5px}
.api-card{background:#161b22;border:1px solid #21262d;border-radius:10px;padding:14px 16px;margin-bottom:8px;display:flex;align-items:center;gap:12px}
.api-method{font-size:11px;font-weight:700;padding:3px 10px;border-radius:6px;flex-shrink:0;min-width:52px;text-align:center}
.api-method.get{background:#0d2818;color:#3fb950}
.api-method.post{background:#0d1d3a;color:#58a6ff}
.api-method.delete{background:#2d1215;color:#f85149}
.api-path{font-size:14px;font-weight:600;color:#f0f6fc;font-family:monospace}
.api-desc{font-size:12px;color:#8b949e;margin-top:2px}
.api-public{font-size:10px;font-weight:700;padding:2px 8px;border-radius:10px;background:#1c1e24;color:#d29922;margin-left:auto;flex-shrink:0}
/* IotlAnime entries */
.iotl-card{background:#161b22;border:1px solid #21262d;border-radius:10px;padding:14px 16px;margin-bottom:8px}
.iotl-top{display:flex;align-items:center;justify-content:space-between;margin-bottom:6px}
.iotl-title{font-size:14px;font-weight:600;color:#f0f6fc;flex:1;overflow:hidden;text-overflow:ellipsis;white-space:nowrap}
.iotl-time{font-size:11px;color:#8b949e}
.iotl-actions{display:flex;gap:6px;margin-top:8px}
.iotl-btn{background:#21262d;color:#c9d1d9;padding:4px 12px;border:1px solid #30363d;border-radius:6px;font-size:11px;cursor:pointer}
.iotl-btn:hover{background:#30363d}
.iotl-btn.del{color:#f85149;border-color:#f8514933}
.iotl-btn.del:hover{background:#2d1215}
.iotl-empty{text-align:center;color:#484f58;padding:30px;font-size:13px}
.iotl-count{font-size:12px;color:#8b949e;margin-bottom:10px}
</style>
</head>
<body>
<header><h1>ZeroClaw AI</h1><span>Android Agent</span></header>
<div class="tabs">
  <div class="tab active" onclick="switchTab('chat')">Chat</div>
  <div class="tab" onclick="switchTab('agents')">Agents</div>
  <div class="tab" onclick="switchTab('apis')">APIs</div>
</div>

<!-- Chat Tab -->
<div class="tab-content active" id="tab-chat">
  <div id="messages"></div>
  <div id="input-bar">
    <input id="msg-input" placeholder="Type a message..." autocomplete="off">
    <button id="send-btn" onclick="send()">Send</button>
  </div>
</div>

<!-- Agents Tab -->
<div class="tab-content" id="tab-agents">
  <div class="agents-wrap" id="agents-wrap">
    <div class="agents-empty" id="agents-loading">Loading agents...</div>
  </div>
</div>

<!-- APIs Tab -->
<div class="tab-content" id="tab-apis">
  <div class="apis-wrap">
    <div class="api-section">
      <h3>Exposed Endpoints</h3>
      <div class="api-card"><span class="api-method post">POST</span><div><div class="api-path">/v1/chat/completions</div><div class="api-desc">OpenAI-compatible chat completions</div></div><span class="api-public">PUBLIC</span></div>
      <div class="api-card"><span class="api-method get">GET</span><div><div class="api-path">/v1/models</div><div class="api-desc">OpenAI-compatible model listing</div></div><span class="api-public">PUBLIC</span></div>
      <div class="api-card"><span class="api-method post">POST</span><div><div class="api-path">/api/chat</div><div class="api-desc">ZeroClaw native chat with session memory</div></div><span class="api-public">PUBLIC</span></div>
      <div class="api-card"><span class="api-method post">POST</span><div><div class="api-path">/api/generate</div><div class="api-desc">Raw LLM generation (supports publish_to_iotlanime)</div></div><span class="api-public">PUBLIC</span></div>
      <div class="api-card"><span class="api-method get">GET</span><div><div class="api-path">/api/discover</div><div class="api-desc">Service discovery — version, endpoints, tools, agents</div></div><span class="api-public">PUBLIC</span></div>
      <div class="api-card"><span class="api-method get">GET</span><div><div class="api-path">/api/iotlanime</div><div class="api-desc">Published anime/list entries from VideoGen</div></div><span class="api-public">PUBLIC</span></div>
      <div class="api-card"><span class="api-method delete">DELETE</span><div><div class="api-path">/api/iotlanime</div><div class="api-desc">Delete entries (?id=N or ?all=true)</div></div><span class="api-public">PUBLIC</span></div>
      <div class="api-card"><span class="api-method get">GET</span><div><div class="api-path">/api/agents/results</div><div class="api-desc">Agent run results (filter by agent_id, id, limit, offset)</div></div><span class="api-public">PUBLIC</span></div>
      <div class="api-card"><span class="api-method delete">DELETE</span><div><div class="api-path">/api/agents/results</div><div class="api-desc">Delete agent results</div></div><span class="api-public">PUBLIC</span></div>
    </div>
    <div class="api-section">
      <h3>IotlAnime Published Lists</h3>
      <div id="iotl-count" class="iotl-count"></div>
      <div id="iotl-list"><div class="iotl-empty">Loading...</div></div>
    </div>
  </div>
</div>

<!-- Results Overlay -->
<div class="results-overlay" id="results-overlay" onclick="if(event.target===this)closeResults()">
  <div class="results-panel">
    <div class="rp-header">
      <h2 id="rp-title">Agent Results</h2>
      <button class="rp-close" onclick="closeResults()">&times;</button>
    </div>
    <div class="rp-stats" id="rp-stats"></div>
    <div class="rp-list" id="rp-list"></div>
    <div class="rp-pages" id="rp-pages"></div>
  </div>
</div>

<script>
const sid='web_'+Math.random().toString(36).substr(2,8);
const msgs=document.getElementById('messages');
const inp=document.getElementById('msg-input');
const btn=document.getElementById('send-btn');
inp.addEventListener('keydown',e=>{if(e.key==='Enter'&&!e.shiftKey){e.preventDefault();send()}});

function esc(s){const d=document.createElement('div');d.textContent=s;return d.innerHTML}
function addMsg(t,c){const d=document.createElement('div');d.className='msg '+c;d.textContent=t;msgs.appendChild(d);msgs.scrollTop=msgs.scrollHeight}

async function send(){
  const t=inp.value.trim();if(!t)return;
  inp.value='';addMsg(t,'user');btn.disabled=true;
  const ty=document.createElement('div');ty.className='typing';ty.textContent='Thinking...';msgs.appendChild(ty);msgs.scrollTop=msgs.scrollHeight;
  try{
    const r=await fetch('/api/chat',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({message:t,session_id:sid})});
    const j=await r.json();ty.remove();
    addMsg(j.reply||j.error||'No response','bot');
  }catch(e){ty.remove();addMsg('Error: '+e.message,'bot')}
  btn.disabled=false;inp.focus();
}
addMsg('Welcome to ZeroClaw AI Chat! Type a message to get started.','bot');
inp.focus();

// ── Tabs ──
function switchTab(tab){
  document.querySelectorAll('.tab').forEach(t=>t.classList.remove('active'));
  document.querySelectorAll('.tab-content').forEach(t=>t.classList.remove('active'));
  document.querySelector('.tab-content#tab-'+tab).classList.add('active');
  event.target.classList.add('active');
  if(tab==='agents') loadAgents();
  if(tab==='apis') loadIotlAnime();
  if(tab==='chat') inp.focus();
}

// ── IotlAnime ──
async function loadIotlAnime(){
  const list=document.getElementById('iotl-list');
  const count=document.getElementById('iotl-count');
  list.innerHTML='<div class="iotl-empty">Loading...</div>';
  try{
    const r=await fetch('/api/iotlanime');
    const d=await r.json();
    const entries=d.entries||[];
    count.textContent=entries.length+' published '+(entries.length===1?'entry':'entries');
    if(entries.length===0){
      list.innerHTML='<div class="iotl-empty">No published entries yet. Enable "Publish to IotlAnime API" in VideoGen to save lists here.</div>';
      return;
    }
    list.innerHTML=entries.map(e=>{
      const tm=new Date(e.timestamp).toLocaleString();
      const preview=e.content.length>200?e.content.substring(0,200)+'...':e.content;
      const cId='iotl_c_'+e.id;
      return '<div class="iotl-card"><div class="iotl-top"><span class="iotl-title">'+esc(e.title)+'</span><span class="iotl-time">'+tm+'</span></div>'+
        '<button class="r-toggle" onclick="tog(\''+cId+'\',this,\'content\')">Show content</button>'+
        '<div class="r-content" id="'+cId+'">'+esc(e.content)+'</div>'+
        '<div class="iotl-actions">'+
        '<button class="iotl-btn" onclick="copyIotl('+e.id+')">Copy JSON</button>'+
        '<button class="iotl-btn del" onclick="deleteIotl('+e.id+')">Delete</button>'+
        '</div></div>';
    }).join('');
  }catch(e){
    list.innerHTML='<div class="iotl-empty">Error: '+esc(e.message)+'</div>';
  }
}
async function copyIotl(id){
  try{
    const r=await fetch('/api/iotlanime?id='+id);
    const d=await r.json();
    await navigator.clipboard.writeText(d.content||'');
    alert('Copied to clipboard!');
  }catch(e){alert('Copy failed: '+e.message)}
}
async function deleteIotl(id){
  if(!confirm('Delete this entry?'))return;
  try{
    await fetch('/api/iotlanime?id='+id,{method:'DELETE'});
    loadIotlAnime();
  }catch(e){alert('Delete failed: '+e.message)}
}

// ── Agents ──
let agents=[];
let selAgent=null;
let rPage=0;
const PER_PAGE=20;

async function loadAgents(){
  const wrap=document.getElementById('agents-wrap');
  wrap.innerHTML='<div class="agents-empty">Loading agents...</div>';
  try{
    // Get agent configs from discover
    const dResp=await fetch('/api/discover');
    const disc=await dResp.json();
    const configs=(disc.agents||[]);

    // Get all results to count runs per agent
    const rResp=await fetch('/api/agents/results?limit=10000');
    const rData=await rResp.json();
    const results=rData.results||[];

    // Build stats per agent
    const statsMap={};
    for(const r of results){
      if(!statsMap[r.agent_id]) statsMap[r.agent_id]={total:0,ok:0,fail:0,last:0,lastStatus:'',name:r.agent_name,url:r.url};
      const s=statsMap[r.agent_id];
      s.total++;
      if(r.status==='success') s.ok++;
      else if(r.status==='failed') s.fail++;
      if(r.timestamp>s.last){s.last=r.timestamp;s.lastStatus=r.status}
    }

    // Merge configs + results (show all agents, even those with no results)
    const merged={};
    for(const c of configs){
      merged[c.id]={id:c.id,name:c.name,url:c.url||'',enabled:c.enabled,interval:c.interval_minutes,channel:c.channel,lastAppStatus:c.last_status,...(statsMap[c.id]||{total:0,ok:0,fail:0,last:0,lastStatus:''})};
    }
    // Skip deleted agents — only show agents that exist in config

    agents=Object.values(merged).sort((a,b)=>b.last-a.last);

    if(agents.length===0){
      wrap.innerHTML='<div class="agents-empty">No agents found. Create agents in the ZeroClaw app.</div>';
      return;
    }

    wrap.innerHTML=agents.map(a=>{
      const on=a.enabled===true;
      const off=a.enabled===false;
      const dot=on?'on':'off';
      const badge=on?'b-on':'b-off';
      const badgeTxt=a.enabled===null?'—':(on?'ACTIVE':'INACTIVE');
      const lastStr=a.last>0?timeAgo(a.last):'Never';
      return '<div class="agent-card" onclick="openAgent(\''+a.id+'\')">'+
        '<div class="ag-dot '+dot+'"></div>'+
        '<div class="ag-info"><div class="ag-name">'+esc(a.name)+'</div>'+
        '<div class="ag-meta"><span>'+a.total+' runs</span><span>Last: '+lastStr+'</span>'+
        '<span>'+a.ok+' ok / '+a.fail+' fail</span></div></div>'+
        '<span class="ag-badge '+badge+'">'+badgeTxt+'</span></div>';
    }).join('');
  }catch(e){
    wrap.innerHTML='<div class="agents-empty">Error: '+esc(e.message)+'</div>';
  }
}

function openAgent(id){
  selAgent=id;rPage=0;
  document.getElementById('results-overlay').classList.add('open');
  loadResults();
}
function closeResults(){
  document.getElementById('results-overlay').classList.remove('open');
  selAgent=null;
}

async function loadResults(){
  const list=document.getElementById('rp-list');
  const stats=document.getElementById('rp-stats');
  const title=document.getElementById('rp-title');
  const pages=document.getElementById('rp-pages');
  list.innerHTML='<div class="rp-empty">Loading...</div>';
  stats.innerHTML='';pages.innerHTML='';

  const a=agents.find(x=>x.id===selAgent);
  title.textContent=a?a.name+' — Results':'Agent Results';

  try{
    const off=rPage*PER_PAGE;
    const r=await fetch('/api/agents/results?agent_id='+encodeURIComponent(selAgent)+'&limit='+PER_PAGE+'&offset='+off);
    const d=await r.json();
    const res=d.results||[];
    const total=d.total||0;

    stats.innerHTML='<div class="stat"><div class="stat-val">'+total+'</div><div class="stat-lbl">Total</div></div>'+
      '<div class="stat"><div class="stat-val" style="color:#3fb950">'+(a?a.ok:0)+'</div><div class="stat-lbl">Success</div></div>'+
      '<div class="stat"><div class="stat-val" style="color:#f85149">'+(a?a.fail:0)+'</div><div class="stat-lbl">Failed</div></div>';

    if(res.length===0){
      list.innerHTML='<div class="rp-empty">No results yet for this agent</div>';
    }else{
      list.innerHTML=res.map(r=>{
        const tm=new Date(r.timestamp).toLocaleString();
        const del=Array.isArray(r.delivered_to)?r.delivered_to.join(', '):'';
        const cId='ec_'+r.id;
        const rId='rc_'+r.id;
        const hasC=r.extracted_content&&r.extracted_content.length>0;
        const hasR=r.raw_content&&r.raw_content.length>0;
        return '<div class="r-card"><div class="r-top"><span class="r-status '+r.status+'">'+r.status+'</span><span class="r-time">'+tm+'</span></div>'+
          (r.url?'<div class="r-url">'+esc(r.url)+'</div>':'')+
          (r.used_api?'<span class="r-api">FREE API</span>':'')+
          (del?'<div class="r-delivered">Delivered to: '+esc(del)+'</div>':'')+
          (r.error_message?'<div class="r-error">'+esc(r.error_message)+'</div>':'')+
          (hasC?'<button class="r-toggle" onclick="tog(\''+cId+'\',this,\'extracted\')">Show extracted content</button><div class="r-content" id="'+cId+'">'+esc(r.extracted_content)+'</div>':'')+
          (hasR?'<button class="r-toggle" onclick="tog(\''+rId+'\',this,\'raw\')">Show raw content</button><div class="r-content" id="'+rId+'">'+esc(r.raw_content)+'</div>':'')+
          '</div>';
      }).join('');
    }

    const tp=Math.ceil(total/PER_PAGE);
    if(tp>1){
      pages.innerHTML='<button class="pg-btn" onclick="pgPrev()" '+(rPage===0?'disabled':'')+'>Prev</button>'+
        '<span style="color:#8b949e;font-size:12px;padding:6px">'+((rPage+1)+' / '+tp)+'</span>'+
        '<button class="pg-btn" onclick="pgNext('+tp+')" '+(rPage>=tp-1?'disabled':'')+'>Next</button>';
    }
  }catch(e){
    list.innerHTML='<div class="rp-empty">Error: '+esc(e.message)+'</div>';
  }
}

function tog(id,btn,type){
  const el=document.getElementById(id);if(!el)return;
  const show=el.style.display==='none'||!el.style.display;
  el.style.display=show?'block':'none';
  btn.textContent=(show?'Hide ':'Show ')+type+' content';
}
function pgPrev(){if(rPage>0){rPage--;loadResults()}}
function pgNext(tp){if(rPage<tp-1){rPage++;loadResults()}}

function timeAgo(ts){
  const s=Math.floor((Date.now()-ts)/1000);
  if(s<60)return s+'s ago';
  const m=Math.floor(s/60);if(m<60)return m+'m ago';
  const h=Math.floor(m/60);if(h<24)return h+'h ago';
  return Math.floor(h/24)+'d ago';
}
</script>
</body>
</html>
    """.trimIndent()

    /**
     * GET /v1/models — OpenAI-compatible model listing.
     * Returns a single "zeroclaw" model so any OpenAI-compatible client can connect.
     */
    private fun handleOpenAIModels(out: java.io.OutputStream) {
        val model = JSONObject()
            .put("id", "zeroclaw")
            .put("object", "model")
            .put("created", System.currentTimeMillis() / 1000)
            .put("owned_by", "zeroclaw-android")

        val result = JSONObject()
            .put("object", "list")
            .put("data", org.json.JSONArray().put(model))

        sendJson(out, result)
    }

    /**
     * POST /v1/chat/completions — OpenAI-compatible chat completions endpoint.
     * Accepts standard OpenAI request format: {"model":"...", "messages":[...]}
     * Returns standard OpenAI response format so any app/tool that speaks OpenAI API
     * can use ZeroClaw as its AI backend.
     */
    private suspend fun handleOpenAIChatCompletions(out: java.io.OutputStream, body: String) {
        try {
            val json = JSONObject(body)
            val messages = json.optJSONArray("messages")
            if (messages == null || messages.length() == 0) {
                sendOpenAIError(out, "messages array is required", 400)
                return
            }

            // Extract the last user message as the prompt
            var userMessage = ""
            var sessionId = "openai_compat"
            for (i in messages.length() - 1 downTo 0) {
                val msg = messages.getJSONObject(i)
                if (msg.optString("role") == "user") {
                    userMessage = msg.optString("content", "")
                    break
                }
            }

            if (userMessage.isBlank()) {
                sendOpenAIError(out, "No user message found in messages array", 400)
                return
            }

            // Build conversation context from all messages for multi-turn
            val chatId = "openai_$sessionId"

            ZeroClawService.log("OpenAI-compat: ${userMessage.take(80)}...")
            val reply = llmRouter.call(userMessage, chatId = chatId)
            ZeroClawService.log("OpenAI-compat: reply sent (${reply.length} chars)")

            // Build OpenAI-compatible response
            val completionId = "chatcmpl-zc${System.currentTimeMillis()}"
            val choice = JSONObject()
                .put("index", 0)
                .put("message", JSONObject()
                    .put("role", "assistant")
                    .put("content", reply))
                .put("finish_reason", "stop")

            val usage = JSONObject()
                .put("prompt_tokens", userMessage.length / 4)  // approximate
                .put("completion_tokens", reply.length / 4)
                .put("total_tokens", (userMessage.length + reply.length) / 4)

            val result = JSONObject()
                .put("id", completionId)
                .put("object", "chat.completion")
                .put("created", System.currentTimeMillis() / 1000)
                .put("model", "zeroclaw")
                .put("choices", org.json.JSONArray().put(choice))
                .put("usage", usage)

            sendJson(out, result)
        } catch (e: Exception) {
            ZeroClawService.log("OpenAI-compat: error — ${e.message}")
            sendOpenAIError(out, e.message ?: "Unknown error", 500)
        }
    }

    // ── Agent Results API — Phase 175 ─────────────────────────────────────

    /**
     * GET /api/agents/results — list all results or filter by agent_id.
     *
     * Query params:
     *   ?agent_id=UUID   — filter by specific agent
     *   ?id=123          — get single result by ID
     *   ?limit=50        — max results (default 100)
     *   ?offset=0        — pagination offset
     */
    private suspend fun handleAgentResultsGet(out: java.io.OutputStream, requestLine: String) {
        try {
            val params = parseQueryParams(requestLine)
            val limit = params["limit"]?.toIntOrNull() ?: 100
            val offset = params["offset"]?.toIntOrNull() ?: 0

            // Single result by ID: /api/agents/results?id=123
            val resultId = params["id"]?.toLongOrNull()
            if (resultId != null) {
                val result = agentResultDao.getById(resultId)
                if (result != null) {
                    sendJson(out, resultToJson(result))
                } else {
                    sendApiError(out, "Result not found", 404)
                }
                return
            }

            // Filter by agent: /api/agents/results?agent_id=UUID
            val agentId = params["agent_id"]
            val results = if (agentId != null) {
                agentResultDao.getByAgentId(agentId, limit, offset)
            } else {
                agentResultDao.getAll(limit, offset)
            }

            val totalCount = if (agentId != null) {
                agentResultDao.countByAgent(agentId)
            } else {
                agentResultDao.count()
            }

            val arr = JSONArray()
            for (r in results) arr.put(resultToJson(r))

            sendJson(out, JSONObject()
                .put("results", arr)
                .put("total", totalCount)
                .put("limit", limit)
                .put("offset", offset)
            )
        } catch (e: Exception) {
            ZeroClawService.log("AgentResultsAPI: GET error — ${e.message}")
            sendApiError(out, e.message ?: "Unknown error", 500)
        }
    }

    /**
     * DELETE /api/agents/results — delete results.
     *
     * Query params:
     *   ?id=123          — delete single result
     *   ?agent_id=UUID   — delete all results for agent
     *   ?older_than=EPOCH — delete results older than timestamp (millis)
     */
    private suspend fun handleAgentResultsDelete(out: java.io.OutputStream, requestLine: String) {
        try {
            val params = parseQueryParams(requestLine)

            val resultId = params["id"]?.toLongOrNull()
            if (resultId != null) {
                agentResultDao.deleteById(resultId)
                sendJson(out, JSONObject().put("deleted", true).put("id", resultId))
                return
            }

            val agentId = params["agent_id"]
            if (agentId != null) {
                agentResultDao.deleteByAgentId(agentId)
                sendJson(out, JSONObject().put("deleted", true).put("agent_id", agentId))
                return
            }

            val olderThan = params["older_than"]?.toLongOrNull()
            if (olderThan != null) {
                agentResultDao.deleteOlderThan(olderThan)
                sendJson(out, JSONObject().put("deleted", true).put("older_than", olderThan))
                return
            }

            sendApiError(out, "Specify id, agent_id, or older_than param", 400)
        } catch (e: Exception) {
            ZeroClawService.log("AgentResultsAPI: DELETE error — ${e.message}")
            sendApiError(out, e.message ?: "Unknown error", 500)
        }
    }

    // ═══ Agent CRUD API — for autom remote management ═════════════════════════

    /** GET /api/agents/list — returns all agents as JSON */
    private fun handleAgentsList(out: java.io.OutputStream) {
        try {
            val mgr = AgentManager.getInstance(context)
            val agents = mgr.loadAll()
            val arr = JSONArray()
            for (a in agents) arr.put(agentToJson(a))
            sendJson(out, JSONObject().put("agents", arr).put("count", agents.size))
        } catch (e: Exception) {
            sendApiError(out, e.message ?: "Unknown error", 500)
        }
    }

    /**
     * POST /api/agents/create — creates a new agent.
     * Body: {name, url, intervalMinutes, channel, chatId, extractPrompt, onlyOnChange, templateId?, apiSource?, type?}
     *   type defaults to "web_scraper". Use "search_only" (BUG-43) to run web_search without a URL.
     */
    private fun handleAgentCreate(out: java.io.OutputStream, body: String) {
        try {
            val json = JSONObject(body)
            val name = json.optString("name").trim()
            val url = json.optString("url").trim()
            val intervalMinutes = json.optInt("interval_minutes", 60)
            val channel = json.optString("channel", "webhook").trim()
            val chatId = json.optString("chat_id", "").trim()
            val extractPrompt = json.optString("extract_prompt", "")
            val onlyOnChange = json.optBoolean("only_on_change", false)
            val templateId = json.optString("template_id").takeIf { it.isNotBlank() }
            val apiSource = json.optString("api_source").takeIf { it.isNotBlank() }
            val type = json.optString("type").trim().ifBlank { AgentManager.TYPE_WEB_SCRAPER }

            if (name.isBlank()) {
                sendApiError(out, "name is required", 400)
                return
            }
            // BUG-43: search_only agents don't fetch a URL — they just run web_search with extractPrompt.
            // For all other types, require url or api_source.
            if (type != AgentManager.TYPE_SEARCH_ONLY && url.isBlank() && apiSource == null) {
                sendApiError(out, "url or api_source is required", 400)
                return
            }
            if (type == AgentManager.TYPE_SEARCH_ONLY && extractPrompt.isBlank()) {
                sendApiError(out, "extract_prompt is required for search_only agents", 400)
                return
            }

            val agent = AgentManager.getInstance(context).createWebScraper(
                name = name,
                url = url,
                intervalMinutes = intervalMinutes,
                channel = channel,
                chatId = chatId,
                extractPrompt = extractPrompt,
                onlyOnChange = onlyOnChange,
                templateId = templateId,
                apiSource = apiSource,
                type = type,
            )

            ZeroClawService.log("AgentAPI: created agent '${agent.name}' (id=${agent.id}, interval=${agent.intervalMinutes}min)")
            sendJson(out, agentToJson(agent))
        } catch (e: Exception) {
            ZeroClawService.log("AgentAPI: create failed — ${e.message}")
            sendApiError(out, e.message ?: "Unknown error", 500)
        }
    }

    /**
     * POST /api/agents/update — updates an existing agent.
     * Body: {id, name?, url?, intervalMinutes?, extractPrompt?, onlyOnChange?, enabled?}
     */
    private fun handleAgentUpdate(out: java.io.OutputStream, body: String) {
        try {
            val json = JSONObject(body)
            val id = json.optString("id").trim()
            if (id.isBlank()) {
                sendApiError(out, "id is required", 400)
                return
            }
            val mgr = AgentManager.getInstance(context)
            val existing = mgr.loadAll().firstOrNull { it.id == id }
            if (existing == null) {
                sendApiError(out, "agent not found", 404)
                return
            }
            val updated = existing.copy(
                name = if (json.has("name")) json.getString("name") else existing.name,
                url = if (json.has("url")) json.getString("url") else existing.url,
                intervalMinutes = if (json.has("interval_minutes")) json.getInt("interval_minutes").coerceAtLeast(5) else existing.intervalMinutes,
                extractPrompt = if (json.has("extract_prompt")) json.getString("extract_prompt") else existing.extractPrompt,
                onlyOnChange = if (json.has("only_on_change")) json.getBoolean("only_on_change") else existing.onlyOnChange,
                enabled = if (json.has("enabled")) json.getBoolean("enabled") else existing.enabled,
                channel = if (json.has("channel")) json.getString("channel") else existing.channel,
                chatId = if (json.has("chat_id")) json.getString("chat_id") else existing.chatId,
            )
            mgr.save(updated)
            ZeroClawService.log("AgentAPI: updated agent '${updated.name}' (id=$id)")
            sendJson(out, agentToJson(updated))
        } catch (e: Exception) {
            sendApiError(out, e.message ?: "Unknown error", 500)
        }
    }

    /** DELETE /api/agents/delete?id=XXX — deletes an agent */
    private fun handleAgentDelete(out: java.io.OutputStream, requestLine: String) {
        try {
            val params = parseQueryParams(requestLine)
            val id = params["id"]
            if (id.isNullOrBlank()) {
                sendApiError(out, "id param required", 400)
                return
            }
            AgentManager.getInstance(context).delete(id)
            ZeroClawService.log("AgentAPI: deleted agent id=$id")
            sendJson(out, JSONObject().put("deleted", true).put("id", id))
        } catch (e: Exception) {
            sendApiError(out, e.message ?: "Unknown error", 500)
        }
    }

    /** POST /api/agents/toggle — toggles an agent's enabled state. Body: {id, enabled} */
    private fun handleAgentToggle(out: java.io.OutputStream, body: String) {
        try {
            val json = JSONObject(body)
            val id = json.optString("id").trim()
            val enabled = json.optBoolean("enabled", true)
            if (id.isBlank()) {
                sendApiError(out, "id is required", 400)
                return
            }
            AgentManager.getInstance(context).setEnabled(id, enabled)
            ZeroClawService.log("AgentAPI: toggled agent id=$id enabled=$enabled")
            sendJson(out, JSONObject().put("id", id).put("enabled", enabled))
        } catch (e: Exception) {
            sendApiError(out, e.message ?: "Unknown error", 500)
        }
    }

    private fun agentToJson(a: ai.zeroclaw.android.agents.AgentConfig): JSONObject = JSONObject()
        .put("id", a.id)
        .put("name", a.name)
        .put("type", a.type)
        .put("url", a.url)
        .put("interval_minutes", a.intervalMinutes)
        .put("channel", a.channel)
        .put("chat_id", a.chatId)
        .put("extract_prompt", a.extractPrompt)
        .put("only_on_change", a.onlyOnChange)
        .put("enabled", a.enabled)
        .put("created_at", a.createdAt)
        .put("last_run_at", a.lastRunAt)
        .put("last_status", a.lastStatus)
        .put("template_id", a.templateId ?: "")
        .put("api_source", a.apiSource ?: "")

    private fun resultToJson(r: AgentResultEntity): JSONObject = JSONObject()
        .put("id", r.id)
        .put("agent_id", r.agentId)
        .put("agent_name", r.agentName)
        .put("run_id", r.runId)
        .put("timestamp", r.timestamp)
        .put("status", r.status)
        .put("url", r.url)
        .put("used_api", r.usedApi)
        .put("raw_content", r.rawContent)
        .put("extracted_content", r.extractedContent)
        .put("delivered_to", try { JSONArray(r.deliveredTo) } catch (_: Exception) { JSONArray() })
        .put("error_message", r.errorMessage)
        .put("content_hash", r.contentHash)

    /**
     * Extract the user's actual topic from a full LLM prompt.
     * Prompts from autom look like: 'You are an anime expert. List exactly 10 anime titles for: "ACTUAL TOPIC". Rules: ...'
     * We want just "ACTUAL TOPIC".
     */
    private fun extractUserTopic(prompt: String): String {
        // Try to extract quoted topic after "for:" or "about:" or "titled:"
        val patterns = listOf(
            Regex("""(?:for|about|titled|topic)[:\s]+[""](.+?)[""]""", RegexOption.IGNORE_CASE),
            Regex("""[""]([^""]{10,})[""]"""),  // first long quoted string
            Regex("""List exactly \d+ .+ for: [""]?(.+?)[""]?\.?\s*Rules""", RegexOption.IGNORE_CASE),
        )
        for (pat in patterns) {
            val match = pat.find(prompt)
            if (match != null) return match.groupValues[1].trim().take(200)
        }
        // Fallback: if prompt starts with system instruction, take first line that looks like user content
        val lines = prompt.lines().map { it.trim() }.filter { it.isNotBlank() }
        // Skip lines that look like system instructions
        val userLine = lines.firstOrNull { line ->
            !line.startsWith("You are") && !line.startsWith("Rules:") &&
            !line.startsWith("Return") && !line.startsWith("Format:") &&
            !line.startsWith("{") && !line.startsWith("[") &&
            line.length > 5
        }
        return userLine?.take(200) ?: prompt.take(200)
    }

    private fun parseQueryParams(requestLine: String): Map<String, String> {
        val qIdx = requestLine.indexOf('?')
        if (qIdx < 0) return emptyMap()
        val spaceIdx = requestLine.indexOf(' ', qIdx)
        val queryString = if (spaceIdx > 0) requestLine.substring(qIdx + 1, spaceIdx) else requestLine.substring(qIdx + 1)
        return queryString.split("&").mapNotNull { param ->
            val eq = param.indexOf('=')
            if (eq > 0) {
                val key = URLDecoder.decode(param.substring(0, eq), "UTF-8")
                val value = URLDecoder.decode(param.substring(eq + 1), "UTF-8")
                key to value
            } else null
        }.toMap()
    }

    private fun sendApiError(out: java.io.OutputStream, message: String, code: Int) {
        val error = JSONObject().put("error", message)
        val bytes = error.toString().toByteArray()
        val response = "HTTP/1.1 $code Error\r\nContent-Type: application/json; charset=utf-8\r\nContent-Length: ${bytes.size}\r\nAccess-Control-Allow-Origin: *\r\nConnection: close\r\n\r\n"
        out.write(response.toByteArray())
        out.write(bytes)
    }

    private fun sendOpenAIError(out: java.io.OutputStream, message: String, code: Int) {
        val error = JSONObject()
            .put("error", JSONObject()
                .put("message", message)
                .put("type", "invalid_request_error")
                .put("code", code))
        val bytes = error.toString().toByteArray()
        val response = "HTTP/1.1 $code Error\r\nContent-Type: application/json; charset=utf-8\r\nContent-Length: ${bytes.size}\r\nAccess-Control-Allow-Origin: *\r\nConnection: close\r\n\r\n"
        out.write(response.toByteArray())
        out.write(bytes)
    }

    fun stop() {
        running = false
        runCatching { serverSocket?.close() }
        serverSocket = null
    }
}

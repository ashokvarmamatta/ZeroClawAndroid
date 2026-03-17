package ai.zeroclaw.android.webchat

import android.content.Context
import ai.zeroclaw.android.data.LlmRouter
import ai.zeroclaw.android.service.ZeroClawService
import kotlinx.coroutines.*
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
                launch { handleRequest(socket) }
            } catch (e: Exception) {
                if (running) ZeroClawService.log("WebChat: server error — ${e.message}")
            }
        }
    }

    private suspend fun handleRequest(socket: java.net.Socket) {
        try {
            val reader = BufferedReader(InputStreamReader(socket.getInputStream()))
            val out = socket.getOutputStream()

            val requestLine = reader.readLine() ?: return
            val headers = mutableMapOf<String, String>()
            var line: String?
            var contentLength = 0
            while (reader.readLine().also { line = it } != null) {
                if (line.isNullOrBlank()) break
                val colonIdx = line!!.indexOf(':')
                if (colonIdx > 0) {
                    val key = line!!.substring(0, colonIdx).trim().lowercase()
                    val value = line!!.substring(colonIdx + 1).trim()
                    headers[key] = value
                    if (key == "content-length") contentLength = value.toIntOrNull() ?: 0
                }
            }

            when {
                requestLine.startsWith("GET / ") || requestLine.startsWith("GET /chat") -> {
                    sendHtml(out, chatPage())
                }
                requestLine.startsWith("POST /api/chat") -> {
                    val bodyChars = CharArray(contentLength)
                    if (contentLength > 0) reader.read(bodyChars, 0, contentLength)
                    val body = String(bodyChars)
                    handleChatApi(out, body)
                }
                else -> {
                    val response = "HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\n\r\n"
                    out.write(response.toByteArray())
                }
            }

            out.flush()
            socket.close()
        } catch (e: Exception) {
            ZeroClawService.log("WebChat: request error — ${e.message}")
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

    private fun sendHtml(out: java.io.OutputStream, html: String) {
        val bytes = html.toByteArray()
        val response = "HTTP/1.1 200 OK\r\nContent-Type: text/html; charset=utf-8\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n"
        out.write(response.toByteArray())
        out.write(bytes)
    }

    private fun sendJson(out: java.io.OutputStream, json: JSONObject) {
        val bytes = json.toString().toByteArray()
        val response = "HTTP/1.1 200 OK\r\nContent-Type: application/json; charset=utf-8\r\nContent-Length: ${bytes.size}\r\nAccess-Control-Allow-Origin: *\r\nConnection: close\r\n\r\n"
        out.write(response.toByteArray())
        out.write(bytes)
    }

    private fun chatPage(): String = """
<!DOCTYPE html>
<html lang="en">
<head>
<meta charset="UTF-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>ZeroClaw AI Chat</title>
<style>
*{margin:0;padding:0;box-sizing:border-box}
body{font-family:-apple-system,BlinkMacSystemFont,'Segoe UI',sans-serif;background:#0d1117;color:#c9d1d9;height:100vh;display:flex;flex-direction:column}
header{background:#161b22;padding:16px 20px;border-bottom:1px solid #30363d;display:flex;align-items:center;gap:12px}
header h1{font-size:18px;color:#58a6ff}
header span{font-size:13px;color:#8b949e}
#messages{flex:1;overflow-y:auto;padding:16px;display:flex;flex-direction:column;gap:12px}
.msg{max-width:80%;padding:10px 14px;border-radius:12px;line-height:1.5;white-space:pre-wrap;word-wrap:break-word;font-size:14px}
.user{background:#1f6feb;color:#fff;align-self:flex-end;border-bottom-right-radius:4px}
.bot{background:#21262d;color:#c9d1d9;align-self:flex-start;border-bottom-left-radius:4px;border:1px solid #30363d}
.typing{color:#8b949e;font-style:italic;align-self:flex-start;padding:8px 14px}
#input-bar{background:#161b22;padding:12px 16px;border-top:1px solid #30363d;display:flex;gap:8px}
#msg-input{flex:1;padding:10px 14px;border-radius:8px;border:1px solid #30363d;background:#0d1117;color:#c9d1d9;font-size:14px;outline:none}
#msg-input:focus{border-color:#58a6ff}
#send-btn{padding:10px 20px;border-radius:8px;border:none;background:#238636;color:#fff;font-weight:600;cursor:pointer;font-size:14px}
#send-btn:hover{background:#2ea043}
#send-btn:disabled{background:#21262d;color:#484f58;cursor:not-allowed}
</style>
</head>
<body>
<header><h1>🦀 ZeroClaw AI</h1><span>Web Chat</span></header>
<div id="messages"></div>
<div id="input-bar">
<input id="msg-input" placeholder="Type a message..." autocomplete="off">
<button id="send-btn" onclick="send()">Send</button>
</div>
<script>
const sid='web_'+Math.random().toString(36).substr(2,8);
const msgs=document.getElementById('messages');
const inp=document.getElementById('msg-input');
const btn=document.getElementById('send-btn');
inp.addEventListener('keydown',e=>{if(e.key==='Enter'&&!e.shiftKey){e.preventDefault();send()}});
function addMsg(text,cls){const d=document.createElement('div');d.className='msg '+cls;d.textContent=text;msgs.appendChild(d);msgs.scrollTop=msgs.scrollHeight}
async function send(){
const text=inp.value.trim();if(!text)return;
inp.value='';addMsg(text,'user');btn.disabled=true;
const typing=document.createElement('div');typing.className='typing';typing.textContent='Thinking...';msgs.appendChild(typing);msgs.scrollTop=msgs.scrollHeight;
try{
const r=await fetch('/api/chat',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({message:text,session_id:sid})});
const j=await r.json();typing.remove();
addMsg(j.reply||j.error||'No response','bot');
}catch(e){typing.remove();addMsg('Error: '+e.message,'bot')}
btn.disabled=false;inp.focus()
}
addMsg('Welcome to ZeroClaw AI Chat! Type a message to get started.','bot');
inp.focus();
</script>
</body>
</html>
    """.trimIndent()

    fun stop() {
        running = false
        runCatching { serverSocket?.close() }
        serverSocket = null
    }
}

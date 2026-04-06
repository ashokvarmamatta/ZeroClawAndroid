package ai.zeroclaw.android.webchat

import android.content.Context
import ai.zeroclaw.android.agents.AgentManager
import ai.zeroclaw.android.data.AgentResultDatabase
import ai.zeroclaw.android.data.AgentResultEntity
import ai.zeroclaw.android.data.LlmRouter
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

            // Read body for POST requests
            val body = if (requestLine.startsWith("POST") && contentLength > 0) {
                val bodyChars = CharArray(contentLength)
                var totalRead = 0
                while (totalRead < contentLength) {
                    val n = reader.read(bodyChars, totalRead, contentLength - totalRead)
                    if (n <= 0) break
                    totalRead += n
                }
                String(bodyChars, 0, totalRead)
            } else ""

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
                            .put("/api/agents/results"))
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
                // Agent Results API — Phase 175
                requestLine.startsWith("GET /api/agents/results") -> {
                    handleAgentResultsGet(out, requestLine)
                }
                requestLine.startsWith("DELETE /api/agents/results") -> {
                    handleAgentResultsDelete(out, requestLine)
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

    /**
     * POST /api/generate — raw LLM generation, no agent pipeline.
     * Request:  {"prompt": "...", "session_id": "optional"}
     * Response: {"text": "raw LLM output"}
     */
    private suspend fun handleGenerateApi(out: java.io.OutputStream, body: String) {
        try {
            val json = JSONObject(body)
            val prompt = json.optString("prompt", json.optString("message", "")).trim()
            val jsonMode = json.optBoolean("json_mode", false)
            val maxTokens = json.optInt("max_tokens", 8192)
            val useTools = json.optBoolean("use_tools", false)

            if (prompt.isBlank()) {
                sendJson(out, JSONObject().put("error", "Empty prompt"))
                return
            }

            ZeroClawService.log("Generate: ${prompt.take(100)}… (json=$jsonMode, maxTokens=$maxTokens, tools=$useTools)")
            val text = if (useTools) {
                llmRouter.rawGenerateWithTools(prompt, jsonMode = jsonMode, maxTokens = maxTokens)
            } else {
                llmRouter.rawGenerate(prompt, jsonMode = jsonMode, maxTokens = maxTokens)
            }
            ZeroClawService.log("Generate: response sent (${text.length} chars)")

            sendJson(out, JSONObject().put("text", text))
        } catch (e: Exception) {
            ZeroClawService.log("Generate: error — ${e.message}")
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
</style>
</head>
<body>
<header><h1>ZeroClaw AI</h1><span>Android Agent</span></header>
<div class="tabs">
  <div class="tab active" onclick="switchTab('chat')">Chat</div>
  <div class="tab" onclick="switchTab('agents')">Agents</div>
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
  if(tab==='chat') inp.focus();
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

package ai.zeroclaw.android.service

import android.app.*
import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import ai.zeroclaw.android.MainActivity
import ai.zeroclaw.android.data.AppSettings
import ai.zeroclaw.android.data.LlmKeyManager
import ai.zeroclaw.android.data.LlmRouter
import ai.zeroclaw.android.data.OfflineModelManager
import ai.zeroclaw.android.telegram.TelegramBotManager
import ai.zeroclaw.android.whatsapp.TwilioWhatsAppManager
import ai.zeroclaw.android.discord.DiscordBotManager
import ai.zeroclaw.android.signal.SignalBridgeManager
import ai.zeroclaw.android.slack.SlackBotManager
import ai.zeroclaw.android.matrix.MatrixBotManager
import ai.zeroclaw.android.irc.IrcBotManager
import ai.zeroclaw.android.teams.TeamsBotManager
import ai.zeroclaw.android.twitch.TwitchBotManager
import ai.zeroclaw.android.line.LineBotManager
import ai.zeroclaw.android.webchat.WebChatServer
import ai.zeroclaw.android.tunnel.TunnelManager
import ai.zeroclaw.android.tools.CronTool
import ai.zeroclaw.android.tools.ToolSystem
import ai.zeroclaw.android.agents.AgentManager
import ai.zeroclaw.android.agents.WebScraperAgent
import kotlinx.coroutines.*

class ZeroClawService : Service() {

    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var wakeLock: PowerManager.WakeLock? = null
    private lateinit var telegramManager: TelegramBotManager
    private lateinit var whatsappManager: TwilioWhatsAppManager
    private lateinit var discordManager: DiscordBotManager
    private lateinit var signalManager: SignalBridgeManager
    private lateinit var slackManager: SlackBotManager
    private lateinit var matrixManager: MatrixBotManager
    private lateinit var ircManager: IrcBotManager
    private lateinit var teamsManager: TeamsBotManager
    private lateinit var twitchManager: TwitchBotManager
    private lateinit var lineManager: LineBotManager
    private lateinit var webChatServer: WebChatServer
    private lateinit var tunnelManager: TunnelManager

    companion object {
        const val ACTION_START = "ai.zeroclaw.START"
        const val ACTION_STOP  = "ai.zeroclaw.STOP"
        const val CHANNEL_ID   = "zeroclaw_service"
        const val NOTIF_ID     = 1001

        @Volatile var isRunning        = false
        @Volatile var tunnelUrl: String? = null
        @Volatile var telegramConnected = false
        @Volatile var whatsappConnected = false
        @Volatile var discordConnected  = false
        @Volatile var signalConnected   = false
        @Volatile var slackConnected    = false
        @Volatile var matrixConnected   = false
        @Volatile var ircConnected      = false
        @Volatile var teamsConnected    = false
        @Volatile var twitchConnected   = false
        @Volatile var lineConnected     = false
        @Volatile var webChatRunning    = false
        val recentLogs = ArrayDeque<String>(50)
        // Detailed per-conversation trace — cleared at start of each new user message
        val conversationLogs = ArrayDeque<String>(200)

        /** Route a quick-reply from the notification shade to the correct channel. */
        @Volatile var instance: ZeroClawService? = null

        suspend fun routeQuickReply(channel: String, chatId: String, text: String) {
            val svc = instance ?: run {
                log("QUICK_REPLY: service not running, cannot route reply")
                return
            }
            svc.routeReply(channel, chatId, text)
        }

        fun log(msg: String) {
            val entry = "[${timeStr()}] $msg"
            android.util.Log.i("ZeroClaw", msg)
            synchronized(recentLogs) {
                if (recentLogs.size >= 50) recentLogs.removeFirst()
                recentLogs.addLast(entry)
            }
        }

        /** Append a detailed trace entry for the current conversation operation. */
        fun logDetail(msg: String) {
            val entry = "[${timeStr()}] $msg"
            android.util.Log.d("ZeroClaw.Detail", msg)
            synchronized(conversationLogs) {
                if (conversationLogs.size >= 200) conversationLogs.removeFirst()
                conversationLogs.addLast(entry)
            }
        }

        /** Clear detailed log — called at start of each new user message. */
        fun clearDetailLog() {
            android.util.Log.d("ZeroClaw.Detail", "━━━ NEW CONVERSATION TURN — LOG CLEARED ━━━")
            synchronized(conversationLogs) { conversationLogs.clear() }
        }

        private fun timeStr(): String {
            val c = java.util.Calendar.getInstance()
            return "%02d:%02d:%02d".format(
                c.get(java.util.Calendar.HOUR_OF_DAY),
                c.get(java.util.Calendar.MINUTE),
                c.get(java.util.Calendar.SECOND)
            )
        }
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        createNotificationChannel()
        TelegramBotManager.restoreLastChatId(this)
        telegramManager = TelegramBotManager(this)
        whatsappManager = TwilioWhatsAppManager(this)
        discordManager  = DiscordBotManager(this)
        signalManager   = SignalBridgeManager(this)
        slackManager    = SlackBotManager(this)
        matrixManager   = MatrixBotManager(this)
        ircManager      = IrcBotManager(this)
        teamsManager    = TeamsBotManager(this)
        twitchManager   = TwitchBotManager(this)
        lineManager     = LineBotManager(this)
        webChatServer   = WebChatServer(this)
        tunnelManager   = TunnelManager(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> { stopSelf(); return START_NOT_STICKY }
            else        -> startAgent()
        }
        return START_STICKY
    }

    private fun startAgent() {
        if (isRunning) return
        isRunning = true
        acquireWakeLock()
        startForeground(NOTIF_ID, buildNotification("ZeroClaw starting..."))
        log("Service started")

        // Reset LLM key failover state — fresh start means all keys are retried
        LlmKeyManager.getInstance(this).resetFailures()

        val keyManager = LlmKeyManager.getInstance(this)
        val keyCount = keyManager.loadKeys().count { it.enabled }
        log("LLM: $keyCount key${if (keyCount != 1) "s" else ""} loaded — failover ready")

        // Auto-load offline model if an enabled offline key exists with a preferred model
        val offlineKey = keyManager.loadKeys().firstOrNull { it.enabled && it.safeProvider == "offline" && it.safePreferredModel.isNotBlank() }
        if (offlineKey != null) {
            serviceScope.launch {
                try {
                    val mgr = OfflineModelManager.getInstance(this@ZeroClawService)
                    val match = mgr.listAppModels().firstOrNull { m -> m.name == offlineKey.safePreferredModel }
                    if (match != null) {
                        mgr.loadModel(match.path)
                        log("Offline: model ${match.name} pre-loaded for background use")
                    }
                } catch (e: Exception) {
                    log("Offline: failed to pre-load model — ${e.message}")
                }
            }
        }

        serviceScope.launch {
            val settings = AppSettings(this@ZeroClawService).getAll()

            // Tunnel
            launch {
                tunnelManager.start { url ->
                    tunnelUrl = url
                    log("Tunnel: $url")
                    updateNotification("Live: $url")
                }
            }

            // Telegram — only needs bot token now; LlmRouter handles AI
            if (settings.telegramToken.isNotBlank()) {
                launch {
                    try {
                        telegramManager.startPolling(settings.telegramToken)
                        telegramConnected = true
                        log("Telegram connected")
                    } catch (e: Exception) {
                        log("Telegram error: ${e.message}")
                    }
                }
            } else {
                log("Telegram: no token set — go to Settings")
            }

            // WhatsApp
            if (settings.twilioSid.isNotBlank()) {
                launch {
                    try {
                        whatsappManager.startWebhookServer(settings, 8080)
                        whatsappConnected = true
                        log("WhatsApp webhook ready on :8080")
                    } catch (e: Exception) {
                        log("WhatsApp error: ${e.message}")
                    }
                }
            } else {
                log("WhatsApp: Twilio not configured — go to Settings")
            }

            // Discord
            if (settings.discordToken.isNotBlank()) {
                launch {
                    try {
                        discordManager.start(settings.discordToken)
                        discordConnected = true
                        log("Discord bot connected")
                    } catch (e: Exception) {
                        log("Discord error: ${e.message}")
                    }
                }
            } else {
                log("Discord: no token set — go to Settings")
            }

            // Signal
            if (settings.signalApiUrl.isNotBlank()) {
                launch {
                    try {
                        signalManager.start(settings.signalApiUrl)
                        signalConnected = true
                        log("Signal bridge connected")
                    } catch (e: Exception) {
                        log("Signal error: ${e.message}")
                    }
                }
            } else {
                log("Signal: no API URL set — go to Settings")
            }

            // Slack
            if (settings.slackToken.isNotBlank()) {
                launch {
                    try {
                        slackManager.start(settings.slackToken)
                        slackConnected = true
                        log("Slack bot connected")
                    } catch (e: Exception) {
                        log("Slack error: ${e.message}")
                    }
                }
            } else {
                log("Slack: no token set — go to Settings")
            }

            // Matrix
            if (settings.matrixConfig.isNotBlank()) {
                launch {
                    try {
                        matrixManager.start(settings.matrixConfig)
                        matrixConnected = true
                        log("Matrix bot connected")
                    } catch (e: Exception) {
                        log("Matrix error: ${e.message}")
                    }
                }
            } else {
                log("Matrix: not configured — go to Settings")
            }

            // IRC
            if (settings.ircConfig.isNotBlank()) {
                launch {
                    try {
                        ircManager.start(settings.ircConfig)
                        ircConnected = true
                        log("IRC bot connected")
                    } catch (e: Exception) {
                        log("IRC error: ${e.message}")
                    }
                }
            } else {
                log("IRC: not configured — go to Settings")
            }

            // Microsoft Teams
            if (settings.teamsConfig.isNotBlank()) {
                launch {
                    try {
                        teamsManager.start(settings.teamsConfig)
                        teamsConnected = true
                        log("Teams bot connected")
                    } catch (e: Exception) {
                        log("Teams error: ${e.message}")
                    }
                }
            } else {
                log("Teams: not configured — go to Settings")
            }

            // Twitch
            if (settings.twitchConfig.isNotBlank()) {
                launch {
                    try {
                        twitchManager.start(settings.twitchConfig)
                        twitchConnected = true
                        log("Twitch bot connected")
                    } catch (e: Exception) {
                        log("Twitch error: ${e.message}")
                    }
                }
            } else {
                log("Twitch: not configured — go to Settings")
            }

            // LINE
            if (settings.lineToken.isNotBlank()) {
                launch {
                    try {
                        lineManager.start(settings.lineToken)
                        lineConnected = true
                        log("LINE bot connected")
                    } catch (e: Exception) {
                        log("LINE error: ${e.message}")
                    }
                }
            } else {
                log("LINE: no token set — go to Settings")
            }

            // Web Chat — always start so external apps (e.g. AutomationVideoGen) can reach /api/chat
            launch {
                try {
                    webChatServer.start()
                    webChatRunning = true
                    log("WebChat: server started on :8088")
                } catch (e: Exception) {
                    log("WebChat error: ${e.message}")
                }
            }

            // Cron task checker — runs every 60 seconds
            launch {
                val cronTool = ToolSystem.getInstance(this@ZeroClawService)
                    .allTools().filterIsInstance<CronTool>().firstOrNull()
                if (cronTool != null) {
                    while (isActive) {
                        try {
                            val dueTasks = cronTool.getDueTasks()
                            for (task in dueTasks) {
                                log("CRON: running '${task.name}' for user ${task.userId}")
                                val reply = LlmRouter.getInstance(this@ZeroClawService)
                                    .call(task.prompt, chatId = task.userId)
                                log("CRON: '${task.name}' → ${reply.take(100)}")
                                cronTool.markRun(task)
                            }
                        } catch (e: Exception) {
                            log("CRON: error — ${e.message}")
                        }
                        delay(60_000L)
                    }
                }
            }

            // Agent runner — checks due agents every 60 seconds
            launch {
                val agentManager = AgentManager.getInstance(this@ZeroClawService)
                val scraper = WebScraperAgent(this@ZeroClawService)
                while (isActive) {
                    try {
                        val dueAgents = agentManager.getDueAgents()
                        for (agent in dueAgents) {
                            log("AGENT: running '${agent.name}' [${agent.type}]")
                            when (agent.type) {
                                AgentManager.TYPE_WEB_SCRAPER -> scraper.run(agent)
                                else -> log("AGENT: unknown type '${agent.type}' — skipping")
                            }
                        }
                    } catch (e: Exception) {
                        log("AGENT: error — ${e.message}")
                    }
                    delay(60_000L)
                }
            }
        }
    }

    override fun onDestroy() {
        instance         = null
        isRunning        = false
        telegramConnected = false
        whatsappConnected = false
        discordConnected  = false
        signalConnected   = false
        slackConnected    = false
        matrixConnected   = false
        ircConnected      = false
        teamsConnected    = false
        twitchConnected   = false
        lineConnected     = false
        webChatRunning    = false
        tunnelUrl         = null
        serviceScope.cancel()
        telegramManager.stop()
        discordManager.stop()
        signalManager.stop()
        slackManager.stop()
        matrixManager.stop()
        ircManager.stop()
        teamsManager.stop()
        twitchManager.stop()
        lineManager.stop()
        webChatServer.stop()
        tunnelManager.stop()
        // Release offline model to free memory
        try { OfflineModelManager.getInstance(this).destroyEngine() } catch (_: Exception) {}
        wakeLock?.release()
        log("Service stopped")
        super.onDestroy()
    }

    /**
     * Proactively send a message to a specific chat on a specific channel.
     * Used by MessageTool to deliver notifications from agents/crons.
     */
    suspend fun sendProactive(channel: String, chatId: String, text: String) {
        log("PROACTIVE: sending to $channel/$chatId — ${text.take(60)}")
        when (channel.lowercase()) {
            "telegram" -> {
                val settings = ai.zeroclaw.android.data.AppSettings(this).getAll()
                if (settings.telegramToken.isBlank()) {
                    log("PROACTIVE: Telegram token not configured")
                    return
                }
                telegramManager.sendProactiveMessage(settings.telegramToken, chatId, text)
            }
            "discord" -> discordManager.sendProactiveMessage(chatId, text)
            "slack" -> slackManager.sendProactiveMessage(chatId, text)
            "whatsapp" -> whatsappManager.sendProactiveMessage(chatId, text)
            "signal" -> signalManager.sendProactiveMessage(chatId, text)
            "matrix" -> matrixManager.sendProactiveMessage(chatId, text)
            "irc" -> ircManager.sendProactiveMessage(chatId, text)
            "teams" -> teamsManager.sendProactiveMessage(chatId, text)
            "twitch" -> twitchManager.sendProactiveMessage(chatId, text)
            "line" -> lineManager.sendProactiveMessage(chatId, text)
            else -> log("PROACTIVE: unknown channel $channel")
        }
    }

    /** Route a quick-reply text back through the originating channel manager. */
    suspend fun routeReply(channel: String, chatId: String, text: String) {
        // Process the quick-reply text as a new message through the agent pipeline.
        log("QUICK_REPLY: routing [$channel] chatId=$chatId text=${text.take(40)}")
        val llmRouter = ai.zeroclaw.android.data.LlmRouter.getInstance(this)
        llmRouter.call(text, chatId)
        log("QUICK_REPLY: processed [$channel] chatId=$chatId")
    }

    private fun acquireWakeLock() {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "ZeroClaw::WakeLock")
            .apply { acquire(24 * 60 * 60 * 1000L) }
    }

    private fun createNotificationChannel() {
        val chan = NotificationChannel(CHANNEL_ID, "ZeroClaw Agent",
            NotificationManager.IMPORTANCE_LOW).apply {
            description = "ZeroClaw AI agent is running"
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(chan)
    }

    private fun buildNotification(text: String): Notification {
        val pi = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("🦀 ZeroClaw")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentIntent(pi)
            .setOngoing(true)
            .build()
    }

    private fun updateNotification(text: String) {
        getSystemService(NotificationManager::class.java)
            .notify(NOTIF_ID, buildNotification(text))
    }

    override fun onBind(intent: Intent?): IBinder? = null
}

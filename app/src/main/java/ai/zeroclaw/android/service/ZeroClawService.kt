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
import ai.zeroclaw.android.telegram.TelegramBotManager
import ai.zeroclaw.android.whatsapp.TwilioWhatsAppManager
import ai.zeroclaw.android.tunnel.TunnelManager
import kotlinx.coroutines.*

class ZeroClawService : Service() {

    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var wakeLock: PowerManager.WakeLock? = null
    private lateinit var telegramManager: TelegramBotManager
    private lateinit var whatsappManager: TwilioWhatsAppManager
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
        val recentLogs = ArrayDeque<String>(50)

        fun log(msg: String) {
            val entry = "[${timeStr()}] $msg"
            synchronized(recentLogs) {
                if (recentLogs.size >= 50) recentLogs.removeFirst()
                recentLogs.addLast(entry)
            }
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
        createNotificationChannel()
        telegramManager = TelegramBotManager(this)
        whatsappManager = TwilioWhatsAppManager(this)
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
        }
    }

    override fun onDestroy() {
        isRunning        = false
        telegramConnected = false
        whatsappConnected = false
        tunnelUrl         = null
        serviceScope.cancel()
        telegramManager.stop()
        tunnelManager.stop()
        wakeLock?.release()
        log("Service stopped")
        super.onDestroy()
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

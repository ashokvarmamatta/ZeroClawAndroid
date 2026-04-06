package ai.zeroclaw.android.service

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.view.View
import android.widget.RemoteViews
import ai.zeroclaw.android.MainActivity
import ai.zeroclaw.android.R
import ai.zeroclaw.android.agents.AgentManager

/**
 * HomeWidget — Super Widget for ZeroClaw Android
 *
 * Shows at a glance:
 * - Server ON/OFF toggle with live status
 * - Activity state (idle / processing / agent running / sending)
 * - Connected bots as emoji icons
 * - Agent count + last agent result
 * - Tunnel URL (if connected)
 * - Quick action buttons: Open Agents, Open Chat
 * - Last activity summary
 * - Messages handled counter
 *
 * Widget updates triggered by:
 * - AppWidgetManager on add/update cycles (every 10 min)
 * - ZeroClawService when status changes
 * - Agent runs completing
 * - Boot (BOOT_COMPLETED broadcast)
 */
class HomeWidget : AppWidgetProvider() {

    companion object {
        private const val BOT_TOTAL = 11  // Total possible bot channels
        val ACTION_WIDGET_START      get() = "${ai.zeroclaw.android.BuildConfig.APPLICATION_ID}.WIDGET_START"
        val ACTION_WIDGET_STOP       get() = "${ai.zeroclaw.android.BuildConfig.APPLICATION_ID}.WIDGET_STOP"
        val ACTION_WIDGET_OPEN_AGENTS get() = "${ai.zeroclaw.android.BuildConfig.APPLICATION_ID}.WIDGET_OPEN_AGENTS"
        val ACTION_WIDGET_OPEN_CHAT  get() = "${ai.zeroclaw.android.BuildConfig.APPLICATION_ID}.WIDGET_OPEN_CHAT"

        fun broadcastUpdate(context: Context) {
            val intent = Intent(context, HomeWidget::class.java).apply {
                action = AppWidgetManager.ACTION_APPWIDGET_UPDATE
                val ids = AppWidgetManager.getInstance(context)
                    .getAppWidgetIds(
                        android.content.ComponentName(context, HomeWidget::class.java)
                    )
                putExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS, ids)
            }
            context.sendBroadcast(intent)
        }
    }

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray
    ) {
        for (widgetId in appWidgetIds) {
            updateWidget(context, appWidgetManager, widgetId)
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        when (intent.action) {
            ACTION_WIDGET_START -> {
                val svcIntent = Intent(context, ZeroClawService::class.java).apply {
                    action = ZeroClawService.ACTION_START
                }
                context.startForegroundService(svcIntent)
                broadcastUpdate(context)
            }
            ACTION_WIDGET_STOP -> {
                val svcIntent = Intent(context, ZeroClawService::class.java).apply {
                    action = ZeroClawService.ACTION_STOP
                }
                context.startService(svcIntent)
                broadcastUpdate(context)
            }
            ACTION_WIDGET_OPEN_AGENTS -> {
                val openIntent = Intent(context, MainActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                    putExtra("navigate_to", "agents")
                }
                context.startActivity(openIntent)
            }
            ACTION_WIDGET_OPEN_CHAT -> {
                val openIntent = Intent(context, MainActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                    putExtra("navigate_to", "home")
                }
                context.startActivity(openIntent)
            }
        }
    }

    private fun updateWidget(
        context: Context,
        appWidgetManager: AppWidgetManager,
        widgetId: Int
    ) {
        val isRunning = ZeroClawService.isRunning
        val views = RemoteViews(context.packageName, R.layout.home_widget)

        // ═══ Title ═══
        views.setTextViewText(R.id.widget_title, if (isRunning) "🦀 ZeroClaw" else "🦀 ZeroClaw")

        // ═══ Toggle button ═══
        if (isRunning) {
            views.setTextViewText(R.id.widget_toggle_btn, "⏹ Stop")
            views.setInt(R.id.widget_toggle_btn, "setBackgroundResource", R.drawable.widget_btn_stop)
        } else {
            views.setTextViewText(R.id.widget_toggle_btn, "▶ Start")
            views.setInt(R.id.widget_toggle_btn, "setBackgroundResource", R.drawable.widget_btn_start)
        }
        val toggleAction = if (isRunning) ACTION_WIDGET_STOP else ACTION_WIDGET_START
        val toggleIntent = Intent(context, HomeWidget::class.java).apply { action = toggleAction }
        val togglePending = PendingIntent.getBroadcast(
            context, widgetId, toggleIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        views.setOnClickPendingIntent(R.id.widget_toggle_btn, togglePending)

        // ═══ Status pill — live activity state ═══
        if (isRunning) {
            val (statusEmoji, statusText, statusColor) = when (ZeroClawService.activityState) {
                "processing" -> Triple("🧠", "Processing", "#FFB74D")
                "sending"    -> Triple("📤", "Sending", "#64B5F6")
                "fetching"   -> Triple("🌐", "Fetching", "#81C784")
                "agent_run"  -> {
                    val detail = ZeroClawService.lastActivityDetail
                    Triple("🕷️", if (detail.isNotBlank()) "Agent: $detail" else "Agent running", "#CE93D8")
                }
                else         -> Triple("⚡", "Idle", "#00D4AA")
            }
            views.setTextViewText(R.id.widget_status_pill, "$statusEmoji $statusText")
            views.setTextColor(R.id.widget_status_pill, android.graphics.Color.parseColor(statusColor))

            // Messages counter
            val msgs = ZeroClawService.totalMessagesHandled
            views.setTextViewText(R.id.widget_uptime,
                if (msgs > 0) "$msgs msgs handled" else "")
        } else {
            views.setTextViewText(R.id.widget_status_pill, "🔴 Stopped")
            views.setTextColor(R.id.widget_status_pill, android.graphics.Color.parseColor("#E53935"))
            views.setTextViewText(R.id.widget_uptime, "")
        }

        // ═══ Connected bots row ═══
        val bots = buildConnectedBotsList()
        if (isRunning && bots.isNotEmpty()) {
            val icons = bots.joinToString(" ") { it.first }
            val count = bots.size
            views.setTextViewText(R.id.widget_bots_icons, icons)
            views.setTextViewText(R.id.widget_bots_count, "$count/${BOT_TOTAL}")
            views.setViewVisibility(R.id.widget_bots_row, View.VISIBLE)
        } else if (isRunning) {
            views.setTextViewText(R.id.widget_bots_icons, "No bots connected")
            views.setTextColor(R.id.widget_bots_icons, android.graphics.Color.parseColor("#666666"))
            views.setTextViewText(R.id.widget_bots_count, "0/${BOT_TOTAL}")
            views.setViewVisibility(R.id.widget_bots_row, View.VISIBLE)
        } else {
            views.setViewVisibility(R.id.widget_bots_row, View.GONE)
        }

        // ═══ Agent stats + Tunnel ═══
        if (isRunning) {
            val agentCount = try {
                AgentManager.getInstance(context).loadAll().count { it.enabled }
            } catch (_: Exception) { 0 }
            ZeroClawService.activeAgentCount = agentCount
            val agentText = when {
                agentCount == 0 -> "🕷️ No agents"
                agentCount == 1 -> "🕷️ 1 agent active"
                else -> "🕷️ $agentCount agents active"
            }
            views.setTextViewText(R.id.widget_agents_info, agentText)

            // Tunnel URL
            val tunnelUrl = ZeroClawService.tunnelUrl
            if (tunnelUrl != null && !tunnelUrl.contains("192.168.") && !tunnelUrl.contains("localhost")) {
                val shortUrl = tunnelUrl
                    .removePrefix("https://")
                    .removePrefix("http://")
                    .removeSuffix("/")
                views.setTextViewText(R.id.widget_tunnel_info, "🌐 $shortUrl")
                views.setViewVisibility(R.id.widget_tunnel_info, View.VISIBLE)
            } else {
                views.setViewVisibility(R.id.widget_tunnel_info, View.GONE)
            }
        } else {
            views.setTextViewText(R.id.widget_agents_info, "🕷️ — agents")
            views.setViewVisibility(R.id.widget_tunnel_info, View.GONE)
        }

        // ═══ Last activity ═══
        if (isRunning) {
            val lastAgent = ZeroClawService.lastAgentRun
            val lastLog = synchronized(ZeroClawService.recentLogs) {
                ZeroClawService.recentLogs.lastOrNull()
            }
            val activityText = when {
                lastAgent.isNotBlank() -> "Last agent: $lastAgent"
                lastLog != null -> lastLog.removePrefix("[").take(60)
                else -> ""
            }
            views.setTextViewText(R.id.widget_last_activity, activityText)
            views.setViewVisibility(R.id.widget_last_activity, if (activityText.isNotBlank()) View.VISIBLE else View.GONE)
        } else {
            views.setViewVisibility(R.id.widget_last_activity, View.GONE)
        }

        // ═══ Quick action buttons ═══
        // Agents button
        val agentsIntent = Intent(context, HomeWidget::class.java).apply { action = ACTION_WIDGET_OPEN_AGENTS }
        val agentsPending = PendingIntent.getBroadcast(
            context, widgetId + 1000, agentsIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        views.setOnClickPendingIntent(R.id.widget_btn_agents, agentsPending)

        // Chat button
        val chatIntent = Intent(context, HomeWidget::class.java).apply { action = ACTION_WIDGET_OPEN_CHAT }
        val chatPending = PendingIntent.getBroadcast(
            context, widgetId + 2000, chatIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        views.setOnClickPendingIntent(R.id.widget_btn_chat, chatPending)

        // ═══ Tap root to open app ═══
        val openIntent = PendingIntent.getActivity(
            context, 0,
            Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        views.setOnClickPendingIntent(R.id.widget_root, openIntent)

        appWidgetManager.updateAppWidget(widgetId, views)
    }

    /** Build list of connected bots as (emoji, name) pairs */
    private fun buildConnectedBotsList(): List<Pair<String, String>> {
        val bots = mutableListOf<Pair<String, String>>()
        if (ZeroClawService.telegramConnected)  bots.add("✈️" to "Telegram")
        if (ZeroClawService.discordConnected)   bots.add("🎮" to "Discord")
        if (ZeroClawService.slackConnected)     bots.add("💼" to "Slack")
        if (ZeroClawService.whatsappConnected)  bots.add("💬" to "WhatsApp")
        if (ZeroClawService.signalConnected)    bots.add("🔒" to "Signal")
        if (ZeroClawService.matrixConnected)    bots.add("🟢" to "Matrix")
        if (ZeroClawService.ircConnected)       bots.add("📡" to "IRC")
        if (ZeroClawService.teamsConnected)     bots.add("🟦" to "Teams")
        if (ZeroClawService.twitchConnected)    bots.add("🟣" to "Twitch")
        if (ZeroClawService.lineConnected)      bots.add("🟩" to "LINE")
        if (ZeroClawService.webChatRunning)     bots.add("🌐" to "WebChat")
        return bots
    }

}

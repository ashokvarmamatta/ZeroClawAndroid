package ai.zeroclaw.android.service

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import ai.zeroclaw.android.MainActivity
import ai.zeroclaw.android.R

/**
 * HomeWidget — Phase 138: Android home screen widget showing service status + quick start.
 *
 * Displays:
 * - Service running/stopped status with colored indicator
 * - Number of active connections
 * - Quick Start / Stop button
 * - Tap anywhere to open the app
 *
 * Widget updates are triggered:
 * - By AppWidgetManager on add/update cycles
 * - By ZeroClawService when status changes (via broadcastWidgetUpdate)
 * - On boot (BOOT_COMPLETED broadcast)
 */
class HomeWidget : AppWidgetProvider() {

    companion object {
        const val ACTION_WIDGET_START = "ai.zeroclaw.WIDGET_START"
        const val ACTION_WIDGET_STOP  = "ai.zeroclaw.WIDGET_STOP"

        /**
         * Push an update to all instances of the widget.
         * Call from ZeroClawService whenever connection status changes.
         */
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
        }
    }

    private fun updateWidget(
        context: Context,
        appWidgetManager: AppWidgetManager,
        widgetId: Int
    ) {
        val isRunning = ZeroClawService.isRunning
        val connections = countConnections()

        val views = RemoteViews(context.packageName, R.layout.home_widget)

        // Status text
        val statusText = if (isRunning) "🟢 Running" else "🔴 Stopped"
        val connText = if (isRunning && connections > 0) "$connections connected" else ""
        views.setTextViewText(R.id.widget_status, statusText)
        views.setTextViewText(R.id.widget_connections, connText)

        // Toggle button
        val toggleText = if (isRunning) "Stop" else "Start"
        views.setTextViewText(R.id.widget_toggle_btn, toggleText)

        // Toggle action
        val toggleAction = if (isRunning) ACTION_WIDGET_STOP else ACTION_WIDGET_START
        val toggleIntent = Intent(context, HomeWidget::class.java).apply { action = toggleAction }
        val togglePending = PendingIntent.getBroadcast(
            context, widgetId, toggleIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        views.setOnClickPendingIntent(R.id.widget_toggle_btn, togglePending)

        // Tap to open app
        val openIntent = PendingIntent.getActivity(
            context, 0,
            Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        views.setOnClickPendingIntent(R.id.widget_root, openIntent)

        appWidgetManager.updateAppWidget(widgetId, views)
    }

    private fun countConnections(): Int {
        var count = 0
        if (ZeroClawService.telegramConnected) count++
        if (ZeroClawService.whatsappConnected) count++
        if (ZeroClawService.discordConnected)  count++
        if (ZeroClawService.slackConnected)    count++
        if (ZeroClawService.matrixConnected)   count++
        if (ZeroClawService.ircConnected)      count++
        if (ZeroClawService.teamsConnected)    count++
        if (ZeroClawService.twitchConnected)   count++
        if (ZeroClawService.lineConnected)     count++
        if (ZeroClawService.webChatRunning)    count++
        return count
    }
}

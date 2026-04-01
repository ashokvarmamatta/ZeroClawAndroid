package ai.zeroclaw.android.infra

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.core.app.NotificationCompat
import androidx.core.app.RemoteInput
import ai.zeroclaw.android.MainActivity
import ai.zeroclaw.android.service.ZeroClawService

/**
 * RichNotifications — Phase 127: Per-channel push notifications with quick reply.
 *
 * Sends rich Android notifications for incoming messages across all channels.
 * Features:
 * - Per-channel notification channels with distinct sounds/vibration
 * - Quick Reply inline action (user can reply without opening app)
 * - Message grouping by channel (bundled notifications)
 * - Big text style for long messages
 * - Notification counter per channel
 */
class RichNotifications(private val context: Context) {

    companion object {
        const val CHANNEL_TELEGRAM = "notif_telegram"
        const val CHANNEL_DISCORD  = "notif_discord"
        const val CHANNEL_SLACK    = "notif_slack"
        const val CHANNEL_WHATSAPP = "notif_whatsapp"
        const val CHANNEL_GENERAL  = "notif_general"

        const val KEY_QUICK_REPLY = "quick_reply_text"
        val ACTION_QUICK_REPLY get() = "${ai.zeroclaw.android.BuildConfig.APPLICATION_ID}.QUICK_REPLY"

        private var notifIdCounter = 2000

        @Volatile private var INSTANCE: RichNotifications? = null
        fun getInstance(context: Context): RichNotifications {
            return INSTANCE ?: synchronized(this) {
                RichNotifications(context.applicationContext).also { INSTANCE = it }
            }
        }
    }

    private val notifManager = context.getSystemService(NotificationManager::class.java)

    init {
        createChannels()
    }

    /**
     * Show a message notification for an incoming chat message.
     */
    fun showMessageNotification(
        channel: String,
        from: String,
        message: String,
        chatId: String,
        isGroup: Boolean = false
    ) {
        val notifChannelId = channelIdFor(channel)
        val notifId = notifIdCounter++

        // Quick Reply action
        val remoteInput = RemoteInput.Builder(KEY_QUICK_REPLY)
            .setLabel("Reply to $from")
            .build()

        val replyIntent = Intent(context, QuickReplyReceiver::class.java).apply {
            action = ACTION_QUICK_REPLY
            putExtra("chat_id", chatId)
            putExtra("channel", channel)
            putExtra("notif_id", notifId)
        }
        val replyPendingIntent = PendingIntent.getBroadcast(
            context, notifId,
            replyIntent,
            PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val replyAction = NotificationCompat.Action.Builder(
            android.R.drawable.ic_menu_send,
            "Reply",
            replyPendingIntent
        ).addRemoteInput(remoteInput).build()

        // Open app intent
        val openIntent = PendingIntent.getActivity(
            context, 0,
            Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val emoji = channelEmoji(channel)
        val title = if (isGroup) "$emoji $from" else "$emoji Message from $from"

        val notif = NotificationCompat.Builder(context, notifChannelId)
            .setContentTitle(title)
            .setContentText(message.take(100))
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setStyle(NotificationCompat.BigTextStyle().bigText(message.take(500)))
            .setContentIntent(openIntent)
            .addAction(replyAction)
            .setAutoCancel(true)
            .setGroup("zeroclaw_$channel")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()

        notifManager.notify(notifId, notif)
        ZeroClawService.log("NOTIF: [$channel] message from $from")
    }

    /**
     * Show a summary notification for bundled messages.
     */
    fun showSummaryNotification(channel: String, count: Int) {
        val notifChannelId = channelIdFor(channel)
        val emoji = channelEmoji(channel)

        val summary = NotificationCompat.Builder(context, notifChannelId)
            .setContentTitle("$emoji $count new ${channel.replaceFirstChar { it.uppercase() }} messages")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setGroup("zeroclaw_$channel")
            .setGroupSummary(true)
            .setAutoCancel(true)
            .build()

        notifManager.notify(channel.hashCode(), summary)
    }

    /**
     * Cancel a notification after quick reply was sent.
     */
    fun cancelNotification(notifId: Int) {
        notifManager.cancel(notifId)
    }

    /**
     * Cancel all ZeroClaw notifications.
     */
    fun cancelAll() {
        notifManager.cancelAll()
    }

    private fun createChannels() {
        val channels = listOf(
            Triple(CHANNEL_TELEGRAM, "✈️ Telegram Messages", NotificationManager.IMPORTANCE_HIGH),
            Triple(CHANNEL_DISCORD,  "🎮 Discord Messages", NotificationManager.IMPORTANCE_HIGH),
            Triple(CHANNEL_SLACK,    "💼 Slack Messages",   NotificationManager.IMPORTANCE_DEFAULT),
            Triple(CHANNEL_WHATSAPP, "💬 WhatsApp Messages", NotificationManager.IMPORTANCE_HIGH),
            Triple(CHANNEL_GENERAL,  "🤖 ZeroClaw Messages", NotificationManager.IMPORTANCE_DEFAULT)
        )
        for ((id, name, importance) in channels) {
            val chan = NotificationChannel(id, name, importance).apply {
                description = "ZeroClaw incoming messages from $name"
                enableVibration(true)
            }
            notifManager.createNotificationChannel(chan)
        }
    }

    private fun channelIdFor(channel: String) = when (channel.lowercase()) {
        "telegram" -> CHANNEL_TELEGRAM
        "discord"  -> CHANNEL_DISCORD
        "slack"    -> CHANNEL_SLACK
        "whatsapp", "twilio" -> CHANNEL_WHATSAPP
        else       -> CHANNEL_GENERAL
    }

    private fun channelEmoji(channel: String) = when (channel.lowercase()) {
        "telegram" -> "✈️"
        "discord"  -> "🎮"
        "slack"    -> "💼"
        "whatsapp" -> "💬"
        "matrix"   -> "🟣"
        "irc"      -> "📡"
        "teams"    -> "💼"
        "twitch"   -> "🎮"
        "line"     -> "💚"
        "webchat"  -> "🌐"
        else       -> "🤖"
    }
}

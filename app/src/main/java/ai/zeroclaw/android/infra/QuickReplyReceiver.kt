package ai.zeroclaw.android.infra

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.RemoteInput
import ai.zeroclaw.android.service.ZeroClawService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * QuickReplyReceiver — handles inline quick reply from RichNotifications.
 *
 * When the user types a reply in the notification shade and taps Send,
 * Android delivers the text via a BroadcastReceiver. This class extracts
 * the reply text and routes it back through the messaging channel.
 */
class QuickReplyReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != RichNotifications.ACTION_QUICK_REPLY) return

        val chatId  = intent.getStringExtra("chat_id") ?: return
        val channel = intent.getStringExtra("channel") ?: return
        val notifId = intent.getIntExtra("notif_id", -1)

        // Extract the typed reply text from the RemoteInput bundle
        val bundle  = RemoteInput.getResultsFromIntent(intent) ?: return
        val replyText = bundle.getCharSequence(RichNotifications.KEY_QUICK_REPLY)
            ?.toString()?.trim() ?: return

        if (replyText.isBlank()) return

        ZeroClawService.log("QUICK_REPLY: [$channel] chatId=$chatId → \"${replyText.take(60)}\"")

        // Dismiss the notification
        if (notifId != -1) {
            RichNotifications.getInstance(context).cancelNotification(notifId)
        }

        // Route reply through the correct channel manager on the service scope
        CoroutineScope(Dispatchers.IO).launch {
            try {
                ZeroClawService.routeQuickReply(channel, chatId, replyText)
            } catch (e: Exception) {
                ZeroClawService.log("QUICK_REPLY: failed to route — ${e.message}")
            }
        }
    }
}

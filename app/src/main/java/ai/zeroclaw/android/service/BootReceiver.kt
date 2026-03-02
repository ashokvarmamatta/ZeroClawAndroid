package ai.zeroclaw.android.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED ||
            intent.action == Intent.ACTION_MY_PACKAGE_REPLACED) {
            // Check if auto-start is enabled in settings before launching
            val prefs = context.getSharedPreferences("zeroclaw_prefs", Context.MODE_PRIVATE)
            val autoStart = prefs.getBoolean("auto_start", true)
            if (autoStart) {
                val serviceIntent = Intent(context, ZeroClawService::class.java).apply {
                    action = ZeroClawService.ACTION_START
                }
                context.startForegroundService(serviceIntent)
            }
        }
    }
}

package ai.zeroclaw.android.infra

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import ai.zeroclaw.android.service.ZeroClawService
import android.app.ActivityManager
import android.content.Intent
import java.util.concurrent.TimeUnit

/**
 * AutoRecovery — Phase 130: WorkManager watchdog to restart service on crash.
 *
 * Schedules a periodic WorkManager job that checks every 15 minutes whether
 * ZeroClawService is still running. If not, it restarts the service.
 *
 * This handles cases where:
 * - The OS kills the service under memory pressure
 * - The service crashes with an unhandled exception
 * - Aggressive OEM battery killers stop background processes
 *
 * WorkManager is resistant to battery optimization because it uses
 * JobScheduler/AlarmManager internally and respects Doze mode correctly.
 */
object AutoRecovery {

    private const val WORK_TAG = "zeroclaw_watchdog"
    private const val CHECK_INTERVAL_MINUTES = 15L

    /**
     * Schedule the watchdog. Call from Application.onCreate() or Service.onCreate().
     * Idempotent — safe to call multiple times.
     */
    fun schedule(context: Context) {
        val request = PeriodicWorkRequestBuilder<WatchdogWorker>(
            CHECK_INTERVAL_MINUTES, TimeUnit.MINUTES
        )
            .addTag(WORK_TAG)
            .build()

        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            WORK_TAG,
            ExistingPeriodicWorkPolicy.KEEP,
            request
        )
        ZeroClawService.log("WATCHDOG: scheduled periodic check every ${CHECK_INTERVAL_MINUTES}m")
    }

    /**
     * Cancel the watchdog (call when user explicitly stops the service permanently).
     */
    fun cancel(context: Context) {
        WorkManager.getInstance(context).cancelAllWorkByTag(WORK_TAG)
        ZeroClawService.log("WATCHDOG: cancelled")
    }

    /**
     * Check if ZeroClawService is currently running.
     */
    fun isServiceRunning(context: Context): Boolean {
        val manager = context.getSystemService(ActivityManager::class.java)
        @Suppress("DEPRECATION")
        return manager.getRunningServices(50).any {
            it.service.className == ZeroClawService::class.java.name
        }
    }

    // ── WorkManager Worker ────────────────────────────────────────────────────

    class WatchdogWorker(
        private val context: Context,
        params: WorkerParameters
    ) : Worker(context, params) {

        override fun doWork(): Result {
            return try {
                if (!isServiceRunning(context)) {
                    ZeroClawService.log("WATCHDOG: service not running — attempting restart")
                    restartService()
                } else {
                    ZeroClawService.log("WATCHDOG: service alive ✓")
                }
                Result.success()
            } catch (e: Exception) {
                ZeroClawService.log("WATCHDOG: error — ${e.message}")
                Result.retry()
            }
        }

        private fun restartService() {
            val intent = Intent(context, ZeroClawService::class.java)
            try {
                context.startForegroundService(intent)
                ZeroClawService.log("WATCHDOG: service restart issued")
            } catch (e: Exception) {
                ZeroClawService.log("WATCHDOG: restart failed — ${e.message}")
            }
        }
    }
}

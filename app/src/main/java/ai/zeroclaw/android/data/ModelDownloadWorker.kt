package ai.zeroclaw.android.data

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.*
import ai.zeroclaw.android.R
import ai.zeroclaw.android.service.ZeroClawService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * Phase 179: WorkManager worker that downloads model files from HuggingFace.
 * Supports resume (HTTP Range), progress reporting, and foreground notification.
 * Inspired by Google AI Edge Gallery's DownloadWorker.
 */
class ModelDownloadWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    companion object {
        const val KEY_MODEL_ID = "model_id"
        const val KEY_DOWNLOAD_URL = "download_url"
        const val KEY_FILE_NAME = "file_name"
        const val KEY_TOTAL_BYTES = "total_bytes"

        // Progress keys
        const val KEY_PROGRESS = "progress"
        const val KEY_RECEIVED_BYTES = "received_bytes"
        const val KEY_SPEED = "speed_bytes_per_sec"

        private const val NOTIFICATION_CHANNEL = "model_download"
        private const val NOTIFICATION_ID = 9090
        private const val TMP_EXT = ".zctmp"
        private const val PROGRESS_INTERVAL_MS = 300L

        fun enqueue(context: Context, model: CatalogModel): java.util.UUID {
            val mgr = OfflineModelManager.getInstance(context)
            val destDir = File(context.filesDir, "offline_models")
            if (!destDir.exists()) destDir.mkdirs()

            val data = Data.Builder()
                .putString(KEY_MODEL_ID, model.id)
                .putString(KEY_DOWNLOAD_URL, model.downloadUrl)
                .putString(KEY_FILE_NAME, model.fileName)
                .putLong(KEY_TOTAL_BYTES, model.sizeBytes)
                .build()

            val request = OneTimeWorkRequestBuilder<ModelDownloadWorker>()
                .setInputData(data)
                .addTag("model_download:${model.id}")
                .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                .build()

            WorkManager.getInstance(context)
                .enqueueUniqueWork("download_${model.id}", ExistingWorkPolicy.REPLACE, request)

            return request.id
        }

        fun cancel(context: Context, modelId: String) {
            WorkManager.getInstance(context).cancelUniqueWork("download_$modelId")
        }
    }

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val downloadUrl = inputData.getString(KEY_DOWNLOAD_URL) ?: return@withContext Result.failure()
        val fileName = inputData.getString(KEY_FILE_NAME) ?: return@withContext Result.failure()
        val modelId = inputData.getString(KEY_MODEL_ID) ?: ""
        val totalBytes = inputData.getLong(KEY_TOTAL_BYTES, 0L)

        val destDir = File(applicationContext.filesDir, "offline_models")
        if (!destDir.exists()) destDir.mkdirs()

        val tmpFile = File(destDir, "$fileName$TMP_EXT")
        val finalFile = File(destDir, fileName)

        // If already downloaded, skip
        if (finalFile.exists() && finalFile.length() > 0) {
            ZeroClawService.log("ModelDownload: $fileName already exists, skipping")
            return@withContext Result.success()
        }

        createNotificationChannel()
        showNotification("Downloading $modelId...", 0)

        val maxRetries = 5
        var attempt = 0

        while (attempt < maxRetries) {
            attempt++
            try {
                // Resume support — check how much we already have
                var startByte = if (tmpFile.exists()) tmpFile.length() else 0L
                ZeroClawService.log("ModelDownload: attempt $attempt, $fileName from byte $startByte")

                val conn = URL(downloadUrl).openConnection() as HttpURLConnection
                conn.connectTimeout = 30_000
                conn.readTimeout = 60_000
                conn.setRequestProperty("User-Agent", "ZeroClaw-Android/1.1")
                if (startByte > 0) {
                    conn.setRequestProperty("Range", "bytes=$startByte-")
                }
                conn.connect()

                val responseCode = conn.responseCode
                if (responseCode !in listOf(200, 206)) {
                    val msg = "HTTP $responseCode: ${conn.responseMessage}"
                    ZeroClawService.log("ModelDownload: failed - $msg")
                    conn.disconnect()
                    // HTTP errors are not retryable (auth, not found, etc.)
                    showNotification("Download failed: $msg", -1)
                    return@withContext Result.failure(
                        Data.Builder().putString("error", msg).build()
                    )
                }

                // If server doesn't support range, start over
                if (responseCode == 200 && startByte > 0) {
                    startByte = 0
                    tmpFile.delete()
                }

                val inputStream = conn.inputStream
                val outputStream = FileOutputStream(tmpFile, startByte > 0)
                val buffer = ByteArray(8192)
                var receivedBytes = startByte
                var lastProgressTime = System.currentTimeMillis()
                var lastProgressBytes = startByte
                var speed = 0L

                inputStream.use { input ->
                    outputStream.use { output ->
                        while (true) {
                            if (isStopped) {
                                ZeroClawService.log("ModelDownload: cancelled by user")
                                conn.disconnect()
                                return@withContext Result.failure()
                            }

                            val bytesRead = input.read(buffer)
                            if (bytesRead == -1) break

                            output.write(buffer, 0, bytesRead)
                            receivedBytes += bytesRead

                            val now = System.currentTimeMillis()
                            if (now - lastProgressTime >= PROGRESS_INTERVAL_MS) {
                                val elapsed = (now - lastProgressTime).coerceAtLeast(1)
                                speed = ((receivedBytes - lastProgressBytes) * 1000) / elapsed
                                lastProgressBytes = receivedBytes
                                lastProgressTime = now

                                val progress = if (totalBytes > 0) {
                                    (receivedBytes.toFloat() / totalBytes).coerceIn(0f, 1f)
                                } else 0f

                                setProgress(
                                    Data.Builder()
                                        .putFloat(KEY_PROGRESS, progress)
                                        .putLong(KEY_RECEIVED_BYTES, receivedBytes)
                                        .putLong(KEY_SPEED, speed)
                                        .build()
                                )

                                val pct = (progress * 100).toInt()
                                val speedLabel = "%.1f".format(speed / (1024.0 * 1024.0))
                                showNotification(
                                    "Downloading $modelId... $pct% ($speedLabel MB/s)",
                                    pct
                                )
                            }
                        }
                    }
                }

                conn.disconnect()

                // Rename tmp -> final
                tmpFile.renameTo(finalFile)
                val sizeMb = finalFile.length() / (1024 * 1024)
                ZeroClawService.log("ModelDownload: $fileName complete ($sizeMb MB)")
                showNotification("$modelId downloaded!", -1)

                return@withContext Result.success()

            } catch (e: Exception) {
                val isNetworkError = e is java.net.SocketException ||
                        e is java.net.SocketTimeoutException ||
                        e is java.io.IOException ||
                        e.message?.contains("abort", ignoreCase = true) == true ||
                        e.message?.contains("reset", ignoreCase = true) == true ||
                        e.message?.contains("timed out", ignoreCase = true) == true ||
                        e.message?.contains("connection", ignoreCase = true) == true

                val downloaded = if (tmpFile.exists()) tmpFile.length() / (1024 * 1024) else 0
                ZeroClawService.log("ModelDownload: attempt $attempt failed ($downloaded MB saved) - ${e.message}")

                if (isNetworkError && attempt < maxRetries) {
                    // Retryable — wait then resume from where we left off
                    val delaySec = attempt * 3L  // 3s, 6s, 9s, 12s, 15s
                    ZeroClawService.log("ModelDownload: retrying in ${delaySec}s (attempt ${attempt + 1}/$maxRetries)")
                    showNotification("Connection lost. Retrying in ${delaySec}s... ($downloaded MB saved)", -1)
                    Thread.sleep(delaySec * 1000)
                    continue
                } else {
                    // Non-retryable or max retries exceeded
                    ZeroClawService.log("ModelDownload: giving up after $attempt attempts - ${e.message}")
                    showNotification("Download failed after $attempt tries. Tap to retry.", -1)
                    return@withContext Result.failure(
                        Data.Builder().putString("error", e.message ?: "Unknown error").build()
                    )
                }
            }
        }

        // Should not reach here, but just in case
        return@withContext Result.failure()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                NOTIFICATION_CHANNEL,
                "Model Downloads",
                NotificationManager.IMPORTANCE_LOW
            ).apply { description = "AI model download progress" }
            val mgr = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            mgr.createNotificationChannel(channel)
        }
    }

    private fun showNotification(text: String, progress: Int) {
        val mgr = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val builder = NotificationCompat.Builder(applicationContext, NOTIFICATION_CHANNEL)
            .setSmallIcon(R.drawable.ic_zeroclaw)
            .setContentTitle("ZeroClaw Model")
            .setContentText(text)
            .setOngoing(progress >= 0)
            .setPriority(NotificationCompat.PRIORITY_LOW)

        if (progress in 0..100) {
            builder.setProgress(100, progress, false)
        } else {
            builder.setProgress(0, 0, false)
        }

        mgr.notify(NOTIFICATION_ID, builder.build())
    }

    override suspend fun getForegroundInfo(): ForegroundInfo {
        createNotificationChannel()
        val notification = NotificationCompat.Builder(applicationContext, NOTIFICATION_CHANNEL)
            .setSmallIcon(R.drawable.ic_zeroclaw)
            .setContentTitle("Downloading AI Model")
            .setContentText("Starting download...")
            .setProgress(100, 0, true)
            .setOngoing(true)
            .build()
        return ForegroundInfo(NOTIFICATION_ID, notification)
    }
}

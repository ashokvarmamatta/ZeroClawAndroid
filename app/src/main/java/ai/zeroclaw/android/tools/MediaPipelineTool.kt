package ai.zeroclaw.android.tools

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import ai.zeroclaw.android.service.ZeroClawService
import java.io.File
import java.io.FileOutputStream

/**
 * MediaPipelineTool — Phase 126: Image resize, audio info, video frame extraction.
 *
 * A unified media processing tool that handles images, audio, and video files.
 * All processing is done on-device using Android's built-in APIs — no API key needed.
 *
 * Actions:
 * - image_resize   — resize image to target dimensions, save as JPEG/PNG
 * - image_info     — get image dimensions, format, file size
 * - audio_info     — get audio duration, bitrate, format, title/artist metadata
 * - video_frame    — extract a frame from a video file at a given timestamp
 * - video_info     — get video duration, resolution, frame rate, bitrate
 */
class MediaPipelineTool(private val context: Context) : Tool {

    override val name = "media_pipeline"
    override val description = "Process media files on-device. Resize images, extract video frames, " +
        "read audio/video metadata. No API key needed. " +
        "Actions: image_resize, image_info, audio_info, video_frame, video_info."

    override val parameters = listOf(
        ToolParam("action", "string", "One of: image_resize, image_info, audio_info, video_frame, video_info"),
        ToolParam("path", "string", "File path or URL of the media file"),
        ToolParam("width", "string", "Target width for resize (optional)", required = false),
        ToolParam("height", "string", "Target height for resize (optional)", required = false),
        ToolParam("quality", "string", "JPEG quality 1-100 for resize (default 85)", required = false),
        ToolParam("timestamp_ms", "string", "Timestamp in milliseconds for video frame extraction (default 1000)", required = false),
        ToolParam("output_path", "string", "Output file path for resize/frame extraction (optional)", required = false)
    )

    override suspend fun execute(args: Map<String, String>): ToolResult {
        val action = args["action"]?.lowercase() ?: return ToolResult(false, "", "Missing 'action'.")
        val path = args["path"]?.trim() ?: return ToolResult(false, "", "Missing 'path'.")

        return try {
            when (action) {
                "image_resize" -> imageResize(path, args)
                "image_info"   -> imageInfo(path)
                "audio_info"   -> mediaInfo(path, isVideo = false)
                "video_info"   -> mediaInfo(path, isVideo = true)
                "video_frame"  -> videoFrame(path, args)
                else -> ToolResult(false, "", "Unknown action '$action'.")
            }
        } catch (e: Exception) {
            ToolResult(false, "", "Media error: ${e.message}")
        }
    }

    private fun imageResize(path: String, args: Map<String, String>): ToolResult {
        val file = resolveFile(path) ?: return ToolResult(false, "", "File not found: $path")
        val original = BitmapFactory.decodeFile(file.absolutePath)
            ?: return ToolResult(false, "", "Could not decode image: $path")

        val targetW = args["width"]?.toIntOrNull() ?: (original.width / 2)
        val targetH = args["height"]?.toIntOrNull() ?: (original.height / 2)
        val quality = args["quality"]?.toIntOrNull()?.coerceIn(1, 100) ?: 85

        val resized = Bitmap.createScaledBitmap(original, targetW, targetH, true)
        original.recycle()

        val outPath = args["output_path"]
            ?: File(context.cacheDir, "resized_${System.currentTimeMillis()}.jpg").absolutePath
        val outFile = File(outPath)
        outFile.parentFile?.mkdirs()

        FileOutputStream(outFile).use { out ->
            resized.compress(Bitmap.CompressFormat.JPEG, quality, out)
        }
        resized.recycle()

        val sizeKb = outFile.length() / 1024
        return ToolResult(true, "Resized to ${targetW}×${targetH} @ quality=$quality → $outPath (${sizeKb}KB)")
    }

    private fun imageInfo(path: String): ToolResult {
        val file = resolveFile(path) ?: return ToolResult(false, "", "File not found: $path")
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, opts)

        val sizeKb = file.length() / 1024
        return ToolResult(true, buildString {
            appendLine("Image: ${file.name}")
            appendLine("Dimensions: ${opts.outWidth}×${opts.outHeight} px")
            appendLine("MIME type: ${opts.outMimeType ?: "unknown"}")
            appendLine("File size: ${sizeKb}KB")
        })
    }

    private fun mediaInfo(path: String, isVideo: Boolean): ToolResult {
        val file = resolveFile(path) ?: return ToolResult(false, "", "File not found: $path")
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(file.absolutePath)
            val durationMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            val bitrate = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_BITRATE) ?: "unknown"
            val title = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE) ?: ""
            val artist = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST) ?: ""
            val mimeType = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_MIMETYPE) ?: "unknown"
            val sizeKb = file.length() / 1024
            val durationSec = durationMs / 1000
            val minutes = durationSec / 60
            val seconds = durationSec % 60

            return ToolResult(true, buildString {
                appendLine("${if (isVideo) "Video" else "Audio"}: ${file.name}")
                appendLine("Duration: ${minutes}m ${seconds}s")
                appendLine("Bitrate: $bitrate bps")
                appendLine("MIME type: $mimeType")
                appendLine("File size: ${sizeKb}KB")
                if (title.isNotBlank()) appendLine("Title: $title")
                if (artist.isNotBlank()) appendLine("Artist: $artist")
                if (isVideo) {
                    val width = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH) ?: "?"
                    val height = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT) ?: "?"
                    appendLine("Resolution: ${width}×${height}")
                }
            })
        } finally {
            retriever.release()
        }
    }

    private fun videoFrame(path: String, args: Map<String, String>): ToolResult {
        val file = resolveFile(path) ?: return ToolResult(false, "", "File not found: $path")
        val timestampMs = args["timestamp_ms"]?.toLongOrNull() ?: 1000L

        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(file.absolutePath)
            val frame = retriever.getFrameAtTime(
                timestampMs * 1000, // convert ms to μs
                MediaMetadataRetriever.OPTION_CLOSEST_SYNC
            ) ?: return ToolResult(false, "", "Could not extract frame at ${timestampMs}ms")

            val outPath = args["output_path"]
                ?: File(context.cacheDir, "frame_${timestampMs}ms.jpg").absolutePath
            val outFile = File(outPath)
            outFile.parentFile?.mkdirs()

            FileOutputStream(outFile).use { out ->
                frame.compress(Bitmap.CompressFormat.JPEG, 90, out)
            }
            frame.recycle()

            val sizeKb = outFile.length() / 1024
            return ToolResult(true, "Frame extracted at ${timestampMs}ms → $outPath (${sizeKb}KB, ${frame.width}×${frame.height})")
        } finally {
            retriever.release()
        }
    }

    private fun resolveFile(path: String): File? {
        val file = File(path)
        if (file.exists()) return file
        // Try common shortcuts
        val shortcuts = mapOf(
            "downloads" to android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS),
            "cache" to context.cacheDir,
            "internal" to context.filesDir
        )
        for ((prefix, dir) in shortcuts) {
            if (path.startsWith(prefix + "/")) {
                val resolved = File(dir, path.removePrefix("$prefix/"))
                if (resolved.exists()) return resolved
            }
        }
        return null
    }
}

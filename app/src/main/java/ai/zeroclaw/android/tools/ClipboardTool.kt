package ai.zeroclaw.android.tools

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * ClipboardTool — read/write device clipboard.
 *
 * Inspired by OpenClaw's clipboard skill.
 * Actions: read (default), write, clear.
 * Must run clipboard operations on main thread.
 */
class ClipboardTool(private val context: Context) : Tool {

    override val name = "clipboard"

    override val description = "Read from or write to the device clipboard. " +
            "Actions: 'read' (get current clipboard content), " +
            "'write' (copy text to clipboard), 'clear' (clear clipboard). " +
            "No API key needed."

    override val parameters = listOf(
        ToolParam("action", "string", "One of: read, write, clear. Default: read.", required = false),
        ToolParam("text", "string", "Text to copy to clipboard (for 'write')", required = false)
    )

    override suspend fun execute(args: Map<String, String>): ToolResult {
        val action = args["action"]?.trim()?.lowercase() ?: "read"

        return when (action) {
            "read" -> readClipboard()
            "write" -> {
                val text = args["text"]?.trim()
                    ?: return ToolResult(false, "", "Missing 'text' parameter for write")
                writeClipboard(text)
            }
            "clear" -> clearClipboard()
            else -> ToolResult(false, "", "Unknown action: $action. Use: read, write, clear.")
        }
    }

    private suspend fun readClipboard(): ToolResult {
        var result: ToolResult? = null
        val latch = CountDownLatch(1)

        Handler(Looper.getMainLooper()).post {
            try {
                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                if (!clipboard.hasPrimaryClip()) {
                    result = ToolResult(true, "Clipboard is empty.")
                } else {
                    val clip = clipboard.primaryClip
                    if (clip == null || clip.itemCount == 0) {
                        result = ToolResult(true, "Clipboard is empty.")
                    } else {
                        val text = clip.getItemAt(0).coerceToText(context)?.toString() ?: ""
                        if (text.isBlank()) {
                            result = ToolResult(true, "Clipboard is empty.")
                        } else {
                            val sb = StringBuilder("📋 Clipboard Content\n${"═".repeat(40)}\n\n")
                            sb.append(text.take(3000))
                            if (text.length > 3000) sb.append("\n\n... (${text.length} chars total, truncated)")
                            result = ToolResult(true, sb.toString())
                        }
                    }
                }
            } catch (e: Exception) {
                result = ToolResult(false, "", "Clipboard read error: ${e.message}")
            }
            latch.countDown()
        }

        withContext(Dispatchers.IO) { latch.await(5, TimeUnit.SECONDS) }
        return result ?: ToolResult(false, "", "Clipboard read timed out")
    }

    private suspend fun writeClipboard(text: String): ToolResult {
        if (text.isBlank()) return ToolResult(false, "", "Cannot copy blank text to clipboard")

        var result: ToolResult? = null
        val latch = CountDownLatch(1)

        Handler(Looper.getMainLooper()).post {
            try {
                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                val clip = ClipData.newPlainText("ZeroClaw", text)
                clipboard.setPrimaryClip(clip)
                result = ToolResult(true, "✅ Copied to clipboard (${text.length} chars):\n${text.take(200)}${if (text.length > 200) "..." else ""}")
            } catch (e: Exception) {
                result = ToolResult(false, "", "Clipboard write error: ${e.message}")
            }
            latch.countDown()
        }

        withContext(Dispatchers.IO) { latch.await(5, TimeUnit.SECONDS) }
        return result ?: ToolResult(false, "", "Clipboard write timed out")
    }

    private suspend fun clearClipboard(): ToolResult {
        var result: ToolResult? = null
        val latch = CountDownLatch(1)

        Handler(Looper.getMainLooper()).post {
            try {
                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                val clip = ClipData.newPlainText("", "")
                clipboard.setPrimaryClip(clip)
                result = ToolResult(true, "✅ Clipboard cleared.")
            } catch (e: Exception) {
                result = ToolResult(false, "", "Clipboard clear error: ${e.message}")
            }
            latch.countDown()
        }

        withContext(Dispatchers.IO) { latch.await(5, TimeUnit.SECONDS) }
        return result ?: ToolResult(false, "", "Clipboard clear timed out")
    }
}

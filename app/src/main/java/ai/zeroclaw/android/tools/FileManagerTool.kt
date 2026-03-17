package ai.zeroclaw.android.tools

import android.content.Context
import android.os.Environment
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

/**
 * FileManagerTool — list, read, write local files on device.
 *
 * Inspired by OpenClaw's file manager skill.
 * Works within app-accessible directories (internal storage, cache, downloads).
 * Actions: list, read, write, info, search.
 */
class FileManagerTool(private val context: Context) : Tool {

    override val name = "file_manager"

    override val description = "List, read, and write files on the device. " +
            "Actions: 'list' (directory listing), 'read' (read text file), " +
            "'write' (write/create text file), 'info' (file details), 'search' (find files by name). " +
            "Works within app storage and common directories. No API key needed."

    override val parameters = listOf(
        ToolParam("action", "string", "One of: list, read, write, info, search. Default: list.", required = false),
        ToolParam("path", "string", "File or directory path. Use 'downloads', 'documents', 'internal', 'cache' as shortcuts.", required = false),
        ToolParam("content", "string", "Content to write (for 'write' action)", required = false),
        ToolParam("query", "string", "Filename pattern to search for (for 'search')", required = false),
        ToolParam("append", "string", "Set to 'true' to append instead of overwrite (for 'write')", required = false)
    )

    private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)

    override suspend fun execute(args: Map<String, String>): ToolResult {
        val action = args["action"]?.trim()?.lowercase() ?: "list"

        return withContext(Dispatchers.IO) {
            try {
                when (action) {
                    "list" -> listDirectory(args["path"])
                    "read" -> readFile(args["path"])
                    "write" -> writeFile(args["path"], args["content"], args["append"]?.trim()?.lowercase() == "true")
                    "info" -> fileInfo(args["path"])
                    "search" -> searchFiles(args["path"], args["query"])
                    else -> ToolResult(false, "", "Unknown action: $action. Use: list, read, write, info, search.")
                }
            } catch (e: SecurityException) {
                ToolResult(false, "", "Permission denied: ${e.message}")
            } catch (e: Exception) {
                ToolResult(false, "", "File manager error: ${e.message}")
            }
        }
    }

    private fun resolvePath(path: String?): File {
        if (path.isNullOrBlank()) return context.filesDir

        return when (path.trim().lowercase()) {
            "internal", "app" -> context.filesDir
            "cache" -> context.cacheDir
            "downloads" -> Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            "documents" -> Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS)
            "pictures" -> Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES)
            "music" -> Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC)
            "dcim" -> Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM)
            "external" -> Environment.getExternalStorageDirectory()
            else -> {
                val f = File(path.trim())
                if (f.isAbsolute) f else File(context.filesDir, path.trim())
            }
        }
    }

    private fun isPathAllowed(file: File): Boolean {
        val canonical = file.canonicalPath
        val allowed = listOf(
            context.filesDir.canonicalPath,
            context.cacheDir.canonicalPath,
            context.externalCacheDir?.canonicalPath,
            Environment.getExternalStorageDirectory().canonicalPath
        ).filterNotNull()
        return allowed.any { canonical.startsWith(it) }
    }

    private fun listDirectory(path: String?): ToolResult {
        val dir = resolvePath(path)

        if (!dir.exists()) {
            return ToolResult(false, "", "Directory not found: ${dir.absolutePath}")
        }

        if (!dir.isDirectory) {
            return ToolResult(false, "", "Not a directory: ${dir.absolutePath}")
        }

        val files = dir.listFiles()?.sortedWith(compareBy<File> { !it.isDirectory }.thenBy { it.name.lowercase() })
            ?: return ToolResult(false, "", "Cannot list directory (permission denied?)")

        if (files.isEmpty()) {
            return ToolResult(true, "Directory is empty: ${dir.absolutePath}")
        }

        val sb = StringBuilder("📁 ${dir.absolutePath}\n${"═".repeat(40)}\n\n")
        var dirCount = 0
        var fileCount = 0

        for (f in files.take(50)) {
            if (f.isDirectory) {
                dirCount++
                val childCount = f.listFiles()?.size ?: 0
                sb.appendLine("📁 ${f.name}/ ($childCount items)")
            } else {
                fileCount++
                val sizeStr = formatSize(f.length())
                val date = dateFormat.format(Date(f.lastModified()))
                sb.appendLine("📄 ${f.name} ($sizeStr, $date)")
            }
        }

        if (files.size > 50) {
            sb.appendLine("\n... and ${files.size - 50} more items")
        }

        sb.appendLine("\nTotal: $dirCount folders, $fileCount files")
        return ToolResult(true, sb.toString().take(4000))
    }

    private fun readFile(path: String?): ToolResult {
        if (path.isNullOrBlank()) return ToolResult(false, "", "Missing 'path' parameter")

        val file = resolvePath(path)
        if (!file.exists()) return ToolResult(false, "", "File not found: ${file.absolutePath}")
        if (file.isDirectory) return ToolResult(false, "", "Cannot read a directory. Use 'list' action instead.")

        if (file.length() > 1_000_000) {
            return ToolResult(false, "", "File too large to read (${formatSize(file.length())}). Max 1MB for text files.")
        }

        // Check if binary
        val ext = file.extension.lowercase()
        val binaryExts = setOf("png", "jpg", "jpeg", "gif", "webp", "bmp", "mp3", "mp4", "wav", "ogg",
            "zip", "tar", "gz", "rar", "7z", "apk", "dex", "so", "bin", "dat", "db", "sqlite")
        if (ext in binaryExts) {
            return ToolResult(false, "", "Cannot read binary file (${file.extension}). Use 'info' action for details.")
        }

        val content = file.readText(Charsets.UTF_8)
        val header = "📄 ${file.name} (${formatSize(file.length())})\n${"═".repeat(40)}\n\n"
        return ToolResult(true, (header + content).take(4000))
    }

    private fun writeFile(path: String?, content: String?, append: Boolean): ToolResult {
        if (path.isNullOrBlank()) return ToolResult(false, "", "Missing 'path' parameter")
        if (content == null) return ToolResult(false, "", "Missing 'content' parameter")

        val file = resolvePath(path)

        if (!isPathAllowed(file)) {
            return ToolResult(false, "", "Cannot write to this location. Use 'internal', 'cache', or 'downloads' paths.")
        }

        // Create parent dirs if needed
        file.parentFile?.mkdirs()

        if (append) {
            file.appendText(content, Charsets.UTF_8)
        } else {
            file.writeText(content, Charsets.UTF_8)
        }

        val action = if (append) "appended to" else "written"
        val sb = StringBuilder("✅ File $action successfully!\n\n")
        sb.appendLine("Path: ${file.absolutePath}")
        sb.appendLine("Size: ${formatSize(file.length())}")
        sb.appendLine("Modified: ${dateFormat.format(Date(file.lastModified()))}")

        return ToolResult(true, sb.toString())
    }

    private fun fileInfo(path: String?): ToolResult {
        if (path.isNullOrBlank()) return ToolResult(false, "", "Missing 'path' parameter")

        val file = resolvePath(path)
        if (!file.exists()) return ToolResult(false, "", "File not found: ${file.absolutePath}")

        val sb = StringBuilder("File Info\n${"═".repeat(40)}\n\n")
        sb.appendLine("Name: ${file.name}")
        sb.appendLine("Path: ${file.absolutePath}")
        sb.appendLine("Type: ${if (file.isDirectory) "Directory" else "File"}")
        sb.appendLine("Size: ${formatSize(file.length())}")
        sb.appendLine("Modified: ${dateFormat.format(Date(file.lastModified()))}")
        sb.appendLine("Readable: ${file.canRead()}")
        sb.appendLine("Writable: ${file.canWrite()}")

        if (file.isDirectory) {
            val children = file.listFiles()
            val dirs = children?.count { it.isDirectory } ?: 0
            val files = children?.count { it.isFile } ?: 0
            sb.appendLine("Contents: $dirs folders, $files files")
            // Total size
            val totalSize = file.walkTopDown().filter { it.isFile }.sumOf { it.length() }
            sb.appendLine("Total size: ${formatSize(totalSize)}")
        } else {
            sb.appendLine("Extension: ${file.extension.ifEmpty { "(none)" }}")
        }

        return ToolResult(true, sb.toString())
    }

    private fun searchFiles(path: String?, query: String?): ToolResult {
        if (query.isNullOrBlank()) return ToolResult(false, "", "Missing 'query' parameter")

        val dir = resolvePath(path)
        if (!dir.exists() || !dir.isDirectory) {
            return ToolResult(false, "", "Directory not found: ${dir.absolutePath}")
        }

        val pattern = query.trim().lowercase()
        val matches = mutableListOf<String>()

        dir.walkTopDown()
            .filter { it.isFile && it.name.lowercase().contains(pattern) }
            .take(30)
            .forEach { f ->
                val relPath = f.relativeTo(dir).path
                val size = formatSize(f.length())
                matches.add("📄 $relPath ($size)")
            }

        if (matches.isEmpty()) {
            return ToolResult(true, "No files matching \"$query\" found in ${dir.absolutePath}")
        }

        val header = "Search Results for \"$query\" in ${dir.absolutePath}\n${"═".repeat(40)}\n\n"
        return ToolResult(true, (header + matches.joinToString("\n")).take(4000))
    }

    private fun formatSize(bytes: Long): String {
        return when {
            bytes < 1024 -> "$bytes B"
            bytes < 1024 * 1024 -> "${"%.1f".format(bytes / 1024.0)} KB"
            bytes < 1024 * 1024 * 1024 -> "${"%.1f".format(bytes / (1024.0 * 1024.0))} MB"
            else -> "${"%.2f".format(bytes / (1024.0 * 1024.0 * 1024.0))} GB"
        }
    }
}

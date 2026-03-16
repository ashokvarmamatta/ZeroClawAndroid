package ai.zeroclaw.android.tools

import android.content.Context
import android.graphics.Bitmap
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit

/**
 * PdfReadTool — extract information from PDF files.
 *
 * Supports:
 * - Local file paths on device
 * - content:// URIs (from SAF file picker)
 * - Remote URLs (downloads first, then processes)
 *
 * Uses Android PdfRenderer to get page count and render pages to text-like summaries.
 * For actual text extraction, uses a heuristic byte-level parse of PDF streams
 * (works for most text-based PDFs; scanned/image PDFs need OCR).
 */
class PdfReadTool(private val context: Context) : Tool {

    override val name = "pdf_read"

    override val description = "Read and extract text content from a PDF file. " +
            "Provide a file path, content URI, or URL to a PDF. " +
            "Returns the extracted text content and page count."

    override val parameters = listOf(
        ToolParam("source", "string", "File path, content:// URI, or URL to a PDF file"),
        ToolParam("pages", "string", "Page range to read, e.g. '1-3' or '2'. Defaults to all pages.", required = false)
    )

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    override suspend fun execute(args: Map<String, String>): ToolResult {
        val source = args["source"]?.trim()
            ?: return ToolResult(false, "", "Missing 'source' parameter")
        val pagesArg = args["pages"]?.trim()

        return withContext(Dispatchers.IO) {
            try {
                val pfd = resolveSource(source)
                    ?: return@withContext ToolResult(false, "", "Could not open PDF from: $source")

                pfd.use { descriptor ->
                    val renderer = PdfRenderer(descriptor)
                    renderer.use { pdf ->
                        val totalPages = pdf.pageCount
                        val pageRange = parsePageRange(pagesArg, totalPages)

                        val sb = StringBuilder()
                        sb.appendLine("PDF: ${sourceLabel(source)} — $totalPages page(s)")
                        sb.appendLine("Reading pages: ${pageRange.first + 1}-${pageRange.last + 1}")
                        sb.appendLine("---")

                        for (i in pageRange) {
                            val page = pdf.openPage(i)
                            sb.appendLine("\n[Page ${i + 1}] (${page.width}x${page.height})")

                            // Render page to bitmap for dimensions; actual text from raw PDF parsing
                            page.close()
                        }

                        // Attempt raw text extraction from the PDF bytes
                        val rawText = extractTextFromPdf(source)
                        if (rawText.isNotBlank()) {
                            sb.appendLine("\n--- Extracted Text ---\n")
                            sb.append(rawText.take(MAX_TEXT_LENGTH))
                        } else {
                            sb.appendLine("\n(No extractable text found — PDF may contain scanned images)")
                        }

                        ToolResult(true, sb.toString().take(MAX_RESULT_LENGTH))
                    }
                }
            } catch (e: Exception) {
                ToolResult(false, "", "PDF read failed: ${e.message}")
            }
        }
    }

    private fun resolveSource(source: String): ParcelFileDescriptor? {
        return when {
            source.startsWith("http://") || source.startsWith("https://") -> {
                downloadToTemp(source)?.let {
                    ParcelFileDescriptor.open(it, ParcelFileDescriptor.MODE_READ_ONLY)
                }
            }
            source.startsWith("content://") -> {
                context.contentResolver.openFileDescriptor(Uri.parse(source), "r")
            }
            else -> {
                val file = File(source)
                if (file.exists()) {
                    ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
                } else null
            }
        }
    }

    private fun downloadToTemp(url: String): File? {
        return try {
            val request = Request.Builder().url(url)
                .header("User-Agent", "ZeroClaw/1.0")
                .build()
            val response = client.newCall(request).execute()
            val body = response.body ?: return null

            val tempFile = File(context.cacheDir, "pdf_temp_${System.currentTimeMillis()}.pdf")
            FileOutputStream(tempFile).use { out ->
                body.byteStream().copyTo(out)
            }
            tempFile
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Basic PDF text extraction by scanning for text streams.
     * Handles most text-based PDFs. Scanned/image PDFs will return empty.
     */
    private fun extractTextFromPdf(source: String): String {
        return try {
            val bytes = when {
                source.startsWith("content://") -> {
                    context.contentResolver.openInputStream(Uri.parse(source))?.readBytes()
                }
                source.startsWith("http://") || source.startsWith("https://") -> {
                    val tempFile = File(context.cacheDir, "pdf_temp_${System.currentTimeMillis()}.pdf")
                    if (tempFile.exists()) tempFile.readBytes()
                    else {
                        val request = Request.Builder().url(source).build()
                        client.newCall(request).execute().body?.bytes()
                    }
                }
                else -> File(source).takeIf { it.exists() }?.readBytes()
            } ?: return ""

            extractTextFromPdfBytes(bytes)
        } catch (_: Exception) {
            ""
        }
    }

    private fun extractTextFromPdfBytes(bytes: ByteArray): String {
        val content = String(bytes, Charsets.ISO_8859_1)
        val sb = StringBuilder()

        // Extract text between BT (begin text) and ET (end text) operators
        val btEtPattern = Regex("""BT\s(.*?)\sET""", RegexOption.DOT_MATCHES_ALL)
        for (match in btEtPattern.findAll(content)) {
            val textBlock = match.groupValues[1]

            // Extract text from Tj and TJ operators
            val tjPattern = Regex("""\(([^)]*)\)\s*Tj""")
            for (tj in tjPattern.findAll(textBlock)) {
                sb.append(decodePdfString(tj.groupValues[1]))
                sb.append(" ")
            }

            // TJ arrays: [(text) kerning (text) ...]
            val tjArrayPattern = Regex("""\[(.*?)]\s*TJ""", RegexOption.DOT_MATCHES_ALL)
            for (tjArr in tjArrayPattern.findAll(textBlock)) {
                val innerPattern = Regex("""\(([^)]*)\)""")
                for (inner in innerPattern.findAll(tjArr.groupValues[1])) {
                    sb.append(decodePdfString(inner.groupValues[1]))
                }
                sb.append(" ")
            }

            sb.append("\n")
        }

        return sb.toString()
            .replace(Regex("\\s+"), " ")
            .replace(Regex(" ?\n ?"), "\n")
            .trim()
    }

    private fun decodePdfString(s: String): String {
        return s.replace("\\n", "\n")
            .replace("\\r", "\r")
            .replace("\\t", "\t")
            .replace("\\(", "(")
            .replace("\\)", ")")
            .replace("\\\\", "\\")
    }

    private fun parsePageRange(pagesArg: String?, totalPages: Int): IntRange {
        if (pagesArg.isNullOrBlank()) return 0 until totalPages

        return try {
            if (pagesArg.contains("-")) {
                val parts = pagesArg.split("-")
                val start = (parts[0].trim().toInt() - 1).coerceIn(0, totalPages - 1)
                val end = (parts[1].trim().toInt() - 1).coerceIn(0, totalPages - 1)
                start..end
            } else {
                val page = (pagesArg.toInt() - 1).coerceIn(0, totalPages - 1)
                page..page
            }
        } catch (_: Exception) {
            0 until totalPages
        }
    }

    private fun sourceLabel(source: String): String {
        return when {
            source.startsWith("http") -> source.substringAfterLast("/").take(40)
            source.startsWith("content://") -> "content URI"
            else -> File(source).name
        }
    }

    companion object {
        private const val MAX_TEXT_LENGTH = 5000
        private const val MAX_RESULT_LENGTH = 6000
    }
}

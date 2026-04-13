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

                        // Step 1: Try heuristic text extraction from PDF bytes
                        var rawText = extractTextFromPdf(source)

                        // Validate quality — if mostly garbage, discard
                        if (rawText.isNotBlank() && !isReadableText(rawText)) {
                            rawText = ""
                        }

                        if (rawText.isNotBlank()) {
                            sb.appendLine("\n--- Extracted Text ---\n")
                            sb.append(rawText.take(MAX_TEXT_LENGTH))
                        } else {
                            // Step 2: Fallback — render each page to bitmap, extract visible text
                            sb.appendLine("\n--- Extracted Text (from rendered pages) ---\n")
                            val pageTexts = mutableListOf<String>()
                            for (i in pageRange) {
                                val page = pdf.openPage(i)
                                // Render to bitmap at 2x scale for readability
                                val scale = 2
                                val bitmap = Bitmap.createBitmap(
                                    page.width * scale, page.height * scale, Bitmap.Config.ARGB_8888
                                )
                                val canvas = android.graphics.Canvas(bitmap)
                                canvas.drawColor(android.graphics.Color.WHITE)
                                page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                                page.close()

                                // Convert bitmap to base64 for vision LLM, or try pixel-based OCR
                                val pageText = extractTextFromBitmap(bitmap, i + 1)
                                if (pageText.isNotBlank()) pageTexts.add(pageText)
                                bitmap.recycle()

                                if (pageTexts.sumOf { it.length } > MAX_TEXT_LENGTH) break
                            }

                            if (pageTexts.isNotEmpty()) {
                                sb.append(pageTexts.joinToString("\n\n").take(MAX_TEXT_LENGTH))
                            } else {
                                sb.appendLine("(No extractable text found — PDF may contain scanned images)")
                            }
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

        // Step 1: Decompress FlateDecode streams
        val decompressedStreams = decompressStreams(bytes, content)

        // Step 2: Try BT/ET extraction on all streams
        val btEtText = extractFromBtEt(content, decompressedStreams)
        if (btEtText.length > 50) return btEtText

        // Step 3: Fallback — extract printable text runs from decompressed streams
        // This catches PDFs where BT/ET parsing fails but readable text exists
        val fallbackText = extractPrintableText(decompressedStreams)
        if (fallbackText.length > 50) return fallbackText

        // Step 4: Last resort — extract any printable ASCII runs from raw PDF bytes
        return extractPrintableRuns(bytes)
    }

    /** Primary extraction: BT/ET text operators with parenthesized + hex strings. */
    private fun extractFromBtEt(content: String, streams: List<String>): String {
        val sb = StringBuilder()
        val allContent = buildString {
            append(content)
            for (s in streams) append(s)
        }

        val btEtPattern = Regex("""BT\s(.*?)\sET""", RegexOption.DOT_MATCHES_ALL)
        for (match in btEtPattern.findAll(allContent)) {
            val textBlock = match.groupValues[1]

            // Parenthesized strings: (text) Tj
            val tjPattern = Regex("""\(([^)]*)\)\s*Tj""")
            for (tj in tjPattern.findAll(textBlock)) {
                sb.append(decodePdfString(tj.groupValues[1]))
                sb.append(" ")
            }

            // Hex strings: <hex> Tj  (e.g. <0048006500...>)
            val hexTjPattern = Regex("""<([0-9A-Fa-f]+)>\s*Tj""")
            for (hx in hexTjPattern.findAll(textBlock)) {
                sb.append(decodeHexString(hx.groupValues[1]))
                sb.append(" ")
            }

            // TJ arrays: [(text) kerning (text) ...] or [<hex> kerning <hex> ...]
            val tjArrayPattern = Regex("""\[(.*?)]\s*TJ""", RegexOption.DOT_MATCHES_ALL)
            for (tjArr in tjArrayPattern.findAll(textBlock)) {
                val arrayContent = tjArr.groupValues[1]
                // Parenthesized entries
                val innerParens = Regex("""\(([^)]*)\)""")
                for (inner in innerParens.findAll(arrayContent)) {
                    sb.append(decodePdfString(inner.groupValues[1]))
                }
                // Hex entries
                val innerHex = Regex("""<([0-9A-Fa-f]+)>""")
                for (inner in innerHex.findAll(arrayContent)) {
                    sb.append(decodeHexString(inner.groupValues[1]))
                }
                sb.append(" ")
            }

            sb.append("\n")
        }

        return sb.toString().replace(Regex("\\s+"), " ").replace(Regex(" ?\n ?"), "\n").trim()
    }

    /** Decode hex-encoded PDF string (e.g. "0048006500" → "He"). */
    private fun decodeHexString(hex: String): String {
        val sb = StringBuilder()
        // Try UTF-16BE first (2 bytes per char, common in modern PDFs)
        if (hex.length >= 4 && hex.length % 4 == 0) {
            var i = 0
            var isUtf16 = true
            while (i + 3 < hex.length) {
                val code = hex.substring(i, i + 4).toIntOrNull(16) ?: run { isUtf16 = false; break }
                if (code in 0x20..0xFFFF) sb.append(code.toChar())
                i += 4
            }
            if (isUtf16 && sb.isNotEmpty()) return sb.toString()
            sb.clear()
        }
        // Fallback: single-byte encoding
        var i = 0
        while (i + 1 < hex.length) {
            val code = hex.substring(i, i + 2).toIntOrNull(16) ?: break
            if (code in 0x20..0x7E) sb.append(code.toChar())
            i += 2
        }
        return sb.toString()
    }

    /** Extract printable text from decompressed stream content. */
    private fun extractPrintableText(streams: List<String>): String {
        val sb = StringBuilder()
        for (stream in streams) {
            // Look for runs of printable ASCII (4+ chars) — likely real text
            val runPattern = Regex("""[\x20-\x7E]{4,}""")
            for (match in runPattern.findAll(stream)) {
                val text = match.value.trim()
                // Skip PDF operators and structural keywords
                if (text.length > 3 && !text.matches(Regex("""^[A-Z][a-z]?\s*$""")) &&
                    !text.startsWith("/") && !text.contains("endobj") &&
                    !text.contains("stream") && !text.contains("xref") &&
                    !text.matches(Regex("""^\d+\s+\d+\s+\d+\s+\w+$"""))
                ) {
                    sb.append(text).append(" ")
                }
            }
            sb.append("\n")
        }
        return sb.toString().replace(Regex("\\s+"), " ").replace(Regex(" ?\n ?"), "\n").trim()
    }

    /** Last resort: extract printable ASCII runs directly from raw bytes. */
    private fun extractPrintableRuns(bytes: ByteArray): String {
        val sb = StringBuilder()
        val currentRun = StringBuilder()
        for (b in bytes) {
            val c = b.toInt() and 0xFF
            if (c in 0x20..0x7E || c == 0x0A || c == 0x0D) {
                currentRun.append(c.toChar())
            } else {
                if (currentRun.length >= 8) {
                    val text = currentRun.toString().trim()
                    if (text.length >= 8 && !text.startsWith("<<") && !text.startsWith("/") &&
                        !text.contains("endobj") && !text.contains("endstream")) {
                        sb.append(text).append(" ")
                    }
                }
                currentRun.clear()
            }
        }
        return sb.toString().replace(Regex("\\s+"), " ").trim()
    }

    /**
     * Find and decompress FlateDecode (zlib) streams in the PDF.
     * Most modern PDFs (Word, Google Docs, etc.) compress content streams.
     */
    private fun decompressStreams(bytes: ByteArray, content: String): List<String> {
        val streams = mutableListOf<String>()

        // Find stream...endstream blocks
        val streamPattern = Regex("""stream\r?\n""")
        val endPattern = "endstream"

        var searchFrom = 0
        while (searchFrom < content.length) {
            val streamMatch = streamPattern.find(content, searchFrom) ?: break
            val dataStart = streamMatch.range.last + 1
            val endIdx = content.indexOf(endPattern, dataStart)
            if (endIdx < 0) break

            // Check if this stream uses FlateDecode
            val headerStart = (dataStart - 200).coerceAtLeast(0)
            val header = content.substring(headerStart, streamMatch.range.first)
            if (header.contains("FlateDecode")) {
                try {
                    val compressedBytes = bytes.copyOfRange(dataStart, endIdx)
                    val inflater = java.util.zip.Inflater()
                    inflater.setInput(compressedBytes)
                    val output = ByteArray(compressedBytes.size * 10)
                    val decompressedLen = inflater.inflate(output)
                    inflater.end()
                    if (decompressedLen > 0) {
                        streams.add(String(output, 0, decompressedLen, Charsets.ISO_8859_1))
                    }
                } catch (_: Exception) {
                    // Not all FlateDecode streams contain text — skip failures
                }
            }

            searchFrom = endIdx + endPattern.length
        }

        return streams
    }

    /**
     * Check if extracted text is actually readable (not binary/structural garbage).
     * Returns false if text has too many non-printable chars or looks like PDF internals.
     */
    private fun isReadableText(text: String): Boolean {
        if (text.length < 20) return false
        val sample = text.take(500)
        val printable = sample.count { it in ' '..'~' || it == '\n' || it == '\r' || it == '\t' }
        val ratio = printable.toFloat() / sample.length
        // If less than 60% printable ASCII, it's likely garbage
        if (ratio < 0.6f) return false
        // Check for common words (at least 3 real words in first 500 chars)
        val words = sample.split(Regex("\\s+")).count { it.length in 2..20 && it.matches(Regex("[a-zA-Z0-9,.!?:;'\"()-]+")) }
        return words >= 3
    }

    /**
     * Extract text from a rendered PDF page bitmap using pixel analysis.
     * Looks for text-like patterns in the bitmap. Returns simple OCR-lite output.
     * For complex PDFs, the bitmap can also be sent to a vision model via ImageAnalysisTool.
     */
    private fun extractTextFromBitmap(bitmap: Bitmap, pageNum: Int): String {
        // Use Android's built-in text extraction if available (API 31+)
        // Fallback: save bitmap to temp file for vision model to read later
        val tempFile = File(context.cacheDir, "pdf_page_${pageNum}_${System.currentTimeMillis()}.png")
        try {
            java.io.FileOutputStream(tempFile).use { out ->
                bitmap.compress(Bitmap.CompressFormat.PNG, 85, out)
            }

            // Try using ImageAnalysisTool to OCR the page via vision LLM
            val imageTool = ImageAnalysisTool(context)
            val result = kotlinx.coroutines.runBlocking {
                imageTool.execute(mapOf(
                    "source" to tempFile.absolutePath,
                    "prompt" to "Extract ALL text from this PDF page image. Return ONLY the text content, preserving formatting and structure. Do not describe the image — just output the text you can read."
                ))
            }
            return if (result.success && result.content.length > 20) {
                "[Page $pageNum]\n${result.content}"
            } else ""
        } catch (_: Exception) {
            return ""
        } finally {
            tempFile.delete()
        }
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

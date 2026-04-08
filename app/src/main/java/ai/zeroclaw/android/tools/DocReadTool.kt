package ai.zeroclaw.android.tools

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.File
import java.io.InputStream
import java.util.concurrent.TimeUnit
import java.util.zip.ZipInputStream

/**
 * DocReadTool — extract text from .docx / .doc files.
 *
 * DOCX files are ZIP archives containing XML. This tool reads word/document.xml
 * and extracts all <w:t> text nodes, preserving paragraph breaks.
 *
 * Supports:
 * - content:// URIs (from SAF file picker)
 * - Local file paths
 * - Remote URLs (downloads first)
 *
 * Also handles plain .txt/.csv/.md/.json files as a fallback text reader.
 */
class DocReadTool(private val context: Context) : Tool {

    override val name = "doc_read"

    override val description = "Read and extract text from document files (.docx, .doc, .txt, .csv, .md, .json, .xml, .html). " +
            "Provide a file path, content URI, or URL. Returns extracted text content."

    override val parameters = listOf(
        ToolParam("source", "string", "File path, content:// URI, or URL to a document file"),
        ToolParam("max_chars", "string", "Maximum characters to extract (default: 10000)", required = false)
    )

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    override suspend fun execute(args: Map<String, String>): ToolResult {
        val source = args["source"]?.trim()
            ?: return ToolResult(false, "", "Missing 'source' parameter")
        val maxChars = args["max_chars"]?.toIntOrNull() ?: 10000

        return withContext(Dispatchers.IO) {
            try {
                val inputStream = resolveSource(source)
                    ?: return@withContext ToolResult(false, "", "Could not open file from: $source")

                val fileName = guessFileName(source).lowercase()

                val text = when {
                    fileName.endsWith(".docx") -> extractDocx(inputStream, maxChars)
                    fileName.endsWith(".doc") -> extractLegacyDoc(inputStream, maxChars)
                    fileName.endsWith(".xlsx") -> extractXlsx(inputStream, maxChars)
                    else -> extractPlainText(inputStream, maxChars)
                }

                if (text.isBlank()) {
                    return@withContext ToolResult(false, "", "No text content found in file")
                }

                ToolResult(true, text)
            } catch (e: Exception) {
                ToolResult(false, "", "Document read failed: ${e.message}")
            }
        }
    }

    /** Open an InputStream from a content:// URI, file path, or URL */
    private fun resolveSource(source: String): InputStream? {
        return try {
            when {
                source.startsWith("content://") -> {
                    context.contentResolver.openInputStream(Uri.parse(source))
                }
                source.startsWith("http://") || source.startsWith("https://") -> {
                    val request = Request.Builder().url(source)
                        .header("User-Agent", "ZeroClaw/1.0")
                        .build()
                    client.newCall(request).execute().body?.byteStream()
                }
                else -> {
                    val file = File(source)
                    if (file.exists()) file.inputStream() else null
                }
            }
        } catch (_: Exception) {
            null
        }
    }

    /** Guess filename from source path/URI */
    private fun guessFileName(source: String): String {
        // Try content resolver for display name
        if (source.startsWith("content://")) {
            try {
                val cursor = context.contentResolver.query(Uri.parse(source), null, null, null, null)
                cursor?.use {
                    if (it.moveToFirst()) {
                        val idx = it.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                        if (idx >= 0) return it.getString(idx) ?: ""
                    }
                }
            } catch (_: Exception) {}
        }
        // Fallback: extract from path
        return source.substringAfterLast("/").substringBefore("?")
    }

    /**
     * Extract text from a .docx file (Office Open XML).
     * DOCX = ZIP containing word/document.xml with <w:t> text elements.
     */
    private fun extractDocx(input: InputStream, maxChars: Int): String {
        val result = StringBuilder()
        val zip = ZipInputStream(input)

        var entry = zip.nextEntry
        while (entry != null) {
            if (entry.name == "word/document.xml") {
                val xmlBytes = zip.readBytes()
                val xmlInput = xmlBytes.inputStream()

                val factory = XmlPullParserFactory.newInstance()
                factory.isNamespaceAware = true
                val parser = factory.newPullParser()
                parser.setInput(xmlInput, "UTF-8")

                var inParagraph = false
                var eventType = parser.eventType

                while (eventType != XmlPullParser.END_DOCUMENT) {
                    when (eventType) {
                        XmlPullParser.START_TAG -> {
                            when (parser.name) {
                                "p" -> inParagraph = true  // w:p = paragraph
                                "br" -> result.append("\n") // w:br = line break
                                "tab" -> result.append("\t") // w:tab
                            }
                        }
                        XmlPullParser.TEXT -> {
                            val text = parser.text
                            if (text != null && text.isNotBlank()) {
                                result.append(text)
                                if (result.length >= maxChars) break
                            }
                        }
                        XmlPullParser.END_TAG -> {
                            if (parser.name == "p" && inParagraph) {
                                result.append("\n")
                                inParagraph = false
                            }
                        }
                    }
                    eventType = parser.next()
                }
                break // found document.xml, done
            }
            entry = zip.nextEntry
        }

        zip.close()
        return result.toString().take(maxChars).trim()
    }

    /**
     * Extract text from .xlsx (Excel Open XML).
     * XLSX = ZIP containing xl/sharedStrings.xml + xl/worksheets/sheet1.xml.
     * We extract shared strings for a readable text dump.
     */
    private fun extractXlsx(input: InputStream, maxChars: Int): String {
        val result = StringBuilder()
        val zip = ZipInputStream(input)

        var entry = zip.nextEntry
        while (entry != null) {
            if (entry.name == "xl/sharedStrings.xml") {
                val xmlBytes = zip.readBytes()
                val xmlInput = xmlBytes.inputStream()

                val factory = XmlPullParserFactory.newInstance()
                factory.isNamespaceAware = true
                val parser = factory.newPullParser()
                parser.setInput(xmlInput, "UTF-8")

                var eventType = parser.eventType
                while (eventType != XmlPullParser.END_DOCUMENT) {
                    if (eventType == XmlPullParser.TEXT) {
                        val text = parser.text
                        if (text != null && text.isNotBlank()) {
                            result.append(text).append("\t")
                            if (result.length >= maxChars) break
                        }
                    }
                    if (eventType == XmlPullParser.END_TAG && parser.name == "si") {
                        result.append("\n")
                    }
                    eventType = parser.next()
                }
                break
            }
            entry = zip.nextEntry
        }

        zip.close()
        return result.toString().take(maxChars).trim()
    }

    /**
     * Legacy .doc files (OLE2 binary format).
     * We do a best-effort byte scan for readable ASCII/UTF text runs.
     */
    private fun extractLegacyDoc(input: InputStream, maxChars: Int): String {
        val bytes = input.readBytes()
        val result = StringBuilder()
        var i = 0

        // Scan for printable text runs (heuristic for OLE2 binary)
        val textRun = StringBuilder()
        while (i < bytes.size && result.length < maxChars) {
            val b = bytes[i].toInt() and 0xFF
            if (b in 32..126 || b == 10 || b == 13 || b == 9) {
                textRun.append(b.toChar())
            } else {
                // End of text run — keep if it's long enough (>4 chars = likely real text)
                if (textRun.length > 4) {
                    result.append(textRun).append(" ")
                }
                textRun.clear()
            }
            i++
        }
        if (textRun.length > 4) {
            result.append(textRun)
        }

        return result.toString().take(maxChars).trim()
    }

    /** Read plain text files directly */
    private fun extractPlainText(input: InputStream, maxChars: Int): String {
        return input.bufferedReader().use { reader ->
            val buf = CharArray(maxChars)
            val read = reader.read(buf, 0, maxChars)
            if (read > 0) String(buf, 0, read) else ""
        }
    }
}

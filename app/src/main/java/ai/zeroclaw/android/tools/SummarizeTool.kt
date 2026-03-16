package ai.zeroclaw.android.tools

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * SummarizeTool — condense long text or web articles into key points.
 *
 * Uses extractive summarization (no LLM call needed inside the tool):
 * scores sentences by word frequency, position, and length, then picks
 * the top-ranked sentences. The LLM gets a compact version to work with.
 *
 * Inspired by OpenClaw's summarize skill.
 */
class SummarizeTool : Tool {

    override val name = "summarize"

    override val description = "Summarize long text or a web article into key points. " +
            "Provide raw text or a URL. Returns the most important sentences extracted from the content."

    override val parameters = listOf(
        ToolParam("text", "string", "The text to summarize. Can be raw text or a URL to fetch first.", required = true),
        ToolParam("sentences", "string", "Number of key sentences to extract (default: 5, max: 15).", required = false)
    )

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    override suspend fun execute(args: Map<String, String>): ToolResult {
        val input = args["text"]?.trim()
            ?: return ToolResult(false, "", "Missing 'text' parameter")

        if (input.isBlank()) return ToolResult(false, "", "Empty text")

        val numSentences = (args["sentences"]?.trim()?.toIntOrNull() ?: 5).coerceIn(1, 15)

        return withContext(Dispatchers.IO) {
            try {
                // If input looks like a URL, fetch it first
                val text = if (input.matches(Regex("^https?://.*", RegexOption.IGNORE_CASE)) ||
                    input.matches(Regex("^www\\..*", RegexOption.IGNORE_CASE))) {
                    fetchAndExtract(if (input.startsWith("http")) input else "https://$input")
                } else {
                    input
                }

                if (text.isBlank()) {
                    return@withContext ToolResult(false, "", "No readable content found")
                }

                val summary = extractiveSummarize(text, numSentences)
                ToolResult(true, summary)
            } catch (e: Exception) {
                ToolResult(false, "", "Summarization failed: ${e.message}")
            }
        }
    }

    /**
     * Extractive summarization — scores sentences and picks the best ones.
     *
     * Scoring factors:
     * 1. Word frequency (TF) — sentences with common meaningful words score higher
     * 2. Position — first and last sentences of the text get a bonus
     * 3. Length — very short or very long sentences are penalized
     * 4. Cue phrases — sentences starting with key phrases get a bonus
     */
    private fun extractiveSummarize(text: String, numSentences: Int): String {
        // Split into sentences
        val sentences = text.split(Regex("(?<=[.!?])\\s+(?=[A-Z\"])"))
            .map { it.trim() }
            .filter { it.length >= 15 && it.length <= 500 } // filter garbage

        if (sentences.size <= numSentences) {
            return "Summary (${sentences.size} sentences):\n\n" + sentences.joinToString("\n\n")
        }

        // Build word frequency map (skip stop words)
        val wordFreq = mutableMapOf<String, Int>()
        val allWords = text.lowercase().split(Regex("\\W+")).filter { it.length > 3 && it !in STOP_WORDS }
        for (word in allWords) {
            wordFreq[word] = (wordFreq[word] ?: 0) + 1
        }
        val maxFreq = wordFreq.values.maxOrNull()?.toDouble() ?: 1.0

        // Score each sentence
        val scored = sentences.mapIndexed { index, sentence ->
            var score = 0.0

            // 1. Word frequency score
            val words = sentence.lowercase().split(Regex("\\W+")).filter { it.length > 3 }
            if (words.isNotEmpty()) {
                val freqScore = words.sumOf { (wordFreq[it] ?: 0).toDouble() / maxFreq } / words.size
                score += freqScore * 3.0
            }

            // 2. Position score — first 3 and last 2 sentences get bonus
            if (index < 3) score += 2.0 - (index * 0.5)
            if (index >= sentences.size - 2) score += 1.0

            // 3. Length score — prefer medium-length sentences
            val len = sentence.length
            score += when {
                len in 40..200 -> 1.0
                len in 200..350 -> 0.5
                else -> 0.0
            }

            // 4. Cue phrase bonus
            val lower = sentence.lowercase()
            if (CUE_PHRASES.any { lower.startsWith(it) }) score += 1.5
            if (lower.contains("important") || lower.contains("significant") ||
                lower.contains("key") || lower.contains("main") || lower.contains("conclusion")) {
                score += 1.0
            }

            // 5. Has numbers (often factual) — small bonus
            if (sentence.contains(Regex("\\d+"))) score += 0.5

            Pair(index, score)
        }

        // Pick top sentences, maintain original order
        val topIndices = scored.sortedByDescending { it.second }
            .take(numSentences)
            .map { it.first }
            .sorted()

        val summary = topIndices.map { sentences[it] }

        val wordCount = text.split(Regex("\\s+")).size
        val header = "Summary ($numSentences key points from ~$wordCount words):\n\n"
        return header + summary.joinToString("\n\n") { "• $it" }
    }

    /** Fetch URL and extract text (reuses WebFetchTool's approach) */
    private fun fetchAndExtract(url: String): String {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", "Mozilla/5.0 (Android; ZeroClaw Bot)")
            .header("Accept", "text/html,application/xhtml+xml,text/plain")
            .get()
            .build()

        val response = client.newCall(request).execute()
        val body = response.body?.string() ?: return ""
        val contentType = response.header("Content-Type", "") ?: ""

        return if (contentType.contains("text/html") || body.trimStart().startsWith("<")) {
            extractReadableText(body)
        } else {
            body
        }.take(MAX_INPUT_LENGTH)
    }

    private fun extractReadableText(html: String): String {
        var text = html
        text = text.replace(Regex("<script[^>]*>[\\s\\S]*?</script>", RegexOption.IGNORE_CASE), " ")
        text = text.replace(Regex("<style[^>]*>[\\s\\S]*?</style>", RegexOption.IGNORE_CASE), " ")
        text = text.replace(Regex("<nav[^>]*>[\\s\\S]*?</nav>", RegexOption.IGNORE_CASE), " ")
        text = text.replace(Regex("<footer[^>]*>[\\s\\S]*?</footer>", RegexOption.IGNORE_CASE), " ")
        text = text.replace(Regex("<header[^>]*>[\\s\\S]*?</header>", RegexOption.IGNORE_CASE), " ")
        text = text.replace(Regex("<!--[\\s\\S]*?-->"), " ")
        text = text.replace(Regex("<br[^>]*/?>", RegexOption.IGNORE_CASE), "\n")
        text = text.replace(Regex("</p>", RegexOption.IGNORE_CASE), "\n\n")
        text = text.replace(Regex("</div>", RegexOption.IGNORE_CASE), "\n")
        text = text.replace(Regex("</h[1-6]>", RegexOption.IGNORE_CASE), "\n\n")
        text = text.replace(Regex("</li>", RegexOption.IGNORE_CASE), "\n")
        text = text.replace(Regex("<[^>]+>"), " ")
        text = text.replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">")
            .replace("&quot;", "\"").replace("&#x27;", "'").replace("&nbsp;", " ")
            .replace("&mdash;", "—").replace("&ndash;", "–")
        text = text.replace(Regex("[ \\t]+"), " ")
        text = text.replace(Regex("\\n[ \\t]+"), "\n")
        text = text.replace(Regex("\\n{3,}"), "\n\n")
        return text.trim()
    }

    companion object {
        private const val MAX_INPUT_LENGTH = 20000

        private val STOP_WORDS = setOf(
            "the", "this", "that", "these", "those", "with", "from", "have", "has",
            "been", "were", "would", "could", "should", "will", "shall", "might",
            "also", "than", "then", "when", "where", "what", "which", "while",
            "more", "most", "some", "such", "very", "just", "only", "about",
            "into", "over", "after", "before", "between", "under", "through",
            "each", "every", "both", "other", "another", "same", "different",
            "there", "here", "they", "them", "their", "your", "does", "done",
            "being", "having", "doing", "made", "make", "like", "even", "still"
        )

        private val CUE_PHRASES = listOf(
            "in summary", "in conclusion", "to summarize", "overall",
            "the key", "the main", "importantly", "significantly",
            "the result", "the findings", "according to", "research shows",
            "the study", "experts say", "data shows", "results show",
            "first", "finally", "notably"
        )
    }
}

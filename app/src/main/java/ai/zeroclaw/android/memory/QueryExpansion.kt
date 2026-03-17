package ai.zeroclaw.android.memory

/**
 * QueryExpansion — Phase 120: Auto-generate related queries for better memory retrieval.
 *
 * When searching memory, a single query often misses relevant results because
 * the stored memory uses different words. Query expansion generates synonyms,
 * related terms, and sub-queries to cast a wider net.
 *
 * Inspired by OpenClaw's memory/query-expansion.ts.
 */
object QueryExpansion {

    // Stop words to filter out (not useful for memory search)
    private val STOP_WORDS = setOf(
        "a", "an", "the", "is", "are", "was", "were", "be", "been", "being",
        "have", "has", "had", "do", "does", "did", "will", "would", "could",
        "should", "may", "might", "shall", "can", "need", "dare", "ought",
        "used", "to", "of", "in", "at", "on", "by", "for", "with", "about",
        "against", "between", "into", "through", "during", "before", "after",
        "above", "below", "from", "up", "down", "out", "off", "over", "under",
        "again", "then", "once", "here", "there", "when", "where", "why",
        "how", "all", "both", "each", "few", "more", "most", "other", "some",
        "such", "no", "not", "only", "own", "same", "so", "than", "too",
        "very", "just", "but", "and", "or", "nor", "as", "if", "i", "me",
        "my", "myself", "we", "our", "you", "your", "he", "she", "it", "they",
        "what", "which", "who", "whom", "this", "that", "these", "those"
    )

    // Common synonym groups for memory-relevant terms
    private val SYNONYMS = mapOf(
        "name" to listOf("called", "named", "title", "label"),
        "birthday" to listOf("birth date", "born", "date of birth", "dob"),
        "phone" to listOf("number", "mobile", "cell", "telephone", "contact"),
        "email" to listOf("mail", "address", "inbox"),
        "job" to listOf("work", "career", "profession", "occupation", "role", "position"),
        "live" to listOf("home", "address", "location", "city", "country", "reside"),
        "like" to listOf("prefer", "love", "enjoy", "favorite", "favourite"),
        "hate" to listOf("dislike", "avoid", "don't like"),
        "goal" to listOf("objective", "target", "aim", "plan", "want", "wish"),
        "remind" to listOf("remember", "don't forget", "note"),
        "password" to listOf("secret", "credential", "login"),
        "website" to listOf("url", "link", "site", "domain"),
        "buy" to listOf("purchase", "order", "get", "acquire"),
        "meeting" to listOf("appointment", "call", "event", "schedule"),
        "code" to listOf("program", "script", "function", "snippet")
    )

    /**
     * Expand a query into multiple related search terms.
     * Returns the original query plus extracted keywords and synonyms.
     */
    fun expand(query: String): List<String> {
        val queries = mutableListOf(query)
        val keywords = extractKeywords(query)

        // Add keyword-only query (strips stop words)
        if (keywords.size >= 2) {
            queries.add(keywords.joinToString(" "))
        }

        // Add synonym expansions
        for (keyword in keywords) {
            val synGroup = SYNONYMS.entries.find { (k, v) ->
                k.equals(keyword, ignoreCase = true) || v.any { it.equals(keyword, ignoreCase = true) }
            }
            if (synGroup != null) {
                // Build an expanded query replacing this keyword with synonyms
                val syns = (listOf(synGroup.key) + synGroup.value).filter {
                    !it.equals(keyword, ignoreCase = true)
                }.take(3)
                for (syn in syns) {
                    val expanded = query.replace(keyword, syn, ignoreCase = true)
                    if (expanded != query) queries.add(expanded)
                }
            }
        }

        // Add individual keywords as separate queries (single-keyword recall)
        for (keyword in keywords.filter { it.length >= 4 }) {
            queries.add(keyword)
        }

        return queries.distinct().take(8)
    }

    /**
     * Extract meaningful keywords from a query (removes stop words, short words).
     */
    fun extractKeywords(text: String): List<String> {
        return text.lowercase()
            .split(Regex("[\\s,.:;!?\"']+"))
            .filter { word ->
                word.length >= 3 && !STOP_WORDS.contains(word)
            }
            .distinct()
    }

    /**
     * Build an FTS-compatible query string for SQLite LIKE search.
     * Returns a list of LIKE patterns to OR together.
     */
    fun buildFtsPatterns(query: String): List<String> {
        val keywords = extractKeywords(query)
        val patterns = mutableListOf<String>()

        // Exact phrase
        patterns.add("%${query.take(100)}%")

        // Individual keywords
        for (kw in keywords) {
            patterns.add("%$kw%")
        }

        // Synonym terms
        for (kw in keywords) {
            SYNONYMS[kw.lowercase()]?.forEach { syn ->
                patterns.add("%$syn%")
            }
        }

        return patterns.distinct()
    }

    /**
     * Score how relevant a memory entry is to the query using keyword overlap.
     * Returns 0.0–1.0.
     */
    fun keywordScore(query: String, memoryText: String): Float {
        val queryKeywords = extractKeywords(query).toSet()
        val memKeywords = extractKeywords(memoryText).toSet()

        if (queryKeywords.isEmpty()) return 0f

        // Jaccard-like overlap
        val intersection = queryKeywords.intersect(memKeywords).size
        val union = queryKeywords.union(memKeywords).size
        val jaccard = intersection.toFloat() / union.toFloat()

        // Bonus for exact phrase match
        val phraseBonus = if (memoryText.contains(query, ignoreCase = true)) 0.3f else 0f

        return (jaccard + phraseBonus).coerceAtMost(1.0f)
    }
}

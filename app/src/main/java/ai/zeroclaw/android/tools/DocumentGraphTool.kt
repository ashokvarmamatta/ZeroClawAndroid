package ai.zeroclaw.android.tools

import android.content.Context
import ai.zeroclaw.android.data.*
import ai.zeroclaw.android.memory.VectorMemory
import ai.zeroclaw.android.service.ZeroClawService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.security.MessageDigest

/**
 * DocumentGraphTool — ingest PDFs/docs, build a knowledge graph, then answer questions.
 *
 * Actions:
 * - ingest:      Parse a file, extract entities + relationships, store in graph DB
 * - query:       Ask a question about an ingested document (RAG + graph traversal)
 * - entities:    List all entities/concepts extracted from a document
 * - connections: Show relationships for a specific entity
 * - list:        List all ingested documents
 * - summary:     Get a structured summary of a document's knowledge graph
 * - delete:      Remove a document and its graph data
 *
 * Supports: PDF, DOCX, DOC, TXT, MD, CSV, JSON, HTML
 */
class DocumentGraphTool(private val context: Context) : Tool {

    override val name = "document_graph"

    override val description = "Ingest PDFs or documents into a knowledge graph, then ask questions about them. " +
            "Actions: 'ingest' (parse file + build graph), 'query' (ask about a document), " +
            "'entities' (list extracted entities), 'connections' (relationships for an entity), " +
            "'list' (show all documents), 'summary' (document graph overview), 'delete' (remove a document). " +
            "After ingesting, users can ask anything about the document and get precise answers backed by the graph."

    override val parameters = listOf(
        ToolParam("action", "string", "One of: ingest, query, entities, connections, list, summary, delete"),
        ToolParam("source", "string", "File path, content:// URI, or URL (required for ingest)", required = false),
        ToolParam("doc_id", "string", "Document ID (from list action, used for query/entities/connections/summary/delete)", required = false),
        ToolParam("question", "string", "Question to ask about the document (for query action)", required = false),
        ToolParam("entity", "string", "Entity name to look up connections for (for connections action)", required = false),
        ToolParam("top_k", "string", "Number of results to return (default 5)", required = false)
    )

    private val db by lazy { DocumentGraphDatabase.getInstance(context) }
    private val dao by lazy { db.dao() }
    private val vectorMemory by lazy { VectorMemory.getInstance(context) }
    private val router by lazy { LlmRouter.getInstance(context) }

    override suspend fun execute(args: Map<String, String>): ToolResult {
        val action = args["action"]?.trim()?.lowercase()
            ?: return ToolResult(false, "", "Missing 'action'. Use: ingest, query, entities, connections, list, summary, delete.")

        return try {
            when (action) {
                "ingest" -> ingest(args)
                "query" -> query(args)
                "entities" -> entities(args)
                "connections" -> connections(args)
                "list" -> list()
                "summary" -> summary(args)
                "delete" -> delete(args)
                else -> ToolResult(false, "", "Unknown action '$action'. Use: ingest, query, entities, connections, list, summary, delete.")
            }
        } catch (e: Exception) {
            ZeroClawService.log("DocumentGraph: $action failed — ${e.message}")
            ToolResult(false, "", "Document graph error: ${e.message}")
        }
    }

    // ─── Ingest ──────────────────────────────────────────────────────────────

    private suspend fun ingest(args: Map<String, String>): ToolResult = withContext(Dispatchers.IO) {
        val source = args["source"]?.trim()
            ?: return@withContext ToolResult(false, "", "Missing 'source' parameter for ingest.")

        val docId = sha256(source)
        val existing = dao.getDocument(docId)
        if (existing != null) {
            return@withContext ToolResult(true, "Document '${existing.name}' is already ingested (${existing.totalNodes} entities, ${existing.totalEdges} relationships).\n\nDocument ID: $docId\n\nReady for questions!")
        }

        // Step 1: Extract text using existing tools
        val fileName = extractFileName(source)
        val fileType = fileName.substringAfterLast(".", "txt").lowercase()
        val text = extractText(source, fileType)
            ?: return@withContext ToolResult(false, "", "Could not extract text from: $source")

        if (text.isBlank()) {
            return@withContext ToolResult(false, "", "No text content found in $fileName. The file may be a scanned image.")
        }

        ZeroClawService.log("DocumentGraph: ingesting $fileName (${text.length} chars)")

        // Step 2: Chunk text
        val chunks = chunkText(text, CHUNK_SIZE)

        // Step 3: Store chunks with embeddings
        val chunkEntities = chunks.mapIndexed { i, chunk ->
            val embedding = try { vectorMemory.embed(chunk) } catch (_: Exception) { null }
            DocChunkEntity(
                chunkId = "${docId}_chunk_$i",
                docId = docId,
                chunkIndex = i,
                text = chunk,
                embedding = if (embedding != null) vectorMemory.serializeEmbedding(embedding) else ""
            )
        }
        // Step 4: Extract entities + relationships via LLM
        val extractionText = if (text.length > MAX_EXTRACTION_TEXT) text.take(MAX_EXTRACTION_TEXT) else text
        val graphData = extractGraph(extractionText, fileName)

        // Step 5: Build nodes and edges (deduplicate node IDs)
        val nodeIdSet = mutableSetOf<String>()
        val nodes = graphData.first.mapNotNull { (label, type, desc) ->
            val nodeId = "${docId}_${normalizeId(label)}"
            if (nodeIdSet.add(nodeId)) {
                GraphNodeEntity(
                    nodeId = nodeId,
                    docId = docId,
                    label = label,
                    nodeType = type,
                    description = desc
                )
            } else null
        }
        val edges = graphData.second.mapNotNull { (src, rel, tgt, ctx) ->
            val sourceId = "${docId}_${normalizeId(src)}"
            val targetId = "${docId}_${normalizeId(tgt)}"
            if (sourceId in nodeIdSet && targetId in nodeIdSet && sourceId != targetId) {
                GraphEdgeEntity(
                    edgeId = "${sourceId}_${normalizeId(rel)}_${targetId}",
                    docId = docId,
                    sourceId = sourceId,
                    targetId = targetId,
                    relation = rel,
                    context = ctx
                )
            } else null
        }

        // Step 6: Store in correct order — document first (parent), then children
        dao.insertDocument(DocumentEntity(
            docId = docId,
            name = fileName,
            source = source,
            fileType = fileType,
            totalChunks = chunks.size,
            totalNodes = nodes.size,
            totalEdges = edges.size,
            textPreview = text.take(200)
        ))
        dao.insertChunks(chunkEntities)
        if (nodes.isNotEmpty()) dao.insertNodes(nodes)
        if (edges.isNotEmpty()) dao.insertEdges(edges)

        // Generate embeddings for nodes in background
        for (node in nodes) {
            val emb = try { vectorMemory.embed("${node.label}: ${node.description}") } catch (_: Exception) { null }
            if (emb != null) {
                dao.insertNodes(listOf(node.copy(embedding = vectorMemory.serializeEmbedding(emb))))
            }
        }

        ZeroClawService.log("DocumentGraph: ingested $fileName — ${nodes.size} entities, ${edges.size} relationships, ${chunks.size} chunks")

        ToolResult(true, buildString {
            appendLine("Ingested '$fileName' into knowledge graph.")
            appendLine()
            appendLine("  Chunks:        ${chunks.size}")
            appendLine("  Entities:      ${nodes.size}")
            appendLine("  Relationships: ${edges.size}")
            appendLine("  Document ID:   $docId")
            appendLine()
            appendLine("Top entities: ${nodes.take(10).joinToString(", ") { "${it.label} (${it.nodeType})" }}")
            appendLine()
            appendLine("You can now ask questions about this document using action 'query' with doc_id '$docId'.")
        })
    }

    // ─── Query (RAG + Graph) ─────────────────────────────────────────────────

    private suspend fun query(args: Map<String, String>): ToolResult = withContext(Dispatchers.IO) {
        val question = args["question"]?.trim()
            ?: return@withContext ToolResult(false, "", "Missing 'question' parameter.")
        val topK = args["top_k"]?.toIntOrNull() ?: 5
        val docId = args["doc_id"]?.trim()

        // Find relevant chunks via semantic search
        val relevantChunks = findRelevantChunks(question, docId, topK)

        // Find relevant graph nodes
        val relevantNodes = dao.searchNodes(question)
        val nodeContext = if (relevantNodes.isNotEmpty()) {
            val nodeInfo = relevantNodes.take(5).map { node ->
                val edges = dao.getEdgesForNode(node.nodeId)
                val connections = edges.take(5).map { edge ->
                    val otherNodeId = if (edge.sourceId == node.nodeId) edge.targetId else edge.sourceId
                    val otherNode = dao.getNode(otherNodeId)
                    "${edge.relation} → ${otherNode?.label ?: "unknown"}"
                }
                "${node.label} (${node.nodeType}): ${node.description}" +
                    if (connections.isNotEmpty()) "\n  Connections: ${connections.joinToString("; ")}" else ""
            }
            "\n\nKnowledge Graph Context:\n${nodeInfo.joinToString("\n")}"
        } else ""

        if (relevantChunks.isEmpty() && relevantNodes.isEmpty()) {
            return@withContext ToolResult(true, "No relevant content found for: $question. Try a different question or check the document was ingested.")
        }

        // Build RAG context
        val chunkContext = relevantChunks.joinToString("\n\n---\n\n") { it.text }

        val prompt = buildString {
            appendLine("You are answering a question about a document using retrieved context.")
            appendLine("Answer ONLY based on the provided context. If the context doesn't contain enough info, say so.")
            appendLine()
            appendLine("=== Document Context ===")
            appendLine(chunkContext.take(MAX_CONTEXT_LENGTH))
            if (nodeContext.isNotEmpty()) {
                appendLine()
                appendLine("=== $nodeContext ===")
            }
            appendLine()
            appendLine("=== Question ===")
            appendLine(question)
        }

        val answer = router.extractOnly(prompt)
            ?: return@withContext ToolResult(true, buildString {
                appendLine("(LLM unavailable — returning raw context)")
                appendLine()
                appendLine("Relevant text chunks:")
                appendLine(chunkContext.take(3000))
                if (nodeContext.isNotEmpty()) appendLine(nodeContext)
            })

        ToolResult(true, answer.take(MAX_RESULT_LENGTH))
    }

    // ─── Entities ────────────────────────────────────────────────────────────

    private suspend fun entities(args: Map<String, String>): ToolResult = withContext(Dispatchers.IO) {
        val docId = resolveDocId(args) ?: return@withContext ToolResult(false, "", "Missing 'doc_id'. Use 'list' to see ingested documents.")
        val doc = dao.getDocument(docId) ?: return@withContext ToolResult(false, "", "Document not found: $docId")
        val topK = args["top_k"]?.toIntOrNull() ?: 20

        val nodes = dao.getTopNodes(docId, topK)
        if (nodes.isEmpty()) return@withContext ToolResult(true, "No entities extracted from '${doc.name}'.")

        ToolResult(true, buildString {
            appendLine("Entities in '${doc.name}' (${nodes.size} shown):")
            appendLine()
            for (node in nodes) {
                val edgeCount = dao.getEdgesForNode(node.nodeId).size
                appendLine("  ${node.label} [${node.nodeType}] — ${node.mentions} mention(s), $edgeCount connection(s)")
                if (node.description.isNotBlank()) appendLine("    ${node.description}")
            }
        })
    }

    // ─── Connections ─────────────────────────────────────────────────────────

    private suspend fun connections(args: Map<String, String>): ToolResult = withContext(Dispatchers.IO) {
        val entityName = args["entity"]?.trim()
            ?: return@withContext ToolResult(false, "", "Missing 'entity' parameter.")
        val docId = args["doc_id"]?.trim()

        val matchingNodes = dao.searchNodes(entityName)
            .let { nodes -> if (docId != null) nodes.filter { it.docId == docId } else nodes }

        if (matchingNodes.isEmpty()) {
            return@withContext ToolResult(true, "No entity matching '$entityName' found. Use 'entities' to see available entities.")
        }

        val node = matchingNodes.first()
        val edges = dao.getEdgesForNode(node.nodeId)
        val neighbors = dao.getNeighbors(node.nodeId)

        ToolResult(true, buildString {
            appendLine("${node.label} (${node.nodeType})")
            if (node.description.isNotBlank()) appendLine("  ${node.description}")
            appendLine()
            appendLine("Connections (${edges.size}):")
            for (edge in edges) {
                val otherNodeId = if (edge.sourceId == node.nodeId) edge.targetId else edge.sourceId
                val otherNode = neighbors.find { it.nodeId == otherNodeId }
                val direction = if (edge.sourceId == node.nodeId) "→" else "←"
                appendLine("  $direction ${edge.relation} → ${otherNode?.label ?: "unknown"} [${otherNode?.nodeType ?: ""}]")
                if (edge.context.isNotBlank()) appendLine("    Context: \"${edge.context.take(100)}\"")
            }
        })
    }

    // ─── List ────────────────────────────────────────────────────────────────

    private suspend fun list(): ToolResult = withContext(Dispatchers.IO) {
        val docs = dao.getAllDocuments()
        if (docs.isEmpty()) return@withContext ToolResult(true, "No documents ingested yet. Use action 'ingest' with a file path, URI, or URL.")

        ToolResult(true, buildString {
            appendLine("Ingested documents (${docs.size}):")
            appendLine()
            for (doc in docs) {
                appendLine("  [${doc.docId.take(8)}] ${doc.name} (${doc.fileType})")
                appendLine("    Entities: ${doc.totalNodes} | Relationships: ${doc.totalEdges} | Chunks: ${doc.totalChunks}")
                appendLine("    Preview: ${doc.textPreview.take(80)}...")
                appendLine()
            }
        })
    }

    // ─── Summary ─────────────────────────────────────────────────────────────

    private suspend fun summary(args: Map<String, String>): ToolResult = withContext(Dispatchers.IO) {
        val docId = resolveDocId(args) ?: return@withContext ToolResult(false, "", "Missing 'doc_id'. Use 'list' to see documents.")
        val doc = dao.getDocument(docId) ?: return@withContext ToolResult(false, "", "Document not found.")

        val nodes = dao.getNodes(docId)
        val edges = dao.getEdges(docId)

        // Group by type
        val byType = nodes.groupBy { it.nodeType }
        // Find most connected nodes (god nodes)
        val edgeCounts = mutableMapOf<String, Int>()
        for (edge in edges) {
            edgeCounts[edge.sourceId] = (edgeCounts[edge.sourceId] ?: 0) + 1
            edgeCounts[edge.targetId] = (edgeCounts[edge.targetId] ?: 0) + 1
        }
        val godNodes = nodes.sortedByDescending { edgeCounts[it.nodeId] ?: 0 }.take(5)

        ToolResult(true, buildString {
            appendLine("Knowledge Graph Summary: ${doc.name}")
            appendLine("═".repeat(50))
            appendLine()
            appendLine("Stats: ${nodes.size} entities, ${edges.size} relationships, ${doc.totalChunks} text chunks")
            appendLine()
            appendLine("Entity Types:")
            for ((type, typeNodes) in byType.entries.sortedByDescending { it.value.size }) {
                appendLine("  $type: ${typeNodes.size} (${typeNodes.take(5).joinToString(", ") { it.label }})")
            }
            appendLine()
            appendLine("Most Connected (God Nodes):")
            for (node in godNodes) {
                val count = edgeCounts[node.nodeId] ?: 0
                appendLine("  ${node.label} — $count connections [${node.nodeType}]")
            }
            appendLine()
            appendLine("Relationships:")
            val relCounts = edges.groupBy { it.relation }.mapValues { it.value.size }
            for ((rel, count) in relCounts.entries.sortedByDescending { it.value }) {
                appendLine("  $rel: $count")
            }
        })
    }

    // ─── Delete ──────────────────────────────────────────────────────────────

    private suspend fun delete(args: Map<String, String>): ToolResult = withContext(Dispatchers.IO) {
        val docId = resolveDocId(args) ?: return@withContext ToolResult(false, "", "Missing 'doc_id'. Use 'list' to see documents.")
        val doc = dao.getDocument(docId) ?: return@withContext ToolResult(false, "", "Document not found: $docId")

        dao.deleteDocument(docId) // CASCADE deletes chunks, nodes, edges
        ToolResult(true, "Deleted '${doc.name}' and its knowledge graph (${doc.totalNodes} entities, ${doc.totalEdges} relationships).")
    }

    // ─── Helpers ─────────────────────────────────────────────────────────────

    private fun extractText(source: String, fileType: String): String? {
        return try {
            val pdfTool = PdfReadTool(context)
            val docTool = DocReadTool(context)

            // Delegate to existing tools' internal extractors
            when (fileType) {
                "pdf" -> {
                    val result = kotlinx.coroutines.runBlocking {
                        pdfTool.execute(mapOf("source" to source))
                    }
                    if (result.success) result.content else null
                }
                "docx", "doc", "xlsx", "txt", "md", "csv", "json", "xml", "html" -> {
                    val result = kotlinx.coroutines.runBlocking {
                        docTool.execute(mapOf("source" to source))
                    }
                    if (result.success) result.content else null
                }
                else -> null
            }
        } catch (e: Exception) {
            ZeroClawService.log("DocumentGraph: text extraction failed — ${e.message}")
            null
        }
    }

    private suspend fun extractGraph(
        text: String,
        fileName: String
    ): Pair<List<Triple<String, String, String>>, List<List<String>>> {
        val prompt = buildString {
            appendLine("Extract entities and relationships from this document text.")
            appendLine("Return ONLY valid JSON, no other text.")
            appendLine()
            appendLine("Output format:")
            appendLine("""{"entities":[{"label":"Name","type":"person|technology|concept|organization|location|event|metric","description":"one line"}],"relationships":[{"source":"Name1","relation":"uses|created_by|related_to|depends_on|part_of|leads_to|measures|located_in|works_at|authored","target":"Name2","context":"source sentence"}]}""")
            appendLine()
            appendLine("Rules:")
            appendLine("- Extract 10-30 entities (most important concepts, people, technologies)")
            appendLine("- Extract 10-40 relationships between them")
            appendLine("- 'type' must be one of: person, technology, concept, organization, location, event, metric")
            appendLine("- 'relation' must be one of: uses, created_by, related_to, depends_on, part_of, leads_to, measures, located_in, works_at, authored")
            appendLine("- 'context' is the source sentence or phrase where you found this relationship")
            appendLine("- Be specific with entity labels (e.g. 'GraphQL' not 'technology')")
            appendLine()
            appendLine("=== Document: $fileName ===")
            appendLine(text)
        }

        val response = router.extractOnly(prompt) ?: return Pair(emptyList(), emptyList())

        return parseGraphJson(response)
    }

    private fun parseGraphJson(json: String): Pair<List<Triple<String, String, String>>, List<List<String>>> {
        val nodes = mutableListOf<Triple<String, String, String>>()
        val edges = mutableListOf<List<String>>()

        try {
            // Extract JSON from response (may have markdown fences)
            val cleaned = json
                .replace(Regex("```json\\s*"), "")
                .replace(Regex("```\\s*"), "")
                .trim()

            val obj = org.json.JSONObject(cleaned)

            val entities = obj.optJSONArray("entities") ?: return Pair(nodes, edges)
            for (i in 0 until entities.length()) {
                val e = entities.getJSONObject(i)
                nodes.add(Triple(
                    e.getString("label"),
                    e.optString("type", "concept"),
                    e.optString("description", "")
                ))
            }

            val rels = obj.optJSONArray("relationships") ?: return Pair(nodes, edges)
            for (i in 0 until rels.length()) {
                val r = rels.getJSONObject(i)
                edges.add(listOf(
                    r.getString("source"),
                    r.optString("relation", "related_to"),
                    r.getString("target"),
                    r.optString("context", "")
                ))
            }
        } catch (e: Exception) {
            ZeroClawService.log("DocumentGraph: JSON parse failed — ${e.message}")
        }

        return Pair(nodes, edges)
    }

    private suspend fun findRelevantChunks(query: String, docId: String?, topK: Int): List<DocChunkEntity> {
        // Try semantic search first
        val queryEmbedding = try { vectorMemory.embed(query) } catch (_: Exception) { null }

        val allChunks = if (docId != null) {
            dao.getChunksWithEmbeddings(docId)
        } else {
            // Search across all docs — get keyword matches
            dao.searchChunksByKeyword(query, topK * 2)
        }

        if (queryEmbedding != null && allChunks.isNotEmpty()) {
            // Rank by cosine similarity
            val scored = allChunks.mapNotNull { chunk ->
                val emb = vectorMemory.deserializeEmbedding(chunk.embedding) ?: return@mapNotNull null
                val score = vectorMemory.cosineSimilarity(queryEmbedding, emb)
                chunk to score
            }.sortedByDescending { it.second }
                .take(topK)
                .map { it.first }

            if (scored.isNotEmpty()) return scored
        }

        // Fallback: keyword search
        val keywordChunks = if (docId != null) {
            dao.getChunks(docId).filter { it.text.contains(query, ignoreCase = true) }
        } else {
            dao.searchChunksByKeyword(query, topK)
        }

        return keywordChunks.take(topK)
    }

    private fun resolveDocId(args: Map<String, String>): String? {
        val raw = args["doc_id"]?.trim() ?: return null
        return if (raw.length < 64) {
            // User might pass short prefix — try matching
            kotlinx.coroutines.runBlocking {
                dao.getAllDocuments().find { it.docId.startsWith(raw) }?.docId
            }
        } else raw
    }

    private fun chunkText(text: String, chunkSize: Int): List<String> {
        val chunks = mutableListOf<String>()
        val paragraphs = text.split(Regex("\n\\s*\n"))
        val current = StringBuilder()

        for (para in paragraphs) {
            if (current.length + para.length > chunkSize && current.isNotEmpty()) {
                chunks.add(current.toString().trim())
                current.clear()
            }
            current.appendLine(para)
        }
        if (current.isNotBlank()) chunks.add(current.toString().trim())

        return chunks.filter { it.length >= MIN_CHUNK_SIZE }
    }

    private fun extractFileName(source: String): String {
        return when {
            source.startsWith("http") -> source.substringAfterLast("/").substringBefore("?").take(60)
            source.startsWith("content://") -> "document"
            else -> source.substringAfterLast("/").substringAfterLast("\\")
        }
    }

    private fun normalizeId(label: String): String {
        return label.lowercase().replace(Regex("[^a-z0-9]"), "_").take(50)
    }

    private fun sha256(input: String): String {
        return MessageDigest.getInstance("SHA-256")
            .digest(input.toByteArray())
            .joinToString("") { "%02x".format(it) }
    }

    companion object {
        private const val CHUNK_SIZE = 800
        private const val MIN_CHUNK_SIZE = 30
        private const val MAX_EXTRACTION_TEXT = 12000
        private const val MAX_CONTEXT_LENGTH = 6000
        private const val MAX_RESULT_LENGTH = 5000
    }
}

package ai.zeroclaw.android.data

import android.content.Context
import androidx.room.*

// ─── Entities ────────────────────────────────────────────────────────────────

/** A document that has been ingested into the knowledge graph. */
@Entity(tableName = "documents")
data class DocumentEntity(
    @PrimaryKey val docId: String,           // SHA-256 of source path/URI
    val name: String,                         // display name (filename)
    val source: String,                       // file path, content URI, or URL
    val fileType: String,                     // "pdf", "docx", "txt", "md", etc.
    val totalChunks: Int = 0,
    val totalNodes: Int = 0,
    val totalEdges: Int = 0,
    val textPreview: String = "",             // first 200 chars of extracted text
    val ingestedAt: Long = System.currentTimeMillis()
)

/** A text chunk stored with its embedding for semantic search. */
@Entity(
    tableName = "doc_chunks",
    foreignKeys = [ForeignKey(
        entity = DocumentEntity::class,
        parentColumns = ["docId"],
        childColumns = ["docId"],
        onDelete = ForeignKey.CASCADE
    )],
    indices = [Index("docId")]
)
data class DocChunkEntity(
    @PrimaryKey val chunkId: String,          // docId + "_chunk_" + index
    val docId: String,
    val chunkIndex: Int,
    val text: String,
    val embedding: String = "",               // JSON float array
    val pageNumber: Int = 0,                  // source page (PDFs)
    val createdAt: Long = System.currentTimeMillis()
)

/** A node (entity/concept) extracted from a document. */
@Entity(
    tableName = "graph_nodes",
    foreignKeys = [ForeignKey(
        entity = DocumentEntity::class,
        parentColumns = ["docId"],
        childColumns = ["docId"],
        onDelete = ForeignKey.CASCADE
    )],
    indices = [Index("docId"), Index("label")]
)
data class GraphNodeEntity(
    @PrimaryKey val nodeId: String,           // docId + "_" + normalized_label
    val docId: String,
    val label: String,                        // "GraphQL", "Alice", "authentication"
    val nodeType: String,                     // "person", "technology", "concept", "org", "location"
    val description: String = "",
    val mentions: Int = 1,                    // how many times mentioned
    val embedding: String = "",               // JSON float array for semantic search
    val createdAt: Long = System.currentTimeMillis()
)

/** A relationship (edge) between two nodes. */
@Entity(
    tableName = "graph_edges",
    foreignKeys = [
        ForeignKey(entity = GraphNodeEntity::class, parentColumns = ["nodeId"], childColumns = ["sourceId"], onDelete = ForeignKey.CASCADE),
        ForeignKey(entity = GraphNodeEntity::class, parentColumns = ["nodeId"], childColumns = ["targetId"], onDelete = ForeignKey.CASCADE)
    ],
    indices = [Index("sourceId"), Index("targetId"), Index("docId")]
)
data class GraphEdgeEntity(
    @PrimaryKey val edgeId: String,           // sourceId + "_" + relation + "_" + targetId
    val docId: String,
    val sourceId: String,
    val targetId: String,
    val relation: String,                     // "uses", "created_by", "related_to", "depends_on", etc.
    val confidence: String = "EXTRACTED",      // EXTRACTED, INFERRED
    val context: String = "",                 // source sentence
    val createdAt: Long = System.currentTimeMillis()
)

// ─── DAO ─────────────────────────────────────────────────────────────────────

@Dao
interface DocumentGraphDao {
    // ── Documents ──
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertDocument(doc: DocumentEntity)

    @Query("SELECT * FROM documents ORDER BY ingestedAt DESC")
    suspend fun getAllDocuments(): List<DocumentEntity>

    @Query("SELECT * FROM documents WHERE docId = :docId")
    suspend fun getDocument(docId: String): DocumentEntity?

    @Query("DELETE FROM documents WHERE docId = :docId")
    suspend fun deleteDocument(docId: String)

    @Query("SELECT COUNT(*) FROM documents")
    suspend fun documentCount(): Int

    // ── Chunks ──
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertChunks(chunks: List<DocChunkEntity>)

    @Query("SELECT * FROM doc_chunks WHERE docId = :docId ORDER BY chunkIndex")
    suspend fun getChunks(docId: String): List<DocChunkEntity>

    @Query("SELECT * FROM doc_chunks WHERE docId = :docId AND embedding != ''")
    suspend fun getChunksWithEmbeddings(docId: String): List<DocChunkEntity>

    @Query("SELECT * FROM doc_chunks WHERE text LIKE '%' || :query || '%' ORDER BY chunkIndex LIMIT :limit")
    suspend fun searchChunksByKeyword(query: String, limit: Int = 10): List<DocChunkEntity>

    // ── Nodes ──
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertNodes(nodes: List<GraphNodeEntity>)

    @Query("SELECT * FROM graph_nodes WHERE docId = :docId ORDER BY mentions DESC")
    suspend fun getNodes(docId: String): List<GraphNodeEntity>

    @Query("SELECT * FROM graph_nodes WHERE docId = :docId ORDER BY mentions DESC LIMIT :limit")
    suspend fun getTopNodes(docId: String, limit: Int = 20): List<GraphNodeEntity>

    @Query("SELECT * FROM graph_nodes WHERE label LIKE '%' || :query || '%' OR description LIKE '%' || :query || '%'")
    suspend fun searchNodes(query: String): List<GraphNodeEntity>

    @Query("SELECT * FROM graph_nodes WHERE nodeId = :nodeId")
    suspend fun getNode(nodeId: String): GraphNodeEntity?

    @Query("SELECT COUNT(*) FROM graph_nodes WHERE docId = :docId")
    suspend fun nodeCount(docId: String): Int

    // ── Edges ──
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertEdges(edges: List<GraphEdgeEntity>)

    @Query("SELECT * FROM graph_edges WHERE docId = :docId")
    suspend fun getEdges(docId: String): List<GraphEdgeEntity>

    @Query("SELECT * FROM graph_edges WHERE sourceId = :nodeId OR targetId = :nodeId")
    suspend fun getEdgesForNode(nodeId: String): List<GraphEdgeEntity>

    @Query("SELECT COUNT(*) FROM graph_edges WHERE docId = :docId")
    suspend fun edgeCount(docId: String): Int

    // ── Graph traversal ──
    @Query("""
        SELECT n.* FROM graph_nodes n
        INNER JOIN graph_edges e ON (e.targetId = n.nodeId AND e.sourceId = :nodeId)
           OR (e.sourceId = n.nodeId AND e.targetId = :nodeId)
        WHERE n.nodeId != :nodeId
    """)
    suspend fun getNeighbors(nodeId: String): List<GraphNodeEntity>
}

// ─── Database ────────────────────────────────────────────────────────────────

@Database(
    entities = [DocumentEntity::class, DocChunkEntity::class, GraphNodeEntity::class, GraphEdgeEntity::class],
    version = 1,
    exportSchema = false
)
abstract class DocumentGraphDatabase : RoomDatabase() {
    abstract fun dao(): DocumentGraphDao

    companion object {
        @Volatile private var INSTANCE: DocumentGraphDatabase? = null

        fun getInstance(context: Context): DocumentGraphDatabase {
            return INSTANCE ?: synchronized(this) {
                Room.databaseBuilder(
                    context.applicationContext,
                    DocumentGraphDatabase::class.java,
                    "zeroclaw_doc_graph"
                ).fallbackToDestructiveMigration().build().also { INSTANCE = it }
            }
        }
    }
}

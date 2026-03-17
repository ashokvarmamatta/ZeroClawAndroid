package ai.zeroclaw.android.tools

import android.content.Context
import androidx.room.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * BookmarkTool — save, list, search bookmarks per user.
 *
 * Inspired by OpenClaw's bookmark skill.
 * Uses Room database for persistent per-user bookmarks.
 * Actions: save, list, search, delete.
 */
class BookmarkTool(private val context: Context) : Tool {

    override val name = "bookmark"

    override val description = "Save, list, search, and delete bookmarks. " +
            "Each user has their own bookmarks. " +
            "Actions: 'save' (add a bookmark), 'list' (show all bookmarks), " +
            "'search' (find bookmarks by keyword), 'delete' (remove a bookmark). " +
            "No API key needed — stored locally on device."

    override val parameters = listOf(
        ToolParam("action", "string", "One of: save, list, search, delete. Default: list.", required = false),
        ToolParam("url", "string", "URL to bookmark (for 'save')", required = false),
        ToolParam("title", "string", "Bookmark title/name (for 'save')", required = false),
        ToolParam("tags", "string", "Comma-separated tags (for 'save')", required = false),
        ToolParam("query", "string", "Search keyword (for 'search')", required = false),
        ToolParam("id", "string", "Bookmark ID to delete (for 'delete')", required = false),
        ToolParam("user_id", "string", "User identifier (auto-set from chat context)", required = false)
    )

    override suspend fun execute(args: Map<String, String>): ToolResult {
        val action = args["action"]?.trim()?.lowercase() ?: "list"
        val userId = args["user_id"]?.trim() ?: "default"

        return withContext(Dispatchers.IO) {
            try {
                val db = BookmarkDatabase.getInstance(context)
                val dao = db.bookmarkDao()

                when (action) {
                    "save" -> saveBookmark(dao, userId, args)
                    "list" -> listBookmarks(dao, userId)
                    "search" -> {
                        val query = args["query"]?.trim()
                            ?: return@withContext ToolResult(false, "", "Missing 'query' parameter for search")
                        searchBookmarks(dao, userId, query)
                    }
                    "delete" -> {
                        val id = args["id"]?.trim()?.toLongOrNull()
                            ?: return@withContext ToolResult(false, "", "Missing or invalid 'id' parameter for delete")
                        deleteBookmark(dao, userId, id)
                    }
                    else -> ToolResult(false, "", "Unknown action: $action. Use: save, list, search, delete.")
                }
            } catch (e: Exception) {
                ToolResult(false, "", "Bookmark error: ${e.message}")
            }
        }
    }

    private suspend fun saveBookmark(dao: BookmarkDao, userId: String, args: Map<String, String>): ToolResult {
        val url = args["url"]?.trim()
        val title = args["title"]?.trim()

        if (url.isNullOrBlank() && title.isNullOrBlank()) {
            return ToolResult(false, "", "Provide at least a 'url' or 'title' to save a bookmark")
        }

        val tags = args["tags"]?.trim() ?: ""

        // Check for duplicate URL
        if (!url.isNullOrBlank()) {
            val existing = dao.findByUrl(userId, url)
            if (existing != null) {
                // Update existing
                val updated = existing.copy(
                    title = title ?: existing.title,
                    tags = tags.ifBlank { existing.tags },
                    updatedAt = System.currentTimeMillis()
                )
                dao.update(updated)
                return ToolResult(true, "✅ Bookmark updated (ID: ${existing.id}):\n" +
                        "Title: ${updated.title}\nURL: ${updated.url}\nTags: ${updated.tags.ifBlank { "(none)" }}")
            }
        }

        val bookmark = BookmarkEntity(
            userId = userId,
            url = url ?: "",
            title = title ?: url ?: "Untitled",
            tags = tags,
            createdAt = System.currentTimeMillis(),
            updatedAt = System.currentTimeMillis()
        )

        val id = dao.insert(bookmark)
        val sb = StringBuilder("✅ Bookmark saved (ID: $id)!\n\n")
        sb.appendLine("Title: ${bookmark.title}")
        if (bookmark.url.isNotBlank()) sb.appendLine("URL: ${bookmark.url}")
        if (tags.isNotBlank()) sb.appendLine("Tags: $tags")

        return ToolResult(true, sb.toString())
    }

    private suspend fun listBookmarks(dao: BookmarkDao, userId: String): ToolResult {
        val bookmarks = dao.getAllForUser(userId)

        if (bookmarks.isEmpty()) {
            return ToolResult(true, "No bookmarks saved yet. Use action 'save' to add one.")
        }

        val sb = StringBuilder("🔖 Bookmarks (${bookmarks.size})\n${"═".repeat(40)}\n\n")

        for (bm in bookmarks.take(30)) {
            sb.appendLine("${bm.id}. ${bm.title}")
            if (bm.url.isNotBlank()) sb.appendLine("   🔗 ${bm.url}")
            if (bm.tags.isNotBlank()) sb.appendLine("   🏷️ ${bm.tags}")
            val date = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).format(java.util.Date(bm.createdAt))
            sb.appendLine("   📅 $date")
            sb.appendLine()
        }

        if (bookmarks.size > 30) {
            sb.appendLine("... and ${bookmarks.size - 30} more bookmarks")
        }

        return ToolResult(true, sb.toString().take(4000))
    }

    private suspend fun searchBookmarks(dao: BookmarkDao, userId: String, query: String): ToolResult {
        if (query.isBlank()) return ToolResult(false, "", "Search query cannot be empty")

        val results = dao.search(userId, query)

        if (results.isEmpty()) {
            return ToolResult(true, "No bookmarks matching \"$query\".")
        }

        val sb = StringBuilder("🔖 Bookmarks matching \"$query\" (${results.size})\n${"═".repeat(40)}\n\n")

        for (bm in results.take(20)) {
            sb.appendLine("${bm.id}. ${bm.title}")
            if (bm.url.isNotBlank()) sb.appendLine("   🔗 ${bm.url}")
            if (bm.tags.isNotBlank()) sb.appendLine("   🏷️ ${bm.tags}")
            sb.appendLine()
        }

        return ToolResult(true, sb.toString().take(4000))
    }

    private suspend fun deleteBookmark(dao: BookmarkDao, userId: String, id: Long): ToolResult {
        val bookmark = dao.getById(id)
        if (bookmark == null || bookmark.userId != userId) {
            return ToolResult(false, "", "Bookmark not found (ID: $id)")
        }

        dao.delete(bookmark)
        return ToolResult(true, "✅ Bookmark deleted (ID: $id): ${bookmark.title}")
    }
}

// ── Room Database for Bookmarks ──────────────────────────────────────────────

@Entity(tableName = "bookmarks")
data class BookmarkEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val userId: String,
    val url: String,
    val title: String,
    val tags: String = "",
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)

@Dao
interface BookmarkDao {
    @Query("SELECT * FROM bookmarks WHERE userId = :userId ORDER BY createdAt DESC")
    suspend fun getAllForUser(userId: String): List<BookmarkEntity>

    @Query("SELECT * FROM bookmarks WHERE id = :id LIMIT 1")
    suspend fun getById(id: Long): BookmarkEntity?

    @Query("SELECT * FROM bookmarks WHERE userId = :userId AND url = :url LIMIT 1")
    suspend fun findByUrl(userId: String, url: String): BookmarkEntity?

    @Query("SELECT * FROM bookmarks WHERE userId = :userId AND (title LIKE '%' || :query || '%' OR url LIKE '%' || :query || '%' OR tags LIKE '%' || :query || '%') ORDER BY createdAt DESC")
    suspend fun search(userId: String, query: String): List<BookmarkEntity>

    @Insert
    suspend fun insert(bookmark: BookmarkEntity): Long

    @Update
    suspend fun update(bookmark: BookmarkEntity)

    @Delete
    suspend fun delete(bookmark: BookmarkEntity)

    @Query("SELECT COUNT(*) FROM bookmarks WHERE userId = :userId")
    suspend fun countForUser(userId: String): Int
}

@Database(entities = [BookmarkEntity::class], version = 1, exportSchema = false)
abstract class BookmarkDatabase : RoomDatabase() {
    abstract fun bookmarkDao(): BookmarkDao

    companion object {
        @Volatile private var INSTANCE: BookmarkDatabase? = null

        fun getInstance(context: Context): BookmarkDatabase {
            return INSTANCE ?: synchronized(this) {
                Room.databaseBuilder(
                    context.applicationContext,
                    BookmarkDatabase::class.java,
                    "zeroclaw_bookmarks"
                ).fallbackToDestructiveMigration().build().also { INSTANCE = it }
            }
        }
    }
}

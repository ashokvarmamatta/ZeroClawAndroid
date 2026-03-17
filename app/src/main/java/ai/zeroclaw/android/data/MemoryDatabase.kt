package ai.zeroclaw.android.data

import android.content.Context
import androidx.room.*

@Entity(tableName = "memories")
data class MemoryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val userId: String,          // chat/user ID (e.g. telegram user ID)
    val key: String,             // memory key/label
    val value: String,           // memory content
    val embedding: String = "",  // JSON float array for vector search (Phase 118)
    val sessionId: String = "",  // named session (Phase 122)
    val tags: String = "",       // comma-separated tags for filtering
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)

@Dao
interface MemoryDao {
    @Query("SELECT * FROM memories WHERE userId = :userId ORDER BY updatedAt DESC")
    suspend fun getAllForUser(userId: String): List<MemoryEntity>

    @Query("SELECT * FROM memories WHERE userId = :userId AND `key` = :key LIMIT 1")
    suspend fun getByKey(userId: String, key: String): MemoryEntity?

    @Query("SELECT * FROM memories WHERE userId = :userId AND (`key` LIKE '%' || :query || '%' OR value LIKE '%' || :query || '%') ORDER BY updatedAt DESC")
    suspend fun search(userId: String, query: String): List<MemoryEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(memory: MemoryEntity)

    @Query("DELETE FROM memories WHERE userId = :userId AND `key` = :key")
    suspend fun deleteByKey(userId: String, key: String)

    @Query("DELETE FROM memories WHERE userId = :userId")
    suspend fun deleteAllForUser(userId: String)

    @Query("SELECT COUNT(*) FROM memories WHERE userId = :userId")
    suspend fun countForUser(userId: String): Int

    @Query("SELECT * FROM memories WHERE userId = :userId AND sessionId = :sessionId ORDER BY updatedAt DESC")
    suspend fun getAllForSession(userId: String, sessionId: String): List<MemoryEntity>

    @Query("SELECT * FROM memories WHERE userId = :userId AND embedding != '' ORDER BY updatedAt DESC")
    suspend fun getAllWithEmbeddings(userId: String): List<MemoryEntity>

    @Query("SELECT DISTINCT sessionId FROM memories WHERE userId = :userId AND sessionId != ''")
    suspend fun getSessionIds(userId: String): List<String>
}

@Database(entities = [MemoryEntity::class], version = 2, exportSchema = false)
abstract class MemoryDatabase : RoomDatabase() {
    abstract fun memoryDao(): MemoryDao

    companion object {
        @Volatile private var INSTANCE: MemoryDatabase? = null

        fun getInstance(context: Context): MemoryDatabase {
            return INSTANCE ?: synchronized(this) {
                Room.databaseBuilder(
                    context.applicationContext,
                    MemoryDatabase::class.java,
                    "zeroclaw_memory"
                ).fallbackToDestructiveMigration().build().also { INSTANCE = it }
            }
        }
    }
}

package ai.zeroclaw.android.data

import android.content.Context
import androidx.room.*
import kotlinx.coroutines.flow.Flow

/**
 * AgentResultDatabase — persists every agent run result.
 *
 * Phase 175: Stores raw + extracted content, delivery status, errors.
 * Results are queryable via local API (:8088) and tunnel.
 */

@Entity(
    tableName = "agent_results",
    indices = [
        Index(value = ["agentId"]),
        Index(value = ["timestamp"]),
        Index(value = ["agentId", "timestamp"])
    ]
)
data class AgentResultEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val agentId: String,              // FK to AgentConfig.id
    val agentName: String,            // Snapshot of agent name at run time
    val runId: String,                // UUID per run
    val timestamp: Long = System.currentTimeMillis(),
    val status: String,               // "success" | "partial" | "failed" | "skipped"
    val url: String,                  // URL that was fetched
    val usedApi: Boolean = false,     // true if free API was used instead of web scrape
    val rawContent: String = "",      // Raw fetched content (truncated to 5000 chars)
    val extractedContent: String = "",// LLM-extracted content (what was delivered)
    val deliveredTo: String = "",     // JSON array of delivery targets e.g. ["telegram","discord"]
    val errorMessage: String = "",    // Error details if status != success
    val contentHash: Int = 0          // Hash for change detection
)

@Dao
interface AgentResultDao {

    @Query("SELECT * FROM agent_results ORDER BY timestamp DESC LIMIT :limit OFFSET :offset")
    suspend fun getAll(limit: Int = 100, offset: Int = 0): List<AgentResultEntity>

    @Query("SELECT * FROM agent_results WHERE agentId = :agentId ORDER BY timestamp DESC LIMIT :limit OFFSET :offset")
    suspend fun getByAgentId(agentId: String, limit: Int = 100, offset: Int = 0): List<AgentResultEntity>

    @Query("SELECT * FROM agent_results WHERE id = :id LIMIT 1")
    suspend fun getById(id: Long): AgentResultEntity?

    @Insert
    suspend fun insert(result: AgentResultEntity): Long

    @Query("DELETE FROM agent_results WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("DELETE FROM agent_results WHERE agentId = :agentId")
    suspend fun deleteByAgentId(agentId: String)

    @Query("DELETE FROM agent_results WHERE timestamp < :before")
    suspend fun deleteOlderThan(before: Long)

    @Query("SELECT COUNT(*) FROM agent_results")
    suspend fun count(): Int

    @Query("SELECT COUNT(*) FROM agent_results WHERE agentId = :agentId")
    suspend fun countByAgent(agentId: String): Int

    @Query("SELECT * FROM agent_results ORDER BY timestamp DESC LIMIT :limit")
    fun observeRecent(limit: Int = 50): Flow<List<AgentResultEntity>>

    @Query("SELECT DISTINCT agentId, agentName FROM agent_results ORDER BY agentName")
    suspend fun getDistinctAgents(): List<AgentSummary>
}

data class AgentSummary(
    val agentId: String,
    val agentName: String
)

@Database(entities = [AgentResultEntity::class], version = 1, exportSchema = false)
abstract class AgentResultDatabase : RoomDatabase() {
    abstract fun agentResultDao(): AgentResultDao

    companion object {
        @Volatile private var INSTANCE: AgentResultDatabase? = null

        fun getInstance(context: Context): AgentResultDatabase {
            return INSTANCE ?: synchronized(this) {
                Room.databaseBuilder(
                    context.applicationContext,
                    AgentResultDatabase::class.java,
                    "zeroclaw_agent_results"
                ).fallbackToDestructiveMigration().build().also { INSTANCE = it }
            }
        }
    }
}

package ai.zeroclaw.android.data

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "messages")
data class MessageEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val channel: String,        // "telegram" or "whatsapp"
    val direction: String,      // "in" or "out"
    val sender: String,
    val content: String,
    val timestamp: Long = System.currentTimeMillis()
)

@Dao
interface MessageDao {
    @Query("SELECT * FROM messages ORDER BY timestamp DESC LIMIT 100")
    fun getRecentMessages(): Flow<List<MessageEntity>>

    @Insert
    suspend fun insert(message: MessageEntity)

    @Query("DELETE FROM messages WHERE timestamp < :before")
    suspend fun deleteOlderThan(before: Long)

    @Query("SELECT COUNT(*) FROM messages")
    suspend fun count(): Int
}

@Database(entities = [MessageEntity::class], version = 1, exportSchema = false)
abstract class MessageDatabase : RoomDatabase() {
    abstract fun messageDao(): MessageDao

    companion object {
        @Volatile private var INSTANCE: MessageDatabase? = null

        fun getInstance(context: android.content.Context): MessageDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    MessageDatabase::class.java,
                    "zeroclaw_messages"
                ).fallbackToDestructiveMigration().build()
                INSTANCE = instance
                instance
            }
        }
    }
}

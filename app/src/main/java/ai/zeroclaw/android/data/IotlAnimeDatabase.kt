package ai.zeroclaw.android.data

import android.content.Context
import androidx.room.*

/**
 * IotlAnimeDatabase — persists generated lists published from VideoGen.
 *
 * Stores JSON content (anime lists, facts, etc.) that are exposed via
 * GET /api/iotlanime for global consumption through Cloudflare Tunnel.
 */

@Entity(
    tableName = "iotl_anime",
    indices = [Index(value = ["timestamp"])]
)
data class IotlAnimeEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,                  // prompt/title used for generation
    val jsonContent: String,            // the generated JSON list
    val timestamp: Long = System.currentTimeMillis(),
    val syncedToCloud: Boolean = false  // future: Cloudflare sync status
)

@Dao
interface IotlAnimeDao {

    @Query("SELECT * FROM iotl_anime ORDER BY timestamp DESC")
    suspend fun getAll(): List<IotlAnimeEntity>

    @Insert
    suspend fun insert(entry: IotlAnimeEntity): Long

    @Query("DELETE FROM iotl_anime WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("DELETE FROM iotl_anime")
    suspend fun deleteAll()

    @Query("SELECT COUNT(*) FROM iotl_anime")
    suspend fun count(): Int
}

@Database(entities = [IotlAnimeEntity::class], version = 1, exportSchema = false)
abstract class IotlAnimeDatabase : RoomDatabase() {
    abstract fun iotlAnimeDao(): IotlAnimeDao

    companion object {
        @Volatile private var INSTANCE: IotlAnimeDatabase? = null

        fun getInstance(context: Context): IotlAnimeDatabase {
            return INSTANCE ?: synchronized(this) {
                Room.databaseBuilder(
                    context.applicationContext,
                    IotlAnimeDatabase::class.java,
                    "zeroclaw_iotl_anime"
                ).fallbackToDestructiveMigration().build().also { INSTANCE = it }
            }
        }
    }
}

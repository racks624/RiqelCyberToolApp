package com.riqel.cybertool

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "command_queue")
data class QueuedCommand(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val commandId: String,
    val action: String,
    val args: String,  // JSON string
    val issuedAt: Long,
    val status: String  // "pending", "done", "failed"
)

@Dao
interface CommandDao {
    @Query("SELECT * FROM command_queue WHERE status = 'pending' ORDER BY issuedAt ASC")
    fun getPendingCommands(): Flow<List<QueuedCommand>>

    @Insert
    suspend fun insert(command: QueuedCommand)

    @Update
    suspend fun update(command: QueuedCommand)

    @Query("DELETE FROM command_queue WHERE status IN ('done', 'failed')")
    suspend fun cleanOldCommands()
}

@Database(entities = [QueuedCommand::class], version = 1)
abstract class AppDatabase : RoomDatabase() {
    abstract fun commandDao(): CommandDao
}

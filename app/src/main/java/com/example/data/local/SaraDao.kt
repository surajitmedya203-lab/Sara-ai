package com.example.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface SaraDao {
    // Tasks
    @Query("SELECT * FROM personal_tasks ORDER BY isCompleted ASC, createdAt DESC")
    fun observeTasks(): Flow<List<PersonalTaskEntity>>

    @Query("SELECT * FROM personal_tasks WHERE isCompleted = 0 ORDER BY createdAt DESC")
    suspend fun getPendingTasksOnce(): List<PersonalTaskEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertTask(task: PersonalTaskEntity): Long

    @Query("UPDATE personal_tasks SET isCompleted = :completed WHERE id = :taskId")
    suspend fun setTaskCompleted(taskId: Long, completed: Boolean)

    @Query("DELETE FROM personal_tasks WHERE id = :taskId")
    suspend fun deleteTask(taskId: Long)

    // Memories
    @Query("SELECT * FROM memory_notes ORDER BY createdAt DESC")
    fun observeMemories(): Flow<List<MemoryNoteEntity>>

    @Query("SELECT * FROM memory_notes ORDER BY createdAt DESC")
    suspend fun getAllMemoriesOnce(): List<MemoryNoteEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMemory(memory: MemoryNoteEntity): Long

    @Query("DELETE FROM memory_notes WHERE id = :memoryId")
    suspend fun deleteMemory(memoryId: Long)

    // Command Logs
    @Query("SELECT * FROM command_logs ORDER BY timestamp DESC LIMIT 50")
    fun observeCommandLogs(): Flow<List<CommandLogEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertCommandLog(log: CommandLogEntity): Long

    @Query("DELETE FROM command_logs")
    suspend fun clearCommandLogs()

    // Routines
    @Query("SELECT * FROM routines ORDER BY id ASC")
    fun observeRoutines(): Flow<List<RoutineEntity>>

    @Query("SELECT * FROM routines ORDER BY id ASC")
    suspend fun getRoutinesOnce(): List<RoutineEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertRoutine(routine: RoutineEntity): Long

    @Query("UPDATE routines SET lastRunAt = :timestamp WHERE id = :routineId")
    suspend fun markRoutineRun(routineId: Long, timestamp: Long)

    @Query("UPDATE routines SET isEnabled = :enabled WHERE id = :routineId")
    suspend fun setRoutineEnabled(routineId: Long, enabled: Boolean)
}

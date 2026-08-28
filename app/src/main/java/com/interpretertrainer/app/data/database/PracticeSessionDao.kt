package com.interpretertrainer.app.data.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface PracticeSessionDao {
    @Query("SELECT * FROM practice_sessions ORDER BY startedAt DESC")
    fun observeAll(): Flow<List<PracticeSessionEntity>>

    @Query("SELECT * FROM practice_sessions WHERE id = :id LIMIT 1")
    fun observeById(id: Long): Flow<PracticeSessionEntity?>

    @Query("SELECT * FROM practice_sessions WHERE id = :id LIMIT 1")
    suspend fun findById(id: Long): PracticeSessionEntity?

    @Query("SELECT recordingPath FROM practice_sessions WHERE recordingPath IS NOT NULL")
    suspend fun listRecordingPaths(): List<String?>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(session: PracticeSessionEntity): Long

    @Query("DELETE FROM practice_sessions WHERE id = :id")
    suspend fun deleteById(id: Long)
}

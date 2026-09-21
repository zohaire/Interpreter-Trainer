package com.interpretertrainer.app.data.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface PracticeLibraryDao {
    @Query("SELECT * FROM practice_library ORDER BY isOfficial DESC, createdAt DESC")
    fun observeAll(): Flow<List<PracticeLibraryEntity>>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertCurated(items: List<PracticeLibraryEntity>)

    @Upsert
    suspend fun upsert(item: PracticeLibraryEntity)

    @Query("UPDATE practice_library SET isFavorite = :favorite WHERE id = :id")
    suspend fun setFavorite(id: String, favorite: Boolean)

    @Query("UPDATE practice_library SET lastPracticedAt = :at WHERE id = :id")
    suspend fun markOpened(id: String, at: Long)

    @Query("UPDATE practice_library SET progressMillis = :progressMillis, completionCount = completionCount + :completionIncrement, lastPracticedAt = :at WHERE id = :id")
    suspend fun updateProgress(id: String, progressMillis: Long, completionIncrement: Int, at: Long)

    @Query("DELETE FROM practice_library WHERE id = :id AND isOfficial = 0")
    suspend fun deletePersonal(id: String)
}

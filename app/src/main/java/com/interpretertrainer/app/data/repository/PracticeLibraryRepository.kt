package com.interpretertrainer.app.data.repository

import com.interpretertrainer.app.data.database.PracticeLibraryDao
import com.interpretertrainer.app.data.database.PracticeLibraryEntity
import kotlinx.coroutines.flow.Flow
import java.util.UUID

class PracticeLibraryRepository(private val dao: PracticeLibraryDao) {
    fun observeAll(): Flow<List<PracticeLibraryEntity>> = dao.observeAll()

    suspend fun seedCuratedCatalog() = dao.insertCurated(CuratedPracticeCatalog.items)

    suspend fun importPersonal(
        title: String,
        mediaUri: String?,
        sourceUrl: String?,
        language: String = "English",
        field: String = "Personal library"
    ) {
        dao.upsert(
            PracticeLibraryEntity(
                id = "personal-${UUID.randomUUID()}",
                title = title.ifBlank { "Imported practice source" },
                speaker = "User-provided source",
                institutionEvent = "Personal practice material",
                eventDate = "Added today",
                language = language,
                field = field,
                topic = "Personal practice",
                difficulty = "Custom",
                speakingSpeed = "Original",
                interpretationTypes = "Simultaneous,Consecutive,Shadowing,Transcription",
                durationMillis = 4 * 60 * 1000L,
                sourceUrl = sourceUrl,
                mediaUri = mediaUri,
                sourceLabel = if (mediaUri != null) "On-device media" else "User link",
                isOfficial = false,
                createdAt = System.currentTimeMillis()
            )
        )
    }

    suspend fun setFavorite(id: String, favorite: Boolean) = dao.setFavorite(id, favorite)
    suspend fun markOpened(id: String) = dao.markOpened(id, System.currentTimeMillis())
    suspend fun updateProgress(id: String, progressMillis: Long, completed: Boolean) =
        dao.updateProgress(id, progressMillis, if (completed) 1 else 0, System.currentTimeMillis())
    suspend fun deletePersonal(id: String) = dao.deletePersonal(id)
}

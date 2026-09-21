package com.interpretertrainer.app.data.database

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "practice_library")
data class PracticeLibraryEntity(
    @PrimaryKey val id: String,
    val title: String,
    val speaker: String,
    val institutionEvent: String,
    val eventDate: String,
    val language: String,
    val field: String,
    val topic: String,
    val difficulty: String,
    val speakingSpeed: String,
    val interpretationTypes: String,
    val durationMillis: Long,
    val sourceUrl: String?,
    val mediaUri: String?,
    val sourceLabel: String,
    val isOfficial: Boolean,
    val createdAt: Long,
    val isFavorite: Boolean = false,
    val lastPracticedAt: Long? = null,
    val progressMillis: Long = 0,
    val completionCount: Int = 0
)

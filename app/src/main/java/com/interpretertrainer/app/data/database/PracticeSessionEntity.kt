package com.interpretertrainer.app.data.database

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "practice_sessions")
data class PracticeSessionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val practiceMode: String,
    val sourceLanguage: String,
    val targetLanguage: String,
    val startedAt: Long,
    val durationMillis: Long,
    val sourceName: String?,
    val transcript: String,
    val notes: String,
    val segmentDurationSeconds: Int?,
    val status: String
)

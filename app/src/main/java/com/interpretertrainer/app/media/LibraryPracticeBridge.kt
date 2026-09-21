package com.interpretertrainer.app.media

import com.interpretertrainer.app.data.database.PracticeLibraryEntity
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** One-shot handoff from the Practice Library into an existing training mode. */
object LibraryPracticeBridge {
    data class Payload(
        val token: Long,
        val mode: String,
        val itemId: String,
        val sourceName: String,
        val sourceUrl: String?,
        val mediaUri: String?,
        val language: String,
        val recommendedDurationMillis: Long
    )

    private val _payload = MutableStateFlow<Payload?>(null)
    val payload = _payload.asStateFlow()

    fun send(item: PracticeLibraryEntity, mode: String) {
        _payload.value = Payload(
            token = System.nanoTime(),
            mode = mode,
            itemId = item.id,
            sourceName = item.title,
            sourceUrl = item.sourceUrl,
            mediaUri = item.mediaUri,
            language = item.language,
            recommendedDurationMillis = item.durationMillis
        )
    }

    fun consume(token: Long) {
        if (_payload.value?.token == token) _payload.value = null
    }
}

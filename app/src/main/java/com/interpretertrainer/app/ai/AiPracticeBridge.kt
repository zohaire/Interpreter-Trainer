package com.interpretertrainer.app.ai

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Locale

/**
 * Small in-process handoff used by Interpreter AI to place generated practice material directly
 * into the training modes. The payload stays in memory only and is consumed by the destination
 * screen once it has been inserted.
 */
object AiPracticeBridge {
    const val MODE_SIMULTANEOUS = "SIMULTANEOUS"
    const val MODE_SHADOWING = "SHADOWING"
    const val MODE_CONSECUTIVE = "CONSECUTIVE"
    const val MODE_TRANSCRIPTION = "TRANSCRIPTION"

    data class PracticePayload(
        val id: Long,
        val mode: String,
        val text: String
    )

    private val _payload = MutableStateFlow<PracticePayload?>(null)
    val payload: StateFlow<PracticePayload?> = _payload.asStateFlow()

    fun sendToMode(mode: String, text: String): Boolean {
        val normalizedMode = when (mode.trim().uppercase(Locale.ROOT)) {
            MODE_SIMULTANEOUS, "SIMULTANEOUS INTERPRETATION" -> MODE_SIMULTANEOUS
            MODE_SHADOWING, "SHADOWING PRACTICE" -> MODE_SHADOWING
            MODE_CONSECUTIVE, "CONSECUTIVE INTERPRETATION" -> MODE_CONSECUTIVE
            MODE_TRANSCRIPTION, "LIVE TRANSCRIPTION" -> MODE_TRANSCRIPTION
            else -> return false
        }
        val cleaned = text.trim()
        if (cleaned.isBlank()) return false

        _payload.value = PracticePayload(
            id = System.nanoTime(),
            mode = normalizedMode,
            text = cleaned.take(20_000)
        )
        return true
    }

    fun clear() { _payload.value = null }

    fun consume(id: Long) {
        if (_payload.value?.id == id) _payload.value = null
    }
}

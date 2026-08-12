package com.interpretertrainer.app.speech

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class SpeechRecognizerManager(private val context: Context) : RecognitionListener {
    private var recognizer: SpeechRecognizer? = null
    private var languageTag: String = "en-US"
    private var shouldListen = false

    private val _state = MutableStateFlow(SpeechState())
    val state: StateFlow<SpeechState> = _state.asStateFlow()

    fun isAvailable(): Boolean = SpeechRecognizer.isRecognitionAvailable(context)

    fun start(language: String) {
        languageTag = language
        shouldListen = true
        if (!isAvailable()) {
            _state.value = _state.value.copy(error = "Speech recognition is not available on this device.")
            return
        }
        if (recognizer == null) {
            recognizer = SpeechRecognizer.createSpeechRecognizer(context).also {
                it.setRecognitionListener(this)
            }
        }
        beginListening()
    }

    private fun beginListening() {
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, languageTag)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
        }
        _state.value = _state.value.copy(isListening = true, error = null)
        recognizer?.startListening(intent)
    }

    fun stop() {
        shouldListen = false
        recognizer?.stopListening()
        _state.value = _state.value.copy(isListening = false)
    }

    fun destroy() {
        shouldListen = false
        recognizer?.cancel()
        recognizer?.destroy()
        recognizer = null
    }

    override fun onReadyForSpeech(params: Bundle?) = Unit
    override fun onBeginningOfSpeech() = Unit
    override fun onRmsChanged(rmsdB: Float) = Unit
    override fun onBufferReceived(buffer: ByteArray?) = Unit
    override fun onEndOfSpeech() { _state.value = _state.value.copy(isListening = false) }

    override fun onError(error: Int) {
        _state.value = _state.value.copy(isListening = false, error = errorMessage(error))
        if (shouldListen && error != SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS && error != SpeechRecognizer.ERROR_CLIENT) {
            recognizer?.cancel()
            beginListening()
        }
    }

    override fun onResults(results: Bundle?) {
        val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty()
        if (text.isNotBlank()) {
            val combined = listOf(_state.value.finalText, text).filter { it.isNotBlank() }.joinToString(" ")
            _state.value = _state.value.copy(finalText = combined, partialText = "", isListening = false)
        }
        if (shouldListen) beginListening()
    }

    override fun onPartialResults(partialResults: Bundle?) {
        val text = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty()
        _state.value = _state.value.copy(partialText = text)
    }

    override fun onEvent(eventType: Int, params: Bundle?) = Unit

    fun clearTranscript() { _state.value = SpeechState(isListening = _state.value.isListening) }

    private fun errorMessage(error: Int): String = when (error) {
        SpeechRecognizer.ERROR_AUDIO -> "Audio recording error"
        SpeechRecognizer.ERROR_CLIENT -> "Recognition client error"
        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Microphone permission is required"
        SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "Speech recognition network error"
        SpeechRecognizer.ERROR_NO_MATCH -> "No speech match found"
        SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "Speech recognizer is busy"
        SpeechRecognizer.ERROR_SERVER -> "Speech recognition service error"
        SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "No speech detected"
        else -> "Speech recognition error ($error)"
    }
}

data class SpeechState(
    val isListening: Boolean = false,
    val partialText: String = "",
    val finalText: String = "",
    val error: String? = null
)

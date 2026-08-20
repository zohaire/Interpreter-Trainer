package com.interpretertrainer.app.speech

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class SpeechRecognizerManager(private val context: Context) : RecognitionListener {
    private val main = Handler(Looper.getMainLooper())
    private var recognizer: SpeechRecognizer? = null
    private var languageTag: String = "en-US"
    private var shouldListen = false
    private var listening = false
    private var destroyed = false
    private var restartGeneration = 0L

    private val _state = MutableStateFlow(SpeechState())
    val state: StateFlow<SpeechState> = _state.asStateFlow()

    fun isAvailable(): Boolean = SpeechRecognizer.isRecognitionAvailable(context)

    fun start(language: String) {
        main.post {
            if (destroyed) return@post
            languageTag = language
            shouldListen = true
            restartGeneration++
            if (!isAvailable()) {
                _state.value = _state.value.copy(error = "Speech recognition is not available on this device.")
                return@post
            }
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                _state.value = _state.value.copy(error = "Microphone permission is required", isListening = false)
                return@post
            }
            ensureRecognizer()
            beginListening()
        }
    }

    private fun ensureRecognizer() {
        if (recognizer == null) {
            recognizer = SpeechRecognizer.createSpeechRecognizer(context).also {
                it.setRecognitionListener(this)
            }
        }
    }

    private fun beginListening() {
        if (destroyed || !shouldListen || listening) return
        ensureRecognizer()
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, languageTag)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, false)
        }
        listening = true
        _state.value = _state.value.copy(isListening = true, error = null)
        runCatching { recognizer?.startListening(intent) }
            .onFailure {
                listening = false
                _state.value = _state.value.copy(isListening = false, error = "The microphone could not start")
                scheduleRestart(650L)
            }
    }

    private fun scheduleRestart(delayMillis: Long) {
        if (!shouldListen || destroyed) return
        val generation = ++restartGeneration
        main.postDelayed({
            if (!destroyed && shouldListen && generation == restartGeneration && !listening) {
                beginListening()
            }
        }, delayMillis)
    }

    private fun recreateRecognizer() {
        runCatching { recognizer?.cancel() }
        runCatching { recognizer?.destroy() }
        recognizer = null
        listening = false
        ensureRecognizer()
    }

    fun stop() {
        main.post {
            shouldListen = false
            restartGeneration++
            listening = false
            runCatching { recognizer?.stopListening() }
            _state.value = _state.value.copy(isListening = false, partialText = "")
        }
    }

    fun destroy() {
        main.post {
            if (destroyed) return@post
            destroyed = true
            shouldListen = false
            restartGeneration++
            listening = false
            runCatching { recognizer?.cancel() }
            runCatching { recognizer?.destroy() }
            recognizer = null
        }
    }

    override fun onReadyForSpeech(params: Bundle?) {
        _state.value = _state.value.copy(isListening = true, error = null)
    }

    override fun onBeginningOfSpeech() = Unit
    override fun onRmsChanged(rmsdB: Float) = Unit
    override fun onBufferReceived(buffer: ByteArray?) = Unit

    override fun onEndOfSpeech() {
        listening = false
        _state.value = _state.value.copy(isListening = false)
    }

    override fun onError(error: Int) {
        listening = false
        if (destroyed) return

        when (error) {
            SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> {
                shouldListen = false
                _state.value = _state.value.copy(isListening = false, error = "Microphone permission is required")
            }
            SpeechRecognizer.ERROR_NO_MATCH,
            SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> {
                _state.value = _state.value.copy(isListening = false, partialText = "", error = null)
                scheduleRestart(300L)
            }
            SpeechRecognizer.ERROR_RECOGNIZER_BUSY,
            SpeechRecognizer.ERROR_CLIENT -> {
                _state.value = _state.value.copy(isListening = false, partialText = "", error = null)
                recreateRecognizer()
                scheduleRestart(750L)
            }
            SpeechRecognizer.ERROR_AUDIO -> {
                _state.value = _state.value.copy(isListening = false, error = "Audio recording error")
                recreateRecognizer()
                scheduleRestart(900L)
            }
            SpeechRecognizer.ERROR_NETWORK,
            SpeechRecognizer.ERROR_NETWORK_TIMEOUT,
            SpeechRecognizer.ERROR_SERVER -> {
                _state.value = _state.value.copy(isListening = false, error = errorMessage(error))
                scheduleRestart(1_100L)
            }
            else -> {
                _state.value = _state.value.copy(isListening = false, error = errorMessage(error))
                scheduleRestart(650L)
            }
        }
    }

    override fun onResults(results: Bundle?) {
        listening = false
        val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            ?.firstOrNull()
            .orEmpty()
            .trim()

        if (text.isNotBlank()) {
            val previous = _state.value.finalText.trim()
            val combined = when {
                previous.isBlank() -> text
                previous.endsWith(text, ignoreCase = true) -> previous
                else -> "$previous $text"
            }
            _state.value = _state.value.copy(
                finalText = combined,
                partialText = "",
                isListening = false,
                error = null
            )
        } else {
            _state.value = _state.value.copy(partialText = "", isListening = false)
        }

        scheduleRestart(260L)
    }

    override fun onPartialResults(partialResults: Bundle?) {
        val text = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            ?.firstOrNull()
            .orEmpty()
        _state.value = _state.value.copy(partialText = text, error = null)
    }

    override fun onEvent(eventType: Int, params: Bundle?) = Unit

    fun clearTranscript() {
        _state.value = SpeechState(isListening = _state.value.isListening)
    }

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

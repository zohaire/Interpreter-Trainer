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

/**
 * Resilient continuous speech recognizer shared by transcription screens.
 *
 * All SpeechRecognizer calls are kept on the main thread, restarts are delayed so Android has time
 * to release the recognition service, and microphone ownership is coordinated with audio recording
 * and Interpreter AI voice chat.
 */
class SpeechRecognizerManager(private val context: Context) : RecognitionListener {
    private val mainHandler = Handler(Looper.getMainLooper())
    private val ownerId = "speech-${System.identityHashCode(this)}"

    private var recognizer: SpeechRecognizer? = null
    private var languageTag: String = "en-US"
    private var shouldListen = false
    private var restartToken = 0L

    private val _state = MutableStateFlow(SpeechState())
    val state: StateFlow<SpeechState> = _state.asStateFlow()

    fun isAvailable(): Boolean = SpeechRecognizer.isRecognitionAvailable(context)

    fun start(language: String) {
        mainHandler.post {
            languageTag = language
            shouldListen = true
            restartToken++

            if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                shouldListen = false
                _state.value = _state.value.copy(isListening = false, error = "Microphone permission is required")
                return@post
            }

            if (!isAvailable()) {
                shouldListen = false
                _state.value = _state.value.copy(isListening = false, error = "Speech recognition is not available on this device.")
                return@post
            }

            MicrophoneSessionCoordinator.acquire(ownerId) {
                preemptForAnotherMicrophoneUser()
            }
            beginListening(recreate = recognizer == null)
        }
    }

    private fun ensureRecognizer(recreate: Boolean) {
        if (recreate) {
            runCatching { recognizer?.cancel() }
            runCatching { recognizer?.destroy() }
            recognizer = null
        }
        if (recognizer == null) {
            recognizer = SpeechRecognizer.createSpeechRecognizer(context).also {
                it.setRecognitionListener(this)
            }
        }
    }

    private fun beginListening(recreate: Boolean = false) {
        if (!shouldListen || !MicrophoneSessionCoordinator.isOwner(ownerId)) return

        runCatching {
            ensureRecognizer(recreate)
            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, languageTag)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, languageTag)
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
                putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, false)
            }
            _state.value = _state.value.copy(isListening = true, error = null)
            recognizer?.startListening(intent)
        }.onFailure {
            _state.value = _state.value.copy(isListening = false, error = it.message ?: "Could not start speech recognition")
            scheduleRestart(550L, recreate = true)
        }
    }

    private fun scheduleRestart(delayMillis: Long, recreate: Boolean = false) {
        if (!shouldListen) return
        val token = ++restartToken
        mainHandler.postDelayed({
            if (shouldListen && token == restartToken && MicrophoneSessionCoordinator.isOwner(ownerId)) {
                beginListening(recreate)
            }
        }, delayMillis)
    }

    fun stop() {
        mainHandler.post { stopInternal(releaseLease = true) }
    }

    private fun stopInternal(releaseLease: Boolean) {
        shouldListen = false
        restartToken++
        runCatching { recognizer?.cancel() }
        _state.value = _state.value.copy(isListening = false, partialText = "")
        if (releaseLease) MicrophoneSessionCoordinator.release(ownerId)
    }

    private fun preemptForAnotherMicrophoneUser() {
        stopInternal(releaseLease = false)
    }

    fun destroy() {
        mainHandler.post {
            shouldListen = false
            restartToken++
            runCatching { recognizer?.cancel() }
            runCatching { recognizer?.destroy() }
            recognizer = null
            MicrophoneSessionCoordinator.release(ownerId)
            _state.value = _state.value.copy(isListening = false, partialText = "")
        }
    }

    override fun onReadyForSpeech(params: Bundle?) {
        _state.value = _state.value.copy(isListening = true, error = null)
    }

    override fun onBeginningOfSpeech() = Unit
    override fun onRmsChanged(rmsdB: Float) = Unit
    override fun onBufferReceived(buffer: ByteArray?) = Unit

    override fun onEndOfSpeech() {
        _state.value = _state.value.copy(isListening = false)
    }

    override fun onError(error: Int) {
        _state.value = _state.value.copy(isListening = false, partialText = "")
        if (!shouldListen || !MicrophoneSessionCoordinator.isOwner(ownerId)) return

        when (error) {
            SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> {
                shouldListen = false
                MicrophoneSessionCoordinator.release(ownerId)
                _state.value = _state.value.copy(error = "Microphone permission is required")
            }
            SpeechRecognizer.ERROR_RECOGNIZER_BUSY,
            SpeechRecognizer.ERROR_CLIENT,
            SpeechRecognizer.ERROR_AUDIO -> {
                _state.value = _state.value.copy(error = null)
                scheduleRestart(650L, recreate = true)
            }
            SpeechRecognizer.ERROR_NO_MATCH,
            SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> {
                _state.value = _state.value.copy(error = null)
                scheduleRestart(300L)
            }
            SpeechRecognizer.ERROR_NETWORK,
            SpeechRecognizer.ERROR_NETWORK_TIMEOUT,
            SpeechRecognizer.ERROR_SERVER -> {
                _state.value = _state.value.copy(error = errorMessage(error))
                scheduleRestart(1_200L, recreate = error == SpeechRecognizer.ERROR_SERVER)
            }
            else -> {
                _state.value = _state.value.copy(error = errorMessage(error))
                scheduleRestart(650L, recreate = true)
            }
        }
    }

    override fun onResults(results: Bundle?) {
        val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty()
        if (text.isNotBlank()) {
            val combined = listOf(_state.value.finalText, text)
                .filter { it.isNotBlank() }
                .joinToString(" ")
            _state.value = _state.value.copy(finalText = combined, partialText = "", isListening = false, error = null)
        } else {
            _state.value = _state.value.copy(partialText = "", isListening = false)
        }

        if (shouldListen && MicrophoneSessionCoordinator.isOwner(ownerId)) {
            scheduleRestart(280L)
        }
    }

    override fun onPartialResults(partialResults: Bundle?) {
        val text = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty()
        _state.value = _state.value.copy(partialText = text, error = null)
    }

    override fun onEvent(eventType: Int, params: Bundle?) = Unit

    fun clearTranscript() {
        _state.value = SpeechState(isListening = _state.value.isListening)
    }

    private fun errorMessage(error: Int): String = when (error) {
        SpeechRecognizer.ERROR_AUDIO -> "Microphone audio error"
        SpeechRecognizer.ERROR_CLIENT -> "Speech recognition restarted"
        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Microphone permission is required"
        SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "Speech recognition network error"
        SpeechRecognizer.ERROR_NO_MATCH -> "No speech match found"
        SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "Speech recognition is restarting"
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

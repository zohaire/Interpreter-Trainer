package com.interpretertrainer.app.speech

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import android.webkit.JavascriptInterface
import android.webkit.WebView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import org.json.JSONObject
import java.util.Locale
import java.util.UUID
import kotlin.math.max

/**
 * Native voice layer for Interpreter Coach.
 *
 * WebView speech APIs are inconsistent on Android, so microphone recognition and speech output
 * live here instead. Continuous voice mode deliberately filters the coach's own loudspeaker audio
 * before treating speech as an interruption.
 */
class InterpreterVoiceBridge(
    context: Context,
    private val webViewProvider: () -> WebView?
) : RecognitionListener {
    private val appContext = context.applicationContext
    private val activity = context.findActivity()
    private val main = Handler(Looper.getMainLooper())

    private var recognizer: SpeechRecognizer? = null
    private var recognitionActive = false
    private var continuousMode = false
    private var destroyed = false
    private var languageTag = ""
    private var restartGeneration = 0L

    private var ttsReady = false
    private var tts: TextToSpeech? = null
    private var pendingSpeech: PendingSpeech? = null
    private var currentSpokenText = ""
    private var speakingForContinuousMode = false

    private var heardSpeechStart = false
    private var peakRms = -100f
    private var lastPartial = ""
    private var stablePartialCount = 0
    private var lastPartialAt = 0L
    private var bargeInHandled = false

    private data class PendingSpeech(val text: String, val languageTag: String, val continuous: Boolean)

    init {
        main.post {
            if (destroyed) return@post
            tts = TextToSpeech(appContext) { status ->
                ttsReady = status == TextToSpeech.SUCCESS
                if (!ttsReady) {
                    postError("High-quality speech output is not available on this device.")
                    return@TextToSpeech
                }
                tts?.setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ASSISTANT)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                tts?.setSpeechRate(1.03f)
                tts?.setPitch(1.0f)
                tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) {
                        postJs("onSpeechStart")
                        postState("speaking")
                        if (speakingForContinuousMode) {
                            scheduleListening(300L)
                        }
                    }

                    override fun onDone(utteranceId: String?) {
                        currentSpokenText = ""
                        postJs("onSpeechDone")
                        if (speakingForContinuousMode && continuousMode && !bargeInHandled) {
                            scheduleListening(180L)
                        } else if (!continuousMode) {
                            postState("ready")
                        }
                    }

                    @Deprecated("Deprecated in Java")
                    override fun onError(utteranceId: String?) {
                        currentSpokenText = ""
                        postError("Speech output failed. Trying the device voice again is safe.")
                        if (continuousMode) scheduleListening(350L)
                    }

                    override fun onError(utteranceId: String?, errorCode: Int) {
                        onError(utteranceId)
                    }
                })

                pendingSpeech?.let {
                    pendingSpeech = null
                    speakInternal(it)
                }
            }
        }
    }

    @JavascriptInterface
    fun isRecognitionAvailable(): Boolean = SpeechRecognizer.isRecognitionAvailable(appContext)

    @JavascriptInterface
    fun startListening(requestedLanguageTag: String, continuous: Boolean) {
        main.post {
            if (destroyed) return@post
            continuousMode = continuous
            languageTag = requestedLanguageTag.trim()
            speakingForContinuousMode = continuous
            bargeInHandled = false
            ensurePermissionAndListen()
        }
    }

    @JavascriptInterface
    fun stopListening() {
        main.post {
            continuousMode = false
            restartGeneration++
            recognitionActive = false
            runCatching { recognizer?.stopListening() }
            postState("ready")
        }
    }

    @JavascriptInterface
    fun stopAll() {
        main.post {
            continuousMode = false
            speakingForContinuousMode = false
            restartGeneration++
            recognitionActive = false
            runCatching { recognizer?.cancel() }
            runCatching { tts?.stop() }
            currentSpokenText = ""
            postState("ready")
        }
    }

    /** Manual tap while the coach is talking always interrupts immediately and listens. */
    @JavascriptInterface
    fun manualInterrupt(requestedLanguageTag: String) {
        main.post {
            if (destroyed) return@post
            continuousMode = true
            speakingForContinuousMode = true
            languageTag = requestedLanguageTag.trim()
            bargeInHandled = true
            runCatching { tts?.stop() }
            currentSpokenText = ""
            runCatching { recognizer?.cancel() }
            recognitionActive = false
            scheduleListening(100L)
        }
    }

    @JavascriptInterface
    fun speak(text: String, requestedLanguageTag: String, continuous: Boolean) {
        val clean = text.trim().take(8_000)
        if (clean.isBlank()) return
        main.post {
            if (destroyed) return@post
            continuousMode = continuous
            speakingForContinuousMode = continuous
            val pending = PendingSpeech(clean, requestedLanguageTag.trim(), continuous)
            if (!ttsReady) {
                pendingSpeech = pending
            } else {
                speakInternal(pending)
            }
        }
    }

    private fun speakInternal(request: PendingSpeech) {
        val engine = tts ?: return
        val locale = resolveLocale(request.languageTag, request.text)
        engine.setLanguage(locale)
        chooseBestVoice(engine, locale)?.let { runCatching { engine.voice = it } }
        engine.setSpeechRate(1.03f)
        engine.setPitch(1.0f)

        currentSpokenText = normalize(request.text)
        speakingForContinuousMode = request.continuous
        bargeInHandled = false
        resetBargeInEvidence()

        val utteranceId = "interpreter-ai-${UUID.randomUUID()}"
        engine.speak(request.text, TextToSpeech.QUEUE_FLUSH, Bundle(), utteranceId)
    }

    private fun ensurePermissionAndListen() {
        if (ContextCompat.checkSelfPermission(appContext, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            val host = activity
            if (host != null) {
                ActivityCompat.requestPermissions(host, arrayOf(Manifest.permission.RECORD_AUDIO), MIC_PERMISSION_REQUEST)
                postError("Microphone permission requested. Allow it, then tap the microphone again.")
            } else {
                postError("Microphone permission is required for voice chat.")
            }
            return
        }
        beginListening()
    }

    private fun beginListening() {
        if (destroyed || recognitionActive) return
        if (!SpeechRecognizer.isRecognitionAvailable(appContext)) {
            postError("Speech recognition is not available on this device.")
            return
        }

        if (recognizer == null) {
            recognizer = SpeechRecognizer.createSpeechRecognizer(appContext).also {
                it.setRecognitionListener(this)
            }
        }

        resetBargeInEvidence()
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
            putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, false)
            if (languageTag.isNotBlank() && languageTag != "auto") {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, languageTag)
            }
        }

        recognitionActive = true
        postState("listening")
        runCatching { recognizer?.startListening(intent) }
            .onFailure {
                recognitionActive = false
                postError("The microphone could not start. Retrying…")
                scheduleListening(600L)
            }
    }

    private fun scheduleListening(delayMillis: Long) {
        if (!continuousMode || destroyed) return
        val generation = ++restartGeneration
        main.postDelayed({
            if (!destroyed && continuousMode && generation == restartGeneration && !recognitionActive) {
                ensurePermissionAndListen()
            }
        }, delayMillis)
    }

    override fun onReadyForSpeech(params: Bundle?) {
        heardSpeechStart = false
        peakRms = -100f
        postState("listening")
    }

    override fun onBeginningOfSpeech() {
        heardSpeechStart = true
    }

    override fun onRmsChanged(rmsdB: Float) {
        peakRms = max(peakRms, rmsdB)
    }

    override fun onBufferReceived(buffer: ByteArray?) = Unit

    override fun onEndOfSpeech() {
        recognitionActive = false
    }

    override fun onError(error: Int) {
        recognitionActive = false
        if (destroyed) return

        when (error) {
            SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> {
                postError("Microphone permission is required for voice chat.")
                continuousMode = false
            }
            SpeechRecognizer.ERROR_NO_MATCH,
            SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> {
                if (continuousMode) scheduleListening(280L)
            }
            SpeechRecognizer.ERROR_RECOGNIZER_BUSY,
            SpeechRecognizer.ERROR_CLIENT -> {
                recreateRecognizer()
                if (continuousMode) scheduleListening(700L)
            }
            SpeechRecognizer.ERROR_NETWORK,
            SpeechRecognizer.ERROR_NETWORK_TIMEOUT,
            SpeechRecognizer.ERROR_SERVER,
            SpeechRecognizer.ERROR_AUDIO -> {
                if (continuousMode) scheduleListening(900L)
                else postError(errorMessage(error))
            }
            else -> {
                if (continuousMode) scheduleListening(550L)
                else postError(errorMessage(error))
            }
        }
    }

    override fun onResults(results: Bundle?) {
        recognitionActive = false
        val text = bestResult(results)
        if (text.isBlank()) {
            if (continuousMode) scheduleListening(250L)
            return
        }

        if (tts?.isSpeaking == true) {
            if (isLikelyEcho(text)) {
                if (continuousMode) scheduleListening(180L)
                return
            }

            // A single recognition result while the loudspeaker is active is not enough to cut
            // the coach off. Prefer the repeated partial-result confirmation path. If a speech
            // service supplies no partials, allow only a clearly deliberate, strong utterance.
            if (!isStrongFinalBargeIn(text)) {
                if (continuousMode) scheduleListening(180L)
                return
            }
            acceptBargeIn(text)
            return
        }

        postResult(text)
        // Do not immediately reopen the mic here. In voice-call mode the page sends this text to
        // the model; listening resumes as soon as the answer starts/finishes speaking.
    }

    override fun onPartialResults(partialResults: Bundle?) {
        val text = bestResult(partialResults)
        if (text.isBlank()) return
        postPartial(text)

        if (tts?.isSpeaking != true || bargeInHandled) return
        if (isLikelyEcho(text)) return
        if (!heardSpeechStart || peakRms < PARTIAL_BARGE_IN_MIN_RMS) return

        val normalized = normalize(text)
        val now = System.currentTimeMillis()
        val sufficientlyLong = normalized.length >= 8 || normalized.split(' ').size >= 2
        if (!sufficientlyLong) return

        if (now - lastPartialAt <= PARTIAL_CONFIRM_WINDOW_MS && partialsAgree(lastPartial, normalized)) {
            stablePartialCount += 1
        } else {
            stablePartialCount = 1
        }
        lastPartial = normalized
        lastPartialAt = now

        if (stablePartialCount >= REQUIRED_STABLE_PARTIALS) {
            acceptBargeIn(text)
        }
    }

    override fun onEvent(eventType: Int, params: Bundle?) = Unit

    private fun isStrongFinalBargeIn(text: String): Boolean {
        if (!heardSpeechStart || peakRms < FINAL_BARGE_IN_MIN_RMS) return false
        val normalized = normalize(text)
        if (normalized.length < FINAL_BARGE_IN_MIN_CHARS) return false
        val words = normalized.split(' ').filter { it.length > 1 }
        return words.size >= FINAL_BARGE_IN_MIN_WORDS
    }

    private fun acceptBargeIn(text: String) {
        if (bargeInHandled) return
        bargeInHandled = true
        currentSpokenText = ""
        runCatching { tts?.stop() }
        runCatching { recognizer?.cancel() }
        recognitionActive = false
        postJs("onBargeIn", text)
        postResult(text)
    }

    private fun recreateRecognizer() {
        restartGeneration++
        runCatching { recognizer?.cancel() }
        runCatching { recognizer?.destroy() }
        recognizer = null
        recognitionActive = false
    }

    private fun resetBargeInEvidence() {
        heardSpeechStart = false
        peakRms = -100f
        lastPartial = ""
        stablePartialCount = 0
        lastPartialAt = 0L
    }

    private fun bestResult(bundle: Bundle?): String {
        return bundle?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            ?.firstOrNull()
            .orEmpty()
            .trim()
    }

    private fun isLikelyEcho(candidate: String): Boolean {
        val spoken = currentSpokenText
        if (spoken.isBlank()) return false
        val heard = normalize(candidate)
        if (heard.isBlank()) return true
        if (spoken.contains(heard) && heard.length >= 5) return true

        val heardTokens = heard.split(' ').filter { it.length > 1 }
        if (heardTokens.isEmpty()) return true
        val spokenTokens = spoken.split(' ').toSet()
        val overlap = heardTokens.count { it in spokenTokens }.toFloat() / heardTokens.size.toFloat()
        return overlap >= ECHO_TOKEN_OVERLAP
    }

    private fun partialsAgree(previous: String, current: String): Boolean {
        if (previous.isBlank()) return false
        if (previous == current) return true
        return previous.startsWith(current) || current.startsWith(previous)
    }

    private fun normalize(value: String): String = value
        .lowercase(Locale.ROOT)
        .replace(Regex("[^\\p{L}\\p{N} ]+"), " ")
        .replace(Regex("\\s+"), " ")
        .trim()

    private fun resolveLocale(requested: String, text: String): Locale {
        if (requested.isNotBlank() && requested != "auto") {
            Locale.forLanguageTag(requested).takeIf { it.language.isNotBlank() }?.let { return it }
        }
        return when {
            ARABIC_REGEX.containsMatchIn(text) -> Locale.forLanguageTag("ar-MA")
            FRENCH_HINTS.any { hint -> text.lowercase(Locale.ROOT).contains(hint) } -> Locale.FRANCE
            else -> Locale.US
        }
    }

    private fun chooseBestVoice(engine: TextToSpeech, locale: Locale): Voice? {
        val candidates = engine.voices
            ?.filter { it.locale.language == locale.language }
            .orEmpty()
        return candidates.maxWithOrNull(
            compareBy<Voice> { it.quality }
                .thenBy { if (it.isNetworkConnectionRequired) 1 else 0 }
                .thenBy { -it.latency }
        )
    }

    private fun postState(state: String) = postJs("onState", state)
    private fun postPartial(text: String) = postJs("onPartial", text)
    private fun postResult(text: String) = postJs("onResult", text)
    private fun postError(message: String) = postJs("onError", message)

    private fun postJs(function: String, argument: String? = null) {
        val arg = argument?.let(JSONObject::quote) ?: ""
        val script = if (argument == null) {
            "window.InterpreterVoiceNative?.$function?.();"
        } else {
            "window.InterpreterVoiceNative?.$function?.($arg);"
        }
        webViewProvider()?.post {
            if (!destroyed) runCatching { webViewProvider()?.evaluateJavascript(script, null) }
        }
    }

    fun destroy() {
        main.post {
            if (destroyed) return@post
            destroyed = true
            continuousMode = false
            restartGeneration++
            runCatching { recognizer?.cancel() }
            runCatching { recognizer?.destroy() }
            recognizer = null
            runCatching { tts?.stop() }
            runCatching { tts?.shutdown() }
            tts = null
        }
    }

    private fun errorMessage(error: Int): String = when (error) {
        SpeechRecognizer.ERROR_AUDIO -> "Microphone audio error"
        SpeechRecognizer.ERROR_CLIENT -> "Speech recognition client error"
        SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "Speech recognition network error"
        SpeechRecognizer.ERROR_NO_MATCH -> "No speech was recognized"
        SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "Speech recognizer is busy"
        SpeechRecognizer.ERROR_SERVER -> "Speech recognition service error"
        SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "No speech detected"
        else -> "Speech recognition error ($error)"
    }

    private fun Context.findActivity(): Activity? = when (this) {
        is Activity -> this
        is ContextWrapper -> baseContext.findActivity()
        else -> null
    }

    companion object {
        private const val MIC_PERMISSION_REQUEST = 7312
        private const val PARTIAL_BARGE_IN_MIN_RMS = 3.5f
        private const val FINAL_BARGE_IN_MIN_RMS = 5.0f
        private const val FINAL_BARGE_IN_MIN_CHARS = 12
        private const val FINAL_BARGE_IN_MIN_WORDS = 3
        private const val REQUIRED_STABLE_PARTIALS = 2
        private const val PARTIAL_CONFIRM_WINDOW_MS = 1_100L
        private const val ECHO_TOKEN_OVERLAP = 0.72f
        private val ARABIC_REGEX = Regex("[\\u0600-\\u06FF]")
        private val FRENCH_HINTS = listOf(" je ", " tu ", " vous ", " est ", " des ", " une ", " dans ", " avec ", " pour ")
    }
}

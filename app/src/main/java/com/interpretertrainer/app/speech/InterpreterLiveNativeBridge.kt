package com.interpretertrainer.app.speech

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.NoiseSuppressor
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Base64
import android.webkit.JavascriptInterface
import android.webkit.WebView
import androidx.core.content.ContextCompat
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.sqrt

/**
 * Native low-latency audio bridge used only by Interpreter Live.
 *
 * The normal fast path uses Android TTS plus an echo-cancelled VOICE_COMMUNICATION detector for
 * barge-in. The bridge also owns an independent PCM capture fallback used when Android's platform
 * SpeechRecognizer is unavailable or unstable. That fallback records one utterance, stops on
 * silence, and sends a WAV data URL back to JavaScript for online Puter speech-to-text.
 */
internal class InterpreterLiveNativeBridge(
    private val context: Context
) : TextToSpeech.OnInitListener {
    private val mainHandler = Handler(Looper.getMainLooper())
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val liveAudioOwnerId = "interpreter-live-output-${System.identityHashCode(this)}"

    @Volatile private var webView: WebView? = null
    @Volatile private var tts: TextToSpeech? = null
    @Volatile private var ttsReady = false
    @Volatile private var previousAudioMode: Int? = null

    private val bargeRunning = AtomicBoolean(false)
    @Volatile private var bargeRecord: AudioRecord? = null
    @Volatile private var bargeThread: Thread? = null

    private val cloudCaptureRunning = AtomicBoolean(false)
    @Volatile private var cloudCaptureRecord: AudioRecord? = null
    @Volatile private var cloudCaptureThread: Thread? = null

    init {
        mainHandler.post { tts = TextToSpeech(context, this) }
    }

    fun attachWebView(view: WebView) {
        webView = view
    }

    @JavascriptInterface
    fun isReady(): Boolean = ttsReady

    @JavascriptInterface
    fun speakText(text: String, languageTag: String): Boolean {
        val clean = text.trim().take(4_000)
        if (clean.isBlank()) return false
        val language = normalizeLanguageTag(languageTag)

        if (!ttsReady) {
            requestOnlineSpeech(clean, language)
            return true
        }

        mainHandler.post {
            // Briefly pre-empt any stale recognizer from the user's previous turn, then release the
            // lease before playback so interruption monitoring can own the microphone independently.
            MicrophoneSessionCoordinator.acquire(liveAudioOwnerId) { }
            MicrophoneSessionCoordinator.release(liveAudioOwnerId)

            val engine = tts
            if (engine == null) {
                requestOnlineSpeech(clean, language)
                return@post
            }

            enterCommunicationMode()
            engine.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )

            if (!NaturalAndroidVoice.configure(engine, language, 0.98f)) {
                exitCommunicationMode()
                requestOnlineSpeech(clean, language)
                return@post
            }

            val result = engine.speak(
                clean,
                TextToSpeech.QUEUE_FLUSH,
                null,
                "interpreter-live-${System.nanoTime()}"
            )
            if (result == TextToSpeech.ERROR) {
                exitCommunicationMode()
                requestOnlineSpeech(clean, language)
            }
        }
        return true
    }

    @JavascriptInterface
    fun stopSpeaking() {
        mainHandler.post {
            runCatching { tts?.stop() }
            stopBargeDetector()
            exitCommunicationMode()
            evaluateJs("window.__stopOnlineVoice?.();")
        }
    }

    /**
     * Device-independent fallback voice input. This is intentionally exposed separately from
     * SpeechRecognizer. JavaScript invokes it only when the platform recognizer is missing/failing.
     */
    @JavascriptInterface
    fun startCloudVoiceInput(languageTag: String): Boolean {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            evaluateJs("window.__cloudVoiceCaptureError?.('Microphone permission is required.');")
            return false
        }
        if (cloudCaptureRunning.getAndSet(true)) return true

        stopBargeDetector()
        val language = normalizeLanguageTag(languageTag)
        val thread = Thread({ runCloudVoiceCapture(language) }, "InterpreterCloudVoiceCapture")
        cloudCaptureThread = thread
        thread.start()
        return true
    }

    @JavascriptInterface
    fun stopCloudVoiceInput() {
        stopCloudVoiceCapture()
    }

    @JavascriptInterface
    fun startBargeInDetection(): Boolean {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            return false
        }
        if (!AcousticEchoCanceler.isAvailable()) return false
        if (bargeRunning.getAndSet(true)) return true

        val thread = Thread({ runBargeDetector() }, "InterpreterLiveBargeIn")
        bargeThread = thread
        thread.start()
        return true
    }

    @JavascriptInterface
    fun stopBargeInDetection() {
        stopBargeDetector()
    }

    @SuppressLint("MissingPermission")
    private fun runCloudVoiceCapture(languageTag: String) {
        val sampleRate = 16_000
        val frameSamples = 320 // 20 ms
        val minBuffer = AudioRecord.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )

        if (minBuffer <= 0) {
            cloudCaptureRunning.set(false)
            evaluateJs("window.__cloudVoiceCaptureError?.('Microphone audio is unavailable.');")
            return
        }

        var record: AudioRecord? = null
        val pcm = ByteArrayOutputStream(sampleRate * 2 * 8)
        var capturedSpeech = false
        var deliverAudio = false

        try {
            record = AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                sampleRate,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                max(minBuffer * 2, frameSamples * 8)
            )
            if (record.state != AudioRecord.STATE_INITIALIZED) {
                evaluateJs("window.__cloudVoiceCaptureError?.('Microphone could not initialize.');")
                return
            }
            cloudCaptureRecord = record

            if (NoiseSuppressor.isAvailable()) {
                runCatching {
                    NoiseSuppressor.create(record.audioSessionId)?.apply {
                        enabled = true
                        release()
                    }
                }
            }

            record.startRecording()
            if (record.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
                evaluateJs("window.__cloudVoiceCaptureError?.('Microphone could not start.');")
                return
            }

            evaluateJs("window.__voiceInputStarted?.();")

            val frame = ShortArray(frameSamples)
            var frameIndex = 0
            var baselineDb = -60f
            var voiceHits = 0
            var silentFrames = 0

            while (cloudCaptureRunning.get() && frameIndex < 1_250) { // hard cap ~25 s
                val read = record.read(frame, 0, frame.size, AudioRecord.READ_BLOCKING)
                if (read <= 0) continue

                var sumSquares = 0.0
                var peak = 0
                for (index in 0 until read) {
                    val sample = frame[index].toInt()
                    val abs = kotlin.math.abs(sample)
                    if (abs > peak) peak = abs
                    sumSquares += sample.toDouble() * sample.toDouble()
                    pcm.write(sample and 0xff)
                    pcm.write((sample shr 8) and 0xff)
                }

                val rms = sqrt(sumSquares / read.coerceAtLeast(1).toDouble())
                val db = if (rms <= 1.0) -90f else (20.0 * log10(rms / 32768.0)).toFloat()
                val peakNorm = peak / 32768f

                if (frameIndex < 8) {
                    baselineDb = if (frameIndex == 0) db else baselineDb * 0.72f + db * 0.28f
                    frameIndex += 1
                    continue
                }

                val threshold = max(-50f, baselineDb + 7.0f)
                val speechLike = db >= threshold && peakNorm >= 0.014f

                if (speechLike) {
                    voiceHits += 1
                    silentFrames = 0
                    if (voiceHits >= 2) capturedSpeech = true
                } else {
                    voiceHits = 0
                    if (capturedSpeech) silentFrames += 1
                    else if (db < baselineDb + 2.0f) baselineDb = baselineDb * 0.98f + db * 0.02f
                }

                frameIndex += 1

                // End ~560 ms after the speaker actually finishes. This keeps voice turns fast while
                // still allowing a natural short pause in the middle of a sentence.
                if (capturedSpeech && silentFrames >= 28) {
                    deliverAudio = true
                    break
                }

                // No-speech timeout ~6 s.
                if (!capturedSpeech && frameIndex >= 300) break
            }

            if (capturedSpeech) deliverAudio = true
        } catch (_: Throwable) {
            evaluateJs("window.__cloudVoiceCaptureError?.('Voice recording failed.');")
        } finally {
            cloudCaptureRunning.set(false)
            runCatching {
                if (record?.recordingState == AudioRecord.RECORDSTATE_RECORDING) record.stop()
            }
            runCatching { record?.release() }
            if (cloudCaptureRecord === record) cloudCaptureRecord = null
            cloudCaptureThread = null
            evaluateJs("window.__voiceInputStopped?.();")
        }

        if (!deliverAudio || !capturedSpeech || pcm.size() < 3_200) {
            evaluateJs("window.__cloudVoiceCaptureError?.('No speech was detected.');")
            return
        }

        val wav = pcmToWav(pcm.toByteArray(), sampleRate)
        val encoded = Base64.encodeToString(wav, Base64.NO_WRAP)
        val dataUrl = "data:audio/wav;base64,$encoded"
        evaluateJs(
            "window.__cloudVoiceAudioReady?.(" +
                JSONObject.quote(dataUrl) + "," + JSONObject.quote(languageTag) + ");"
        )
    }

    @SuppressLint("MissingPermission")
    private fun runBargeDetector() {
        val sampleRate = 16_000
        val frameSamples = 320
        val minBuffer = AudioRecord.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        if (minBuffer <= 0) {
            notifyBargeUnavailableIfStillExpected()
            return
        }

        var record: AudioRecord? = null
        var echoCanceler: AcousticEchoCanceler? = null
        var noiseSuppressor: NoiseSuppressor? = null

        try {
            record = AudioRecord(
                MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                sampleRate,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                max(minBuffer * 2, frameSamples * 8)
            )
            if (record.state != AudioRecord.STATE_INITIALIZED) {
                notifyBargeUnavailableIfStillExpected()
                return
            }
            bargeRecord = record

            echoCanceler = runCatching { AcousticEchoCanceler.create(record.audioSessionId) }.getOrNull()
            val echoReady = runCatching {
                echoCanceler?.enabled = true
                echoCanceler?.enabled == true
            }.getOrDefault(false)
            if (!echoReady) {
                notifyBargeUnavailableIfStillExpected()
                return
            }

            if (NoiseSuppressor.isAvailable()) {
                noiseSuppressor = runCatching { NoiseSuppressor.create(record.audioSessionId) }.getOrNull()
                runCatching { noiseSuppressor?.enabled = true }
            }

            record.startRecording()
            if (record.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
                notifyBargeUnavailableIfStillExpected()
                return
            }

            val frame = ShortArray(frameSamples)
            var frameIndex = 0
            var baselineDb = -58f
            var previousDb = -58f
            var voiceHits = 0

            while (bargeRunning.get()) {
                val read = record.read(frame, 0, frame.size, AudioRecord.READ_BLOCKING)
                if (read <= 0) continue

                var sumSquares = 0.0
                var peak = 0
                for (index in 0 until read) {
                    val sample = frame[index].toInt()
                    val abs = kotlin.math.abs(sample)
                    if (abs > peak) peak = abs
                    sumSquares += sample.toDouble() * sample.toDouble()
                }

                val rms = sqrt(sumSquares / read.coerceAtLeast(1).toDouble())
                val db = if (rms <= 1.0) -90f else (20.0 * log10(rms / 32768.0)).toFloat()
                val peakNorm = peak / 32768f

                if (frameIndex < 5) {
                    baselineDb = if (frameIndex == 0) db else baselineDb * 0.68f + db * 0.32f
                    previousDb = db
                    frameIndex += 1
                    continue
                }

                val threshold = max(-52f, baselineDb + 5.0f)
                val suddenOnset = db - previousDb >= 4.0f
                val speechLike = (db >= threshold && peakNorm >= 0.018f) ||
                    (suddenOnset && db >= -48f && peakNorm >= 0.022f)

                if (speechLike) {
                    voiceHits += 1
                } else {
                    voiceHits = (voiceHits - 1).coerceAtLeast(0)
                    if (db < baselineDb + 2.5f) {
                        baselineDb = baselineDb * 0.97f + db * 0.03f
                    }
                }

                previousDb = db
                frameIndex += 1

                if (voiceHits >= 3 && bargeRunning.compareAndSet(true, false)) {
                    evaluateJs("window.__nativeBargeInDetected?.();")
                    break
                }
            }
        } catch (_: Throwable) {
            notifyBargeUnavailableIfStillExpected()
        } finally {
            bargeRunning.set(false)
            runCatching {
                if (record?.recordingState == AudioRecord.RECORDSTATE_RECORDING) record.stop()
            }
            runCatching { echoCanceler?.release() }
            runCatching { noiseSuppressor?.release() }
            runCatching { record?.release() }
            if (bargeRecord === record) bargeRecord = null
            bargeThread = null
        }
    }

    private fun notifyBargeUnavailableIfStillExpected() {
        if (bargeRunning.compareAndSet(true, false)) {
            evaluateJs("window.__nativeBargeMonitorUnavailable?.();")
        }
    }

    private fun stopBargeDetector() {
        bargeRunning.set(false)
        val record = bargeRecord
        runCatching {
            if (record?.recordingState == AudioRecord.RECORDSTATE_RECORDING) record.stop()
        }
    }

    private fun stopCloudVoiceCapture() {
        cloudCaptureRunning.set(false)
        val record = cloudCaptureRecord
        runCatching {
            if (record?.recordingState == AudioRecord.RECORDSTATE_RECORDING) record.stop()
        }
    }

    private fun requestOnlineSpeech(text: String, languageTag: String) {
        evaluateJs(
            "window.__onlineVoiceSpeak?.(" + JSONObject.quote(text) + "," +
                JSONObject.quote(languageTag) + ");"
        )
    }

    private fun pcmToWav(pcm: ByteArray, sampleRate: Int): ByteArray {
        val out = ByteArrayOutputStream(44 + pcm.size)
        fun ascii(value: String) = out.write(value.toByteArray(Charsets.US_ASCII))
        fun intLe(value: Int) {
            out.write(value and 0xff)
            out.write((value shr 8) and 0xff)
            out.write((value shr 16) and 0xff)
            out.write((value shr 24) and 0xff)
        }
        fun shortLe(value: Int) {
            out.write(value and 0xff)
            out.write((value shr 8) and 0xff)
        }

        val channels = 1
        val bitsPerSample = 16
        val byteRate = sampleRate * channels * bitsPerSample / 8
        val blockAlign = channels * bitsPerSample / 8

        ascii("RIFF")
        intLe(36 + pcm.size)
        ascii("WAVE")
        ascii("fmt ")
        intLe(16)
        shortLe(1)
        shortLe(channels)
        intLe(sampleRate)
        intLe(byteRate)
        shortLe(blockAlign)
        shortLe(bitsPerSample)
        ascii("data")
        intLe(pcm.size)
        out.write(pcm)
        return out.toByteArray()
    }

    private fun normalizeLanguageTag(tag: String): String = when (tag.lowercase()) {
        "fr", "fr-fr", "french" -> "fr-FR"
        "ar", "ar-ma", "arabic" -> "ar-MA"
        else -> "en-US"
    }

    private fun enterCommunicationMode() {
        if (previousAudioMode == null) previousAudioMode = audioManager.mode
        runCatching { audioManager.mode = AudioManager.MODE_IN_COMMUNICATION }
    }

    private fun exitCommunicationMode() {
        val restore = previousAudioMode ?: AudioManager.MODE_NORMAL
        previousAudioMode = null
        runCatching { audioManager.mode = restore }
    }

    private fun evaluateJs(script: String) {
        mainHandler.post { runCatching { webView?.evaluateJavascript(script, null) } }
    }

    override fun onInit(status: Int) {
        ttsReady = status == TextToSpeech.SUCCESS
        if (!ttsReady) return

        mainHandler.post {
            tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {
                    evaluateJs("window.__nativeSpeechStarted?.();")
                }

                override fun onDone(utteranceId: String?) {
                    stopBargeDetector()
                    exitCommunicationMode()
                    evaluateJs("window.__nativeSpeechFinished?.();")
                }

                override fun onStop(utteranceId: String?, interrupted: Boolean) {
                    stopBargeDetector()
                    exitCommunicationMode()
                }

                @Deprecated("Deprecated in Java")
                override fun onError(utteranceId: String?) {
                    stopBargeDetector()
                    exitCommunicationMode()
                    evaluateJs("window.__nativeSpeechFinished?.();")
                }

                override fun onError(utteranceId: String?, errorCode: Int) {
                    stopBargeDetector()
                    exitCommunicationMode()
                    evaluateJs("window.__nativeSpeechFinished?.();")
                }
            })
        }
    }

    fun dispose() {
        stopBargeDetector()
        stopCloudVoiceCapture()
        mainHandler.post {
            runCatching { tts?.stop() }
            runCatching { tts?.shutdown() }
            tts = null
            ttsReady = false
            exitCommunicationMode()
            MicrophoneSessionCoordinator.release(liveAudioOwnerId)
            webView = null
        }
    }
}

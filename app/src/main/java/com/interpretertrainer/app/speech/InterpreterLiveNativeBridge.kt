package com.interpretertrainer.app.speech

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.NoiseSuppressor
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.webkit.JavascriptInterface
import android.webkit.WebView
import androidx.core.content.ContextCompat
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.sqrt

/**
 * Native low-latency audio bridge used only by Interpreter Live.
 *
 * Android TTS starts quickly and a separate VOICE_COMMUNICATION AudioRecord with acoustic echo
 * cancellation detects near-end speech while the AI is talking. Once speech starts, the page stops
 * output and hands the microphone to SpeechRecognizer to capture the complete user interruption.
 */
internal class InterpreterLiveNativeBridge(
    private val context: Context
) : TextToSpeech.OnInitListener {
    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile private var webView: WebView? = null
    @Volatile private var tts: TextToSpeech? = null
    @Volatile private var ttsReady = false

    private val bargeRunning = AtomicBoolean(false)
    @Volatile private var bargeRecord: AudioRecord? = null
    @Volatile private var bargeThread: Thread? = null

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
        if (clean.isBlank() || !ttsReady) return false

        mainHandler.post {
            val engine = tts ?: run {
                evaluateJs("window.__nativeSpeechFinished?.();")
                return@post
            }

            engine.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANT)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )

            if (!NaturalAndroidVoice.configure(engine, languageTag, 0.98f)) {
                evaluateJs("window.__nativeSpeechFinished?.();")
                return@post
            }

            val result = engine.speak(
                clean,
                TextToSpeech.QUEUE_FLUSH,
                null,
                "interpreter-live-${System.nanoTime()}"
            )
            if (result == TextToSpeech.ERROR) evaluateJs("window.__nativeSpeechFinished?.();")
        }
        return true
    }

    @JavascriptInterface
    fun stopSpeaking() {
        mainHandler.post { runCatching { tts?.stop() } }
    }

    /**
     * Starts a VAD/AEC capture while AI speech is playing. It never transcribes the user; it only
     * signals speech onset. Devices without usable acoustic echo cancellation fall back immediately
     * to the existing recognizer-based monitor instead of silently losing interruption support.
     */
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
    private fun runBargeDetector() {
        val sampleRate = 16_000
        val frameSamples = 320 // 20 ms
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

                // First ~120 ms learns the residual speaker/AEC floor.
                if (frameIndex < 6) {
                    baselineDb = if (frameIndex == 0) db else baselineDb * 0.68f + db * 0.32f
                    previousDb = db
                    frameIndex += 1
                    continue
                }

                val threshold = max(-47f, baselineDb + 7.5f)
                val suddenOnset = db - previousDb >= 5.5f
                val speechLike = (db >= threshold && peakNorm >= 0.026f) ||
                    (suddenOnset && db >= -43f && peakNorm >= 0.032f)

                if (speechLike) {
                    voiceHits += 1
                } else {
                    voiceHits = (voiceHits - 1).coerceAtLeast(0)
                    if (db < baselineDb + 3.5f) {
                        baselineDb = baselineDb * 0.965f + db * 0.035f
                    }
                }

                previousDb = db
                frameIndex += 1

                // Three positive 20-ms frames: fast reaction without a single-spike false trigger.
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
                    evaluateJs("window.__nativeSpeechFinished?.();")
                }

                override fun onStop(utteranceId: String?, interrupted: Boolean) {
                    stopBargeDetector()
                }

                @Deprecated("Deprecated in Java")
                override fun onError(utteranceId: String?) {
                    stopBargeDetector()
                    evaluateJs("window.__nativeSpeechFinished?.();")
                }

                override fun onError(utteranceId: String?, errorCode: Int) {
                    stopBargeDetector()
                    evaluateJs("window.__nativeSpeechFinished?.();")
                }
            })
        }
    }

    fun dispose() {
        stopBargeDetector()
        mainHandler.post {
            runCatching { tts?.stop() }
            runCatching { tts?.shutdown() }
            tts = null
            ttsReady = false
            webView = null
        }
    }
}

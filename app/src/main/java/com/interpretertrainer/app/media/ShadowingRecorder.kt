package com.interpretertrainer.app.media

import android.content.Context
import android.media.MediaRecorder
import android.os.Environment
import com.interpretertrainer.app.speech.MicrophoneSessionCoordinator
import java.io.File

/**
 * Records interpretation/shadowing audio while coordinating exclusive microphone access with
 * speech recognition and Interpreter AI voice chat.
 */
class ShadowingRecorder(private val context: Context) {
    private val ownerId = "recorder-${System.identityHashCode(this)}"
    private var mediaRecorder: MediaRecorder? = null

    var isRecording: Boolean = false
        private set

    var currentFile: File? = null
        private set

    fun start(): File {
        check(!isRecording) { "A recording is already in progress." }

        MicrophoneSessionCoordinator.acquire(ownerId) {
            stopInternal(releaseLease = false)
        }

        return try {
            val directory = context.getExternalFilesDir(Environment.DIRECTORY_MUSIC)
                ?: context.filesDir
            val file = File(directory, "interpreter_${System.currentTimeMillis()}.m4a")

            val recorder = MediaRecorder(context).apply {
                setAudioSource(MediaRecorder.AudioSource.MIC)
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                setAudioEncodingBitRate(128_000)
                setAudioSamplingRate(44_100)
                setOutputFile(file.absolutePath)
                prepare()
                start()
            }

            mediaRecorder = recorder
            currentFile = file
            isRecording = true
            file
        } catch (error: Throwable) {
            runCatching { mediaRecorder?.release() }
            mediaRecorder = null
            isRecording = false
            MicrophoneSessionCoordinator.release(ownerId)
            throw error
        }
    }

    fun stop(): File? = stopInternal(releaseLease = true)

    private fun stopInternal(releaseLease: Boolean): File? {
        if (!isRecording) {
            if (releaseLease) MicrophoneSessionCoordinator.release(ownerId)
            return currentFile
        }

        val file = currentFile
        try {
            mediaRecorder?.stop()
        } catch (_: RuntimeException) {
            file?.delete()
            currentFile = null
        } finally {
            runCatching { mediaRecorder?.reset() }
            runCatching { mediaRecorder?.release() }
            mediaRecorder = null
            isRecording = false
            if (releaseLease) MicrophoneSessionCoordinator.release(ownerId)
        }
        return currentFile
    }

    fun release() {
        if (isRecording) {
            stopInternal(releaseLease = true)
        } else {
            runCatching { mediaRecorder?.release() }
            mediaRecorder = null
            MicrophoneSessionCoordinator.release(ownerId)
        }
    }
}

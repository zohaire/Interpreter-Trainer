package com.interpretertrainer.app.media

import android.content.Context
import android.media.MediaRecorder
import android.os.Environment
import java.io.File

class ShadowingRecorder(private val context: Context) {
    private var mediaRecorder: MediaRecorder? = null

    var isRecording: Boolean = false
        private set

    var currentFile: File? = null
        private set

    fun start(): File {
        check(!isRecording) { "A recording is already in progress." }

        val directory = context.getExternalFilesDir(Environment.DIRECTORY_MUSIC)
            ?: context.filesDir
        val file = File(directory, "shadowing_${System.currentTimeMillis()}.m4a")

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
        return file
    }

    fun stop(): File? {
        if (!isRecording) return currentFile

        val file = currentFile
        try {
            mediaRecorder?.stop()
        } catch (_: RuntimeException) {
            file?.delete()
            currentFile = null
        } finally {
            mediaRecorder?.reset()
            mediaRecorder?.release()
            mediaRecorder = null
            isRecording = false
        }
        return currentFile
    }

    fun release() {
        if (isRecording) {
            stop()
        } else {
            mediaRecorder?.release()
            mediaRecorder = null
        }
    }
}

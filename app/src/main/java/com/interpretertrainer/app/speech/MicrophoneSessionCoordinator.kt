package com.interpretertrainer.app.speech

import android.os.Handler
import android.os.Looper

/**
 * Coordinates exclusive microphone users inside Interpreter Trainer.
 *
 * Android speech recognition and MediaRecorder both want the device microphone. Starting one while
 * another still owns the audio input is a common source of ERROR_RECOGNIZER_BUSY, ERROR_AUDIO and
 * recorder start failures. Every microphone feature acquires this coordinator first; the previous
 * owner is cleanly pre-empted before the next one starts.
 */
object MicrophoneSessionCoordinator {
    private val mainHandler = Handler(Looper.getMainLooper())
    private val lock = Any()

    private data class Lease(
        val owner: String,
        val onPreempt: () -> Unit
    )

    @Volatile
    private var activeLease: Lease? = null

    fun acquire(owner: String, onPreempt: () -> Unit): Boolean {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            mainHandler.post { acquire(owner, onPreempt) }
            return false
        }

        val previous = synchronized(lock) {
            val current = activeLease
            if (current?.owner == owner) {
                activeLease = Lease(owner, onPreempt)
                return true
            }
            activeLease = Lease(owner, onPreempt)
            current
        }

        previous?.let { runCatching { it.onPreempt() } }
        return true
    }

    fun release(owner: String) {
        synchronized(lock) {
            if (activeLease?.owner == owner) activeLease = null
        }
    }

    fun isOwner(owner: String): Boolean = activeLease?.owner == owner

    fun activeOwner(): String? = activeLease?.owner
}

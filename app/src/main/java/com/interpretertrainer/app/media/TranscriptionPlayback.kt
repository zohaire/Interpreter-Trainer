package com.interpretertrainer.app.media

/** Main-thread coordination: restore only media that was playing before recognition started. */
object TranscriptionPlayback {
    private val listeners = mutableMapOf<Any, (Boolean) -> Unit>()
    fun register(owner: Any, listener: (Boolean) -> Unit) { listeners[owner] = listener }
    fun unregister(owner: Any) { listeners.remove(owner) }
    fun beforeRecognition() { listeners.values.toList().forEach { it(false) } }
    fun recognitionReady() { listeners.values.toList().forEach { it(true) } }
}

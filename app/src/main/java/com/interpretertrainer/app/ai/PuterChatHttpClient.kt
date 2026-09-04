package com.interpretertrainer.app.ai

import java.io.IOException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.util.concurrent.atomic.AtomicBoolean
import javax.net.ssl.SSLException

/** One authenticated chat call to Puter. Never follows redirects or changes TLS verification. */
internal class PuterChatHttpClient(
    private val openConnection: () -> HttpURLConnection = {
        URL(ENDPOINT).openConnection() as HttpURLConnection
    }
) {
    private val cancelled = AtomicBoolean(false)
    @Volatile private var connection: HttpURLConnection? = null

    fun cancel() {
        cancelled.set(true)
        connection?.disconnect()
    }

    fun execute(body: String, emit: (kind: String, data: String, status: Int) -> Unit) {
        fun send(kind: String, data: String = "", status: Int = 0) {
            if (!cancelled.get()) emit(kind, data, status)
        }
        var http: HttpURLConnection? = null
        try {
            if (cancelled.get()) return
            val active = openConnection()
            http = active
            connection = active
            if (cancelled.get()) return
            active.requestMethod = "POST"
            active.instanceFollowRedirects = false
            active.connectTimeout = 15_000
            active.readTimeout = 30_000
            active.useCaches = false
            active.doOutput = true
            active.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            active.setRequestProperty("Accept", "application/x-ndjson, application/json")
            val bytes = body.toByteArray(Charsets.UTF_8)
            active.setFixedLengthStreamingMode(bytes.size)
            active.outputStream.use { it.write(bytes) }
            val status = active.responseCode
            if (status !in 200..299) {
                val response = active.errorStream?.bufferedReader(Charsets.UTF_8)?.use {
                    val chars = CharArray(16_384)
                    val count = it.read(chars)
                    if (count > 0) String(chars, 0, count) else ""
                }.orEmpty()
                send("http_error", response, status)
                return
            }
            active.inputStream.bufferedReader(Charsets.UTF_8).use { reader ->
                // Puter's SDK consumes one JSON value per line for streamed chat responses.
                if (active.contentType.orEmpty().substringBefore(';').trim() == "application/x-ndjson") {
                    send("started", status = status)
                    val line = StringBuilder()
                    val buffer = CharArray(2048)
                    var total = 0
                    while (!cancelled.get()) {
                        val count = reader.read(buffer)
                        if (count < 0) break
                        total += count
                        if (total > MAX_RESPONSE_CHARS) throw ResponseTooLarge()
                        for (index in 0 until count) {
                            val char = buffer[index]
                            if (char == '\n') {
                                val part = line.toString().trim()
                                if (part.isNotEmpty()) send("part", part, status)
                                line.setLength(0)
                            } else line.append(char)
                        }
                    }
                    if (line.isNotBlank()) send("part", line.toString().trim(), status)
                    send("done", status = status)
                } else {
                    val response = StringBuilder()
                    val buffer = CharArray(2048)
                    while (!cancelled.get()) {
                        val count = reader.read(buffer)
                        if (count < 0) break
                        response.append(buffer, 0, count)
                        if (response.length > MAX_RESPONSE_CHARS) throw ResponseTooLarge()
                    }
                    send("result", response.toString(), status)
                }
            }
        } catch (_: SocketTimeoutException) {
            send("transport_error", "REQUEST_TIMEOUT")
        } catch (_: SSLException) {
            send("transport_error", "TLS_ERROR")
        } catch (_: ResponseTooLarge) {
            send("transport_error", "RESPONSE_TOO_LARGE")
        } catch (_: IOException) {
            send("transport_error", "NETWORK_ERROR")
        } catch (_: Exception) {
            send("transport_error", "NETWORK_ERROR")
        } finally {
            http?.disconnect()
            connection = null
        }
    }

    private class ResponseTooLarge : IOException()

    companion object {
        const val ENDPOINT = "https://api.puter.com/drivers/call"
        private const val MAX_RESPONSE_CHARS = 4 * 1024 * 1024
    }
}

package com.interpretertrainer.app.ai

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.DataOutputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

class AiCoachClient {
    data class ShadowingFeedback(
        val transcript: String,
        val feedback: String,
        val score: Int?
    )

    suspend fun askCoach(
        baseUrl: String,
        message: String,
        languageTag: String,
        context: String = ""
    ): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            require(baseUrl.isNotBlank()) { "AI backend URL is not configured." }
            require(message.isNotBlank()) { "Write a question first." }

            val body = JSONObject()
                .put("message", message)
                .put("language", languageTag)
                .put("context", context)
                .toString()

            val connection = openConnection("${baseUrl.trimEnd('/')}/api/coach", "application/json")
            connection.outputStream.bufferedWriter(Charsets.UTF_8).use { it.write(body) }
            readJsonResponse(connection).getString("reply")
        }
    }

    suspend fun analyzeShadowing(
        baseUrl: String,
        recording: File,
        languageTag: String,
        sourceName: String?,
        notes: String,
        speed: Float
    ): Result<ShadowingFeedback> = withContext(Dispatchers.IO) {
        runCatching {
            require(baseUrl.isNotBlank()) { "AI backend URL is not configured." }
            require(recording.exists()) { "The shadowing recording is no longer available." }

            val boundary = "InterpreterTrainerBoundary${System.currentTimeMillis()}"
            val url = URL("${baseUrl.trimEnd('/')}/api/shadowing-feedback")
            val connection = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 20_000
                readTimeout = 120_000
                doOutput = true
                setRequestProperty("Accept", "application/json")
                setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
            }

            DataOutputStream(connection.outputStream).use { output ->
                writeField(output, boundary, "language", languageTag)
                writeField(output, boundary, "sourceName", sourceName.orEmpty())
                writeField(output, boundary, "notes", notes)
                writeField(output, boundary, "speed", speed.toString())
                writeFile(output, boundary, "recording", recording)
                output.writeBytes("--$boundary--\r\n")
                output.flush()
            }

            val json = readJsonResponse(connection)
            ShadowingFeedback(
                transcript = json.optString("transcript"),
                feedback = json.getString("feedback"),
                score = if (json.has("score") && !json.isNull("score")) json.optInt("score") else null
            )
        }
    }

    private fun openConnection(url: String, contentType: String): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 20_000
            readTimeout = 90_000
            doOutput = true
            setRequestProperty("Content-Type", contentType)
            setRequestProperty("Accept", "application/json")
        }

    private fun readJsonResponse(connection: HttpURLConnection): JSONObject {
        val code = connection.responseCode
        val stream = if (code in 200..299) connection.inputStream else connection.errorStream
        val text = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
        connection.disconnect()

        if (code !in 200..299) {
            val message = runCatching { JSONObject(text).optString("error") }.getOrNull()
                ?.takeIf { it.isNotBlank() }
                ?: "AI server returned HTTP $code"
            error(message)
        }
        return JSONObject(text)
    }

    private fun writeField(output: DataOutputStream, boundary: String, name: String, value: String) {
        output.writeBytes("--$boundary\r\n")
        output.writeBytes("Content-Disposition: form-data; name=\"$name\"\r\n\r\n")
        output.write(value.toByteArray(Charsets.UTF_8))
        output.writeBytes("\r\n")
    }

    private fun writeFile(output: DataOutputStream, boundary: String, name: String, file: File) {
        output.writeBytes("--$boundary\r\n")
        output.writeBytes("Content-Disposition: form-data; name=\"$name\"; filename=\"${file.name}\"\r\n")
        output.writeBytes("Content-Type: audio/mp4\r\n\r\n")
        BufferedInputStream(file.inputStream()).use { input ->
            val buffer = ByteArray(16 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count <= 0) break
                output.write(buffer, 0, count)
            }
        }
        output.writeBytes("\r\n")
    }
}

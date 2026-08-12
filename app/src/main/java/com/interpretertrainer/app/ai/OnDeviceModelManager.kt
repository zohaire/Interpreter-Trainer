package com.interpretertrainer.app.ai

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * Owns the one-time download of the open-weight neural model used by Interpreter AI.
 * The model is stored in app-private storage and never bundled into the APK.
 */
object OnDeviceModelManager {
    const val MODEL_LABEL = "Qwen2.5-0.5B-Instruct Q4_0"
    const val MODEL_DOWNLOAD_SIZE_LABEL = "~429 MB"

    private const val MODEL_FILE_NAME = "qwen2.5-0.5b-instruct-q4_0.gguf"
    private const val MODEL_URL =
        "https://huggingface.co/Qwen/Qwen2.5-0.5B-Instruct-GGUF/resolve/main/qwen2.5-0.5b-instruct-q4_0.gguf?download=true"
    private const val MODEL_SHA256 =
        "7671c0c304e6ce5a7fc577bcb12aba01e2c155cc2efd29b2213c95b18edaf6ed"
    private const val MIN_EXPECTED_BYTES = 400_000_000L

    private fun modelDirectory(context: Context): File =
        File(context.filesDir, "interpreter_ai").apply { mkdirs() }

    fun modelFile(context: Context): File = File(modelDirectory(context), MODEL_FILE_NAME)

    private fun verifiedMarker(context: Context): File =
        File(modelDirectory(context), "$MODEL_FILE_NAME.verified")

    fun isInstalled(context: Context): Boolean {
        val file = modelFile(context)
        return file.isFile && file.length() >= MIN_EXPECTED_BYTES && verifiedMarker(context).isFile
    }

    fun installedBytes(context: Context): Long = modelFile(context).takeIf { it.exists() }?.length() ?: 0L

    suspend fun download(
        context: Context,
        onProgress: (downloadedBytes: Long, totalBytes: Long) -> Unit
    ): File = withContext(Dispatchers.IO) {
        if (isInstalled(context)) return@withContext modelFile(context)

        val finalFile = modelFile(context)
        val tempFile = File(finalFile.parentFile, "$MODEL_FILE_NAME.part")
        val marker = verifiedMarker(context)
        marker.delete()
        tempFile.delete()

        val connection = (URL(MODEL_URL).openConnection() as HttpURLConnection).apply {
            instanceFollowRedirects = true
            connectTimeout = 30_000
            readTimeout = 60_000
            requestMethod = "GET"
            setRequestProperty("User-Agent", "InterpreterTrainer/0.2 Android")
        }

        try {
            connection.connect()
            if (connection.responseCode !in 200..299) {
                error("Model download failed with HTTP ${connection.responseCode}")
            }

            val total = connection.contentLengthLong.coerceAtLeast(0L)
            val digest = MessageDigest.getInstance("SHA-256")
            var downloaded = 0L
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE * 4)

            connection.inputStream.buffered().use { input ->
                FileOutputStream(tempFile).buffered().use { output ->
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        output.write(buffer, 0, count)
                        digest.update(buffer, 0, count)
                        downloaded += count
                        onProgress(downloaded, total)
                    }
                }
            }

            require(tempFile.length() >= MIN_EXPECTED_BYTES) {
                "Downloaded model is incomplete (${tempFile.length()} bytes)."
            }

            val actualSha = digest.digest().joinToString("") { "%02x".format(it) }
            require(actualSha.equals(MODEL_SHA256, ignoreCase = true)) {
                "Model verification failed. Delete the partial model and try again."
            }

            if (finalFile.exists()) finalFile.delete()
            require(tempFile.renameTo(finalFile)) { "Could not finish installing the AI model." }
            marker.writeText(MODEL_SHA256)
            onProgress(finalFile.length(), finalFile.length())
            finalFile
        } catch (t: Throwable) {
            tempFile.delete()
            throw t
        } finally {
            connection.disconnect()
        }
    }

    fun delete(context: Context) {
        modelFile(context).delete()
        File(modelDirectory(context), "$MODEL_FILE_NAME.part").delete()
        verifiedMarker(context).delete()
    }
}

package com.interpretertrainer.app.ai

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * Owns the one-time/resumable download of the open-weight neural model used by Interpreter AI.
 * The model is stored in app-private storage and never bundled into the APK.
 */
object OnDeviceModelManager {
    // This artifact has published Android CPU/GPU benchmark results with LiteRT-LM.
    // We intentionally prefer it over the newer 347 MB no-think artifact, which failed to
    // initialize on a real arm64-v8a device with LiteRT-LM 0.14.0.
    const val MODEL_LABEL = "Qwen3-0.6B mixed INT4 (Android-tested)"
    const val MODEL_DOWNLOAD_SIZE_LABEL = "~475 MB"

    private const val MODEL_FILE_NAME = "qwen3_0_6b_mixed_int4.litertlm"
    private const val MODEL_URL =
        "https://huggingface.co/litert-community/Qwen3-0.6B/resolve/main/qwen3_0_6b_mixed_int4.litertlm?download=true"

    // Published artifact is roughly 475 MiB. This protects against HTML/error pages and
    // truncated downloads while leaving room for repository-side metadata changes.
    private const val MIN_EXPECTED_BYTES = 450_000_000L

    private val LEGACY_MODEL_FILES = listOf(
        "qwen2.5-0.5b-instruct-q4_0.gguf",
        "qwen3_0.6b_nothink_q4_block32_ekv1280.litertlm"
    )

    private fun modelDirectory(context: Context): File =
        File(context.filesDir, "interpreter_ai").apply { mkdirs() }

    fun modelFile(context: Context): File = File(modelDirectory(context), MODEL_FILE_NAME)

    private fun completionMarker(context: Context): File =
        File(modelDirectory(context), "$MODEL_FILE_NAME.complete")

    fun isInstalled(context: Context): Boolean {
        val file = modelFile(context)
        return file.isFile && file.length() >= MIN_EXPECTED_BYTES && completionMarker(context).isFile
    }

    fun installedBytes(context: Context): Long = modelFile(context).takeIf { it.exists() }?.length() ?: 0L

    /**
     * Downloads the model with HTTP Range resume when supported by the host.
     * A failed network connection therefore does not normally throw away hundreds of MB.
     */
    suspend fun download(
        context: Context,
        onProgress: (downloadedBytes: Long, totalBytes: Long) -> Unit
    ): File = withContext(Dispatchers.IO) {
        cleanupLegacyArtifacts(context)
        if (isInstalled(context)) return@withContext modelFile(context)

        val finalFile = modelFile(context)
        val tempFile = File(finalFile.parentFile, "$MODEL_FILE_NAME.part")
        val marker = completionMarker(context)
        marker.delete()

        var existing = tempFile.takeIf { it.exists() }?.length() ?: 0L
        var connection = openConnection(existing)

        try {
            connection.connect()
            var response = connection.responseCode

            // If the server ignored Range and returned a full file, restart safely from zero.
            if (existing > 0L && response == HttpURLConnection.HTTP_OK) {
                connection.disconnect()
                tempFile.delete()
                existing = 0L
                connection = openConnection(0L)
                connection.connect()
                response = connection.responseCode
            }

            if (response !in 200..299) {
                error("Model download failed with HTTP $response. Your partial download was kept for retry.")
            }

            val remainingLength = connection.contentLengthLong.coerceAtLeast(0L)
            val total = when {
                response == HttpURLConnection.HTTP_PARTIAL && remainingLength > 0L -> existing + remainingLength
                remainingLength > 0L -> remainingLength
                else -> 0L
            }

            var downloaded = existing
            onProgress(downloaded, total)
            val append = response == HttpURLConnection.HTTP_PARTIAL && existing > 0L
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE * 8)

            connection.inputStream.buffered().use { input ->
                FileOutputStream(tempFile, append).buffered().use { output ->
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        output.write(buffer, 0, count)
                        downloaded += count
                        onProgress(downloaded, total)
                    }
                }
            }

            require(tempFile.length() >= MIN_EXPECTED_BYTES) {
                "Downloaded model is incomplete (${tempFile.length()} bytes). Tap Try again to resume it."
            }

            if (finalFile.exists()) finalFile.delete()
            require(tempFile.renameTo(finalFile)) { "Could not finish installing the AI model." }
            marker.writeText("${finalFile.length()}")
            onProgress(finalFile.length(), finalFile.length())
            finalFile
        } catch (t: Throwable) {
            // Keep .part so a later Try again can resume instead of downloading from zero.
            throw t
        } finally {
            connection.disconnect()
        }
    }

    private fun openConnection(existingBytes: Long): HttpURLConnection =
        (URL(MODEL_URL).openConnection() as HttpURLConnection).apply {
            instanceFollowRedirects = true
            connectTimeout = 30_000
            readTimeout = 90_000
            requestMethod = "GET"
            setRequestProperty("User-Agent", "InterpreterTrainer/0.3.1 Android")
            if (existingBytes > 0L) setRequestProperty("Range", "bytes=$existingBytes-")
        }

    private fun cleanupLegacyArtifacts(context: Context) {
        val dir = modelDirectory(context)
        LEGACY_MODEL_FILES.forEach { fileName ->
            File(dir, fileName).delete()
            File(dir, "$fileName.part").delete()
            File(dir, "$fileName.verified").delete()
            File(dir, "$fileName.complete").delete()
        }
    }

    fun delete(context: Context) {
        modelFile(context).delete()
        File(modelDirectory(context), "$MODEL_FILE_NAME.part").delete()
        completionMarker(context).delete()
        cleanupLegacyArtifacts(context)
    }
}

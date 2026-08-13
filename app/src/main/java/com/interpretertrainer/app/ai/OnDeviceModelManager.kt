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
    const val MODEL_LABEL = "Qwen3-0.6B mixed INT4 (Android-tested)"
    const val MODEL_DOWNLOAD_SIZE_LABEL = "~475 MB"

    private const val MODEL_FILE_NAME = "qwen3_0_6b_mixed_int4.litertlm"
    private const val MODEL_URL =
        "https://huggingface.co/litert-community/Qwen3-0.6B/resolve/main/qwen3_0_6b_mixed_int4.litertlm?download=true"

    // Protect against HTML/error pages and obviously truncated downloads.
    private const val MIN_EXPECTED_BYTES = 450_000_000L

    private val LEGACY_MODEL_FILES = listOf(
        "qwen2.5-0.5b-instruct-q4_0.gguf",
        "qwen3_0.6b_nothink_q4_block32_ekv1280.litertlm"
    )

    private fun modelDirectory(context: Context): File =
        File(context.filesDir, "interpreter_ai").apply { mkdirs() }

    fun modelFile(context: Context): File = File(modelDirectory(context), MODEL_FILE_NAME)

    private fun partialFile(context: Context): File =
        File(modelDirectory(context), "$MODEL_FILE_NAME.part")

    private fun completionMarker(context: Context): File =
        File(modelDirectory(context), "$MODEL_FILE_NAME.complete")

    fun isInstalled(context: Context): Boolean {
        val file = modelFile(context)
        return file.isFile && file.length() >= MIN_EXPECTED_BYTES && completionMarker(context).isFile
    }

    fun installedBytes(context: Context): Long = when {
        modelFile(context).exists() -> modelFile(context).length()
        partialFile(context).exists() -> partialFile(context).length()
        else -> 0L
    }

    /**
     * Downloads the model with HTTP Range resume when supported by the host.
     *
     * HTTP 416 deserves special handling: it usually means the local .part file is already at the
     * server's end-of-file (for example after the last bytes arrived but before we wrote the
     * completion marker), or that the local resume point no longer matches the remote object.
     * In the first case we finalize locally without another network transfer. In the second case we
     * delete only the invalid .part file and retry once from byte zero. We never require an app
     * uninstall to recover a model download.
     */
    suspend fun download(
        context: Context,
        onProgress: (downloadedBytes: Long, totalBytes: Long) -> Unit
    ): File = withContext(Dispatchers.IO) {
        cleanupLegacyArtifacts(context)
        if (isInstalled(context)) return@withContext modelFile(context)

        val finalFile = modelFile(context)
        val tempFile = partialFile(context)
        val marker = completionMarker(context)
        marker.delete()

        // A previous process may have renamed the complete model but crashed before writing its
        // marker. Recover that case locally instead of downloading hundreds of MB again.
        if (finalFile.isFile && finalFile.length() >= MIN_EXPECTED_BYTES) {
            marker.writeText(finalFile.length().toString())
            onProgress(finalFile.length(), finalFile.length())
            return@withContext finalFile
        }

        var existing = tempFile.takeIf { it.exists() }?.length() ?: 0L
        var restartedFromZero = false

        while (true) {
            val connection = openConnection(existing)
            try {
                connection.connect()
                val response = connection.responseCode

                if (response == HTTP_RANGE_NOT_SATISFIABLE && existing > 0L) {
                    val remoteTotal = parseRemoteTotal(connection.getHeaderField("Content-Range"))

                    // A 416 response reports the remote object length in Content-Range. If our
                    // local partial file exactly matches that length, the transfer is already done.
                    if (remoteTotal != null && existing == remoteTotal && existing >= MIN_EXPECTED_BYTES) {
                        return@withContext finalizeDownloadedModel(
                            tempFile = tempFile,
                            finalFile = finalFile,
                            marker = marker,
                            expectedTotal = remoteTotal,
                            onProgress = onProgress
                        )
                    }

                    // The resume point is stale/invalid. Preserve every other piece of app data and
                    // restart only this model file once from zero.
                    if (!restartedFromZero) {
                        tempFile.delete()
                        existing = 0L
                        restartedFromZero = true
                        continue
                    }

                    error(
                        "The model server rejected the download range twice (HTTP 416). " +
                            "Please try again later; no app uninstall is required."
                    )
                }

                if (response !in 200..299) {
                    error("Model download failed with HTTP $response. Your partial download was kept for retry.")
                }

                // A 200 response to a Range request means the host ignored Range and is sending the
                // entire file. Overwrite the .part file from zero using this response; do not append.
                val append = response == HttpURLConnection.HTTP_PARTIAL && existing > 0L
                if (!append && existing > 0L) {
                    existing = 0L
                    tempFile.delete()
                }

                val remoteTotal = parseRemoteTotal(connection.getHeaderField("Content-Range"))
                val remainingLength = connection.contentLengthLong.coerceAtLeast(0L)
                val total = when {
                    remoteTotal != null -> remoteTotal
                    response == HttpURLConnection.HTTP_PARTIAL && remainingLength > 0L -> existing + remainingLength
                    remainingLength > 0L -> remainingLength
                    else -> 0L
                }

                // If the server resumed from a different offset than requested, appending would
                // corrupt the model. Restart only the model transfer, once.
                if (response == HttpURLConnection.HTTP_PARTIAL) {
                    val returnedStart = parseRangeStart(connection.getHeaderField("Content-Range"))
                    if (returnedStart != null && returnedStart != existing) {
                        if (!restartedFromZero) {
                            tempFile.delete()
                            existing = 0L
                            restartedFromZero = true
                            continue
                        }
                        error("The model server returned an inconsistent byte range. Please try again later.")
                    }
                }

                var downloaded = existing
                onProgress(downloaded, total)
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

                return@withContext finalizeDownloadedModel(
                    tempFile = tempFile,
                    finalFile = finalFile,
                    marker = marker,
                    expectedTotal = total.takeIf { it > 0L },
                    onProgress = onProgress
                )
            } finally {
                connection.disconnect()
            }
        }
    }

    private fun finalizeDownloadedModel(
        tempFile: File,
        finalFile: File,
        marker: File,
        expectedTotal: Long?,
        onProgress: (Long, Long) -> Unit
    ): File {
        val bytes = tempFile.length()
        require(bytes >= MIN_EXPECTED_BYTES) {
            "Downloaded model is incomplete ($bytes bytes). Tap Try again to resume it."
        }
        if (expectedTotal != null) {
            require(bytes == expectedTotal) {
                "Downloaded model size does not match the server ($bytes / $expectedTotal bytes). Tap Try again."
            }
        }

        if (finalFile.exists()) finalFile.delete()
        require(tempFile.renameTo(finalFile)) { "Could not finish installing the AI model." }
        marker.writeText(finalFile.length().toString())
        onProgress(finalFile.length(), expectedTotal ?: finalFile.length())
        return finalFile
    }

    // Returns the total object length from a Content-Range response, including an unsatisfied
    // range response where the header contains an asterisk instead of a start/end pair.
    private fun parseRemoteTotal(contentRange: String?): Long? {
        if (contentRange.isNullOrBlank()) return null
        val slash = contentRange.lastIndexOf('/')
        if (slash < 0 || slash == contentRange.lastIndex) return null
        return contentRange.substring(slash + 1).trim().takeUnless { it == "*" }?.toLongOrNull()
    }

    private fun parseRangeStart(contentRange: String?): Long? {
        if (contentRange.isNullOrBlank()) return null
        val value = contentRange.substringAfter("bytes", "").trim()
        if (value.isBlank() || value.startsWith("*/")) return null
        return value.substringBefore('-').trim().toLongOrNull()
    }

    private fun openConnection(existingBytes: Long): HttpURLConnection =
        (URL(MODEL_URL).openConnection() as HttpURLConnection).apply {
            instanceFollowRedirects = true
            connectTimeout = 30_000
            readTimeout = 90_000
            requestMethod = "GET"
            setRequestProperty("User-Agent", "InterpreterTrainer/0.3.2 Android")
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
        partialFile(context).delete()
        completionMarker(context).delete()
        cleanupLegacyArtifacts(context)
    }

    private const val HTTP_RANGE_NOT_SATISFIABLE = 416
}

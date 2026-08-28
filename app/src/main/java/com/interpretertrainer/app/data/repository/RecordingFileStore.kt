package com.interpretertrainer.app.data.repository

import java.io.File

/**
 * Owns lifecycle cleanup for recordings created by [com.interpretertrainer.app.media.ShadowingRecorder].
 * Paths are canonicalized and must stay inside an app-owned recording directory before deletion.
 */
class RecordingFileStore(
    roots: List<File>,
    private val nowMillis: () -> Long = System::currentTimeMillis
) {
    private val canonicalRoots = roots
        .mapNotNull { runCatching { it.canonicalFile }.getOrNull() }
        .distinctBy { it.path }

    fun deleteOwned(path: String?): Boolean {
        val file = ownedRecording(path) ?: return false
        return !file.exists() || file.delete()
    }

    fun pruneOrphans(referencedPaths: Set<String>, minimumAgeMillis: Long = DAY_MILLIS): Int {
        val referenced = referencedPaths.mapNotNullTo(mutableSetOf()) { path ->
            runCatching { File(path).canonicalPath }.getOrNull()
        }
        val cutoff = nowMillis() - minimumAgeMillis.coerceAtLeast(0L)
        var removed = 0

        canonicalRoots.forEach { root ->
            root.listFiles().orEmpty().forEach fileLoop@{ file ->
                val owned = ownedRecording(file.path) ?: return@fileLoop
                if (
                    owned.isFile &&
                    owned.lastModified() <= cutoff &&
                    owned.canonicalPath !in referenced &&
                    owned.delete()
                ) {
                    removed++
                }
            }
        }
        return removed
    }

    private fun ownedRecording(path: String?): File? {
        if (path.isNullOrBlank()) return null
        val candidate = runCatching { File(path).canonicalFile }.getOrNull() ?: return null
        if (!RECORDING_NAME.matches(candidate.name)) return null
        val insideOwnedRoot = canonicalRoots.any { root ->
            candidate.path.startsWith(root.path + File.separator)
        }
        return candidate.takeIf { insideOwnedRoot }
    }

    private companion object {
        const val DAY_MILLIS = 24L * 60L * 60L * 1_000L
        val RECORDING_NAME = Regex("interpreter_[0-9]+\\.m4a")
    }
}

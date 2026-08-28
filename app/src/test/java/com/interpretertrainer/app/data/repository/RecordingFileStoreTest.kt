package com.interpretertrainer.app.data.repository

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class RecordingFileStoreTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun deleteOwned_removesOnlyRecorderFilesInsideOwnedRoot() {
        val ownedRoot = temporaryFolder.newFolder("recordings")
        val outsideRoot = temporaryFolder.newFolder("outside")
        val owned = File(ownedRoot, "interpreter_123.m4a").apply { writeText("audio") }
        val outside = File(outsideRoot, "interpreter_456.m4a").apply { writeText("audio") }
        val unrelated = File(ownedRoot, "notes.txt").apply { writeText("keep") }
        val store = RecordingFileStore(listOf(ownedRoot))

        assertTrue(store.deleteOwned(owned.path))
        assertFalse(owned.exists())
        assertFalse(store.deleteOwned(outside.path))
        assertFalse(store.deleteOwned(unrelated.path))
        assertTrue(outside.exists())
        assertTrue(unrelated.exists())
    }

    @Test
    fun pruneOrphans_keepsReferencedAndRecentRecordings() {
        val now = 2_000_000_000_000L
        val ownedRoot = temporaryFolder.newFolder("recordings")
        val orphan = File(ownedRoot, "interpreter_100.m4a").apply {
            writeText("audio")
            setLastModified(now - 2L * 24L * 60L * 60L * 1_000L)
        }
        val referenced = File(ownedRoot, "interpreter_200.m4a").apply {
            writeText("audio")
            setLastModified(now - 2L * 24L * 60L * 60L * 1_000L)
        }
        val recent = File(ownedRoot, "interpreter_300.m4a").apply {
            writeText("audio")
            setLastModified(now - 60_000L)
        }
        val store = RecordingFileStore(listOf(ownedRoot), nowMillis = { now })

        assertTrue(store.pruneOrphans(setOf(referenced.path)) == 1)
        assertFalse(orphan.exists())
        assertTrue(referenced.exists())
        assertTrue(recent.exists())
    }
}

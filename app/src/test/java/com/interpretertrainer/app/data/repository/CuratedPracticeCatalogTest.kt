package com.interpretertrainer.app.data.repository

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CuratedPracticeCatalogTest {
    @Test
    fun containsTwentyAuthenticUnPracticeWindows() {
        val items = CuratedPracticeCatalog.items

        assertEquals(20, items.size)
        assertEquals(items.size, items.map { it.id }.distinct().size)
        assertTrue(items.all { it.isOfficial })
        assertTrue(items.all { it.durationMillis == 4 * 60 * 1000L })
        assertTrue(items.all { it.sourceUrl?.startsWith("https://gadebate.un.org/") == true })
        assertTrue(items.all { it.speaker.isNotBlank() && it.eventDate.isNotBlank() })
    }
}

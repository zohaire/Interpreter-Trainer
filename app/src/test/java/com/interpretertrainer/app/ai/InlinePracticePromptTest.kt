package com.interpretertrainer.app.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class InlinePracticePromptTest {
    @Test
    fun promptCarriesModeLanguagesTopicAndOutputConstraints() {
        val prompt = buildInlinePracticePrompt(
            InlinePracticeRequest(
                mode = PracticeGenerationMode.CONSECUTIVE,
                sourceLanguage = "French",
                targetLanguage = "Arabic (MSA)",
                topic = "renewable energy policy",
                length = PracticeTextLength.SHORT
            )
        )

        assertTrue(prompt.contains("Consecutive interpretation"))
        assertTrue(prompt.contains("Write in French"))
        assertTrue(prompt.contains("interpret it into Arabic (MSA)"))
        assertTrue(prompt.contains("renewable energy policy"))
        assertTrue(prompt.contains("approximately 120 words"))
        assertTrue(prompt.contains("Return only the finished source passage"))
    }

    @Test
    fun generatedTextIsTrimmedWithoutLargeBlankGaps() {
        val normalized = normalizeGeneratedPracticeText("  First line.  \r\n\r\n  \r\nSecond line.  \n")

        assertEquals("First line.\n\nSecond line.", normalized)
        assertFalse(normalized.endsWith("\n"))
    }
}

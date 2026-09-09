package com.interpretertrainer.app.ai

enum class PracticeGenerationMode(
    val title: String,
    internal val instruction: String
) {
    SIMULTANEOUS(
        title = "Simultaneous interpretation",
        instruction = "Write a cohesive speech with natural transitions, a realistic speaking rhythm, and enough density for simultaneous interpreting."
    ),
    SHADOWING(
        title = "Shadowing",
        instruction = "Write a fluent spoken-style passage with varied sentence length, clear phrasing, and natural rhythm for shadowing practice."
    ),
    CONSECUTIVE(
        title = "Consecutive interpretation",
        instruction = "Write a structured speech in distinct idea units, including details that reward note-taking and memory."
    ),
    TRANSCRIPTION(
        title = "Live transcription",
        instruction = "Write a realistic spoken passage with punctuation cues, names, numbers, and terminology for transcription practice."
    )
}

enum class PracticeTextLength(val label: String, val targetWords: Int, val maxTokens: Int) {
    SHORT("Short", 120, 240),
    STANDARD("Standard", 220, 420),
    EXTENDED("Extended", 350, 640)
}

data class InlinePracticeRequest(
    val mode: PracticeGenerationMode,
    val sourceLanguage: String,
    val targetLanguage: String? = null,
    val topic: String = "",
    val length: PracticeTextLength = PracticeTextLength.STANDARD
)

fun buildInlinePracticePrompt(request: InlinePracticeRequest): String {
    val topic = request.topic.trim().ifBlank { "a current professional or academic topic" }
    val arabicPolicy = if (request.sourceLanguage.contains("Arabic", ignoreCase = true)) {
        " Use Modern Standard Arabic (العربية الفصحى) only, unless the topic explicitly asks for a dialect."
    } else {
        ""
    }
    val direction = request.targetLanguage
        ?.takeIf { it.isNotBlank() && it != request.sourceLanguage }
        ?.let { " The learner will interpret it into $it." }
        .orEmpty()

    return """
        Create source material for ${request.mode.title} practice.
        Write in ${request.sourceLanguage}.$direction$arabicPolicy
        Topic: $topic.
        Target length: approximately ${request.length.targetWords} words.

        ${request.mode.instruction}
        Include realistic names, numbers, dates, and field-specific terminology where appropriate.
        Return only the finished source passage in natural paragraphs. Do not add a title, heading, label, translation, answer, analysis, instructions, markdown, quotation marks, or introductory commentary.
    """.trimIndent()
}

fun normalizeGeneratedPracticeText(value: String): String = value
    .replace("\r\n", "\n")
    .replace('\r', '\n')
    .lines()
    .map { it.trim() }
    .joinToString("\n")
    .replace(Regex("\n[\\t ]*\n(?:[\\t ]*\n)+"), "\n\n")
    .trim()

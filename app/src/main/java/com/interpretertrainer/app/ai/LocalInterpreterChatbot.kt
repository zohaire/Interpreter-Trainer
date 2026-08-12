package com.interpretertrainer.app.ai

import com.interpretertrainer.app.data.database.PracticeSessionEntity
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Offline conversational layer specialized for interpreter training.
 *
 * This is intentionally narrow: it answers interpreter-practice questions and can summarize
 * locally saved performance history. It never calls a network service or external AI provider.
 */
object LocalInterpreterChatbot {

    data class Reply(
        val text: String,
        val suggestedPrompts: List<String> = emptyList()
    )

    fun reply(message: String, sessions: List<PracticeSessionEntity>): Reply {
        val raw = message.trim()
        if (raw.isBlank()) return Reply("Ask me something about your interpreting practice.")
        val q = raw.lowercase(Locale.ROOT)

        return when {
            containsAny(q, "hello", "hi ", "hey", "bonjour", "salut", "مرحبا", "السلام") ->
                Reply(
                    "Hi. I’m your local Interpreter Coach. I can help with shadowing, consecutive interpreting, sight translation, note-taking, omissions, numbers, terminology, delivery, and your saved practice history.",
                    defaultPrompts()
                )

            containsAny(q, "am i improving", "progress", "improvement", "getting better", "compare my", "تحسن", "progrès") ->
                historyProgress(sessions)

            containsAny(q, "what should i practice", "practice today", "train today", "recommend practice", "what do i work on") ->
                practiceRecommendation(sessions)

            containsAny(q, "last session", "latest session", "recent session") ->
                latestSession(sessions)

            containsAny(q, "my history", "practice history", "how many sessions", "my sessions", "statistics", "stats") ->
                historyOverview(sessions)

            containsAny(q, "evaluate", "evaluation", "score me", "assess", "performance") ->
                Reply(
                    "I can evaluate a performance locally. Open the Evaluate tab, choose the practice mode and languages, then add the source transcript and your transcript. Timing is optional. For Shadowing, the evaluator can measure coverage, sequence, omissions, additions, numbers, names, pace and timing. Cross-language interpretation is scored only on observable evidence unless a semantic model is available.",
                    listOf("What does the score measure?", "How do I reduce omissions?", "How should I practice numbers?")
                )

            containsAny(q, "shadowing") ->
                Reply(
                    "For shadowing, train in layers: first preserve rhythm and continuous speech, then improve exact content retention. Start at 0.75× if you are dropping words, move to 1.0× when coverage is stable, and use headphones so your microphone captures your voice cleanly. After recording, compare omissions, sequence retention, pace and numbers instead of judging yourself only by how fluent you sounded.",
                    listOf("How do I reduce shadowing delay?", "What speed should I use?", "Evaluate my performance")
                )

            containsAny(q, "consecutive", "consecutive interpretation") ->
                Reply(
                    "In consecutive interpreting, your priority is meaning structure rather than sentence-by-sentence memory. Listen for the speaker’s idea, relation and direction, then note only anchors: names, numbers, key nouns, logical links and changes of position. If 30-second segments cause omissions, return to 15 seconds until your reconstruction is complete, then increase the segment length.",
                    listOf("How should I take notes?", "How do I remember more?", "What should I practice today?")
                )

            containsAny(q, "sight translation", "sight interpreting") ->
                Reply(
                    "For sight translation, scan the sentence before speaking, identify the main clause, mark terminology and numbers, then reformulate naturally in the target language. Avoid translating word by word. A useful drill is to give yourself 10–20 seconds to scan a paragraph, translate it aloud once, then review where syntax from the source language leaked into your target-language delivery.",
                    listOf("How do I avoid word-for-word translation?", "How can I improve reformulation?", "Evaluate my performance")
                )

            containsAny(q, "note taking", "note-taking", "notes", "symbols") ->
                Reply(
                    "Interpreter notes should trigger memory, not reproduce the speech. Write ideas vertically, show relationships with arrows, keep a stable symbol for cause/result/contrast, and always isolate numbers and proper names. If you are still writing when the next idea arrives, your notes are too detailed.",
                    listOf("Give me a note-taking drill", "How do I remember more?", "How should I practice numbers?")
                )

            containsAny(q, "omission", "omit", "missing information", "lose information") ->
                Reply(
                    "Frequent omissions usually come from overload, weak segmentation or attention being spent on wording too early. Reduce the chunk size, listen for complete ideas before reformulating, and review exactly what type of information disappears: numbers, qualifiers, examples, names or logical connectors. Train the recurring category separately rather than simply repeating the whole speech.",
                    listOf("What should I practice today?", "How do I remember more?", "How should I practice numbers?")
                )

            containsAny(q, "number", "numbers", "date", "dates", "figure", "figures", "percentage") ->
                Reply(
                    "Treat numbers as high-risk data. Write them immediately and separately from ordinary notes. Practice short audio containing dates, percentages, prices and large numbers, then repeat the content without looking at a transcript. Do not rely on semantic memory for figures: preserve them explicitly.",
                    listOf("How should I take notes?", "Why do I omit information?", "What should I practice today?")
                )

            containsAny(q, "name", "names", "proper noun", "proper nouns") ->
                Reply(
                    "Proper names are low-context items, so they are easy to lose. Note the first recognizable letters or a phonetic cue immediately, then continue listening. In practice, deliberately include several unfamiliar names and organizations so name retention becomes automatic rather than exceptional.",
                    listOf("How should I practice numbers?", "How should I take notes?", "Why do I omit information?")
                )

            containsAny(q, "fluency", "hesitation", "filler", "pace", "delivery") ->
                Reply(
                    "Fluency is not the same as speed. Aim for controlled continuity, clear phrasing and short silent pauses instead of fillers. If your delivery becomes faster than your planning capacity, accuracy normally falls. Record yourself, compare source and output duration, and work first on stable phrasing before increasing speed.",
                    listOf("How do I reduce fillers?", "What speed should I use for shadowing?", "Evaluate my performance")
                )

            containsAny(q, "memory", "remember", "retention") ->
                Reply(
                    "Interpretation memory improves when you encode meaning rather than words. After each short segment, ask yourself: who did what, why, with what result, and what changed? Reconstruct that structure before worrying about elegant wording. Gradually increase segment length only when the meaning chain remains complete.",
                    listOf("How do I reduce omissions?", "How should I take notes?", "What should I practice today?")
                )

            containsAny(q, "terminology", "vocabulary", "term", "glossary") ->
                Reply(
                    "For terminology, build small topic-specific glossaries rather than long general vocabulary lists. Store the term, a short definition, its equivalent in your working languages and one realistic sentence. Before interpreting a topic, activate the glossary by speaking the terms aloud in both directions.",
                    listOf("How do I prepare for a topic?", "How can I improve reformulation?", "What should I practice today?")
                )

            containsAny(q, "register", "reformulation", "word for word", "word-for-word") ->
                Reply(
                    "Good reformulation preserves meaning, tone and level of formality without copying source-language syntax. Listen for the proposition first, then rebuild it using normal target-language patterns. A useful exercise is to express the same idea in two different ways before translating it; that weakens dependence on the original wording.",
                    listOf("How do I avoid word-for-word translation?", "How can I improve sight translation?", "Evaluate my performance")
                )

            containsAny(q, "accuracy", "accurate") ->
                Reply(
                    "Accuracy should be checked in categories: core meaning, logical relations, qualifiers, numbers, names and terminology. A fluent output can still be inaccurate, so review evidence rather than overall impression. For same-language shadowing I can compare transcript coverage directly; for cross-language interpreting, this basic offline version avoids claiming semantic accuracy it cannot reliably measure.",
                    listOf("What does the score measure?", "How do I reduce omissions?", "Evaluate my performance")
                )

            else ->
                Reply(
                    "I’m specialized in interpreter training rather than general conversation. Ask me about shadowing, consecutive interpreting, sight translation, note-taking, memory, omissions, numbers, names, terminology, reformulation, fluency, or your saved practice progress. You can also use the Evaluate tab for a performance report.",
                    defaultPrompts()
                )
        }
    }

    private fun historyOverview(sessions: List<PracticeSessionEntity>): Reply {
        if (sessions.isEmpty()) {
            return Reply(
                "You do not have saved practice sessions yet. Complete and save a Shadowing, Consecutive, Sight Translation or Live Transcription session, then I can summarize your history.",
                listOf("What should I practice today?", "How should I start shadowing?")
            )
        }

        val counts = sessions.groupingBy { readableMode(it.practiceMode) }.eachCount().entries
            .sortedByDescending { it.value }
        val totalMinutes = sessions.sumOf { it.durationMillis }.toDouble() / 60_000.0
        val scored = extractScores(sessions)
        val summary = buildString {
            append("You have ${sessions.size} saved session${if (sessions.size == 1) "" else "s"}, totaling about ${totalMinutes.roundToInt()} minutes of recorded practice. ")
            append("By mode: ${counts.joinToString { "${it.key} ${it.value}" }}.")
            if (scored.isNotEmpty()) {
                append(" Your ${scored.size} locally scored session${if (scored.size == 1) " has" else "s have"} an average of ${scored.average().roundToInt()}/100.")
            }
        }
        return Reply(summary, listOf("Am I improving?", "What should I practice today?", "Tell me about my last session"))
    }

    private fun latestSession(sessions: List<PracticeSessionEntity>): Reply {
        val latest = sessions.maxByOrNull { it.startedAt }
            ?: return Reply("You do not have a saved session yet.", listOf("What should I practice today?"))
        val score = extractScore(latest.aiFeedback)
        val text = buildString {
            append("Your latest saved session is ${readableMode(latest.practiceMode)} (${latest.sourceLanguage} → ${latest.targetLanguage}). ")
            append("Duration: about ${(latest.durationMillis / 1000.0).roundToInt()} seconds.")
            score?.let { append(" The saved local coach score is $it/100.") }
            if (!latest.aiFeedback.isNullOrBlank()) append(" Its detailed coach feedback is available in Practice History.")
        }
        return Reply(text, listOf("Am I improving?", "What should I practice today?", "How do I improve this mode?"))
    }

    private fun historyProgress(sessions: List<PracticeSessionEntity>): Reply {
        if (sessions.size < 2) {
            return Reply(
                "I need at least two saved sessions to discuss progress. Keep saving comparable sessions so the trend is based on evidence rather than impression.",
                listOf("What should I practice today?", "Tell me about my last session")
            )
        }

        val chronological = sessions.sortedBy { it.startedAt }
        val scoredPairs = chronological.mapNotNull { session -> extractScore(session.aiFeedback)?.let { session to it } }
        if (scoredPairs.size >= 2) {
            val split = (scoredPairs.size / 2).coerceAtLeast(1)
            val earlier = scoredPairs.take(split).map { it.second }.average()
            val recent = scoredPairs.drop(split).map { it.second }.average()
            val delta = recent - earlier
            val direction = when {
                delta >= 5 -> "Your recent local scores are clearly higher"
                delta <= -5 -> "Your recent local scores are lower"
                else -> "Your recent local scores are broadly stable"
            }
            return Reply(
                "$direction: earlier average ${earlier.roundToInt()}/100 versus recent average ${recent.roundToInt()}/100. Use the same practice mode and similar difficulty when comparing sessions; otherwise the score trend can be misleading.",
                listOf("What should I practice today?", "Tell me about my last session", "How do I reduce omissions?")
            )
        }

        val recent = chronological.takeLast(minOf(3, chronological.size))
        return Reply(
            "You have ${sessions.size} saved sessions, but not enough of them contain local evaluation scores for a reliable score trend. Your latest ${recent.size} sessions include ${recent.joinToString { readableMode(it.practiceMode) }}. Generate and save local feedback on comparable sessions and I’ll be able to give you a stronger progress comparison.",
            listOf("Evaluate my performance", "What should I practice today?")
        )
    }

    private fun practiceRecommendation(sessions: List<PracticeSessionEntity>): Reply {
        if (sessions.isEmpty()) {
            return Reply(
                "Start with a 5–10 minute Shadowing session at 0.75× or 1.0×, save the recording, then do one 15-second Consecutive session. That gives me two different baselines to work with.",
                listOf("How should I start shadowing?", "How do I practice consecutive interpreting?")
            )
        }

        val counts = sessions.groupingBy { it.practiceMode }.eachCount()
        val coreModes = listOf("SHADOWING", "CONSECUTIVE", "SIGHT_TRANSLATION")
        val leastPracticed = coreModes.minByOrNull { counts[it] ?: 0 } ?: "SHADOWING"
        val scored = sessions.mapNotNull { session -> extractScore(session.aiFeedback)?.let { session to it } }
        val weakest = scored.minByOrNull { it.second }

        val recommendation = if (weakest != null && weakest.second < 75) {
            "Your lowest saved local score is ${weakest.second}/100 in ${readableMode(weakest.first.practiceMode)}. Repeat that mode today with slightly easier material, then compare the new score with the previous one."
        } else {
            "Your least-practiced core mode is ${readableMode(leastPracticed)}. Do a short session in that mode today so your training stays balanced."
        }
        return Reply(recommendation, listOf("How do I reduce omissions?", "How should I take notes?", "Evaluate my performance"))
    }

    private fun extractScores(sessions: List<PracticeSessionEntity>): List<Int> =
        sessions.mapNotNull { extractScore(it.aiFeedback) }

    private fun extractScore(feedback: String?): Int? {
        if (feedback.isNullOrBlank()) return null
        val regexes = listOf(
            Regex("Score:\\s*(\\d{1,3})\\s*/\\s*100", RegexOption.IGNORE_CASE),
            Regex("\\b(\\d{1,3})\\s*/\\s*100\\b")
        )
        return regexes.firstNotNullOfOrNull { regex ->
            regex.find(feedback)?.groupValues?.getOrNull(1)?.toIntOrNull()?.coerceIn(0, 100)
        }
    }

    private fun readableMode(mode: String): String = when (mode.uppercase(Locale.ROOT)) {
        "SHADOWING" -> "Shadowing"
        "CONSECUTIVE" -> "Consecutive"
        "SIGHT_TRANSLATION" -> "Sight Translation"
        "LIVE_TRANSCRIPTION" -> "Live Transcription"
        else -> mode.replace('_', ' ').lowercase().replaceFirstChar { it.titlecase(Locale.ROOT) }
    }

    private fun containsAny(text: String, vararg terms: String): Boolean = terms.any { text.contains(it) }

    private fun defaultPrompts(): List<String> = listOf(
        "What should I practice today?",
        "Am I improving?",
        "How do I reduce omissions?"
    )
}

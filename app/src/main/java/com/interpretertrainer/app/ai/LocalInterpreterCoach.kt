package com.interpretertrainer.app.ai

import com.interpretertrainer.app.model.PracticeMode
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * A deterministic, fully on-device feedback engine specialized for interpreter practice.
 *
 * It intentionally does not call a network service or a general-purpose language model.
 * Same-language modes such as Shadowing can be evaluated lexically. Cross-language modes
 * are evaluated only on observable markers (numbers, names, timing, length balance, etc.)
 * because token overlap is not a valid measure of translation meaning.
 */
object LocalInterpreterCoach {

    data class Metric(
        val label: String,
        val score: Int?,
        val detail: String
    )

    data class Report(
        val overallScore: Int?,
        val scoreLabel: String,
        val summary: String,
        val metrics: List<Metric>,
        val strengths: List<String>,
        val improvements: List<String>,
        val evidence: List<String>,
        val limitation: String?
    ) {
        fun asPlainText(): String = buildString {
            appendLine(scoreLabel)
            overallScore?.let { appendLine("Score: $it / 100") }
            appendLine(summary)
            appendLine()

            if (metrics.isNotEmpty()) {
                appendLine("Metrics")
                metrics.forEach { metric ->
                    val scoreText = metric.score?.let { "$it/100" } ?: "n/a"
                    appendLine("• ${metric.label}: $scoreText — ${metric.detail}")
                }
                appendLine()
            }

            if (strengths.isNotEmpty()) {
                appendLine("Strengths")
                strengths.forEach { appendLine("• $it") }
                appendLine()
            }

            if (improvements.isNotEmpty()) {
                appendLine("Improve next")
                improvements.forEach { appendLine("• $it") }
                appendLine()
            }

            if (evidence.isNotEmpty()) {
                appendLine("Evidence")
                evidence.forEach { appendLine("• $it") }
                appendLine()
            }

            limitation?.let {
                appendLine("Scope")
                append(it)
            }
        }.trim()
    }

    fun analyze(
        mode: PracticeMode,
        sourceText: String,
        traineeText: String,
        sourceLanguage: String,
        targetLanguage: String,
        sourceDurationMillis: Long? = null,
        traineeDurationMillis: Long? = null
    ): Report {
        val sourceTokens = tokenize(sourceText)
        val traineeTokens = tokenize(traineeText)
        val sameLanguage = sourceLanguage.equals(targetLanguage, ignoreCase = true)
        val lexicalComparable = sameLanguage && sourceTokens.isNotEmpty() && traineeTokens.isNotEmpty()

        val metrics = mutableListOf<Metric>()
        val weightedScores = mutableListOf<Pair<Int, Double>>()
        val strengths = mutableListOf<String>()
        val improvements = mutableListOf<String>()
        val evidence = mutableListOf<String>()

        var coverageScore: Int? = null
        var orderScore: Int? = null
        var additionRate: Double? = null

        if (lexicalComparable) {
            coverageScore = (multisetCoverage(sourceTokens, traineeTokens) * 100).roundToInt().coerceIn(0, 100)
            orderScore = (orderedSimilarity(sourceTokens, traineeTokens) * 100).roundToInt().coerceIn(0, 100)
            additionRate = additionRate(sourceTokens, traineeTokens)

            metrics += Metric(
                label = "Content coverage",
                score = coverageScore,
                detail = when {
                    coverageScore >= 90 -> "Most source words and content markers were preserved."
                    coverageScore >= 75 -> "Good coverage with some observable omissions."
                    else -> "A noticeable portion of the source was not reproduced."
                }
            )
            metrics += Metric(
                label = "Sequence retention",
                score = orderScore,
                detail = "Measures how closely the trainee transcript follows the source word order."
            )
            weightedScores += coverageScore to 0.45
            weightedScores += orderScore to 0.20

            if (coverageScore >= 88) strengths += "You preserved a high proportion of the source content."
            if (orderScore >= 88) strengths += "Your sequence closely followed the speaker, which is useful for shadowing control."
            if (coverageScore < 75) improvements += "Repeat the passage at a lower speed and focus on content words that disappeared from your output."
            if (orderScore < 70 && mode == PracticeMode.SHADOWING) improvements += "Work on staying closer to the speaker instead of reconstructing the sentence after a delay."

            val omitted = importantMissingTokens(sourceTokens, traineeTokens)
            val added = importantMissingTokens(traineeTokens, sourceTokens)
            if (omitted.isNotEmpty()) evidence += "Likely omitted content words: ${omitted.joinToString(", ")}."
            if (added.isNotEmpty() && additionRate > 0.10) evidence += "Words appearing mainly in your output: ${added.joinToString(", ")}."
            if (additionRate > 0.25) improvements += "Reduce additions: your transcript contains substantially more unmatched material than the source."
        }

        val sourceNumbers = extractNumbers(sourceText)
        val traineeNumbers = extractNumbers(traineeText)
        if (sourceNumbers.isNotEmpty() && traineeText.isNotBlank()) {
            val matched = multisetMatches(sourceNumbers, traineeNumbers)
            val numberScore = ((matched.toDouble() / sourceNumbers.size) * 100).roundToInt().coerceIn(0, 100)
            val missingNumbers = subtractMultiset(sourceNumbers, traineeNumbers).distinct().take(8)
            metrics += Metric(
                label = "Numbers & figures",
                score = numberScore,
                detail = if (missingNumbers.isEmpty()) "All detected source figures were preserved." else "Missing or changed: ${missingNumbers.joinToString(", ")}."
            )
            weightedScores += numberScore to if (lexicalComparable) 0.10 else 0.30
            if (numberScore == 100) strengths += "Detected numbers and figures were preserved."
            if (numberScore < 80) improvements += "Give extra attention to numbers, dates and quantities; they are high-risk interpretation details."
        }

        val sourceNames = extractLatinNameMarkers(sourceText)
        val traineeNames = extractLatinNameMarkers(traineeText)
        if (sourceNames.isNotEmpty() && traineeText.isNotBlank()) {
            val matched = multisetMatches(sourceNames.map(String::lowercase), traineeNames.map(String::lowercase))
            val nameScore = ((matched.toDouble() / sourceNames.size) * 100).roundToInt().coerceIn(0, 100)
            val missingNames = subtractMultiset(sourceNames.map(String::lowercase), traineeNames.map(String::lowercase)).distinct().take(8)
            metrics += Metric(
                label = "Names & proper nouns",
                score = nameScore,
                detail = if (missingNames.isEmpty()) "Detected names were preserved." else "Possibly missed: ${missingNames.joinToString(", ")}."
            )
            weightedScores += nameScore to if (lexicalComparable) 0.05 else 0.25
            if (nameScore >= 90) strengths += "Detected names and proper nouns were retained well."
            if (nameScore < 75) improvements += "Create a stronger note-taking cue for names and proper nouns."
        }

        val sourceDuration = sourceDurationMillis?.takeIf { it > 0L }
        val traineeDuration = traineeDurationMillis?.takeIf { it > 0L }
        if (sourceDuration != null && traineeDuration != null) {
            val timingScore = ratioScore(sourceDuration.toDouble(), traineeDuration.toDouble())
            val deltaSeconds = abs(sourceDuration - traineeDuration) / 1000.0
            metrics += Metric(
                label = "Timing match",
                score = timingScore,
                detail = "Delivery differed from the expected duration by ${"%.1f".format(Locale.US, deltaSeconds)} seconds."
            )
            weightedScores += timingScore to if (lexicalComparable) 0.15 else 0.20
            if (timingScore >= 90) strengths += "Your delivery duration stayed close to the source timing."
            if (timingScore < 75) improvements += "Practice with shorter chunks and aim to stay closer to the source timing."
        }

        if (sourceTokens.isNotEmpty() && traineeTokens.isNotEmpty()) {
            val lengthScore = ratioScore(sourceTokens.size.toDouble(), traineeTokens.size.toDouble())
            metrics += Metric(
                label = "Output length balance",
                score = lengthScore,
                detail = "Source: ${sourceTokens.size} words; trainee: ${traineeTokens.size} words."
            )
            if (!lexicalComparable) weightedScores += lengthScore to 0.15
        }

        if (sourceDuration != null && traineeDuration != null && sourceTokens.isNotEmpty() && traineeTokens.isNotEmpty()) {
            val sourceWpm = wordsPerMinute(sourceTokens.size, sourceDuration)
            val traineeWpm = wordsPerMinute(traineeTokens.size, traineeDuration)
            if (sourceWpm > 0.0 && traineeWpm > 0.0) {
                val paceScore = ratioScore(sourceWpm, traineeWpm)
                metrics += Metric(
                    label = "Pace match",
                    score = paceScore,
                    detail = "Source ≈ ${sourceWpm.roundToInt()} wpm; trainee ≈ ${traineeWpm.roundToInt()} wpm."
                )
                if (lexicalComparable) weightedScores += paceScore to 0.10
                if (paceScore >= 90) strengths += "Your speaking pace closely matched the source."
                if (paceScore < 75) improvements += "Stabilize your pace before increasing playback speed."
            }
        }

        if (traineeText.isNotBlank()) {
            val fillerCount = countFillers(traineeText)
            if (fillerCount > 0) {
                evidence += "Detected hesitation/filler markers: $fillerCount."
                if (fillerCount >= 4) improvements += "Reduce audible fillers by allowing a brief silent pause instead."
            }
        }

        val overallScore = weightedAverage(weightedScores)
        val scoreLabel = when {
            lexicalComparable -> "Local Interpreter Coach score"
            overallScore != null -> "Local observable-performance score"
            else -> "Local Interpreter Coach"
        }

        val summary = when {
            overallScore == null -> "Not enough measurable information is available yet. Add transcripts and/or timing information for useful feedback."
            overallScore >= 90 -> "Excellent control of the measurable features in this practice sample."
            overallScore >= 80 -> "Strong performance with a few specific areas worth refining."
            overallScore >= 70 -> "Solid practice, but the measurable data shows several areas for targeted repetition."
            overallScore >= 55 -> "The session shows partial control; repeat the material more slowly and focus on the issues listed below."
            else -> "This passage should be repeated in shorter or slower chunks before increasing difficulty."
        }

        if (strengths.isEmpty() && overallScore != null && overallScore >= 70) {
            strengths += "The session produced a usable performance baseline for future comparison."
        }
        if (improvements.isEmpty() && overallScore != null) {
            improvements += "Repeat the same material once more and try to improve the lowest metric."
        }

        val limitation = when {
            !sameLanguage && sourceText.isNotBlank() && traineeText.isNotBlank() ->
                "This offline basic engine does not claim to judge semantic equivalence between different languages. For cross-language interpretation it scores only observable markers such as numbers, names, timing and output balance."
            sourceText.isBlank() || traineeText.isBlank() ->
                "Transcript-based accuracy was not calculated. Add both the source transcript and your transcript for fuller local feedback."
            else -> null
        }

        return Report(
            overallScore = overallScore,
            scoreLabel = scoreLabel,
            summary = summary,
            metrics = metrics,
            strengths = strengths.distinct(),
            improvements = improvements.distinct(),
            evidence = evidence.distinct(),
            limitation = limitation
        )
    }

    private fun tokenize(text: String): List<String> = normalize(text)
        .split(' ')
        .filter { it.isNotBlank() }

    private fun normalize(text: String): String {
        val normalizedDigits = buildString(text.length) {
            text.forEach { c ->
                append(
                    when (c) {
                        in '٠'..'٩' -> ('0'.code + (c.code - '٠'.code)).toChar()
                        in '۰'..'۹' -> ('0'.code + (c.code - '۰'.code)).toChar()
                        else -> c
                    }
                )
            }
        }

        return normalizedDigits
            .lowercase(Locale.ROOT)
            .replace(Regex("[\\u064B-\\u065F\\u0670\\u06D6-\\u06ED]"), "")
            .replace('ـ', ' ')
            .replace(Regex("[\\p{P}\\p{S}]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    private fun multisetCoverage(source: List<String>, trainee: List<String>): Double {
        if (source.isEmpty()) return 0.0
        val traineeCounts = trainee.groupingBy { it }.eachCount().toMutableMap()
        var matches = 0
        source.forEach { token ->
            val count = traineeCounts[token] ?: 0
            if (count > 0) {
                matches++
                if (count == 1) traineeCounts.remove(token) else traineeCounts[token] = count - 1
            }
        }
        return matches.toDouble() / source.size
    }

    private fun orderedSimilarity(source: List<String>, trainee: List<String>): Double {
        if (source.isEmpty() || trainee.isEmpty()) return 0.0
        val a = source.take(350)
        val b = trainee.take(350)
        var previous = IntArray(b.size + 1)
        var current = IntArray(b.size + 1)
        for (i in a.indices) {
            for (j in b.indices) {
                current[j + 1] = if (a[i] == b[j]) {
                    previous[j] + 1
                } else {
                    max(previous[j + 1], current[j])
                }
            }
            val swap = previous
            previous = current
            current = swap
            current.fill(0)
        }
        return previous[b.size].toDouble() / max(1, a.size)
    }

    private fun additionRate(source: List<String>, trainee: List<String>): Double {
        if (trainee.isEmpty()) return 0.0
        val sourceCounts = source.groupingBy { it }.eachCount().toMutableMap()
        var unmatched = 0
        trainee.forEach { token ->
            val count = sourceCounts[token] ?: 0
            if (count > 0) {
                if (count == 1) sourceCounts.remove(token) else sourceCounts[token] = count - 1
            } else {
                unmatched++
            }
        }
        return unmatched.toDouble() / trainee.size
    }

    private fun importantMissingTokens(source: List<String>, other: List<String>): List<String> {
        val stopWords = englishStopWords + frenchStopWords + arabicStopWords
        val otherCounts = other.groupingBy { it }.eachCount().toMutableMap()
        val missing = mutableListOf<String>()
        source.forEach { token ->
            val count = otherCounts[token] ?: 0
            if (count > 0) {
                if (count == 1) otherCounts.remove(token) else otherCounts[token] = count - 1
            } else if (token.length >= 3 && token !in stopWords && token.none(Char::isDigit)) {
                missing += token
            }
        }
        return missing.groupingBy { it }.eachCount()
            .entries
            .sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenByDescending { it.key.length })
            .map { it.key }
            .take(8)
    }

    private fun extractNumbers(text: String): List<String> {
        val normalized = normalize(text)
        return Regex("\\b\\d+(?:[.,]\\d+)?\\b").findAll(normalized).map { it.value }.toList()
    }

    private fun extractLatinNameMarkers(text: String): List<String> {
        if (text.isBlank()) return emptyList()
        val acronym = Regex("\\b[A-ZÀ-ÖØ-Þ]{2,}\\b")
        val capitalized = Regex("\\b[A-ZÀ-ÖØ-Þ][A-Za-zÀ-ÖØ-öø-ÿ'’\\-]{2,}\\b")
        return (acronym.findAll(text).map { it.value } + capitalized.findAll(text).map { it.value })
            .filterNot { it.lowercase(Locale.ROOT) in capitalizedFalsePositives }
            .distinct()
            .toList()
    }

    private fun <T> multisetMatches(source: List<T>, trainee: List<T>): Int {
        val counts = trainee.groupingBy { it }.eachCount().toMutableMap()
        var matches = 0
        source.forEach { item ->
            val count = counts[item] ?: 0
            if (count > 0) {
                matches++
                if (count == 1) counts.remove(item) else counts[item] = count - 1
            }
        }
        return matches
    }

    private fun <T> subtractMultiset(source: List<T>, trainee: List<T>): List<T> {
        val counts = trainee.groupingBy { it }.eachCount().toMutableMap()
        val missing = mutableListOf<T>()
        source.forEach { item ->
            val count = counts[item] ?: 0
            if (count > 0) {
                if (count == 1) counts.remove(item) else counts[item] = count - 1
            } else {
                missing += item
            }
        }
        return missing
    }

    private fun ratioScore(first: Double, second: Double): Int {
        if (first <= 0.0 || second <= 0.0) return 0
        return ((min(first, second) / max(first, second)) * 100).roundToInt().coerceIn(0, 100)
    }

    private fun wordsPerMinute(words: Int, durationMillis: Long): Double {
        if (words <= 0 || durationMillis <= 0) return 0.0
        return words / (durationMillis / 60_000.0)
    }

    private fun weightedAverage(values: List<Pair<Int, Double>>): Int? {
        if (values.isEmpty()) return null
        val totalWeight = values.sumOf { it.second }
        if (totalWeight <= 0.0) return null
        return (values.sumOf { it.first * it.second } / totalWeight).roundToInt().coerceIn(0, 100)
    }

    private fun countFillers(text: String): Int {
        val normalized = normalize(text)
        val tokens = normalized.split(' ').filter { it.isNotBlank() }
        return tokens.count { it in fillerWords }
    }

    private val englishStopWords = setOf(
        "the", "a", "an", "and", "or", "but", "of", "to", "in", "on", "for", "with", "at", "by", "from",
        "is", "are", "was", "were", "be", "been", "being", "it", "this", "that", "these", "those", "as"
    )

    private val frenchStopWords = setOf(
        "le", "la", "les", "un", "une", "des", "de", "du", "et", "ou", "mais", "dans", "sur", "pour", "avec",
        "par", "est", "sont", "était", "étaient", "ce", "cette", "ces", "que", "qui", "au", "aux"
    )

    private val arabicStopWords = setOf(
        "في", "من", "إلى", "الى", "على", "عن", "مع", "و", "أو", "او", "لكن", "هذا", "هذه", "ذلك", "تلك",
        "هو", "هي", "هم", "كان", "كانت", "يكون", "أن", "ان", "ما", "التي", "الذي"
    )

    private val fillerWords = setOf(
        "um", "uh", "erm", "hmm", "like", "euh", "heu", "ben", "bah", "يعني", "اه", "أه", "امم", "مم"
    )

    private val capitalizedFalsePositives = setOf(
        "the", "this", "that", "a", "an", "in", "on", "at", "for", "with", "le", "la", "les", "un", "une",
        "ce", "cette", "ces", "je", "il", "elle", "nous", "vous", "ils", "elles"
    )
}

package com.interpretertrainer.app.ai

import com.interpretertrainer.app.data.database.PracticeSessionEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale

class OpenSourceCoachClient {

    data class ChatMessage(
        val role: String,
        val content: String
    )

    suspend fun chat(
        baseUrl: String,
        history: List<ChatMessage>,
        sessions: List<PracticeSessionEntity>
    ): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            require(baseUrl.isNotBlank()) { "Enhanced AI server is not configured." }
            require(history.isNotEmpty()) { "Conversation is empty." }

            val messages = JSONArray().apply {
                put(JSONObject().put("role", "system").put("content", systemPrompt(sessions)))
                history.takeLast(12).forEach { turn ->
                    put(
                        JSONObject()
                            .put("role", if (turn.role == "assistant") "assistant" else "user")
                            .put("content", turn.content.take(4_000))
                    )
                }
            }

            requestCompletion(baseUrl, messages, maxTokens = 650)
        }
    }

    suspend fun explainEvaluation(
        baseUrl: String,
        mode: String,
        sourceLanguage: String,
        targetLanguage: String,
        sourceText: String,
        traineeText: String,
        evaluatorReport: String
    ): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            require(baseUrl.isNotBlank()) { "Enhanced AI server is not configured." }

            val messages = JSONArray().apply {
                put(
                    JSONObject().put("role", "system").put(
                        "content",
                        """
                        You are Interpreter Coach, a specialist assistant for interpreter training.
                        Explain the supplied LOCAL EVALUATOR REPORT in a supportive but precise way.
                        The evaluator report is the source of truth. Never change, invent or contradict its numeric scores or evidence.
                        For cross-language interpretation, do not claim semantic accuracy beyond what the report actually measured.
                        Give 2-4 prioritized, practical exercises. Respond in the same language as the trainee text when clear; otherwise use English.
                        """.trimIndent()
                    )
                )
                put(
                    JSONObject().put("role", "user").put(
                        "content",
                        buildString {
                            appendLine("Practice mode: $mode")
                            appendLine("Languages: $sourceLanguage -> $targetLanguage")
                            appendLine()
                            appendLine("LOCAL EVALUATOR REPORT:")
                            appendLine(evaluatorReport.take(6_000))
                            appendLine()
                            if (sourceText.isNotBlank()) {
                                appendLine("SOURCE TRANSCRIPT:")
                                appendLine(sourceText.take(3_000))
                                appendLine()
                            }
                            if (traineeText.isNotBlank()) {
                                appendLine("TRAINEE TRANSCRIPT:")
                                appendLine(traineeText.take(3_000))
                            }
                        }
                    )
                )
            }

            requestCompletion(baseUrl, messages, maxTokens = 750)
        }
    }

    private fun requestCompletion(baseUrl: String, messages: JSONArray, maxTokens: Int): String {
        val normalized = baseUrl.trim().trimEnd('/')
        require(normalized.startsWith("https://") || normalized.startsWith("http://")) {
            "AI server URL must start with http:// or https://"
        }
        val endpoint = if (normalized.endsWith("/v1")) {
            "$normalized/chat/completions"
        } else {
            "$normalized/v1/chat/completions"
        }

        val body = JSONObject()
            .put("model", "qwen2.5-1.5b-instruct")
            .put("messages", messages)
            .put("temperature", 0.35)
            .put("top_p", 0.9)
            .put("max_tokens", maxTokens)
            .put("stream", false)
            .toString()

        val connection = (URL(endpoint).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 15_000
            readTimeout = 90_000
            doOutput = true
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("Accept", "application/json")
            setRequestProperty("Authorization", "Bearer no-key")
        }

        connection.outputStream.bufferedWriter(Charsets.UTF_8).use { it.write(body) }
        val code = connection.responseCode
        val stream = if (code in 200..299) connection.inputStream else connection.errorStream
        val text = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
        connection.disconnect()

        if (code !in 200..299) {
            val detail = runCatching { JSONObject(text).optString("error") }.getOrNull()
                ?.takeIf { it.isNotBlank() }
            error(detail ?: "Enhanced AI server returned HTTP $code")
        }

        val json = JSONObject(text)
        val choices = json.optJSONArray("choices") ?: error("AI server returned no choices.")
        val message = choices.optJSONObject(0)?.optJSONObject("message")
        val content = message?.optString("content").orEmpty().trim()
        require(content.isNotBlank()) { "AI server returned an empty response." }
        return content
    }

    private fun systemPrompt(sessions: List<PracticeSessionEntity>): String = buildString {
        appendLine("You are Interpreter Coach, the specialized coaching assistant inside Interpreter Trainer.")
        appendLine("Focus on shadowing, consecutive interpreting, sight translation, note-taking, terminology, reformulation, memory, omissions, numbers, names, fluency and deliberate practice.")
        appendLine("You can converse naturally in English, French and Arabic. Follow the user's language when practical.")
        appendLine("Be concise, specific and actionable. Do not behave like a general-purpose trivia chatbot when the request is unrelated to interpreter training; gently redirect.")
        appendLine("Never invent a user's past performance, transcript, score, weakness or improvement trend. Treat the saved session context below as evidence, not as a complete medical/psychological profile.")
        appendLine("If asked for a numeric evaluation without an existing local evaluator score, tell the user to use the Evaluate tab or provide source and trainee transcripts. The local evaluator is authoritative for numeric scores.")
        appendLine()
        appendLine("RECENT SAVED PRACTICE CONTEXT:")
        if (sessions.isEmpty()) {
            appendLine("No saved sessions yet.")
        } else {
            sessions.sortedByDescending { it.startedAt }.take(6).forEachIndexed { index, session ->
                appendLine("Session ${index + 1}: ${readableMode(session.practiceMode)}, ${session.sourceLanguage} -> ${session.targetLanguage}, duration ${session.durationMillis / 1000}s.")
                session.sourceName?.takeIf { it.isNotBlank() }?.let { appendLine("Source: ${it.take(160)}") }
                if (session.notes.isNotBlank()) appendLine("Notes: ${session.notes.take(350)}")
                if (session.transcript.isNotBlank()) appendLine("Trainee transcript excerpt: ${session.transcript.take(500)}")
                session.aiFeedback?.takeIf { it.isNotBlank() }?.let { appendLine("Saved local evaluator feedback: ${it.take(800)}") }
            }
        }
    }

    private fun readableMode(mode: String): String = when (mode.uppercase(Locale.ROOT)) {
        "SHADOWING" -> "Shadowing"
        "CONSECUTIVE" -> "Consecutive Interpretation"
        "SIGHT_TRANSLATION" -> "Sight Translation"
        "LIVE_TRANSCRIPTION" -> "Live Transcription"
        else -> mode.replace('_', ' ')
    }
}

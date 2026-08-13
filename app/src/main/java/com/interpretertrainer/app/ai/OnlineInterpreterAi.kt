package com.interpretertrainer.app.ai

import android.content.Context
import com.interpretertrainer.app.BuildConfig
import com.interpretertrainer.app.data.database.PracticeSessionEntity
import java.net.HttpURLConnection
import java.net.URL
import java.util.ArrayDeque
import java.util.Locale
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * Network-backed Interpreter AI client.
 *
 * No model weights or provider credentials are stored in the APK. The app talks only to the
 * Interpreter Trainer backend, which owns the hosted open-weight model connection.
 */
class OnlineInterpreterAi(context: Context) {
    private val appContext = context.applicationContext
    private val historyMutex = Mutex()
    private val recentChatTurns = ArrayDeque<ChatTurn>()

    private val baseUrl: String
        get() = BuildConfig.INTERPRETER_AI_BASE_URL.trim().trimEnd('/')

    private val installationId: String by lazy {
        val prefs = appContext.getSharedPreferences("interpreter_ai", Context.MODE_PRIVATE)
        prefs.getString("installation_id", null)
            ?: UUID.randomUUID().toString().also { generated ->
                prefs.edit().putString("installation_id", generated).apply()
            }
    }

    fun isConfigured(): Boolean = baseUrl.startsWith("https://")

    suspend fun chat(
        message: String,
        sessions: List<PracticeSessionEntity>
    ): Result<String> = runCatching {
        require(message.isNotBlank()) { "Message is empty." }

        val cleanMessage = message.trim().take(MAX_USER_MESSAGE_CHARS)
        val history = historyMutex.withLock { recentChatTurns.toList() }

        val body = JSONObject()
            .put("kind", "chat")
            .put("message", cleanMessage)
            .put("practiceContext", compactRecentSessionContext(sessions))
            .put("history", JSONArray().apply {
                history.takeLast(MAX_HISTORY_TURNS).forEach { turn ->
                    put(JSONObject().put("role", "user").put("content", turn.user))
                    put(JSONObject().put("role", "assistant").put("content", turn.assistant))
                }
            })

        val answer = request(body)

        historyMutex.withLock {
            recentChatTurns.addLast(
                ChatTurn(
                    user = cleanMessage.take(MAX_HISTORY_USER_CHARS),
                    assistant = answer.take(MAX_HISTORY_ASSISTANT_CHARS)
                )
            )
            while (recentChatTurns.size > MAX_HISTORY_TURNS) recentChatTurns.removeFirst()
        }

        answer
    }

    suspend fun explainEvaluation(
        mode: String,
        sourceLanguage: String,
        targetLanguage: String,
        sourceText: String,
        traineeText: String,
        evaluatorReport: String
    ): Result<String> = runCatching {
        val body = JSONObject()
            .put("kind", "evaluation")
            .put("mode", mode.take(80))
            .put("sourceLanguage", sourceLanguage.take(40))
            .put("targetLanguage", targetLanguage.take(40))
            .put("sourceText", sourceText.take(MAX_EVALUATION_EXCERPT_CHARS))
            .put("traineeText", traineeText.take(MAX_EVALUATION_EXCERPT_CHARS))
            .put("evaluatorReport", evaluatorReport.take(MAX_EVALUATOR_REPORT_CHARS))

        request(body)
    }

    private suspend fun request(body: JSONObject): String = withContext(Dispatchers.IO) {
        check(isConfigured()) {
            "Interpreter AI online service is not configured in this build yet."
        }

        val connection = URL("$baseUrl/v1/coach").openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "POST"
            connection.connectTimeout = CONNECT_TIMEOUT_MS
            connection.readTimeout = READ_TIMEOUT_MS
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            connection.setRequestProperty("Accept", "application/json")
            connection.setRequestProperty("X-Interpreter-Install-Id", installationId)
            connection.setRequestProperty("User-Agent", "InterpreterTrainer/${BuildConfig.VERSION_NAME} Android")

            connection.outputStream.bufferedWriter(Charsets.UTF_8).use { writer ->
                writer.write(body.toString())
            }

            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val payload = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            val json = runCatching { JSONObject(payload) }.getOrNull()

            if (status !in 200..299) {
                val serverMessage = json?.optString("error")
                    ?.takeIf { it.isNotBlank() }
                    ?: "HTTP $status"
                error("Interpreter AI service error: $serverMessage")
            }

            val answer = json?.optString("answer")?.trim().orEmpty()
            require(answer.isNotBlank()) { "Interpreter AI returned an empty response." }
            answer.take(MAX_RESPONSE_CHARS)
        } finally {
            connection.disconnect()
        }
    }

    private fun compactRecentSessionContext(sessions: List<PracticeSessionEntity>): String = buildString {
        if (sessions.isEmpty()) {
            append("No saved practice sessions.")
            return@buildString
        }

        sessions.sortedByDescending { it.startedAt }.take(3).forEachIndexed { index, session ->
            append(
                "${index + 1}. ${readableMode(session.practiceMode)}; " +
                    "${session.sourceLanguage} -> ${session.targetLanguage}; " +
                    "${session.durationMillis / 1000}s"
            )
            session.notes.takeIf { it.isNotBlank() }?.let {
                append("; notes=${it.replace('\n', ' ').take(120)}")
            }
            session.aiFeedback?.takeIf { it.isNotBlank() }?.let {
                append("; saved evaluator=${it.replace('\n', ' ').take(220)}")
            }
            appendLine()
        }
    }.take(MAX_SESSION_CONTEXT_CHARS)

    private fun readableMode(mode: String): String = when (mode.uppercase(Locale.ROOT)) {
        "SHADOWING" -> "Shadowing"
        "CONSECUTIVE" -> "Consecutive Interpretation"
        "SIGHT_TRANSLATION" -> "Sight Translation"
        "LIVE_TRANSCRIPTION" -> "Live Transcription"
        else -> mode.replace('_', ' ')
    }

    private data class ChatTurn(val user: String, val assistant: String)

    companion object {
        private const val CONNECT_TIMEOUT_MS = 15_000
        private const val READ_TIMEOUT_MS = 90_000
        private const val MAX_USER_MESSAGE_CHARS = 4_000
        private const val MAX_RESPONSE_CHARS = 8_000
        private const val MAX_SESSION_CONTEXT_CHARS = 2_500
        private const val MAX_EVALUATION_EXCERPT_CHARS = 3_000
        private const val MAX_EVALUATOR_REPORT_CHARS = 4_000
        private const val MAX_HISTORY_TURNS = 4
        private const val MAX_HISTORY_USER_CHARS = 1_000
        private const val MAX_HISTORY_ASSISTANT_CHARS = 1_500
    }
}

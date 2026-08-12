package com.interpretertrainer.app.ai

import android.content.Context
import android.os.Build
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.interpretertrainer.app.data.database.PracticeSessionEntity
import java.io.File
import java.util.ArrayDeque
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

/**
 * Real on-device neural chatbot backed by LiteRT-LM.
 *
 * Qwen3 + LiteRT-LM 0.14.0 can fail on the second send in the same Conversation because the
 * regenerated prompt is not always a byte-for-byte extension of LiteRT-LM's cached prompt. The
 * model itself is fine, so we keep one Engine loaded and create a fresh one-shot Conversation for
 * every user message. A small recent-chat summary is carried manually so the coach still has useful
 * conversational memory without entering the broken incremental-conversation path.
 */
class OnDeviceInterpreterAi(context: Context) {
    private val appContext = context.applicationContext
    private val loadMutex = Mutex()
    private val inferenceMutex = Mutex()
    private val historyMutex = Mutex()
    private val recentChatTurns = ArrayDeque<ChatTurn>()

    @Volatile private var engine: Engine? = null
    @Volatile private var activeBackend: RuntimeBackend? = null

    fun isModelInstalled(): Boolean = OnDeviceModelManager.isInstalled(appContext)

    fun isReady(): Boolean = engine?.isInitialized() == true

    fun activeBackendLabel(): String? = activeBackend?.label

    suspend fun ensureLoaded(): Result<Unit> = runCatching {
        loadMutex.withLock {
            if (isReady()) return@withLock

            if (!OnDeviceModelManager.isInstalled(appContext)) {
                error("Interpreter AI model is not installed yet.")
            }

            if (Build.SUPPORTED_64_BIT_ABIS.isEmpty()) {
                error(
                    "This phone is running a 32-bit Android userspace. " +
                        "The on-device neural runtime requires 64-bit Android. " +
                        "Supported ABIs: ${Build.SUPPORTED_ABIS.joinToString()}"
                )
            }

            cleanupObsoleteRuntimeCache()
            closeRuntime()

            val failures = mutableListOf<String>()
            for (backend in listOf(RuntimeBackend.GPU, RuntimeBackend.CPU)) {
                try {
                    loadRuntime(backend)
                    return@withLock
                } catch (t: Throwable) {
                    failures += "${backend.label}: ${shortError(t)}"
                    closeRuntime()
                }
            }

            error(
                "The neural model file is installed, but this phone could not initialize the " +
                    "runtime with either GPU or CPU. ${failures.joinToString(" | ")}"
            )
        }
    }

    suspend fun chat(
        message: String,
        sessions: List<PracticeSessionEntity>,
        maxTokens: Int = 320
    ): Result<String> = runCatching {
        require(message.isNotBlank()) { "Message is empty." }
        ensureLoaded().getOrThrow()

        val cleanMessage = message.trim().take(MAX_USER_MESSAGE_CHARS)
        val historySnapshot = historyMutex.withLock { recentChatTurns.toList() }
        val prompt = buildChatPrompt(cleanMessage, sessions, historySnapshot)
        val answer = generateWithRecovery(prompt, maxTokens)

        historyMutex.withLock {
            recentChatTurns.addLast(ChatTurn(cleanMessage, answer.take(MAX_HISTORY_ASSISTANT_CHARS)))
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
        ensureLoaded().getOrThrow()

        val prompt = buildString {
            appendLine("Explain this interpreter evaluation concisely. The LOCAL EVALUATOR REPORT is authoritative.")
            appendLine("Do not change its numbers or invent semantic accuracy. Give 2 to 4 practical exercises.")
            appendLine("Mode: $mode")
            appendLine("Languages: $sourceLanguage -> $targetLanguage")
            appendLine()
            appendLine("LOCAL EVALUATOR REPORT:")
            appendLine(evaluatorReport.take(1_000))
            if (sourceText.isNotBlank()) {
                appendLine()
                appendLine("SOURCE EXCERPT:")
                appendLine(sourceText.take(450))
            }
            if (traineeText.isNotBlank()) {
                appendLine()
                appendLine("TRAINEE EXCERPT:")
                appendLine(traineeText.take(450))
            }
        }

        generateWithRecovery(prompt, 320)
    }

    private fun buildChatPrompt(
        message: String,
        sessions: List<PracticeSessionEntity>,
        history: List<ChatTurn>
    ): String = buildString {
        appendLine("Relevant saved-practice summary. Use it only when useful; never invent missing evidence.")
        appendLine("<practice_context>")
        append(compactRecentSessionContext(sessions))
        appendLine("</practice_context>")

        if (history.isNotEmpty()) {
            appendLine()
            appendLine("Recent conversation context:")
            history.takeLast(MAX_HISTORY_TURNS).forEach { turn ->
                appendLine("User: ${turn.user.take(MAX_HISTORY_USER_CHARS)}")
                appendLine("Coach: ${turn.assistant.take(MAX_HISTORY_ASSISTANT_CHARS)}")
            }
        }

        appendLine()
        appendLine("Current user message:")
        append(message)
    }

    private suspend fun generateWithRecovery(prompt: String, maxTokens: Int): String =
        inferenceMutex.withLock {
            val firstBackend = activeBackend ?: error("Interpreter AI runtime is not ready.")

            try {
                return@withLock sendOneShot(prompt, maxTokens)
            } catch (firstFailure: Throwable) {
                val alternate = firstBackend.alternate()

                val retry = runCatching {
                    loadMutex.withLock {
                        closeRuntime()
                        loadRuntime(alternate)
                    }
                    sendOneShot(prompt, maxTokens)
                }

                retry.getOrNull()?.let { return@withLock it }

                val alternateFailure = retry.exceptionOrNull()
                closeRuntime()
                throw IllegalStateException(
                    "Neural inference failed on ${firstBackend.label} and ${alternate.label}. " +
                        "${shortError(firstFailure)} | ${shortError(alternateFailure)}",
                    firstFailure
                )
            }
        }

    /**
     * One user message = one native Conversation. This intentionally avoids LiteRT-LM's broken
     * second-turn prefix-cache path for this Qwen3 artifact while keeping the expensive Engine live.
     */
    private suspend fun sendOneShot(prompt: String, maxTokens: Int): String {
        val currentEngine = engine ?: error("Interpreter AI engine is not ready.")
        val chat = currentEngine.createConversation(conversationConfig())
        val rawOutput = StringBuilder()

        try {
            withTimeout(GENERATION_TIMEOUT_MS) {
                withContext(Dispatchers.Default) {
                    chat.sendMessageAsync(prompt).collect { chunk ->
                        rawOutput.append(chunk.toString())
                    }
                }
            }
        } catch (timeout: TimeoutCancellationException) {
            runCatching { chat.cancelProcess() }
            throw IllegalStateException("Neural generation timed out. The runtime was reset safely.", timeout)
        } finally {
            runCatching { chat.close() }
        }

        val rawResponse = rawOutput.toString().trim()
        require(rawResponse.isNotBlank()) { "Interpreter AI returned an empty response." }

        val visibleResponse = removeThinking(rawResponse).trim()
        require(visibleResponse.isNotBlank()) { "Interpreter AI returned no visible answer." }

        return if (maxTokens > 0) visibleResponse.take(MAX_RESPONSE_CHARS) else visibleResponse
    }

    private suspend fun loadRuntime(backend: RuntimeBackend) {
        val model = OnDeviceModelManager.modelFile(appContext)
        val candidate = Engine(
            EngineConfig(
                modelPath = model.absolutePath,
                backend = backend.engineBackend,
                maxNumTokens = MOBILE_CONTEXT_TOKENS,
                cacheDir = FilePaths.aiCacheDir(appContext, backend)
            )
        )

        try {
            withContext(Dispatchers.Default) { candidate.initialize() }
            engine = candidate
            activeBackend = backend
        } catch (t: Throwable) {
            runCatching { if (candidate.isInitialized()) candidate.close() }
            throw IllegalStateException(
                "${backend.label} runtime could not initialize: ${shortError(t)}",
                t
            )
        }
    }

    /**
     * Disabling Qwen thinking is safe here because every Conversation has exactly one user turn.
     * The previous prefix mismatch happened only when LiteRT-LM attempted to render turn two from
     * an already-cached Conversation.
     */
    private fun conversationConfig() = ConversationConfig(
        systemInstruction = Contents.of(BASE_SYSTEM_PROMPT),
        extraContext = mapOf("enable_thinking" to false)
    )

    fun unload() {
        closeRuntime()
    }

    private fun closeRuntime() {
        engine?.let { current ->
            runCatching { if (current.isInitialized()) current.close() }
        }
        engine = null
        activeBackend = null
    }

    private fun compactRecentSessionContext(sessions: List<PracticeSessionEntity>): String = buildString {
        if (sessions.isEmpty()) {
            appendLine("No saved practice sessions.")
            return@buildString
        }

        val recent = sessions.sortedByDescending { it.startedAt }.take(2)
        recent.forEachIndexed { index, session ->
            appendLine(
                "${index + 1}. ${readableMode(session.practiceMode)}; " +
                    "${session.sourceLanguage} -> ${session.targetLanguage}; " +
                    "${session.durationMillis / 1000}s"
            )

            if (index == 0) {
                session.notes.takeIf { it.isNotBlank() }?.let {
                    appendLine("Notes: ${it.replace('\n', ' ').take(90)}")
                }
                session.aiFeedback?.takeIf { it.isNotBlank() }?.let {
                    appendLine("Saved evaluator: ${it.replace('\n', ' ').take(150)}")
                }
            }
        }
    }.take(MAX_SESSION_CONTEXT_CHARS)

    private fun removeThinking(value: String): String {
        var result = value
        while (true) {
            val start = result.indexOf("<think>", ignoreCase = true)
            if (start < 0) break
            val end = result.indexOf("</think>", startIndex = start + 7, ignoreCase = true)
            if (end < 0) {
                result = result.substring(0, start)
                break
            }
            result = result.removeRange(start, end + "</think>".length)
        }
        return result.trim()
    }

    private fun readableMode(mode: String): String = when (mode.uppercase(Locale.ROOT)) {
        "SHADOWING" -> "Shadowing"
        "CONSECUTIVE" -> "Consecutive Interpretation"
        "SIGHT_TRANSLATION" -> "Sight Translation"
        "LIVE_TRANSCRIPTION" -> "Live Transcription"
        else -> mode.replace('_', ' ')
    }

    private fun cleanupObsoleteRuntimeCache() {
        runCatching { File(appContext.cacheDir, "interpreter_ai_litert_cache").deleteRecursively() }
    }

    private fun shortError(t: Throwable?): String {
        if (t == null) return "unknown runtime error"
        val message = t.message?.replace('\n', ' ')?.trim().orEmpty()
        return if (message.isBlank()) t::class.java.simpleName else message.take(360)
    }

    private data class ChatTurn(val user: String, val assistant: String)

    private enum class RuntimeBackend(
        val label: String,
        val engineBackend: Backend
    ) {
        GPU("GPU", Backend.GPU()),
        CPU("CPU", Backend.CPU());

        fun alternate(): RuntimeBackend = if (this == GPU) CPU else GPU
    }

    private object FilePaths {
        fun aiCacheDir(context: Context, backend: RuntimeBackend): String =
            File(
                context.cacheDir,
                "interpreter_ai_qwen3_mixed_int4_v5_${backend.name.lowercase(Locale.ROOT)}"
            ).apply { mkdirs() }.absolutePath
    }

    companion object {
        private const val MOBILE_CONTEXT_TOKENS = 1280
        private const val GENERATION_TIMEOUT_MS = 60_000L
        private const val MAX_SESSION_CONTEXT_CHARS = 600
        private const val MAX_USER_MESSAGE_CHARS = 1_000
        private const val MAX_RESPONSE_CHARS = 4_000
        private const val MAX_HISTORY_TURNS = 2
        private const val MAX_HISTORY_USER_CHARS = 300
        private const val MAX_HISTORY_ASSISTANT_CHARS = 420

        private val BASE_SYSTEM_PROMPT = """
            You are Interpreter AI, a specialized coach for interpreters and interpretation students.
            Help with shadowing, consecutive interpreting, sight translation, note-taking, terminology,
            memory, reformulation, numbers, names, fluency and delivery. Reply naturally in the user's
            language (English, French or Arabic). Be concise, practical and specific. Never invent the
            user's performance, transcript, score or trend. Treat supplied evaluator numbers as
            authoritative and clearly distinguish evidence from inference. Keep answers focused on
            interpreter training.
        """.trimIndent()
    }
}

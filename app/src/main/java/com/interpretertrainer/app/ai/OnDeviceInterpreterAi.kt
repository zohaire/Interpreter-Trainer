package com.interpretertrainer.app.ai

import android.content.Context
import android.os.Build
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.Conversation
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.interpretertrainer.app.data.database.PracticeSessionEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.File
import java.util.Locale

/**
 * Real on-device neural chatbot backed by LiteRT-LM.
 *
 * The runtime is deliberately defensive because Android accelerator support varies by device:
 * - GPU is preferred for this model because it usually needs less runtime memory.
 * - CPU is an automatic fallback when GPU cannot initialize or invoke the model.
 * - Readiness is based on engine/conversation initialization only; no hidden user turn is consumed.
 * - Generation uses LiteRT-LM's coroutine streaming API and has a hard timeout.
 * - Qwen's native thinking format is kept internally so sequential conversation rendering remains
 *   prefix-stable; thinking text is stripped only from the answer shown in the app.
 * - Any failed native send invalidates the conversation/engine instead of reusing poisoned state.
 * - Practice history is compacted and injected only once per runtime conversation.
 */
class OnDeviceInterpreterAi(context: Context) {
    private val appContext = context.applicationContext
    private val loadMutex = Mutex()
    private val inferenceMutex = Mutex()

    @Volatile private var engine: Engine? = null
    @Volatile private var conversation: Conversation? = null
    @Volatile private var activeBackend: RuntimeBackend? = null
    @Volatile private var practiceContextPrimed = false

    fun isModelInstalled(): Boolean = OnDeviceModelManager.isInstalled(appContext)

    fun isReady(): Boolean = engine?.isInitialized() == true && conversation != null

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
        val prompt = if (!practiceContextPrimed) {
            buildString {
                appendLine("Relevant saved-practice summary. Use it only when useful; never invent missing evidence.")
                appendLine("<practice_context>")
                append(compactRecentSessionContext(sessions))
                appendLine("</practice_context>")
                appendLine()
                appendLine("User message:")
                append(cleanMessage)
            }
        } else {
            cleanMessage
        }

        val answer = generateWithRecovery(prompt, maxTokens)
        practiceContextPrimed = true
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
            appendLine(evaluatorReport.take(1_500))
            if (sourceText.isNotBlank()) {
                appendLine()
                appendLine("SOURCE EXCERPT:")
                appendLine(sourceText.take(600))
            }
            if (traineeText.isNotBlank()) {
                appendLine()
                appendLine("TRAINEE EXCERPT:")
                appendLine(traineeText.take(600))
            }
        }

        generateWithRecovery(prompt, 360)
    }

    private suspend fun generateWithRecovery(prompt: String, maxTokens: Int): String =
        inferenceMutex.withLock {
            val firstBackend = activeBackend ?: error("Interpreter AI runtime is not ready.")

            try {
                return@withLock sendCurrent(prompt, maxTokens)
            } catch (firstFailure: Throwable) {
                val alternate = firstBackend.alternate()

                val retry = runCatching {
                    loadMutex.withLock {
                        closeRuntime()
                        loadRuntime(alternate)
                    }
                    sendCurrent(prompt, maxTokens)
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
     * Keep the model's full native response in the Conversation so LiteRT-LM can render the next
     * turn as an exact extension of the cached prompt. Only sanitize the copy returned to the UI.
     */
    private suspend fun sendCurrent(prompt: String, maxTokens: Int): String {
        val chat = conversation ?: error("Interpreter AI conversation is not ready.")
        val rawOutput = StringBuilder()

        withTimeout(GENERATION_TIMEOUT_MS) {
            withContext(Dispatchers.Default) {
                chat.sendMessageAsync(prompt).collect { chunk ->
                    rawOutput.append(chunk.toString())
                }
            }
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
            val chat = candidate.createConversation(conversationConfig())

            engine = candidate
            conversation = chat
            activeBackend = backend
            practiceContextPrimed = false
        } catch (t: Throwable) {
            runCatching { if (candidate.isInitialized()) candidate.close() }
            throw IllegalStateException(
                "${backend.label} runtime could not initialize: ${shortError(t)}",
                t
            )
        }
    }

    /**
     * Do not force enable_thinking=false here. With this Qwen3 artifact, LiteRT-LM 0.14.0 inserts
     * an empty thinking block while rendering generation but stores the first assistant answer
     * without it, making turn two fail its incremental-prefix check. Let Qwen preserve its native
     * format internally and remove thinking only from the displayed response.
     */
    private fun conversationConfig() = ConversationConfig(
        systemInstruction = Contents.of(BASE_SYSTEM_PROMPT)
    )

    fun unload() {
        closeRuntime()
    }

    private fun closeRuntime() {
        runCatching { conversation?.close() }
        conversation = null
        engine?.let { current ->
            runCatching { if (current.isInitialized()) current.close() }
        }
        engine = null
        activeBackend = null
        practiceContextPrimed = false
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
                    appendLine("Notes: ${it.replace('\n', ' ').take(100)}")
                }
                session.aiFeedback?.takeIf { it.isNotBlank() }?.let {
                    appendLine("Saved evaluator summary: ${it.replace('\n', ' ').take(180)}")
                }
            } else {
                session.aiFeedback?.takeIf { it.isNotBlank() }?.let {
                    appendLine("Earlier evaluator summary: ${it.replace('\n', ' ').take(100)}")
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
                "interpreter_ai_qwen3_mixed_int4_v4_${backend.name.lowercase(Locale.ROOT)}"
            ).apply { mkdirs() }.absolutePath
    }

    companion object {
        private const val MOBILE_CONTEXT_TOKENS = 1280
        private const val GENERATION_TIMEOUT_MS = 60_000L
        private const val MAX_SESSION_CONTEXT_CHARS = 800
        private const val MAX_USER_MESSAGE_CHARS = 1_500
        private const val MAX_RESPONSE_CHARS = 6_000

        private val BASE_SYSTEM_PROMPT = """
            You are Interpreter AI, a specialized coach for interpreters and interpretation students.
            Help with shadowing, consecutive interpreting, sight translation, note-taking, terminology,
            memory, reformulation, numbers, names, fluency and delivery. Reply naturally in the user's
            language (English, French or Arabic). Be concise, practical and specific. Never invent the
            user's performance, transcript, score or trend. Treat supplied evaluator numbers as
            authoritative and clearly distinguish evidence from inference. Keep answers focused on
            interpreter training. Keep internal reasoning brief; the app will only show your final answer.
        """.trimIndent()
    }
}

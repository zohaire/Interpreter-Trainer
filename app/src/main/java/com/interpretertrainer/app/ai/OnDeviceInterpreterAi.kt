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
 * - Readiness is based on engine/conversation initialization only; we do NOT consume a hidden
 *   sendMessage call before the user's first message.
 * - Generation uses LiteRT-LM's coroutine streaming API instead of repeated synchronous JNI calls.
 * - Any failed native send invalidates the conversation/engine instead of reusing poisoned state.
 * - Practice history is compacted and injected only once per runtime conversation so the 2048-token
 *   Qwen context is not consumed by repeatedly attaching the same session transcripts.
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

    /**
     * Sends once on the current backend. If native invocation fails, the failed runtime is destroyed,
     * the alternate backend is initialized, and the same request is retried once. If that also fails,
     * no broken Conversation is kept around for the next Send button press.
     */
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
     * Use the Flow API recommended for coroutine callers. It avoids tying the UI to a blocking JNI
     * send call and lets cancellation/timeouts unwind instead of leaving the Send button spinning.
     */
    private suspend fun sendCurrent(prompt: String, maxTokens: Int): String {
        val chat = conversation ?: error("Interpreter AI conversation is not ready.")
        val output = StringBuilder()

        withTimeout(GENERATION_TIMEOUT_MS) {
            withContext(Dispatchers.Default) {
                chat.sendMessageAsync(prompt).collect { chunk ->
                    output.append(chunk.toString())
                }
            }
        }

        val response = output.toString().trim()
        require(response.isNotBlank()) { "Interpreter AI returned an empty response." }

        // LiteRT-LM limits the entire KV cache rather than exposing a per-call output token limit.
        // Bound extreme output for UI safety while retaining maxTokens as call-site intent.
        return if (maxTokens > 0) response.take(MAX_RESPONSE_CHARS) else response
    }

    private suspend fun loadRuntime(backend: RuntimeBackend) {
        val model = OnDeviceModelManager.modelFile(appContext)
        val candidate = Engine(
            EngineConfig(
                modelPath = model.absolutePath,
                backend = backend.engineBackend,
                // The model supports 2048 tokens. A smaller working window reduces KV-cache memory
                // pressure on phones while still leaving enough room for compact coaching context.
                maxNumTokens = MOBILE_CONTEXT_TOKENS,
                // Keep separate, versioned accelerator caches. Reusing a cache generated for a
                // different model/backend can produce device-specific compiled-model failures.
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

    private fun conversationConfig() = ConversationConfig(
        systemInstruction = Contents.of(BASE_SYSTEM_PROMPT),
        extraContext = mapOf("enable_thinking" to false)
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

    private fun readableMode(mode: String): String = when (mode.uppercase(Locale.ROOT)) {
        "SHADOWING" -> "Shadowing"
        "CONSECUTIVE" -> "Consecutive Interpretation"
        "SIGHT_TRANSLATION" -> "Sight Translation"
        "LIVE_TRANSCRIPTION" -> "Live Transcription"
        else -> mode.replace('_', ' ')
    }

    private fun cleanupObsoleteRuntimeCache() {
        // Leave the downloaded model alone; deleting old accelerator cache costs no network traffic.
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
                "interpreter_ai_qwen3_mixed_int4_v3_${backend.name.lowercase(Locale.ROOT)}"
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
            interpreter training.
        """.trimIndent()
    }
}

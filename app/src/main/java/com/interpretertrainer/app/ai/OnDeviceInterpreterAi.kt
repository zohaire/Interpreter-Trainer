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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.Locale

/**
 * Real on-device neural chatbot backed by the production LiteRT-LM Android SDK.
 * Numeric assessment stays with LocalInterpreterCoach; the neural model explains evidence,
 * answers open-ended interpreter-training questions and uses recent session context.
 */
class OnDeviceInterpreterAi(context: Context) {
    private val appContext = context.applicationContext
    private val loadMutex = Mutex()
    private val inferenceMutex = Mutex()

    @Volatile private var engine: Engine? = null
    @Volatile private var conversation: Conversation? = null

    fun isModelInstalled(): Boolean = OnDeviceModelManager.isInstalled(appContext)

    fun isReady(): Boolean = engine?.isInitialized() == true && conversation != null

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

            closeRuntime()

            val model = OnDeviceModelManager.modelFile(appContext)
            val candidate = Engine(
                EngineConfig(
                    modelPath = model.absolutePath,
                    backend = Backend.CPU(),
                    // Use the context/KV-cache size embedded in the converted model. Forcing a
                    // different size can make otherwise valid LiteRT-LM artifacts fail to load.
                    cacheDir = FilePaths.aiCacheDir(appContext)
                )
            )

            try {
                withContext(Dispatchers.Default) { candidate.initialize() }
                val chat = candidate.createConversation(
                    ConversationConfig(
                        systemInstruction = Contents.of(BASE_SYSTEM_PROMPT)
                    )
                )
                engine = candidate
                conversation = chat
            } catch (t: Throwable) {
                runCatching { if (candidate.isInitialized()) candidate.close() }
                throw IllegalStateException(
                    buildString {
                        append("Neural runtime could not load ${OnDeviceModelManager.MODEL_LABEL}")
                        t.message?.takeIf { it.isNotBlank() }?.let { append(": ").append(it) }
                        append(". Device ABI: ").append(Build.SUPPORTED_ABIS.joinToString())
                    },
                    t
                )
            }
        }
    }

    suspend fun chat(
        message: String,
        sessions: List<PracticeSessionEntity>,
        maxTokens: Int = 420
    ): Result<String> = runCatching {
        require(message.isNotBlank()) { "Message is empty." }
        ensureLoaded().getOrThrow()

        val prompt = buildString {
            appendLine("Use the following local practice context only when it is relevant. Never invent missing performance data.")
            appendLine("<practice_context>")
            append(recentSessionContext(sessions))
            appendLine("</practice_context>")
            appendLine()
            appendLine("User message:")
            append(message.trim())
        }

        generate(prompt, maxTokens)
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
            appendLine("Explain this interpreter performance evaluation. The LOCAL EVALUATOR REPORT is authoritative.")
            appendLine("Do not change its numbers, do not invent semantic accuracy, and do not claim evidence that is absent.")
            appendLine("Give concise feedback and 2 to 4 practical exercises.")
            appendLine("Practice mode: $mode")
            appendLine("Languages: $sourceLanguage -> $targetLanguage")
            appendLine()
            appendLine("LOCAL EVALUATOR REPORT:")
            appendLine(evaluatorReport.take(6_000))
            if (sourceText.isNotBlank()) {
                appendLine()
                appendLine("SOURCE TRANSCRIPT:")
                appendLine(sourceText.take(3_000))
            }
            if (traineeText.isNotBlank()) {
                appendLine()
                appendLine("TRAINEE TRANSCRIPT:")
                appendLine(traineeText.take(3_000))
            }
        }

        generate(prompt, 520)
    }

    private suspend fun generate(prompt: String, maxTokens: Int): String = inferenceMutex.withLock {
        val chat = conversation ?: error("Interpreter AI conversation is not ready.")
        withContext(Dispatchers.Default) {
            // LiteRT-LM 0.14.0 Message.toString() is defined as its textual Contents.toString().
            val response = chat.sendMessage(prompt).toString().trim()
            require(response.isNotBlank()) { "Interpreter AI returned an empty response." }
            if (maxTokens > 0) response else response
        }
    }

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
    }

    private fun recentSessionContext(sessions: List<PracticeSessionEntity>): String = buildString {
        if (sessions.isEmpty()) {
            appendLine("No saved practice sessions.")
            return@buildString
        }

        sessions.sortedByDescending { it.startedAt }.take(5).forEachIndexed { index, session ->
            appendLine(
                "${index + 1}. ${readableMode(session.practiceMode)}; " +
                    "${session.sourceLanguage} -> ${session.targetLanguage}; " +
                    "duration ${session.durationMillis / 1000}s"
            )
            session.notes.takeIf { it.isNotBlank() }?.let { appendLine("Notes: ${it.take(300)}") }
            session.transcript.takeIf { it.isNotBlank() }?.let {
                appendLine("Trainee transcript excerpt: ${it.take(450)}")
            }
            session.aiFeedback?.takeIf { it.isNotBlank() }?.let {
                appendLine("Saved evaluator feedback: ${it.take(650)}")
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

    private object FilePaths {
        fun aiCacheDir(context: Context): String =
            java.io.File(context.cacheDir, "interpreter_ai_litert_cache").apply { mkdirs() }.absolutePath
    }

    companion object {
        private val BASE_SYSTEM_PROMPT = """
            You are Interpreter AI, a specialized conversational coach inside the Interpreter Trainer Android app.
            Your job is to help interpreters and interpretation students improve shadowing, consecutive interpreting,
            sight translation, note-taking, terminology, memory, reformulation, numbers, names, fluency and delivery.

            Converse naturally and answer open-ended questions. Use English, French or Arabic according to the user's language.
            Be practical and specific. You are not a general-purpose assistant: politely redirect unrelated requests back to interpreter training.
            Never invent the user's past performance, transcript, score or trend. When practice-session evidence is supplied, distinguish evidence from inference.
            Numeric performance scores come from the app's Local Interpreter Coach evaluator; never overwrite or fabricate them.
            For cross-language interpretation, do not claim semantic equivalence unless the supplied evidence actually supports it.
        """.trimIndent()
    }
}

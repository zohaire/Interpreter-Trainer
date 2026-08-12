package com.interpretertrainer.app.ai

import android.content.Context
import com.arm.aichat.AiChat
import com.arm.aichat.InferenceEngine
import com.interpretertrainer.app.data.database.PracticeSessionEntity
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.Locale

/**
 * Real neural chatbot running Qwen locally through llama.cpp.
 * Numeric assessment stays with LocalInterpreterCoach; this model explains evidence,
 * answers open-ended interpreter-training questions and uses recent session context.
 */
class OnDeviceInterpreterAi(context: Context) {
    private val appContext = context.applicationContext
    private val engine: InferenceEngine = AiChat.getInferenceEngine(appContext)
    private val loadMutex = Mutex()

    @Volatile
    private var systemPromptInstalled = false

    fun isModelInstalled(): Boolean = OnDeviceModelManager.isInstalled(appContext)

    fun isReady(): Boolean = engine.state.value is InferenceEngine.State.ModelReady

    suspend fun ensureLoaded(): Result<Unit> = runCatching {
        loadMutex.withLock {
            if (engine.state.value is InferenceEngine.State.ModelReady && systemPromptInstalled) {
                return@withLock
            }

            if (!OnDeviceModelManager.isInstalled(appContext)) {
                error("Interpreter AI model is not installed yet.")
            }

            when (val state = engine.state.value) {
                is InferenceEngine.State.Error -> engine.cleanUp()
                is InferenceEngine.State.ModelReady -> {
                    // A model is already loaded. Keep it and install the system prompt only
                    // when this instance loaded it. If another state owns it, reload cleanly.
                    if (!systemPromptInstalled) engine.cleanUp()
                }
                else -> Unit
            }

            if (engine.state.value is InferenceEngine.State.Uninitialized ||
                engine.state.value is InferenceEngine.State.Initializing
            ) {
                val initialized = engine.state
                    .filter {
                        it is InferenceEngine.State.Initialized ||
                            it is InferenceEngine.State.Error
                    }
                    .first()
                if (initialized is InferenceEngine.State.Error) throw initialized.exception
            }

            if (engine.state.value !is InferenceEngine.State.Initialized) {
                val state = engine.state.value
                if (state is InferenceEngine.State.Error) throw state.exception
                check(state is InferenceEngine.State.Initialized) {
                    "AI runtime is not ready (${state.javaClass.simpleName})."
                }
            }

            engine.loadModel(OnDeviceModelManager.modelFile(appContext).absolutePath)
            engine.setSystemPrompt(BASE_SYSTEM_PROMPT)
            systemPromptInstalled = true
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

        engine.sendUserPrompt(prompt, maxTokens)
            .toList()
            .joinToString("")
            .trim()
            .ifBlank { error("Interpreter AI returned an empty response.") }
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

        engine.sendUserPrompt(prompt, 520)
            .toList()
            .joinToString("")
            .trim()
            .ifBlank { error("Interpreter AI returned an empty response.") }
    }

    fun unload() {
        if (engine.state.value is InferenceEngine.State.ModelReady) {
            runCatching { engine.cleanUp() }
        }
        systemPromptInstalled = false
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

package com.interpretertrainer.app.ui.screens

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.CloudDone
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.interpretertrainer.app.ai.LocalInterpreterChatbot
import com.interpretertrainer.app.ai.LocalInterpreterCoach
import com.interpretertrainer.app.ai.OpenSourceCoachClient
import com.interpretertrainer.app.ai.OpenSourceCoachSettings
import com.interpretertrainer.app.data.database.PracticeSessionEntity
import com.interpretertrainer.app.model.LanguageOption
import com.interpretertrainer.app.model.PracticeMode
import com.interpretertrainer.app.viewmodel.SessionViewModel
import kotlinx.coroutines.launch

private data class CoachChatMessage(
    val fromUser: Boolean,
    val text: String,
    val suggestions: List<String> = emptyList()
)

@Composable
fun AiCoachScreen(onBack: () -> Unit, sessionViewModel: SessionViewModel) {
    val context = LocalContext.current
    val sessions by sessionViewModel.sessions.collectAsState()
    var section by rememberSaveable { mutableStateOf("CHAT") }
    var serverUrl by rememberSaveable { mutableStateOf(OpenSourceCoachSettings.getServerUrl(context)) }
    var serverDraft by rememberSaveable { mutableStateOf(serverUrl) }
    var showSetup by rememberSaveable { mutableStateOf(false) }

    TrainerScaffold("Interpreter Coach", onBack) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            SectionCard {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Icon(Icons.Default.AutoAwesome, contentDescription = null)
                    Column(Modifier.weight(1f)) {
                        Text("Interpreter AI", style = MaterialTheme.typography.titleMedium)
                        Text(
                            if (serverUrl.isBlank()) {
                                "Offline coach is active. Connect the owner's self-hosted model for open-ended AI conversation."
                            } else {
                                "Enhanced ${OpenSourceCoachSettings.MODEL_LABEL} chat is enabled with the offline coach as fallback."
                            },
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(
                        if (serverUrl.isBlank()) Icons.Default.Lock else Icons.Default.CloudDone,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Text(
                        if (serverUrl.isBlank()) "Offline • private • no API key" else "Self-hosted open-source model • no AI-vendor API key",
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
                OutlinedButton(onClick = { showSetup = !showSetup }) {
                    Icon(Icons.Default.Settings, contentDescription = null)
                    Spacer(Modifier.width(6.dp))
                    Text(if (showSetup) "Hide AI setup" else "AI setup")
                }

                if (showSetup) {
                    OutlinedTextField(
                        value = serverDraft,
                        onValueChange = { serverDraft = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("Self-hosted AI server URL") },
                        placeholder = { Text("https://ai.your-domain.com") },
                        singleLine = true
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = {
                            OpenSourceCoachSettings.setServerUrl(context, serverDraft)
                            serverUrl = OpenSourceCoachSettings.getServerUrl(context)
                            serverDraft = serverUrl
                        }) { Text("Save") }
                        if (serverUrl.isNotBlank()) {
                            OutlinedButton(onClick = {
                                OpenSourceCoachSettings.setServerUrl(context, "")
                                serverUrl = ""
                                serverDraft = ""
                            }) { Text("Use offline only") }
                        }
                    }
                    Text(
                        "Recommended backend: ${OpenSourceCoachSettings.MODEL_LABEL} served by llama.cpp. The app keeps its local evaluator and fallback even if this server is unreachable.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = section == "CHAT",
                    onClick = { section = "CHAT" },
                    label = { Text("Chat") }
                )
                FilterChip(
                    selected = section == "EVALUATE",
                    onClick = { section = "EVALUATE" },
                    label = { Text("Evaluate") }
                )
            }

            Box(Modifier.fillMaxWidth().weight(1f)) {
                if (section == "CHAT") {
                    InterpreterChatPane(sessions = sessions, serverUrl = serverUrl)
                } else {
                    InterpreterEvaluationPane(serverUrl = serverUrl)
                }
            }
        }
    }
}

@Composable
private fun InterpreterChatPane(
    sessions: List<PracticeSessionEntity>,
    serverUrl: String
) {
    val client = remember { OpenSourceCoachClient() }
    val scope = rememberCoroutineScope()
    val initial = remember { LocalInterpreterChatbot.reply("hello", emptyList()) }
    val messages = remember {
        mutableStateListOf(
            CoachChatMessage(
                fromUser = false,
                text = initial.text,
                suggestions = initial.suggestedPrompts
            )
        )
    }
    var input by rememberSaveable { mutableStateOf("") }
    var loading by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()

    fun addOfflineReply(clean: String, prefix: String? = null) {
        val reply = LocalInterpreterChatbot.reply(clean, sessions)
        messages += CoachChatMessage(
            fromUser = false,
            text = if (prefix == null) reply.text else "$prefix\n\n${reply.text}",
            suggestions = reply.suggestedPrompts
        )
    }

    fun sendMessage(value: String) {
        val clean = value.trim()
        if (clean.isBlank() || loading) return

        messages += CoachChatMessage(fromUser = true, text = clean)
        input = ""

        if (serverUrl.isBlank()) {
            addOfflineReply(clean)
            return
        }

        val history = messages.takeLast(12).map { message ->
            OpenSourceCoachClient.ChatMessage(
                role = if (message.fromUser) "user" else "assistant",
                content = message.text
            )
        }
        loading = true
        scope.launch {
            client.chat(
                baseUrl = serverUrl,
                history = history,
                sessions = sessions
            ).onSuccess { answer ->
                messages += CoachChatMessage(fromUser = false, text = answer)
            }.onFailure {
                addOfflineReply(
                    clean,
                    prefix = "Enhanced AI is unavailable right now, so I switched to the offline interpreter coach."
                )
            }
            loading = false
        }
    }

    LaunchedEffect(messages.size, loading) {
        if (messages.isNotEmpty()) listState.animateScrollToItem(messages.lastIndex)
    }

    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxWidth().weight(1f),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            contentPadding = PaddingValues(bottom = 8.dp)
        ) {
            items(messages) { message ->
                Column(Modifier.fillMaxWidth()) {
                    Text(
                        if (message.fromUser) "You" else "Interpreter Coach",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(4.dp))
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = MaterialTheme.shapes.medium,
                        tonalElevation = if (message.fromUser) 3.dp else 1.dp
                    ) {
                        Text(message.text, modifier = Modifier.padding(14.dp))
                    }

                    if (!message.fromUser && message.suggestions.isNotEmpty()) {
                        Spacer(Modifier.height(7.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            message.suggestions.forEach { suggestion ->
                                AssistChip(
                                    onClick = { sendMessage(suggestion) },
                                    label = { Text(suggestion) }
                                )
                            }
                        }
                    }
                }
            }
            if (loading) {
                item {
                    Surface(shape = MaterialTheme.shapes.medium, tonalElevation = 1.dp) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(14.dp),
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                            Text("Interpreter Coach is thinking…")
                        }
                    }
                }
            }
        }

        OutlinedTextField(
            value = input,
            onValueChange = { input = it },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Ask your Interpreter Coach") },
            placeholder = { Text("Ask naturally about your practice or interpreting") },
            minLines = 2,
            maxLines = 4,
            enabled = !loading,
            trailingIcon = {
                IconButton(onClick = { sendMessage(input) }, enabled = input.isNotBlank() && !loading) {
                    Icon(Icons.Default.Send, contentDescription = "Send")
                }
            }
        )
    }
}

@Composable
private fun InterpreterEvaluationPane(serverUrl: String) {
    val client = remember { OpenSourceCoachClient() }
    val scope = rememberCoroutineScope()
    var mode by rememberSaveable { mutableStateOf(PracticeMode.SHADOWING) }
    var sourceLanguage by rememberSaveable { mutableStateOf(LanguageOption.ENGLISH_US) }
    var targetLanguage by rememberSaveable { mutableStateOf(LanguageOption.ENGLISH_US) }
    var sourceText by rememberSaveable { mutableStateOf("") }
    var traineeText by rememberSaveable { mutableStateOf("") }
    var sourceDurationSeconds by rememberSaveable { mutableStateOf("") }
    var traineeDurationSeconds by rememberSaveable { mutableStateOf("") }
    var report by remember { mutableStateOf<LocalInterpreterCoach.Report?>(null) }
    var aiExplanation by rememberSaveable { mutableStateOf("") }
    var aiError by rememberSaveable { mutableStateOf<String?>(null) }
    var aiLoading by remember { mutableStateOf(false) }

    fun clearEnhanced() {
        aiExplanation = ""
        aiError = null
    }

    fun analyze() {
        val sourceMs = sourceDurationSeconds.toDoubleOrNull()?.takeIf { it > 0 }?.times(1000)?.toLong()
        val traineeMs = traineeDurationSeconds.toDoubleOrNull()?.takeIf { it > 0 }?.times(1000)?.toLong()
        report = LocalInterpreterCoach.analyze(
            mode = mode,
            sourceText = sourceText,
            traineeText = traineeText,
            sourceLanguage = sourceLanguage.tag,
            targetLanguage = targetLanguage.tag,
            sourceDurationMillis = sourceMs,
            traineeDurationMillis = traineeMs
        )
        clearEnhanced()
    }

    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("Practice mode", style = MaterialTheme.typography.titleMedium)
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(
                PracticeMode.SHADOWING,
                PracticeMode.CONSECUTIVE,
                PracticeMode.SIGHT_TRANSLATION
            ).forEach { option ->
                FilterChip(
                    selected = mode == option,
                    onClick = {
                        mode = option
                        report = null
                        clearEnhanced()
                        if (option == PracticeMode.SHADOWING) targetLanguage = sourceLanguage
                    },
                    label = { Text(option.label) }
                )
            }
        }

        LanguageSelector("Source language", sourceLanguage) {
            sourceLanguage = it
            if (mode == PracticeMode.SHADOWING) targetLanguage = it
            report = null
            clearEnhanced()
        }
        LanguageSelector("Target / trainee language", targetLanguage) {
            targetLanguage = it
            report = null
            clearEnhanced()
        }

        OutlinedTextField(
            value = sourceText,
            onValueChange = { sourceText = it; report = null; clearEnhanced() },
            modifier = Modifier.fillMaxWidth().heightIn(min = 150.dp),
            label = { Text("Source transcript") },
            placeholder = { Text("Paste the original speech or source text here") }
        )

        OutlinedTextField(
            value = traineeText,
            onValueChange = { traineeText = it; report = null; clearEnhanced() },
            modifier = Modifier.fillMaxWidth().heightIn(min = 150.dp),
            label = { Text("Your transcript / interpretation") },
            placeholder = { Text("Paste what you said here") }
        )

        Text("Timing (optional)", style = MaterialTheme.typography.titleMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = sourceDurationSeconds,
                onValueChange = { sourceDurationSeconds = it.filter { ch -> ch.isDigit() || ch == '.' }; report = null; clearEnhanced() },
                modifier = Modifier.weight(1f),
                label = { Text("Source seconds") },
                singleLine = true
            )
            OutlinedTextField(
                value = traineeDurationSeconds,
                onValueChange = { traineeDurationSeconds = it.filter { ch -> ch.isDigit() || ch == '.' }; report = null; clearEnhanced() },
                modifier = Modifier.weight(1f),
                label = { Text("Your seconds") },
                singleLine = true
            )
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                enabled = sourceText.isNotBlank() || traineeText.isNotBlank() ||
                    (sourceDurationSeconds.isNotBlank() && traineeDurationSeconds.isNotBlank()),
                onClick = { analyze() }
            ) {
                Icon(Icons.Default.AutoAwesome, contentDescription = null)
                Spacer(Modifier.width(6.dp))
                Text("Analyze")
            }
            OutlinedButton(onClick = {
                sourceText = ""
                traineeText = ""
                sourceDurationSeconds = ""
                traineeDurationSeconds = ""
                report = null
                clearEnhanced()
            }) { Text("Clear") }
        }

        report?.let { result ->
            SectionCard {
                Text(result.scoreLabel, style = MaterialTheme.typography.titleMedium)
                result.overallScore?.let { score ->
                    Text("$score / 100", style = MaterialTheme.typography.headlineMedium)
                    LinearProgressIndicator(
                        progress = { score / 100f },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
                Text(result.summary)
            }

            if (result.metrics.isNotEmpty()) {
                SectionCard {
                    Text("Metrics", style = MaterialTheme.typography.titleMedium)
                    result.metrics.forEachIndexed { index, metric ->
                        if (index > 0) HorizontalDivider(Modifier.padding(vertical = 6.dp))
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text(metric.label, style = MaterialTheme.typography.titleSmall)
                            metric.score?.let { Text("$it/100") }
                        }
                        Text(metric.detail, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }

            if (result.strengths.isNotEmpty()) FeedbackListCard("Strengths", result.strengths)
            if (result.improvements.isNotEmpty()) FeedbackListCard("Improve next", result.improvements)
            if (result.evidence.isNotEmpty()) FeedbackListCard("Evidence", result.evidence)
            result.limitation?.let {
                SectionCard {
                    Text("Evaluator scope", style = MaterialTheme.typography.titleMedium)
                    Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }

            if (serverUrl.isNotBlank()) {
                Button(
                    enabled = !aiLoading,
                    modifier = Modifier.fillMaxWidth(),
                    onClick = {
                        aiLoading = true
                        aiError = null
                        scope.launch {
                            client.explainEvaluation(
                                baseUrl = serverUrl,
                                mode = mode.label,
                                sourceLanguage = sourceLanguage.tag,
                                targetLanguage = targetLanguage.tag,
                                sourceText = sourceText,
                                traineeText = traineeText,
                                evaluatorReport = result.asPlainText()
                            ).onSuccess { aiExplanation = it }
                                .onFailure { aiError = it.message ?: "Enhanced AI explanation failed." }
                            aiLoading = false
                        }
                    }
                ) {
                    if (aiLoading) {
                        CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(8.dp))
                    }
                    Text(if (aiLoading) "Preparing coaching feedback…" else "Explain with Enhanced AI")
                }
            }

            aiError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            if (aiExplanation.isNotBlank()) {
                SectionCard {
                    Text("Interpreter AI feedback", style = MaterialTheme.typography.titleMedium)
                    Text(aiExplanation)
                    Text(
                        "The numeric score above still comes from the local evidence-based evaluator.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
        Spacer(Modifier.height(8.dp))
    }
}

@Composable
private fun FeedbackListCard(title: String, items: List<String>) {
    SectionCard {
        Text(title, style = MaterialTheme.typography.titleMedium)
        items.forEach { item -> Text("• $item", modifier = Modifier.padding(vertical = 2.dp)) }
    }
}

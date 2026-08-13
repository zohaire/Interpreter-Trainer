package com.interpretertrainer.app.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Send
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.interpretertrainer.app.ai.LocalInterpreterCoach
import com.interpretertrainer.app.ai.OnlineInterpreterAi
import com.interpretertrainer.app.data.database.PracticeSessionEntity
import com.interpretertrainer.app.model.LanguageOption
import com.interpretertrainer.app.model.PracticeMode
import com.interpretertrainer.app.viewmodel.SessionViewModel
import kotlinx.coroutines.launch

private data class CoachChatMessage(
    val fromUser: Boolean,
    val text: String
)

@Composable
fun AiCoachScreen(onBack: () -> Unit, sessionViewModel: SessionViewModel) {
    val context = LocalContext.current
    val sessions by sessionViewModel.sessions.collectAsState()
    val interpreterAi = remember { OnlineInterpreterAi(context) }
    val serviceConfigured = remember { interpreterAi.isConfigured() }
    var section by rememberSaveable { mutableStateOf("CHAT") }

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
                    Icon(
                        if (serviceConfigured) Icons.Default.Cloud else Icons.Default.CloudOff,
                        contentDescription = null
                    )
                    Column(Modifier.weight(1f)) {
                        Text("Interpreter AI • online", style = MaterialTheme.typography.titleMedium)
                        Text(
                            if (serviceConfigured) {
                                "A hosted open-weight model powers chat and coaching. Nothing large is downloaded to this phone."
                            } else {
                                "The app is ready for the hosted AI service, but this build does not contain its deployed endpoint yet."
                            },
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(Icons.Default.Lock, contentDescription = null, modifier = Modifier.size(18.dp))
                    Text(
                        "Internet required • no model package • no AI provider key stored in the APK",
                        style = MaterialTheme.typography.bodyMedium
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
                    InterpreterChatPane(
                        sessions = sessions,
                        interpreterAi = interpreterAi,
                        serviceConfigured = serviceConfigured
                    )
                } else {
                    InterpreterEvaluationPane(
                        interpreterAi = interpreterAi,
                        serviceConfigured = serviceConfigured
                    )
                }
            }
        }
    }
}

@Composable
private fun InterpreterChatPane(
    sessions: List<PracticeSessionEntity>,
    interpreterAi: OnlineInterpreterAi,
    serviceConfigured: Boolean
) {
    val scope = rememberCoroutineScope()
    val messages = remember {
        mutableStateListOf(
            CoachChatMessage(
                fromUser = false,
                text = if (serviceConfigured) {
                    "Interpreter AI is ready. Ask me about interpreting, your saved practice, weaknesses, terminology, training plans, or how to improve your performance."
                } else {
                    "Interpreter AI is being switched to its hosted service. This build cannot send AI requests until the deployment endpoint is configured."
                }
            )
        )
    }
    var input by rememberSaveable { mutableStateOf("") }
    var loading by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()

    fun sendMessage(value: String) {
        val clean = value.trim()
        if (clean.isBlank() || loading || !serviceConfigured) return

        messages += CoachChatMessage(fromUser = true, text = clean)
        input = ""
        loading = true

        scope.launch {
            interpreterAi.chat(clean, sessions)
                .onSuccess { answer ->
                    messages += CoachChatMessage(fromUser = false, text = answer)
                }
                .onFailure { failure ->
                    messages += CoachChatMessage(
                        fromUser = false,
                        text = "Interpreter AI could not answer: ${failure.message ?: "online request failed"}"
                    )
                }
            loading = false
        }
    }

    LaunchedEffect(messages.size, loading) {
        if (messages.isNotEmpty()) listState.animateScrollToItem(messages.lastIndex)
    }

    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        if (!serviceConfigured) {
            Surface(
                shape = MaterialTheme.shapes.medium,
                color = MaterialTheme.colorScheme.secondaryContainer
            ) {
                Text(
                    "Online AI deployment is not connected in this build. There is no hidden rule-based fallback and no model download.",
                    modifier = Modifier.padding(12.dp),
                    color = MaterialTheme.colorScheme.onSecondaryContainer
                )
            }
        }

        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxWidth().weight(1f),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            contentPadding = PaddingValues(bottom = 8.dp)
        ) {
            items(messages) { message ->
                Column(Modifier.fillMaxWidth()) {
                    Text(
                        if (message.fromUser) "You" else "Interpreter AI • online",
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
                            Text("Interpreter AI is generating…")
                        }
                    }
                }
            }
        }

        OutlinedTextField(
            value = input,
            onValueChange = { input = it },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Ask Interpreter AI") },
            placeholder = { Text("e.g. Why am I losing numbers in consecutive interpreting?") },
            minLines = 2,
            maxLines = 4,
            enabled = serviceConfigured && !loading,
            trailingIcon = {
                IconButton(
                    onClick = { sendMessage(input) },
                    enabled = serviceConfigured && input.isNotBlank() && !loading
                ) {
                    Icon(Icons.Default.Send, contentDescription = "Send")
                }
            }
        )
    }
}

@Composable
private fun InterpreterEvaluationPane(
    interpreterAi: OnlineInterpreterAi,
    serviceConfigured: Boolean
) {
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

    fun clearAiExplanation() {
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
        clearAiExplanation()
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
                        clearAiExplanation()
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
            clearAiExplanation()
        }
        LanguageSelector("Target / trainee language", targetLanguage) {
            targetLanguage = it
            report = null
            clearAiExplanation()
        }

        OutlinedTextField(
            value = sourceText,
            onValueChange = { sourceText = it; report = null; clearAiExplanation() },
            modifier = Modifier.fillMaxWidth().heightIn(min = 150.dp),
            label = { Text("Source transcript") },
            placeholder = { Text("Paste the original speech or source text here") }
        )

        OutlinedTextField(
            value = traineeText,
            onValueChange = { traineeText = it; report = null; clearAiExplanation() },
            modifier = Modifier.fillMaxWidth().heightIn(min = 150.dp),
            label = { Text("Your transcript / interpretation") },
            placeholder = { Text("Paste what you said here") }
        )

        Text("Timing (optional)", style = MaterialTheme.typography.titleMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = sourceDurationSeconds,
                onValueChange = {
                    sourceDurationSeconds = it.filter { ch -> ch.isDigit() || ch == '.' }
                    report = null
                    clearAiExplanation()
                },
                modifier = Modifier.weight(1f),
                label = { Text("Source seconds") },
                singleLine = true
            )
            OutlinedTextField(
                value = traineeDurationSeconds,
                onValueChange = {
                    traineeDurationSeconds = it.filter { ch -> ch.isDigit() || ch == '.' }
                    report = null
                    clearAiExplanation()
                },
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
                clearAiExplanation()
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
                    Text("What the measured score can judge", style = MaterialTheme.typography.titleMedium)
                    Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }

            SectionCard {
                Text("AI coaching explanation", style = MaterialTheme.typography.titleMedium)
                Text(
                    if (serviceConfigured) {
                        "Interpreter AI can explain the measured report and suggest targeted exercises. The evaluator's numeric scores remain authoritative."
                    } else {
                        "The measured report works now. The hosted AI explanation becomes available when the online service is deployed."
                    },
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Button(
                    enabled = serviceConfigured && !aiLoading,
                    onClick = {
                        aiLoading = true
                        aiError = null
                        scope.launch {
                            interpreterAi.explainEvaluation(
                                mode = mode.label,
                                sourceLanguage = sourceLanguage.tag,
                                targetLanguage = targetLanguage.tag,
                                sourceText = sourceText,
                                traineeText = traineeText,
                                evaluatorReport = result.asPlainText()
                            ).onSuccess {
                                aiExplanation = it
                            }.onFailure {
                                aiError = it.message ?: "AI explanation failed."
                            }
                            aiLoading = false
                        }
                    }
                ) {
                    Icon(Icons.Default.AutoAwesome, contentDescription = null)
                    Spacer(Modifier.width(6.dp))
                    Text("Explain with Interpreter AI")
                }

                if (aiLoading) {
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                        Text("Generating coaching feedback…")
                    }
                }
                aiError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                if (aiExplanation.isNotBlank()) Text(aiExplanation)
            }
        }
        Spacer(Modifier.height(8.dp))
    }
}

@Composable
private fun FeedbackListCard(title: String, items: List<String>) {
    SectionCard {
        Text(title, style = MaterialTheme.typography.titleMedium)
        items.forEach { item ->
            Text("• $item", modifier = Modifier.padding(vertical = 2.dp))
        }
    }
}

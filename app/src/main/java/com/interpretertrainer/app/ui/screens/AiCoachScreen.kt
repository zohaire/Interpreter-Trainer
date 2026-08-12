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
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Send
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.interpretertrainer.app.ai.LocalInterpreterChatbot
import com.interpretertrainer.app.ai.LocalInterpreterCoach
import com.interpretertrainer.app.data.database.PracticeSessionEntity
import com.interpretertrainer.app.model.LanguageOption
import com.interpretertrainer.app.model.PracticeMode
import com.interpretertrainer.app.viewmodel.SessionViewModel

private data class CoachChatMessage(
    val fromUser: Boolean,
    val text: String,
    val suggestions: List<String> = emptyList()
)

@Composable
fun AiCoachScreen(onBack: () -> Unit, sessionViewModel: SessionViewModel) {
    val sessions by sessionViewModel.sessions.collectAsState()
    var section by rememberSaveable { mutableStateOf("CHAT") }

    TrainerScaffold("Interpreter Coach", onBack) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            SectionCard {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Icon(Icons.Default.AutoAwesome, contentDescription = null)
                    Column(Modifier.weight(1f)) {
                        Text("Independent interpreter assistant", style = MaterialTheme.typography.titleMedium)
                        Text(
                            "Chat about interpreting, use your saved practice history, or evaluate a performance. Everything in this version runs locally on your phone.",
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(Icons.Default.Lock, contentDescription = null, modifier = Modifier.size(18.dp))
                    Text("Offline • private • specialized", style = MaterialTheme.typography.bodyMedium)
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
                    InterpreterChatPane(sessions = sessions)
                } else {
                    InterpreterEvaluationPane()
                }
            }
        }
    }
}

@Composable
private fun InterpreterChatPane(sessions: List<PracticeSessionEntity>) {
    val initial = remember {
        LocalInterpreterChatbot.reply("hello", emptyList())
    }
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
    val listState = rememberLazyListState()

    fun sendMessage(value: String) {
        val clean = value.trim()
        if (clean.isBlank()) return
        messages += CoachChatMessage(fromUser = true, text = clean)
        val reply = LocalInterpreterChatbot.reply(clean, sessions)
        messages += CoachChatMessage(
            fromUser = false,
            text = reply.text,
            suggestions = reply.suggestedPrompts
        )
        input = ""
    }

    LaunchedEffect(messages.size) {
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
        }

        OutlinedTextField(
            value = input,
            onValueChange = { input = it },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Ask your Interpreter Coach") },
            placeholder = { Text("e.g. What should I practice today?") },
            minLines = 2,
            maxLines = 4,
            trailingIcon = {
                IconButton(onClick = { sendMessage(input) }, enabled = input.isNotBlank()) {
                    Icon(Icons.Default.Send, contentDescription = "Send")
                }
            }
        )
    }
}

@Composable
private fun InterpreterEvaluationPane() {
    var mode by rememberSaveable { mutableStateOf(PracticeMode.SHADOWING) }
    var sourceLanguage by rememberSaveable { mutableStateOf(LanguageOption.ENGLISH_US) }
    var targetLanguage by rememberSaveable { mutableStateOf(LanguageOption.ENGLISH_US) }
    var sourceText by rememberSaveable { mutableStateOf("") }
    var traineeText by rememberSaveable { mutableStateOf("") }
    var sourceDurationSeconds by rememberSaveable { mutableStateOf("") }
    var traineeDurationSeconds by rememberSaveable { mutableStateOf("") }
    var report by remember { mutableStateOf<LocalInterpreterCoach.Report?>(null) }

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
        }
        LanguageSelector("Target / trainee language", targetLanguage) {
            targetLanguage = it
            report = null
        }

        OutlinedTextField(
            value = sourceText,
            onValueChange = { sourceText = it; report = null },
            modifier = Modifier.fillMaxWidth().heightIn(min = 150.dp),
            label = { Text("Source transcript") },
            placeholder = { Text("Paste the original speech or source text here") }
        )

        OutlinedTextField(
            value = traineeText,
            onValueChange = { traineeText = it; report = null },
            modifier = Modifier.fillMaxWidth().heightIn(min = 150.dp),
            label = { Text("Your transcript / interpretation") },
            placeholder = { Text("Paste what you said here") }
        )

        Text("Timing (optional)", style = MaterialTheme.typography.titleMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = sourceDurationSeconds,
                onValueChange = { sourceDurationSeconds = it.filter { ch -> ch.isDigit() || ch == '.' }; report = null },
                modifier = Modifier.weight(1f),
                label = { Text("Source seconds") },
                singleLine = true
            )
            OutlinedTextField(
                value = traineeDurationSeconds,
                onValueChange = { traineeDurationSeconds = it.filter { ch -> ch.isDigit() || ch == '.' }; report = null },
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
                Text("Analyze Performance")
            }
            OutlinedButton(onClick = {
                sourceText = ""
                traineeText = ""
                sourceDurationSeconds = ""
                traineeDurationSeconds = ""
                report = null
            }) { Text("Clear") }
        }

        report?.let { result ->
            SectionCard {
                Text(result.scoreLabel, style = MaterialTheme.typography.titleMedium)
                result.overallScore?.let { score ->
                    Spacer(Modifier.height(6.dp))
                    Text("$score / 100", style = MaterialTheme.typography.headlineMedium)
                    Spacer(Modifier.height(8.dp))
                    LinearProgressIndicator(
                        progress = { score / 100f },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
                Spacer(Modifier.height(8.dp))
                Text(result.summary)
            }

            if (result.metrics.isNotEmpty()) {
                SectionCard {
                    Text("Metrics", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(8.dp))
                    result.metrics.forEachIndexed { index, metric ->
                        if (index > 0) HorizontalDivider(Modifier.padding(vertical = 10.dp))
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text(metric.label, style = MaterialTheme.typography.titleSmall)
                            metric.score?.let { Text("$it/100") }
                        }
                        Spacer(Modifier.height(3.dp))
                        Text(metric.detail, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }

            if (result.strengths.isNotEmpty()) FeedbackListCard("Strengths", result.strengths)
            if (result.improvements.isNotEmpty()) FeedbackListCard("Improve next", result.improvements)
            if (result.evidence.isNotEmpty()) FeedbackListCard("Evidence", result.evidence)
            result.limitation?.let {
                SectionCard {
                    Text("What this local engine can judge", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(6.dp))
                    Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
        Spacer(Modifier.height(6.dp))
        items.forEach { item ->
            Text("• $item", modifier = Modifier.padding(vertical = 2.dp))
        }
    }
}

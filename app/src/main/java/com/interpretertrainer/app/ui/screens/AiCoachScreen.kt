package com.interpretertrainer.app.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.interpretertrainer.app.ai.LocalInterpreterCoach
import com.interpretertrainer.app.model.LanguageOption
import com.interpretertrainer.app.model.PracticeMode

@Composable
fun AiCoachScreen(onBack: () -> Unit) {
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

    TrainerScaffold("Local Interpreter Coach", onBack) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            SectionCard {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Icon(Icons.Default.AutoAwesome, contentDescription = null)
                    Column(Modifier.weight(1f)) {
                        Text("Independent feedback engine", style = MaterialTheme.typography.titleMedium)
                        Text(
                            "Built specifically for interpreter practice. It runs on your phone and does not call ChatGPT, Claude, Gemini or any external AI API.",
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(Icons.Default.Lock, contentDescription = null, modifier = Modifier.size(18.dp))
                    Text("Offline • private • no API key", style = MaterialTheme.typography.bodyMedium)
                }
            }

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
                modifier = Modifier.fillMaxWidth().heightIn(min = 160.dp),
                label = { Text("Source transcript") },
                placeholder = { Text("Paste the original speech or source text here") }
            )

            OutlinedTextField(
                value = traineeText,
                onValueChange = { traineeText = it; report = null },
                modifier = Modifier.fillMaxWidth().heightIn(min = 160.dp),
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

                if (result.strengths.isNotEmpty()) {
                    FeedbackListCard("Strengths", result.strengths)
                }
                if (result.improvements.isNotEmpty()) {
                    FeedbackListCard("Improve next", result.improvements)
                }
                if (result.evidence.isNotEmpty()) {
                    FeedbackListCard("Evidence", result.evidence)
                }
                result.limitation?.let {
                    SectionCard {
                        Text("What this local engine can judge", style = MaterialTheme.typography.titleMedium)
                        Spacer(Modifier.height(6.dp))
                        Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
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

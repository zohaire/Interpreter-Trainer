package com.interpretertrainer.app.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.interpretertrainer.app.ai.AiPracticeBridge
import com.interpretertrainer.app.data.database.PracticeSessionEntity
import com.interpretertrainer.app.model.LanguageOption
import com.interpretertrainer.app.model.PracticeMode
import com.interpretertrainer.app.speech.SpeechRecognizerManager
import com.interpretertrainer.app.viewmodel.SessionViewModel

@Composable
fun LiveTranscriptionScreen(
    onBack: () -> Unit,
    sessionViewModel: SessionViewModel,
    onOpenAiCoach: () -> Unit
) {
    val context = LocalContext.current
    val speech = remember { SpeechRecognizerManager(context.applicationContext) }
    val state by speech.state.collectAsState()
    val aiPayload by AiPracticeBridge.payload.collectAsState()
    var language by rememberSaveable { mutableStateOf(LanguageOption.ENGLISH_US) }
    var startedAt by rememberSaveable { mutableLongStateOf(0L) }
    var aiReferenceText by rememberSaveable { mutableStateOf("") }
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted -> if (granted) { startedAt = System.currentTimeMillis(); speech.start(language.tag) } }
    DisposableEffect(Unit) { onDispose { speech.destroy() } }

    LaunchedEffect(aiPayload?.id) {
        val payload = aiPayload
        if (payload != null && payload.mode == AiPracticeBridge.MODE_TRANSCRIPTION) {
            aiReferenceText = payload.text
            AiPracticeBridge.consume(payload.id)
        }
    }

    fun startListening() {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            if (startedAt == 0L) startedAt = System.currentTimeMillis(); speech.start(language.tag)
        } else permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
    }

    TrainerScaffold("Live Transcription", onBack) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            LanguageSelector("Recognition language", language) { language = it; if (state.isListening) { speech.stop(); speech.start(it.tag) } }

            SectionCard {
                Text("AI reference text", style = MaterialTheme.typography.titleMedium)
                Text(
                    "Ask Interpreter AI for a short passage, then use “Use in Transcription” under its answer. You can read or play the passage while checking how accurately live transcription captures it.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                OutlinedButton(onClick = onOpenAiCoach) { Text("Open AI Coach") }
                OutlinedTextField(
                    value = aiReferenceText,
                    onValueChange = { aiReferenceText = it },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 110.dp),
                    label = { Text("Reference / practice text") },
                    placeholder = { Text("AI-generated transcription practice text will appear here") }
                )
            }

            Text(if (state.isListening) "Listening…" else "Microphone stopped", style = MaterialTheme.typography.titleMedium)
            state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            OutlinedTextField(value = listOf(state.finalText, state.partialText).filter { it.isNotBlank() }.joinToString(" "), onValueChange = {}, readOnly = true, modifier = Modifier.fillMaxWidth().weight(1f), label = { Text("Live transcript") })
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { if (state.isListening) speech.stop() else startListening() }) { Text(if (state.isListening) "Stop" else "Start transcription") }
                OutlinedButton(onClick = speech::clearTranscript) { Text("Clear") }
            }
            Button(enabled = state.finalText.isNotBlank(), onClick = {
                speech.stop(); val now = System.currentTimeMillis()
                sessionViewModel.save(
                    PracticeSessionEntity(
                        practiceMode = PracticeMode.LIVE_TRANSCRIPTION.name,
                        sourceLanguage = language.tag,
                        targetLanguage = language.tag,
                        startedAt = startedAt.takeIf { it > 0 } ?: now,
                        durationMillis = if (startedAt > 0) now - startedAt else 0,
                        sourceName = if (aiReferenceText.isNotBlank()) "AI Coach reference text" else null,
                        transcript = state.finalText,
                        notes = if (aiReferenceText.isNotBlank()) "Reference text:\n$aiReferenceText" else "",
                        segmentDurationSeconds = null,
                        status = "COMPLETED"
                    )
                )
            }) { Text("Save session") }
        }
    }
}

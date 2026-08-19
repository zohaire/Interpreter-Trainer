package com.interpretertrainer.app.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Stop
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

    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) {
            startedAt = System.currentTimeMillis()
            speech.start(language.tag)
        }
    }

    DisposableEffect(Unit) { onDispose { speech.destroy() } }

    LaunchedEffect(aiPayload?.id) {
        val payload = aiPayload
        if (payload != null && payload.mode == AiPracticeBridge.MODE_TRANSCRIPTION) {
            aiReferenceText = payload.text
            AiPracticeBridge.consume(payload.id)
        }
    }

    fun applyLanguage(option: LanguageOption) {
        language = option
        if (state.isListening) {
            speech.stop()
            speech.start(option.tag)
        }
    }

    fun startListening() {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            if (startedAt == 0L) startedAt = System.currentTimeMillis()
            speech.start(language.tag)
        } else {
            permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    val visibleTranscript = listOf(state.finalText, state.partialText)
        .filter { it.isNotBlank() }
        .joinToString(" ")

    TrainerScaffold("Live Transcription", onBack) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 14.dp, vertical = 10.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            PracticeTopStrip(
                sourceLanguage = language,
                targetLanguage = language,
                onSourceLanguageChange = ::applyLanguage,
                onTargetLanguageChange = ::applyLanguage,
                sourceLabel = "Speech",
                targetLabel = "Text"
            )

            SectionCard {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Column {
                        Text("Practice source", style = MaterialTheme.typography.titleMedium)
                        Text(
                            "Optional reference material from Interpreter AI",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    FilledTonalIconButton(onClick = onOpenAiCoach) {
                        Icon(Icons.Default.AutoAwesome, contentDescription = "Open Interpreter AI")
                    }
                }
                OutlinedTextField(
                    value = aiReferenceText,
                    onValueChange = { aiReferenceText = it },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 105.dp),
                    placeholder = { Text("Send a passage from Interpreter AI or paste one here") },
                    shape = RoundedCornerShape(18.dp)
                )
            }

            SectionCard {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("Live transcript", style = MaterialTheme.typography.titleMedium)
                    AssistChip(
                        onClick = {},
                        label = { Text(if (state.isListening) "Listening" else language.tag) },
                        leadingIcon = {
                            Icon(
                                if (state.isListening) Icons.Default.Mic else Icons.Default.Mic,
                                contentDescription = null,
                                modifier = Modifier.size(17.dp)
                            )
                        }
                    )
                }
                Text(
                    if (state.isListening) "Speak naturally. Partial recognition appears immediately." else "Tap Start to activate the microphone.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }

                OutlinedTextField(
                    value = visibleTranscript,
                    onValueChange = {},
                    readOnly = true,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 210.dp),
                    placeholder = { Text("Your transcript will appear here") },
                    shape = RoundedCornerShape(18.dp)
                )

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = { if (state.isListening) speech.stop() else startListening() },
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(18.dp)
                    ) {
                        Icon(if (state.isListening) Icons.Default.Stop else Icons.Default.Mic, contentDescription = null)
                        Text(if (state.isListening) " Stop" else " Start")
                    }
                    FilledTonalIconButton(
                        onClick = speech::clearTranscript,
                        enabled = !state.isListening && visibleTranscript.isNotBlank()
                    ) {
                        Icon(Icons.Default.DeleteOutline, contentDescription = "Clear transcript")
                    }
                }
            }

            Button(
                enabled = state.finalText.isNotBlank(),
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(18.dp),
                onClick = {
                    speech.stop()
                    val now = System.currentTimeMillis()
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
                }
            ) { Text("Save session") }

            Spacer(Modifier.height(8.dp))
        }
    }
}

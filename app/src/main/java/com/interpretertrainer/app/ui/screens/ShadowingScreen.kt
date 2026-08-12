package com.interpretertrainer.app.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.media3.ui.PlayerView
import com.interpretertrainer.app.ai.AiBackendSettings
import com.interpretertrainer.app.ai.AiCoachClient
import com.interpretertrainer.app.data.database.PracticeSessionEntity
import com.interpretertrainer.app.media.MediaController
import com.interpretertrainer.app.media.ShadowingRecorder
import com.interpretertrainer.app.model.LanguageOption
import com.interpretertrainer.app.model.PracticeMode
import com.interpretertrainer.app.util.formatDuration
import com.interpretertrainer.app.viewmodel.SessionViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File

@Composable
fun ShadowingScreen(
    onBack: () -> Unit,
    sessionViewModel: SessionViewModel,
    onOpenAiCoach: () -> Unit
) {
    val context = LocalContext.current
    val sourceMedia = remember { MediaController(context) }
    val recordingMedia = remember { MediaController(context) }
    val recorder = remember { ShadowingRecorder(context.applicationContext) }
    val aiClient = remember { AiCoachClient() }
    val scope = rememberCoroutineScope()

    var sourceName by rememberSaveable { mutableStateOf<String?>(null) }
    var notes by rememberSaveable { mutableStateOf("") }
    var speed by rememberSaveable { mutableFloatStateOf(1f) }
    var sourceLang by rememberSaveable { mutableStateOf(LanguageOption.ENGLISH_US) }
    var recordingPath by rememberSaveable { mutableStateOf<String?>(null) }
    var recordingElapsed by rememberSaveable { mutableLongStateOf(0L) }
    var recordingStartedAt by rememberSaveable { mutableLongStateOf(0L) }
    var aiFeedback by rememberSaveable { mutableStateOf("") }
    var aiTranscript by rememberSaveable { mutableStateOf("") }
    var aiScore by rememberSaveable { mutableStateOf<Int?>(null) }
    var aiError by rememberSaveable { mutableStateOf<String?>(null) }
    var aiLoading by remember { mutableStateOf(false) }
    var isRecording by remember { mutableStateOf(false) }
    var backendUrl by rememberSaveable { mutableStateOf(AiBackendSettings.getUrl(context)) }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        uri?.let {
            sourceName = it.lastPathSegment
            sourceMedia.load(it)
            recordingElapsed = 0L
            aiFeedback = ""
            aiTranscript = ""
            aiScore = null
            aiError = null
        }
    }

    fun beginShadowing() {
        if (sourceName == null) {
            aiError = "Choose an audio or video source first."
            return
        }
        if (isRecording) return

        runCatching {
            recordingMedia.pause()
            val file = recorder.start()
            recordingPath = file.absolutePath
            recordingStartedAt = System.currentTimeMillis()
            recordingElapsed = 0L
            aiFeedback = ""
            aiTranscript = ""
            aiScore = null
            aiError = null
            isRecording = true
            sourceMedia.play()
        }.onFailure {
            aiError = it.message ?: "Could not start microphone recording."
            isRecording = false
        }
    }

    fun finishShadowing() {
        sourceMedia.pause()
        recorder.stop()?.let { recordingPath = it.absolutePath }
        isRecording = false
    }

    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) beginShadowing() else aiError = "Microphone permission is required to record shadowing practice."
    }

    fun requestStart() {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            beginShadowing()
        } else {
            permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    LaunchedEffect(isRecording) {
        while (isRecording) {
            recordingElapsed = System.currentTimeMillis() - recordingStartedAt
            delay(250)
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            if (recorder.isRecording) recorder.stop()
            recorder.release()
            sourceMedia.release()
            recordingMedia.release()
        }
    }

    TrainerScaffold("Shadowing", onBack) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Button(onClick = { picker.launch(arrayOf("audio/*", "video/*")) }, enabled = !isRecording) {
                Text("Choose audio / video")
            }

            AndroidView(
                factory = { PlayerView(it).apply { player = sourceMedia.player; useController = true } },
                modifier = Modifier.fillMaxWidth().height(210.dp)
            )

            LanguageSelector("Practice language", sourceLang) { sourceLang = it }

            Text("Playback speed")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(.75f, 1f, 1.25f).forEach { value ->
                    FilterChip(
                        selected = speed == value,
                        enabled = !isRecording,
                        onClick = {
                            speed = value
                            sourceMedia.setSpeed(value)
                        },
                        label = { Text("${value}x") }
                    )
                }
            }

            SectionCard {
                Text("Your shadowing recording", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(6.dp))
                Text(
                    "Use headphones when possible so the microphone captures your voice rather than the source audio.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(10.dp))
                Text("Recording time: ${formatDuration(recordingElapsed)}")
                Spacer(Modifier.height(10.dp))

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (!isRecording) {
                        Button(onClick = { requestStart() }, enabled = sourceName != null) {
                            Icon(Icons.Default.Mic, contentDescription = null)
                            Spacer(Modifier.width(6.dp))
                            Text("Start Shadowing")
                        }
                    } else {
                        Button(onClick = { finishShadowing() }) {
                            Icon(Icons.Default.Stop, contentDescription = null)
                            Spacer(Modifier.width(6.dp))
                            Text("Stop & Save Recording")
                        }
                    }
                }

                recordingPath?.let { path ->
                    val file = File(path)
                    if (file.exists() && !isRecording) {
                        Spacer(Modifier.height(10.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(onClick = {
                                recordingMedia.load(Uri.fromFile(file))
                                recordingMedia.play()
                            }) {
                                Icon(Icons.Default.PlayArrow, contentDescription = null)
                                Spacer(Modifier.width(6.dp))
                                Text("Play my recording")
                            }
                            OutlinedButton(onClick = { recordingMedia.pause() }) { Text("Stop playback") }
                        }
                    }
                }
            }

            OutlinedTextField(
                value = notes,
                onValueChange = { notes = it },
                modifier = Modifier.fillMaxWidth().heightIn(min = 120.dp),
                label = { Text("Notes") }
            )

            SectionCard {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(Icons.Default.AutoAwesome, contentDescription = null)
                    Text("AI Shadowing Feedback", style = MaterialTheme.typography.titleMedium)
                }
                Spacer(Modifier.height(8.dp))

                if (backendUrl.isBlank()) {
                    Text(
                        "Configure the secure AI server before requesting feedback.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedButton(onClick = {
                        if (isRecording) finishShadowing()
                        onOpenAiCoach()
                    }) { Text("Configure AI Coach") }
                } else {
                    Button(
                        enabled = !aiLoading && !isRecording && recordingPath?.let { File(it).exists() } == true,
                        onClick = {
                            val file = recordingPath?.let(::File) ?: return@Button
                            aiLoading = true
                            aiError = null
                            scope.launch {
                                aiClient.analyzeShadowing(
                                    baseUrl = backendUrl,
                                    recording = file,
                                    languageTag = sourceLang.tag,
                                    sourceName = sourceName,
                                    notes = notes,
                                    speed = speed
                                ).onSuccess { result ->
                                    aiTranscript = result.transcript
                                    aiFeedback = result.feedback
                                    aiScore = result.score
                                }.onFailure {
                                    aiError = it.message ?: "AI feedback request failed."
                                }
                                aiLoading = false
                            }
                        }
                    ) {
                        if (aiLoading) {
                            CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                            Spacer(Modifier.width(8.dp))
                        }
                        Text(if (aiLoading) "Analyzing…" else "Generate AI Feedback")
                    }
                }

                aiError?.let {
                    Spacer(Modifier.height(8.dp))
                    Text(it, color = MaterialTheme.colorScheme.error)
                }

                aiScore?.let {
                    Spacer(Modifier.height(10.dp))
                    Text("AI score: $it / 100", style = MaterialTheme.typography.titleMedium)
                }
                if (aiTranscript.isNotBlank()) {
                    Spacer(Modifier.height(10.dp))
                    Text("Recording transcript", style = MaterialTheme.typography.titleSmall)
                    Text(aiTranscript)
                }
                if (aiFeedback.isNotBlank()) {
                    Spacer(Modifier.height(10.dp))
                    Text("Coach feedback", style = MaterialTheme.typography.titleSmall)
                    Text(aiFeedback)
                }
            }

            Button(
                enabled = sourceName != null && !isRecording,
                onClick = {
                    val position = sourceMedia.player.currentPosition.coerceAtLeast(0L)
                    sessionViewModel.save(
                        PracticeSessionEntity(
                            practiceMode = PracticeMode.SHADOWING.name,
                            sourceLanguage = sourceLang.tag,
                            targetLanguage = sourceLang.tag,
                            startedAt = System.currentTimeMillis() - position,
                            durationMillis = position,
                            sourceName = sourceName,
                            transcript = aiTranscript,
                            notes = notes,
                            segmentDurationSeconds = null,
                            status = "COMPLETED",
                            recordingPath = recordingPath,
                            aiFeedback = aiFeedback.ifBlank { null }
                        )
                    )
                }
            ) { Text("Save session") }
        }
    }
}

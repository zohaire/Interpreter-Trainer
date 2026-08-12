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
import androidx.compose.material.icons.filled.Link
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
import com.interpretertrainer.app.ai.LocalInterpreterCoach
import com.interpretertrainer.app.data.database.PracticeSessionEntity
import com.interpretertrainer.app.media.MediaController
import com.interpretertrainer.app.media.ShadowingRecorder
import com.interpretertrainer.app.model.LanguageOption
import com.interpretertrainer.app.model.PracticeMode
import com.interpretertrainer.app.util.formatDuration
import com.interpretertrainer.app.viewmodel.SessionViewModel
import kotlinx.coroutines.delay
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

    var sourceName by rememberSaveable { mutableStateOf<String?>(null) }
    var mediaUrl by rememberSaveable { mutableStateOf("") }
    var notes by rememberSaveable { mutableStateOf("") }
    var speed by rememberSaveable { mutableFloatStateOf(1f) }
    var sourceLang by rememberSaveable { mutableStateOf(LanguageOption.ENGLISH_US) }
    var recordingPath by rememberSaveable { mutableStateOf<String?>(null) }
    var recordingElapsed by rememberSaveable { mutableLongStateOf(0L) }
    var recordingStartedAt by rememberSaveable { mutableLongStateOf(0L) }
    var sourceTranscript by rememberSaveable { mutableStateOf("") }
    var traineeTranscript by rememberSaveable { mutableStateOf("") }
    var localFeedback by rememberSaveable { mutableStateOf("") }
    var localScore by rememberSaveable { mutableStateOf<Int?>(null) }
    var errorMessage by rememberSaveable { mutableStateOf<String?>(null) }
    var isRecording by remember { mutableStateOf(false) }

    fun resetPracticeForNewSource() {
        recordingElapsed = 0L
        recordingPath = null
        localFeedback = ""
        localScore = null
        errorMessage = null
    }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        uri?.let {
            sourceName = it.lastPathSegment ?: "Local media"
            sourceMedia.load(it)
            mediaUrl = ""
            resetPracticeForNewSource()
        }
    }

    fun loadNetworkSource() {
        sourceMedia.loadUrl(mediaUrl)
            .onSuccess {
                val parsed = Uri.parse(mediaUrl.trim())
                sourceName = parsed.lastPathSegment?.takeIf { it.isNotBlank() } ?: "Online media"
                resetPracticeForNewSource()
            }
            .onFailure {
                errorMessage = it.message ?: "Could not load that media URL."
            }
    }

    fun beginShadowing() {
        if (sourceName == null) {
            errorMessage = "Choose a local source or load a media URL first."
            return
        }
        if (isRecording) return

        runCatching {
            recordingMedia.pause()
            val file = recorder.start()
            recordingPath = file.absolutePath
            recordingStartedAt = System.currentTimeMillis()
            recordingElapsed = 0L
            localFeedback = ""
            localScore = null
            errorMessage = null
            isRecording = true
            sourceMedia.seekTo(0L)
            sourceMedia.play()
        }.onFailure {
            errorMessage = it.message ?: "Could not start microphone recording."
            isRecording = false
        }
    }

    fun finishShadowing() {
        sourceMedia.pause()
        recorder.stop()?.let { recordingPath = it.absolutePath }
        if (recordingStartedAt > 0L) {
            recordingElapsed = System.currentTimeMillis() - recordingStartedAt
        }
        isRecording = false
    }

    fun generateLocalFeedback() {
        val rawSourceDuration = sourceMedia.player.duration
        val expectedSourceDuration = rawSourceDuration
            .takeIf { it > 0L }
            ?.let { (it / speed.coerceAtLeast(0.1f)).toLong() }
        val traineeDuration = recordingElapsed.takeIf { it > 0L }

        val report = LocalInterpreterCoach.analyze(
            mode = PracticeMode.SHADOWING,
            sourceText = sourceTranscript,
            traineeText = traineeTranscript,
            sourceLanguage = sourceLang.tag,
            targetLanguage = sourceLang.tag,
            sourceDurationMillis = expectedSourceDuration,
            traineeDurationMillis = traineeDuration
        )
        localScore = report.overallScore
        localFeedback = report.asPlainText()
        errorMessage = null
    }

    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) beginShadowing() else errorMessage = "Microphone permission is required to record shadowing practice."
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
            if (sourceMedia.player.duration > 0L && sourceMedia.player.currentPosition >= sourceMedia.player.duration - 100L) {
                finishShadowing()
                break
            }
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
            SectionCard {
                Text("Source media", style = MaterialTheme.typography.titleMedium)
                Button(onClick = { picker.launch(arrayOf("audio/*", "video/*")) }, enabled = !isRecording) {
                    Text("Choose from phone")
                }
                Text("or", color = MaterialTheme.colorScheme.onSurfaceVariant)
                OutlinedTextField(
                    value = mediaUrl,
                    onValueChange = { mediaUrl = it; errorMessage = null },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Direct audio / video URL") },
                    placeholder = { Text("https://example.com/video.mp4") },
                    singleLine = true,
                    leadingIcon = { Icon(Icons.Default.Link, contentDescription = null) },
                    enabled = !isRecording
                )
                Button(
                    onClick = { loadNetworkSource() },
                    enabled = mediaUrl.isNotBlank() && !isRecording
                ) { Text("Load URL") }
                Text(
                    "Use a direct playable media or stream link. Ordinary webpage links may not contain a playable stream.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                sourceName?.let { Text("Loaded: $it", style = MaterialTheme.typography.labelLarge) }
            }

            AndroidView(
                factory = { PlayerView(it).apply { player = sourceMedia.player; useController = true } },
                modifier = Modifier.fillMaxWidth().height(210.dp)
            )

            LanguageSelector("Practice language", sourceLang) {
                sourceLang = it
                localFeedback = ""
                localScore = null
            }

            Text("Playback speed", style = MaterialTheme.typography.titleSmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(.75f, 1f, 1.25f).forEach { value ->
                    FilterChip(
                        selected = speed == value,
                        enabled = !isRecording,
                        onClick = {
                            speed = value
                            sourceMedia.setSpeed(value)
                            localFeedback = ""
                            localScore = null
                        },
                        label = { Text("${value}x") }
                    )
                }
            }

            SectionCard {
                Text("Your shadowing recording", style = MaterialTheme.typography.titleMedium)
                Text(
                    "Use headphones when possible so the microphone captures your voice rather than the source audio.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text("Recording time: ${formatDuration(recordingElapsed)}")

                if (!isRecording) {
                    Button(onClick = { requestStart() }, enabled = sourceName != null) {
                        Icon(Icons.Default.Mic, contentDescription = null)
                        Spacer(Modifier.width(6.dp))
                        Text("Start Shadowing + Recorder")
                    }
                } else {
                    Button(onClick = { finishShadowing() }) {
                        Icon(Icons.Default.Stop, contentDescription = null)
                        Spacer(Modifier.width(6.dp))
                        Text("Stop & Save Recording")
                    }
                }

                recordingPath?.let { path ->
                    val file = File(path)
                    if (file.exists() && !isRecording) {
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
                modifier = Modifier.fillMaxWidth().heightIn(min = 110.dp),
                label = { Text("Notes") }
            )

            SectionCard {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(Icons.Default.AutoAwesome, contentDescription = null)
                    Column(Modifier.weight(1f)) {
                        Text("Local Shadowing Coach", style = MaterialTheme.typography.titleMedium)
                        Text(
                            "Fast offline scoring remains available even when the enhanced AI server is unavailable.",
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                OutlinedTextField(
                    value = sourceTranscript,
                    onValueChange = {
                        sourceTranscript = it
                        localFeedback = ""
                        localScore = null
                    },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 110.dp),
                    label = { Text("Source transcript (optional, recommended)") },
                    placeholder = { Text("Paste what the original speaker says") }
                )

                OutlinedTextField(
                    value = traineeTranscript,
                    onValueChange = {
                        traineeTranscript = it
                        localFeedback = ""
                        localScore = null
                    },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 110.dp),
                    label = { Text("Your spoken transcript (optional, recommended)") },
                    placeholder = { Text("Paste the transcript of your shadowing") }
                )

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        enabled = !isRecording && (
                            recordingElapsed > 0L ||
                                sourceTranscript.isNotBlank() ||
                                traineeTranscript.isNotBlank()
                            ),
                        onClick = { generateLocalFeedback() }
                    ) {
                        Icon(Icons.Default.AutoAwesome, contentDescription = null)
                        Spacer(Modifier.width(6.dp))
                        Text("Local Feedback")
                    }
                    OutlinedButton(onClick = onOpenAiCoach) { Text("Full Coach") }
                }

                errorMessage?.let { Text(it, color = MaterialTheme.colorScheme.error) }

                localScore?.let {
                    Text("Local score: $it / 100", style = MaterialTheme.typography.titleMedium)
                    LinearProgressIndicator(progress = { it / 100f }, modifier = Modifier.fillMaxWidth())
                }
                if (localFeedback.isNotBlank()) Text(localFeedback)
            }

            Button(
                enabled = sourceName != null && !isRecording,
                modifier = Modifier.fillMaxWidth(),
                onClick = {
                    val position = sourceMedia.player.currentPosition.coerceAtLeast(0L)
                    sessionViewModel.save(
                        PracticeSessionEntity(
                            practiceMode = PracticeMode.SHADOWING.name,
                            sourceLanguage = sourceLang.tag,
                            targetLanguage = sourceLang.tag,
                            startedAt = System.currentTimeMillis() - position,
                            durationMillis = maxOf(position, recordingElapsed),
                            sourceName = sourceName,
                            transcript = traineeTranscript,
                            notes = notes,
                            segmentDurationSeconds = null,
                            status = "COMPLETED",
                            recordingPath = recordingPath,
                            aiFeedback = localFeedback.ifBlank { null }
                        )
                    )
                }
            ) { Text("Save session") }

            Spacer(Modifier.height(12.dp))
        }
    }
}

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
import androidx.compose.material.icons.filled.Headphones
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
import com.interpretertrainer.app.ai.AiPracticeBridge
import com.interpretertrainer.app.ai.LocalInterpreterCoach
import com.interpretertrainer.app.data.database.PracticeSessionEntity
import com.interpretertrainer.app.media.MediaController
import com.interpretertrainer.app.media.MediaLinkResolver
import com.interpretertrainer.app.media.ShadowingRecorder
import com.interpretertrainer.app.model.LanguageOption
import com.interpretertrainer.app.model.PracticeMode
import com.interpretertrainer.app.util.formatDuration
import com.interpretertrainer.app.viewmodel.SessionViewModel
import kotlinx.coroutines.delay
import java.io.File

@Composable
fun SimultaneousScreen(
    onBack: () -> Unit,
    sessionViewModel: SessionViewModel,
    onOpenAiCoach: () -> Unit
) {
    val context = LocalContext.current
    val sourceMedia = remember { MediaController(context) }
    val recordingMedia = remember { MediaController(context) }
    val recorder = remember { ShadowingRecorder(context.applicationContext) }
    val aiPayload by AiPracticeBridge.payload.collectAsState()

    var sourceName by rememberSaveable { mutableStateOf<String?>(null) }
    var hasNativeMedia by rememberSaveable { mutableStateOf(false) }
    var webSourceUrl by rememberSaveable { mutableStateOf<String?>(null) }
    var mediaUrl by rememberSaveable { mutableStateOf("") }
    var sourceText by rememberSaveable { mutableStateOf("") }
    var interpretationTranscript by rememberSaveable { mutableStateOf("") }
    var notes by rememberSaveable { mutableStateOf("") }
    var speed by rememberSaveable { mutableFloatStateOf(1f) }
    var sourceLang by rememberSaveable { mutableStateOf(LanguageOption.ENGLISH_US) }
    var targetLang by rememberSaveable { mutableStateOf(LanguageOption.ARABIC_MOROCCO) }
    var recordingPath by rememberSaveable { mutableStateOf<String?>(null) }
    var recordingElapsed by rememberSaveable { mutableLongStateOf(0L) }
    var recordingStartedAt by rememberSaveable { mutableLongStateOf(0L) }
    var localFeedback by rememberSaveable { mutableStateOf("") }
    var localScore by rememberSaveable { mutableStateOf<Int?>(null) }
    var errorMessage by rememberSaveable { mutableStateOf<String?>(null) }
    var isRecording by remember { mutableStateOf(false) }

    val hasWebSource = !webSourceUrl.isNullOrBlank()
    val hasSource = hasNativeMedia || hasWebSource || sourceText.isNotBlank()

    fun clearFeedback() {
        localFeedback = ""
        localScore = null
    }

    fun resetPracticeForNewSource() {
        recordingElapsed = 0L
        recordingPath = null
        clearFeedback()
        errorMessage = null
    }

    LaunchedEffect(aiPayload?.id) {
        val payload = aiPayload
        if (payload != null && payload.mode == AiPracticeBridge.MODE_SIMULTANEOUS) {
            sourceMedia.pause()
            sourceMedia.clear()
            sourceText = payload.text
            sourceName = "AI Coach practice text"
            hasNativeMedia = false
            webSourceUrl = null
            mediaUrl = ""
            resetPracticeForNewSource()
            AiPracticeBridge.consume(payload.id)
        }
    }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        uri?.let {
            sourceName = it.lastPathSegment ?: "Local media"
            sourceMedia.load(it)
            hasNativeMedia = true
            webSourceUrl = null
            mediaUrl = ""
            resetPracticeForNewSource()
        }
    }

    fun loadNetworkSource() {
        val resolved = MediaLinkResolver.resolve(mediaUrl).getOrElse {
            errorMessage = it.message ?: "That link is not valid."
            return
        }
        sourceMedia.pause()
        mediaUrl = resolved.normalizedUrl

        if (resolved.usesNativePlayer) {
            sourceMedia.loadUrl(resolved.playbackUrl)
                .onSuccess {
                    sourceName = resolved.displayName
                    hasNativeMedia = true
                    webSourceUrl = null
                    resetPracticeForNewSource()
                }
                .onFailure { errorMessage = it.message ?: "Could not load that media link." }
        } else {
            sourceMedia.clear()
            sourceName = resolved.displayName
            hasNativeMedia = false
            webSourceUrl = resolved.playbackUrl
            resetPracticeForNewSource()
        }
    }

    fun beginInterpretation() {
        if (!hasSource) {
            errorMessage = "Add a video/audio source, webpage or source text first."
            return
        }
        if (isRecording) return

        runCatching {
            recordingMedia.pause()
            recordingPath = recorder.start().absolutePath
            recordingStartedAt = System.currentTimeMillis()
            recordingElapsed = 0L
            clearFeedback()
            errorMessage = null
            isRecording = true
            if (hasNativeMedia) {
                sourceMedia.seekTo(0L)
                sourceMedia.play()
            }
        }.onFailure {
            errorMessage = it.message ?: "Could not start microphone recording."
            isRecording = false
        }
    }

    fun finishInterpretation() {
        if (hasNativeMedia) sourceMedia.pause()
        recorder.stop()?.let { recordingPath = it.absolutePath }
        if (recordingStartedAt > 0L) recordingElapsed = System.currentTimeMillis() - recordingStartedAt
        isRecording = false
    }

    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) beginInterpretation() else errorMessage = "Microphone permission is required."
    }

    fun requestStart() {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            beginInterpretation()
        } else {
            permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    fun generateLocalFeedback() {
        val sourceDuration = if (hasNativeMedia) {
            sourceMedia.player.duration.takeIf { it > 0L }?.let { (it / speed.coerceAtLeast(.1f)).toLong() }
        } else null

        val report = LocalInterpreterCoach.analyze(
            mode = PracticeMode.SIMULTANEOUS_INTERPRETATION,
            sourceText = sourceText,
            traineeText = interpretationTranscript,
            sourceLanguage = sourceLang.tag,
            targetLanguage = targetLang.tag,
            sourceDurationMillis = sourceDuration,
            traineeDurationMillis = recordingElapsed.takeIf { it > 0L }
        )
        localScore = report.overallScore
        localFeedback = report.asPlainText()
        errorMessage = null
    }

    LaunchedEffect(isRecording, hasNativeMedia) {
        while (isRecording) {
            recordingElapsed = System.currentTimeMillis() - recordingStartedAt
            if (
                hasNativeMedia &&
                sourceMedia.player.duration > 0L &&
                sourceMedia.player.currentPosition >= sourceMedia.player.duration - 100L
            ) {
                finishInterpretation()
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

    TrainerScaffold("Simultaneous Interpretation", onBack) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            SectionCard {
                Text("Languages", style = MaterialTheme.typography.titleMedium)
                LanguageSelector("Source language", sourceLang) { sourceLang = it; clearFeedback() }
                LanguageSelector("Target language", targetLang) { targetLang = it; clearFeedback() }
            }

            SectionCard {
                Text("Source workspace", style = MaterialTheme.typography.titleMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { picker.launch(arrayOf("audio/*", "video/*")) }, enabled = !isRecording) {
                        Text("Choose media")
                    }
                    OutlinedButton(onClick = onOpenAiCoach, enabled = !isRecording) {
                        Icon(Icons.Default.AutoAwesome, null)
                        Text(" AI Coach")
                    }
                }

                OutlinedTextField(
                    value = mediaUrl,
                    onValueChange = { mediaUrl = it; errorMessage = null },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Media or webpage link") },
                    placeholder = { Text("youtube.com/watch?v=… or https://…/video.mp4") },
                    leadingIcon = { Icon(Icons.Default.Link, null) },
                    enabled = !isRecording,
                    singleLine = true
                )
                OutlinedButton(onClick = { loadNetworkSource() }, enabled = !isRecording && mediaUrl.isNotBlank()) {
                    Text("Load link")
                }

                OutlinedTextField(
                    value = sourceText,
                    onValueChange = { sourceText = it; clearFeedback() },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 170.dp),
                    label = { Text("Source transcript / AI text") },
                    placeholder = { Text("Paste source text or use “Use in Simultaneous” in AI Coach") },
                    enabled = !isRecording
                )

                Text("Playback speed", style = MaterialTheme.typography.labelLarge)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(.75f, 1f, 1.25f).forEach { value ->
                        FilterChip(
                            selected = speed == value,
                            enabled = !isRecording,
                            onClick = {
                                speed = value
                                if (hasNativeMedia) sourceMedia.setSpeed(value)
                            },
                            label = { Text("${value}x") }
                        )
                    }
                }
                errorMessage?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }

            when {
                hasNativeMedia -> AndroidView(
                    factory = { PlayerView(it).apply { player = sourceMedia.player; useController = true } },
                    modifier = Modifier.fillMaxWidth().height(220.dp)
                )
                hasWebSource -> EmbeddedWebSource(
                    url = webSourceUrl!!,
                    modifier = Modifier.fillMaxWidth().height(240.dp)
                )
            }

            SectionCard {
                Text("Interpretation booth", style = MaterialTheme.typography.titleMedium)
                Text(
                    if (isRecording) "Recording • ${formatDuration(recordingElapsed)}" else "Use headphones and start when the source is ready.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Button(
                    onClick = { if (isRecording) finishInterpretation() else requestStart() },
                    enabled = hasSource,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(if (isRecording) Icons.Default.Stop else Icons.Default.Mic, null)
                    Text(if (isRecording) " Stop interpreting" else " Start interpreting")
                }

                recordingPath?.let { path ->
                    val file = File(path)
                    if (file.exists() && !isRecording) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(onClick = {
                                recordingMedia.load(Uri.fromFile(file))
                                recordingMedia.play()
                            }) {
                                Icon(Icons.Default.PlayArrow, null)
                                Text(" Replay")
                            }
                            OutlinedButton(onClick = { recordingMedia.pause() }) { Text("Stop replay") }
                        }
                    }
                }
            }

            PracticeTranscriptionPanel(
                language = targetLang,
                onLanguageChange = { targetLang = it; clearFeedback() },
                transcript = interpretationTranscript,
                onTranscriptChange = { interpretationTranscript = it; clearFeedback() },
                enabled = !isRecording,
                title = "Simultaneous interpretation transcription"
            )

            SectionCard {
                Text("Review", style = MaterialTheme.typography.titleMedium)
                OutlinedTextField(
                    value = notes,
                    onValueChange = { notes = it },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 90.dp),
                    label = { Text("Practice notes") }
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = { generateLocalFeedback() },
                        enabled = !isRecording && (sourceText.isNotBlank() || interpretationTranscript.isNotBlank() || recordingElapsed > 0L),
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(Icons.Default.AutoAwesome, null)
                        Text(" Quick feedback")
                    }
                    OutlinedButton(onClick = onOpenAiCoach, modifier = Modifier.weight(1f)) {
                        Text("AI Coach")
                    }
                }
                localScore?.let {
                    Text("Observable score: $it / 100", style = MaterialTheme.typography.titleSmall)
                    LinearProgressIndicator(progress = { it / 100f }, modifier = Modifier.fillMaxWidth())
                }
                if (localFeedback.isNotBlank()) {
                    Text(localFeedback, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }

            Button(
                enabled = hasSource && !isRecording,
                modifier = Modifier.fillMaxWidth(),
                onClick = {
                    val mediaPosition = if (hasNativeMedia) sourceMedia.player.currentPosition.coerceAtLeast(0L) else 0L
                    val duration = maxOf(mediaPosition, recordingElapsed)
                    sessionViewModel.save(
                        PracticeSessionEntity(
                            practiceMode = PracticeMode.SIMULTANEOUS_INTERPRETATION.name,
                            sourceLanguage = sourceLang.tag,
                            targetLanguage = targetLang.tag,
                            startedAt = System.currentTimeMillis() - duration,
                            durationMillis = duration,
                            sourceName = sourceName ?: if (sourceText.isNotBlank()) "Source text" else null,
                            transcript = interpretationTranscript,
                            notes = notes,
                            segmentDurationSeconds = null,
                            status = "COMPLETED",
                            recordingPath = recordingPath,
                            aiFeedback = localFeedback.ifBlank { null }
                        )
                    )
                }
            ) { Text("Save simultaneous session") }

            Spacer(Modifier.height(12.dp))
        }
    }
}

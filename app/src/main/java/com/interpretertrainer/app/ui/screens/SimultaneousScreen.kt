package com.interpretertrainer.app.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Headphones
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
    var speed by rememberSaveable { mutableFloatStateOf(1f) }
    var sourceLang by rememberSaveable { mutableStateOf(LanguageOption.ENGLISH_US) }
    var targetLang by rememberSaveable { mutableStateOf(LanguageOption.ARABIC_MOROCCO) }
    var recordingPath by rememberSaveable { mutableStateOf<String?>(null) }
    var recordingElapsed by rememberSaveable { mutableLongStateOf(0L) }
    var recordingStartedAt by rememberSaveable { mutableLongStateOf(0L) }
    var errorMessage by rememberSaveable { mutableStateOf<String?>(null) }
    var isRecording by remember { mutableStateOf(false) }

    val hasWebSource = !webSourceUrl.isNullOrBlank()
    val hasSource = hasNativeMedia || hasWebSource || sourceText.isNotBlank()

    fun resetPracticeForNewSource() {
        recordingElapsed = 0L
        recordingPath = null
        interpretationTranscript = ""
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
            sourceText = ""
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
                    sourceText = ""
                    resetPracticeForNewSource()
                }
                .onFailure { errorMessage = it.message ?: "Could not load that media link." }
        } else {
            sourceMedia.clear()
            sourceName = resolved.displayName
            hasNativeMedia = false
            webSourceUrl = resolved.playbackUrl
            sourceText = ""
            resetPracticeForNewSource()
        }
    }

    fun beginInterpretation() {
        if (!hasSource) {
            errorMessage = "Add a source first."
            return
        }
        if (isRecording) return
        runCatching {
            recordingMedia.pause()
            recordingPath = recorder.start().absolutePath
            recordingStartedAt = System.currentTimeMillis()
            recordingElapsed = 0L
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

    TrainerScaffold("Simultaneous", onBack) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 14.dp, vertical = 10.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            PracticeTopStrip(
                sourceLanguage = sourceLang,
                targetLanguage = targetLang,
                onSourceLanguageChange = { sourceLang = it },
                onTargetLanguageChange = { targetLang = it }
            )

            SectionCard {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column {
                        Text("Source", style = MaterialTheme.typography.titleMedium)
                        Text(
                            sourceName ?: "Media, link, text or AI material",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    AssistChip(
                        onClick = {},
                        label = { Text("${speed}×") }
                    )
                }

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ModernActionButton(
                        text = "Media",
                        icon = Icons.Default.Headphones,
                        onClick = { picker.launch(arrayOf("audio/*", "video/*")) },
                        enabled = !isRecording,
                        modifier = Modifier.weight(1f)
                    )
                    ModernActionButton(
                        text = "AI",
                        icon = Icons.Default.AutoAwesome,
                        onClick = onOpenAiCoach,
                        enabled = !isRecording,
                        modifier = Modifier.weight(1f)
                    )
                }

                CompactMediaLinkField(
                    value = mediaUrl,
                    onValueChange = { mediaUrl = it; errorMessage = null },
                    onLoad = { loadNetworkSource() },
                    enabled = !isRecording
                )

                OutlinedTextField(
                    value = sourceText,
                    onValueChange = {
                        sourceText = it
                        if (it.isNotBlank()) {
                            hasNativeMedia = false
                            webSourceUrl = null
                            sourceName = "Source text"
                        }
                    },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 150.dp),
                    placeholder = { Text("Paste a speech or send material from Interpreter AI") },
                    enabled = !isRecording,
                    shape = RoundedCornerShape(18.dp)
                )

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(.75f, 1f, 1.25f).forEach { value ->
                        FilterChip(
                            selected = speed == value,
                            enabled = !isRecording,
                            onClick = {
                                speed = value
                                if (hasNativeMedia) sourceMedia.setSpeed(value)
                            },
                            label = { Text("${value}×") }
                        )
                    }
                }
                errorMessage?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }

            when {
                hasNativeMedia -> AndroidView(
                    factory = { PlayerView(it).apply { player = sourceMedia.player; useController = true } },
                    modifier = Modifier.fillMaxWidth().height(215.dp)
                )
                hasWebSource -> EmbeddedWebSource(
                    url = webSourceUrl!!,
                    modifier = Modifier.fillMaxWidth().height(230.dp)
                )
            }

            SectionCard {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("Interpret", style = MaterialTheme.typography.titleMedium)
                    AssistChip(
                        onClick = {},
                        label = { Text(if (isRecording) formatDuration(recordingElapsed) else "Ready") },
                        leadingIcon = {
                            Icon(
                                if (isRecording) Icons.Default.Mic else Icons.Default.Headphones,
                                contentDescription = null,
                                modifier = Modifier.size(17.dp)
                            )
                        }
                    )
                }

                Button(
                    onClick = { if (isRecording) finishInterpretation() else requestStart() },
                    enabled = hasSource,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(18.dp)
                ) {
                    Icon(if (isRecording) Icons.Default.Stop else Icons.Default.Mic, null)
                    Text(if (isRecording) " Stop interpretation" else " Start interpretation")
                }

                recordingPath?.let { path ->
                    val file = File(path)
                    if (file.exists() && !isRecording) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            FilledTonalButton(
                                onClick = {
                                    recordingMedia.load(Uri.fromFile(file))
                                    recordingMedia.play()
                                },
                                shape = RoundedCornerShape(16.dp)
                            ) {
                                Icon(Icons.Default.PlayArrow, null)
                                Text(" Replay")
                            }
                            TextButton(onClick = { recordingMedia.pause() }) { Text("Stop replay") }
                        }
                    }
                }
            }

            PracticeTranscriptionPanel(
                language = targetLang,
                transcript = interpretationTranscript,
                onTranscriptChange = { interpretationTranscript = it },
                enabled = !isRecording,
                title = "Live target transcription"
            )

            Button(
                enabled = hasSource && !isRecording,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(18.dp),
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
                            notes = "",
                            segmentDurationSeconds = null,
                            status = "COMPLETED",
                            recordingPath = recordingPath,
                            aiFeedback = null
                        )
                    )
                }
            ) { Text("Save session") }

            Spacer(Modifier.height(8.dp))
        }
    }
}

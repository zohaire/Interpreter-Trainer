package com.interpretertrainer.app.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import android.speech.tts.TextToSpeech
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
import com.interpretertrainer.app.speech.NaturalAndroidVoice
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
    val aiPayload by AiPracticeBridge.payload.collectAsState()

    var language by rememberSaveable { mutableStateOf(LanguageOption.ENGLISH_US) }
    var sourceName by rememberSaveable { mutableStateOf<String?>(null) }
    var sourceText by rememberSaveable { mutableStateOf("") }
    var mediaUrl by rememberSaveable { mutableStateOf("") }
    var webSourceUrl by rememberSaveable { mutableStateOf<String?>(null) }
    var hasNativeMedia by rememberSaveable { mutableStateOf(false) }
    var speed by rememberSaveable { mutableFloatStateOf(1f) }
    var transcript by rememberSaveable { mutableStateOf("") }
    var recordingPath by rememberSaveable { mutableStateOf<String?>(null) }
    var recordingStartedAt by rememberSaveable { mutableLongStateOf(0L) }
    var recordingElapsed by rememberSaveable { mutableLongStateOf(0L) }
    var isRecording by remember { mutableStateOf(false) }
    var errorMessage by rememberSaveable { mutableStateOf<String?>(null) }

    var ttsReady by remember { mutableStateOf(false) }
    val tts = remember {
        TextToSpeech(context.applicationContext) { status ->
            ttsReady = status == TextToSpeech.SUCCESS
        }
    }

    val hasWebSource = !webSourceUrl.isNullOrBlank()
    val hasSource = hasNativeMedia || hasWebSource || sourceText.isNotBlank()

    fun applyLanguage(option: LanguageOption) {
        language = option
        tts.stop()
        if (ttsReady) NaturalAndroidVoice.configure(tts, option, speed)
    }

    fun speakSourceText() {
        if (!ttsReady || sourceText.isBlank()) return
        val configured = NaturalAndroidVoice.configure(tts, language, speed)
        if (!configured) {
            errorMessage = "A voice for ${language.label} is not installed on this device."
            return
        }
        errorMessage = null
        tts.speak(sourceText, TextToSpeech.QUEUE_FLUSH, null, "shadow-source")
    }

    fun stopSource() {
        sourceMedia.pause()
        tts.stop()
    }

    LaunchedEffect(aiPayload?.id) {
        val payload = aiPayload
        if (payload != null && payload.mode == AiPracticeBridge.MODE_SHADOWING) {
            stopSource()
            sourceMedia.clear()
            sourceText = payload.text
            sourceName = "AI Coach shadowing text"
            hasNativeMedia = false
            webSourceUrl = null
            mediaUrl = ""
            transcript = ""
            errorMessage = null
            AiPracticeBridge.consume(payload.id)
        }
    }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        uri?.let {
            stopSource()
            sourceMedia.load(it)
            sourceName = it.lastPathSegment ?: "Local media"
            hasNativeMedia = true
            sourceText = ""
            webSourceUrl = null
            mediaUrl = ""
            transcript = ""
            errorMessage = null
        }
    }

    fun loadNetworkSource() {
        val resolved = MediaLinkResolver.resolve(mediaUrl).getOrElse {
            errorMessage = it.message ?: "That link is not valid."
            return
        }
        stopSource()
        mediaUrl = resolved.normalizedUrl
        sourceText = ""
        if (resolved.usesNativePlayer) {
            sourceMedia.loadUrl(resolved.playbackUrl)
                .onSuccess {
                    sourceName = resolved.displayName
                    hasNativeMedia = true
                    webSourceUrl = null
                    transcript = ""
                    errorMessage = null
                }
                .onFailure { errorMessage = it.message ?: "Could not load that media link." }
        } else {
            sourceMedia.clear()
            sourceName = resolved.displayName
            hasNativeMedia = false
            webSourceUrl = resolved.playbackUrl
            transcript = ""
            errorMessage = null
        }
    }

    fun startRecording() {
        if (isRecording) return
        runCatching {
            recordingPath = recorder.start().absolutePath
            recordingStartedAt = System.currentTimeMillis()
            recordingElapsed = 0L
            isRecording = true
            errorMessage = null
            when {
                hasNativeMedia -> {
                    sourceMedia.seekTo(0L)
                    sourceMedia.play()
                }
                sourceText.isNotBlank() -> speakSourceText()
            }
        }.onFailure {
            isRecording = false
            errorMessage = it.message ?: "Could not start recording."
        }
    }

    fun stopRecording() {
        stopSource()
        recorder.stop()?.let { recordingPath = it.absolutePath }
        if (recordingStartedAt > 0L) recordingElapsed = System.currentTimeMillis() - recordingStartedAt
        isRecording = false
    }

    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) startRecording() else errorMessage = "Microphone permission is required."
    }

    fun requestRecording() {
        if (!hasSource) {
            errorMessage = "Add audio, video, a link or source text first."
            return
        }
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            startRecording()
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
            tts.stop()
            tts.shutdown()
        }
    }

    TrainerScaffold("Shadowing", onBack) { padding ->
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
                onTargetLanguageChange = ::applyLanguage
            )

            SectionCard {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Column {
                        Text("Shadowing source", style = MaterialTheme.typography.titleMedium)
                        Text(
                            sourceName ?: "Choose media, paste a link or use AI text",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    AssistChip(onClick = {}, label = { Text("${speed}×") })
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
                            stopSource()
                            sourceMedia.clear()
                            hasNativeMedia = false
                            webSourceUrl = null
                            sourceName = "Source text"
                        }
                    },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 140.dp),
                    placeholder = { Text("Paste text or send a passage from Interpreter AI") },
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
                                if (ttsReady) NaturalAndroidVoice.configure(tts, language, value)
                            },
                            label = { Text("${value}×") }
                        )
                    }
                }

                if (sourceText.isNotBlank()) {
                    FilledTonalButton(
                        onClick = { if (tts.isSpeaking) tts.stop() else speakSourceText() },
                        enabled = ttsReady && !isRecording,
                        shape = RoundedCornerShape(16.dp)
                    ) {
                        Icon(Icons.Default.PlayArrow, null)
                        Text(if (tts.isSpeaking) " Stop voice" else " Play source voice")
                    }
                }
                errorMessage?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }

            when {
                hasNativeMedia -> AndroidView(
                    factory = { PlayerView(it).apply { player = sourceMedia.player; useController = true } },
                    modifier = Modifier.fillMaxWidth().height(205.dp)
                )
                hasWebSource -> EmbeddedWebSource(
                    url = webSourceUrl!!,
                    modifier = Modifier.fillMaxWidth().height(225.dp)
                )
            }

            SectionCard {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("Shadow", style = MaterialTheme.typography.titleMedium)
                    AssistChip(
                        onClick = {},
                        label = { Text(if (isRecording) "${recordingElapsed / 1000}s" else "Ready") },
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
                    onClick = { if (isRecording) stopRecording() else requestRecording() },
                    enabled = hasSource,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(18.dp)
                ) {
                    Icon(if (isRecording) Icons.Default.Stop else Icons.Default.Mic, null)
                    Text(if (isRecording) " Stop shadowing" else " Start shadowing")
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
                language = language,
                transcript = transcript,
                onTranscriptChange = { transcript = it },
                enabled = !isRecording,
                title = "Shadowing transcription"
            )

            Button(
                enabled = hasSource && !isRecording,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(18.dp),
                onClick = {
                    val duration = maxOf(recordingElapsed, if (hasNativeMedia) sourceMedia.player.currentPosition.coerceAtLeast(0L) else 0L)
                    sessionViewModel.save(
                        PracticeSessionEntity(
                            practiceMode = PracticeMode.SHADOWING.name,
                            sourceLanguage = language.tag,
                            targetLanguage = language.tag,
                            startedAt = System.currentTimeMillis() - duration,
                            durationMillis = duration,
                            sourceName = sourceName ?: if (sourceText.isNotBlank()) "Shadowing source text" else null,
                            transcript = transcript,
                            notes = "",
                            segmentDurationSeconds = null,
                            status = "COMPLETED",
                            recordingPath = recordingPath
                        )
                    )
                }
            ) { Text("Save session") }

            Spacer(Modifier.height(8.dp))
        }
    }
}

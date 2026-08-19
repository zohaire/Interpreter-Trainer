package com.interpretertrainer.app.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import android.speech.tts.TextToSpeech
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
import com.interpretertrainer.app.data.database.PracticeSessionEntity
import com.interpretertrainer.app.media.MediaController
import com.interpretertrainer.app.media.MediaLinkResolver
import com.interpretertrainer.app.media.ShadowingRecorder
import com.interpretertrainer.app.model.LanguageOption
import com.interpretertrainer.app.model.PracticeMode
import com.interpretertrainer.app.viewmodel.SessionViewModel
import kotlinx.coroutines.delay
import java.io.File
import java.util.Locale

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
    var notes by rememberSaveable { mutableStateOf("") }
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

    fun localeFor(option: LanguageOption): Locale = when (option) {
        LanguageOption.ARABIC_MOROCCO -> Locale("ar", "MA")
        LanguageOption.FRENCH_FRANCE -> Locale.FRANCE
        LanguageOption.ENGLISH_US -> Locale.US
    }

    fun speakSourceText() {
        if (!ttsReady || sourceText.isBlank()) return
        tts.language = localeFor(language)
        tts.setSpeechRate(speed.coerceIn(.75f, 1.25f))
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
            webSourceUrl = null
            mediaUrl = ""
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
        if (resolved.usesNativePlayer) {
            sourceMedia.loadUrl(resolved.playbackUrl)
                .onSuccess {
                    sourceName = resolved.displayName
                    hasNativeMedia = true
                    webSourceUrl = null
                    errorMessage = null
                }
                .onFailure { errorMessage = it.message ?: "Could not load that media link." }
        } else {
            sourceMedia.clear()
            sourceName = resolved.displayName
            hasNativeMedia = false
            webSourceUrl = resolved.playbackUrl
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
            errorMessage = "Add audio/video or AI/source text first."
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
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            SectionCard {
                Text("Shadowing language", style = MaterialTheme.typography.titleMedium)
                Text(
                    "Shadow in Arabic, English or French. Source playback, AI text-to-speech and transcription all follow the selected language.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                LanguageSelector("Language", language) {
                    language = it
                    tts.stop()
                }
            }

            SectionCard {
                Text("Source", style = MaterialTheme.typography.titleMedium)
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
                    leadingIcon = { Icon(Icons.Default.Link, null) },
                    enabled = !isRecording,
                    singleLine = true
                )
                OutlinedButton(onClick = { loadNetworkSource() }, enabled = !isRecording && mediaUrl.isNotBlank()) {
                    Text("Load link")
                }
                OutlinedTextField(
                    value = sourceText,
                    onValueChange = { sourceText = it },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 150.dp),
                    label = { Text("Source / AI practice text") },
                    placeholder = { Text("In AI Coach, tap “Use in Shadowing” to put generated material here") },
                    enabled = !isRecording
                )
                if (sourceText.isNotBlank()) {
                    OutlinedButton(onClick = { if (tts.isSpeaking) tts.stop() else speakSourceText() }, enabled = ttsReady && !isRecording) {
                        Icon(Icons.Default.PlayArrow, null)
                        Text(if (tts.isSpeaking) " Stop text voice" else " Play text voice")
                    }
                }
                errorMessage?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }

            when {
                hasNativeMedia -> AndroidView(
                    factory = { PlayerView(it).apply { player = sourceMedia.player; useController = true } },
                    modifier = Modifier.fillMaxWidth().height(210.dp)
                )
                hasWebSource -> EmbeddedWebSource(
                    url = webSourceUrl!!,
                    modifier = Modifier.fillMaxWidth().height(230.dp)
                )
            }

            SectionCard {
                Text("Playback speed", style = MaterialTheme.typography.titleMedium)
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
            }

            SectionCard {
                Text("Shadowing recording", style = MaterialTheme.typography.titleMedium)
                Text(
                    if (isRecording) "Recording… ${recordingElapsed / 1000}s" else "Record your shadowing voice for replay and review.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Button(
                    onClick = { if (isRecording) stopRecording() else requestRecording() },
                    enabled = hasSource,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(if (isRecording) Icons.Default.Stop else Icons.Default.Mic, null)
                    Text(if (isRecording) " Stop recording" else " Start shadowing")
                }
                recordingPath?.let { path ->
                    val file = File(path)
                    if (file.exists() && !isRecording) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(onClick = {
                                recordingMedia.load(Uri.fromFile(file))
                                recordingMedia.play()
                            }) {
                                Icon(Icons.Default.Headphones, null)
                                Text(" Replay")
                            }
                            OutlinedButton(onClick = { recordingMedia.pause() }) { Text("Stop replay") }
                        }
                    }
                }
            }

            PracticeTranscriptionPanel(
                language = language,
                onLanguageChange = { language = it },
                transcript = transcript,
                onTranscriptChange = { transcript = it },
                enabled = !isRecording,
                title = "Shadowing transcription"
            )

            OutlinedTextField(
                value = notes,
                onValueChange = { notes = it },
                modifier = Modifier.fillMaxWidth().heightIn(min = 100.dp),
                label = { Text("Practice notes") }
            )

            Button(
                enabled = hasSource && !isRecording,
                modifier = Modifier.fillMaxWidth(),
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
                            notes = notes,
                            segmentDurationSeconds = null,
                            status = "COMPLETED",
                            recordingPath = recordingPath
                        )
                    )
                }
            ) { Text("Save shadowing session") }

            Spacer(Modifier.height(12.dp))
        }
    }
}

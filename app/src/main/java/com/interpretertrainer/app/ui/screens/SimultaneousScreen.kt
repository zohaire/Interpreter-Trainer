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
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Headphones
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
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

        mediaUrl = resolved.normalizedUrl
        sourceMedia.pause()

        if (resolved.usesNativePlayer) {
            sourceMedia.loadUrl(resolved.playbackUrl)
                .onSuccess {
                    sourceName = resolved.displayName
                    hasNativeMedia = true
                    webSourceUrl = null
                    resetPracticeForNewSource()
                }
                .onFailure {
                    errorMessage = it.message ?: "Could not load that direct media link."
                }
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
            errorMessage = "Add a video/audio source, web link or source text first."
            return
        }
        if (isRecording) return

        runCatching {
            recordingMedia.pause()
            val file = recorder.start()
            recordingPath = file.absolutePath
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

    fun generateLocalFeedback() {
        val sourceDuration = if (hasNativeMedia) {
            sourceMedia.player.duration
                .takeIf { it > 0L }
                ?.let { (it / speed.coerceAtLeast(0.1f)).toLong() }
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

    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) beginInterpretation()
        else errorMessage = "Microphone permission is required to record your interpretation."
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

    TrainerScaffold("Simultaneous Interpretation", onBack) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Column(
                modifier = Modifier.padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                ElevatedCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Surface(
                                shape = MaterialTheme.shapes.large,
                                color = MaterialTheme.colorScheme.primaryContainer,
                                modifier = Modifier.size(46.dp)
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Icon(Icons.Default.Headphones, null, tint = MaterialTheme.colorScheme.primary)
                                }
                            }
                            Column(Modifier.weight(1f)) {
                                Text("Live simultaneous practice", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                                Text(
                                    "Use local media, direct streams, YouTube/Vimeo or ordinary webpage links beside the source text.",
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }

                        BoxWithConstraints(Modifier.fillMaxWidth()) {
                            if (maxWidth >= 620.dp) {
                                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                    Box(Modifier.weight(1f)) { LanguageSelector("Source language", sourceLang) { sourceLang = it; clearFeedback() } }
                                    Box(Modifier.weight(1f)) { LanguageSelector("Target language", targetLang) { targetLang = it; clearFeedback() } }
                                }
                            } else {
                                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                    LanguageSelector("Source language", sourceLang) { sourceLang = it; clearFeedback() }
                                    LanguageSelector("Target language", targetLang) { targetLang = it; clearFeedback() }
                                }
                            }
                        }
                    }
                }

                Text("Source workspace", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)

                BoxWithConstraints(Modifier.fillMaxWidth()) {
                    if (maxWidth >= 760.dp) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(14.dp),
                            verticalAlignment = Alignment.Top
                        ) {
                            SimultaneousMediaPane(
                                modifier = Modifier.weight(1.08f),
                                sourceMedia = sourceMedia,
                                sourceName = sourceName,
                                hasNativeMedia = hasNativeMedia,
                                webSourceUrl = webSourceUrl,
                                mediaUrl = mediaUrl,
                                onMediaUrlChange = { mediaUrl = it; errorMessage = null },
                                onChooseMedia = { picker.launch(arrayOf("audio/*", "video/*")) },
                                onLoadUrl = { loadNetworkSource() },
                                speed = speed,
                                onSpeedChange = { speed = it; sourceMedia.setSpeed(it); clearFeedback() },
                                enabled = !isRecording
                            )
                            SimultaneousTextPane(
                                modifier = Modifier.weight(.92f),
                                sourceText = sourceText,
                                onSourceTextChange = { sourceText = it; clearFeedback(); errorMessage = null },
                                onOpenAiCoach = onOpenAiCoach,
                                enabled = !isRecording
                            )
                        }
                    } else {
                        Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                            SimultaneousMediaPane(
                                modifier = Modifier.fillMaxWidth(),
                                sourceMedia = sourceMedia,
                                sourceName = sourceName,
                                hasNativeMedia = hasNativeMedia,
                                webSourceUrl = webSourceUrl,
                                mediaUrl = mediaUrl,
                                onMediaUrlChange = { mediaUrl = it; errorMessage = null },
                                onChooseMedia = { picker.launch(arrayOf("audio/*", "video/*")) },
                                onLoadUrl = { loadNetworkSource() },
                                speed = speed,
                                onSpeedChange = { speed = it; sourceMedia.setSpeed(it); clearFeedback() },
                                enabled = !isRecording
                            )
                            SimultaneousTextPane(
                                modifier = Modifier.fillMaxWidth(),
                                sourceText = sourceText,
                                onSourceTextChange = { sourceText = it; clearFeedback(); errorMessage = null },
                                onOpenAiCoach = onOpenAiCoach,
                                enabled = !isRecording
                            )
                        }
                    }
                }

                ElevatedCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Icon(
                                if (isRecording) Icons.Default.Mic else Icons.Default.Headphones,
                                contentDescription = null,
                                tint = if (isRecording) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
                            )
                            Column(Modifier.weight(1f)) {
                                Text("Interpretation booth", style = MaterialTheme.typography.titleMedium)
                                Text(
                                    if (isRecording) "Recording your interpretation • ${formatDuration(recordingElapsed)}" else "Ready when you are",
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }

                        if (hasWebSource) {
                            Text(
                                "This is a web-player link. Start or pause the source with the controls in the video pane; recording remains independent so the website cannot interrupt your microphone session.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        } else {
                            Text(
                                "For clean recordings, use headphones so your microphone captures your voice instead of the source audio.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }

                        if (!isRecording) {
                            Button(onClick = { requestStart() }, enabled = hasSource, modifier = Modifier.fillMaxWidth()) {
                                Icon(Icons.Default.Mic, null)
                                Spacer(Modifier.width(8.dp))
                                Text("Start interpreting")
                            }
                        } else {
                            Button(
                                onClick = { finishInterpretation() },
                                modifier = Modifier.fillMaxWidth(),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = MaterialTheme.colorScheme.error,
                                    contentColor = MaterialTheme.colorScheme.onError
                                )
                            ) {
                                Icon(Icons.Default.Stop, null)
                                Spacer(Modifier.width(8.dp))
                                Text("Stop recording")
                            }
                        }

                        recordingPath?.let { path ->
                            val file = File(path)
                            if (file.exists() && !isRecording) {
                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    OutlinedButton(
                                        modifier = Modifier.weight(1f),
                                        onClick = { recordingMedia.load(Uri.fromFile(file)); recordingMedia.play() }
                                    ) {
                                        Icon(Icons.Default.PlayArrow, null)
                                        Spacer(Modifier.width(6.dp))
                                        Text("Replay")
                                    }
                                    OutlinedButton(modifier = Modifier.weight(1f), onClick = { recordingMedia.pause() }) {
                                        Text("Stop playback")
                                    }
                                }
                            }
                        }
                    }
                }

                ElevatedCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Icon(Icons.Default.Description, null)
                            Text("Transcription & review", style = MaterialTheme.typography.titleMedium)
                        }

                        OutlinedTextField(
                            value = interpretationTranscript,
                            onValueChange = { interpretationTranscript = it; clearFeedback() },
                            modifier = Modifier.fillMaxWidth().heightIn(min = 140.dp),
                            label = { Text("Your interpretation transcript") },
                            placeholder = { Text("Paste or type what you interpreted") },
                            enabled = !isRecording
                        )
                        OutlinedTextField(
                            value = notes,
                            onValueChange = { notes = it },
                            modifier = Modifier.fillMaxWidth().heightIn(min = 90.dp),
                            label = { Text("Practice notes") }
                        )

                        Text(
                            "Quick feedback checks measurable details such as timing, numbers, names and output balance. For cross-language meaning and reformulation, use the full AI coach.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )

                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(
                                modifier = Modifier.weight(1f),
                                enabled = !isRecording && (recordingElapsed > 0L || sourceText.isNotBlank() || interpretationTranscript.isNotBlank()),
                                onClick = { generateLocalFeedback() }
                            ) {
                                Icon(Icons.Default.AutoAwesome, null)
                                Spacer(Modifier.width(6.dp))
                                Text("Quick feedback")
                            }
                            OutlinedButton(modifier = Modifier.weight(1f), onClick = onOpenAiCoach) { Text("AI Coach") }
                        }

                        errorMessage?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                        localScore?.let {
                            Text("Observable score: $it / 100", style = MaterialTheme.typography.titleMedium)
                            LinearProgressIndicator(progress = { it / 100f }, modifier = Modifier.fillMaxWidth())
                        }
                        if (localFeedback.isNotBlank()) {
                            Surface(color = MaterialTheme.colorScheme.surfaceVariant, shape = MaterialTheme.shapes.large) {
                                Text(localFeedback, modifier = Modifier.padding(14.dp), style = MaterialTheme.typography.bodyMedium)
                            }
                        }
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
                                sourceName = sourceName ?: "Source text",
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

                Spacer(Modifier.height(16.dp))
            }
        }
    }
}

@Composable
private fun SimultaneousMediaPane(
    modifier: Modifier,
    sourceMedia: MediaController,
    sourceName: String?,
    hasNativeMedia: Boolean,
    webSourceUrl: String?,
    mediaUrl: String,
    onMediaUrlChange: (String) -> Unit,
    onChooseMedia: () -> Unit,
    onLoadUrl: () -> Unit,
    speed: Float,
    onSpeedChange: (Float) -> Unit,
    enabled: Boolean
) {
    val hasWebSource = !webSourceUrl.isNullOrBlank()

    ElevatedCard(modifier = modifier) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(Icons.Default.VideoLibrary, null, tint = MaterialTheme.colorScheme.primary)
                Column(Modifier.weight(1f)) {
                    Text("Video / audio", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "Paste a normal video link, webpage link, direct stream, or choose a file from your phone.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            when {
                hasNativeMedia -> AndroidView(
                    factory = { PlayerView(it).apply { player = sourceMedia.player; useController = true } },
                    modifier = Modifier.fillMaxWidth().aspectRatio(16f / 9f)
                )
                hasWebSource -> EmbeddedWebSource(
                    url = webSourceUrl!!,
                    modifier = Modifier.fillMaxWidth().aspectRatio(16f / 9f)
                )
                else -> Surface(
                    modifier = Modifier.fillMaxWidth().aspectRatio(16f / 9f),
                    shape = MaterialTheme.shapes.large,
                    color = MaterialTheme.colorScheme.surfaceVariant
                ) {
                    Column(
                        modifier = Modifier.padding(20.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Icon(Icons.Default.VideoLibrary, null, modifier = Modifier.size(38.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.height(8.dp))
                        Text("Your source will appear here")
                    }
                }
            }

            sourceName?.let {
                Text(
                    if (hasWebSource) "$it • web player" else it,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary
                )
            }

            FilledTonalButton(onClick = onChooseMedia, enabled = enabled, modifier = Modifier.fillMaxWidth()) {
                Text("Choose media from phone")
            }

            OutlinedTextField(
                value = mediaUrl,
                onValueChange = onMediaUrlChange,
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Media or webpage link") },
                placeholder = { Text("youtube.com/watch?v=… or https://…/video.mp4") },
                singleLine = true,
                leadingIcon = { Icon(Icons.Default.Link, null) },
                enabled = enabled
            )
            OutlinedButton(
                onClick = onLoadUrl,
                enabled = enabled && mediaUrl.isNotBlank(),
                modifier = Modifier.fillMaxWidth()
            ) { Text("Load link") }

            if (hasWebSource) {
                Text(
                    "Use the controls inside the embedded page. Some websites may require sign-in or may block embedded playback; those restrictions come from the website itself.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                Text("Playback speed", style = MaterialTheme.typography.labelLarge)
                Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                    listOf(.75f, 1f, 1.25f).forEach { value ->
                        FilterChip(
                            selected = speed == value,
                            enabled = enabled && hasNativeMedia,
                            onClick = { onSpeedChange(value) },
                            label = { Text("${value}x") }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SimultaneousTextPane(
    modifier: Modifier,
    sourceText: String,
    onSourceTextChange: (String) -> Unit,
    onOpenAiCoach: () -> Unit,
    enabled: Boolean
) {
    ElevatedCard(modifier = modifier) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(Icons.Default.Description, null, tint = MaterialTheme.colorScheme.primary)
                Column(Modifier.weight(1f)) {
                    Text("Source text", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "Keep the transcript, script or AI-generated practice text beside the media.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            OutlinedButton(onClick = onOpenAiCoach, enabled = enabled, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Default.AutoAwesome, null)
                Spacer(Modifier.width(6.dp))
                Text("Generate practice text with AI")
            }
            OutlinedTextField(
                value = sourceText,
                onValueChange = onSourceTextChange,
                modifier = Modifier.fillMaxWidth().heightIn(min = 300.dp),
                label = { Text("Source transcript / text") },
                placeholder = { Text("Paste text here or send an AI Coach answer directly into this mode…") },
                enabled = enabled
            )
            Text(
                "You can practice from text alone. In AI Coach, use “Use in Simultaneous” under a generated answer to insert it here automatically.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

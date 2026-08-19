package com.interpretertrainer.app.ui.screens

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Link
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.ui.PlayerView
import com.interpretertrainer.app.ai.AiPracticeBridge
import com.interpretertrainer.app.data.database.PracticeSessionEntity
import com.interpretertrainer.app.media.MediaController
import com.interpretertrainer.app.media.MediaLinkResolver
import com.interpretertrainer.app.model.LanguageOption
import com.interpretertrainer.app.model.PracticeMode
import com.interpretertrainer.app.util.formatDuration
import com.interpretertrainer.app.viewmodel.SessionViewModel
import kotlinx.coroutines.delay

@Composable
fun ConsecutiveScreen(
    onBack: () -> Unit,
    sessionViewModel: SessionViewModel,
    onOpenAiCoach: () -> Unit
) {
    val context = LocalContext.current
    val media = remember { MediaController(context) }
    val aiPayload by AiPracticeBridge.payload.collectAsState()
    var sourceName by rememberSaveable { mutableStateOf<String?>(null) }
    var webSourceUrl by rememberSaveable { mutableStateOf<String?>(null) }
    var mediaUrl by rememberSaveable { mutableStateOf("") }
    var aiSourceText by rememberSaveable { mutableStateOf("") }
    var sourceError by rememberSaveable { mutableStateOf<String?>(null) }
    var segmentSeconds by rememberSaveable { mutableIntStateOf(30) }
    var segmentIndex by rememberSaveable { mutableIntStateOf(0) }
    var segmentStart by rememberSaveable { mutableLongStateOf(0L) }
    var notes by rememberSaveable { mutableStateOf("") }
    var transcript by rememberSaveable { mutableStateOf("") }
    var sourceLang by rememberSaveable { mutableStateOf(LanguageOption.ENGLISH_US) }
    var targetLang by rememberSaveable { mutableStateOf(LanguageOption.FRENCH_FRANCE) }
    var position by remember { mutableLongStateOf(0L) }

    val isWebSource = !webSourceUrl.isNullOrBlank()
    val hasAiSource = aiSourceText.isNotBlank()
    val hasSource = sourceName != null || hasAiSource
    val hasNativeSource = sourceName != null && !isWebSource && !hasAiSource

    fun resetSegments() {
        media.pause()
        segmentIndex = 0
        segmentStart = 0L
        position = 0L
        if (hasNativeSource) media.seekTo(0L)
        sourceError = null
    }

    LaunchedEffect(aiPayload?.id) {
        val payload = aiPayload
        if (payload != null && payload.mode == AiPracticeBridge.MODE_CONSECUTIVE) {
            media.pause()
            media.clear()
            aiSourceText = payload.text
            sourceName = null
            webSourceUrl = null
            mediaUrl = ""
            segmentIndex = 0
            segmentStart = 0L
            position = 0L
            sourceError = null
            AiPracticeBridge.consume(payload.id)
        }
    }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        uri?.let {
            sourceName = it.lastPathSegment ?: "Local media"
            media.load(it)
            aiSourceText = ""
            webSourceUrl = null
            mediaUrl = ""
            segmentIndex = 0
            segmentStart = 0L
            position = 0L
            sourceError = null
        }
    }

    fun loadNetworkSource() {
        val resolved = MediaLinkResolver.resolve(mediaUrl).getOrElse {
            sourceError = it.message ?: "That link is not valid."
            return
        }

        mediaUrl = resolved.normalizedUrl
        media.pause()
        aiSourceText = ""

        if (resolved.usesNativePlayer) {
            media.loadUrl(resolved.playbackUrl)
                .onSuccess {
                    sourceName = resolved.displayName
                    webSourceUrl = null
                    segmentIndex = 0
                    segmentStart = 0L
                    position = 0L
                    sourceError = null
                }
                .onFailure {
                    sourceError = it.message ?: "Could not load that direct media link."
                }
        } else {
            media.clear()
            sourceName = resolved.displayName
            webSourceUrl = resolved.playbackUrl
            segmentIndex = 0
            segmentStart = 0L
            position = 0L
            sourceError = null
        }
    }

    LaunchedEffect(media.player, segmentSeconds, segmentStart, isWebSource) {
        while (true) {
            if (!isWebSource && !hasAiSource) {
                position = media.player.currentPosition
                val boundary = segmentStart + segmentSeconds * 1000L
                if (media.player.isPlaying && position >= boundary) {
                    media.pause()
                    media.seekTo(boundary)
                    position = boundary
                }
            }
            delay(100)
        }
    }

    DisposableEffect(Unit) { onDispose { media.release() } }

    fun playCurrent() {
        if (!hasNativeSource) return
        segmentStart = segmentIndex * segmentSeconds * 1000L
        media.seekTo(segmentStart)
        media.play()
    }

    fun playNext() {
        if (!hasNativeSource) return
        val nextStart = (segmentIndex + 1) * segmentSeconds * 1000L
        val duration = media.player.duration
        if (duration <= 0 || nextStart < duration) {
            segmentIndex++
            segmentStart = nextStart
            media.seekTo(nextStart)
            media.play()
        }
    }

    TrainerScaffold("Consecutive Interpretation", onBack) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            SectionCard {
                Text("AI practice text", style = MaterialTheme.typography.titleMedium)
                Text(
                    "Ask Interpreter AI for a speech, briefing or passage, then tap “Use in Consecutive” under its answer. The text will appear here automatically.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                OutlinedButton(onClick = onOpenAiCoach) { Text("Open AI Coach") }
                OutlinedTextField(
                    value = aiSourceText,
                    onValueChange = {
                        aiSourceText = it
                        if (it.isNotBlank()) {
                            media.pause()
                            sourceName = null
                            webSourceUrl = null
                        }
                    },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 150.dp),
                    label = { Text("Source practice text") },
                    placeholder = { Text("AI-generated consecutive interpreting material will appear here") }
                )
            }

            SectionCard {
                Text("Source media", style = MaterialTheme.typography.titleMedium)
                Button(onClick = { picker.launch(arrayOf("audio/*", "video/*")) }) {
                    Text("Choose from phone")
                }
                Text("or", color = MaterialTheme.colorScheme.onSurfaceVariant)
                OutlinedTextField(
                    value = mediaUrl,
                    onValueChange = { mediaUrl = it; sourceError = null },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Media or webpage link") },
                    placeholder = { Text("youtube.com/watch?v=… or https://…/audio.mp3") },
                    singleLine = true,
                    leadingIcon = { Icon(Icons.Default.Link, contentDescription = null) }
                )
                Button(onClick = { loadNetworkSource() }, enabled = mediaUrl.isNotBlank()) {
                    Text("Load link")
                }
                Text(
                    "Direct files and HLS/DASH streams use the native player. YouTube, Vimeo and ordinary webpages open inside the app instead of being treated as broken media files.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                sourceError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                sourceName?.let {
                    Text(
                        "Loaded: $it${if (isWebSource) " • web player" else ""}",
                        style = MaterialTheme.typography.labelLarge
                    )
                }
            }

            if (hasAiSource) {
                SectionCard {
                    Text("Text-source practice", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "Use the AI text above as your consecutive source. Media segmentation is paused while a text source is active.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else if (isWebSource) {
                EmbeddedWebSource(
                    url = webSourceUrl!!,
                    modifier = Modifier.fillMaxWidth().height(240.dp)
                )
            } else {
                AndroidView(
                    factory = { PlayerView(it).apply { player = media.player; useController = true } },
                    modifier = Modifier.fillMaxWidth().height(200.dp)
                )
            }

            SectionCard {
                Text("Segment length", style = MaterialTheme.typography.titleMedium)
                if (isWebSource) {
                    Text(
                        "Automatic 15/30/60-second seeking is available for local files and direct media streams. For webpage players, use the website's own playback controls while taking consecutive notes.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else if (hasAiSource) {
                    Text(
                        "Segment controls are for media sources. With an AI text source, work paragraph by paragraph and use the notes field below.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(15, 30, 60).forEach { seconds ->
                        FilterChip(
                            selected = segmentSeconds == seconds,
                            enabled = !isWebSource && !hasAiSource,
                            onClick = {
                                media.pause()
                                segmentSeconds = seconds
                                segmentIndex = 0
                                segmentStart = 0L
                                media.seekTo(0L)
                            },
                            label = { Text("${seconds}s") }
                        )
                    }
                }
                Text(
                    when {
                        hasAiSource -> "AI text-source mode"
                        isWebSource -> "Web-player mode"
                        else -> "Segment ${segmentIndex + 1} • ${formatDuration(segmentStart)} → ${formatDuration(segmentStart + segmentSeconds * 1000L)}"
                    },
                    style = MaterialTheme.typography.titleSmall
                )
                if (!isWebSource && !hasAiSource) {
                    LinearProgressIndicator(
                        progress = {
                            ((position - segmentStart).coerceAtLeast(0L).toFloat() / (segmentSeconds * 1000L))
                                .coerceIn(0f, 1f)
                        },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        enabled = segmentIndex > 0 && hasNativeSource,
                        onClick = {
                            media.pause()
                            segmentIndex--
                            playCurrent()
                        }
                    ) { Text("Previous") }
                    OutlinedButton(enabled = hasNativeSource, onClick = { playCurrent() }) { Text("Replay") }
                    Button(enabled = hasNativeSource, onClick = { playNext() }) { Text("Next") }
                }
            }

            LanguageSelector("Source language", sourceLang) { sourceLang = it }
            LanguageSelector("Target language", targetLang) { targetLang = it }

            OutlinedTextField(
                value = notes,
                onValueChange = { notes = it },
                modifier = Modifier.fillMaxWidth().heightIn(min = 130.dp),
                label = { Text("Interpreter notes") }
            )
            OutlinedTextField(
                value = transcript,
                onValueChange = { transcript = it },
                modifier = Modifier.fillMaxWidth().heightIn(min = 130.dp),
                label = { Text("Interpretation transcript") }
            )

            Button(
                enabled = hasSource,
                modifier = Modifier.fillMaxWidth(),
                onClick = {
                    val duration = if (isWebSource || hasAiSource) 0L else position
                    sessionViewModel.save(
                        PracticeSessionEntity(
                            practiceMode = PracticeMode.CONSECUTIVE.name,
                            sourceLanguage = sourceLang.tag,
                            targetLanguage = targetLang.tag,
                            startedAt = System.currentTimeMillis() - duration,
                            durationMillis = duration,
                            sourceName = sourceName ?: if (hasAiSource) "AI Coach practice text" else null,
                            transcript = transcript,
                            notes = notes,
                            segmentDurationSeconds = if (isWebSource || hasAiSource) null else segmentSeconds,
                            status = "COMPLETED"
                        )
                    )
                }
            ) { Text("Save session") }

            Spacer(Modifier.height(12.dp))
        }
    }
}

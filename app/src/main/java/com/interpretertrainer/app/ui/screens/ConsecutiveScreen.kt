package com.interpretertrainer.app.ui.screens

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
        segmentIndex = 0
        segmentStart = 0L
        position = 0L
        transcript = ""
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
            resetSegments()
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
            resetSegments()
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
                    resetSegments()
                }
                .onFailure { sourceError = it.message ?: "Could not load that media link." }
        } else {
            media.clear()
            sourceName = resolved.displayName
            webSourceUrl = resolved.playbackUrl
            resetSegments()
        }
    }

    LaunchedEffect(media.player, segmentSeconds, segmentStart, isWebSource, hasAiSource) {
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

    TrainerScaffold("Consecutive", onBack) { padding ->
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
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Column {
                        Text("Source", style = MaterialTheme.typography.titleMedium)
                        Text(
                            sourceName ?: if (hasAiSource) "Interpreter AI text" else "Media, link or AI passage",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    if (hasSource) AssistChip(onClick = {}, label = { Text(if (hasAiSource) "AI text" else if (isWebSource) "Web" else "Media") })
                }

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ModernActionButton(
                        text = "Media",
                        icon = Icons.Default.Headphones,
                        onClick = { picker.launch(arrayOf("audio/*", "video/*")) },
                        modifier = Modifier.weight(1f)
                    )
                    ModernActionButton(
                        text = "AI",
                        icon = Icons.Default.AutoAwesome,
                        onClick = onOpenAiCoach,
                        modifier = Modifier.weight(1f)
                    )
                }

                CompactMediaLinkField(
                    value = mediaUrl,
                    onValueChange = { mediaUrl = it; sourceError = null },
                    onLoad = { loadNetworkSource() }
                )

                OutlinedTextField(
                    value = aiSourceText,
                    onValueChange = {
                        aiSourceText = it
                        if (it.isNotBlank()) {
                            media.pause()
                            media.clear()
                            sourceName = null
                            webSourceUrl = null
                        }
                    },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 140.dp),
                    placeholder = { Text("Paste a source or send a passage from Interpreter AI") },
                    shape = RoundedCornerShape(18.dp)
                )
                sourceError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }

            when {
                hasAiSource -> SectionCard {
                    Text("Text practice", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "Work paragraph by paragraph. The segment player stays out of the way while AI text is active.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                isWebSource -> EmbeddedWebSource(
                    url = webSourceUrl!!,
                    modifier = Modifier.fillMaxWidth().height(225.dp)
                )
                else -> AndroidView(
                    factory = { PlayerView(it).apply { player = media.player; useController = true } },
                    modifier = Modifier.fillMaxWidth().height(195.dp)
                )
            }

            SectionCard {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("Segments", style = MaterialTheme.typography.titleMedium)
                    AssistChip(
                        onClick = {},
                        label = {
                            Text(
                                when {
                                    hasAiSource -> "Text"
                                    isWebSource -> "Web"
                                    else -> "${segmentSeconds}s"
                                }
                            )
                        }
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

                if (!isWebSource && !hasAiSource) {
                    Text(
                        "Segment ${segmentIndex + 1} · ${formatDuration(segmentStart)} → ${formatDuration(segmentStart + segmentSeconds * 1000L)}",
                        style = MaterialTheme.typography.labelLarge
                    )
                    LinearProgressIndicator(
                        progress = {
                            ((position - segmentStart).coerceAtLeast(0L).toFloat() / (segmentSeconds * 1000L)).coerceIn(0f, 1f)
                        },
                        modifier = Modifier.fillMaxWidth()
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(
                            enabled = segmentIndex > 0 && hasNativeSource,
                            onClick = {
                                media.pause()
                                segmentIndex--
                                playCurrent()
                            }
                        ) { Text("Previous") }
                        FilledTonalButton(enabled = hasNativeSource, onClick = { playCurrent() }, shape = RoundedCornerShape(16.dp)) { Text("Replay") }
                        Button(enabled = hasNativeSource, onClick = { playNext() }, shape = RoundedCornerShape(16.dp)) { Text("Next") }
                    }
                } else {
                    Text(
                        if (hasAiSource) "Use the source text above for memory and reformulation practice."
                        else "Use the embedded player's controls for webpage media.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            SectionCard {
                Text("Interpreter notes", style = MaterialTheme.typography.titleMedium)
                OutlinedTextField(
                    value = notes,
                    onValueChange = { notes = it },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 120.dp),
                    placeholder = { Text("Symbols, numbers, names, links between ideas…") },
                    shape = RoundedCornerShape(18.dp)
                )
            }

            PracticeTranscriptionPanel(
                language = targetLang,
                transcript = transcript,
                onTranscriptChange = { transcript = it },
                title = "Interpretation transcription"
            )

            Button(
                enabled = hasSource,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(18.dp),
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

            Spacer(Modifier.height(8.dp))
        }
    }
}

package com.interpretertrainer.app.ui.screens

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.media3.common.Player
import com.interpretertrainer.app.data.database.PracticeSessionEntity
import com.interpretertrainer.app.media.MediaController
import com.interpretertrainer.app.model.LanguageOption
import com.interpretertrainer.app.model.PracticeMode
import com.interpretertrainer.app.util.formatDuration
import com.interpretertrainer.app.viewmodel.SessionViewModel
import kotlinx.coroutines.delay

@Composable
fun ConsecutiveScreen(onBack: () -> Unit, sessionViewModel: SessionViewModel) {
    val context = LocalContext.current
    val media = remember { MediaController(context) }
    var sourceName by rememberSaveable { mutableStateOf<String?>(null) }
    var segmentSeconds by rememberSaveable { mutableIntStateOf(30) }
    var segmentIndex by rememberSaveable { mutableIntStateOf(0) }
    var segmentStart by rememberSaveable { mutableLongStateOf(0L) }
    var notes by rememberSaveable { mutableStateOf("") }
    var transcript by rememberSaveable { mutableStateOf("") }
    var sourceLang by rememberSaveable { mutableStateOf(LanguageOption.ENGLISH_US) }
    var targetLang by rememberSaveable { mutableStateOf(LanguageOption.FRENCH_FRANCE) }
    var position by remember { mutableLongStateOf(0L) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? -> uri?.let { sourceName = it.lastPathSegment; media.load(it); segmentIndex = 0; segmentStart = 0 } }

    LaunchedEffect(media.player, segmentSeconds, segmentStart) {
        while (true) {
            position = media.player.currentPosition
            val boundary = segmentStart + segmentSeconds * 1000L
            if (media.player.isPlaying && position >= boundary) {
                media.pause(); media.seekTo(boundary); position = boundary
            }
            delay(100)
        }
    }
    DisposableEffect(Unit) { onDispose { media.release() } }

    fun playCurrent() {
        segmentStart = segmentIndex * segmentSeconds * 1000L
        media.seekTo(segmentStart); media.play()
    }
    fun playNext() {
        val nextStart = (segmentIndex + 1) * segmentSeconds * 1000L
        val duration = media.player.duration
        if (duration <= 0 || nextStart < duration) { segmentIndex++; segmentStart = nextStart; media.seekTo(nextStart); media.play() }
    }

    TrainerScaffold("Consecutive Interpretation", onBack) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(onClick = { picker.launch(arrayOf("audio/*", "video/*")) }) { Text("Choose audio / video") }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { listOf(15,30,60).forEach { seconds -> FilterChip(selected = segmentSeconds == seconds, onClick = { media.pause(); segmentSeconds = seconds; segmentIndex = 0; segmentStart = 0; media.seekTo(0) }, label = { Text("${seconds}s") }) } }
            Text("Segment ${segmentIndex + 1} • ${formatDuration(segmentStart)} → ${formatDuration(segmentStart + segmentSeconds * 1000L)}")
            LinearProgressIndicator(progress = { if (segmentSeconds == 0) 0f else ((position - segmentStart).coerceAtLeast(0L).toFloat() / (segmentSeconds * 1000L)).coerceIn(0f,1f) }, modifier = Modifier.fillMaxWidth())
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(enabled = segmentIndex > 0, onClick = { media.pause(); segmentIndex--; playCurrent() }) { Text("Previous") }
                OutlinedButton(onClick = { playCurrent() }) { Text("Replay") }
                Button(onClick = { playNext() }) { Text("Play Next Segment") }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(Modifier.weight(1f)) { LanguageSelector("Source", sourceLang) { sourceLang = it } }
                Box(Modifier.weight(1f)) { LanguageSelector("Target", targetLang) { targetLang = it } }
            }
            OutlinedTextField(value = notes, onValueChange = { notes = it }, modifier = Modifier.fillMaxWidth().weight(1f), label = { Text("Interpreter notes") })
            OutlinedTextField(value = transcript, onValueChange = { transcript = it }, modifier = Modifier.fillMaxWidth().weight(1f), label = { Text("Interpretation transcript") })
            Button(enabled = sourceName != null, onClick = {
                sessionViewModel.save(PracticeSessionEntity(practiceMode = PracticeMode.CONSECUTIVE.name, sourceLanguage = sourceLang.tag, targetLanguage = targetLang.tag, startedAt = System.currentTimeMillis() - position, durationMillis = position, sourceName = sourceName, transcript = transcript, notes = notes, segmentDurationSeconds = segmentSeconds, status = "COMPLETED"))
            }) { Text("Save session") }
        }
    }
}

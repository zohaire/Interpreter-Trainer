package com.interpretertrainer.app.ui.screens

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.media3.common.Player
import androidx.media3.ui.PlayerView
import androidx.compose.ui.viewinterop.AndroidView
import com.interpretertrainer.app.data.database.PracticeSessionEntity
import com.interpretertrainer.app.media.MediaController
import com.interpretertrainer.app.model.LanguageOption
import com.interpretertrainer.app.model.PracticeMode
import com.interpretertrainer.app.viewmodel.SessionViewModel

@Composable
fun ShadowingScreen(onBack: () -> Unit, sessionViewModel: SessionViewModel) {
    val context = LocalContext.current
    val media = remember { MediaController(context) }
    var sourceName by rememberSaveable { mutableStateOf<String?>(null) }
    var notes by rememberSaveable { mutableStateOf("") }
    var speed by rememberSaveable { mutableFloatStateOf(1f) }
    var sourceLang by rememberSaveable { mutableStateOf(LanguageOption.ENGLISH_US) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? -> uri?.let { sourceName = it.lastPathSegment; media.load(it) } }
    DisposableEffect(Unit) { onDispose { media.release() } }

    TrainerScaffold("Shadowing", onBack) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(onClick = { picker.launch(arrayOf("audio/*", "video/*")) }) { Text("Choose audio / video") }
            AndroidView(factory = { PlayerView(it).apply { player = media.player; useController = true } }, modifier = Modifier.fillMaxWidth().height(220.dp))
            LanguageSelector("Practice language", sourceLang) { sourceLang = it }
            Text("Playback speed")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(.75f, 1f, 1.25f).forEach { value -> FilterChip(selected = speed == value, onClick = { speed = value; media.setSpeed(value) }, label = { Text("${value}x") }) }
            }
            OutlinedTextField(value = notes, onValueChange = { notes = it }, modifier = Modifier.fillMaxWidth().weight(1f), label = { Text("Notes") })
            Button(enabled = sourceName != null, onClick = {
                sessionViewModel.save(PracticeSessionEntity(practiceMode = PracticeMode.SHADOWING.name, sourceLanguage = sourceLang.tag, targetLanguage = sourceLang.tag, startedAt = System.currentTimeMillis() - media.player.currentPosition, durationMillis = media.player.currentPosition, sourceName = sourceName, transcript = "", notes = notes, segmentDurationSeconds = null, status = "COMPLETED"))
            }) { Text("Save session") }
        }
    }
}

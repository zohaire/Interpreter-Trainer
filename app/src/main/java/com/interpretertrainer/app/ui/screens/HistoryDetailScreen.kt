package com.interpretertrainer.app.ui.screens

import android.net.Uri
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.interpretertrainer.app.media.MediaController
import com.interpretertrainer.app.util.formatDuration
import com.interpretertrainer.app.viewmodel.SessionViewModel
import java.io.File

@Composable
fun HistoryDetailScreen(id: Long, onBack: () -> Unit, sessionViewModel: SessionViewModel) {
    val session by sessionViewModel.sessions.collectAsState()
    val item = session.firstOrNull { it.id == id }
    val context = LocalContext.current
    val recordingMedia = remember { MediaController(context) }

    DisposableEffect(Unit) {
        onDispose { recordingMedia.release() }
    }

    TrainerScaffold("Session Review", onBack) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            if (item == null) {
                Text("Session not found.")
            } else {
                Text(item.practiceMode.replace('_', ' '), style = MaterialTheme.typography.headlineSmall)
                Text("Languages: ${item.sourceLanguage} → ${item.targetLanguage}")
                Text("Duration: ${formatDuration(item.durationMillis)}")
                item.sourceName?.let { Text("Source: $it") }
                item.segmentDurationSeconds?.let { Text("Segment length: ${it}s") }

                item.recordingPath?.let { path ->
                    val file = File(path)
                    SectionCard {
                        Text("Practice recording", style = MaterialTheme.typography.titleMedium)
                        Spacer(Modifier.height(8.dp))
                        if (file.exists()) {
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Button(onClick = {
                                    recordingMedia.load(Uri.fromFile(file))
                                    recordingMedia.play()
                                }) { Text("Play recording") }
                                OutlinedButton(onClick = { recordingMedia.pause() }) { Text("Stop") }
                            }
                        } else {
                            Text("The recording file is no longer available on this device.")
                        }
                    }
                }

                SectionCard {
                    Text("Transcript", style = MaterialTheme.typography.titleMedium)
                    Text(item.transcript.ifBlank { "No transcript saved." })
                }
                SectionCard {
                    Text("Notes", style = MaterialTheme.typography.titleMedium)
                    Text(item.notes.ifBlank { "No notes saved." })
                }
                item.aiFeedback?.takeIf { it.isNotBlank() }?.let { feedback ->
                    SectionCard {
                        Text("AI Coach feedback", style = MaterialTheme.typography.titleMedium)
                        Spacer(Modifier.height(8.dp))
                        Text(feedback)
                    }
                }
                OutlinedButton(onClick = {
                    recordingMedia.pause()
                    sessionViewModel.delete(id)
                    onBack()
                }) { Text("Delete session") }
            }
        }
    }
}

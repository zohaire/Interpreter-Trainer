package com.interpretertrainer.app.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.interpretertrainer.app.util.formatDuration
import com.interpretertrainer.app.viewmodel.SessionViewModel

@Composable
fun HistoryDetailScreen(id: Long, onBack: () -> Unit, sessionViewModel: SessionViewModel) {
    val session by sessionViewModel.sessions.collectAsState()
    val item = session.firstOrNull { it.id == id }
    TrainerScaffold("Session Review", onBack) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).padding(16.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (item == null) Text("Session not found.") else {
                Text(item.practiceMode.replace('_',' '), style = MaterialTheme.typography.headlineSmall)
                Text("Languages: ${item.sourceLanguage} → ${item.targetLanguage}")
                Text("Duration: ${formatDuration(item.durationMillis)}")
                item.sourceName?.let { Text("Source: $it") }
                item.segmentDurationSeconds?.let { Text("Segment length: ${it}s") }
                SectionCard { Text("Transcript", style = MaterialTheme.typography.titleMedium); Text(item.transcript.ifBlank { "No transcript saved." }) }
                SectionCard { Text("Notes", style = MaterialTheme.typography.titleMedium); Text(item.notes.ifBlank { "No notes saved." }) }
                OutlinedButton(onClick = { sessionViewModel.delete(id); onBack() }) { Text("Delete session") }
            }
        }
    }
}

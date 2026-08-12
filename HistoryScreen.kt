package com.interpretertrainer.app.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.interpretertrainer.app.util.formatDuration
import com.interpretertrainer.app.viewmodel.SessionViewModel
import java.text.DateFormat
import java.util.Date

@Composable
fun HistoryScreen(onBack: () -> Unit, sessionViewModel: SessionViewModel, onOpen: (Long) -> Unit) {
    val sessions by sessionViewModel.sessions.collectAsState()
    TrainerScaffold("Practice History", onBack) { padding ->
        if (sessions.isEmpty()) Box(Modifier.fillMaxSize().padding(padding).padding(24.dp)) { Text("No saved sessions yet.") }
        else LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            items(sessions, key = { it.id }) { session ->
                Card(Modifier.fillMaxWidth().clickable { onOpen(session.id) }) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(session.practiceMode.replace('_',' '), style = MaterialTheme.typography.titleMedium)
                        Text("${session.sourceLanguage} → ${session.targetLanguage}")
                        Text("${DateFormat.getDateTimeInstance().format(Date(session.startedAt))} • ${formatDuration(session.durationMillis)}", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

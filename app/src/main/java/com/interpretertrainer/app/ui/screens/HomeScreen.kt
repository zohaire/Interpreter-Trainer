package com.interpretertrainer.app.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.interpretertrainer.app.ui.Routes

@Composable
fun HomeScreen(onNavigate: (String) -> Unit) {
    Scaffold { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding).padding(horizontal = 20.dp),
            contentPadding = PaddingValues(vertical = 24.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            item {
                Text("Interpreter Trainer", style = MaterialTheme.typography.headlineMedium)
                Spacer(Modifier.height(6.dp))
                Text("Train interpreting skills with focused, professional practice workflows.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(14.dp))
            }
            item { TrainingCard("Sight Translation", "Read, time and record sight-translation practice.", Icons.Default.Article) { onNavigate(Routes.SIGHT) } }
            item { TrainingCard("Shadowing", "Play audio/video while recording your own shadowing performance.", Icons.Default.GraphicEq) { onNavigate(Routes.SHADOWING) } }
            item { TrainingCard("Consecutive Interpretation", "Practice reliable 15, 30 or 60 second segments.", Icons.Default.SkipNext) { onNavigate(Routes.CONSECUTIVE) } }
            item { TrainingCard("Live Transcription", "Practice speech recognition in Arabic, English or French.", Icons.Default.Mic) { onNavigate(Routes.TRANSCRIPTION) } }
            item { TrainingCard("Local Interpreter Coach", "Independent offline scoring and feedback specialized for interpreter practice.", Icons.Default.AutoAwesome) { onNavigate(Routes.AI_COACH) } }
            item { TrainingCard("Practice History", "Review saved sessions, notes, transcripts, recordings and feedback.", Icons.Default.History) { onNavigate(Routes.HISTORY) } }
        }
    }
}

@Composable
private fun TrainingCard(title: String, subtitle: String, icon: ImageVector, onClick: () -> Unit) {
    ElevatedCard(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(20.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(32.dp), tint = MaterialTheme.colorScheme.primary)
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(4.dp))
                Text(subtitle, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Icon(Icons.Default.ChevronRight, contentDescription = null)
        }
    }
}

package com.interpretertrainer.app.ui.screens

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.interpretertrainer.app.data.database.PracticeSessionEntity
import com.interpretertrainer.app.model.LanguageOption
import com.interpretertrainer.app.model.PracticeMode
import com.interpretertrainer.app.util.formatDuration
import com.interpretertrainer.app.viewmodel.SessionViewModel
import kotlinx.coroutines.delay

@Composable
fun SightTranslationScreen(onBack: () -> Unit, sessionViewModel: SessionViewModel) {
    val context = LocalContext.current
    var text by rememberSaveable { mutableStateOf("") }
    var sourceName by rememberSaveable { mutableStateOf<String?>(null) }
    var fontSize by rememberSaveable { mutableFloatStateOf(18f) }
    var sourceLang by rememberSaveable { mutableStateOf(LanguageOption.ENGLISH_US) }
    var targetLang by rememberSaveable { mutableStateOf(LanguageOption.FRENCH_FRANCE) }
    var startedAt by rememberSaveable { mutableLongStateOf(0L) }
    var elapsed by rememberSaveable { mutableLongStateOf(0L) }
    var running by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(running) {
        while (running) { elapsed = System.currentTimeMillis() - startedAt; delay(250) }
    }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        uri ?: return@rememberLauncherForActivityResult
        runCatching { context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        sourceName = uri.lastPathSegment
        val type = context.contentResolver.getType(uri).orEmpty()
        text = when {
            type == "text/plain" || uri.toString().endsWith(".txt", true) -> context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }.orEmpty()
            else -> "Selected: ${sourceName ?: "document"}\n\nTXT extraction works in this baseline. PDF/DOCX extraction is intentionally isolated for the next document-processing module instead of pretending unsupported files were extracted."
        }
    }

    TrainerScaffold("Sight Translation", onBack) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).padding(16.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { picker.launch(arrayOf("text/plain", "application/pdf", "application/vnd.openxmlformats-officedocument.wordprocessingml.document")) }) { Text("Import document") }
                OutlinedButton(onClick = { text = ""; sourceName = null }) { Text("Clear") }
            }
            LanguageSelector("Source", sourceLang) { sourceLang = it }
            LanguageSelector("Target", targetLang) { targetLang = it }
            Text("Text size: ${fontSize.toInt()} sp")
            Slider(value = fontSize, onValueChange = { fontSize = it }, valueRange = 14f..32f)
            OutlinedTextField(value = text, onValueChange = { text = it }, modifier = Modifier.fillMaxWidth().heightIn(min = 280.dp), textStyle = LocalTextStyle.current.copy(fontSize = fontSize.sp), label = { Text("Source text") })
            Text("Practice time: ${formatDuration(elapsed)}", style = MaterialTheme.typography.titleMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { if (!running) { startedAt = System.currentTimeMillis() - elapsed; running = true } else running = false }) { Text(if (running) "Pause" else if (elapsed > 0) "Resume" else "Start") }
                Button(enabled = elapsed > 0, onClick = {
                    running = false
                    sessionViewModel.save(PracticeSessionEntity(practiceMode = PracticeMode.SIGHT_TRANSLATION.name, sourceLanguage = sourceLang.tag, targetLanguage = targetLang.tag, startedAt = startedAt.takeIf { it > 0 } ?: System.currentTimeMillis(), durationMillis = elapsed, sourceName = sourceName, transcript = "", notes = "", segmentDurationSeconds = null, status = "COMPLETED"))
                }) { Text("Save session") }
            }
        }
    }
}

package com.interpretertrainer.app.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.interpretertrainer.app.model.LanguageOption
import com.interpretertrainer.app.speech.SpeechRecognizerManager

@Composable
fun PracticeTranscriptionPanel(
    language: LanguageOption,
    transcript: String,
    onTranscriptChange: (String) -> Unit,
    enabled: Boolean = true,
    title: String = "Live transcription"
) {
    val context = LocalContext.current
    val speech = remember { SpeechRecognizerManager(context.applicationContext) }
    val state by speech.state.collectAsState()

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted && enabled) speech.start(language.tag)
    }

    fun startListening() {
        if (!enabled) return
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            speech.clearTranscript()
            speech.start(language.tag)
        } else {
            permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    LaunchedEffect(state.finalText, state.partialText) {
        val recognized = listOf(state.finalText, state.partialText)
            .filter { it.isNotBlank() }
            .joinToString(" ")
            .trim()
        if (recognized.isNotBlank()) onTranscriptChange(recognized)
    }

    LaunchedEffect(language, enabled) {
        if (!enabled && state.isListening) speech.stop()
        if (enabled && state.isListening) {
            speech.stop()
            speech.start(language.tag)
        }
    }

    DisposableEffect(Unit) {
        onDispose { speech.destroy() }
    }

    SectionCard {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            AssistChip(
                onClick = {},
                label = { Text(if (state.isListening) "Listening" else language.tag) },
                leadingIcon = {
                    Icon(
                        if (state.isListening) Icons.Default.Mic else Icons.Default.Mic,
                        contentDescription = null,
                        modifier = Modifier.heightIn(max = 18.dp)
                    )
                }
            )
        }
        Text(
            when {
                !enabled -> "Microphone is in use by the active recording session."
                state.isListening -> "Capturing ${language.label} speech in real time."
                else -> "Tap the microphone when you are ready to transcribe."
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }

        OutlinedTextField(
            value = transcript,
            onValueChange = onTranscriptChange,
            modifier = Modifier.fillMaxWidth().heightIn(min = 128.dp),
            placeholder = { Text("Recognized speech appears here") },
            enabled = enabled || transcript.isNotBlank(),
            shape = RoundedCornerShape(18.dp)
        )

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = { if (state.isListening) speech.stop() else startListening() },
                enabled = enabled,
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.weight(1f)
            ) {
                Icon(if (state.isListening) Icons.Default.Stop else Icons.Default.Mic, contentDescription = null)
                Text(if (state.isListening) " Stop" else " Transcribe")
            }
            FilledTonalIconButton(
                onClick = {
                    speech.clearTranscript()
                    onTranscriptChange("")
                },
                enabled = !state.isListening && transcript.isNotBlank()
            ) {
                Icon(Icons.Default.DeleteOutline, contentDescription = "Clear transcript")
            }
        }
    }
}

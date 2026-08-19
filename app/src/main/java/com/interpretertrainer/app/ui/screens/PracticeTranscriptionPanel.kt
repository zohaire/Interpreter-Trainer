package com.interpretertrainer.app.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
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

/**
 * Reusable Arabic / English / French live transcription panel for interpretation practice modes.
 * The parent owns the transcript so speech recognition and manual correction stay in sync.
 */
@Composable
fun PracticeTranscriptionPanel(
    language: LanguageOption,
    onLanguageChange: (LanguageOption) -> Unit,
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
        Text(title, style = MaterialTheme.typography.titleMedium)
        Text(
            "Transcribe directly in Arabic, English or French. You can correct the recognized text manually before saving the session.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        LanguageSelector("Transcription language", language) {
            onLanguageChange(it)
        }

        Text(
            when {
                !enabled -> "Transcription is paused while another microphone session is active."
                state.isListening -> "Listening…"
                else -> "Microphone stopped"
            },
            color = if (state.isListening) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
        )
        state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }

        OutlinedTextField(
            value = transcript,
            onValueChange = onTranscriptChange,
            modifier = Modifier.fillMaxWidth().heightIn(min = 120.dp),
            label = { Text("Transcript") },
            placeholder = { Text("Recognized speech will appear here") },
            enabled = enabled || transcript.isNotBlank()
        )

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = { if (state.isListening) speech.stop() else startListening() },
                enabled = enabled
            ) {
                Icon(if (state.isListening) Icons.Default.Stop else Icons.Default.Mic, contentDescription = null)
                Text(if (state.isListening) " Stop" else " Start")
            }
            OutlinedButton(
                onClick = {
                    speech.clearTranscript()
                    onTranscriptChange("")
                },
                enabled = !state.isListening && transcript.isNotBlank()
            ) { Text("Clear") }
        }
    }
}

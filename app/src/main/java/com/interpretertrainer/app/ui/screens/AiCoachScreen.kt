package com.interpretertrainer.app.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.interpretertrainer.app.ai.AiBackendSettings
import com.interpretertrainer.app.ai.AiCoachClient
import com.interpretertrainer.app.model.LanguageOption
import kotlinx.coroutines.launch

@Composable
fun AiCoachScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val client = remember { AiCoachClient() }
    val scope = rememberCoroutineScope()

    var backendUrl by rememberSaveable { mutableStateOf(AiBackendSettings.getUrl(context)) }
    var language by rememberSaveable { mutableStateOf(LanguageOption.ENGLISH_US) }
    var question by rememberSaveable { mutableStateOf("") }
    var answer by rememberSaveable { mutableStateOf("") }
    var error by rememberSaveable { mutableStateOf<String?>(null) }
    var loading by rememberSaveable { mutableStateOf(false) }

    TrainerScaffold("AI Interpreter Coach", onBack) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            SectionCard {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Icon(Icons.Default.AutoAwesome, contentDescription = null)
                    Column {
                        Text("Interpreter-focused AI assistant", style = MaterialTheme.typography.titleMedium)
                        Text(
                            "Ask about interpreting strategies, terminology, omissions, reformulation, register or practice planning.",
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            Text("Secure AI server", style = MaterialTheme.typography.titleMedium)
            OutlinedTextField(
                value = backendUrl,
                onValueChange = { backendUrl = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Backend URL") },
                placeholder = { Text("https://your-server.example.com") },
                singleLine = true
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = {
                    AiBackendSettings.setUrl(context, backendUrl)
                    backendUrl = AiBackendSettings.getUrl(context)
                    error = null
                }) { Text("Save server") }
                if (backendUrl.isBlank()) {
                    Text(
                        "Required for AI requests",
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(top = 12.dp)
                    )
                }
            }

            LanguageSelector("Coach language", language) { language = it }

            OutlinedTextField(
                value = question,
                onValueChange = { question = it },
                modifier = Modifier.fillMaxWidth().heightIn(min = 140.dp),
                label = { Text("Ask the AI coach") },
                placeholder = { Text("How can I reduce omissions during 30-second consecutive interpretation?") }
            )

            Button(
                enabled = !loading && question.isNotBlank() && backendUrl.isNotBlank(),
                onClick = {
                    loading = true
                    error = null
                    scope.launch {
                        client.askCoach(
                            baseUrl = backendUrl,
                            message = question,
                            languageTag = language.tag
                        ).onSuccess {
                            answer = it
                        }.onFailure {
                            error = it.message ?: "AI request failed."
                        }
                        loading = false
                    }
                }
            ) {
                if (loading) {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(8.dp))
                }
                Text(if (loading) "Thinking…" else "Ask AI Coach")
            }

            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }

            if (answer.isNotBlank()) {
                SectionCard {
                    Text("Coach response", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(8.dp))
                    Text(answer)
                }
            }

            Text(
                "The Android app never stores an OpenAI API key. The backend URL points to a server that keeps the key securely on the server side.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

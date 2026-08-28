package com.interpretertrainer.app.ui.screens

import android.webkit.CookieManager
import android.webkit.WebStorage
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.interpretertrainer.app.privacy.AiPrivacyPreferences

@Composable
fun PrivacyScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    var cleared by remember { mutableStateOf(false) }

    TrainerScaffold("Privacy & data", onBack) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 18.dp, vertical = 14.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            PrivacySection(
                "Practice data stays on this device",
                "Saved sessions, transcripts, notes, feedback and recordings are stored in app-private storage. Android backup is disabled. Deleting a session also deletes its app-owned recording."
            )
            PrivacySection(
                "Online Interpreter AI",
                "The coach requires internet access. Messages, evaluation material and up to five recent practice summaries may be sent to Puter and its selected Qwen model only after you accept the in-app disclosure. Puter authentication and provider policies apply."
            )
            PrivacySection(
                "Voice features",
                "Android speech recognition may use your device's configured online speech service. Interpreter AI voice output may use Puter text-to-speech, with Android text-to-speech as a fallback."
            )
            PrivacySection(
                "Your control",
                "You can continue using core practice modes without opening Interpreter AI. Clearing access below removes this app's AI consent, cookies and embedded web storage. It does not delete a Puter account or data held by external providers."
            )

            OutlinedButton(
                onClick = {
                    AiPrivacyPreferences.revoke(context)
                    CookieManager.getInstance().removeAllCookies(null)
                    CookieManager.getInstance().flush()
                    WebStorage.getInstance().deleteAllData()
                    cleared = true
                }
            ) {
                Text("Clear AI access and local web data")
            }
            if (cleared) {
                Text(
                    "AI access was reset. The disclosure will appear before the coach opens again.",
                    color = MaterialTheme.colorScheme.primary
                )
            }
        }
    }
}

@Composable
private fun PrivacySection(title: String, body: String) {
    SectionCard {
        Text(title, style = MaterialTheme.typography.titleMedium)
        Text(body, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

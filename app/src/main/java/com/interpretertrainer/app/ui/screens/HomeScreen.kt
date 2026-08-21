package com.interpretertrainer.app.ui.screens

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.interpretertrainer.app.R
import com.interpretertrainer.app.media.AudioRouteKind
import com.interpretertrainer.app.media.rememberAudioOutputRoute
import com.interpretertrainer.app.ui.Routes
import com.interpretertrainer.app.ui.theme.ThemeMode

private enum class HomeWorkspace { INTERPRETING, SIGN_LANGUAGE }

@Composable
fun HomeScreen(
    onNavigate: (String) -> Unit,
    themeMode: ThemeMode,
    onThemeModeChange: (ThemeMode) -> Unit
) {
    val context = LocalContext.current
    val audioRoute = rememberAudioOutputRoute()
    var workspace by rememberSaveable { mutableStateOf(HomeWorkspace.INTERPRETING) }

    Scaffold { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 18.dp),
            contentPadding = PaddingValues(top = 20.dp, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                ElevatedCard(modifier = Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier.padding(18.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        Surface(
                            shape = MaterialTheme.shapes.extraLarge,
                            color = MaterialTheme.colorScheme.surface,
                            modifier = Modifier.size(86.dp)
                        ) {
                            Image(
                                painter = painterResource(R.drawable.ic_app),
                                contentDescription = "Interpreter Trainer logo",
                                modifier = Modifier.padding(8.dp)
                            )
                        }
                        Column(Modifier.weight(1f)) {
                            Text(
                                "Interpreter Trainer",
                                style = MaterialTheme.typography.headlineSmall,
                                fontWeight = FontWeight.SemiBold
                            )
                            Spacer(Modifier.height(5.dp))
                            Text(
                                "Interpretation training and sign-language practice in one app.",
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            if (audioRoute.isExternal) {
                                Spacer(Modifier.height(9.dp))
                                Row(
                                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(
                                        imageVector = when (audioRoute.kind) {
                                            AudioRouteKind.BLUETOOTH -> Icons.Default.Bluetooth
                                            AudioRouteKind.WIRED -> Icons.Default.Headphones
                                            AudioRouteKind.EXTERNAL -> Icons.Default.SettingsInputComponent
                                            AudioRouteKind.SPEAKER -> Icons.Default.VolumeUp
                                        },
                                        contentDescription = null,
                                        modifier = Modifier.size(18.dp),
                                        tint = MaterialTheme.colorScheme.primary
                                    )
                                    Text(
                                        audioRoute.deviceName ?: audioRoute.label,
                                        style = MaterialTheme.typography.labelMedium,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                }
                            }
                        }
                    }
                }
            }

            item {
                Text(
                    "Workspace",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(top = 4.dp)
                )
            }
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    FilterChip(
                        selected = workspace == HomeWorkspace.INTERPRETING,
                        onClick = { workspace = HomeWorkspace.INTERPRETING },
                        label = { Text("Interpreting") },
                        leadingIcon = { Icon(Icons.Default.Headphones, contentDescription = null) },
                        modifier = Modifier.weight(1f)
                    )
                    FilterChip(
                        selected = workspace == HomeWorkspace.SIGN_LANGUAGE,
                        onClick = { workspace = HomeWorkspace.SIGN_LANGUAGE },
                        label = { Text("Sign Language") },
                        leadingIcon = { Icon(Icons.Default.RecordVoiceOver, contentDescription = null) },
                        modifier = Modifier.weight(1f)
                    )
                }
            }

            if (workspace == HomeWorkspace.INTERPRETING) {
                item {
                    Text("Interpretation studio", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 6.dp))
                }
                item {
                    TrainingCard(
                        "Simultaneous Interpretation",
                        "Interpret from video/audio or source text, record your delivery, and transcribe in Arabic, English or French.",
                        Icons.Default.Headphones
                    ) { onNavigate(Routes.SIMULTANEOUS) }
                }
                item {
                    TrainingCard(
                        "Shadowing",
                        "Shadow Arabic, English or French from media or AI text, record yourself, and review a live transcript.",
                        Icons.Default.RecordVoiceOver
                    ) { onNavigate(Routes.SHADOWING) }
                }
                item {
                    TrainingCard(
                        "Consecutive Interpretation",
                        "Work through source segments or AI practice text with integrated three-language transcription.",
                        Icons.Default.SkipNext
                    ) { onNavigate(Routes.CONSECUTIVE) }
                }
                item {
                    TrainingCard(
                        "Live Transcription",
                        "Capture speech in Arabic, English or French for review and practice.",
                        Icons.Default.Mic
                    ) { onNavigate(Routes.TRANSCRIPTION) }
                }
                item {
                    Text("Coach & review", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 6.dp))
                }
                item {
                    TrainingCard(
                        "Interpreter Coach",
                        "Qwen3.8 Max online AI with voice chat, universal file attachments, practice-text handoff and evaluation.",
                        Icons.Default.AutoAwesome
                    ) { onNavigate(Routes.AI_COACH) }
                }
                item {
                    TrainingCard(
                        "Practice History",
                        "Review saved sessions, transcripts, recordings, notes and feedback.",
                        Icons.Default.History
                    ) { onNavigate(Routes.HISTORY) }
                }
            } else {
                item {
                    Text("Sign-language studio", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 6.dp))
                }
                item {
                    TrainingCard(
                        "Sign Language Emulator",
                        "Turn English text or uploaded text files into an ASL-oriented sequence with a human-proportioned 3D signer and safe fingerspelling fallback.",
                        Icons.Default.RecordVoiceOver
                    ) { onNavigate(Routes.SIGN_LANGUAGE) }
                }
                item {
                    SectionCard {
                        Text("Accuracy first", style = MaterialTheme.typography.titleMedium)
                        Text(
                            "The emulator uses phrase-level rules where available and fingerspells unknown terms instead of inventing random signs. It is a training aid, not a certified human interpreter.",
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            item {
                SectionCard {
                    Text("Appearance", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "Choose what is most comfortable for reading and long practice sessions.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        ThemeMode.entries.forEach { mode ->
                            FilterChip(
                                selected = themeMode == mode,
                                onClick = { onThemeModeChange(mode) },
                                label = { Text(mode.label) }
                            )
                        }
                    }
                }
            }

            item {
                HorizontalDivider(Modifier.padding(vertical = 8.dp))
                Column(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(5.dp)
                ) {
                    Text("Owner", style = MaterialTheme.typography.labelLarge)
                    Text("Zouhair Elachaqi", style = MaterialTheme.typography.titleSmall)
                    TextButton(onClick = {
                        context.startActivity(Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:zohaireachak@gmail.com")))
                    }) { Text("zohaireachak@gmail.com") }
                    TextButton(onClick = {
                        context.startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:+212655156667")))
                    }) { Text("+212655156667") }
                    Text(
                        "Interpreter Trainer",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

@Composable
private fun TrainingCard(title: String, subtitle: String, icon: ImageVector, onClick: () -> Unit) {
    ElevatedCard(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        Row(
            Modifier.padding(horizontal = 18.dp, vertical = 17.dp),
            horizontalArrangement = Arrangement.spacedBy(15.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Surface(
                color = MaterialTheme.colorScheme.primaryContainer,
                shape = MaterialTheme.shapes.medium,
                modifier = Modifier.size(48.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        icon,
                        contentDescription = null,
                        modifier = Modifier.size(27.dp),
                        tint = MaterialTheme.colorScheme.primary
                    )
                }
            }
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(4.dp))
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Icon(Icons.Default.ChevronRight, contentDescription = null)
        }
    }
}

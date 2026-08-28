package com.interpretertrainer.app.ui.screens

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.interpretertrainer.app.R
import com.interpretertrainer.app.media.AudioOutputRoute
import com.interpretertrainer.app.media.AudioRouteKind
import com.interpretertrainer.app.media.rememberAudioOutputRoute
import com.interpretertrainer.app.ui.Routes
import com.interpretertrainer.app.ui.theme.ThemeMode

private val StudioPurple = Color(0xFF3528D8)
private val StudioViolet = Color(0xFF7356FF)
private val StudioBackground = Color(0xFFFAF9FE)
private val StudioCard = Color.White
private val StudioText = Color(0xFF141622)
private val StudioMuted = Color(0xFF5C6070)
private val StudioBorder = Color(0xFFE8E5F0)

@Composable
fun HomeScreen(
    onNavigate: (String) -> Unit,
    themeMode: ThemeMode,
    onThemeModeChange: (ThemeMode) -> Unit
) {
    val context = LocalContext.current
    val audioRoute = rememberAudioOutputRoute()
    val dark = when (themeMode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }
    val pageBackground = if (dark) MaterialTheme.colorScheme.background else StudioBackground
    val cardColor = if (dark) MaterialTheme.colorScheme.surface else StudioCard
    val primaryText = if (dark) MaterialTheme.colorScheme.onSurface else StudioText
    val secondaryText = if (dark) MaterialTheme.colorScheme.onSurfaceVariant else StudioMuted
    val borderColor = if (dark) MaterialTheme.colorScheme.outlineVariant else StudioBorder

    Scaffold(
        containerColor = pageBackground,
        floatingActionButton = {
            FloatingActionButton(
                onClick = { onNavigate(Routes.AI_COACH) },
                modifier = Modifier.size(66.dp),
                shape = CircleShape,
                containerColor = StudioPurple,
                contentColor = Color.White,
                elevation = FloatingActionButtonDefaults.elevation(defaultElevation = 12.dp)
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .clip(CircleShape)
                        .background(Brush.linearGradient(listOf(StudioViolet, Color(0xFF2D18C7)))),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        Icons.Default.AutoAwesome,
                        contentDescription = "Open Interpreter AI",
                        modifier = Modifier.size(31.dp)
                    )
                }
            }
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 20.dp),
            contentPadding = PaddingValues(top = 18.dp, bottom = 108.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            item { HeroCard(cardColor, primaryText, secondaryText, borderColor, audioRoute) }
            item {
                Spacer(Modifier.height(8.dp))
                SectionTitle("Interpretation studio", primaryText)
            }
            item {
                StudioTrainingCard(
                    "Simultaneous Interpretation",
                    "Interpret from media or source text, record your delivery, and transcribe in Arabic, English or French.",
                    Icons.Default.Headphones,
                    Color(0xFFEAE9FF), cardColor, primaryText, secondaryText, borderColor
                ) { onNavigate(Routes.SIMULTANEOUS) }
            }
            item {
                StudioTrainingCard(
                    "Shadowing",
                    "Shadow three-language media or AI text, record yourself, and review a live transcript.",
                    Icons.Default.RecordVoiceOver,
                    Color(0xFFF2E7FF), cardColor, primaryText, secondaryText, borderColor
                ) { onNavigate(Routes.SHADOWING) }
            }
            item {
                StudioTrainingCard(
                    "Consecutive Interpretation",
                    "Work through timed source segments with integrated three-language transcription.",
                    Icons.Default.SkipNext,
                    Color(0xFFEDEAFF), cardColor, primaryText, secondaryText, borderColor
                ) { onNavigate(Routes.CONSECUTIVE) }
            }
            item {
                StudioTrainingCard(
                    "Live Transcription",
                    "Capture speech in Arabic, English or French for review and practice.",
                    Icons.Default.Mic,
                    Color(0xFFF0E8FF), cardColor, primaryText, secondaryText, borderColor
                ) { onNavigate(Routes.TRANSCRIPTION) }
            }
            item {
                Spacer(Modifier.height(8.dp))
                SectionTitle("Coach & review", primaryText)
            }
            item {
                StudioTrainingCard(
                    "Interpreter AI",
                    "Use online streaming coaching, voice conversation, practice handoff and performance evaluation.",
                    Icons.Default.AutoAwesome,
                    Color(0xFFE7E1FF), cardColor, primaryText, secondaryText, borderColor
                ) { onNavigate(Routes.AI_COACH) }
            }
            item {
                StudioTrainingCard(
                    "Practice History",
                    "Review saved sessions, transcripts, recordings, notes and feedback.",
                    Icons.Default.History,
                    Color(0xFFEAE9FF), cardColor, primaryText, secondaryText, borderColor
                ) { onNavigate(Routes.HISTORY) }
            }
            item {
                Spacer(Modifier.height(8.dp))
                InfoCard(
                    "Appearance",
                    "Choose the theme that is most comfortable for longer practice sessions.",
                    cardColor, primaryText, secondaryText, borderColor
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
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
                InfoCard(
                    "Privacy & support",
                    "Review how local practice data and optional online AI features are handled.",
                    cardColor, primaryText, secondaryText, borderColor
                ) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(onClick = { onNavigate(Routes.PRIVACY) }) { Text("Privacy & data") }
                        TextButton(onClick = {
                            context.startActivity(
                                Intent(
                                    Intent.ACTION_SENDTO,
                                    Uri.parse("mailto:zohaireachak@gmail.com?subject=Interpreter%20Trainer%20support")
                                )
                            )
                        }) { Text("Contact support") }
                    }
                }
            }
            item {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(3.dp)
                ) {
                    Text(
                        "Interpreter Trainer · Zouhair Elachaqi",
                        style = MaterialTheme.typography.labelMedium,
                        color = secondaryText
                    )
                    Text(
                        "Arabic · English · French",
                        style = MaterialTheme.typography.bodySmall,
                        color = secondaryText
                    )
                }
            }
        }
    }
}

@Composable
private fun HeroCard(
    cardColor: Color,
    primaryText: Color,
    secondaryText: Color,
    borderColor: Color,
    audioRoute: AudioOutputRoute
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(28.dp),
        colors = CardDefaults.cardColors(containerColor = cardColor),
        border = BorderStroke(1.dp, borderColor),
        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    Brush.linearGradient(
                        listOf(Color.Transparent, Color(0x0F6D55FF), Color(0x1A9B86FF))
                    )
                )
                .padding(horizontal = 22.dp, vertical = 24.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(20.dp)
        ) {
            Surface(
                shape = RoundedCornerShape(24.dp),
                color = Color.White,
                shadowElevation = 6.dp,
                modifier = Modifier.size(104.dp)
            ) {
                Image(
                    painter = painterResource(R.drawable.ic_app),
                    contentDescription = "Interpreter Trainer logo",
                    modifier = Modifier.padding(12.dp)
                )
            }
            Column(Modifier.weight(1f)) {
                Text(
                    "Interpreter Trainer",
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold,
                    color = primaryText
                )
                Spacer(Modifier.height(7.dp))
                Text(
                    "Professional Arabic, English and French interpretation practice.",
                    style = MaterialTheme.typography.bodyLarge,
                    color = secondaryText
                )
                if (audioRoute.isExternal) {
                    Spacer(Modifier.height(10.dp))
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            when (audioRoute.kind) {
                                AudioRouteKind.BLUETOOTH -> Icons.Default.Bluetooth
                                AudioRouteKind.WIRED -> Icons.Default.Headphones
                                AudioRouteKind.EXTERNAL -> Icons.Default.SettingsInputComponent
                                AudioRouteKind.SPEAKER -> Icons.Default.VolumeUp
                            },
                            contentDescription = null,
                            modifier = Modifier.size(17.dp),
                            tint = StudioPurple
                        )
                        Text(
                            audioRoute.deviceName ?: audioRoute.label,
                            style = MaterialTheme.typography.labelMedium,
                            color = StudioPurple
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SectionTitle(title: String, color: Color) {
    Text(
        title,
        style = MaterialTheme.typography.headlineSmall,
        fontWeight = FontWeight.Bold,
        color = color
    )
}

@Composable
private fun StudioTrainingCard(
    title: String,
    subtitle: String,
    icon: ImageVector,
    iconBackground: Color,
    cardColor: Color,
    primaryText: Color,
    secondaryText: Color,
    borderColor: Color,
    onClick: () -> Unit
) {
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = cardColor),
        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp),
        border = BorderStroke(1.dp, borderColor)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 18.dp),
            horizontalArrangement = Arrangement.spacedBy(18.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Surface(
                color = if (cardColor == StudioCard) iconBackground else MaterialTheme.colorScheme.primaryContainer,
                shape = RoundedCornerShape(20.dp),
                modifier = Modifier.size(64.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(icon, null, Modifier.size(33.dp), tint = StudioPurple)
                }
            }
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = primaryText)
                Spacer(Modifier.height(5.dp))
                Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = secondaryText)
            }
            Icon(Icons.Default.ChevronRight, contentDescription = null, tint = primaryText)
        }
    }
}

@Composable
private fun InfoCard(
    title: String,
    body: String,
    cardColor: Color,
    primaryText: Color,
    secondaryText: Color,
    borderColor: Color,
    content: @Composable ColumnScope.() -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(22.dp),
        colors = CardDefaults.cardColors(containerColor = cardColor),
        border = BorderStroke(1.dp, borderColor)
    ) {
        Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = primaryText)
            Text(body, style = MaterialTheme.typography.bodyMedium, color = secondaryText)
            content()
        }
    }
}

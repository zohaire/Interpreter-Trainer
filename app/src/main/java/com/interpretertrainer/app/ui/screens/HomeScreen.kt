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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
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
import com.interpretertrainer.app.media.AudioRouteKind
import com.interpretertrainer.app.media.rememberAudioOutputRoute
import com.interpretertrainer.app.ui.Routes
import com.interpretertrainer.app.ui.theme.ThemeMode

private enum class HomeWorkspace { INTERPRETING, SIGN_LANGUAGE }

private val StudioPurple = Color(0xFF3528D8)
private val StudioViolet = Color(0xFF7356FF)
private val StudioLilac = Color(0xFFF2EEFF)
private val StudioLilacStrong = Color(0xFFE9E1FF)
private val StudioText = Color(0xFF141622)
private val StudioMuted = Color(0xFF5C6070)
private val StudioBackground = Color(0xFFFAF9FE)
private val StudioCard = Color(0xFFFFFFFF)
private val StudioBorder = Color(0xFFE8E5F0)

@Composable
fun HomeScreen(
    onNavigate: (String) -> Unit,
    themeMode: ThemeMode,
    onThemeModeChange: (ThemeMode) -> Unit
) {
    val context = LocalContext.current
    val audioRoute = rememberAudioOutputRoute()
    var workspace by rememberSaveable { mutableStateOf(HomeWorkspace.INTERPRETING) }
    val dark = isSystemInDarkTheme() && themeMode != ThemeMode.LIGHT

    val pageBackground = if (dark) MaterialTheme.colorScheme.background else StudioBackground
    val cardColor = if (dark) MaterialTheme.colorScheme.surface else StudioCard
    val primaryText = if (dark) MaterialTheme.colorScheme.onSurface else StudioText
    val secondaryText = if (dark) MaterialTheme.colorScheme.onSurfaceVariant else StudioMuted
    val borderColor = if (dark) MaterialTheme.colorScheme.outlineVariant else StudioBorder

    Scaffold(
        containerColor = pageBackground,
        floatingActionButton = {
            if (workspace == HomeWorkspace.INTERPRETING) {
                FloatingActionButton(
                    onClick = { onNavigate(Routes.AI_COACH) },
                    modifier = Modifier.size(66.dp),
                    shape = CircleShape,
                    containerColor = StudioPurple,
                    contentColor = Color.White,
                    elevation = FloatingActionButtonDefaults.elevation(
                        defaultElevation = 12.dp,
                        pressedElevation = 6.dp
                    )
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .clip(CircleShape)
                            .background(
                                Brush.linearGradient(
                                    listOf(StudioViolet, Color(0xFF2D18C7))
                                )
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            Icons.Default.AutoAwesome,
                            contentDescription = "Open Interpreter AI",
                            modifier = Modifier.size(31.dp),
                            tint = Color.White
                        )
                    }
                }
            }
        },
        floatingActionButtonPosition = FabPosition.End
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 20.dp),
            contentPadding = PaddingValues(top = 18.dp, bottom = 108.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            item {
                HeroCard(
                    cardColor = cardColor,
                    primaryText = primaryText,
                    secondaryText = secondaryText,
                    borderColor = borderColor,
                    audioRoute = audioRoute
                )
            }

            item {
                Spacer(Modifier.height(8.dp))
                Text(
                    "Workspace",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    color = primaryText
                )
            }

            item {
                WorkspaceSwitcher(
                    selected = workspace,
                    onSelected = { workspace = it },
                    cardColor = cardColor,
                    borderColor = borderColor,
                    primaryText = primaryText,
                    secondaryText = secondaryText
                )
            }

            if (workspace == HomeWorkspace.INTERPRETING) {
                item {
                    Spacer(Modifier.height(10.dp))
                    SectionTitle("Interpretation studio", primaryText)
                }

                item {
                    StudioTrainingCard(
                        title = "Simultaneous Interpretation",
                        subtitle = "Interpret from video/audio or source text, record your delivery, and transcribe in Arabic, English or French.",
                        icon = Icons.Default.Headphones,
                        iconTint = StudioPurple,
                        iconBackground = if (dark) MaterialTheme.colorScheme.primaryContainer else Color(0xFFEAE9FF),
                        cardColor = cardColor,
                        primaryText = primaryText,
                        secondaryText = secondaryText,
                        borderColor = borderColor
                    ) { onNavigate(Routes.SIMULTANEOUS) }
                }

                item {
                    StudioTrainingCard(
                        title = "Shadowing",
                        subtitle = "Shadow Arabic, English or French from media or AI text, record yourself, and review a live transcript.",
                        icon = Icons.Default.RecordVoiceOver,
                        iconTint = Color(0xFF5A28DF),
                        iconBackground = if (dark) MaterialTheme.colorScheme.primaryContainer else Color(0xFFF2E7FF),
                        cardColor = cardColor,
                        primaryText = primaryText,
                        secondaryText = secondaryText,
                        borderColor = borderColor
                    ) { onNavigate(Routes.SHADOWING) }
                }

                item {
                    StudioTrainingCard(
                        title = "Consecutive Interpretation",
                        subtitle = "Work through source segments or AI practice text with integrated three-language transcription.",
                        icon = Icons.Default.SkipNext,
                        iconTint = Color(0xFF382AD7),
                        iconBackground = if (dark) MaterialTheme.colorScheme.primaryContainer else Color(0xFFEDEAFF),
                        cardColor = cardColor,
                        primaryText = primaryText,
                        secondaryText = secondaryText,
                        borderColor = borderColor
                    ) { onNavigate(Routes.CONSECUTIVE) }
                }

                item {
                    StudioTrainingCard(
                        title = "Live Transcription",
                        subtitle = "Capture speech in Arabic, English or French for review and practice.",
                        icon = Icons.Default.Mic,
                        iconTint = Color(0xFF4D28E2),
                        iconBackground = if (dark) MaterialTheme.colorScheme.primaryContainer else Color(0xFFF0E8FF),
                        cardColor = cardColor,
                        primaryText = primaryText,
                        secondaryText = secondaryText,
                        borderColor = borderColor
                    ) { onNavigate(Routes.TRANSCRIPTION) }
                }

                item {
                    Spacer(Modifier.height(8.dp))
                    SectionTitle("Review", primaryText)
                }

                item {
                    StudioTrainingCard(
                        title = "Practice History",
                        subtitle = "Review saved sessions, transcripts, recordings, notes and feedback.",
                        icon = Icons.Default.History,
                        iconTint = StudioPurple,
                        iconBackground = if (dark) MaterialTheme.colorScheme.primaryContainer else StudioLilac,
                        cardColor = cardColor,
                        primaryText = primaryText,
                        secondaryText = secondaryText,
                        borderColor = borderColor
                    ) { onNavigate(Routes.HISTORY) }
                }
            } else {
                item {
                    Spacer(Modifier.height(10.dp))
                    SectionTitle("Sign-language studio", primaryText)
                }

                item {
                    StudioTrainingCard(
                        title = "Sign Language Emulator",
                        subtitle = "Translate English text and uploaded material into an ASL-oriented sequence with an animated human signer.",
                        icon = Icons.Default.RecordVoiceOver,
                        iconTint = StudioPurple,
                        iconBackground = if (dark) MaterialTheme.colorScheme.primaryContainer else StudioLilacStrong,
                        cardColor = cardColor,
                        primaryText = primaryText,
                        secondaryText = secondaryText,
                        borderColor = borderColor
                    ) { onNavigate(Routes.SIGN_LANGUAGE) }
                }

                item {
                    InfoCard(
                        title = "Accuracy first",
                        body = "Phrase-level signs are used where available. Unknown terms fall back to visible fingerspelling rather than invented random signs.",
                        cardColor = cardColor,
                        primaryText = primaryText,
                        secondaryText = secondaryText,
                        borderColor = borderColor
                    )
                }
            }

            item {
                Spacer(Modifier.height(8.dp))
                InfoCard(
                    title = "Appearance",
                    body = "Choose the theme that is most comfortable for longer practice sessions.",
                    cardColor = cardColor,
                    primaryText = primaryText,
                    secondaryText = secondaryText,
                    borderColor = borderColor
                ) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.fillMaxWidth()
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
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    Text(
                        "Interpreter Trainer · Zouhair Elachaqi",
                        style = MaterialTheme.typography.labelMedium,
                        color = secondaryText
                    )
                    TextButton(onClick = {
                        context.startActivity(Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:zohaireachak@gmail.com")))
                    }) {
                        Text("Contact developer", color = StudioPurple)
                    }
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
    audioRoute: com.interpretertrainer.app.media.AudioOutputRoute
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(28.dp),
        colors = CardDefaults.cardColors(containerColor = cardColor),
        border = BorderStroke(1.dp, borderColor),
        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    Brush.linearGradient(
                        listOf(
                            Color.Transparent,
                            Color(0x0F6D55FF),
                            Color(0x1A9B86FF)
                        )
                    )
                )
                .padding(horizontal = 22.dp, vertical = 24.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(20.dp)
            ) {
                Surface(
                    shape = RoundedCornerShape(24.dp),
                    color = Color.White,
                    tonalElevation = 0.dp,
                    shadowElevation = 6.dp,
                    modifier = Modifier.size(108.dp)
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
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Interpretation training and sign-language practice in one app.",
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
                                imageVector = when (audioRoute.kind) {
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
}

@Composable
private fun WorkspaceSwitcher(
    selected: HomeWorkspace,
    onSelected: (HomeWorkspace) -> Unit,
    cardColor: Color,
    borderColor: Color,
    primaryText: Color,
    secondaryText: Color
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        WorkspacePill(
            modifier = Modifier.weight(1f),
            title = "Interpreting",
            subtitle = null,
            icon = Icons.Default.Headphones,
            selected = selected == HomeWorkspace.INTERPRETING,
            onClick = { onSelected(HomeWorkspace.INTERPRETING) },
            cardColor = cardColor,
            borderColor = borderColor,
            primaryText = primaryText,
            secondaryText = secondaryText
        )
        WorkspacePill(
            modifier = Modifier.weight(1f),
            title = "Sign Language",
            subtitle = null,
            icon = Icons.Default.RecordVoiceOver,
            selected = selected == HomeWorkspace.SIGN_LANGUAGE,
            onClick = { onSelected(HomeWorkspace.SIGN_LANGUAGE) },
            cardColor = cardColor,
            borderColor = borderColor,
            primaryText = primaryText,
            secondaryText = secondaryText
        )
    }
}

@Composable
private fun WorkspacePill(
    modifier: Modifier,
    title: String,
    subtitle: String?,
    icon: ImageVector,
    selected: Boolean,
    onClick: () -> Unit,
    cardColor: Color,
    borderColor: Color,
    primaryText: Color,
    secondaryText: Color
) {
    val container = if (selected) StudioLilacStrong else cardColor
    val stroke = if (selected) Color(0xFFD5C8FF) else borderColor

    Surface(
        onClick = onClick,
        modifier = modifier.heightIn(min = 64.dp),
        shape = RoundedCornerShape(18.dp),
        color = container,
        border = BorderStroke(1.dp, stroke),
        shadowElevation = if (selected) 3.dp else 1.dp
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 13.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(11.dp)
        ) {
            Icon(
                icon,
                contentDescription = null,
                tint = StudioPurple,
                modifier = Modifier.size(27.dp)
            )
            Column(Modifier.weight(1f)) {
                Text(
                    title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = if (selected) FontWeight.Bold else FontWeight.SemiBold,
                    color = if (selected) StudioPurple else primaryText,
                    maxLines = 1
                )
                if (subtitle != null) {
                    Text(
                        subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = secondaryText,
                        maxLines = 1
                    )
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
    iconTint: Color,
    iconBackground: Color,
    cardColor: Color,
    primaryText: Color,
    secondaryText: Color,
    borderColor: Color,
    onClick: () -> Unit
) {
    ElevatedCard(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.elevatedCardColors(containerColor = cardColor),
        elevation = CardDefaults.elevatedCardElevation(defaultElevation = 4.dp),
        border = BorderStroke(1.dp, borderColor)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 18.dp),
            horizontalArrangement = Arrangement.spacedBy(18.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Surface(
                color = iconBackground,
                shape = RoundedCornerShape(20.dp),
                modifier = Modifier.size(66.dp),
                shadowElevation = 2.dp
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        icon,
                        contentDescription = null,
                        modifier = Modifier.size(34.dp),
                        tint = iconTint
                    )
                }
            }

            Column(Modifier.weight(1f)) {
                Text(
                    title,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = primaryText
                )
                Spacer(Modifier.height(5.dp))
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodyMedium,
                    color = secondaryText
                )
            }

            Surface(
                shape = CircleShape,
                color = if (cardColor == StudioCard) Color(0xFFF8F7FB) else MaterialTheme.colorScheme.surfaceVariant,
                modifier = Modifier.size(44.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        Icons.Default.ChevronRight,
                        contentDescription = null,
                        modifier = Modifier.size(26.dp),
                        tint = primaryText
                    )
                }
            }
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
    content: (@Composable ColumnScope.() -> Unit)? = null
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
            Text(
                title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = primaryText
            )
            Text(
                body,
                style = MaterialTheme.typography.bodyMedium,
                color = secondaryText
            )
            content?.invoke(this)
        }
    }
}

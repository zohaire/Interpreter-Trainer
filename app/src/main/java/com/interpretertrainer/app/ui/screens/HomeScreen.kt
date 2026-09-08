package com.interpretertrainer.app.ui.screens

import android.content.Intent
import android.net.Uri
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.BrightnessAuto
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.Headphones
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.LightMode
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.PrivacyTip
import androidx.compose.material.icons.filled.RecordVoiceOver
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SupportAgent
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.interpretertrainer.app.model.PracticeMode
import com.interpretertrainer.app.ui.Routes
import com.interpretertrainer.app.ui.theme.InterpreterBlue
import com.interpretertrainer.app.ui.theme.InterpreterCyan
import com.interpretertrainer.app.ui.theme.InterpreterNavy
import com.interpretertrainer.app.ui.theme.ThemeMode
import com.interpretertrainer.app.viewmodel.SessionViewModel
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

private val HeroBlue = Color(0xFF0A3D98)
private val HeroDeepBlue = Color(0xFF061738)
private val ContinueBlue = Color(0xFF1A6FFF)
private val DashboardLightBackground = Color(0xFFF5F8FD)
private val DashboardDarkBackground = Color(0xFF050D1C)
private val DashboardNav = Color(0xFF071A3D)
private val CardLight = Color(0xFFFBFCFF)
private val CardDark = Color(0xFF0D1B32)

private enum class DashboardTab { HOME, PRACTICE }

private data class PracticeCardSpec(
    val title: String,
    val description: String,
    val icon: ImageVector,
    val iconColor: Color,
    val iconBackground: Color,
    val route: String
)

@Composable
fun HomeScreen(
    onNavigate: (String) -> Unit,
    themeMode: ThemeMode,
    onThemeModeChange: (ThemeMode) -> Unit,
    sessionViewModel: SessionViewModel
) {
    val context = LocalContext.current
    val sessions by sessionViewModel.sessions.collectAsStateWithLifecycle()
    val summary = remember(sessions) { buildDashboardSummary(sessions) }
    val listState = rememberLazyListState()
    val coroutineScope = rememberCoroutineScope()
    val selectedTab by remember {
        derivedStateOf {
            if (listState.firstVisibleItemIndex == 0) DashboardTab.HOME else DashboardTab.PRACTICE
        }
    }
    val dark = when (themeMode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }
    val pageBackground = if (dark) DashboardDarkBackground else DashboardLightBackground

    Scaffold(
        containerColor = pageBackground,
        bottomBar = {
            DashboardNavigationBar(
                selectedTab = selectedTab,
                onHome = { coroutineScope.launch { listState.animateScrollToItem(0) } },
                onPractice = { coroutineScope.launch { listState.animateScrollToItem(1) } },
                onAiCoach = { onNavigate(Routes.AI_COACH) },
                onHistory = { onNavigate(Routes.HISTORY) }
            )
        }
    ) { padding ->
        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(bottom = 28.dp)
        ) {
            item(key = "dashboard-header") {
                DashboardHeader(
                    summary = summary,
                    themeMode = themeMode,
                    onThemeModeChange = onThemeModeChange,
                    onOpenPrivacy = { onNavigate(Routes.PRIVACY) },
                    onContactSupport = {
                        context.startActivity(
                            Intent(
                                Intent.ACTION_SENDTO,
                                Uri.parse("mailto:zohaireachak@gmail.com?subject=Interpreter%20Trainer%20support")
                            )
                        )
                    },
                    onContinue = {
                        onNavigate(routeForPracticeMode(summary.latestSession?.practiceMode))
                    }
                )
            }
            item(key = "practice-grid") {
                PracticeGrid(onNavigate = onNavigate, dark = dark)
            }
            item(key = "dashboard-footer") {
                DashboardFooter(dark = dark, onOpenPrivacy = { onNavigate(Routes.PRIVACY) })
            }
        }
    }
}

@Composable
private fun DashboardHeader(
    summary: DashboardSummary,
    themeMode: ThemeMode,
    onThemeModeChange: (ThemeMode) -> Unit,
    onOpenPrivacy: () -> Unit,
    onContactSupport: () -> Unit,
    onContinue: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(472.dp)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(390.dp)
                .clip(RoundedCornerShape(bottomStart = 42.dp, bottomEnd = 42.dp))
                .background(
                    Brush.verticalGradient(
                        colors = listOf(InterpreterNavy, HeroBlue, HeroDeepBlue)
                    )
                )
        ) {
            HeroWaves()
            Column(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .fillMaxWidth()
                    .widthIn(max = 780.dp)
                    .statusBarsPadding()
                    .padding(horizontal = 22.dp, vertical = 12.dp)
            ) {
                DashboardTopBar(
                    themeMode = themeMode,
                    onThemeModeChange = onThemeModeChange,
                    onOpenPrivacy = onOpenPrivacy,
                    onContactSupport = onContactSupport
                )
                Spacer(Modifier.height(36.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(18.dp)
                ) {
                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Text(
                            text = "Practice\nwith precision",
                            style = MaterialTheme.typography.displaySmall,
                            color = Color.White
                        )
                        Text(
                            text = "Build your skills. Broaden your impact.",
                            style = MaterialTheme.typography.bodyLarge,
                            color = Color(0xFFB7CEF8)
                        )
                    }
                    WeeklyGoal(summary)
                }
            }
        }

        ContinuePracticeCard(
            summary = summary,
            onClick = onContinue,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .widthIn(max = 744.dp)
                .padding(horizontal = 18.dp)
        )
    }
}

@Composable
private fun DashboardTopBar(
    themeMode: ThemeMode,
    onThemeModeChange: (ThemeMode) -> Unit,
    onOpenPrivacy: () -> Unit,
    onContactSupport: () -> Unit
) {
    var menuExpanded by remember { mutableStateOf(false) }

    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = "Interpreter Trainer",
            style = MaterialTheme.typography.headlineSmall,
            color = Color.White,
            modifier = Modifier.weight(1f)
        )
        Box {
            IconButton(onClick = { menuExpanded = true }) {
                Icon(
                    imageVector = Icons.Default.AccountCircle,
                    contentDescription = "Open appearance, privacy and support",
                    tint = Color.White,
                    modifier = Modifier.size(30.dp)
                )
            }
            DropdownMenu(
                expanded = menuExpanded,
                onDismissRequest = { menuExpanded = false }
            ) {
                Text(
                    text = "Appearance",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                )
                ThemeMode.entries.forEach { mode ->
                    DropdownMenuItem(
                        text = { Text(mode.label) },
                        leadingIcon = {
                            Icon(
                                imageVector = when (mode) {
                                    ThemeMode.SYSTEM -> Icons.Default.BrightnessAuto
                                    ThemeMode.LIGHT -> Icons.Default.LightMode
                                    ThemeMode.DARK -> Icons.Default.DarkMode
                                },
                                contentDescription = null
                            )
                        },
                        trailingIcon = {
                            if (themeMode == mode) {
                                Icon(Icons.Default.Check, contentDescription = "Selected")
                            }
                        },
                        onClick = {
                            onThemeModeChange(mode)
                            menuExpanded = false
                        }
                    )
                }
                HorizontalDivider()
                DropdownMenuItem(
                    text = { Text("Privacy & data") },
                    leadingIcon = { Icon(Icons.Default.PrivacyTip, contentDescription = null) },
                    onClick = {
                        menuExpanded = false
                        onOpenPrivacy()
                    }
                )
                DropdownMenuItem(
                    text = { Text("Contact support") },
                    leadingIcon = { Icon(Icons.Default.SupportAgent, contentDescription = null) },
                    onClick = {
                        menuExpanded = false
                        onContactSupport()
                    }
                )
            }
        }
    }
}

@Composable
private fun WeeklyGoal(summary: DashboardSummary) {
    val percentage = (summary.progress * 100).roundToInt()

    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            modifier = Modifier
                .size(118.dp)
                .semantics {
                    progressBarRangeInfo = ProgressBarRangeInfo(summary.progress, 0f..1f)
                },
            contentAlignment = Alignment.Center
        ) {
            CircularProgressIndicator(
                progress = { summary.progress },
                modifier = Modifier.fillMaxSize(),
                color = InterpreterCyan,
                trackColor = Color.White.copy(alpha = 0.17f),
                strokeWidth = 11.dp
            )
            Text(
                text = "$percentage%",
                style = MaterialTheme.typography.headlineMedium,
                color = Color.White
            )
        }
        Spacer(Modifier.height(8.dp))
        Text(
            text = "Weekly goal",
            style = MaterialTheme.typography.labelLarge,
            color = Color(0xFFAFC8F6)
        )
        Text(
            text = "${summary.completedThisWeek}/${summary.weeklyGoal} sessions",
            style = MaterialTheme.typography.bodyMedium,
            color = Color.White
        )
    }
}

@Composable
private fun ContinuePracticeCard(
    summary: DashboardSummary,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val latest = summary.latestSession
    val modeName = practiceModeTitle(latest?.practiceMode)
    val sourceName = latest?.sourceName?.takeIf(String::isNotBlank)
        ?: if (latest == null) "Choose your first studio session" else modeName
    val meta = if (latest == null) {
        "Arabic · English · French"
    } else {
        "${shortLanguage(latest.sourceLanguage)} → ${shortLanguage(latest.targetLanguage)}  ·  ${compactDuration(latest.durationMillis)}"
    }

    Card(
        onClick = onClick,
        modifier = modifier.animateContentSize(),
        shape = RoundedCornerShape(26.dp),
        colors = CardDefaults.cardColors(containerColor = Color.Transparent),
        elevation = CardDefaults.cardElevation(defaultElevation = 14.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    Brush.horizontalGradient(
                        listOf(ContinueBlue, Color(0xFF114BAF), Color(0xFF0A2D6D))
                    )
                )
                .padding(horizontal = 18.dp, vertical = 20.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Surface(
                shape = CircleShape,
                color = Color(0xFF0A3F9E).copy(alpha = 0.9f),
                modifier = Modifier.size(72.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        Icons.Default.PlayArrow,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(38.dp)
                    )
                }
            }
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(
                    text = if (latest == null) "Start practicing" else "Continue practice",
                    style = MaterialTheme.typography.titleLarge,
                    color = Color.White
                )
                Text(
                    text = sourceName,
                    style = MaterialTheme.typography.bodyLarge,
                    color = Color(0xFFD7E4FF),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = meta,
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color(0xFFB9D0FB),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Icon(
                Icons.Default.ChevronRight,
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier.size(28.dp)
            )
        }
    }
}

@Composable
private fun PracticeGrid(onNavigate: (String) -> Unit, dark: Boolean) {
    val specs = remember {
        listOf(
            PracticeCardSpec(
                title = "Simultaneous",
                description = "Train real-time comprehension and delivery.",
                icon = Icons.Default.Headphones,
                iconColor = Color(0xFF087F91),
                iconBackground = Color(0xFFBDF4FA),
                route = Routes.SIMULTANEOUS
            ),
            PracticeCardSpec(
                title = "Shadowing",
                description = "Improve fluency and ear–voice coordination.",
                icon = Icons.Default.RecordVoiceOver,
                iconColor = Color(0xFF3E4FD1),
                iconBackground = Color(0xFFDDE1FF),
                route = Routes.SHADOWING
            ),
            PracticeCardSpec(
                title = "Consecutive",
                description = "Strengthen memory and structured delivery.",
                icon = Icons.Default.SkipNext,
                iconColor = Color(0xFF3949C6),
                iconBackground = Color(0xFFE3E3FF),
                route = Routes.CONSECUTIVE
            ),
            PracticeCardSpec(
                title = "Live Transcription",
                description = "Practice with real-time speech to text.",
                icon = Icons.Default.Mic,
                iconColor = Color(0xFF007F93),
                iconBackground = Color(0xFFB8F2F8),
                route = Routes.TRANSCRIPTION
            )
        )
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        contentAlignment = Alignment.TopCenter
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .widthIn(max = 744.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = "Interpretation studio",
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onBackground
            )
            Text(
                text = "Choose a focused practice mode",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                PracticeModeCard(specs[0], dark, Modifier.weight(1f)) { onNavigate(specs[0].route) }
                PracticeModeCard(specs[1], dark, Modifier.weight(1f)) { onNavigate(specs[1].route) }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                PracticeModeCard(specs[2], dark, Modifier.weight(1f)) { onNavigate(specs[2].route) }
                PracticeModeCard(specs[3], dark, Modifier.weight(1f)) { onNavigate(specs[3].route) }
            }
        }
    }
}

@Composable
private fun PracticeModeCard(
    spec: PracticeCardSpec,
    dark: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Card(
        onClick = onClick,
        modifier = modifier.heightIn(min = 194.dp),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = if (dark) CardDark else CardLight),
        border = BorderStroke(
            1.dp,
            if (dark) Color(0xFF263A5B) else Color(0xFFDCE4EF)
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = if (dark) 0.dp else 3.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(17.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Surface(
                modifier = Modifier.size(58.dp),
                shape = CircleShape,
                color = if (dark) spec.iconColor.copy(alpha = 0.24f) else spec.iconBackground
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = spec.icon,
                        contentDescription = null,
                        tint = if (dark) spec.iconBackground else spec.iconColor,
                        modifier = Modifier.size(30.dp)
                    )
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(
                    text = spec.title,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f),
                    maxLines = 2
                )
                Icon(
                    Icons.Default.ChevronRight,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.size(22.dp)
                )
            }
            Text(
                text = spec.description,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun DashboardFooter(dark: Boolean, onOpenPrivacy: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 22.dp),
        contentAlignment = Alignment.Center
    ) {
        Surface(
            onClick = onOpenPrivacy,
            modifier = Modifier
                .fillMaxWidth()
                .widthIn(max = 744.dp),
            shape = RoundedCornerShape(20.dp),
            color = if (dark) CardDark else Color.White,
            border = BorderStroke(
                1.dp,
                if (dark) Color(0xFF263A5B) else Color(0xFFDCE4EF)
            )
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 17.dp, vertical = 14.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Default.Lock, contentDescription = null, tint = InterpreterBlue)
                Column(Modifier.weight(1f)) {
                    Text(
                        "Private by design",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        "Practice records stay on this device.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Icon(
                    Icons.Default.ChevronRight,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun DashboardNavigationBar(
    selectedTab: DashboardTab,
    onHome: () -> Unit,
    onPractice: () -> Unit,
    onAiCoach: () -> Unit,
    onHistory: () -> Unit
) {
    NavigationBar(
        containerColor = DashboardNav,
        tonalElevation = 12.dp
    ) {
        DashboardNavigationItem(
            label = "Home",
            icon = Icons.Default.Home,
            selected = selectedTab == DashboardTab.HOME,
            onClick = onHome
        )
        DashboardNavigationItem(
            label = "Practice",
            icon = Icons.Default.Headphones,
            selected = selectedTab == DashboardTab.PRACTICE,
            onClick = onPractice
        )
        DashboardNavigationItem(
            label = "AI Coach",
            icon = Icons.Default.AutoAwesome,
            selected = false,
            onClick = onAiCoach
        )
        DashboardNavigationItem(
            label = "History",
            icon = Icons.Default.History,
            selected = false,
            onClick = onHistory
        )
    }
}

@Composable
private fun androidx.compose.foundation.layout.RowScope.DashboardNavigationItem(
    label: String,
    icon: ImageVector,
    selected: Boolean,
    onClick: () -> Unit
) {
    NavigationBarItem(
        selected = selected,
        onClick = onClick,
        icon = { Icon(icon, contentDescription = label) },
        label = { Text(label) },
        colors = NavigationBarItemDefaults.colors(
            selectedIconColor = Color(0xFF5C91FF),
            selectedTextColor = Color(0xFF75A2FF),
            unselectedIconColor = Color(0xFFA9BBD9),
            unselectedTextColor = Color(0xFFA9BBD9),
            indicatorColor = Color(0xFF12366F)
        )
    )
}

@Composable
private fun HeroWaves() {
    Canvas(modifier = Modifier.fillMaxSize()) {
        val first = Path().apply {
            moveTo(-size.width * 0.1f, size.height * 0.77f)
            cubicTo(
                size.width * 0.18f,
                size.height * 0.60f,
                size.width * 0.34f,
                size.height * 0.94f,
                size.width * 0.62f,
                size.height * 0.72f
            )
            cubicTo(
                size.width * 0.78f,
                size.height * 0.59f,
                size.width * 0.92f,
                size.height * 0.84f,
                size.width * 1.12f,
                size.height * 0.67f
            )
        }
        drawPath(
            path = first,
            color = Color(0xFF2780FF).copy(alpha = 0.28f),
            style = Stroke(width = 2.dp.toPx())
        )
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(Color(0xFF1E70E8).copy(alpha = 0.26f), Color.Transparent),
                center = Offset(size.width * 0.74f, size.height * 0.30f),
                radius = size.width * 0.48f
            ),
            radius = size.width * 0.48f,
            center = Offset(size.width * 0.74f, size.height * 0.30f)
        )
    }
}

private fun routeForPracticeMode(mode: String?): String = when (mode) {
    PracticeMode.SHADOWING.name -> Routes.SHADOWING
    PracticeMode.CONSECUTIVE_INTERPRETATION.name -> Routes.CONSECUTIVE
    PracticeMode.LIVE_TRANSCRIPTION.name -> Routes.TRANSCRIPTION
    else -> Routes.SIMULTANEOUS
}

private fun practiceModeTitle(mode: String?): String = when (mode) {
    PracticeMode.SHADOWING.name -> "Shadowing"
    PracticeMode.CONSECUTIVE_INTERPRETATION.name -> "Consecutive interpretation"
    PracticeMode.LIVE_TRANSCRIPTION.name -> "Live transcription"
    else -> "Simultaneous interpretation"
}

private fun shortLanguage(tag: String): String = when {
    tag.startsWith("ar", ignoreCase = true) -> "AR"
    tag.startsWith("fr", ignoreCase = true) -> "FR"
    tag.startsWith("en", ignoreCase = true) -> "EN"
    else -> tag.take(2).uppercase()
}

private fun compactDuration(durationMillis: Long): String {
    val minutes = durationMillis / 60_000L
    return if (minutes > 0) "$minutes min" else "< 1 min"
}

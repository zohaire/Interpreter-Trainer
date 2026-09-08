package com.interpretertrainer.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

val InterpreterNavy = Color(0xFF071D48)
val InterpreterBlue = Color(0xFF1768F2)
val InterpreterCyan = Color(0xFF43D7EC)

private val BrandBlueLight = Color(0xFFAEC7FF)
private val BrandBlueContainer = Color(0xFFDCE7FF)
private val BrandBlueDarkContainer = Color(0xFF14356F)
private val SoftBackground = Color(0xFFF4F7FC)
private val DarkBackground = Color(0xFF050D1C)

private val LightScheme = lightColorScheme(
    primary = InterpreterBlue,
    onPrimary = Color.White,
    primaryContainer = BrandBlueContainer,
    onPrimaryContainer = Color(0xFF071A45),
    secondary = Color(0xFF365A91),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFD8E6FF),
    onSecondaryContainer = Color(0xFF0A234A),
    tertiary = Color(0xFF007E91),
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFB7F2FA),
    onTertiaryContainer = Color(0xFF002F37),
    background = SoftBackground,
    onBackground = Color(0xFF101828),
    surface = Color.White,
    onSurface = Color(0xFF101828),
    surfaceVariant = Color(0xFFE7EDF7),
    onSurfaceVariant = Color(0xFF526078),
    outline = Color(0xFF7A889F),
    outlineVariant = Color(0xFFD6DEEA)
)

private val DarkScheme = darkColorScheme(
    primary = BrandBlueLight,
    onPrimary = Color(0xFF002B6C),
    primaryContainer = BrandBlueDarkContainer,
    onPrimaryContainer = Color(0xFFDCE7FF),
    secondary = Color(0xFFAAC7FA),
    onSecondary = Color(0xFF0C315F),
    secondaryContainer = Color(0xFF24466F),
    onSecondaryContainer = Color(0xFFD8E6FF),
    tertiary = Color(0xFF65D8E9),
    onTertiary = Color(0xFF00363F),
    tertiaryContainer = Color(0xFF00505E),
    onTertiaryContainer = Color(0xFFB7F2FA),
    background = DarkBackground,
    onBackground = Color(0xFFE4EBF8),
    surface = Color(0xFF0B172C),
    onSurface = Color(0xFFE4EBF8),
    surfaceVariant = Color(0xFF24324A),
    onSurfaceVariant = Color(0xFFBBC7DA),
    outline = Color(0xFF8795AA),
    outlineVariant = Color(0xFF35445D)
)

private val StudioTypography = Typography(
    displaySmall = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Bold,
        fontSize = 38.sp,
        lineHeight = 42.sp,
        letterSpacing = (-0.5).sp
    ),
    headlineMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Bold,
        fontSize = 28.sp,
        lineHeight = 34.sp
    ),
    headlineSmall = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Bold,
        fontSize = 22.sp,
        lineHeight = 28.sp
    ),
    titleLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 20.sp,
        lineHeight = 26.sp
    ),
    titleMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 16.sp,
        lineHeight = 22.sp
    ),
    bodyLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Normal,
        fontSize = 16.sp,
        lineHeight = 24.sp
    ),
    bodyMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Normal,
        fontSize = 14.sp,
        lineHeight = 20.sp
    ),
    labelLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 14.sp,
        lineHeight = 20.sp
    )
)

@Composable
fun InterpreterTrainerTheme(
    themeMode: ThemeMode = ThemeMode.SYSTEM,
    content: @Composable () -> Unit
) {
    val darkTheme = when (themeMode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }

    MaterialTheme(
        colorScheme = if (darkTheme) DarkScheme else LightScheme,
        typography = StudioTypography,
        content = content
    )
}

package com.interpretertrainer.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val BrandBlue = Color(0xFF1E26A5)
private val BrandBlueLight = Color(0xFFBBC3FF)
private val BrandBlueContainer = Color(0xFFDDE1FF)
private val BrandBlueDarkContainer = Color(0xFF303A9B)
private val SoftBackground = Color(0xFFF9F9FF)
private val DarkBackground = Color(0xFF111318)

private val LightScheme = lightColorScheme(
    primary = BrandBlue,
    onPrimary = Color.White,
    primaryContainer = BrandBlueContainer,
    onPrimaryContainer = Color(0xFF07105F),
    secondary = Color(0xFF565D8E),
    tertiary = Color(0xFF006C7A),
    background = SoftBackground,
    surface = Color(0xFFFEFBFF),
    surfaceVariant = Color(0xFFE3E3EC)
)

private val DarkScheme = darkColorScheme(
    primary = BrandBlueLight,
    onPrimary = Color(0xFF06106D),
    primaryContainer = BrandBlueDarkContainer,
    onPrimaryContainer = Color(0xFFDDE1FF),
    secondary = Color(0xFFBEC4FF),
    tertiary = Color(0xFF83D2E3),
    background = DarkBackground,
    surface = Color(0xFF191B20),
    surfaceVariant = Color(0xFF45464F)
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
        content = content
    )
}

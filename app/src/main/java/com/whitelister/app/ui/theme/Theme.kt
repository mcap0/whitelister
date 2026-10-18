package com.whitelister.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val DarkColorScheme = darkColorScheme(
    primary = Teal80,
    onPrimary = Color(0xFF00363B),
    primaryContainer = TealContainerDark,
    onPrimaryContainer = Teal80,
    secondaryContainer = TealContainerDark,
    onSecondaryContainer = Teal80,
    background = BackgroundDark,
    onBackground = InkDark,
    surface = CardDark,
    onSurface = InkDark,
    surfaceVariant = OutlineDark,
    onSurfaceVariant = MutedDark,
    surfaceContainerHighest = OutlineDark,
    outline = OutlineDark,
    outlineVariant = OutlineDark,
    error = ErrorDark
)

private val LightColorScheme = lightColorScheme(
    primary = Teal40,
    onPrimary = Color.White,
    primaryContainer = TealContainerLight,
    onPrimaryContainer = Color(0xFF00363B),
    secondaryContainer = TealContainerLight,
    onSecondaryContainer = Color(0xFF00363B),
    background = BackgroundLight,
    onBackground = InkLight,
    surface = CardLight,
    onSurface = InkLight,
    surfaceVariant = OutlineLight,
    onSurfaceVariant = MutedLight,
    surfaceContainerHighest = OutlineLight,
    outline = OutlineLight,
    outlineVariant = OutlineLight,
    error = ErrorLight
)

@Composable
fun WhitelisterTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme,
        typography = Typography,
        content = content
    )
}

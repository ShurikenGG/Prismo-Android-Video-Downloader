package com.prismo.downloader

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val DarkColors = darkColorScheme(
    primary = Color(0xFF7CC4FF),
    onPrimary = Color(0xFF00243B),
    primaryContainer = Color(0xFF123B5C),
    onPrimaryContainer = Color(0xFFD2EAFF),
    secondary = Color(0xFFB8C8DA),
    secondaryContainer = Color(0xFF2A3947),
    onSecondaryContainer = Color(0xFFD5E4F7),
    background = Color(0xFF0B0C0F),
    onBackground = Color(0xFFE4E6EA),
    surface = Color(0xFF0B0C0F),
    onSurface = Color(0xFFE4E6EA),
    surfaceVariant = Color(0xFF1A1D23),
    onSurfaceVariant = Color(0xFFA9AFBA),
    outline = Color(0xFF3A404B),
    error = Color(0xFFFF8A80)
)

@Composable
fun FourKTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = DarkColors, typography = Typography(), content = content)
}

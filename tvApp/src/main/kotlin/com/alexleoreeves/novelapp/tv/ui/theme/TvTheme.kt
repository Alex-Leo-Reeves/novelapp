package com.alexleoreeves.novelapp.tv.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

val NeonBlue = Color(0xFF00BFFF)
val NeonCyan = Color(0xFF00E5FF)
val TvBlack = Color(0xFF06060A)
val TvSurface = Color(0xFF0C0C12)
val TvCard = Color(0xFF14141E)
val TvWhite = Color(0xFFFFFFFF)
val TvSubtext = Color(0xFF9CA3AF)
val TvGreen = Color(0xFF06D6A0)

private val DarkColorScheme = darkColorScheme(
    primary = NeonBlue,
    secondary = NeonCyan,
    tertiary = TvGreen,
    background = TvBlack,
    surface = TvSurface,
    surfaceVariant = TvCard,
    onPrimary = Color.White,
    onSecondary = Color.White,
    onBackground = Color.White,
    onSurface = Color.White,
    onSurfaceVariant = TvSubtext,
    outline = Color.White.copy(alpha = 0.1f)
)

@Composable
fun NovaReadTVTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = DarkColorScheme,
        typography = Typography(),
        content = content
    )
}

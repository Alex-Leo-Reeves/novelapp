package com.alexleoreeves.novelapp.ui.theme

import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.*
import androidx.compose.ui.unit.sp
import com.alexleoreeves.novelapp.data.AppTheme

// ─────────────────────────────────────────────────────────────────────────────
//  Color Palettes for each theme
// ─────────────────────────────────────────────────────────────────────────────
object NovelColors {
    // Amethyst Black
    val darkBackground     = Color(0xFF000000)
    val darkSurface        = Color(0xFF0A0A0A)
    val darkCard           = Color(0xFF121212)
    val darkAccent         = Color(0xFF9D4EDD)
    val darkAccentGlow     = Color(0xFFC77DFF)
    val darkText           = Color(0xFFF3F3F3)
    val darkSubText        = Color(0xFF9E9E9E)

    // Rose Black
    val pinkBackground     = Color(0xFF000000)
    val pinkSurface        = Color(0xFF0D0609)
    val pinkCard           = Color(0xFF1A0C12)
    val pinkAccent         = Color(0xFFFF2A85)
    val pinkAccentGlow     = Color(0xFFFF758F)
    val pinkText           = Color(0xFFFFF0F5)
    val pinkSubText        = Color(0xFFB0A0A5)

    // Lavender Black
    val lavenderBackground = Color(0xFF000000)
    val lavenderSurface    = Color(0xFF0A0714)
    val lavenderCard       = Color(0xFF140E28)
    val lavenderAccent     = Color(0xFF8B5CF6)
    val lavenderMint       = Color(0xFF34D399)
    val lavenderText       = Color(0xFFEDE9FE)
    val lavenderSubText    = Color(0xFFA78BFA)

    // Emerald Black
    val greenBackground    = Color(0xFF000000)
    val greenSurface       = Color(0xFF050F08)
    val greenCard          = Color(0xFF0A1F10)
    val greenAccent        = Color(0xFF10B981)
    val greenText          = Color(0xFFECFDF5)
    val greenSubText       = Color(0xFF6EE7B7)

    // Common
    val error              = Color(0xFFCF6679)
    val onError            = Color(0xFFFFFFFF)
}

// ─────────────────────────────────────────────────────────────────────────────
//  Color scheme generators (all dark/black now)
// ─────────────────────────────────────────────────────────────────────────────
fun darkColorScheme() = darkColorScheme(
    background = NovelColors.darkBackground,
    surface = NovelColors.darkSurface,
    primary = NovelColors.darkAccent,
    onPrimary = Color.White,
    onBackground = NovelColors.darkText,
    onSurface = NovelColors.darkText,
    secondary = NovelColors.darkAccentGlow,
    onSecondary = Color.Black,
    error = NovelColors.error,
    onError = NovelColors.onError,
)

fun pinkColorScheme() = darkColorScheme(
    background = NovelColors.pinkBackground,
    surface = NovelColors.pinkSurface,
    primary = NovelColors.pinkAccent,
    onPrimary = Color.White,
    onBackground = NovelColors.pinkText,
    onSurface = NovelColors.pinkText,
    secondary = NovelColors.pinkAccentGlow,
    onSecondary = Color.Black,
    error = NovelColors.error,
    onError = NovelColors.onError,
)

fun lavenderColorScheme() = darkColorScheme(
    background = NovelColors.lavenderBackground,
    surface = NovelColors.lavenderSurface,
    primary = NovelColors.lavenderAccent,
    onPrimary = Color.White,
    onBackground = NovelColors.lavenderText,
    onSurface = NovelColors.lavenderText,
    secondary = NovelColors.lavenderMint,
    onSecondary = Color.Black,
    error = NovelColors.error,
    onError = NovelColors.onError,
)

fun greenColorScheme() = darkColorScheme(
    background = NovelColors.greenBackground,
    surface = NovelColors.greenSurface,
    primary = NovelColors.greenAccent,
    onPrimary = Color.White,
    onBackground = NovelColors.greenText,
    onSurface = NovelColors.greenText,
    secondary = NovelColors.greenAccent,
    onSecondary = Color.Black,
    error = NovelColors.error,
    onError = NovelColors.onError,
)

// ─────────────────────────────────────────────────────────────────────────────
//  App-wide typography (Serif for reader, Sans for UI)
// ─────────────────────────────────────────────────────────────────────────────
val AppTypography = Typography(
    headlineLarge = TextStyle(
        fontFamily = FontFamily.Serif,
        fontSize = 28.sp,
        lineHeight = 34.sp,
        letterSpacing = (-0.5).sp
    ),
    headlineMedium = TextStyle(
        fontFamily = FontFamily.Serif,
        fontSize = 22.sp,
        lineHeight = 28.sp
    ),
    bodyLarge = TextStyle(
        fontFamily = FontFamily.Serif,
        fontSize = 18.sp,
        lineHeight = 30.sp,     // 160% line height — easy on eyes for reading
        letterSpacing = 0.3.sp
    ),
    bodyMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontSize = 14.sp,
        lineHeight = 20.sp,
        letterSpacing = 0.2.sp
    ),
    labelSmall = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontSize = 11.sp,
        letterSpacing = 0.5.sp
    )
)

// ─────────────────────────────────────────────────────────────────────────────
//  App theme composable
// ─────────────────────────────────────────────────────────────────────────────
@Composable
fun NovelAppTheme(
    appTheme: AppTheme = AppTheme.DARK,
    content: @Composable () -> Unit
) {
    val colorScheme = when (appTheme) {
        AppTheme.DARK          -> darkColorScheme()
        AppTheme.WHITE_PINK    -> pinkColorScheme()
        AppTheme.LAVENDER_MINT -> lavenderColorScheme()
        AppTheme.GREEN         -> greenColorScheme()
    }
    MaterialTheme(
        colorScheme = colorScheme,
        typography = AppTypography,
        content = content
    )
}

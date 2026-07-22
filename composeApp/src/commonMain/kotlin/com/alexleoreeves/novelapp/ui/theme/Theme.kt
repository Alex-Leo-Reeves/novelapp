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
    // Neon Black — pure dark with blue accents (default)
    val darkBackground     = Color(0xFF000000)
    val darkSurface        = Color(0xFF050505)
    val darkCard           = Color(0xFF0F1923)
    val darkAccent         = Color(0xFF00BFFF)
    val darkAccentGlow     = Color(0xFF00E5FF)
    val darkText           = Color(0xFFF3F3F3)
    val darkSubText        = Color(0xFF8A9BB5)

    // Cyber Black — same base, cyan-blue accents
    val pinkBackground     = Color(0xFF000000)
    val pinkSurface        = Color(0xFF050A0F)
    val pinkCard           = Color(0xFF0F1A26)
    val pinkAccent         = Color(0xFF00BFFF)
    val pinkAccentGlow     = Color(0xFF00E5FF)
    val pinkText           = Color(0xFFF0F6FF)
    val pinkSubText        = Color(0xFF8A9BB5)

    // Deep Blue — darker with deeper blue accents
    val lavenderBackground = Color(0xFF000000)
    val lavenderSurface    = Color(0xFF05080F)
    val lavenderCard       = Color(0xFF0A1428)
    val lavenderAccent     = Color(0xFF2196F3)
    val lavenderMint       = Color(0xFF03DAC6)
    val lavenderText       = Color(0xFFEDF2FF)
    val lavenderSubText    = Color(0xFF7A9BFF)

    // Electric Black — slightly different blue accent
    val greenBackground    = Color(0xFF000000)
    val greenSurface       = Color(0xFF050808)
    val greenCard          = Color(0xFF0C1A1A)
    val greenAccent        = Color(0xFF00BCD4)
    val greenText          = Color(0xFFECFDF5)
    val greenSubText       = Color(0xFF6EE7B7)

    // Common
    val error              = Color(0xFFEF4444)
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

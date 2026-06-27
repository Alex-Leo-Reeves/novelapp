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
    // Dark Theme
    val darkBackground    = Color(0xFF0D0D0D)
    val darkSurface       = Color(0xFF1A1A2E)
    val darkCard          = Color(0xFF16213E)
    val darkAccent        = Color(0xFF7B2FBE)
    val darkAccentGlow    = Color(0xFFAB6BEB)
    val darkText          = Color(0xFFE8E8E8)
    val darkSubText       = Color(0xFF9E9E9E)

    // White-Pink Theme
    val pinkBackground    = Color(0xFFFFF0F5)
    val pinkSurface       = Color(0xFFFFE4EF)
    val pinkCard          = Color(0xFFFFD6E7)
    val pinkAccent        = Color(0xFFE91E8C)
    val pinkText          = Color(0xFF2C2C2C)
    val pinkSubText       = Color(0xFF666666)

    // Lavender Mint Theme
    val lavenderBackground = Color(0xFFF0ECF8)
    val lavenderSurface    = Color(0xFFE6E0F5)
    val lavenderCard       = Color(0xFFD8D0EE)
    val lavenderAccent     = Color(0xFF6A5ACD)
    val lavenderMint       = Color(0xFF98E4B0)
    val lavenderText       = Color(0xFF1A1A3A)
    val lavenderSubText    = Color(0xFF4A4A6A)

    // Green Theme
    val greenBackground    = Color(0xFFE8F5E9)
    val greenSurface       = Color(0xFFDCEFDD)
    val greenCard          = Color(0xFFCCE5CD)
    val greenAccent        = Color(0xFF2E7D32)
    val greenText          = Color(0xFF1B301B)
    val greenSubText       = Color(0xFF3E6B3E)

    // Common
    val error              = Color(0xFFCF6679)
    val onError            = Color(0xFFFFFFFF)
}

// ─────────────────────────────────────────────────────────────────────────────
//  Color scheme generators
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

fun pinkColorScheme() = lightColorScheme(
    background = NovelColors.pinkBackground,
    surface = NovelColors.pinkSurface,
    primary = NovelColors.pinkAccent,
    onPrimary = Color.White,
    onBackground = NovelColors.pinkText,
    onSurface = NovelColors.pinkText,
    secondary = NovelColors.pinkAccent,
    onSecondary = Color.White,
)

fun lavenderColorScheme() = lightColorScheme(
    background = NovelColors.lavenderBackground,
    surface = NovelColors.lavenderSurface,
    primary = NovelColors.lavenderAccent,
    onPrimary = Color.White,
    onBackground = NovelColors.lavenderText,
    onSurface = NovelColors.lavenderText,
    secondary = NovelColors.lavenderMint,
    onSecondary = NovelColors.lavenderText,
)

fun greenColorScheme() = lightColorScheme(
    background = NovelColors.greenBackground,
    surface = NovelColors.greenSurface,
    primary = NovelColors.greenAccent,
    onPrimary = Color.White,
    onBackground = NovelColors.greenText,
    onSurface = NovelColors.greenText,
    secondary = NovelColors.greenAccent,
    onSecondary = Color.White,
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

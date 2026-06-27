package com.alexleoreeves.novelapp.ui.theme

import com.alexleoreeves.novelapp.data.AppTheme

// Re-export surface and subText color helpers so all screens can use them
fun AppTheme.surfaceColor() = when (this) {
    AppTheme.DARK -> NovelColors.darkSurface
    AppTheme.WHITE_PINK -> NovelColors.pinkSurface
    AppTheme.LAVENDER_MINT -> NovelColors.lavenderSurface
    AppTheme.GREEN -> NovelColors.greenSurface
}

fun AppTheme.subTextColor() = when (this) {
    AppTheme.DARK -> NovelColors.darkSubText
    AppTheme.WHITE_PINK -> NovelColors.pinkSubText
    AppTheme.LAVENDER_MINT -> NovelColors.lavenderSubText
    AppTheme.GREEN -> NovelColors.greenSubText
}

fun AppTheme.accentColor() = when (this) {
    AppTheme.DARK -> NovelColors.darkAccent
    AppTheme.WHITE_PINK -> NovelColors.pinkAccent
    AppTheme.LAVENDER_MINT -> NovelColors.lavenderAccent
    AppTheme.GREEN -> NovelColors.greenAccent
}

fun AppTheme.backgroundColor() = when (this) {
    AppTheme.DARK -> NovelColors.darkBackground
    AppTheme.WHITE_PINK -> NovelColors.pinkBackground
    AppTheme.LAVENDER_MINT -> NovelColors.lavenderBackground
    AppTheme.GREEN -> NovelColors.greenBackground
}

fun AppTheme.textColor() = when (this) {
    AppTheme.DARK -> NovelColors.darkText
    AppTheme.WHITE_PINK -> NovelColors.pinkText
    AppTheme.LAVENDER_MINT -> NovelColors.lavenderText
    AppTheme.GREEN -> NovelColors.greenText
}

fun AppTheme.cardColor() = when (this) {
    AppTheme.DARK -> NovelColors.darkCard
    AppTheme.WHITE_PINK -> NovelColors.pinkCard
    AppTheme.LAVENDER_MINT -> NovelColors.lavenderCard
    AppTheme.GREEN -> NovelColors.greenCard
}

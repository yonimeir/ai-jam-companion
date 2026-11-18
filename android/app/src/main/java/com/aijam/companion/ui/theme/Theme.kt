package com.aijam.companion.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * Warm Velvet & Steinway Amber Studio Color Palette (inspired by Apple Music Sing & Moises Studio).
 */
object StageColors {
    val Obsidian = Color(0xFF09080D)
    val DeepSlate = Color(0xFF121018)
    val Charcoal = Color(0xFF1A1723)
    val Elevated = Color(0xFF242031)
    val Hairline = Color(0xFF302A40)

    val Gold = Color(0xFFF6B93B)
    val Brass = Color(0xFFE58E26)
    val Cyan = Color(0xFF7DD3FC)
    val Emerald = Color(0xFF34D399)
    val Coral = Color(0xFFF472B6)

    val TextPrimary = Color(0xFFFAF7F2)
    val TextSecondary = Color(0xFFD8D3E2)
    val TextMuted = Color(0xFF8E86A0)
}

private val MidnightStageColorScheme = darkColorScheme(
    primary = StageColors.Gold,
    onPrimary = StageColors.Obsidian,
    secondary = StageColors.Cyan,
    onSecondary = StageColors.Obsidian,
    tertiary = StageColors.Emerald,
    background = StageColors.Obsidian,
    onBackground = StageColors.TextPrimary,
    surface = StageColors.Charcoal,
    onSurface = StageColors.TextPrimary,
    surfaceVariant = StageColors.Elevated,
    onSurfaceVariant = StageColors.TextSecondary,
    outline = StageColors.Hairline,
    error = StageColors.Coral
)

private val StageTypography = Typography(
    displayLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Black,
        fontSize = 46.sp,
        letterSpacing = (-1.2).sp,
        color = StageColors.TextPrimary
    ),
    headlineMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.ExtraBold,
        fontSize = 23.sp,
        color = StageColors.TextPrimary
    ),
    titleMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 15.sp,
        color = StageColors.TextPrimary
    ),
    bodyMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Normal,
        fontSize = 14.sp,
        color = StageColors.TextSecondary
    ),
    labelSmall = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Medium,
        fontSize = 11.sp,
        letterSpacing = 0.3.sp,
        color = StageColors.TextMuted
    )
)

@Composable
fun AIJamStageTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = MidnightStageColorScheme,
        typography = StageTypography,
        content = content
    )
}

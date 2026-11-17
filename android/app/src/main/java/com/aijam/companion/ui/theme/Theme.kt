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

object StageColors {
    val Obsidian = Color(0xFF07090F)
    val DeepSlate = Color(0xFF0C111D)
    val Charcoal = Color(0xFF111827)
    val Elevated = Color(0xFF182135)
    val Hairline = Color(0xFF24304A)

    val Gold = Color(0xFFF5B041)
    val Brass = Color(0xFFD98B2B)
    val Cyan = Color(0xFF38BDF8)
    val Emerald = Color(0xFF34D399)
    val Coral = Color(0xFFFB7185)

    val TextPrimary = Color(0xFFF8FAFC)
    val TextSecondary = Color(0xFFCBD5E1)
    val TextMuted = Color(0xFF7D8EA8)
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
        fontSize = 44.sp,
        letterSpacing = (-1).sp,
        color = StageColors.TextPrimary
    ),
    headlineMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Bold,
        fontSize = 22.sp,
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
        letterSpacing = 0.4.sp,
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

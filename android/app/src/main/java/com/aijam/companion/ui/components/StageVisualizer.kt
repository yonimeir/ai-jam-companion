package com.aijam.companion.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aijam.companion.model.EngineMode
import com.aijam.companion.model.LiveJamState
import com.aijam.companion.ui.theme.StageColors
import kotlin.math.abs
import kotlin.math.ceil

/**
 * Sleek Live Harmonic & Vocal Stage Deck (Apple Music Sing × Moises Studio aesthetic):
 * - Sculptural Current Chord + Hebrew chord quality + Next Chord preview ("הבא: A")
 * - 4-Beat visual pulse metronome & Bar counter
 * - Smooth background Melodic Pitch Horizon & live vocal waveform
 * - Vocal Entry Countdown banner ("כניסת שירה בעוד 3...") during instrumental intros/gaps
 */
@Composable
fun StageVisualizer(
    state: LiveJamState,
    modifier: Modifier = Modifier
) {
    val sectionColor = sectionAccentColor(state.currentSection)
    val modeAccent by animateColorAsState(
        targetValue = when (state.engineMode) {
            EngineMode.VAMPING -> StageColors.Coral
            EngineMode.CONFIDENCE_WAIT -> StageColors.Cyan
            EngineMode.PLAYING -> sectionColor
        },
        animationSpec = tween(320),
        label = "modeAccent"
    )

    val beatPulseAnim by animateFloatAsState(
        targetValue = if (state.isRunning) state.beatPulse else 0f,
        animationSpec = spring(stiffness = 380f, dampingRatio = 0.68f),
        label = "beatPulse"
    )

    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(22.dp),
        color = StageColors.DeepSlate,
        border = BorderStroke(1.dp, modeAccent.copy(alpha = 0.24f + 0.16f * beatPulseAnim))
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(136.dp)
                .background(
                    Brush.horizontalGradient(
                        colors = listOf(
                            modeAccent.copy(alpha = 0.14f + 0.10f * beatPulseAnim),
                            StageColors.DeepSlate,
                            StageColors.Obsidian
                        )
                    )
                )
        ) {
            // Subtle background Melodic Pitch Horizon & Live Waveform (LTR)
            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                Canvas(modifier = Modifier.fillMaxSize()) {
                    val w = size.width
                    val h = size.height
                    val targets = state.targetPitchTrail
                    val singerPitches = state.pitchTrail
                    val wave = state.waveform

                    val horizonTop = h * 0.25f
                    val horizonBottom = h * 0.85f
                    val minMidi = 50f
                    val maxMidi = 78f

                    fun midiToY(midi: Float): Float {
                        val norm = ((midi - minMidi) / (maxMidi - minMidi)).coerceIn(0f, 1f)
                        return horizonBottom - norm * (horizonBottom - horizonTop)
                    }

                    // Target melody contour (subtle warm amber dashed curve)
                    if (targets.size >= 2) {
                        val targetPath = Path()
                        var started = false
                        val stepX = w / (targets.size - 1).coerceAtLeast(1)
                        targets.forEachIndexed { idx, midi ->
                            if (midi > 20f) {
                                val pt = Offset(idx * stepX, midiToY(midi))
                                if (!started) {
                                    targetPath.moveTo(pt.x, pt.y)
                                    started = true
                                } else {
                                    targetPath.lineTo(pt.x, pt.y)
                                }
                            } else {
                                started = false
                            }
                        }
                        drawPath(
                            path = targetPath,
                            color = StageColors.Gold.copy(alpha = 0.26f),
                            style = Stroke(
                                width = 1.8.dp.toPx(),
                                cap = StrokeCap.Round,
                                pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 8f), 0f)
                            )
                        )
                    }

                    // Live singer pitch trail
                    if (singerPitches.size >= 2) {
                        val singerPath = Path()
                        var started = false
                        val stepX = w / (singerPitches.size - 1).coerceAtLeast(1)
                        var lastPt: Offset? = null
                        singerPitches.forEachIndexed { idx, midi ->
                            if (midi > 20f) {
                                val pt = Offset(idx * stepX, midiToY(midi))
                                if (!started) {
                                    singerPath.moveTo(pt.x, pt.y)
                                    started = true
                                } else {
                                    singerPath.lineTo(pt.x, pt.y)
                                }
                                lastPt = pt
                            } else {
                                started = false
                            }
                        }
                        drawPath(
                            path = singerPath,
                            color = StageColors.Cyan.copy(alpha = 0.55f),
                            style = Stroke(
                                width = 2.4.dp.toPx(),
                                cap = StrokeCap.Round,
                                join = StrokeJoin.Round
                            )
                        )
                        lastPt?.let { tip ->
                            val locked = state.isVoiced &&
                                state.targetMidi > 20f &&
                                abs(state.singerMidi - state.targetMidi) <= 1.8f
                            val tipColor = if (locked) StageColors.Emerald else StageColors.Cyan
                            drawCircle(
                                color = tipColor.copy(alpha = 0.28f),
                                radius = 9.dp.toPx(),
                                center = tip
                            )
                            drawCircle(
                                color = tipColor,
                                radius = 4.dp.toPx(),
                                center = tip
                            )
                        }
                    }

                    // Bottom delicate audio wave ribbon
                    if (wave.size >= 4) {
                        val baseY = h * 0.90f
                        val wavePath = Path()
                        val step = w / (wave.size - 1)
                        val ampScale = 14.dp.toPx()
                        wavePath.moveTo(0f, baseY)
                        for (i in wave.indices) {
                            val x = i * step
                            val y = baseY - wave[i].coerceIn(-1f, 1f) * ampScale
                            wavePath.lineTo(x, y)
                        }
                        drawPath(
                            path = wavePath,
                            color = modeAccent.copy(alpha = 0.32f),
                            style = Stroke(width = 1.5.dp.toPx(), cap = StrokeCap.Round)
                        )
                    }
                }
            }

            // Foreground HUD content
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.SpaceBetween
            ) {
                // Main Row: Current Chord -> Next Chord on one side, Beat Metronome & Section on the other
                CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // Left: Current Chord + Next Chord Preview
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Column {
                                Row(verticalAlignment = Alignment.Bottom) {
                                    Text(
                                        text = state.currentChord,
                                        color = StageColors.TextPrimary,
                                        fontSize = 42.sp,
                                        fontWeight = FontWeight.Black,
                                        fontFamily = FontFamily.SansSerif,
                                        letterSpacing = (-1).sp,
                                        lineHeight = 42.sp
                                    )
                                    Spacer(modifier = Modifier.width(10.dp))
                                    // Next Chord Pill
                                    Surface(
                                        color = StageColors.Elevated.copy(alpha = 0.88f),
                                        shape = RoundedCornerShape(10.dp),
                                        border = BorderStroke(0.8.dp, StageColors.Hairline)
                                    ) {
                                        Row(
                                            modifier = Modifier.padding(horizontal = 9.dp, vertical = 4.dp),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Text(
                                                text = "הבא:",
                                                color = StageColors.TextMuted,
                                                fontSize = 11.sp,
                                                fontWeight = FontWeight.Medium
                                            )
                                            Spacer(modifier = Modifier.width(5.dp))
                                            Text(
                                                text = state.nextChord,
                                                color = StageColors.Gold,
                                                fontSize = 14.sp,
                                                fontWeight = FontWeight.ExtraBold
                                            )
                                        }
                                    }
                                }
                                Spacer(modifier = Modifier.height(2.dp))
                                Text(
                                    text = state.chordQuality,
                                    color = modeAccent,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.SemiBold
                                )
                            }
                        }

                        // Right: 4-Beat Visual Metronome + Bar & Pitch badge
                        Column(horizontalAlignment = Alignment.End) {
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                for (beat in 1..4) {
                                    val isActive = state.currentBeat == beat
                                    val dotColor = when {
                                        isActive && beat == 1 -> StageColors.Gold
                                        isActive -> modeAccent
                                        else -> StageColors.Elevated
                                    }
                                    Surface(
                                        modifier = Modifier.size(if (isActive) 22.dp else 18.dp),
                                        shape = CircleShape,
                                        color = if (isActive) dotColor.copy(alpha = 0.24f) else StageColors.Elevated.copy(alpha = 0.6f),
                                        border = BorderStroke(
                                            width = if (isActive) 1.5.dp else 0.8.dp,
                                            color = if (isActive) dotColor else StageColors.Hairline
                                        )
                                    ) {
                                        Box(contentAlignment = Alignment.Center) {
                                            Text(
                                                text = "$beat",
                                                color = if (isActive) dotColor else StageColors.TextMuted,
                                                fontSize = 10.sp,
                                                fontWeight = FontWeight.ExtraBold,
                                                fontFamily = FontFamily.Monospace
                                            )
                                        }
                                    }
                                }
                            }
                            Spacer(modifier = Modifier.height(6.dp))
                            Text(
                                text = "תיבה ${state.currentBar} מתוך ${state.totalBars}",
                                color = StageColors.TextSecondary,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    }
                }

                // Bottom Strip: Vocal Entry Countdown OR Current Section + Live Pitch status
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (state.secondsToVocalEntry in 0.2f..14.0f) {
                        val secsLeft = ceil(state.secondsToVocalEntry.toDouble()).toInt()
                        Surface(
                            color = StageColors.Gold.copy(alpha = 0.18f),
                            shape = RoundedCornerShape(999.dp),
                            border = BorderStroke(1.dp, StageColors.Gold.copy(alpha = 0.6f))
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(7.dp)
                                        .clip(CircleShape)
                                        .background(StageColors.Gold)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = "כניסת שירה בעוד $secsLeft שניות...",
                                    color = StageColors.TextPrimary,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                    } else {
                        Text(
                            text = state.currentSection,
                            color = StageColors.TextSecondary,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }

                    // Right side: Live vocal note or Audio Output Mode badge
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        if (state.isVoiced && state.singerF0Hz > 0f) {
                            Surface(
                                color = StageColors.Cyan.copy(alpha = 0.15f),
                                shape = RoundedCornerShape(999.dp)
                            ) {
                                Text(
                                    text = "🎤 ${state.singerNoteName}",
                                    color = StageColors.Cyan,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold,
                                    fontFamily = FontFamily.Monospace,
                                    modifier = Modifier.padding(horizontal = 9.dp, vertical = 3.dp)
                                )
                            }
                        } else {
                            Text(
                                text = state.audioOutputMode.labelHe,
                                color = StageColors.TextMuted,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Medium
                            )
                        }
                    }
                }
            }
        }
    }
}

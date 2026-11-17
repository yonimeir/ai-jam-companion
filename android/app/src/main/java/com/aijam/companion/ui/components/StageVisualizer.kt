package com.aijam.companion.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
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
import androidx.compose.material3.LinearProgressIndicator
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
import androidx.compose.ui.graphics.Color
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
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/**
 * 60fps Audio-Reactive Stage Canvas combining:
 * - Harmonic halo rings pulsing with beat & drum energy
 * - Radial spectrum petals reactive to live waveform
 * - Live vocal waveform trace
 * - Melodic Pitch Horizon comparing Target Melody contour vs. Singer Pitch Trail
 * - Sculptural Chord Emblem + 4-Beat LED bar + Confidence/Dynamics meters
 */
@Composable
fun StageVisualizer(
    state: LiveJamState,
    modifier: Modifier = Modifier
) {
    val modeAccent by animateColorAsState(
        targetValue = when (state.engineMode) {
            EngineMode.PLAYING -> StageColors.Gold
            EngineMode.VAMPING -> StageColors.Coral
            EngineMode.CONFIDENCE_WAIT -> StageColors.Cyan
        },
        animationSpec = tween(320),
        label = "modeAccent"
    )

    val beatPulseAnim by animateFloatAsState(
        targetValue = if (state.isRunning) state.beatPulse else 0f,
        animationSpec = spring(stiffness = 380f, dampingRatio = 0.65f),
        label = "beatPulse"
    )
    val drumPulseAnim by animateFloatAsState(
        targetValue = if (state.isRunning) state.drumPulse else 0f,
        animationSpec = spring(stiffness = 440f, dampingRatio = 0.7f),
        label = "drumPulse"
    )

    val infiniteTransition = rememberInfiniteTransition(label = "stageSpin")
    val ambientPhase by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = (2f * PI).toFloat(),
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 9000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "ambientPhase"
    )

    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        color = StageColors.Charcoal,
        border = BorderStroke(1.dp, modeAccent.copy(alpha = 0.28f))
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(232.dp)
                .background(
                    Brush.radialGradient(
                        colors = listOf(
                            modeAccent.copy(alpha = 0.14f + 0.12f * beatPulseAnim),
                            StageColors.DeepSlate,
                            StageColors.Obsidian
                        )
                    )
                )
        ) {
            // Musical canvas forced LTR so time/melody flows naturally left -> right
            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                Canvas(modifier = Modifier.fillMaxSize()) {
                    val w = size.width
                    val h = size.height
                    val center = Offset(w * 0.5f, h * 0.45f)
                    val baseRadius = minOf(w, h) * 0.29f

                    // 1. Subtle Horizon Grid
                    for (row in 1..3) {
                        val y = h * (0.24f * row)
                        drawLine(
                            color = StageColors.Hairline.copy(alpha = 0.35f),
                            start = Offset(0f, y),
                            end = Offset(w, y),
                            strokeWidth = 1f
                        )
                    }

                    // 2. Reactive Concentric Harmonic Rings & Acoustic Petals
                    val ringRadius = baseRadius + beatPulseAnim * 18.dp.toPx()
                    drawCircle(
                        color = modeAccent.copy(alpha = 0.16f + 0.22f * beatPulseAnim),
                        radius = ringRadius * 1.28f,
                        center = center,
                        style = Stroke(width = 1.5.dp.toPx())
                    )
                    drawCircle(
                        color = StageColors.Cyan.copy(alpha = 0.14f + 0.20f * drumPulseAnim),
                        radius = ringRadius * 0.96f,
                        center = center,
                        style = Stroke(width = 1.2.dp.toPx())
                    )

                    val spokeCount = 36
                    val wave = state.waveform
                    for (i in 0 until spokeCount) {
                        val angle = ambientPhase + (i.toFloat() / spokeCount) * (2f * PI.toFloat())
                        val waveIdx = if (wave.isNotEmpty()) (i * wave.size / spokeCount) % wave.size else 0
                        val energy = if (state.isRunning && wave.isNotEmpty()) {
                            abs(wave[waveIdx]).coerceIn(0f, 1f)
                        } else {
                            0.08f
                        }
                        val innerR = ringRadius * 0.88f
                        val outerR = innerR + (6f + energy * 34f + beatPulseAnim * 10f).dp.toPx()
                        val sx = center.x + cos(angle) * innerR
                        val sy = center.y + sin(angle) * innerR
                        val ex = center.x + cos(angle) * outerR
                        val ey = center.y + sin(angle) * outerR
                        drawLine(
                            color = modeAccent.copy(alpha = 0.22f + energy * 0.65f),
                            start = Offset(sx, sy),
                            end = Offset(ex, ey),
                            strokeWidth = 2.2.dp.toPx(),
                            cap = StrokeCap.Round
                        )
                    }

                    // 3. Target Melody Contour (Warm Brass Dashed Curve) vs. Singer Pitch Trail (Electric Cyan)
                    val targets = state.targetPitchTrail
                    val singerPitches = state.pitchTrail
                    val horizonTop = h * 0.12f
                    val horizonBottom = h * 0.72f
                    val minMidi = 45f
                    val maxMidi = 81f

                    fun midiToY(midi: Float): Float {
                        val norm = ((midi - minMidi) / (maxMidi - minMidi)).coerceIn(0f, 1f)
                        return horizonBottom - norm * (horizonBottom - horizonTop)
                    }

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
                            color = StageColors.Gold.copy(alpha = 0.50f),
                            style = Stroke(
                                width = 2.dp.toPx(),
                                cap = StrokeCap.Round,
                                pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 8f), 0f)
                            )
                        )
                    }

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
                            color = StageColors.Cyan,
                            style = Stroke(
                                width = 3.dp.toPx(),
                                cap = StrokeCap.Round,
                                join = StrokeJoin.Round
                            )
                        )
                        lastPt?.let { tip ->
                            val isLockedOnPitch = state.isVoiced &&
                                state.targetMidi > 20f &&
                                abs(state.singerMidi - state.targetMidi) <= 1.8f
                            val tipColor = if (isLockedOnPitch) StageColors.Emerald else StageColors.Cyan
                            drawCircle(
                                color = tipColor.copy(alpha = 0.32f),
                                radius = 11.dp.toPx(),
                                center = tip
                            )
                            drawCircle(
                                color = tipColor,
                                radius = 4.5.dp.toPx(),
                                center = tip
                            )
                        }
                    }

                    // 4. Bottom Live Vocal Waveform Ribbon
                    if (wave.size >= 4) {
                        val waveBaseY = h * 0.84f
                        val wavePath = Path()
                        val step = w / (wave.size - 1)
                        val ampScale = 26.dp.toPx()
                        wavePath.moveTo(0f, waveBaseY)
                        for (i in wave.indices) {
                            val x = i * step
                            val y = waveBaseY - wave[i].coerceIn(-1f, 1f) * ampScale
                            wavePath.lineTo(x, y)
                        }
                        drawPath(
                            path = wavePath,
                            color = if (state.isVoiced) StageColors.Cyan.copy(alpha = 0.85f)
                            else StageColors.Hairline.copy(alpha = 0.8f),
                            style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round)
                        )
                    }
                }
            }

            // Foreground HUD & Center Chord Emblem
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.SpaceBetween
            ) {
                // Top Telemetry Row: Section + Bar/Total + Engine Mode Badge
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Surface(
                        color = StageColors.Elevated.copy(alpha = 0.86f),
                        shape = RoundedCornerShape(999.dp),
                        border = BorderStroke(1.dp, StageColors.Hairline)
                    ) {
                        Text(
                            text = "${state.currentSection} • תיבה ${state.currentBar}/${state.totalBars}",
                            color = StageColors.TextSecondary,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                        )
                    }

                    Surface(
                        color = modeAccent.copy(alpha = 0.16f),
                        shape = RoundedCornerShape(999.dp),
                        border = BorderStroke(1.dp, modeAccent.copy(alpha = 0.45f))
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(7.dp)
                                    .clip(CircleShape)
                                    .background(modeAccent)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = state.engineMode.labelHe,
                                color = modeAccent,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }

                // Center Chord Emblem + Beat LEDs
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = state.currentChord,
                        color = StageColors.TextPrimary,
                        fontSize = 46.sp,
                        fontWeight = FontWeight.Black,
                        fontFamily = FontFamily.SansSerif,
                        letterSpacing = (-1).sp
                    )
                    Text(
                        text = state.chordQuality,
                        color = modeAccent.copy(alpha = 0.88f),
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Medium
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    // 4-Beat LED bar (LTR)
                    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(7.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            for (beat in 1..4) {
                                val isActive = state.currentBeat == beat
                                Box(
                                    modifier = Modifier
                                        .size(if (isActive) 10.dp else 7.dp)
                                        .clip(CircleShape)
                                        .background(
                                            when {
                                                isActive && beat == 1 -> StageColors.Gold
                                                isActive -> StageColors.Cyan
                                                else -> StageColors.Hairline
                                            }
                                        )
                                )
                            }
                        }
                    }
                }

                // Bottom Telemetry Strip: Singer Pitch + Confidence + Dynamics
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Singer Pitch readout
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = if (state.isVoiced) state.singerNoteName else "---",
                            color = if (state.isVoiced) StageColors.Cyan else StageColors.TextMuted,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.Monospace
                        )
                        if (state.isVoiced && state.singerF0Hz > 0f) {
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = "${state.singerF0Hz.toInt()}Hz",
                                color = StageColors.TextMuted,
                                fontSize = 11.sp,
                                fontFamily = FontFamily.Monospace
                            )
                        }
                    }

                    // Alignment Confidence & Tempo Ratio
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Column(horizontalAlignment = Alignment.End) {
                            Text(
                                text = "דיוק ${(state.confidence * 100).toInt()}% • x${"%.2f".format(state.tempoRatio)}",
                                color = StageColors.TextMuted,
                                fontSize = 10.sp,
                                fontFamily = FontFamily.Monospace
                            )
                            Spacer(modifier = Modifier.height(3.dp))
                            LinearProgressIndicator(
                                progress = { state.confidence.coerceIn(0f, 1f) },
                                modifier = Modifier
                                    .width(86.dp)
                                    .height(4.dp)
                                    .clip(RoundedCornerShape(999.dp)),
                                color = if (state.confidence >= 0.55f) StageColors.Emerald else StageColors.Coral,
                                trackColor = StageColors.Elevated
                            )
                        }
                    }
                }
            }
        }
    }
}

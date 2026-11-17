package com.aijam.companion.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import com.aijam.companion.ui.theme.StageColors

/**
 * Interactive 2-octave (C3..B4) illuminated stage keyboard forced LTR.
 * Displays active accompaniment keys + chord target dots, and allows tap-to-play.
 */
@Composable
fun PianoKeyboard(
    activeKeysMidi: Set<Int>,
    chordNotes: List<Int>,
    onTapKey: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    // Map active notes (including bass notes < 48 or high notes > 71) cleanly onto C3..B4 (48..71)
    val litVisibleNotes = buildSet {
        for (m in activeKeysMidi) {
            var folded = m
            while (folded < 48) folded += 12
            while (folded > 71) folded -= 12
            add(folded)
        }
    }
    val chordPitchClasses = chordNotes.map { ((it % 12) + 12) % 12 }.toSet()

    val whiteSteps = listOf(0, 2, 4, 5, 7, 9, 11)
    val blackOffsets = mapOf(1 to 0, 3 to 1, 6 to 3, 8 to 4, 10 to 5)

    val whiteMidiNotes = buildList {
        for (oct in 0..1) {
            val base = 48 + oct * 12
            for (step in whiteSteps) add(base + step)
        }
    }

    val blackMidiEntries = buildList {
        for (oct in 0..1) {
            val base = 48 + oct * 12
            blackOffsets.forEach { (semi, whiteIdxInOct) ->
                val globalWhiteIdx = oct * 7 + whiteIdxInOct
                add((base + semi) to globalWhiteIdx)
            }
        }
    }

    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        color = StageColors.Charcoal,
        border = BorderStroke(1.dp, StageColors.Hairline)
    ) {
        CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
            BoxWithConstraints(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(82.dp)
                    .padding(6.dp)
            ) {
                val whiteWidth = maxWidth / 14f
                val blackWidth = whiteWidth * 0.62f
                val blackHeight = maxHeight * 0.60f

                // White Keys Row
                Row(modifier = Modifier.fillMaxSize()) {
                    whiteMidiNotes.forEach { midi ->
                        val isLit = midi in litVisibleNotes
                        val isChordTone = (midi % 12) in chordPitchClasses
                        val isC = midi % 12 == 0

                        val keyBrush = if (isLit) {
                            Brush.verticalGradient(
                                listOf(StageColors.Gold, StageColors.Brass)
                            )
                        } else {
                            Brush.verticalGradient(
                                listOf(Color(0xFFF1F5F9), Color(0xFFD8E1ED))
                            )
                        }

                        Box(
                            modifier = Modifier
                                .width(whiteWidth)
                                .fillMaxHeight()
                                .padding(horizontal = 1.dp)
                                .clip(RoundedCornerShape(bottomStart = 6.dp, bottomEnd = 6.dp, topStart = 3.dp, topEnd = 3.dp))
                                .background(keyBrush)
                                .border(
                                    width = 0.8.dp,
                                    color = if (isLit) StageColors.Gold else Color(0xFF94A3B8).copy(alpha = 0.4f),
                                    shape = RoundedCornerShape(bottomStart = 6.dp, bottomEnd = 6.dp, topStart = 3.dp, topEnd = 3.dp)
                                )
                                .clickable { onTapKey(midi) },
                            contentAlignment = Alignment.BottomCenter
                        ) {
                            if (isChordTone && !isLit) {
                                Box(
                                    modifier = Modifier
                                        .padding(bottom = 12.dp)
                                        .size(4.dp)
                                        .clip(CircleShape)
                                        .background(StageColors.Cyan.copy(alpha = 0.75f))
                                )
                            }
                            if (isC) {
                                Text(
                                    text = "C${midi / 12 - 1}",
                                    color = if (isLit) StageColors.Obsidian else Color(0xFF475569),
                                    fontSize = 8.sp,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.padding(bottom = 2.dp)
                                )
                            }
                        }
                    }
                }

                // Black Keys Overlay
                blackMidiEntries.forEach { (midi, whiteIdx) ->
                    val isLit = midi in litVisibleNotes
                    val isChordTone = (midi % 12) in chordPitchClasses
                    val xOffset = whiteWidth * (whiteIdx + 1) - blackWidth * 0.5f

                    val keyBrush = if (isLit) {
                        Brush.verticalGradient(listOf(StageColors.Cyan, Color(0xFF0284C7)))
                    } else {
                        Brush.verticalGradient(listOf(Color(0xFF1E293B), Color(0xFF0B1120)))
                    }

                    Box(
                        modifier = Modifier
                            .offset(x = xOffset)
                            .width(blackWidth)
                            .height(blackHeight)
                            .zIndex(2f)
                            .clip(RoundedCornerShape(bottomStart = 5.dp, bottomEnd = 5.dp))
                            .background(keyBrush)
                            .border(
                                width = 1.dp,
                                color = if (isLit) StageColors.Cyan else Color(0xFF334155),
                                shape = RoundedCornerShape(bottomStart = 5.dp, bottomEnd = 5.dp)
                            )
                            .clickable { onTapKey(midi) },
                        contentAlignment = Alignment.BottomCenter
                    ) {
                        if (isChordTone && !isLit) {
                            Box(
                                modifier = Modifier
                                    .padding(bottom = 5.dp)
                                    .size(3.5.dp)
                                    .clip(CircleShape)
                                    .background(StageColors.Gold.copy(alpha = 0.7f))
                            )
                        }
                    }
                }
            }
        }
    }
}

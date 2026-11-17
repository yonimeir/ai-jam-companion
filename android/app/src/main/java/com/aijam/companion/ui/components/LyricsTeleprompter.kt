package com.aijam.companion.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.EditNote
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aijam.companion.model.BarState
import com.aijam.companion.model.EngineMode
import com.aijam.companion.model.LyricLine
import com.aijam.companion.ui.theme.StageColors

/**
 * Synced karaoke teleprompter card showing previous, active (progress-highlighted),
 * and upcoming lyrics lines with inline chord hints and tap-to-jump navigation.
 */
@Composable
fun LyricsTeleprompter(
    lyrics: List<LyricLine>,
    states: List<BarState>,
    refTimeSec: Double,
    engineMode: EngineMode,
    currentChord: String,
    onJumpToTime: (Double) -> Unit,
    onOpenLyricsEditor: () -> Unit,
    modifier: Modifier = Modifier
) {
    val activeIdx = lyrics.indexOfLast { refTimeSec >= it.startTime - 0.15 }
        .let { idx ->
            if (idx >= 0 && refTimeSec <= lyrics[idx].endTime + 1.2) idx else -1
        }

    val nextIdx = if (activeIdx >= 0) {
        activeIdx + 1
    } else {
        lyrics.indexOfFirst { it.startTime > refTimeSec }
    }

    val prevLine = if (activeIdx > 0) lyrics[activeIdx - 1] else null
    val activeLine = if (activeIdx >= 0) lyrics[activeIdx] else null
    val nextLine = if (nextIdx in lyrics.indices) lyrics[nextIdx] else null

    val lineProgress = if (activeLine != null) {
        val span = (activeLine.endTime - activeLine.startTime).coerceAtLeast(0.4)
        ((refTimeSec - activeLine.startTime) / span).toFloat().coerceIn(0f, 1f)
    } else 0f

    val activeLineColor by animateColorAsState(
        targetValue = if (engineMode == EngineMode.VAMPING) StageColors.Coral else StageColors.Gold,
        animationSpec = tween(250),
        label = "activeLyricColor"
    )

    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        color = StageColors.Charcoal,
        border = BorderStroke(1.dp, StageColors.Hairline)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp)
        ) {
            // Header row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Rounded.GraphicEq,
                        contentDescription = null,
                        tint = StageColors.Gold,
                        modifier = Modifier.padding(end = 6.dp).size(16.dp)
                    )
                    Text(
                        text = "טלפרומפטר מילים מסונכרן",
                        color = StageColors.TextMuted,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }

                Surface(
                    modifier = Modifier
                        .clip(RoundedCornerShape(999.dp))
                        .clickable { onOpenLyricsEditor() },
                    color = StageColors.Elevated,
                    border = BorderStroke(1.dp, StageColors.Hairline)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.EditNote,
                            contentDescription = "ערוך מילים",
                            tint = StageColors.Cyan,
                            modifier = Modifier.padding(end = 4.dp).size(16.dp)
                        )
                        Text(
                            text = if (lyrics.isEmpty()) "הוסף מילים / LRC" else "ערוך מילים",
                            color = StageColors.TextSecondary,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Medium
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            if (lyrics.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(StageColors.DeepSlate)
                        .clickable { onOpenLyricsEditor() }
                        .padding(vertical = 18.dp, horizontal = 12.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "אין עדיין מילים לשיר זה • הקש להדבקת טקסט או טעינת קובץ .lrc",
                        color = StageColors.TextMuted,
                        fontSize = 13.sp,
                        textAlign = TextAlign.Center
                    )
                }
            } else {
                // Previous dimmed line
                if (prevLine != null) {
                    Text(
                        text = prevLine.text,
                        color = StageColors.TextMuted.copy(alpha = 0.65f),
                        fontSize = 12.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        textAlign = TextAlign.Center,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onJumpToTime(prevLine.startTime) }
                            .padding(vertical = 2.dp)
                    )
                }

                // Active / Instrumental center highlight box
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .clickable(enabled = activeLine != null) {
                            activeLine?.let { onJumpToTime(it.startTime) }
                        },
                    color = StageColors.DeepSlate,
                    border = BorderStroke(1.dp, activeLineColor.copy(alpha = 0.35f)),
                    shape = RoundedCornerShape(14.dp)
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        if (activeLine != null) {
                            val lineChord = states.firstOrNull { it.bar == activeLine.bar }?.chord ?: currentChord
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.Center,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Surface(
                                    color = activeLineColor.copy(alpha = 0.16f),
                                    shape = RoundedCornerShape(6.dp)
                                ) {
                                    Text(
                                        text = lineChord,
                                        color = activeLineColor,
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Bold,
                                        modifier = Modifier.padding(horizontal = 7.dp, vertical = 2.dp)
                                    )
                                }
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = activeLine.text,
                                    color = StageColors.TextPrimary,
                                    fontSize = 17.sp,
                                    fontWeight = FontWeight.Bold,
                                    textAlign = TextAlign.Center
                                )
                            }
                            Spacer(modifier = Modifier.height(8.dp))
                            LinearProgressIndicator(
                                progress = { lineProgress },
                                modifier = Modifier
                                    .fillMaxWidth(0.75f)
                                    .height(3.dp)
                                    .clip(RoundedCornerShape(999.dp)),
                                color = activeLineColor,
                                trackColor = StageColors.Elevated
                            )
                        } else {
                            Text(
                                text = if (engineMode == EngineMode.VAMPING) {
                                    "VAMP מחזורי על $currentChord — קח את הזמן או אלתר בחופשיות"
                                } else {
                                    "פתיח / מעבר אינסטרומנטלי ($currentChord)"
                                },
                                color = activeLineColor,
                                fontSize = 14.sp,
                                fontWeight = FontWeight.SemiBold,
                                textAlign = TextAlign.Center
                            )
                        }
                    }
                }

                // Upcoming line preview
                if (nextLine != null) {
                    val nextChord = states.firstOrNull { it.bar == nextLine.bar }?.chord
                    Text(
                        text = if (nextChord != null) "${nextLine.text}  [$nextChord]" else nextLine.text,
                        color = StageColors.TextSecondary.copy(alpha = 0.78f),
                        fontSize = 13.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        textAlign = TextAlign.Center,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onJumpToTime(nextLine.startTime) }
                            .padding(top = 3.dp)
                    )
                }
            }
        }
    }
}

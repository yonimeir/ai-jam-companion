package com.aijam.companion.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.EditNote
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aijam.companion.model.BarState
import com.aijam.companion.model.EngineMode
import com.aijam.companion.model.LyricLine
import com.aijam.companion.model.SongSection
import com.aijam.companion.ui.theme.StageColors

/**
 * Full-stage Apple Music Sing × Moises Lead-Sheet Teleprompter:
 * - Multi-line smooth auto-scrolling LazyColumn
 * - Inline chord badges ([Bm], [Gmaj7], [F#7]) and section headers above lyric phrases
 * - High-contrast active vocal line with real-time phrase progress bar
 * - Tap any line to seek playback immediately to that phrase
 */
@Composable
fun LyricsTeleprompter(
    lyrics: List<LyricLine>,
    states: List<BarState>,
    sections: List<SongSection> = emptyList(),
    refTimeSec: Double,
    engineMode: EngineMode,
    currentChord: String,
    onJumpToTime: (Double) -> Unit,
    onOpenLyricsEditor: () -> Unit,
    modifier: Modifier = Modifier
) {
    val listState = rememberLazyListState()

    val activeIdx = lyrics.indexOfLast { refTimeSec >= it.startTime - 0.18 }
        .let { idx ->
            if (idx >= 0 && refTimeSec <= lyrics[idx].endTime + 1.8) idx else -1
        }

    val upcomingIdx = if (activeIdx >= 0) {
        activeIdx
    } else {
        lyrics.indexOfFirst { it.startTime > refTimeSec }.coerceAtLeast(0)
    }

    LaunchedEffect(activeIdx, upcomingIdx, lyrics.size) {
        if (lyrics.isNotEmpty()) {
            val targetScroll = (if (activeIdx >= 0) activeIdx else upcomingIdx).coerceIn(0, lyrics.lastIndex)
            listState.animateScrollToItem((targetScroll - 1).coerceAtLeast(0))
        }
    }

    val activeColor by animateColorAsState(
        targetValue = if (engineMode == EngineMode.VAMPING) StageColors.Coral else StageColors.Gold,
        animationSpec = tween(250),
        label = "teleprompterActive"
    )

    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        color = StageColors.DeepSlate,
        border = BorderStroke(0.8.dp, StageColors.Hairline.copy(alpha = 0.65f))
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        colors = listOf(
                            StageColors.Charcoal.copy(alpha = 0.55f),
                            StageColors.DeepSlate,
                            StageColors.Obsidian.copy(alpha = 0.85f)
                        )
                    )
                )
                .padding(horizontal = 14.dp, vertical = 10.dp)
        ) {
            // Subtle Top Bar of Teleprompter
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Rounded.MusicNote,
                        contentDescription = null,
                        tint = StageColors.Gold,
                        modifier = Modifier.size(15.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "מילים ואקורדים בסנכרון חי • הקש על שורה למעבר",
                        color = StageColors.TextMuted,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Medium
                    )
                }

                Surface(
                    modifier = Modifier
                        .clip(RoundedCornerShape(999.dp))
                        .clickable { onOpenLyricsEditor() },
                    color = StageColors.Elevated,
                    border = BorderStroke(0.8.dp, StageColors.Hairline)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.EditNote,
                            contentDescription = "ערוך או חפש מילים",
                            tint = StageColors.Gold,
                            modifier = Modifier.size(15.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = if (lyrics.isEmpty()) "חפש / הוסף מילים" else "מילים / LRC",
                            color = StageColors.TextSecondary,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(6.dp))

            if (lyrics.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .clip(RoundedCornerShape(16.dp))
                        .background(StageColors.Obsidian.copy(alpha = 0.5f))
                        .clickable { onOpenLyricsEditor() }
                        .padding(20.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            text = "אין עדיין מילים מסונכרנות לשיר זה",
                            color = StageColors.TextPrimary,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Bold,
                            textAlign = TextAlign.Center
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = "הקש כאן לחיפוש אוטומטי אונליין (LRC) או הדבקת מילים",
                            color = StageColors.Gold,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold,
                            textAlign = TextAlign.Center
                        )
                    }
                }
            } else {
                // Instrumental Intro / Break Banner when before first lyric
                val firstLyricStart = lyrics.first().startTime
                if (refTimeSec < firstLyricStart - 0.25) {
                    val introChords = states.take(4).map { it.chord }.distinct().joinToString(" → ")
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 6.dp),
                        color = StageColors.Gold.copy(alpha = 0.12f),
                        shape = RoundedCornerShape(12.dp),
                        border = BorderStroke(1.dp, StageColors.Gold.copy(alpha = 0.4f))
                    ) {
                        Text(
                            text = "🎹 פתיח פסנתר ($introChords) — השורה הראשונה מוכנה מטה",
                            color = StageColors.Gold,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                        )
                    }
                }

                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(vertical = 6.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    itemsIndexed(lyrics, key = { idx, line -> "${line.bar}_${idx}" }) { idx, line ->
                        val isActive = idx == activeIdx
                        val isNextUp = activeIdx == -1 && idx == upcomingIdx
                        val isPast = if (activeIdx >= 0) idx < activeIdx else line.endTime < refTimeSec

                        val barState = states.firstOrNull { it.bar == line.bar }
                        val barChords = barState?.subChords?.distinct()?.joinToString(" → ")
                            ?: barState?.chord
                            ?: currentChord

                        // Check if this lyric line starts a new section
                        val prevBar = lyrics.getOrNull(idx - 1)?.bar ?: 0
                        val sectionHeader = sections.firstOrNull { sec ->
                            line.bar in sec.startBar..sec.endBar && (idx == 0 || prevBar < sec.startBar)
                        }

                        val lineProgress = if (isActive) {
                            val span = (line.endTime - line.startTime).coerceAtLeast(0.4)
                            ((refTimeSec - line.startTime) / span).toFloat().coerceIn(0f, 1f)
                        } else 0f

                        Column(modifier = Modifier.fillMaxWidth()) {
                            if (sectionHeader != null) {
                                val secAccent = sectionAccentColor(sectionHeader.name)
                                Text(
                                    text = sectionHeader.label,
                                    color = secAccent,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.ExtraBold,
                                    modifier = Modifier.padding(start = 6.dp, top = 4.dp, bottom = 4.dp)
                                )
                            }

                            Surface(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(16.dp))
                                    .clickable { onJumpToTime(line.startTime) },
                                shape = RoundedCornerShape(16.dp),
                                color = when {
                                    isActive -> StageColors.Elevated.copy(alpha = 0.92f)
                                    isNextUp -> StageColors.Charcoal.copy(alpha = 0.78f)
                                    else -> StageColors.Obsidian.copy(alpha = 0.25f)
                                },
                                border = BorderStroke(
                                    width = if (isActive) 1.5.dp else if (isNextUp) 1.dp else 0.dp,
                                    color = when {
                                        isActive -> activeColor.copy(alpha = 0.75f)
                                        isNextUp -> StageColors.Gold.copy(alpha = 0.35f)
                                        else -> StageColors.Obsidian.copy(alpha = 0f)
                                    }
                                )
                            ) {
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(
                                            horizontal = 14.dp,
                                            vertical = if (isActive) 12.dp else 8.dp
                                        )
                                ) {
                                    // Top Row inside lyric card: Chord Pill + Timestamp/Bar
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Surface(
                                            color = if (isActive) activeColor.copy(alpha = 0.22f)
                                            else StageColors.Charcoal,
                                            shape = RoundedCornerShape(8.dp),
                                            border = BorderStroke(
                                                0.8.dp,
                                                if (isActive) activeColor else StageColors.Hairline
                                            )
                                        ) {
                                            Text(
                                                text = barChords,
                                                color = if (isActive) activeColor else StageColors.Gold.copy(alpha = 0.85f),
                                                fontSize = if (isActive) 12.sp else 11.sp,
                                                fontWeight = FontWeight.ExtraBold,
                                                fontFamily = FontFamily.Monospace,
                                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                                            )
                                        }

                                        Text(
                                            text = "תיבה ${line.bar} • ${formatLrcTime(line.startTime)}",
                                            color = StageColors.TextMuted.copy(alpha = 0.7f),
                                            fontSize = 10.sp,
                                            fontFamily = FontFamily.Monospace
                                        )
                                    }

                                    Spacer(modifier = Modifier.height(5.dp))

                                    // Lyric Text
                                    Text(
                                        text = line.text,
                                        color = when {
                                            isActive -> StageColors.TextPrimary
                                            isNextUp -> StageColors.TextPrimary.copy(alpha = 0.90f)
                                            isPast -> StageColors.TextMuted.copy(alpha = 0.52f)
                                            else -> StageColors.TextSecondary.copy(alpha = 0.85f)
                                        },
                                        fontSize = when {
                                            isActive -> 21.sp
                                            isNextUp -> 17.sp
                                            else -> 15.sp
                                        },
                                        fontWeight = if (isActive || isNextUp) FontWeight.ExtraBold else FontWeight.SemiBold,
                                        lineHeight = if (isActive) 26.sp else 20.sp
                                    )

                                    if (isActive) {
                                        Spacer(modifier = Modifier.height(8.dp))
                                        LinearProgressIndicator(
                                            progress = { lineProgress },
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .height(4.dp)
                                                .clip(RoundedCornerShape(999.dp)),
                                            color = activeColor,
                                            trackColor = StageColors.Obsidian
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun formatLrcTime(sec: Double): String {
    val total = sec.toInt().coerceAtLeast(0)
    val m = total / 60
    val s = total % 60
    return "%d:%02d".format(m, s)
}

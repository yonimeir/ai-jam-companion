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
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aijam.companion.model.SongSection
import com.aijam.companion.ui.theme.StageColors

/**
 * Interactive Song Structure Roadmap Strip (פתיח • בית 1 • פזמון 1 • בית 2 • פזמון 2 • גשר • סיום).
 * Clearly highlights which part of the song is playing right now, displays the bar progress inside
 * the current section, and allows 1-tap navigation to any section.
 */
@Composable
fun SectionRoadmapBar(
    sections: List<SongSection>,
    currentBar: Int,
    refTimeSec: Double,
    onSelectSection: (SongSection) -> Unit,
    modifier: Modifier = Modifier
) {
    if (sections.isEmpty()) return

    val listState = rememberLazyListState()
    val activeIdx = sections.indexOfFirst { currentBar in it.startBar..it.endBar }
        .let { if (it >= 0) it else sections.indexOfLast { refTimeSec >= it.startTime }.coerceAtLeast(0) }

    LaunchedEffect(activeIdx) {
        if (activeIdx in sections.indices) {
            listState.animateScrollToItem((activeIdx - 1).coerceAtLeast(0))
        }
    }

    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
        LazyRow(
            state = listState,
            modifier = modifier.fillMaxWidth(),
            contentPadding = PaddingValues(horizontal = 2.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            itemsIndexed(sections, key = { idx, s -> "${s.name}_$idx" }) { idx, section ->
                val isCurrent = idx == activeIdx
                val isPast = idx < activeIdx
                val accentColor = sectionAccentColor(section.name)

                val sectionSpanSec = (section.endTime - section.startTime).coerceAtLeast(0.5)
                val sectionProgress = when {
                    isCurrent -> ((refTimeSec - section.startTime) / sectionSpanSec).toFloat().coerceIn(0.05f, 1f)
                    isPast -> 1f
                    else -> 0f
                }

                val hebrewTitle = section.label.substringBefore("•").trim().ifBlank { section.label }
                val totalSecBars = (section.endBar - section.startBar + 1).coerceAtLeast(1)
                val barInSec = (currentBar - section.startBar + 1).coerceIn(1, totalSecBars)

                val bgColor by animateColorAsState(
                    targetValue = when {
                        isCurrent -> accentColor.copy(alpha = 0.20f)
                        isPast -> StageColors.DeepSlate.copy(alpha = 0.65f)
                        else -> StageColors.Charcoal.copy(alpha = 0.85f)
                    },
                    animationSpec = tween(240),
                    label = "secBg"
                )

                Surface(
                    modifier = Modifier
                        .clip(RoundedCornerShape(12.dp))
                        .clickable { onSelectSection(section) },
                    shape = RoundedCornerShape(12.dp),
                    color = bgColor,
                    border = BorderStroke(
                        width = if (isCurrent) 1.5.dp else 0.8.dp,
                        color = if (isCurrent) accentColor else StageColors.Hairline.copy(alpha = 0.55f)
                    )
                ) {
                    Column(
                        modifier = Modifier.padding(
                            horizontal = if (isCurrent) 12.dp else 10.dp,
                            vertical = 6.dp
                        ),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.Center
                        ) {
                            if (isCurrent) {
                                Box(
                                    modifier = Modifier
                                        .size(6.dp)
                                        .clip(CircleShape)
                                        .background(accentColor)
                                )
                                Spacer(modifier = Modifier.width(5.dp))
                            }
                            Text(
                                text = hebrewTitle,
                                color = when {
                                    isCurrent -> StageColors.TextPrimary
                                    isPast -> StageColors.TextMuted.copy(alpha = 0.7f)
                                    else -> StageColors.TextSecondary
                                },
                                fontSize = if (isCurrent) 12.sp else 11.sp,
                                fontWeight = if (isCurrent) FontWeight.ExtraBold else FontWeight.SemiBold
                            )
                            if (isCurrent) {
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = "$barInSec/$totalSecBars",
                                    color = accentColor,
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold,
                                    fontFamily = FontFamily.Monospace
                                )
                            }
                        }

                        if (isCurrent) {
                            Spacer(modifier = Modifier.height(4.dp))
                            LinearProgressIndicator(
                                progress = { sectionProgress },
                                modifier = Modifier
                                    .width(68.dp)
                                    .height(3.dp)
                                    .clip(RoundedCornerShape(999.dp)),
                                color = accentColor,
                                trackColor = StageColors.Obsidian.copy(alpha = 0.6f)
                            )
                        }
                    }
                }
            }
        }
    }
}

fun sectionAccentColor(sectionName: String): Color {
    val low = sectionName.lowercase()
    return when {
        "chorus" in low || "פזמון" in low -> StageColors.Gold
        "bridge" in low || "גשר" in low -> StageColors.Coral
        "intro" in low || "פתיח" in low -> StageColors.Cyan
        "outro" in low || "סיום" in low -> StageColors.Emerald
        else -> Color(0xFFA78BFA) // Warm Studio Violet for Verses
    }
}

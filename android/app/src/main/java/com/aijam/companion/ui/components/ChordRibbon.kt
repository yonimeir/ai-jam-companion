package com.aijam.companion.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aijam.companion.model.BarState
import com.aijam.companion.ui.theme.StageColors

/**
 * Compact auto-scrolling horizontal Bar & Chord progression ribbon (LTR) with tap-to-jump.
 */
@Composable
fun ChordRibbon(
    states: List<BarState>,
    currentBar: Int,
    onJumpToBar: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    val listState = rememberLazyListState()

    LaunchedEffect(currentBar) {
        val targetIndex = states.indexOfFirst { it.bar == currentBar }
        if (targetIndex >= 0) {
            listState.animateScrollToItem(index = (targetIndex - 1).coerceAtLeast(0))
        }
    }

    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
        LazyRow(
            state = listState,
            modifier = modifier.fillMaxWidth(),
            contentPadding = PaddingValues(horizontal = 2.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            items(states, key = { it.bar }) { barState ->
                val isCurrent = barState.bar == currentBar
                val isPast = barState.bar < currentBar
                val secColor = sectionAccentColor(barState.section)
                val distinctChords = barState.subChords.distinct()
                val chordLabel = if (distinctChords.size > 1) {
                    distinctChords.joinToString("·")
                } else {
                    barState.chord
                }

                val containerColor = when {
                    isCurrent -> secColor.copy(alpha = 0.20f)
                    isPast -> StageColors.DeepSlate.copy(alpha = 0.55f)
                    else -> StageColors.Charcoal
                }
                val borderColor = when {
                    isCurrent -> secColor
                    else -> StageColors.Hairline.copy(alpha = 0.55f)
                }

                Surface(
                    modifier = Modifier
                        .width(74.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .clickable { onJumpToBar(barState.bar) },
                    shape = RoundedCornerShape(12.dp),
                    color = containerColor,
                    border = BorderStroke(if (isCurrent) 1.5.dp else 0.8.dp, borderColor)
                ) {
                    Column(
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
                        horizontalAlignment = Alignment.Start
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "B${barState.bar}",
                                color = if (isCurrent) secColor else StageColors.TextMuted,
                                fontSize = 9.sp,
                                fontWeight = FontWeight.Bold,
                                fontFamily = FontFamily.Monospace
                            )
                        }
                        Spacer(modifier = Modifier.height(1.dp))
                        Text(
                            text = chordLabel,
                            color = if (isCurrent) StageColors.TextPrimary else StageColors.TextSecondary,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.ExtraBold
                        )
                    }
                }
            }
        }
    }
}

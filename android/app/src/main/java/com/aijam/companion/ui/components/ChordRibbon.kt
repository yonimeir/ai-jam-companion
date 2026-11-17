package com.aijam.companion.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
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
 * Auto-scrolling horizontal bar progression ribbon (forced LTR) with tap-to-jump.
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
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(states, key = { it.bar }) { barState ->
                val isCurrent = barState.bar == currentBar
                val isPast = barState.bar < currentBar

                val containerColor = when {
                    isCurrent -> StageColors.Gold.copy(alpha = 0.16f)
                    isPast -> StageColors.Obsidian
                    else -> StageColors.Charcoal
                }
                val borderColor = when {
                    isCurrent -> StageColors.Gold
                    else -> StageColors.Hairline
                }

                Surface(
                    modifier = Modifier
                        .width(82.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .clickable { onJumpToBar(barState.bar) },
                    shape = RoundedCornerShape(14.dp),
                    color = containerColor,
                    border = BorderStroke(if (isCurrent) 1.5.dp else 1.dp, borderColor)
                ) {
                    Column(
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                        horizontalAlignment = Alignment.Start
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "B${barState.bar}",
                                color = if (isCurrent) StageColors.Gold else StageColors.TextMuted,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                fontFamily = FontFamily.Monospace
                            )
                            Text(
                                text = barState.section.replace("_", " ").take(7),
                                color = StageColors.TextMuted.copy(alpha = 0.8f),
                                fontSize = 9.sp
                            )
                        }
                        Spacer(modifier = Modifier.padding(top = 3.dp))
                        Text(
                            text = barState.chord,
                            color = if (isCurrent) StageColors.Gold else StageColors.TextPrimary,
                            fontSize = 16.sp,
                            fontWeight = FontWeight.ExtraBold
                        )
                    }
                }
            }
        }
    }
}

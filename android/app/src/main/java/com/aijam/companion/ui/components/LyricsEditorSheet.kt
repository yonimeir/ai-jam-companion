package com.aijam.companion.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material.icons.rounded.AutoFixHigh
import androidx.compose.material.icons.rounded.CloudDownload
import androidx.compose.material.icons.rounded.FileOpen
import androidx.compose.material.icons.rounded.Lyrics
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aijam.companion.data.LyricsParser
import com.aijam.companion.model.SongMap
import com.aijam.companion.ui.theme.StageColors

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LyricsEditorSheet(
    songMap: SongMap,
    isFetchingOnline: Boolean = false,
    onFetchOnlineLyrics: (String) -> Unit = {},
    onPickLrcFile: () -> Unit,
    onSaveLyricsText: (String) -> Unit,
    onDismiss: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var searchQuery by remember(songMap.metadata.id) {
        mutableStateOf("${songMap.metadata.artist} ${songMap.metadata.title}".trim())
    }
    var text by remember(songMap.metadata.id, songMap.lyrics) {
        mutableStateOf(
            if (songMap.lyrics.isNotEmpty()) LyricsParser.toLrcString(songMap.lyrics) else ""
        )
    }
    val hasTimestamps = LyricsParser.isLrcFormat(text)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = StageColors.DeepSlate,
        contentColor = StageColors.TextPrimary
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 18.dp)
                .padding(bottom = 26.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Rounded.Lyrics,
                    contentDescription = null,
                    tint = StageColors.Gold
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "מילים מסונכרנות — ${songMap.metadata.title}",
                    fontSize = 17.sp,
                    fontWeight = FontWeight.Bold,
                    color = StageColors.TextPrimary
                )
            }

            Spacer(modifier = Modifier.height(10.dp))

            // Online Synced Lyrics Search Row (LRCLIB)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    modifier = Modifier.weight(1f),
                    singleLine = true,
                    placeholder = {
                        Text(
                            text = "שם השיר והאמן לחיפוש LRC...",
                            color = StageColors.TextMuted,
                            fontSize = 12.sp
                        )
                    },
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedContainerColor = StageColors.Charcoal,
                        unfocusedContainerColor = StageColors.Charcoal,
                        focusedBorderColor = StageColors.Cyan,
                        unfocusedBorderColor = StageColors.Hairline,
                        focusedTextColor = StageColors.TextPrimary,
                        unfocusedTextColor = StageColors.TextPrimary
                    ),
                    shape = RoundedCornerShape(12.dp)
                )

                Button(
                    onClick = { onFetchOnlineLyrics(searchQuery) },
                    enabled = !isFetchingOnline && searchQuery.isNotBlank(),
                    modifier = Modifier.height(54.dp),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = StageColors.Cyan,
                        contentColor = StageColors.Obsidian
                    )
                ) {
                    if (isFetchingOnline) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            color = StageColors.Obsidian,
                            strokeWidth = 2.dp
                        )
                    } else {
                        Icon(
                            imageVector = Icons.Rounded.CloudDownload,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "חפש LRC",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.ExtraBold
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(190.dp),
                placeholder = {
                    Text(
                        text = "[00:13.00] In my head, you lay down next to me...\n[00:16.20] Kiss my lips, hold me carefully...",
                        color = StageColors.TextMuted.copy(alpha = 0.65f),
                        fontSize = 13.sp
                    )
                },
                colors = OutlinedTextFieldDefaults.colors(
                    focusedContainerColor = StageColors.Charcoal,
                    unfocusedContainerColor = StageColors.Charcoal,
                    focusedBorderColor = StageColors.Gold,
                    unfocusedBorderColor = StageColors.Hairline,
                    focusedTextColor = StageColors.TextPrimary,
                    unfocusedTextColor = StageColors.TextPrimary
                ),
                shape = RoundedCornerShape(14.dp)
            )

            Spacer(modifier = Modifier.height(12.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                OutlinedButton(
                    onClick = onPickLrcFile,
                    modifier = Modifier.weight(1f).height(46.dp),
                    shape = RoundedCornerShape(12.dp),
                    border = BorderStroke(1.dp, StageColors.Hairline)
                ) {
                    Icon(
                        imageVector = Icons.Rounded.FileOpen,
                        contentDescription = null,
                        tint = StageColors.Cyan
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "טען קובץ .lrc",
                        color = StageColors.TextSecondary,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }

                Button(
                    onClick = {
                        onSaveLyricsText(text)
                        onDismiss()
                    },
                    modifier = Modifier.weight(1.2f).height(46.dp),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = StageColors.Gold,
                        contentColor = StageColors.Obsidian
                    )
                ) {
                    Icon(imageVector = Icons.Rounded.AutoFixHigh, contentDescription = null)
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = if (hasTimestamps) "שמור סנכרון LRC" else "סנכרן אוטומטית",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }
    }
}

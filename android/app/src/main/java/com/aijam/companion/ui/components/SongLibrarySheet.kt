package com.aijam.companion.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AudioFile
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.LibraryMusic
import androidx.compose.material.icons.rounded.UploadFile
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aijam.companion.audio.AnalysisUiState
import com.aijam.companion.data.SongLibraryRepository
import com.aijam.companion.model.SongMap
import com.aijam.companion.ui.theme.StageColors

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SongLibrarySheet(
    songs: List<SongMap>,
    activeSongId: String,
    analysisState: AnalysisUiState,
    onPickAudioFile: () -> Unit,
    onSelectSong: (SongMap) -> Unit,
    onDeleteSong: (String) -> Unit,
    onDismissError: () -> Unit,
    onDismiss: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

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
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Rounded.LibraryMusic,
                        contentDescription = null,
                        tint = StageColors.Gold
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "ספריית שירים וניתוח מקומי",
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                        color = StageColors.TextPrimary
                    )
                }
            }

            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = "בחר קובץ אודיו (MP3, WAV, M4A, FLAC) מהמכשיר — הניתוח ההרמוני, הטמפו והמלודיה רצים מקומית בלי שרת.",
                fontSize = 12.sp,
                color = StageColors.TextMuted
            )

            Spacer(modifier = Modifier.height(14.dp))

            // On-Device Audio Import CTA
            Button(
                onClick = onPickAudioFile,
                enabled = !analysisState.isAnalyzing,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(50.dp),
                shape = RoundedCornerShape(14.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = StageColors.Gold,
                    contentColor = StageColors.Obsidian
                )
            ) {
                Icon(imageVector = Icons.Rounded.UploadFile, contentDescription = null)
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = if (analysisState.isAnalyzing) "מנתח שיר במכשיר..." else "ייבוא וניתוח שיר מהמכשיר",
                    fontWeight = FontWeight.Bold,
                    fontSize = 14.sp
                )
            }

            if (analysisState.isAnalyzing) {
                Spacer(modifier = Modifier.height(12.dp))
                Surface(
                    color = StageColors.Charcoal,
                    shape = RoundedCornerShape(12.dp),
                    border = BorderStroke(1.dp, StageColors.Cyan.copy(alpha = 0.4f))
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = analysisState.stageMessage,
                                color = StageColors.Cyan,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Medium
                            )
                            Text(
                                text = "${(analysisState.progress * 100).toInt()}%",
                                color = StageColors.TextPrimary,
                                fontSize = 12.sp,
                                fontFamily = FontFamily.Monospace
                            )
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                        LinearProgressIndicator(
                            progress = { analysisState.progress.coerceIn(0f, 1f) },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(6.dp)
                                .clip(RoundedCornerShape(999.dp)),
                            color = StageColors.Cyan,
                            trackColor = StageColors.Elevated
                        )
                    }
                }
            }

            if (analysisState.error != null) {
                Spacer(modifier = Modifier.height(10.dp))
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onDismissError() },
                    color = StageColors.Coral.copy(alpha = 0.14f),
                    shape = RoundedCornerShape(12.dp),
                    border = BorderStroke(1.dp, StageColors.Coral.copy(alpha = 0.5f))
                ) {
                    Text(
                        text = "${analysisState.error} (הקש לסגירה)",
                        color = StageColors.Coral,
                        fontSize = 12.sp,
                        modifier = Modifier.padding(12.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 320.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(songs, key = { it.metadata.id }) { song ->
                    val isSelected = song.metadata.id == activeSongId
                    val isDemo = song.metadata.id == SongLibraryRepository.DEMO_SONG_ID
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(14.dp))
                            .clickable {
                                onSelectSong(song)
                                onDismiss()
                            },
                        shape = RoundedCornerShape(14.dp),
                        color = if (isSelected) StageColors.Gold.copy(alpha = 0.12f) else StageColors.Charcoal,
                        border = BorderStroke(
                            width = if (isSelected) 1.5.dp else 1.dp,
                            color = if (isSelected) StageColors.Gold else StageColors.Hairline
                        )
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = if (isSelected) Icons.Rounded.CheckCircle else Icons.Rounded.AudioFile,
                                contentDescription = null,
                                tint = if (isSelected) StageColors.Gold else StageColors.Cyan,
                                modifier = Modifier.size(22.dp)
                            )
                            Spacer(modifier = Modifier.width(10.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = song.metadata.title,
                                    color = StageColors.TextPrimary,
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.Bold,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    text = "${song.metadata.artist} • ${song.metadata.bpm.toInt()} BPM • ${song.metadata.totalBars} תיבות" +
                                        if (song.lyrics.isNotEmpty()) " • 🎤 מילים" else "",
                                    color = StageColors.TextMuted,
                                    fontSize = 11.sp,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }

                            if (!isDemo) {
                                IconButton(onClick = { onDeleteSong(song.metadata.id) }) {
                                    Icon(
                                        imageVector = Icons.Rounded.DeleteOutline,
                                        contentDescription = "מחק שיר",
                                        tint = StageColors.Coral
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

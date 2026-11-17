package com.aijam.companion.ui.components

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.BugReport
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aijam.companion.ui.theme.StageColors

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DiagnosticsSheet(
    buildReport: () -> String,
    onClearLogs: () -> Unit,
    onCopiedMessage: (String) -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var refreshTick by remember { mutableIntStateOf(0) }
    val reportText = remember(refreshTick) { buildReport() }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = StageColors.DeepSlate,
        contentColor = StageColors.TextPrimary
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Rounded.BugReport,
                        contentDescription = null,
                        tint = StageColors.Gold,
                        modifier = Modifier.size(22.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Column {
                        Text(
                            text = "דוח דיאגנוסטיקה ולוגים",
                            fontSize = 17.sp,
                            fontWeight = FontWeight.Bold,
                            color = StageColors.TextPrimary
                        )
                        Text(
                            text = "ניתוח BPM, אקורדים לפי תיבות, ומעקב סנכרון בזמן אמת",
                            fontSize = 11.sp,
                            color = StageColors.TextMuted
                        )
                    }
                }

                Row {
                    IconButton(onClick = { refreshTick++ }) {
                        Icon(
                            imageVector = Icons.Rounded.Refresh,
                            contentDescription = "רענן דוח",
                            tint = StageColors.Cyan
                        )
                    }
                    IconButton(
                        onClick = {
                            onClearLogs()
                            refreshTick++
                        }
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.DeleteOutline,
                            contentDescription = "נקה לוגים",
                            tint = StageColors.TextMuted
                        )
                    }
                }
            }

            // 1-Tap Copy & Share Buttons
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    onClick = {
                        val fresh = buildReport()
                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                        clipboard?.setPrimaryClip(ClipData.newPlainText("AI Jam Diagnostics", fresh))
                        onCopiedMessage("הדוח המלא הועתק ללוח! אפשר להדביק אותו כאן בצ'אט")
                    },
                    modifier = Modifier
                        .weight(1.15f)
                        .height(46.dp),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = StageColors.Gold,
                        contentColor = StageColors.Obsidian
                    )
                ) {
                    Icon(
                        imageVector = Icons.Rounded.ContentCopy,
                        contentDescription = null,
                        modifier = Modifier.size(17.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "העתק דוח מלא",
                        fontWeight = FontWeight.ExtraBold,
                        fontSize = 13.sp
                    )
                }

                OutlinedButton(
                    onClick = {
                        val fresh = buildReport()
                        val sendIntent = Intent(Intent.ACTION_SEND).apply {
                            type = "text/plain"
                            putExtra(Intent.EXTRA_SUBJECT, "AI Jam Companion - Diagnostic Report")
                            putExtra(Intent.EXTRA_TEXT, fresh)
                        }
                        context.startActivity(Intent.createChooser(sendIntent, "שתף דוח לוגים"))
                    },
                    modifier = Modifier
                        .weight(1f)
                        .height(46.dp),
                    shape = RoundedCornerShape(12.dp),
                    border = BorderStroke(1.dp, StageColors.Cyan.copy(alpha = 0.65f))
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Share,
                        contentDescription = null,
                        tint = StageColors.Cyan,
                        modifier = Modifier.size(17.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "שתף לוגים",
                        color = StageColors.Cyan,
                        fontWeight = FontWeight.Bold,
                        fontSize = 13.sp
                    )
                }
            }

            // Monospace LTR Log Viewer
            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(340.dp),
                    shape = RoundedCornerShape(12.dp),
                    color = StageColors.Obsidian,
                    border = BorderStroke(1.dp, StageColors.Hairline)
                ) {
                    val vScroll = rememberScrollState()
                    val hScroll = rememberScrollState()
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .verticalScroll(vScroll)
                            .horizontalScroll(hScroll)
                            .background(StageColors.Obsidian)
                            .padding(12.dp)
                    ) {
                        Text(
                            text = reportText,
                            color = StageColors.TextSecondary,
                            fontSize = 11.sp,
                            lineHeight = 16.sp,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                }
            }
        }
    }
}

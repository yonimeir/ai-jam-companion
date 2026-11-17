package com.aijam.companion.ui

import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.BugReport
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.FileDownload
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.LibraryMusic
import androidx.compose.material.icons.rounded.Loop
import androidx.compose.material.icons.rounded.Lyrics
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Replay
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.aijam.companion.audio.JamSessionController
import com.aijam.companion.model.AccompanimentStyle
import com.aijam.companion.model.InputMode
import com.aijam.companion.ui.components.ChordRibbon
import com.aijam.companion.ui.components.DiagnosticsSheet
import com.aijam.companion.ui.components.LyricsEditorSheet
import com.aijam.companion.ui.components.LyricsTeleprompter
import com.aijam.companion.ui.components.PianoKeyboard
import com.aijam.companion.ui.components.SongLibrarySheet
import com.aijam.companion.ui.components.StageVisualizer
import com.aijam.companion.ui.theme.StageColors
import kotlinx.coroutines.launch

@Composable
fun StudioScreen(controller: JamSessionController) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val songMap by controller.songMap.collectAsState()
    val library by controller.library.collectAsState()
    val liveState by controller.liveState.collectAsState()
    val style by controller.style.collectAsState()
    val includeDrums by controller.includeDrums.collectAsState()
    val reverbMix by controller.reverbMix.collectAsState()
    val confidenceThreshold by controller.confidenceThreshold.collectAsState()
    val simSpeed by controller.simSpeed.collectAsState()
    val hearSinger by controller.hearSinger.collectAsState()
    val analysisState by controller.analysisState.collectAsState()
    val statusMessage by controller.statusMessage.collectAsState()

    var showLibrarySheet by remember { mutableStateOf(false) }
    var showLyricsSheet by remember { mutableStateOf(false) }
    var showSettingsSheet by remember { mutableStateOf(false) }
    var showDiagnosticsSheet by remember { mutableStateOf(false) }

    val snackbarHostState = remember { SnackbarHostState() }
    LaunchedEffect(statusMessage) {
        statusMessage?.let { msg ->
            snackbarHostState.showSnackbar(msg)
            controller.clearStatusMessage()
        }
    }

    val micPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            controller.setInputMode(InputMode.MIC)
        }
    }

    val audioPickerLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) {
            showLibrarySheet = true
            controller.importAudioUri(uri)
        }
    }

    val lrcPickerLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) {
            val raw = runCatching {
                context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
            }.getOrNull()
            if (!raw.isNullOrBlank()) {
                controller.updateSongLyrics(raw)
                showLyricsSheet = false
            }
        }
    }

    val midiExportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("audio/midi")
    ) { uri: Uri? ->
        if (uri != null) {
            val bytes = controller.exportMidiBytes()
            runCatching {
                context.contentResolver.openOutputStream(uri)?.use { out ->
                    out.write(bytes)
                }
            }
        }
    }

    fun requestMicMode() {
        val hasPerm = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
        if (hasPerm) {
            controller.setInputMode(InputMode.MIC)
        } else {
            micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    Scaffold(
        containerColor = StageColors.Obsidian,
        snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
        topBar = {
            StudioTopBar(
                title = songMap.metadata.title,
                bpm = songMap.metadata.bpm,
                isAnalyzing = analysisState.isAnalyzing,
                onOpenLibrary = { showLibrarySheet = true },
                onOpenLyrics = { showLyricsSheet = true },
                onOpenDiagnostics = { showDiagnosticsSheet = true },
                onOpenSettings = { showSettingsSheet = true }
            )
        },
        bottomBar = {
            StageConsoleBar(
                isRunning = liveState.isRunning,
                inputMode = liveState.inputMode,
                isVampHolding = liveState.isVampHolding,
                onToggleJam = { controller.toggleJam() },
                onSelectMic = { requestMicMode() },
                onSelectDemo = { controller.setInputMode(InputMode.DEMO) },
                onToggleVampHold = { controller.setForceVampHold(!liveState.isVampHolding) }
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 14.dp, vertical = 6.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            // 1. Live Audio-Reactive Stage Canvas
            StageVisualizer(state = liveState)

            // 2. Song Progress + Time strip
            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text(
                        text = formatSec(liveState.refTimeSec),
                        color = StageColors.TextSecondary,
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace
                    )
                    LinearProgressIndicator(
                        progress = { (liveState.progressPct / 100f).coerceIn(0f, 1f) },
                        modifier = Modifier
                            .weight(1f)
                            .height(5.dp)
                            .clip(RoundedCornerShape(999.dp)),
                        color = StageColors.Gold,
                        trackColor = StageColors.Elevated
                    )
                    Text(
                        text = formatSec(liveState.durationSec),
                        color = StageColors.TextMuted,
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace
                    )
                    IconButton(
                        onClick = { controller.resetToStart() },
                        modifier = Modifier.size(28.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Replay,
                            contentDescription = "חזור להתחלה",
                            tint = StageColors.TextMuted,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }
            }

            // 3. Synced Song Lyrics Teleprompter
            LyricsTeleprompter(
                lyrics = songMap.lyrics,
                states = songMap.states,
                refTimeSec = liveState.refTimeSec,
                engineMode = liveState.engineMode,
                currentChord = liveState.currentChord,
                onJumpToTime = { controller.jumpToTime(it) },
                onOpenLyricsEditor = { showLyricsSheet = true }
            )

            // 4. Chord Progression Bar Ribbon
            ChordRibbon(
                states = songMap.states,
                currentBar = liveState.currentBar,
                onJumpToBar = { controller.jumpToBar(it) }
            )

            // 5. Interactive 2-Octave Illuminated Stage Piano
            PianoKeyboard(
                activeKeysMidi = liveState.activeKeysMidi,
                chordNotes = liveState.chordNotes,
                onTapKey = { controller.tapPianoKey(it) }
            )

            Spacer(modifier = Modifier.height(6.dp))
        }
    }

    if (showLibrarySheet) {
        SongLibrarySheet(
            songs = library,
            activeSongId = songMap.metadata.id,
            analysisState = analysisState,
            onPickAudioFile = { audioPickerLauncher.launch(arrayOf("audio/*")) },
            onSelectSong = { controller.selectSong(it) },
            onDeleteSong = { controller.deleteSong(it) },
            onDismissError = { controller.dismissAnalysisError() },
            onDismiss = { showLibrarySheet = false }
        )
    }

    if (showLyricsSheet) {
        LyricsEditorSheet(
            songMap = songMap,
            onPickLrcFile = { lrcPickerLauncher.launch(arrayOf("text/*", "application/octet-stream", "*/*")) },
            onSaveLyricsText = { controller.updateSongLyrics(it) },
            onDismiss = { showLyricsSheet = false }
        )
    }

    if (showSettingsSheet) {
        StudioSettingsSheet(
            style = style,
            includeDrums = includeDrums,
            reverbMix = reverbMix,
            confidenceThreshold = confidenceThreshold,
            simSpeed = simSpeed,
            hearSinger = hearSinger,
            onSelectStyle = { controller.setStyle(it) },
            onToggleDrums = { controller.setIncludeDrums(it) },
            onReverbChange = { controller.setReverbMix(it) },
            onConfidenceChange = { controller.setConfidenceThreshold(it) },
            onSimSpeedChange = { controller.setSimSpeed(it) },
            onToggleHearSinger = { controller.setHearSinger(it) },
            onExportMidi = {
                val safeTitle = songMap.metadata.title.replace(Regex("[^a-zA-Z0-9_-]"), "_").ifBlank { "ai_jam" }
                midiExportLauncher.launch("${safeTitle}_live.mid")
            },
            onOpenDiagnostics = {
                showSettingsSheet = false
                showDiagnosticsSheet = true
            },
            onDismiss = { showSettingsSheet = false }
        )
    }

    if (showDiagnosticsSheet) {
        DiagnosticsSheet(
            buildReport = { controller.buildDiagnosticReport() },
            onClearLogs = { controller.clearDiagnosticLogs() },
            onCopiedMessage = { msg ->
                showDiagnosticsSheet = false
                scope.launch { snackbarHostState.showSnackbar(msg) }
            },
            onDismiss = { showDiagnosticsSheet = false }
        )
    }
}

@Composable
private fun StudioTopBar(
    title: String,
    bpm: Double,
    isAnalyzing: Boolean,
    onOpenLibrary: () -> Unit,
    onOpenLyrics: () -> Unit,
    onOpenDiagnostics: () -> Unit,
    onOpenSettings: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        // Brand + Song Selector Pill
        Row(
            modifier = Modifier
                .weight(1f)
                .clip(RoundedCornerShape(14.dp))
                .clickable { onOpenLibrary() }
                .padding(end = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Surface(
                modifier = Modifier.size(38.dp),
                shape = CircleShape,
                color = StageColors.Gold.copy(alpha = 0.16f),
                border = BorderStroke(1.dp, StageColors.Gold.copy(alpha = 0.55f))
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = Icons.Rounded.MusicNote,
                        contentDescription = null,
                        tint = StageColors.Gold,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
            Spacer(modifier = Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = title,
                        color = StageColors.TextPrimary,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                    Icon(
                        imageVector = Icons.Rounded.ExpandMore,
                        contentDescription = "בחר שיר",
                        tint = StageColors.Gold,
                        modifier = Modifier.size(18.dp)
                    )
                }
                Text(
                    text = if (isAnalyzing) "מנתח שיר במכשיר..." else "${bpm.toInt()} BPM • מנוע אודיו מקומי",
                    color = if (isAnalyzing) StageColors.Cyan else StageColors.TextMuted,
                    fontSize = 11.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }

        // Action icons: Library / Lyrics / Diagnostics / Settings
        Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            IconButton(onClick = onOpenLibrary) {
                Icon(
                    imageVector = Icons.Rounded.LibraryMusic,
                    contentDescription = "ספריית שירים",
                    tint = StageColors.Gold
                )
            }
            IconButton(onClick = onOpenLyrics) {
                Icon(
                    imageVector = Icons.Rounded.Lyrics,
                    contentDescription = "ערוך מילים",
                    tint = StageColors.Cyan
                )
            }
            IconButton(onClick = onOpenDiagnostics) {
                Icon(
                    imageVector = Icons.Rounded.BugReport,
                    contentDescription = "לוגים ודיאגנוסטיקה",
                    tint = StageColors.Brass
                )
            }
            IconButton(onClick = onOpenSettings) {
                Icon(
                    imageVector = Icons.Rounded.Tune,
                    contentDescription = "הגדרות ליווי",
                    tint = StageColors.TextSecondary
                )
            }
        }
    }
}

@Composable
private fun StageConsoleBar(
    isRunning: Boolean,
    inputMode: InputMode,
    isVampHolding: Boolean,
    onToggleJam: () -> Unit,
    onSelectMic: () -> Unit,
    onSelectDemo: () -> Unit,
    onToggleVampHold: () -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = StageColors.DeepSlate,
        border = BorderStroke(1.dp, StageColors.Hairline)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // Primary Play / Stop Button
            Button(
                onClick = onToggleJam,
                modifier = Modifier
                    .weight(1.25f)
                    .height(48.dp),
                shape = RoundedCornerShape(14.dp),
                contentPadding = PaddingValues(horizontal = 12.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (isRunning) StageColors.Coral else StageColors.Gold,
                    contentColor = StageColors.Obsidian
                )
            ) {
                Icon(
                    imageVector = if (isRunning) Icons.Rounded.Stop else Icons.Rounded.PlayArrow,
                    contentDescription = null
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = if (isRunning) "עצור ג'אם" else "נגן ליווי",
                    fontWeight = FontWeight.ExtraBold,
                    fontSize = 14.sp
                )
            }

            // Input Mode Segmented Pill (Mic vs Demo)
            Surface(
                modifier = Modifier
                    .weight(1.1f)
                    .height(48.dp),
                shape = RoundedCornerShape(14.dp),
                color = StageColors.Charcoal,
                border = BorderStroke(1.dp, StageColors.Hairline)
            ) {
                Row(
                    modifier = Modifier.fillMaxSize().padding(3.dp),
                    horizontalArrangement = Arrangement.spacedBy(3.dp)
                ) {
                    val isMic = inputMode == InputMode.MIC
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxSize()
                            .clip(RoundedCornerShape(10.dp))
                            .background(if (isMic) StageColors.Cyan.copy(alpha = 0.22f) else StageColors.Charcoal)
                            .clickable { onSelectMic() },
                        contentAlignment = Alignment.Center
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Rounded.Mic,
                                contentDescription = null,
                                tint = if (isMic) StageColors.Cyan else StageColors.TextMuted,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(3.dp))
                            Text(
                                text = "מיק'",
                                color = if (isMic) StageColors.Cyan else StageColors.TextMuted,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }

                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxSize()
                            .clip(RoundedCornerShape(10.dp))
                            .background(if (!isMic) StageColors.Gold.copy(alpha = 0.22f) else StageColors.Charcoal)
                            .clickable { onSelectDemo() },
                        contentAlignment = Alignment.Center
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Rounded.GraphicEq,
                                contentDescription = null,
                                tint = if (!isMic) StageColors.Gold else StageColors.TextMuted,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(3.dp))
                            Text(
                                text = "דמו",
                                color = if (!isMic) StageColors.Gold else StageColors.TextMuted,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
            }

            // Hold Vamp Pad
            Surface(
                modifier = Modifier
                    .weight(0.95f)
                    .height(48.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .clickable { onToggleVampHold() },
                shape = RoundedCornerShape(14.dp),
                color = if (isVampHolding) StageColors.Coral.copy(alpha = 0.22f) else StageColors.Charcoal,
                border = BorderStroke(
                    width = if (isVampHolding) 1.5.dp else 1.dp,
                    color = if (isVampHolding) StageColors.Coral else StageColors.Hairline
                )
            ) {
                Row(
                    modifier = Modifier.fillMaxSize(),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Loop,
                        contentDescription = null,
                        tint = if (isVampHolding) StageColors.Coral else StageColors.TextSecondary,
                        modifier = Modifier.size(17.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = if (isVampHolding) "VAMP!" else "עצור מעבר",
                        color = if (isVampHolding) StageColors.Coral else StageColors.TextSecondary,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun StudioSettingsSheet(
    style: AccompanimentStyle,
    includeDrums: Boolean,
    reverbMix: Float,
    confidenceThreshold: Float,
    simSpeed: Float,
    hearSinger: Boolean,
    onSelectStyle: (AccompanimentStyle) -> Unit,
    onToggleDrums: (Boolean) -> Unit,
    onReverbChange: (Float) -> Unit,
    onConfidenceChange: (Float) -> Unit,
    onSimSpeedChange: (Float) -> Unit,
    onToggleHearSinger: (Boolean) -> Unit,
    onExportMidi: () -> Unit,
    onOpenDiagnostics: () -> Unit,
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
                .padding(bottom = 26.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = "קונסולת ליווי ואפקטים",
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                color = StageColors.TextPrimary
            )

            // Style cards
            Text(
                text = "סגנון נגינה (Voice-Leading)",
                fontSize = 12.sp,
                color = StageColors.TextMuted
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                AccompanimentStyle.entries.forEach { st ->
                    val selected = st == style
                    Surface(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(12.dp))
                            .clickable { onSelectStyle(st) },
                        shape = RoundedCornerShape(12.dp),
                        color = if (selected) StageColors.Gold.copy(alpha = 0.16f) else StageColors.Charcoal,
                        border = BorderStroke(
                            if (selected) 1.5.dp else 1.dp,
                            if (selected) StageColors.Gold else StageColors.Hairline
                        )
                    ) {
                        Column(modifier = Modifier.padding(10.dp)) {
                            Text(
                                text = st.labelHe,
                                color = if (selected) StageColors.Gold else StageColors.TextPrimary,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = st.subtitleHe,
                                color = StageColors.TextMuted,
                                fontSize = 10.sp
                            )
                        }
                    }
                }
            }

            HorizontalDivider(color = StageColors.Hairline)

            // Switches
            SettingSwitchRow(
                label = "תופים מינימליסטיים (קיק / ריים / מברשות)",
                checked = includeDrums,
                onCheckedChange = onToggleDrums
            )
            SettingSwitchRow(
                label = "נגן אודיו מקורי של השיר (בקובץ שיובא מהמכשיר)",
                checked = hearSinger,
                onCheckedChange = onToggleHearSinger
            )

            // Sliders
            SettingSliderRow(
                label = "חלל Reverb: ${(reverbMix * 100).toInt()}%",
                value = reverbMix,
                range = 0f..0.85f,
                onValueChange = onReverbChange
            )
            SettingSliderRow(
                label = "רגישות מעבר אקורד: ${(confidenceThreshold * 100).toInt()}%",
                value = confidenceThreshold,
                range = 0.30f..0.88f,
                onValueChange = onConfidenceChange
            )
            SettingSliderRow(
                label = "מהירות נגינה (מצב דמו/אימון): x${"%.2f".format(simSpeed)}",
                value = simSpeed,
                range = 0.65f..1.45f,
                onValueChange = onSimSpeedChange
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedButton(
                    onClick = onExportMidi,
                    modifier = Modifier
                        .weight(1f)
                        .height(46.dp),
                    shape = RoundedCornerShape(14.dp),
                    border = BorderStroke(1.dp, StageColors.Cyan.copy(alpha = 0.55f))
                ) {
                    Icon(
                        imageVector = Icons.Rounded.FileDownload,
                        contentDescription = null,
                        tint = StageColors.Cyan,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "ייצוא MIDI",
                        color = StageColors.Cyan,
                        fontWeight = FontWeight.Bold,
                        fontSize = 12.sp
                    )
                }

                OutlinedButton(
                    onClick = onOpenDiagnostics,
                    modifier = Modifier
                        .weight(1f)
                        .height(46.dp),
                    shape = RoundedCornerShape(14.dp),
                    border = BorderStroke(1.dp, StageColors.Gold.copy(alpha = 0.55f))
                ) {
                    Icon(
                        imageVector = Icons.Rounded.BugReport,
                        contentDescription = null,
                        tint = StageColors.Gold,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "ייצוא לוגים",
                        color = StageColors.Gold,
                        fontWeight = FontWeight.Bold,
                        fontSize = 12.sp
                    )
                }
            }
        }
    }
}

@Composable
private fun SettingSwitchRow(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            color = StageColors.TextSecondary,
            fontSize = 13.sp,
            modifier = Modifier.weight(1f).padding(end = 12.dp)
        )
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = StageColors.Obsidian,
                checkedTrackColor = StageColors.Gold,
                uncheckedThumbColor = StageColors.TextMuted,
                uncheckedTrackColor = StageColors.Elevated
            )
        )
    }
}

@Composable
private fun SettingSliderRow(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    onValueChange: (Float) -> Unit
) {
    Column {
        Text(text = label, color = StageColors.TextSecondary, fontSize = 12.sp)
        Slider(
            value = value,
            onValueChange = onValueChange,
            valueRange = range,
            colors = SliderDefaults.colors(
                thumbColor = StageColors.Gold,
                activeTrackColor = StageColors.Gold,
                inactiveTrackColor = StageColors.Elevated
            )
        )
    }
}

private fun formatSec(sec: Double): String {
    val total = sec.toInt().coerceAtLeast(0)
    val m = total / 60
    val s = total % 60
    return "%d:%02d".format(m, s)
}

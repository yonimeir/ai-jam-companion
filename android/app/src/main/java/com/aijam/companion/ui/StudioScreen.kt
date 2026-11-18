package com.aijam.companion.ui

import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.BugReport
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.FileDownload
import androidx.compose.material.icons.rounded.Headphones
import androidx.compose.material.icons.rounded.LibraryMusic
import androidx.compose.material.icons.rounded.Loop
import androidx.compose.material.icons.rounded.Lyrics
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Piano
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
import com.aijam.companion.model.AudioOutputMode
import com.aijam.companion.model.InputMode
import com.aijam.companion.ui.components.ChordRibbon
import com.aijam.companion.ui.components.DiagnosticsSheet
import com.aijam.companion.ui.components.LyricsEditorSheet
import com.aijam.companion.ui.components.LyricsTeleprompter
import com.aijam.companion.ui.components.PianoKeyboard
import com.aijam.companion.ui.components.SectionRoadmapBar
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
    val audioOutputMode by controller.audioOutputMode.collectAsState()
    val isFetchingOnlineLyrics by controller.isFetchingOnlineLyrics.collectAsState()
    val analysisState by controller.analysisState.collectAsState()
    val statusMessage by controller.statusMessage.collectAsState()

    var showLibrarySheet by remember { mutableStateOf(false) }
    var showLyricsSheet by remember { mutableStateOf(false) }
    var showSettingsSheet by remember { mutableStateOf(false) }
    var showDiagnosticsSheet by remember { mutableStateOf(false) }
    var showPianoDrawer by remember { mutableStateOf(false) }

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
                artist = songMap.metadata.artist,
                bpm = songMap.metadata.bpm,
                isAnalyzing = analysisState.isAnalyzing,
                showPianoDrawer = showPianoDrawer,
                onOpenLibrary = { showLibrarySheet = true },
                onOpenLyrics = { showLyricsSheet = true },
                onTogglePianoDrawer = { showPianoDrawer = !showPianoDrawer },
                onOpenDiagnostics = { showDiagnosticsSheet = true },
                onOpenSettings = { showSettingsSheet = true }
            )
        },
        bottomBar = {
            StageConsoleBar(
                isRunning = liveState.isRunning,
                inputMode = liveState.inputMode,
                audioOutputMode = audioOutputMode,
                hasOriginalAudio = liveState.hasOriginalAudio,
                isVampHolding = liveState.isVampHolding,
                refTimeSec = liveState.refTimeSec,
                durationSec = liveState.durationSec,
                progressPct = liveState.progressPct,
                onToggleJam = { controller.toggleJam() },
                onResetToStart = { controller.resetToStart() },
                onSelectOriginalTrack = {
                    if (liveState.hasOriginalAudio) {
                        controller.selectOriginalTrackMode()
                    } else {
                        audioPickerLauncher.launch(arrayOf("audio/*"))
                    }
                },
                onSelectPianoOnly = { controller.selectPianoAccompanimentMode() },
                onSelectMic = { requestMicMode() },
                onToggleVampHold = { controller.setForceVampHold(!liveState.isVampHolding) }
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 14.dp, vertical = 4.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // 1. Interactive Song Structure Roadmap Strip (פתיח • בית 1 • פזמון 1 • בית 2 • גשר • סיום)
            SectionRoadmapBar(
                sections = songMap.sections,
                currentBar = liveState.currentBar,
                refTimeSec = liveState.refTimeSec,
                onSelectSection = { section -> controller.jumpToTime(section.startTime) }
            )

            // 2. Live Harmonic & Vocal Stage Deck (Current Chord -> Next Chord, 4-Beat Metronome, Vocal Entry Countdown)
            StageVisualizer(state = liveState)

            // 3. Centerpiece Apple-Music-Sing Style Lead-Sheet Teleprompter (takes main screen height)
            LyricsTeleprompter(
                lyrics = songMap.lyrics,
                states = songMap.states,
                sections = songMap.sections,
                refTimeSec = liveState.refTimeSec,
                engineMode = liveState.engineMode,
                currentChord = liveState.currentChord,
                onJumpToTime = { controller.jumpToTime(it) },
                onOpenLyricsEditor = { showLyricsSheet = true },
                modifier = Modifier.weight(1f)
            )

            // 4. Compact Bar & Chord Progression Ribbon
            ChordRibbon(
                states = songMap.states,
                currentBar = liveState.currentBar,
                onJumpToBar = { controller.jumpToBar(it) }
            )

            // 5. Optional Collapsible 2-Octave Illuminated Grand Piano Keyboard
            AnimatedVisibility(visible = showPianoDrawer) {
                PianoKeyboard(
                    activeKeysMidi = liveState.activeKeysMidi,
                    chordNotes = liveState.chordNotes,
                    onTapKey = { controller.tapPianoKey(it) }
                )
            }
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
            isFetchingOnline = isFetchingOnlineLyrics,
            onFetchOnlineLyrics = { query -> controller.fetchOnlineLyricsForCurrentSong(query) },
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
            audioOutputMode = audioOutputMode,
            hasOriginalAudio = liveState.hasOriginalAudio,
            onSelectStyle = { controller.setStyle(it) },
            onToggleDrums = { controller.setIncludeDrums(it) },
            onReverbChange = { controller.setReverbMix(it) },
            onConfidenceChange = { controller.setConfidenceThreshold(it) },
            onSimSpeedChange = { controller.setSimSpeed(it) },
            onSelectAudioOutputMode = { controller.setAudioOutputMode(it) },
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
    artist: String,
    bpm: Double,
    isAnalyzing: Boolean,
    showPianoDrawer: Boolean,
    onOpenLibrary: () -> Unit,
    onOpenLyrics: () -> Unit,
    onTogglePianoDrawer: () -> Unit,
    onOpenDiagnostics: () -> Unit,
    onOpenSettings: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Row(
            modifier = Modifier
                .weight(1f)
                .clip(RoundedCornerShape(14.dp))
                .clickable { onOpenLibrary() }
                .padding(end = 6.dp),
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
                        fontSize = 16.sp,
                        fontWeight = FontWeight.ExtraBold,
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
                    text = if (isAnalyzing) "מנתח שיר במכשיר..." else "$artist • ${bpm.toInt()} BPM",
                    color = if (isAnalyzing) StageColors.Cyan else StageColors.TextMuted,
                    fontSize = 11.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(0.dp)) {
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
                    contentDescription = "מילים ו-LRC",
                    tint = StageColors.Cyan
                )
            }
            IconButton(onClick = onTogglePianoDrawer) {
                Icon(
                    imageVector = Icons.Rounded.Piano,
                    contentDescription = "מקלדת פסנתר",
                    tint = if (showPianoDrawer) StageColors.Gold else StageColors.TextMuted
                )
            }
            IconButton(onClick = onOpenDiagnostics) {
                Icon(
                    imageVector = Icons.Rounded.BugReport,
                    contentDescription = "דיאגנוסטיקה",
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
    audioOutputMode: AudioOutputMode,
    hasOriginalAudio: Boolean,
    isVampHolding: Boolean,
    refTimeSec: Double,
    durationSec: Double,
    progressPct: Float,
    onToggleJam: () -> Unit,
    onResetToStart: () -> Unit,
    onSelectOriginalTrack: () -> Unit,
    onSelectPianoOnly: () -> Unit,
    onSelectMic: () -> Unit,
    onToggleVampHold: () -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = StageColors.DeepSlate,
        border = BorderStroke(0.8.dp, StageColors.Hairline)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 14.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // Timecode + Progress Strip (LTR)
            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = formatSec(refTimeSec),
                        color = StageColors.TextSecondary,
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace
                    )
                    LinearProgressIndicator(
                        progress = { (progressPct / 100f).coerceIn(0f, 1f) },
                        modifier = Modifier
                            .weight(1f)
                            .height(4.dp)
                            .clip(RoundedCornerShape(999.dp)),
                        color = StageColors.Gold,
                        trackColor = StageColors.Elevated
                    )
                    Text(
                        text = formatSec(durationSec),
                        color = StageColors.TextMuted,
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace
                    )
                    IconButton(
                        onClick = onResetToStart,
                        modifier = Modifier.size(24.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Replay,
                            contentDescription = "חזור להתחלה",
                            tint = StageColors.TextMuted,
                            modifier = Modifier.size(15.dp)
                        )
                    }
                }
            }

            // Primary Controls Row: Play/Stop + 3-Way Audio Mode Selector + Vamp Hold
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                // Play / Stop Button
                Button(
                    onClick = onToggleJam,
                    modifier = Modifier
                        .weight(1.1f)
                        .height(46.dp),
                    shape = RoundedCornerShape(14.dp),
                    contentPadding = PaddingValues(horizontal = 10.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (isRunning) StageColors.Coral else StageColors.Gold,
                        contentColor = StageColors.Obsidian
                    )
                ) {
                    Icon(
                        imageVector = if (isRunning) Icons.Rounded.Stop else Icons.Rounded.PlayArrow,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = if (isRunning) "עצור" else "נגן",
                        fontWeight = FontWeight.ExtraBold,
                        fontSize = 14.sp
                    )
                }

                // 3-Way Non-Clashing Mode Selector: [פסנתר כנף] [שיר מקורי] [מיקרופון]
                Surface(
                    modifier = Modifier
                        .weight(2.15f)
                        .height(46.dp),
                    shape = RoundedCornerShape(14.dp),
                    color = StageColors.Charcoal,
                    border = BorderStroke(0.8.dp, StageColors.Hairline)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(3.dp),
                        horizontalArrangement = Arrangement.spacedBy(3.dp)
                    ) {
                        val isMic = inputMode == InputMode.MIC
                        val isPianoOnly = !isMic && audioOutputMode == AudioOutputMode.PIANO_ONLY
                        val isOriginal = !isMic && audioOutputMode != AudioOutputMode.PIANO_ONLY

                        // 1. Acoustic Grand Piano Accompaniment Mode
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxSize()
                                .clip(RoundedCornerShape(10.dp))
                                .background(
                                    if (isPianoOnly) StageColors.Gold.copy(alpha = 0.22f)
                                    else StageColors.Charcoal
                                )
                                .clickable { onSelectPianoOnly() },
                            contentAlignment = Alignment.Center
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    imageVector = Icons.Rounded.Piano,
                                    contentDescription = null,
                                    tint = if (isPianoOnly) StageColors.Gold else StageColors.TextMuted,
                                    modifier = Modifier.size(14.dp)
                                )
                                Spacer(modifier = Modifier.width(3.dp))
                                Text(
                                    text = "פסנתר",
                                    color = if (isPianoOnly) StageColors.Gold else StageColors.TextMuted,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }

                        // 2. Original Audio Track Mode (100% clean MP3, zero synth clashing)
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxSize()
                                .clip(RoundedCornerShape(10.dp))
                                .background(
                                    if (isOriginal) StageColors.Emerald.copy(alpha = 0.22f)
                                    else StageColors.Charcoal
                                )
                                .clickable { onSelectOriginalTrack() },
                            contentAlignment = Alignment.Center
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    imageVector = Icons.Rounded.Headphones,
                                    contentDescription = null,
                                    tint = if (isOriginal) StageColors.Emerald
                                    else if (hasOriginalAudio) StageColors.TextSecondary
                                    else StageColors.TextMuted,
                                    modifier = Modifier.size(14.dp)
                                )
                                Spacer(modifier = Modifier.width(3.dp))
                                Text(
                                    text = if (hasOriginalAudio) "מקור" else "+MP3",
                                    color = if (isOriginal) StageColors.Emerald
                                    else if (hasOriginalAudio) StageColors.TextSecondary
                                    else StageColors.TextMuted,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }

                        // 3. Live Microphone Accompaniment Mode
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxSize()
                                .clip(RoundedCornerShape(10.dp))
                                .background(
                                    if (isMic) StageColors.Cyan.copy(alpha = 0.22f)
                                    else StageColors.Charcoal
                                )
                                .clickable { onSelectMic() },
                            contentAlignment = Alignment.Center
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    imageVector = Icons.Rounded.Mic,
                                    contentDescription = null,
                                    tint = if (isMic) StageColors.Cyan else StageColors.TextMuted,
                                    modifier = Modifier.size(14.dp)
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
                    }
                }

                // Hold Vamp Pad
                Surface(
                    modifier = Modifier
                        .weight(0.75f)
                        .height(46.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .clickable { onToggleVampHold() },
                    shape = RoundedCornerShape(14.dp),
                    color = if (isVampHolding) StageColors.Coral.copy(alpha = 0.22f) else StageColors.Charcoal,
                    border = BorderStroke(
                        width = if (isVampHolding) 1.5.dp else 0.8.dp,
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
                            modifier = Modifier.size(15.dp)
                        )
                        Spacer(modifier = Modifier.width(3.dp))
                        Text(
                            text = "לופ",
                            color = if (isVampHolding) StageColors.Coral else StageColors.TextSecondary,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
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
    audioOutputMode: AudioOutputMode,
    hasOriginalAudio: Boolean,
    onSelectStyle: (AccompanimentStyle) -> Unit,
    onToggleDrums: (Boolean) -> Unit,
    onReverbChange: (Float) -> Unit,
    onConfidenceChange: (Float) -> Unit,
    onSimSpeedChange: (Float) -> Unit,
    onSelectAudioOutputMode: (AudioOutputMode) -> Unit,
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
                text = "קונסולת סאונד וליווי פסנתר כנף",
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                color = StageColors.TextPrimary
            )

            if (hasOriginalAudio) {
                Text(
                    text = "מצב שמע (ללא התנגשות בין MP3 לפסנתר)",
                    fontSize = 12.sp,
                    color = StageColors.TextMuted
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    AudioOutputMode.entries.forEach { mode ->
                        val selected = mode == audioOutputMode
                        Surface(
                            modifier = Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(12.dp))
                                .clickable { onSelectAudioOutputMode(mode) },
                            shape = RoundedCornerShape(12.dp),
                            color = if (selected) StageColors.Emerald.copy(alpha = 0.16f) else StageColors.Charcoal,
                            border = BorderStroke(
                                if (selected) 1.5.dp else 1.dp,
                                if (selected) StageColors.Emerald else StageColors.Hairline
                            )
                        ) {
                            Column(modifier = Modifier.padding(10.dp)) {
                                Text(
                                    text = mode.labelHe,
                                    color = if (selected) StageColors.Emerald else StageColors.TextPrimary,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold
                                )
                                Text(
                                    text = mode.subtitleHe,
                                    color = StageColors.TextMuted,
                                    fontSize = 9.sp,
                                    maxLines = 2
                                )
                            }
                        }
                    }
                }
            }

            Text(
                text = "סגנון נגינת הפסנתר (Voice-Leading)",
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

            SettingSwitchRow(
                label = "הוסף פעימת תופים עדינה (כבוי כברירת מחדל בבלדות פסנתר)",
                checked = includeDrums,
                onCheckedChange = onToggleDrums
            )

            SettingSliderRow(
                label = "הדהוד אולם קונצרטים (Reverb): ${(reverbMix * 100).toInt()}%",
                value = reverbMix,
                range = 0f..0.85f,
                onValueChange = onReverbChange
            )
            SettingSliderRow(
                label = "רגישות מעבר אקורד (במצב מיקרופון): ${(confidenceThreshold * 100).toInt()}%",
                value = confidenceThreshold,
                range = 0.30f..0.88f,
                onValueChange = onConfidenceChange
            )
            SettingSliderRow(
                label = "מהירות נגינה / אימון: x${"%.2f".format(simSpeed)}",
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
            modifier = Modifier
                .weight(1f)
                .padding(end = 12.dp)
        )
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = StageColors.Obsidian,
                checkedTrackColor = StageColors.Gold,
                uncheckedThumbColor = StageColors.TextMuted,
                uncheckedTrackColor = StageColors.Charcoal
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
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = label,
            color = StageColors.TextSecondary,
            fontSize = 12.sp
        )
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

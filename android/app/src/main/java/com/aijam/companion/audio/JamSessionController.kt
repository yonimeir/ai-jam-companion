package com.aijam.companion.audio

import android.annotation.SuppressLint
import android.app.Application
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.aijam.companion.data.LyricsParser
import com.aijam.companion.data.SongLibraryRepository
import com.aijam.companion.engine.MidiExporter
import com.aijam.companion.engine.RealtimeJamTracker
import com.aijam.companion.engine.TrackerStepResult
import com.aijam.companion.model.AccompanimentStyle
import com.aijam.companion.model.InputMode
import com.aijam.companion.model.LiveJamState
import com.aijam.companion.model.SongMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

data class AnalysisUiState(
    val isAnalyzing: Boolean = false,
    val progress: Float = 0f,
    val stageMessage: String = "",
    val error: String? = null
)

/**
 * Coordinates the live jam engine, AudioRecord microphone capture, Demo singer synthesis,
 * low-latency AudioTrack accompaniment synth, on-device MP3/WAV analysis, lyrics, and MIDI export.
 */
class JamSessionController(application: Application) : AndroidViewModel(application) {

    private val repository = SongLibraryRepository(application.applicationContext)
    private val synth = PolyphonicAudioSynth().apply { start() }

    private val _library = MutableStateFlow<List<SongMap>>(emptyList())
    val library: StateFlow<List<SongMap>> = _library.asStateFlow()

    private val _songMap = MutableStateFlow(repository.loadDemoSong())
    val songMap: StateFlow<SongMap> = _songMap.asStateFlow()

    private val _liveState = MutableStateFlow(initialLiveState(_songMap.value))
    val liveState: StateFlow<LiveJamState> = _liveState.asStateFlow()

    private val _style = MutableStateFlow(AccompanimentStyle.BALLAD_ARPEGGIO)
    val style: StateFlow<AccompanimentStyle> = _style.asStateFlow()

    private val _includeDrums = MutableStateFlow(true)
    val includeDrums: StateFlow<Boolean> = _includeDrums.asStateFlow()

    private val _reverbMix = MutableStateFlow(0.28f)
    val reverbMix: StateFlow<Float> = _reverbMix.asStateFlow()

    private val _confidenceThreshold = MutableStateFlow(0.55f)
    val confidenceThreshold: StateFlow<Float> = _confidenceThreshold.asStateFlow()

    private val _simSpeed = MutableStateFlow(1.0f)
    val simSpeed: StateFlow<Float> = _simSpeed.asStateFlow()

    private val _hearSinger = MutableStateFlow(true)
    val hearSinger: StateFlow<Boolean> = _hearSinger.asStateFlow()

    private val _analysisState = MutableStateFlow(AnalysisUiState())
    val analysisState: StateFlow<AnalysisUiState> = _analysisState.asStateFlow()

    private val _statusMessage = MutableStateFlow<String?>(null)
    val statusMessage: StateFlow<String?> = _statusMessage.asStateFlow()

    private var tracker: RealtimeJamTracker = createTracker(_songMap.value)
    private var jamJob: Job? = null
    private var analysisJob: Job? = null

    private val pitchHistory = ArrayDeque<Float>(64)
    private val targetHistory = ArrayDeque<Float>(64)
    private val keyExpiryMs = mutableMapOf<Int, Long>()

    @Volatile private var beatPulseLevel = 0f
    @Volatile private var drumPulseLevel = 0f

    init {
        _library.value = repository.listSongs()
        synth.reverbMix = _reverbMix.value
    }

    private fun initialLiveState(song: SongMap): LiveJamState {
        val firstState = song.states.firstOrNull()
        val firstSection = song.sections.firstOrNull()?.label ?: "Intro"
        return LiveJamState(
            isRunning = false,
            inputMode = InputMode.DEMO,
            currentChord = firstState?.chord ?: "C",
            chordQuality = if (firstState?.chord?.endsWith("m") == true) "Minor Triad" else "Major Triad",
            chordNotes = firstState?.notes ?: listOf(60, 64, 67),
            currentBar = 1,
            totalBars = song.metadata.totalBars.coerceAtLeast(1),
            currentBeat = 1,
            currentSection = firstSection,
            refTimeSec = 0.0,
            durationSec = song.metadata.duration.coerceAtLeast(1.0),
            progressPct = 0f
        )
    }

    private fun createTracker(song: SongMap): RealtimeJamTracker {
        return RealtimeJamTracker(
            songMap = song,
            sampleRate = 22050,
            windowSize = 2048,
            hopSize = 512,
            confidenceThreshold = _confidenceThreshold.value.toDouble(),
            style = _style.value,
            includeDrums = _includeDrums.value
        )
    }

    fun clearStatusMessage() {
        _statusMessage.value = null
    }

    fun setInputMode(mode: InputMode) {
        val wasRunning = _liveState.value.isRunning
        if (wasRunning) stopJam()
        _liveState.value = _liveState.value.copy(inputMode = mode)
        if (wasRunning) startJam()
    }

    fun setStyle(newStyle: AccompanimentStyle) {
        _style.value = newStyle
        tracker.style = newStyle
    }

    fun setIncludeDrums(enabled: Boolean) {
        _includeDrums.value = enabled
        tracker.includeDrums = enabled
    }

    fun setReverbMix(mix: Float) {
        val clamped = mix.coerceIn(0f, 1f)
        _reverbMix.value = clamped
        synth.reverbMix = clamped
    }

    fun setConfidenceThreshold(value: Float) {
        val clamped = value.coerceIn(0.25f, 0.90f)
        _confidenceThreshold.value = clamped
        tracker.setConfidenceThreshold(clamped.toDouble())
    }

    fun setSimSpeed(speed: Float) {
        _simSpeed.value = speed.coerceIn(0.6f, 1.5f)
    }

    fun setHearSinger(enabled: Boolean) {
        _hearSinger.value = enabled
        if (!enabled) synth.setDemoVoice(0.0, false, 0f)
    }

    fun setForceVampHold(hold: Boolean) {
        tracker.stateMachine.forceVampHold = hold
        _liveState.value = _liveState.value.copy(isVampHolding = hold)
    }

    fun toggleJam() {
        if (_liveState.value.isRunning) stopJam() else startJam()
    }

    fun startJam() {
        if (jamJob?.isActive == true) return
        val mode = _liveState.value.inputMode
        _liveState.value = _liveState.value.copy(isRunning = true)
        jamJob = viewModelScope.launch(Dispatchers.Default) {
            if (mode == InputMode.MIC) {
                runMicrophoneLoop()
            } else {
                runDemoLoop()
            }
        }
    }

    fun stopJam() {
        jamJob?.cancel()
        jamJob = null
        synth.panic()
        keyExpiryMs.clear()
        beatPulseLevel = 0f
        drumPulseLevel = 0f
        _liveState.value = _liveState.value.copy(
            isRunning = false,
            isVoiced = false,
            singerF0Hz = 0f,
            singerNoteName = "---",
            activeKeysMidi = emptySet(),
            waveform = FloatArray(128),
            beatPulse = 0f,
            drumPulse = 0f
        )
    }

    fun resetToStart() {
        val wasRunning = _liveState.value.isRunning
        stopJam()
        tracker.reset()
        pitchHistory.clear()
        targetHistory.clear()
        _liveState.value = initialLiveState(_songMap.value).copy(inputMode = _liveState.value.inputMode)
        if (wasRunning) startJam()
    }

    fun jumpToBar(bar: Int) {
        val state = _songMap.value.states.firstOrNull { it.bar == bar } ?: return
        jumpToTime(state.startTime)
    }

    fun jumpToTime(timeSec: Double) {
        val target = timeSec.coerceIn(0.0, _songMap.value.metadata.duration)
        tracker.jumpToTime(target)
        val state = _songMap.value.states.firstOrNull { target >= it.startTime && target < it.endTime }
            ?: _songMap.value.states.minByOrNull { kotlin.math.abs(it.startTime - target) }
        val pct = if (_songMap.value.metadata.duration > 0.0) {
            ((target / _songMap.value.metadata.duration) * 100.0).toFloat().coerceIn(0f, 100f)
        } else 0f

        _liveState.value = _liveState.value.copy(
            refTimeSec = target,
            progressPct = pct,
            currentBar = state?.bar ?: _liveState.value.currentBar,
            currentChord = state?.chord ?: _liveState.value.currentChord,
            chordNotes = state?.notes ?: _liveState.value.chordNotes
        )
    }

    fun tapPianoKey(midiNote: Int) {
        synth.triggerPianoNotes(listOf(midiNote), velocity = 92, durationSec = 0.85)
        val now = System.currentTimeMillis()
        keyExpiryMs[midiNote] = now + 260L
        _liveState.value = _liveState.value.copy(activeKeysMidi = keyExpiryMs.keys.toSet())
    }

    fun selectSong(song: SongMap) {
        stopJam()
        _songMap.value = song
        tracker = createTracker(song)
        pitchHistory.clear()
        targetHistory.clear()
        _liveState.value = initialLiveState(song).copy(inputMode = _liveState.value.inputMode)
        _statusMessage.value = "נטען: ${song.metadata.title}"
    }

    fun deleteSong(songId: String) {
        if (repository.deleteSong(songId)) {
            val updated = repository.listSongs()
            _library.value = updated
            if (_songMap.value.metadata.id == songId) {
                selectSong(updated.first())
            }
            _statusMessage.value = "השיר הוסר מהספרייה"
        }
    }

    fun updateSongLyrics(rawText: String, targetSong: SongMap = _songMap.value) {
        val aligned = LyricsParser.parseAndAlign(rawText, targetSong)
        val saved = repository.updateLyrics(targetSong, aligned)
        _library.value = repository.listSongs()
        if (_songMap.value.metadata.id == saved.metadata.id) {
            _songMap.value = saved
        }
        _statusMessage.value = if (aligned.isEmpty()) {
            "המילים נוקו"
        } else {
            "סונכרנו ${aligned.size} שורות לשיר"
        }
    }

    fun importAudioUri(uri: Uri) {
        if (analysisJob?.isActive == true) return
        stopJam()

        analysisJob = viewModelScope.launch(Dispatchers.Default) {
            try {
                val context = getApplication<Application>().applicationContext
                _analysisState.value = AnalysisUiState(
                    isAnalyzing = true,
                    progress = 0.04f,
                    stageMessage = "מפענח אודיו על המכשיר (MediaCodec)..."
                )

                val decoded = AudioFileDecoder.decodeUri(context, uri) { decPct ->
                    _analysisState.value = AnalysisUiState(
                        isAnalyzing = true,
                        progress = 0.04f + decPct * 0.28f,
                        stageMessage = "מפענח אודיו (${(decPct * 100).toInt()}%)..."
                    )
                }

                val newId = SongLibraryRepository.sanitizeId(
                    "${decoded.title}_${System.currentTimeMillis() % 10000}"
                )
                val analyzed = OfflineSongAnalyzer.analyze(
                    samples = decoded.samples,
                    sampleRate = decoded.sampleRate,
                    id = newId,
                    title = decoded.title,
                    artist = decoded.artist
                ) { dspPct, msg ->
                    _analysisState.value = AnalysisUiState(
                        isAnalyzing = true,
                        progress = 0.32f + dspPct * 0.65f,
                        stageMessage = msg
                    )
                }

                val saved = repository.saveSong(analyzed)
                _library.value = repository.listSongs()
                _analysisState.value = AnalysisUiState(isAnalyzing = false, progress = 1f)
                selectSong(saved)
                _statusMessage.value = "נותח בהצלחה: ${saved.metadata.title} (${saved.metadata.bpm.toInt()} BPM)"
            } catch (e: Exception) {
                _analysisState.value = AnalysisUiState(
                    isAnalyzing = false,
                    progress = 0f,
                    error = e.message ?: "שגיאה בניתוח הקובץ"
                )
            }
        }
    }

    fun dismissAnalysisError() {
        _analysisState.value = AnalysisUiState()
    }

    fun exportMidiBytes(): ByteArray {
        return MidiExporter.buildMidiBytes(
            timeline = tracker.scheduledTimeline.toList(),
            bpm = tracker.bpm
        )
    }

    private suspend fun runDemoLoop() {
        val hopSize = 512
        val sampleRate = 22050
        val hopMs = (hopSize * 1000.0) / sampleRate
        val chunk = FloatArray(hopSize)
        val anchor = _songMap.value.vocalAnchor
        val totalAnchorFrames = anchor.times.size

        var anchorPos: Double = if (anchor.times.isNotEmpty()) {
            val currRef = _liveState.value.refTimeSec
            anchor.times.indexOfFirst { it >= currRef }.let { if (it < 0) 0.0 else it.toDouble() }
        } else 0.0

        var synthPhase = 0.0
        var vibPhase = 0.0
        val twoPi = 2.0 * PI

        while (coroutineContextIsActive()) {
            val stepStartNs = System.nanoTime()
            val speed = _simSpeed.value.toDouble()
            val frameIdx = anchorPos.toInt().coerceIn(0, max(0, totalAnchorFrames - 1))

            val isVoicedRef = totalAnchorFrames > 0 &&
                anchor.voicedProb[frameIdx] > 0.35 &&
                anchor.f0Hz[frameIdx] > 50.0
            val refF0 = if (isVoicedRef) anchor.f0Hz[frameIdx] else 0.0
            val rmsDb = if (totalAnchorFrames > 0) anchor.rmsDb[frameIdx] else -55.0
            val amp = if (isVoicedRef) {
                (0.14 * exp((rmsDb.coerceIn(-45.0, -8.0) + 20.0) / 22.0)).toFloat().coerceIn(0.06f, 0.42f)
            } else 0.0f

            // Synthesize clean harmonic vocal hop buffer for real-time FFT-YIN tracker
            for (i in 0 until hopSize) {
                if (isVoicedRef) {
                    vibPhase = (vibPhase + twoPi * 5.1 / sampleRate) % twoPi
                    val f = refF0 * (1.0 + 0.005 * sin(vibPhase))
                    synthPhase = (synthPhase + twoPi * f / sampleRate) % twoPi
                    chunk[i] = (amp * (0.72 * sin(synthPhase) + 0.28 * sin(2.0 * synthPhase))).toFloat()
                } else {
                    // Low-level room floor when breathing / silent
                    chunk[i] = ((sin(i * 0.37) * 0.0005)).toFloat()
                }
            }

            if (_hearSinger.value && !tracker.stateMachine.forceVampHold) {
                synth.setDemoVoice(refF0, isVoicedRef, amp * 0.72f)
            } else {
                synth.setDemoVoice(0.0, false, 0f)
            }

            val res = tracker.processAudioBuffer(chunk)
            if (res != null) {
                handleStepResult(res, chunk)
            }

            if (!tracker.stateMachine.forceVampHold && totalAnchorFrames > 1) {
                anchorPos += speed
                if (anchorPos >= totalAnchorFrames - 1) {
                    // Smooth loop restart from beginning
                    anchorPos = 0.0
                    tracker.jumpToTime(0.0)
                    pitchHistory.clear()
                    targetHistory.clear()
                }
            }

            val elapsedMs = (System.nanoTime() - stepStartNs) / 1_000_000.0
            val sleepMs = max(4L, (hopMs - elapsedMs).toLong())
            delay(sleepMs)
        }
        synth.setDemoVoice(0.0, false, 0f)
    }

    @SuppressLint("MissingPermission")
    private suspend fun runMicrophoneLoop() {
        synth.setDemoVoice(0.0, false, 0f)
        val sampleRate = 22050
        val hopSize = 512
        val minBuf = AudioRecord.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        ).coerceAtLeast(hopSize * 4)

        val recorder = try {
            AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                sampleRate,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                minBuf
            )
        } catch (_: SecurityException) {
            _statusMessage.value = "נדרשת הרשאת מיקרופון להאזנה חיה"
            _liveState.value = _liveState.value.copy(isRunning = false, inputMode = InputMode.DEMO)
            return
        }

        if (recorder.state != AudioRecord.STATE_INITIALIZED) {
            recorder.release()
            _statusMessage.value = "לא ניתן לפתוח את המיקרופון במכשיר"
            _liveState.value = _liveState.value.copy(isRunning = false)
            return
        }

        val shortBuf = ShortArray(hopSize)
        val floatBuf = FloatArray(hopSize)

        try {
            recorder.startRecording()
            while (coroutineContextIsActive()) {
                val read = recorder.read(shortBuf, 0, hopSize)
                if (read > 0) {
                    val norm = 1f / 32768f
                    for (i in 0 until read) {
                        floatBuf[i] = shortBuf[i] * norm
                    }
                    val inputSlice = if (read == hopSize) floatBuf else floatBuf.copyOf(read)
                    val res = tracker.processAudioBuffer(inputSlice)
                    if (res != null) {
                        handleStepResult(res, inputSlice)
                    }
                } else {
                    delay(10)
                }
            }
        } finally {
            runCatching {
                recorder.stop()
                recorder.release()
            }
        }
    }

    private suspend fun coroutineContextIsActive(): Boolean {
        return kotlinx.coroutines.currentCoroutineContext().isActive
    }

    private fun handleStepResult(res: TrackerStepResult, rawAudio: FloatArray) {
        val secPerBeat = 60.0 / tracker.bpm
        val nowMs = System.currentTimeMillis()

        if (res.triggeredNotes.isNotEmpty() && res.durationBeats > 0.0) {
            val durSec = res.durationBeats * secPerBeat
            synth.triggerPianoNotes(res.triggeredNotes, res.pianoVelocity, durSec)
            val holdMs = (durSec * 650.0).toLong().coerceIn(180L, 1200L)
            res.triggeredNotes.forEach { keyExpiryMs[it] = nowMs + holdMs }
            beatPulseLevel = 1f
        } else {
            beatPulseLevel *= 0.86f
        }

        if (res.drumHits.isNotEmpty()) {
            synth.triggerDrums(res.drumHits)
            drumPulseLevel = 1f
        } else {
            drumPulseLevel *= 0.82f
        }

        // Prune expired lit keys
        keyExpiryMs.entries.removeAll { it.value < nowMs }

        // Downsample 512-sample audio slice into 128-point waveform
        val wave = FloatArray(128)
        val stride = max(1, rawAudio.size / 128)
        for (i in 0 until 128) {
            val idx = min(rawAudio.lastIndex, i * stride)
            wave[i] = rawAudio[idx]
        }

        // Lookup target melody pitch at refTimeSec for the visual Pitch Horizon
        val targetMidi = lookupTargetMidi(res.refTimeSec)
        pitchHistory.addLast(if (res.isVoiced) res.singerMidi.toFloat() else 0f)
        targetHistory.addLast(targetMidi)
        if (pitchHistory.size > 64) pitchHistory.removeFirst()
        if (targetHistory.size > 64) targetHistory.removeFirst()

        _liveState.value = _liveState.value.copy(
            isRunning = true,
            engineMode = res.engineMode,
            currentChord = res.chord,
            chordQuality = res.quality,
            chordNotes = res.chordNotes,
            currentBar = res.bar,
            totalBars = _songMap.value.metadata.totalBars.coerceAtLeast(1),
            currentBeat = res.beat,
            currentSection = res.section,
            refTimeSec = res.refTimeSec,
            durationSec = _songMap.value.metadata.duration.coerceAtLeast(1.0),
            progressPct = res.progressPct,
            confidence = res.confidence.toFloat(),
            tempoRatio = res.tempoRatio.toFloat(),
            singerF0Hz = res.singerF0Hz.toFloat(),
            singerNoteName = res.singerNoteName,
            singerMidi = res.singerMidi.toFloat(),
            targetMidi = targetMidi,
            isVoiced = res.isVoiced,
            pianoVelocity = res.pianoVelocity,
            activeKeysMidi = keyExpiryMs.keys.toSet(),
            waveform = wave,
            pitchTrail = pitchHistory.toList(),
            targetPitchTrail = targetHistory.toList(),
            beatPulse = beatPulseLevel,
            drumPulse = drumPulseLevel,
            isVampHolding = tracker.stateMachine.forceVampHold
        )
    }

    private fun lookupTargetMidi(refTime: Double): Float {
        val anchor = _songMap.value.vocalAnchor
        if (anchor.times.isEmpty()) return 0f
        val approxIdx = ((refTime / _songMap.value.metadata.duration.coerceAtLeast(0.1)) *
            (anchor.times.size - 1)).toInt().coerceIn(0, anchor.times.lastIndex)
        return if (anchor.voicedProb[approxIdx] > 0.35 && anchor.midiNotes[approxIdx] > 0.0) {
            anchor.midiNotes[approxIdx].toFloat()
        } else 0f
    }

    override fun onCleared() {
        stopJam()
        analysisJob?.cancel()
        synth.stop()
        super.onCleared()
    }
}

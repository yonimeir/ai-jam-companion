package com.aijam.companion.engine

import com.aijam.companion.model.AccompanimentStyle
import com.aijam.companion.model.EngineMode
import com.aijam.companion.model.ScheduledNoteEvent
import com.aijam.companion.model.SongMap
import com.aijam.companion.util.DiagnosticLogger
import kotlin.math.floor
import kotlin.math.max

data class TrackerStepResult(
    val refTimeSec: Double,
    val progressPct: Float,
    val section: String,
    val bar: Int,
    val beat: Int,
    val chord: String,
    val quality: String,
    val chordNotes: List<Int>,
    val engineMode: EngineMode,
    val confidence: Double,
    val tempoRatio: Double,
    val singerF0Hz: Double,
    val singerNoteName: String,
    val singerMidi: Double,
    val isVoiced: Boolean,
    val pianoVelocity: Int,
    val triggeredNotes: List<Int>,
    val durationBeats: Double,
    val drumHits: List<DrumHit>
)

/**
 * Coordinates sliding ring-buffer audio framing, FFT-YIN pitch detection,
 * monotonic Online DTW alignment, Harmonic State Machine, phase-locked beat clock,
 * voice-leading, and MIDI timeline capture.
 */
class RealtimeJamTracker(
    private val songMap: SongMap,
    private val sampleRate: Int = 22050,
    private val windowSize: Int = 2048,
    private val hopSize: Int = 512,
    confidenceThreshold: Double = 0.55,
    var style: AccompanimentStyle = AccompanimentStyle.BALLAD_ARPEGGIO,
    var includeDrums: Boolean = false
) {
    val bpm: Double = songMap.metadata.bpm.coerceIn(40.0, 220.0)
    private val pitchDetector = RealtimePitchDetector(sampleRate = sampleRate)
    val scoreFollower = OnlineScoreFollower(songMap = songMap)
    val stateMachine = HarmonicStateMachine(songMap = songMap, confidenceThreshold = confidenceThreshold)
    private val velocityMapper = VelocityMapper()

    private val ringBuffer = ArrayList<Float>(windowSize * 2)
    private val hopDt = hopSize.toDouble() / sampleRate
    private var elapsedTime = 0.0
    private var performanceBeat = 0.0
    private var internalBeatPhase = 0.0
    private var lastBeatIndex = -1
    private var prevRhNotes: List<Int>? = null
    private var lastPianoTriggerTimeSec = -10.0
    private var demoCursorSec = 0.0

    val scheduledTimeline = mutableListOf<ScheduledNoteEvent>()
    var lastStatus: TrackerStepResult? = null
        private set

    fun setConfidenceThreshold(value: Double) {
        stateMachine.confidenceThreshold = value.coerceIn(0.2, 0.95)
    }

    fun reset() {
        scoreFollower.reset()
        stateMachine.reset()
        velocityMapper.reset()
        ringBuffer.clear()
        elapsedTime = 0.0
        performanceBeat = 0.0
        internalBeatPhase = 0.0
        lastBeatIndex = -1
        prevRhNotes = null
        lastPianoTriggerTimeSec = -10.0
        demoCursorSec = 0.0
        scheduledTimeline.clear()
        lastStatus = null
    }

    fun jumpToTime(targetTimeSec: Double) {
        val clamped = targetTimeSec.coerceIn(0.0, songMap.metadata.duration.coerceAtLeast(0.1))
        scoreFollower.jumpToTime(clamped)
        demoCursorSec = clamped
        val secPerBeat = 60.0 / bpm
        internalBeatPhase = clamped / secPerBeat
        lastBeatIndex = floor(internalBeatPhase).toInt() - 1
    }

    /**
     * Steady-clock step used in Demo / Practice / Original Song Playback mode.
     * Advances rhythmically without feeding synthetic buzz-saw oscillators into FFT-YIN.
     */
    fun stepSteadyClock(
        dtSec: Double = hopDt,
        speedMultiplier: Double = 1.0,
        externalTimeSec: Double? = null
    ): TrackerStepResult {
        val prevRefTime = lastStatus?.refTimeSec ?: demoCursorSec
        val prevChord = lastStatus?.chord ?: songMap.states.firstOrNull()?.chord ?: "C"
        val prevMode = lastStatus?.engineMode ?: EngineMode.PLAYING

        elapsedTime += dtSec
        val secPerBeat = 60.0 / bpm
        val effSpeed = if (stateMachine.forceVampHold) 1.0 else speedMultiplier.coerceIn(0.5, 1.75)

        if (!stateMachine.forceVampHold) {
            if (externalTimeSec != null) {
                demoCursorSec = externalTimeSec.coerceIn(0.0, songMap.metadata.duration.coerceAtLeast(0.1))
            } else {
                demoCursorSec += dtSec * effSpeed
                if (demoCursorSec >= songMap.metadata.duration && songMap.metadata.duration > 1.0) {
                    demoCursorSec = 0.0
                    internalBeatPhase = 0.0
                    lastBeatIndex = -1
                    DiagnosticLogger.log("DEMO", "Looping song back to start (0.0s)")
                }
            }
            scoreFollower.jumpToTime(demoCursorSec)
        }

        val refTime = demoCursorSec
        val progressPct = scoreFollower.getProgressPercent()

        // Lookup reference vocal melody for visual guide & dynamic velocity
        val anchor = songMap.vocalAnchor
        val frameIdx = if (anchor.times.isNotEmpty() && songMap.metadata.duration > 0.0) {
            ((refTime / songMap.metadata.duration) * (anchor.times.size - 1))
                .toInt().coerceIn(0, anchor.times.lastIndex)
        } else -1

        val isVoicedRef = frameIdx >= 0 &&
            anchor.voicedProb[frameIdx] > 0.45 &&
            anchor.f0Hz[frameIdx] > 65.0
        val refF0 = if (isVoicedRef) anchor.f0Hz[frameIdx] else 0.0
        val refMidi = if (isVoicedRef) anchor.midiNotes[frameIdx] else 0.0
        val refRms = if (frameIdx >= 0) anchor.rmsDb[frameIdx] else -24.0

        val hState = stateMachine.update(
            refTime = refTime,
            confidence = 0.96,
            isVoiced = !stateMachine.forceVampHold,
            dt = dtSec,
            refExpectsVocal = isVoicedRef
        )

        val velocity = velocityMapper.mapRmsToVelocity(refRms.coerceAtLeast(-28.0))
        val isVamping = hState.engineMode == EngineMode.VAMPING

        // Advance phase-locked beat clock
        performanceBeat += (dtSec / secPerBeat) * effSpeed
        internalBeatPhase = if (!isVamping && externalTimeSec != null) {
            refTime / secPerBeat
        } else {
            internalBeatPhase + (dtSec / secPerBeat) * effSpeed
        }

        val currentBeatIndex = floor(internalBeatPhase).toInt()
        val beatInBar = if (isVamping) {
            hState.vampCycleBeat
        } else {
            ((currentBeatIndex.coerceAtLeast(0)) % 4) + 1
        }

        val (triggeredNotes, durationBeats, drumHits) = triggerBeatIfNeeded(
            currentBeatIndex = currentBeatIndex,
            chord = hState.chord,
            beatInBar = beatInBar,
            velocity = velocity,
            isVamping = isVamping
        )

        DiagnosticLogger.recordFrameStep(
            refTimeSec = refTime,
            bar = hState.bar,
            beat = beatInBar,
            chord = hState.chord,
            mode = hState.engineMode.name,
            isVoiced = isVoicedRef,
            singerNote = VoicingEngine.midiToNoteName(refMidi),
            rmsDb = refRms,
            confidence = hState.confidence,
            tempoRatio = effSpeed,
            beatTriggered = triggeredNotes.isNotEmpty() || drumHits.isNotEmpty(),
            chordChanged = hState.chord != prevChord,
            enteredVamp = hState.engineMode == EngineMode.VAMPING && prevMode != EngineMode.VAMPING
        )

        val res = TrackerStepResult(
            refTimeSec = refTime,
            progressPct = progressPct,
            section = hState.section,
            bar = hState.bar,
            beat = beatInBar,
            chord = hState.chord,
            quality = formatChordQuality(hState.chord),
            chordNotes = hState.notes,
            engineMode = hState.engineMode,
            confidence = hState.confidence,
            tempoRatio = effSpeed,
            singerF0Hz = refF0,
            singerNoteName = VoicingEngine.midiToNoteName(refMidi),
            singerMidi = refMidi,
            isVoiced = isVoicedRef,
            pianoVelocity = velocity,
            triggeredNotes = triggeredNotes,
            durationBeats = durationBeats,
            drumHits = drumHits
        )
        lastStatus = res
        return res
    }

    fun processSingleHop(window: FloatArray): TrackerStepResult {
        val prevChord = lastStatus?.chord ?: songMap.states.firstOrNull()?.chord ?: "C"
        val prevMode = lastStatus?.engineMode ?: EngineMode.PLAYING

        elapsedTime += hopDt
        val secPerBeat = 60.0 / bpm

        val pitchFrame = pitchDetector.processChunk(window)

        // Speaker-bleed suppression: right after triggering a piano chord, require stronger vocal signal
        val recentlyTriggeredPiano = (elapsedTime - lastPianoTriggerTimeSec) < 0.14
        val effectiveVoiced = if (recentlyTriggeredPiano) {
            pitchFrame.isVoiced && pitchFrame.rmsDb > -38.0 && pitchFrame.confidence >= 0.72
        } else {
            pitchFrame.isVoiced
        }

        val (refFrameIdx, refTime, alignConf) = scoreFollower.step(
            liveMidi = pitchFrame.midiNote,
            liveVoiced = effectiveVoiced
        )
        demoCursorSec = refTime
        val progressPct = scoreFollower.getProgressPercent()
        val refExpectsVocal = scoreFollower.isReferenceVocalExpected(refFrameIdx)

        val hState = stateMachine.update(
            refTime = refTime,
            confidence = alignConf,
            isVoiced = effectiveVoiced,
            dt = hopDt,
            refExpectsVocal = refExpectsVocal
        )

        val velocity = velocityMapper.mapRmsToVelocity(pitchFrame.rmsDb)
        val isVamping = hState.engineMode == EngineMode.VAMPING

        // Continuous phase-locked beat clock (never jumps backward or double-fires on state transitions)
        val tempoScale = if (isVamping || hState.engineMode == EngineMode.CONFIDENCE_WAIT) {
            1.0
        } else {
            scoreFollower.estimatedTempoRatio.coerceIn(0.88, 1.12)
        }
        performanceBeat += (hopDt / secPerBeat) * tempoScale
        internalBeatPhase += (hopDt / secPerBeat) * tempoScale

        val currentBeatIndex = floor(internalBeatPhase).toInt()
        val currentBeat = if (isVamping) {
            hState.vampCycleBeat
        } else {
            ((currentBeatIndex.coerceAtLeast(0)) % 4) + 1
        }

        val (triggeredNotes, durationBeats, drumHits) = triggerBeatIfNeeded(
            currentBeatIndex = currentBeatIndex,
            chord = hState.chord,
            beatInBar = currentBeat,
            velocity = velocity,
            isVamping = isVamping
        )

        DiagnosticLogger.recordFrameStep(
            refTimeSec = refTime,
            bar = hState.bar,
            beat = currentBeat,
            chord = hState.chord,
            mode = hState.engineMode.name,
            isVoiced = effectiveVoiced,
            singerNote = VoicingEngine.midiToNoteName(if (effectiveVoiced) pitchFrame.midiNote else 0.0),
            rmsDb = pitchFrame.rmsDb,
            confidence = hState.confidence,
            tempoRatio = scoreFollower.estimatedTempoRatio,
            beatTriggered = triggeredNotes.isNotEmpty() || drumHits.isNotEmpty(),
            chordChanged = hState.chord != prevChord,
            enteredVamp = hState.engineMode == EngineMode.VAMPING && prevMode != EngineMode.VAMPING
        )

        val res = TrackerStepResult(
            refTimeSec = refTime,
            progressPct = progressPct,
            section = hState.section,
            bar = hState.bar,
            beat = currentBeat,
            chord = hState.chord,
            quality = formatChordQuality(hState.chord),
            chordNotes = hState.notes,
            engineMode = hState.engineMode,
            confidence = hState.confidence,
            tempoRatio = scoreFollower.estimatedTempoRatio,
            singerF0Hz = if (effectiveVoiced) pitchFrame.f0Hz else 0.0,
            singerNoteName = VoicingEngine.midiToNoteName(if (effectiveVoiced) pitchFrame.midiNote else 0.0),
            singerMidi = if (effectiveVoiced) pitchFrame.midiNote else 0.0,
            isVoiced = effectiveVoiced,
            pianoVelocity = velocity,
            triggeredNotes = triggeredNotes,
            durationBeats = durationBeats,
            drumHits = drumHits
        )
        lastStatus = res
        return res
    }

    private fun triggerBeatIfNeeded(
        currentBeatIndex: Int,
        chord: String,
        beatInBar: Int,
        velocity: Int,
        isVamping: Boolean
    ): Triple<List<Int>, Double, List<DrumHit>> {
        if (currentBeatIndex <= lastBeatIndex) {
            return Triple(emptyList(), 0.0, emptyList())
        }
        lastBeatIndex = currentBeatIndex

        val triggeredNotes = mutableListOf<Int>()
        val voicing = VoicingEngine.generatePianoVoicing(
            chordName = chord,
            style = style,
            beatInBar = beatInBar,
            prevNotes = prevRhNotes
        )
        if (voicing.harmonyNotes.isNotEmpty()) {
            prevRhNotes = voicing.harmonyNotes
        }
        if (voicing.bassNote != null) triggeredNotes.add(voicing.bassNote)
        triggeredNotes.addAll(voicing.harmonyNotes)
        val durationBeats = voicing.durationBeats

        if (triggeredNotes.isNotEmpty() && durationBeats > 0.0) {
            lastPianoTriggerTimeSec = elapsedTime
            scheduledTimeline.add(
                ScheduledNoteEvent(
                    notes = triggeredNotes.toList(),
                    startBeat = performanceBeat,
                    durationBeats = durationBeats,
                    velocity = velocity,
                    channel = 0
                )
            )
        }

        var drumHits = emptyList<DrumHit>()
        if (includeDrums) {
            drumHits = VoicingEngine.generateDrumPattern(
                beatInBar = beatInBar,
                style = style,
                velocity = velocity,
                isVamping = isVamping
            )
            drumHits.forEach { hit ->
                scheduledTimeline.add(
                    ScheduledNoteEvent(
                        notes = listOf(hit.note),
                        startBeat = performanceBeat,
                        durationBeats = 0.25,
                        velocity = hit.velocity,
                        channel = 9
                    )
                )
            }
        }
        return Triple(triggeredNotes, durationBeats, drumHits)
    }

    private fun formatChordQuality(chord: String): String =
        VoicingEngine.describeChordQuality(chord)

    fun processAudioBuffer(audioChunk: FloatArray): TrackerStepResult? {
        for (sample in audioChunk) ringBuffer.add(sample)
        var latest = lastStatus
        while (ringBuffer.size >= windowSize) {
            val window = FloatArray(windowSize) { idx -> ringBuffer[idx] }
            latest = processSingleHop(window)
            repeat(hopSize) {
                if (ringBuffer.isNotEmpty()) ringBuffer.removeAt(0)
            }
        }
        return latest
    }
}


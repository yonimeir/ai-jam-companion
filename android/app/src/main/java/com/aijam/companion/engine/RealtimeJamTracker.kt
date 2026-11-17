package com.aijam.companion.engine

import com.aijam.companion.model.AccompanimentStyle
import com.aijam.companion.model.EngineMode
import com.aijam.companion.model.ScheduledNoteEvent
import com.aijam.companion.model.SongMap

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
 * Online DTW alignment, Harmonic State Machine, voice-leading, and MIDI timeline capture.
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
    private var lastBeatToken = -1
    private var prevRhNotes: List<Int>? = null

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
        lastBeatToken = -1
        prevRhNotes = null
        scheduledTimeline.clear()
        lastStatus = null
    }

    fun jumpToTime(targetTimeSec: Double) {
        scoreFollower.jumpToTime(targetTimeSec)
        lastBeatToken = -1
    }

    fun processSingleHop(window: FloatArray): TrackerStepResult {
        elapsedTime += hopDt
        val secPerBeat = 60.0 / bpm
        performanceBeat += hopDt / secPerBeat

        val pitchFrame = pitchDetector.processChunk(window)
        val (_, refTime, alignConf) = scoreFollower.step(
            liveMidi = pitchFrame.midiNote,
            liveVoiced = pitchFrame.isVoiced
        )
        val progressPct = scoreFollower.getProgressPercent()

        val hState = stateMachine.update(
            refTime = refTime,
            confidence = alignConf,
            isVoiced = pitchFrame.isVoiced,
            dt = hopDt
        )

        val velocity = velocityMapper.mapRmsToVelocity(pitchFrame.rmsDb)
        val isVamping = hState.engineMode == EngineMode.VAMPING
        val currentBeat = if (isVamping) hState.vampCycleBeat else hState.beat
        val beatToken = if (isVamping) {
            (elapsedTime / secPerBeat).toInt()
        } else {
            (refTime / secPerBeat).toInt()
        }

        val triggeredNotes = mutableListOf<Int>()
        var durationBeats = 0.0
        var drumHits = emptyList<DrumHit>()

        if (beatToken != lastBeatToken) {
            lastBeatToken = beatToken
            val voicing = VoicingEngine.generatePianoVoicing(
                chordName = hState.chord,
                style = style,
                beatInBar = currentBeat,
                prevNotes = prevRhNotes
            )
            if (voicing.harmonyNotes.isNotEmpty()) {
                prevRhNotes = voicing.harmonyNotes
            }
            if (voicing.bassNote != null) triggeredNotes.add(voicing.bassNote)
            triggeredNotes.addAll(voicing.harmonyNotes)
            durationBeats = voicing.durationBeats

            if (triggeredNotes.isNotEmpty() && durationBeats > 0.0) {
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

            if (includeDrums) {
                drumHits = VoicingEngine.generateDrumPattern(
                    beatInBar = currentBeat,
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
        }

        val res = TrackerStepResult(
            refTimeSec = refTime,
            progressPct = progressPct,
            section = hState.section,
            bar = hState.bar,
            beat = currentBeat,
            chord = hState.chord,
            quality = if (hState.chord.endsWith("m")) "Minor Triad" else "Major Triad",
            chordNotes = hState.notes,
            engineMode = hState.engineMode,
            confidence = hState.confidence,
            tempoRatio = scoreFollower.estimatedTempoRatio,
            singerF0Hz = pitchFrame.f0Hz,
            singerNoteName = VoicingEngine.midiToNoteName(pitchFrame.midiNote),
            singerMidi = pitchFrame.midiNote,
            isVoiced = pitchFrame.isVoiced,
            pianoVelocity = velocity,
            triggeredNotes = triggeredNotes,
            durationBeats = durationBeats,
            drumHits = drumHits
        )
        lastStatus = res
        return res
    }

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

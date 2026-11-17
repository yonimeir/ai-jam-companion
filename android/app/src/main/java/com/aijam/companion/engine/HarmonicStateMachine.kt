package com.aijam.companion.engine

import com.aijam.companion.model.ChordEvent
import com.aijam.companion.model.EngineMode
import com.aijam.companion.model.SongMap

data class HarmonicState(
    val chord: String,
    val root: String,
    val quality: String,
    val bar: Int,
    val beat: Int,
    val section: String,
    val notes: List<Int>,
    val engineMode: EngineMode,
    val confidence: Double,
    val vampCycleBeat: Int,
    val transitionOccurred: Boolean
)

/**
 * Harmonic State Machine (Stage C).
 * Manages chord state, confidence-gated transitions, and automatic Vamp looping
 * during breath pauses or vocal improvisation.
 */
class HarmonicStateMachine(
    songMap: SongMap,
    var confidenceThreshold: Double = 0.58,
    private val minConfirmFrames: Int = 2,
    private val pauseToVampSec: Double = 0.5
) {
    val chords: List<ChordEvent> = songMap.chords.ifEmpty {
        listOf(
            ChordEvent(
                chord = "C",
                root = "C",
                quality = "major",
                startTime = 0.0,
                endTime = 4.0,
                bar = 1,
                beat = 1,
                notes = listOf(60, 64, 67)
            )
        )
    }
    private val sections = songMap.sections
    private val bpm = songMap.metadata.bpm.coerceIn(40.0, 220.0)
    private val beatDuration = 60.0 / bpm

    var currentChordIdx: Int = 0
        private set
    private var candidateChordIdx: Int = 0
    private var confirmCount: Int = 0
    var engineMode: EngineMode = EngineMode.PLAYING
        private set

    private var unvoicedDuration: Double = 0.0
    var vampBeat: Int = 1
        private set
    private var vampTimer: Double = 0.0
    var forceVampHold: Boolean = false

    fun reset() {
        currentChordIdx = 0
        candidateChordIdx = 0
        confirmCount = 0
        engineMode = EngineMode.PLAYING
        unvoicedDuration = 0.0
        vampBeat = 1
        vampTimer = 0.0
        forceVampHold = false
    }

    private fun findChordIndexAtTime(refTime: Double): Int {
        for (i in chords.indices) {
            if (refTime >= chords[i].startTime && refTime < chords[i].endTime) {
                return i
            }
        }
        return if (refTime >= chords.last().endTime) chords.lastIndex else 0
    }

    private fun sectionForBar(bar: Int): String {
        for (s in sections) {
            if (bar in s.startBar..s.endBar) return s.label
        }
        return "Verse"
    }

    fun update(
        refTime: Double,
        confidence: Double,
        isVoiced: Boolean,
        dt: Double
    ): HarmonicState {
        var transitionOccurred = false
        if (!isVoiced || forceVampHold) {
            unvoicedDuration += dt
        } else {
            unvoicedDuration = 0.0
        }

        val targetIdx = findChordIndexAtTime(refTime)

        if (forceVampHold || unvoicedDuration >= pauseToVampSec) {
            engineMode = EngineMode.VAMPING
            vampTimer += dt
            if (vampTimer >= beatDuration) {
                vampTimer -= beatDuration
                vampBeat = (vampBeat % 4) + 1
            }
        } else {
            if (targetIdx != currentChordIdx) {
                if (targetIdx == candidateChordIdx) {
                    confirmCount++
                } else {
                    candidateChordIdx = targetIdx
                    confirmCount = 1
                }

                if (confidence >= confidenceThreshold && confirmCount >= minConfirmFrames) {
                    currentChordIdx = targetIdx
                    engineMode = EngineMode.PLAYING
                    confirmCount = 0
                    vampBeat = 1
                    transitionOccurred = true
                } else {
                    engineMode = EngineMode.CONFIDENCE_WAIT
                }
            } else {
                confirmCount = 0
                if (engineMode != EngineMode.VAMPING) {
                    engineMode = EngineMode.PLAYING
                }
            }
        }

        val curr = chords[currentChordIdx]
        return HarmonicState(
            chord = curr.chord,
            root = curr.root,
            quality = curr.quality,
            bar = curr.bar,
            beat = curr.beat,
            section = sectionForBar(curr.bar),
            notes = curr.notes,
            engineMode = engineMode,
            confidence = confidence,
            vampCycleBeat = vampBeat,
            transitionOccurred = transitionOccurred
        )
    }
}

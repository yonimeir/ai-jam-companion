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
 * - Differentiates between beat progression within the same chord vs. actual harmonic chord transitions.
 * - Only enters automatic VAMP when the reference song expects vocals and the singer pauses >= pauseToVampSec
 *   (never gets stuck in VAMP during instrumental intros/interludes!).
 * - Cleanly exits VAMP back to PLAYING as soon as the singer resumes singing.
 */
class HarmonicStateMachine(
    songMap: SongMap,
    var confidenceThreshold: Double = 0.52,
    private val minConfirmFrames: Int = 2,
    private val pauseToVampSec: Double = 1.15
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
        dt: Double,
        refExpectsVocal: Boolean = true
    ): HarmonicState {
        var transitionOccurred = false
        if (forceVampHold) {
            unvoicedDuration += dt
        } else if (!isVoiced && refExpectsVocal) {
            unvoicedDuration += dt
        } else {
            unvoicedDuration = 0.0
        }

        val targetIdx = findChordIndexAtTime(refTime)
        val currEvent = chords[currentChordIdx]
        val targetEvent = chords[targetIdx]

        if (forceVampHold || (refExpectsVocal && unvoicedDuration >= pauseToVampSec)) {
            engineMode = EngineMode.VAMPING
            vampTimer += dt
            if (vampTimer >= beatDuration) {
                vampTimer -= beatDuration
                vampBeat = (vampBeat % 4) + 1
            }
        } else {
            // If the chord symbol is identical (just advancing beats within the same bar/chord)
            // or we are in an instrumental passage, advance immediately without blocking!
            val isSameHarmony = targetEvent.chord == currEvent.chord && targetEvent.bar == currEvent.bar
            if (targetIdx != currentChordIdx) {
                if (isSameHarmony || !refExpectsVocal) {
                    currentChordIdx = targetIdx
                    confirmCount = 0
                    engineMode = EngineMode.PLAYING
                } else {
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
                }
            } else {
                confirmCount = 0
                // Cleanly return from VAMPING to PLAYING as soon as singing resumes!
                engineMode = EngineMode.PLAYING
            }
        }

        val active = chords[currentChordIdx]
        return HarmonicState(
            chord = active.chord,
            root = active.root,
            quality = active.quality,
            bar = active.bar,
            beat = active.beat,
            section = sectionForBar(active.bar),
            notes = active.notes,
            engineMode = engineMode,
            confidence = confidence,
            vampCycleBeat = vampBeat,
            transitionOccurred = transitionOccurred
        )
    }
}

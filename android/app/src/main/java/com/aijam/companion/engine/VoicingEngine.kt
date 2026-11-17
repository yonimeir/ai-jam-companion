package com.aijam.companion.engine

import com.aijam.companion.model.AccompanimentStyle
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.roundToInt

data class PianoVoicing(
    val bassNote: Int?,
    val harmonyNotes: List<Int>,
    val durationBeats: Double,
    val sustain: Boolean
)

data class DrumHit(
    val note: Int,
    val velocity: Int
)

/**
 * Smooth voice-leading piano voicings, minimalist drum grooves, and RMS->Velocity dynamics.
 */
object VoicingEngine {
    val NOTE_NAMES = listOf("C", "C#", "D", "D#", "E", "F", "F#", "G", "G#", "A", "A#", "B")
    private val NAME_TO_SEMITONE = NOTE_NAMES.withIndex().associate { it.value to it.index }
    private val ALIASES = mapOf("Db" to "C#", "Eb" to "D#", "Gb" to "F#", "Ab" to "G#", "Bb" to "A#")

    const val DRUM_KICK = 36
    const val DRUM_RIM = 37
    const val DRUM_SNARE_BRUSH = 38
    const val DRUM_CLOSED_HH = 42
    const val DRUM_RIDE = 51

    fun midiToNoteName(midiVal: Double): String {
        if (midiVal <= 0.0) return "---"
        val mRound = midiVal.roundToInt()
        val noteName = NOTE_NAMES[((mRound % 12) + 12) % 12]
        val octave = (mRound / 12) - 1
        val cents = ((midiVal - mRound) * 100.0).roundToInt()
        val centsStr = if (abs(cents) > 5) "%+d¢".format(cents) else ""
        return "$noteName$octave$centsStr"
    }

    fun chordToMidiNotes(chordName: String, octave: Int = 4): List<Int> {
        val isMaj7 = chordName.endsWith("maj7")
        val is7th = chordName.endsWith("7")
        val clean = chordName.replace("maj7", "").replace("7", "")
        val isMinor = clean.endsWith("m")
        val rawRoot = if (isMinor) clean.dropLast(1) else clean
        val rootStr = ALIASES[rawRoot] ?: rawRoot
        val rootSemi = NAME_TO_SEMITONE[rootStr] ?: 0
        val rootMidi = 12 * (octave + 1) + rootSemi

        return if (isMinor) {
            buildList {
                add(rootMidi)
                add(rootMidi + 3)
                add(rootMidi + 7)
                if (is7th) add(rootMidi + 10)
            }
        } else {
            buildList {
                add(rootMidi)
                add(rootMidi + 4)
                add(rootMidi + 7)
                if (isMaj7) add(rootMidi + 11) else if (is7th) add(rootMidi + 10)
            }
        }
    }

    fun getChordIntervals(chordName: String): Pair<Int, List<Int>> {
        val isMaj7 = "maj7" in chordName
        val is7th = "7" in chordName
        val clean = chordName.replace("maj7", "").replace("7", "")
        val isMinor = clean.endsWith("m")
        val rawRoot = if (isMinor) clean.dropLast(1) else clean
        val rootStr = ALIASES[rawRoot] ?: rawRoot
        val rootSemi = NAME_TO_SEMITONE[rootStr] ?: 0

        val intervals = if (isMinor) {
            buildList {
                add(0); add(3); add(7)
                if (is7th) add(10)
            }
        } else {
            buildList {
                add(0); add(4); add(7)
                if (isMaj7) add(11) else if (is7th) add(10)
            }
        }
        return rootSemi to intervals
    }

    private fun applyVoiceLeading(
        pitchClasses: List<Int>,
        prevNotes: List<Int>?,
        centerMidi: Double = 65.0
    ): List<Int> {
        val targetCenter = if (!prevNotes.isNullOrEmpty()) prevNotes.average() else centerMidi
        val candidates = mutableListOf<List<Int>>()

        for (baseOct in listOf(48, 60)) {
            val voiced = pitchClasses.map { pc ->
                var n = baseOct + pc
                while (n < 57) n += 12
                while (n > 78) n -= 12
                n
            }.sorted()
            candidates.add(voiced)

            if (voiced.size >= 3) {
                val inv1 = (listOf(voiced[1], voiced[2], voiced[0] + 12) + voiced.drop(3)).sorted()
                val inv2 = (listOf(voiced[2], voiced[0] + 12, voiced[1] + 12) + voiced.drop(3)).sorted()
                for (inv in listOf(inv1, inv2)) {
                    if (inv.last() <= 81 && inv.first() >= 55) candidates.add(inv)
                }
            }
        }

        var best = candidates.first()
        var bestCost = Double.MAX_VALUE
        val prevSorted = prevNotes?.sorted()
        for (cand in candidates) {
            var cost = abs(cand.average() - targetCenter)
            if (prevSorted != null && prevSorted.size == cand.size) {
                val candSorted = cand.sorted()
                var dist = 0
                for (i in candSorted.indices) dist += abs(candSorted[i] - prevSorted[i])
                cost += 0.5 * dist
            }
            if (cost < bestCost) {
                bestCost = cost
                best = cand
            }
        }
        return best
    }

    fun generatePianoVoicing(
        chordName: String,
        style: AccompanimentStyle,
        beatInBar: Int,
        prevNotes: List<Int>?
    ): PianoVoicing {
        val (rootSemi, intervals) = getChordIntervals(chordName)
        var bassRoot = 36 + rootSemi
        if (bassRoot < 36) bassRoot += 12
        val bassFifth = bassRoot + 7

        val pitchClasses = intervals.map { (rootSemi + it) % 12 }
        val rhVoiced = applyVoiceLeading(pitchClasses, prevNotes)

        return when (style) {
            AccompanimentStyle.BALLAD_ARPEGGIO -> when (beatInBar) {
                1 -> PianoVoicing(bassRoot, rhVoiced, 1.6, true)
                2 -> PianoVoicing(bassFifth, listOf(rhVoiced.firstOrNull() ?: (bassFifth + 12)), 1.1, true)
                3 -> {
                    val mid = if (rhVoiced.size > 1) rhVoiced[1] else rhVoiced.first()
                    PianoVoicing(null, listOf(mid, rhVoiced.last()), 1.1, true)
                }
                else -> {
                    val top = if (rhVoiced.last() < 72) rhVoiced.last() + 12 else rhVoiced.last()
                    PianoVoicing(null, listOf(top), 0.95, true)
                }
            }

            AccompanimentStyle.RHYTHMIC_COMP -> if (beatInBar == 1 || beatInBar == 3) {
                PianoVoicing(bassRoot, rhVoiced, 0.8, false)
            } else {
                PianoVoicing(null, rhVoiced, 0.5, false)
            }

            AccompanimentStyle.SUSTAINED_CHORDS -> if (beatInBar == 1) {
                PianoVoicing(bassRoot, rhVoiced, 3.8, true)
            } else {
                PianoVoicing(null, emptyList(), 0.0, true)
            }
        }
    }

    fun generateDrumPattern(
        beatInBar: Int,
        style: AccompanimentStyle,
        velocity: Int,
        isVamping: Boolean
    ): List<DrumHit> {
        val velScale = (velocity / 110.0).coerceIn(0.35, 1.0)
        return when (style) {
            AccompanimentStyle.SUSTAINED_CHORDS -> {
                if (beatInBar == 1) listOf(DrumHit(DRUM_RIDE, (36 * velScale).toInt())) else emptyList()
            }
            AccompanimentStyle.BALLAD_ARPEGGIO -> when (beatInBar) {
                1 -> listOf(DrumHit(DRUM_KICK, (68 * velScale).toInt()), DrumHit(DRUM_CLOSED_HH, (38 * velScale).toInt()))
                2 -> listOf(DrumHit(DRUM_RIM, (48 * velScale).toInt()), DrumHit(DRUM_CLOSED_HH, (34 * velScale).toInt()))
                3 -> buildList {
                    if (!isVamping) add(DrumHit(DRUM_KICK, (56 * velScale).toInt()))
                    add(DrumHit(DRUM_CLOSED_HH, (38 * velScale).toInt()))
                }
                else -> listOf(DrumHit(DRUM_RIM, (45 * velScale).toInt()), DrumHit(DRUM_CLOSED_HH, (34 * velScale).toInt()))
            }
            AccompanimentStyle.RHYTHMIC_COMP -> when (beatInBar) {
                1 -> listOf(DrumHit(DRUM_KICK, (82 * velScale).toInt()), DrumHit(DRUM_CLOSED_HH, (48 * velScale).toInt()))
                2 -> listOf(DrumHit(DRUM_SNARE_BRUSH, (68 * velScale).toInt()), DrumHit(DRUM_CLOSED_HH, (44 * velScale).toInt()))
                3 -> listOf(DrumHit(DRUM_KICK, (74 * velScale).toInt()), DrumHit(DRUM_CLOSED_HH, (48 * velScale).toInt()))
                else -> listOf(DrumHit(DRUM_SNARE_BRUSH, (65 * velScale).toInt()), DrumHit(DRUM_CLOSED_HH, (44 * velScale).toInt()))
            }
        }
    }
}

class VelocityMapper(
    private val minRmsDb: Double = -45.0,
    private val maxRmsDb: Double = -12.0,
    private val minVel: Int = 38,
    private val maxVel: Int = 112,
    private val attackCoeff: Double = 0.6,
    private val releaseCoeff: Double = 0.15
) {
    private var smoothedVel: Double = minVel.toDouble()

    fun reset() {
        smoothedVel = minVel.toDouble()
    }

    fun mapRmsToVelocity(rmsDb: Double): Int {
        val clamped = rmsDb.coerceIn(minRmsDb, maxRmsDb)
        val norm = (clamped - minRmsDb) / (maxRmsDb - minRmsDb + 1e-6)
        val curved = norm.pow(1.3)
        val target = minVel + curved * (maxVel - minVel)
        smoothedVel += if (target > smoothedVel) {
            attackCoeff * (target - smoothedVel)
        } else {
            releaseCoeff * (target - smoothedVel)
        }
        return smoothedVel.roundToInt().coerceIn(1, 127)
    }
}

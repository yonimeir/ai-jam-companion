package com.aijam.companion.audio

import com.aijam.companion.engine.Fft
import com.aijam.companion.engine.RealtimePitchDetector
import com.aijam.companion.engine.VoicingEngine
import com.aijam.companion.model.BarState
import com.aijam.companion.model.ChordEvent
import com.aijam.companion.model.SongMap
import com.aijam.companion.model.SongMetadata
import com.aijam.companion.model.SongSection
import com.aijam.companion.model.VocalAnchor
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.log2
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Pure Kotlin Stage-A On-Device Music Analyzer:
 * 1. STFT Spectral Flux Onset Strength + Tempogram BPM & Beat Tracking
 * 2. Bass-Weighted + Full-Range Harmonic Chromagram
 * 3. Krumhansl-Schmuckler Key Detection + Key-Aware Viterbi HMM Chord Decoding
 * 4. Bar Quantization & Novelty-Based Song Section Segmentation
 * 5. Singing Pitch Contour Extraction (VocalAnchor)
 */
object OfflineSongAnalyzer {

    private const val WINDOW_SIZE = 2048
    private const val HOP_SIZE = 512

    private val MAJOR_PROFILE = doubleArrayOf(
        6.35, 2.23, 3.48, 2.33, 4.38, 4.09, 2.52, 5.19, 2.39, 3.66, 2.29, 2.88
    )
    private val MINOR_PROFILE = doubleArrayOf(
        6.33, 2.68, 3.52, 5.38, 2.60, 3.53, 2.54, 4.75, 3.98, 2.69, 3.34, 3.17
    )

    private data class ChordTemplate(
        val name: String,
        val root: String,
        val rootSemitone: Int,
        val quality: String,
        val weights: DoubleArray
    )

    private val CHORD_TEMPLATES: List<ChordTemplate> = buildChordTemplates()

    private fun buildChordTemplates(): List<ChordTemplate> {
        val notes = VoicingEngine.NOTE_NAMES
        val out = ArrayList<ChordTemplate>(24)
        for (i in 0 until 12) {
            val maj = DoubleArray(12)
            maj[i] = 1.0
            maj[(i + 4) % 12] = 0.80
            maj[(i + 7) % 12] = 0.65
            normalizeL2(maj)
            out.add(ChordTemplate(notes[i], notes[i], i, "major", maj))

            val min = DoubleArray(12)
            min[i] = 1.0
            min[(i + 3) % 12] = 0.80
            min[(i + 7) % 12] = 0.65
            normalizeL2(min)
            out.add(ChordTemplate("${notes[i]}m", notes[i], i, "minor", min))
        }
        return out
    }

    fun analyze(
        samples: FloatArray,
        sampleRate: Int = 22050,
        id: String = "analyzed_song",
        title: String = "Analyzed Song",
        artist: String = "AI Jam",
        onProgress: (Float, String) -> Unit = { _, _ -> }
    ): SongMap {
        require(samples.size >= WINDOW_SIZE * 2) { "קובץ האודיו קצר מדי לניתוח" }

        val duration = samples.size.toDouble() / sampleRate
        val numFrames = max(1, (samples.size - WINDOW_SIZE) / HOP_SIZE + 1)
        val hopSec = HOP_SIZE.toDouble() / sampleRate
        val hann = Fft.hannWindow(WINDOW_SIZE)
        val halfBins = WINDOW_SIZE / 2

        // Precompute FFT bin -> pitch-class mapping for full spectrum (80-2200 Hz) & bass (45-250 Hz)
        val binPitchClass = IntArray(halfBins) { -1 }
        val binWeight = DoubleArray(halfBins)
        val binIsBass = BooleanArray(halfBins)
        val freqPerBin = sampleRate.toDouble() / WINDOW_SIZE
        for (b in 1 until halfBins) {
            val freq = b * freqPerBin
            if (freq in 45.0..2400.0) {
                val midi = 69.0 + 12.0 * log2(freq / 440.0)
                val pc = ((midi.roundToInt() % 12) + 12) % 12
                binPitchClass[b] = pc
                // Smooth perceptual weight emphasizing musical octave range C2..C7
                binWeight[b] = if (freq < 260.0) 1.15 else 1.0
                binIsBass[b] = freq <= 250.0
            }
        }

        onProgress(0.10f, "מחשב ספקטרום ותדרים...")
        val frameChroma = Array(numFrames) { DoubleArray(12) }
        val bassChroma = Array(numFrames) { DoubleArray(12) }
        val onsetEnv = DoubleArray(numFrames)

        val real = DoubleArray(WINDOW_SIZE)
        val imag = DoubleArray(WINDOW_SIZE)
        val prevMag = DoubleArray(halfBins)

        for (f in 0 until numFrames) {
            val start = f * HOP_SIZE
            for (i in 0 until WINDOW_SIZE) {
                real[i] = samples[start + i] * hann[i]
                imag[i] = 0.0
            }
            Fft.transform(real, imag, inverse = false)

            var flux = 0.0
            val fc = frameChroma[f]
            val bc = bassChroma[f]
            for (b in 1 until halfBins) {
                val mag = sqrt(real[b] * real[b] + imag[b] * imag[b])
                val logMag = ln(1.0 + 8.0 * mag)
                val diff = logMag - prevMag[b]
                if (diff > 0.0) flux += diff
                prevMag[b] = logMag

                val pc = binPitchClass[b]
                if (pc >= 0) {
                    val energy = mag * mag * binWeight[b]
                    fc[pc] += energy
                    if (binIsBass[b]) bc[pc] += energy
                }
            }
            onsetEnv[f] = flux
            normalizeL2(fc)
            normalizeL2(bc)

            if (f % 150 == 0) {
                onProgress(0.10f + 0.30f * (f.toFloat() / numFrames), "מנתח כרומה וקצב...")
            }
        }

        // 1. Estimate BPM & track beat timestamps
        onProgress(0.45f, "מזהה טמפו (BPM) ופעימות...")
        val bpm = estimateTempo(onsetEnv, hopSec)
        val beatTimes = trackBeats(onsetEnv, hopSec, bpm, duration)

        // 2. Global key estimation & Viterbi HMM chord decoding per beat
        onProgress(0.58f, "מפענח סולם והרמוניה (Viterbi HMM)...")
        val meanChroma = DoubleArray(12)
        for (f in 0 until numFrames) {
            for (pc in 0 until 12) {
                meanChroma[pc] += 0.7 * frameChroma[f][pc] + 0.3 * bassChroma[f][pc]
            }
        }
        val (keyTonic, keyMode) = estimateKey(meanChroma)
        val diatonicPrior = buildDiatonicPrior(keyTonic, keyMode)

        val beatChroma = Array(beatTimes.size) { bIdx ->
            val tStart = beatTimes[bIdx]
            val tEnd = if (bIdx + 1 < beatTimes.size) beatTimes[bIdx + 1] else duration
            val fStart = (tStart / hopSec).toInt().coerceIn(0, numFrames - 1)
            val fEnd = max(fStart + 1, (tEnd / hopSec).toInt().coerceIn(1, numFrames))
            val comb = DoubleArray(12)
            for (f in fStart until fEnd) {
                for (pc in 0 until 12) {
                    comb[pc] += 0.68 * frameChroma[f][pc] + 0.32 * bassChroma[f][pc]
                }
            }
            normalizeL2(comb)
            comb
        }

        val emission = Array(beatChroma.size) { b ->
            DoubleArray(CHORD_TEMPLATES.size) { c ->
                var dot = 0.0
                val tpl = CHORD_TEMPLATES[c].weights
                val bc = beatChroma[b]
                for (pc in 0 until 12) dot += bc[pc] * tpl[pc]
                dot
            }
        }
        val decodedChordIndices = viterbiDecodeChords(emission, diatonicPrior, stayBonus = 0.24)

        val chordEvents = buildList {
            for (i in beatTimes.indices) {
                val tpl = CHORD_TEMPLATES[decodedChordIndices[i]]
                val tStart = beatTimes[i]
                val tEnd = if (i + 1 < beatTimes.size) beatTimes[i + 1] else duration
                val bar = (i / 4) + 1
                val beatInBar = (i % 4) + 1
                add(
                    ChordEvent(
                        chord = tpl.name,
                        root = tpl.root,
                        quality = tpl.quality,
                        startTime = round3(tStart),
                        endTime = round3(max(tStart + 0.1, tEnd)),
                        bar = bar,
                        beat = beatInBar,
                        notes = VoicingEngine.chordToMidiNotes(tpl.name, octave = 4)
                    )
                )
            }
        }

        // 3. Extract vocal melody contour (VocalAnchor)
        onProgress(0.75f, "מחלץ קו מלודיה ושירה (FFT-YIN)...")
        val vocalAnchor = extractVocalAnchor(samples, sampleRate, numFrames, hopSec)

        // 4. Quantize into BarStates & Sections
        onProgress(0.92f, "בונה תיבות ומבנה שיר...")
        val totalBars = max(1, (beatTimes.size + 3) / 4)
        val sections = segmentSongSections(beatChroma, totalBars, beatTimes, duration)

        val states = buildList {
            for (barIdx in 1..totalBars) {
                val bEvents = chordEvents.filter { it.bar == barIdx }
                if (bEvents.isEmpty()) continue
                val dominant = bEvents
                    .groupingBy { it.chord }
                    .eachCount()
                    .maxByOrNull { it.value }
                    ?.key ?: bEvents.first().chord

                val barStart = bEvents.first().startTime
                val barEnd = bEvents.last().endTime
                val targetMidi = estimateTargetPitchForBar(vocalAnchor, barStart, barEnd)
                val secName = sections.firstOrNull { barIdx in it.startBar..it.endBar }?.name ?: "verse_1"

                add(
                    BarState(
                        bar = barIdx,
                        section = secName,
                        chord = dominant,
                        subChords = bEvents.map { it.chord },
                        startTime = round3(barStart),
                        endTime = round3(barEnd),
                        notes = VoicingEngine.chordToMidiNotes(dominant, octave = 4),
                        targetPitchMidi = round2(targetMidi)
                    )
                )
            }
        }

        val metadata = SongMetadata(
            id = id,
            title = title,
            artist = artist,
            duration = round3(duration),
            bpm = round1(bpm),
            sampleRate = sampleRate,
            totalBars = states.size.coerceAtLeast(1),
            totalBeats = beatTimes.size
        )

        onProgress(1.0f, "הניתוח הושלם!")
        return SongMap(
            metadata = metadata,
            sections = sections,
            chords = chordEvents,
            states = states,
            vocalAnchor = vocalAnchor,
            lyrics = emptyList()
        )
    }

    private fun estimateTempo(onsetEnv: DoubleArray, hopSec: Double): Double {
        if (onsetEnv.size < 32) return 100.0
        val mean = onsetEnv.average()
        val centered = DoubleArray(onsetEnv.size) { max(0.0, onsetEnv[it] - mean) }

        val minLag = max(8, (60.0 / (175.0 * hopSec)).roundToInt())
        val maxLag = min(onsetEnv.size / 2, (60.0 / (58.0 * hopSec)).roundToInt())
        if (maxLag <= minLag) return 100.0

        var bestBpm = 100.0
        var bestScore = -1.0
        for (lag in minLag..maxLag) {
            var ac = 0.0
            val count = centered.size - lag
            for (i in 0 until count) ac += centered[i] * centered[i + lag]
            ac /= max(1, count)
            val candidateBpm = 60.0 / (lag * hopSec)
            // Soft Gaussian preference around typical song tempo (104 BPM) to curb half/double ambiguity
            val dist = (log2(candidateBpm / 104.0)) / 0.55
            val prior = kotlin.math.exp(-0.5 * dist * dist)
            val score = ac * (0.65 + 0.35 * prior)
            if (score > bestScore) {
                bestScore = score
                bestBpm = candidateBpm
            }
        }
        return bestBpm.coerceIn(58.0, 175.0)
    }

    private fun trackBeats(
        onsetEnv: DoubleArray,
        hopSec: Double,
        bpm: Double,
        duration: Double
    ): List<Double> {
        val beatPeriodSec = 60.0 / bpm
        val periodFrames = max(4, (beatPeriodSec / hopSec).roundToInt())

        // Find optimal phase within first beat period
        var bestPhase = 0
        var bestPhaseEnergy = -1.0
        for (phase in 0 until periodFrames) {
            var energy = 0.0
            var idx = phase
            while (idx < onsetEnv.size) {
                energy += onsetEnv[idx]
                idx += periodFrames
            }
            if (energy > bestPhaseEnergy) {
                bestPhaseEnergy = energy
                bestPhase = phase
            }
        }

        val beats = mutableListOf<Double>()
        var t = bestPhase * hopSec
        // Ensure beat grid covers start cleanly
        if (t > 0.28 * beatPeriodSec) {
            beats.add(0.0)
        } else {
            t = 0.0
        }
        while (t < duration - 0.12 * beatPeriodSec) {
            beats.add(t)
            t += beatPeriodSec
        }
        if (beats.isEmpty()) beats.add(0.0)
        return beats.distinct()
    }

    fun estimateKey(meanChroma: DoubleArray): Pair<Int, String> {
        val cAvg = meanChroma.average()
        val cNorm = DoubleArray(12) { meanChroma[it] - cAvg }
        val majAvg = MAJOR_PROFILE.average()
        val minAvg = MINOR_PROFILE.average()

        var bestTonic = 0
        var bestMode = "major"
        var bestCorr = Double.NEGATIVE_INFINITY

        for (tonic in 0 until 12) {
            var majCorr = 0.0
            var minCorr = 0.0
            for (pc in 0 until 12) {
                val pIdx = (pc - tonic + 12) % 12
                majCorr += cNorm[pc] * (MAJOR_PROFILE[pIdx] - majAvg)
                minCorr += cNorm[pc] * (MINOR_PROFILE[pIdx] - minAvg)
            }
            if (majCorr > bestCorr) {
                bestCorr = majCorr
                bestTonic = tonic
                bestMode = "major"
            }
            if (minCorr > bestCorr) {
                bestCorr = minCorr
                bestTonic = tonic
                bestMode = "minor"
            }
        }
        return bestTonic to bestMode
    }

    private fun buildDiatonicPrior(tonic: Int, mode: String): DoubleArray {
        val majorOffsets = mapOf(0 to "major", 2 to "minor", 4 to "minor", 5 to "major", 7 to "major", 9 to "minor")
        val minorOffsets = mapOf(0 to "minor", 3 to "major", 5 to "minor", 7 to "minor", 8 to "major", 10 to "major")
        val table = if (mode == "major") majorOffsets else minorOffsets

        return DoubleArray(CHORD_TEMPLATES.size) { idx ->
            val tpl = CHORD_TEMPLATES[idx]
            val rel = (tpl.rootSemitone - tonic + 12) % 12
            val expected = table[rel]
            when {
                expected == tpl.quality -> 0.08
                (rel == 5 || rel == 7) && tpl.quality == "major" -> 0.03
                else -> -0.04
            }
        }
    }

    private fun viterbiDecodeChords(
        emission: Array<DoubleArray>,
        prior: DoubleArray,
        stayBonus: Double
    ): IntArray {
        val nSteps = emission.size
        val nStates = CHORD_TEMPLATES.size
        if (nSteps == 0) return IntArray(0)

        var dp = DoubleArray(nStates) { s -> emission[0][s] + prior[s] }
        val backptr = Array(nSteps) { IntArray(nStates) }

        for (t in 1 until nSteps) {
            val nextDp = DoubleArray(nStates)
            for (curr in 0 until nStates) {
                var bestPrev = 0
                var bestVal = Double.NEGATIVE_INFINITY
                for (prev in 0 until nStates) {
                    val trans = if (prev == curr) stayBonus else 0.0
                    val cand = dp[prev] + trans
                    if (cand > bestVal) {
                        bestVal = cand
                        bestPrev = prev
                    }
                }
                nextDp[curr] = bestVal + emission[t][curr] + prior[curr]
                backptr[t][curr] = bestPrev
            }
            dp = nextDp
        }

        val path = IntArray(nSteps)
        var lastBest = 0
        for (s in 1 until nStates) {
            if (dp[s] > dp[lastBest]) lastBest = s
        }
        path[nSteps - 1] = lastBest
        for (t in nSteps - 1 downTo 1) {
            path[t - 1] = backptr[t][path[t]]
        }
        return path
    }

    private fun extractVocalAnchor(
        samples: FloatArray,
        sampleRate: Int,
        numFrames: Int,
        hopSec: Double
    ): VocalAnchor {
        // Light first-order pre-emphasis / rumble high-pass copy so bass guitar doesn't mask vocal pitch
        val vocalBand = FloatArray(samples.size)
        var prev = 0f
        for (i in samples.indices) {
            val x = samples[i]
            vocalBand[i] = x - 0.88f * prev
            prev = x
        }

        val detector = RealtimePitchDetector(
            sampleRate = sampleRate,
            fmin = 95.0,
            fmax = 900.0,
            yinThreshold = 0.22,
            minRmsDb = -46.0
        )

        val times = DoubleArray(numFrames)
        val f0Hz = DoubleArray(numFrames)
        val midiNotes = DoubleArray(numFrames)
        val voicedProb = DoubleArray(numFrames)
        val rmsDb = DoubleArray(numFrames)
        val window = FloatArray(WINDOW_SIZE)

        for (f in 0 until numFrames) {
            times[f] = round3(f * hopSec)
            val start = f * HOP_SIZE
            System.arraycopy(vocalBand, start, window, 0, WINDOW_SIZE)
            val pf = detector.processChunk(window)
            rmsDb[f] = round2(pf.rmsDb)
            if (pf.isVoiced) {
                f0Hz[f] = round2(pf.f0Hz)
                midiNotes[f] = round2(pf.midiNote)
                voicedProb[f] = round2(pf.confidence)
            } else {
                f0Hz[f] = 0.0
                midiNotes[f] = 0.0
                voicedProb[f] = 0.0
            }
        }

        // Suppress isolated 1-frame glitches
        for (f in 1 until numFrames - 1) {
            if (voicedProb[f] > 0.0 && voicedProb[f - 1] == 0.0 && voicedProb[f + 1] == 0.0) {
                f0Hz[f] = 0.0
                midiNotes[f] = 0.0
                voicedProb[f] = 0.0
            }
        }

        return VocalAnchor(times, f0Hz, midiNotes, voicedProb, rmsDb)
    }

    private fun segmentSongSections(
        beatChroma: Array<DoubleArray>,
        totalBars: Int,
        beatTimes: List<Double>,
        duration: Double
    ): List<SongSection> {
        if (totalBars <= 4) {
            return listOf(
                SongSection("verse_1", "Verse 1", 0.0, round3(duration), 1, totalBars)
            )
        }

        val barChroma = Array(totalBars) { b ->
            val vec = DoubleArray(12)
            val startBeat = b * 4
            val endBeat = min(beatChroma.size, startBeat + 4)
            for (bi in startBeat until endBeat) {
                for (pc in 0 until 12) vec[pc] += beatChroma[bi][pc]
            }
            normalizeL2(vec)
            vec
        }

        // Find significant harmonic contrast boundaries >= 4 bars apart
        val boundaries = mutableListOf(1)
        var lastBoundary = 1
        for (b in 4 until totalBars - 2) {
            if (b - lastBoundary < 4) continue
            var dist = 0.0
            for (pc in 0 until 12) {
                val d = barChroma[b - 1][pc] - barChroma[b][pc]
                dist += d * d
            }
            if (dist > 0.28 || b - lastBoundary >= 8) {
                boundaries.add(b)
                lastBoundary = b
            }
        }

        val labels = listOf("Intro", "Verse 1", "Chorus 1", "Verse 2", "Chorus 2", "Bridge", "Outro")
        val names = listOf("intro", "verse_1", "chorus_1", "verse_2", "chorus_2", "bridge", "outro")

        return boundaries.mapIndexed { idx, startBar ->
            val endBar = if (idx + 1 < boundaries.size) boundaries[idx + 1] - 1 else totalBars
            val startBeatIdx = ((startBar - 1) * 4).coerceIn(0, beatTimes.lastIndex)
            val endBeatIdx = (endBar * 4).coerceAtMost(beatTimes.size)
            val sTime = beatTimes[startBeatIdx]
            val eTime = if (endBeatIdx < beatTimes.size) beatTimes[endBeatIdx] else duration
            val labelIdx = if (boundaries.size == 1) 1 else min(idx, labels.lastIndex)
            SongSection(
                name = names[labelIdx],
                label = labels[labelIdx],
                startTime = round3(sTime),
                endTime = round3(eTime),
                startBar = startBar,
                endBar = endBar
            )
        }
    }

    private fun estimateTargetPitchForBar(
        anchor: VocalAnchor,
        startTime: Double,
        endTime: Double
    ): Double {
        val voicedPitches = mutableListOf<Double>()
        for (i in anchor.times.indices) {
            val t = anchor.times[i]
            if (t in startTime..endTime && anchor.voicedProb[i] > 0.35 && anchor.midiNotes[i] > 0.0) {
                voicedPitches.add(anchor.midiNotes[i])
            }
        }
        if (voicedPitches.isEmpty()) return 0.0
        voicedPitches.sort()
        return voicedPitches[voicedPitches.size / 2]
    }

    private fun normalizeL2(v: DoubleArray) {
        var sq = 0.0
        for (x in v) sq += x * x
        val norm = sqrt(sq)
        if (norm > 1e-9) {
            val inv = 1.0 / norm
            for (i in v.indices) v[i] *= inv
        }
    }

    private fun round1(v: Double): Double = (v * 10.0).roundToInt() / 10.0
    private fun round2(v: Double): Double = (v * 100.0).roundToInt() / 100.0
    private fun round3(v: Double): Double = (v * 1000.0).roundToInt() / 1000.0
}

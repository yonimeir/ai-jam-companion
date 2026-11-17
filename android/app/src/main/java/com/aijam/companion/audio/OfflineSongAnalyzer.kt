package com.aijam.companion.audio

import com.aijam.companion.engine.Fft
import com.aijam.companion.engine.VoicingEngine
import com.aijam.companion.model.BarState
import com.aijam.companion.model.ChordEvent
import com.aijam.companion.model.SongMap
import com.aijam.companion.model.SongMetadata
import com.aijam.companion.model.SongSection
import com.aijam.companion.model.VocalAnchor
import com.aijam.companion.util.DiagnosticLogger
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.log2
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Stage-A On-Device Music Analyzer:
 * 1. Multi-band Spectral Flux Onset Strength + Ellis (2007) Dynamic Programming Beat Tracking
 * 2. Downbeat (Beat 1) Bar-Phase Alignment using bass onset & harmonic change novelty
 * 3. Harmonic + Bass Chromagram with Bar/Half-Bar Stabilized Viterbi HMM Chord Decoding
 * 4. Foreground-Isolated Harmonic Salience + Viterbi Smooth Vocal Contour Extraction
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
            maj[(i + 4) % 12] = 0.82
            maj[(i + 7) % 12] = 0.68
            normalizeL2(maj)
            out.add(ChordTemplate(notes[i], notes[i], i, "major", maj))

            val min = DoubleArray(12)
            min[i] = 1.0
            min[(i + 3) % 12] = 0.82
            min[(i + 7) % 12] = 0.68
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
        audioPath: String? = null,
        onProgress: (Float, String) -> Unit = { _, _ -> }
    ): SongMap {
        val t0 = System.currentTimeMillis()
        require(samples.size >= WINDOW_SIZE * 2) { "קובץ האודיו קצר מדי לניתוח" }

        val duration = samples.size.toDouble() / sampleRate
        val numFrames = max(1, (samples.size - WINDOW_SIZE) / HOP_SIZE + 1)
        val hopSec = HOP_SIZE.toDouble() / sampleRate
        val hann = Fft.hannWindow(WINDOW_SIZE)
        val halfBins = WINDOW_SIZE / 2
        val freqPerBin = sampleRate.toDouble() / WINDOW_SIZE

        // Precompute FFT bin mapping for chroma (C2..C7) and bass (45..210 Hz)
        val binPitchClass = IntArray(halfBins) { -1 }
        val binWeight = DoubleArray(halfBins)
        val binIsBass = BooleanArray(halfBins)
        val maxVocalBin = min(halfBins - 1, (3200.0 / freqPerBin).toInt())

        for (b in 1 until halfBins) {
            val freq = b * freqPerBin
            if (freq in 50.0..2200.0) {
                val midi = 69.0 + 12.0 * log2(freq / 440.0)
                val rounded = midi.roundToInt()
                // Weight bins closer to exact semitone centers higher to reduce spectral leakage
                val centsDist = abs(midi - rounded)
                if (centsDist <= 0.42) {
                    val pc = ((rounded % 12) + 12) % 12
                    binPitchClass[b] = pc
                    val centerSharpness = 1.0 - centsDist
                    binWeight[b] = centerSharpness * (if (freq < 260.0) 1.25 else 1.0)
                    binIsBass[b] = freq in 50.0..210.0
                }
            }
        }

        onProgress(0.10f, "מחשב ספקטרום, כרומה והתקפי קצב...")
        val frameChroma = Array(numFrames) { DoubleArray(12) }
        val bassChroma = Array(numFrames) { DoubleArray(12) }
        val onsetEnv = DoubleArray(numFrames)
        val bassEnergy = DoubleArray(numFrames)
        val frameRmsDb = DoubleArray(numFrames)
        // Compact magnitude spectrogram up to 3.2 kHz for foreground vocal salience extraction
        val specMag = Array(numFrames) { FloatArray(maxVocalBin + 1) }

        val real = DoubleArray(WINDOW_SIZE)
        val imag = DoubleArray(WINDOW_SIZE)
        val prevMag = DoubleArray(halfBins)

        for (f in 0 until numFrames) {
            val start = f * HOP_SIZE
            var sqSum = 0.0
            for (i in 0 until WINDOW_SIZE) {
                val s = samples[start + i].toDouble()
                sqSum += s * s
                real[i] = s * hann[i]
                imag[i] = 0.0
            }
            val rms = sqrt(sqSum / WINDOW_SIZE)
            frameRmsDb[f] = 20.0 * log10(max(rms, 1e-5))

            Fft.transform(real, imag, inverse = false)

            var flux = 0.0
            var bassE = 0.0
            val fc = frameChroma[f]
            val bc = bassChroma[f]
            val sm = specMag[f]

            for (b in 1 until halfBins) {
                val mag = sqrt(real[b] * real[b] + imag[b] * imag[b])
                if (b <= maxVocalBin) {
                    sm[b] = mag.toFloat()
                }
                val logMag = ln(1.0 + 10.0 * mag)
                val diff = logMag - prevMag[b]
                if (diff > 0.0) flux += diff
                prevMag[b] = logMag

                val pc = binPitchClass[b]
                if (pc >= 0) {
                    val energy = mag * bindHarmonicSuppression(sm, b, maxVocalBin) * binWeight[b]
                    fc[pc] += energy
                    if (binIsBass[b]) {
                        bc[pc] += energy * 1.4
                        bassE += energy
                    }
                }
            }
            onsetEnv[f] = flux
            bassEnergy[f] = bassE
            normalizeL2(fc)
            normalizeL2(bc)

            if (f % 160 == 0) {
                onProgress(0.10f + 0.30f * (f.toFloat() / numFrames), "מנתח כרומה וקצב...")
            }
        }

        // 1. Estimate BPM & run Dynamic Programming beat tracking + downbeat bar alignment
        onProgress(0.44f, "מזהה טמפו (BPM) ופעימות מדויקות...")
        val bpm = estimateTempo(onsetEnv, hopSec)
        val rawBeatFrames = trackBeatsDynamic(onsetEnv, hopSec, bpm, numFrames)
        val beatTimes = alignBeatGridToDownbeats(rawBeatFrames, frameChroma, bassEnergy, hopSec, bpm, duration)

        // 2. Global key estimation & Bar-Stabilized Viterbi HMM chord decoding
        onProgress(0.58f, "מפענח סולם ואקורדים מיוצבים לתיבות...")
        val meanChroma = DoubleArray(12)
        for (f in 0 until numFrames) {
            for (pc in 0 until 12) {
                meanChroma[pc] += 0.65 * frameChroma[f][pc] + 0.35 * bassChroma[f][pc]
            }
        }
        val (keyTonic, keyMode) = estimateKey(meanChroma)
        val keyName = "${VoicingEngine.NOTE_NAMES[keyTonic]} $keyMode"
        val diatonicPrior = buildDiatonicPrior(keyTonic, keyMode)

        val beatChroma = Array(beatTimes.size) { bIdx ->
            val tStart = beatTimes[bIdx]
            val tEnd = if (bIdx + 1 < beatTimes.size) beatTimes[bIdx + 1] else duration
            val fStart = (tStart / hopSec).toInt().coerceIn(0, numFrames - 1)
            val fEnd = max(fStart + 1, (tEnd / hopSec).toInt().coerceIn(1, numFrames))
            val comb = DoubleArray(12)
            for (f in fStart until fEnd) {
                for (pc in 0 until 12) {
                    // Strong bass root weighting prevents arpeggio upper notes from flipping the chord
                    comb[pc] += 0.58 * frameChroma[f][pc] + 0.42 * bassChroma[f][pc]
                }
            }
            normalizeL2(comb)
            comb
        }

        val totalBars = max(1, (beatTimes.size + 3) / 4)
        // Aggregate chroma at the BAR level first so arpeggiated piano bars get a single stable chord
        val barChroma = Array(totalBars) { barZeroIdx ->
            val bStart = barZeroIdx * 4
            val bEnd = min(beatChroma.size, bStart + 4)
            val vec = DoubleArray(12)
            for (b in bStart until bEnd) {
                // Beat 1 of the bar carries extra harmonic anchor weight (bass root strike)
                val w = if (b == bStart) 1.45 else 1.0
                for (pc in 0 until 12) vec[pc] += beatChroma[b][pc] * w
            }
            normalizeL2(vec)
            vec
        }

        val barEmission = Array(totalBars) { b ->
            DoubleArray(CHORD_TEMPLATES.size) { c ->
                var dot = 0.0
                val tpl = CHORD_TEMPLATES[c].weights
                val bc = barChroma[b]
                for (pc in 0 until 12) dot += bc[pc] * tpl[pc]
                dot
            }
        }
        val decodedBarChordIndices = viterbiDecodeChords(barEmission, diatonicPrior, stayBonus = 0.16)

        // Build beat-indexed ChordEvents stabilized per bar (allowing a half-bar split on Beat 3 only when unmistakable)
        val chordEvents = buildList {
            for (barIdx in 1..totalBars) {
                val barZero = barIdx - 1
                val primaryIdx = decodedBarChordIndices[barZero]
                val bStart = barZero * 4
                val bEnd = min(beatTimes.size, bStart + 4)
                if (bStart >= beatTimes.size) break

                // Check if second half of bar (Beats 3-4) has a distinct strong chord change
                var secondHalfIdx = primaryIdx
                if (bEnd - bStart >= 4) {
                    val half2Vec = DoubleArray(12)
                    for (pc in 0 until 12) {
                        half2Vec[pc] = beatChroma[bStart + 2][pc] * 1.2 + beatChroma[bStart + 3][pc]
                    }
                    normalizeL2(half2Vec)
                    var primaryScore = diatonicPrior[primaryIdx]
                    for (pc in 0 until 12) primaryScore += half2Vec[pc] * CHORD_TEMPLATES[primaryIdx].weights[pc]

                    var bestAltIdx = primaryIdx
                    var bestAltScore = primaryScore
                    for (c in CHORD_TEMPLATES.indices) {
                        var sc = diatonicPrior[c]
                        for (pc in 0 until 12) sc += half2Vec[pc] * CHORD_TEMPLATES[c].weights[pc]
                        if (sc > bestAltScore) {
                            bestAltScore = sc
                            bestAltIdx = c
                        }
                    }
                    if (bestAltIdx != primaryIdx && (bestAltScore - primaryScore) > 0.20) {
                        secondHalfIdx = bestAltIdx
                    }
                }

                for (b in bStart until bEnd) {
                    val beatInBar = (b - bStart) + 1
                    val tplIdx = if (beatInBar >= 3) secondHalfIdx else primaryIdx
                    val tpl = CHORD_TEMPLATES[tplIdx]
                    val tStart = beatTimes[b]
                    val tEnd = if (b + 1 < beatTimes.size) beatTimes[b + 1] else duration
                    add(
                        ChordEvent(
                            chord = tpl.name,
                            root = tpl.root,
                            quality = tpl.quality,
                            startTime = round3(tStart),
                            endTime = round3(max(tStart + 0.1, tEnd)),
                            bar = barIdx,
                            beat = beatInBar,
                            notes = VoicingEngine.chordToMidiNotes(tpl.name, octave = 4)
                        )
                    )
                }
            }
        }

        // 3. Extract smooth foreground vocal melody contour (REPET background suppression + Viterbi pitch path)
        onProgress(0.74f, "מבודד שירה ומחלץ קו מלודיה רציף...")
        val vocalAnchor = extractForegroundVocalAnchor(
            specMag = specMag,
            frameRmsDb = frameRmsDb,
            freqPerBin = freqPerBin,
            hopSec = hopSec
        )

        // 4. Quantize into BarStates & Sections
        onProgress(0.92f, "בונה תיבות ומבנה שיר...")
        val sections = segmentSongSections(barChroma, totalBars, beatTimes, duration)

        val states = buildList {
            for (barIdx in 1..totalBars) {
                val bEvents = chordEvents.filter { it.bar == barIdx }
                if (bEvents.isEmpty()) continue
                val dominant = bEvents.first().chord
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
            totalBeats = beatTimes.size,
            audioPath = audioPath
        )

        val result = SongMap(
            metadata = metadata,
            sections = sections,
            chords = chordEvents,
            states = states,
            vocalAnchor = vocalAnchor,
            lyrics = emptyList()
        )

        DiagnosticLogger.recordAnalysisSummary(
            songMap = result,
            detectedKey = keyName,
            analysisDurationMs = System.currentTimeMillis() - t0,
            rawAudioSaved = !audioPath.isNullOrBlank()
        )
        onProgress(1.0f, "הניתוח הושלם!")
        return result
    }

    private fun bindHarmonicSuppression(sm: FloatArray, bin: Int, maxBin: Int): Double {
        // Slight boost when 2nd harmonic is also present (true pitched note vs percussive noise)
        val h2 = bin * 2
        return if (h2 <= maxBin && sm[h2] > sm[bin] * 0.1f) 1.15 else 1.0
    }

    private fun estimateTempo(onsetEnv: DoubleArray, hopSec: Double): Double {
        if (onsetEnv.size < 32) return 96.0
        val mean = onsetEnv.average()
        val centered = DoubleArray(onsetEnv.size) { max(0.0, onsetEnv[it] - mean) }

        val minLag = max(8, (60.0 / (165.0 * hopSec)).roundToInt())
        val maxLag = min(onsetEnv.size / 2, (60.0 / (60.0 * hopSec)).roundToInt())
        if (maxLag <= minLag) return 96.0

        val acScores = DoubleArray(maxLag + 1)
        for (lag in minLag..maxLag) {
            var ac = 0.0
            val count = centered.size - lag
            for (i in 0 until count) ac += centered[i] * centered[i + lag]
            acScores[lag] = ac / max(1, count)
        }

        var bestBpm = 96.0
        var bestScore = -1.0
        for (lag in minLag..maxLag) {
            // Reinforce integer multiples (2x lag = bar/half-bar periodicity)
            val twoLag = if (lag * 2 <= maxLag) acScores[lag * 2] * 0.35 else 0.0
            val candidateBpm = 60.0 / (lag * hopSec)
            // Gentle log-Gaussian prior centered at 96 BPM (ideal for ballads & mid-tempo pop)
            val dist = log2(candidateBpm / 96.0) / 0.52
            val prior = exp(-0.5 * dist * dist)
            val score = (acScores[lag] + twoLag) * (0.62 + 0.38 * prior)
            if (score > bestScore) {
                bestScore = score
                bestBpm = candidateBpm
            }
        }
        return bestBpm.coerceIn(60.0, 165.0)
    }

    /**
     * Ellis (2007) Dynamic Programming beat tracker: locks beats onto real musical onsets
     * while maintaining smooth tempo continuity.
     */
    private fun trackBeatsDynamic(
        onsetEnv: DoubleArray,
        hopSec: Double,
        bpm: Double,
        numFrames: Int
    ): IntArray {
        val period = max(6, (60.0 / (bpm * hopSec)).roundToInt())
        if (numFrames <= period * 2) {
            return IntArray(max(1, numFrames / period)) { it * period }
        }

        // Smooth onset envelope with Gaussian kernel of width period/8
        val halfWin = max(1, period / 8)
        val localScore = DoubleArray(numFrames)
        var maxLocal = 1e-6
        for (i in 0 until numFrames) {
            var s = 0.0
            for (k in -halfWin..halfWin) {
                val idx = i + k
                if (idx in 0 until numFrames) {
                    val w = exp(-0.5 * (k.toDouble() / halfWin).pow(2.0))
                    s += onsetEnv[idx] * w
                }
            }
            localScore[i] = s
            if (s > maxLocal) maxLocal = s
        }
        for (i in 0 until numFrames) localScore[i] /= maxLocal

        val minLag = max(4, (period * 0.65).roundToInt())
        val maxLag = max(minLag + 2, (period * 1.45).roundToInt())
        val tightness = 140.0

        val cumScore = DoubleArray(numFrames)
        val backlink = IntArray(numFrames) { -1 }

        for (i in 0 until numFrames) {
            var bestPrev = -1
            var bestScore = 0.0
            val searchStart = max(0, i - maxLag)
            val searchEnd = i - minLag
            if (searchEnd >= searchStart) {
                for (prev in searchStart..searchEnd) {
                    val lag = (i - prev).toDouble()
                    val logRatio = ln(lag / period)
                    val penalty = tightness * logRatio * logRatio
                    val cand = cumScore[prev] - penalty
                    if (bestPrev == -1 || cand > bestScore) {
                        bestScore = cand
                        bestPrev = prev
                    }
                }
            }
            cumScore[i] = localScore[i] + if (bestPrev >= 0) bestScore else 0.0
            backlink[i] = bestPrev
        }

        // Backtrack from best peak near end
        var curr = numFrames - 1
        val tailStart = max(0, numFrames - period * 2)
        for (i in tailStart until numFrames) {
            if (cumScore[i] > cumScore[curr]) curr = i
        }

        val beatsRev = ArrayList<Int>(numFrames / period + 4)
        while (curr >= 0) {
            beatsRev.add(curr)
            curr = backlink[curr]
        }
        beatsRev.reverse()
        return beatsRev.toIntArray()
    }

    /**
     * Aligns the beat sequence so index 0 (and every 4th beat) falls on the musical Downbeat (Beat 1)
     * where bass root notes and chord changes occur.
     */
    private fun alignBeatGridToDownbeats(
        beatFrames: IntArray,
        frameChroma: Array<DoubleArray>,
        bassEnergy: DoubleArray,
        hopSec: Double,
        bpm: Double,
        duration: Double
    ): List<Double> {
        val beatPeriodSec = 60.0 / bpm
        if (beatFrames.size < 8) {
            val out = mutableListOf<Double>()
            var t = 0.0
            while (t < duration - 0.1) {
                out.add(t)
                t += beatPeriodSec
            }
            return out.ifEmpty { listOf(0.0) }
        }

        // Score each of the 4 possible downbeat phases (0, 1, 2, 3)
        val phaseScores = DoubleArray(4)
        for (b in 1 until beatFrames.size) {
            val fCurr = beatFrames[b].coerceIn(0, frameChroma.lastIndex)
            val fPrev = beatFrames[b - 1].coerceIn(0, frameChroma.lastIndex)
            var chromaDiff = 0.0
            for (pc in 0 until 12) {
                val d = frameChroma[fCurr][pc] - frameChroma[fPrev][pc]
                chromaDiff += d * d
            }
            val bassWinEnd = min(bassEnergy.lastIndex, fCurr + 6)
            var bassPeak = 0.0
            for (f in fCurr..bassWinEnd) {
                if (bassEnergy[f] > bassPeak) bassPeak = bassEnergy[f]
            }
            phaseScores[b % 4] += chromaDiff + 0.002 * bassPeak
        }

        var bestPhase = 0
        for (p in 1..3) {
            if (phaseScores[p] > phaseScores[bestPhase]) bestPhase = p
        }

        // Start from the first aligned downbeat (or prepend clean beats back to 0.0 if needed)
        val aligned = mutableListOf<Double>()
        val firstDownbeatSec = beatFrames[bestPhase] * hopSec
        if (firstDownbeatSec > beatPeriodSec * 1.5) {
            // Keep leading pickup bar aligned on a 4-beat boundary
            var t = firstDownbeatSec - 4.0 * beatPeriodSec
            while (t < 0.0) t += beatPeriodSec
            var bIdx = 0
            while (bIdx < beatFrames.size && beatFrames[bIdx] * hopSec < firstDownbeatSec - 0.15) {
                bIdx++
            }
            for (i in bIdx until beatFrames.size) {
                aligned.add(beatFrames[i] * hopSec)
            }
        } else {
            for (i in bestPhase until beatFrames.size) {
                aligned.add(beatFrames[i] * hopSec)
            }
        }

        if (aligned.isEmpty()) aligned.add(0.0)
        return aligned
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
                expected == tpl.quality -> 0.12
                (rel == 5 || rel == 7) && tpl.quality == "major" -> 0.05
                else -> -0.06
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

    /**
     * Extracts a smooth, octave-consistent foreground vocal melody contour from a polyphonic mix:
     * 1. Subtracts local temporal median background spectrum (REPET-style piano pad suppression)
     * 2. Evaluates harmonic summation salience over semitone candidates (MIDI 48..81 / C3..A5)
     * 3. Decodes the globally smooth vocal pitch trajectory via Viterbi dynamic programming
     * 4. Suppresses short (<140ms) instrumental transients
     */
    private fun extractForegroundVocalAnchor(
        specMag: Array<FloatArray>,
        frameRmsDb: DoubleArray,
        freqPerBin: Double,
        hopSec: Double
    ): VocalAnchor {
        val numFrames = specMag.size
        val maxBin = if (numFrames > 0) specMag[0].size - 1 else 0
        val minMidi = 48 // C3 (130.8 Hz)
        val maxMidi = 81 // A5 (880.0 Hz)
        val numPitches = maxMidi - minMidi + 1

        // Precompute harmonic bin indices for each candidate MIDI note (H = 1..4)
        val pitchHarmonicBins = Array(numPitches) { pIdx ->
            val midi = minMidi + pIdx
            val f0 = 440.0 * 2.0.pow((midi - 69.0) / 12.0)
            IntArray(4) { h ->
                ((h + 1) * f0 / freqPerBin).roundToInt().coerceIn(1, maxBin)
            }
        }
        val harmonicWeights = doubleArrayOf(1.0, 0.72, 0.48, 0.30)

        // Frame-by-frame foreground salience matrix
        val salience = Array(numFrames) { DoubleArray(numPitches) }
        val frameVoicedEnergy = DoubleArray(numFrames)
        val scratchBg = FloatArray(11)

        for (f in 0 until numFrames) {
            val curSpec = specMag[f]
            var maxSal = 0.0
            var totalSal = 0.0

            for (pIdx in 0 until numPitches) {
                val hBins = pitchHarmonicBins[pIdx]
                var sal = 0.0
                for (h in 0 until 4) {
                    val b = hBins[h]
                    // Local temporal median across ±25 frames (step 5) to estimate repeating piano background
                    var count = 0
                    for (offset in -25..25 step 5) {
                        val nf = f + offset
                        if (nf in 0 until numFrames) {
                            scratchBg[count++] = specMag[nf][b]
                        }
                    }
                    scratchBg.sort(0, count)
                    val bgMedian = scratchBg[count / 2]
                    // Foreground peak above repeating background (plus soft floor so solo vocals work too)
                    val fg = max(curSpec[b] - 0.78f * bgMedian, curSpec[b] * 0.28f).toDouble()
                    sal += fg * harmonicWeights[h]
                }
                salience[f][pIdx] = sal
                totalSal += sal
                if (sal > maxSal) maxSal = sal
            }

            // Normalize frame salience & compute peak-to-mean crest factor (voiced clarity)
            val meanSal = totalSal / numPitches
            val crest = if (meanSal > 1e-6) maxSal / meanSal else 0.0
            frameVoicedEnergy[f] = crest
            if (maxSal > 1e-6) {
                for (pIdx in 0 until numPitches) {
                    salience[f][pIdx] /= maxSal
                }
            }
        }

        // Viterbi pitch tracking across voiced frames to prevent octave/arpeggio jumps
        val dp = DoubleArray(numPitches) { p -> salience[0][p] }
        val back = Array(numFrames) { IntArray(numPitches) }

        for (f in 1 until numFrames) {
            val nextDp = DoubleArray(numPitches)
            for (curr in 0 until numPitches) {
                var bestPrev = curr
                var bestVal = Double.NEGATIVE_INFINITY
                for (prev in 0 until numPitches) {
                    val semiJump = abs(curr - prev)
                    val jumpPenalty = when {
                        semiJump == 0 -> 0.0
                        semiJump <= 2 -> 0.06 * semiJump
                        semiJump <= 5 -> 0.16 * semiJump
                        else -> 0.45 + 0.22 * semiJump // Strongly penalize sudden octave/chord jumps
                    }
                    val cand = dp[prev] - jumpPenalty
                    if (cand > bestVal) {
                        bestVal = cand
                        bestPrev = prev
                    }
                }
                nextDp[curr] = bestVal + salience[f][curr]
                back[f][curr] = bestPrev
            }
            for (p in 0 until numPitches) dp[p] = nextDp[p]
        }

        val bestPitchPath = IntArray(numFrames)
        var bestEnd = 0
        for (p in 1 until numPitches) {
            if (dp[p] > dp[bestEnd]) bestEnd = p
        }
        bestPitchPath[numFrames - 1] = bestEnd
        for (f in numFrames - 1 downTo 1) {
            bestPitchPath[f - 1] = back[f][bestPitchPath[f]]
        }

        // Determine silence gate relative to track peak RMS
        val maxRms = frameRmsDb.maxOrNull() ?: -20.0
        val rmsGate = max(-46.0, maxRms - 28.0)

        val times = DoubleArray(numFrames)
        val f0Hz = DoubleArray(numFrames)
        val midiNotes = DoubleArray(numFrames)
        val voicedProb = DoubleArray(numFrames)

        for (f in 0 until numFrames) {
            times[f] = round3(f * hopSec)
            val crest = frameVoicedEnergy[f]
            val pIdx = bestPitchPath[f]
            val sal = salience[f][pIdx]
            val conf = ((crest - 1.35) / 1.65 * sal).coerceIn(0.0, 1.0)
            if (frameRmsDb[f] >= rmsGate && conf >= 0.38) {
                val midi = (minMidi + pIdx).toDouble()
                midiNotes[f] = midi
                f0Hz[f] = round2(440.0 * 2.0.pow((midi - 69.0) / 12.0))
                voicedProb[f] = round2(conf)
            }
        }

        // Suppress isolated short voiced bursts (< 5 frames / ~115ms) caused by piano/drum attacks
        var runStart = -1
        for (f in 0..numFrames) {
            val v = f < numFrames && voicedProb[f] > 0.0
            if (v && runStart < 0) {
                runStart = f
            } else if (!v && runStart >= 0) {
                if (f - runStart < 5) {
                    for (k in runStart until f) {
                        f0Hz[k] = 0.0
                        midiNotes[k] = 0.0
                        voicedProb[k] = 0.0
                    }
                }
                runStart = -1
            }
        }

        return VocalAnchor(
            times = times,
            f0Hz = f0Hz,
            midiNotes = midiNotes,
            voicedProb = voicedProb,
            rmsDb = DoubleArray(numFrames) { round2(frameRmsDb[it]) }
        )
    }

    private fun segmentSongSections(
        barChroma: Array<DoubleArray>,
        totalBars: Int,
        beatTimes: List<Double>,
        duration: Double
    ): List<SongSection> {
        if (totalBars <= 4) {
            return listOf(
                SongSection("verse_1", "Verse 1", 0.0, round3(duration), 1, totalBars)
            )
        }

        val boundaries = mutableListOf(1)
        var lastBoundary = 1
        for (b in 5..totalBars - 3) {
            if (b - lastBoundary < 4) continue
            var dist = 0.0
            for (pc in 0 until 12) {
                val d = barChroma[b - 1][pc] - barChroma[b - 2][pc]
                dist += d * d
            }
            if ((dist > 0.30 && (b - 1) % 4 == 0) || b - lastBoundary >= 8) {
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

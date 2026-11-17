package com.aijam.companion.engine

import com.aijam.companion.model.SongMap
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Monotonic, jitter-free Online Dynamic Time Warping (ODTW) score follower.
 * - Advances automatically at 1.0x during instrumental intros/interludes.
 * - Never jumps backwards (monotonic frame progression).
 * - Smoothly tracks live vocal phrasing with bounded per-hop rate (0..2 frames/hop).
 */
class OnlineScoreFollower(
    songMap: SongMap,
    private val windowSizeFrames: Int = 96,
    private val stepPenalty: Double = 0.0,
    private val stayPenalty: Double = 0.08,
    private val skipPenalty: Double = 0.05
) {
    private val refTimes = songMap.vocalAnchor.times
    private val refPitches = songMap.vocalAnchor.midiNotes
    private val refVoiced = songMap.vocalAnchor.voicedProb
    private val totalFrames = refPitches.size
    private val duration = songMap.metadata.duration

    private val firstVocalFrame: Int = run {
        var first = 0
        for (i in 0 until totalFrames) {
            if (refVoiced[i] > 0.35 && refPitches[i] > 0.0) {
                first = i
                break
            }
        }
        first
    }

    var currentFrame: Int = 0
        private set
    private var fractionalFrame: Double = 0.0
    var estimatedTempoRatio: Double = 1.0
        private set
    var confidence: Double = 0.75
        private set

    private val accumulatedCosts = DoubleArray(totalFrames)
    private var hasLockedVocal = false
    private val frameHistory = ArrayDeque<Int>(48)

    init {
        reset()
    }

    fun reset() {
        currentFrame = 0
        fractionalFrame = 0.0
        estimatedTempoRatio = 1.0
        accumulatedCosts.fill(100.0)
        if (totalFrames > 0) accumulatedCosts[0] = 0.0
        confidence = 0.75
        hasLockedVocal = false
        frameHistory.clear()
    }

    /**
     * Returns true if the reference song map expects vocal singing at [frameIdx]
     * (checks a tight ±4 frame / ~90ms neighborhood so brief consonant gaps don't flip state,
     * while advancing cleanly all the way to [firstVocalFrame] during the instrumental intro).
     */
    fun isReferenceVocalExpected(frameIdx: Int = currentFrame): Boolean {
        if (totalFrames == 0 || frameIdx < firstVocalFrame) return false
        val start = max(0, frameIdx - 4)
        val end = min(totalFrames - 1, frameIdx + 4)
        var voicedCount = 0
        for (i in start..end) {
            if (refVoiced[i] > 0.35 && refPitches[i] > 0.0) voicedCount++
        }
        return voicedCount >= 3
    }

    private fun frameCosts(
        liveMidi: Double,
        liveVoiced: Boolean,
        wStart: Int,
        wEnd: Int
    ): DoubleArray {
        val len = wEnd - wStart
        val costs = DoubleArray(len)
        if (!liveVoiced) {
            for (i in 0 until len) {
                val j = wStart + i
                val rv = refVoiced[j] > 0.35 && refPitches[j] > 0.0
                costs[i] = if (rv) 0.35 else 0.05
            }
            return costs
        }

        for (i in 0 until len) {
            val j = wStart + i
            val rp = refPitches[j]
            val rv = refVoiced[j] > 0.35 && rp > 0.0
            if (!rv) {
                costs[i] = 0.65
            } else {
                val diff = abs(liveMidi - rp)
                val mod12 = diff % 12.0
                val octDiff = min(mod12, 12.0 - mod12)
                val bestDiff = min(diff, octDiff + 1.0)
                val ratio = bestDiff / 2.4
                costs[i] = 1.0 - exp(-(ratio * ratio))
            }
        }
        return costs
    }

    private fun tryVocalEntryLock(liveMidi: Double) {
        if (totalFrames == 0) return
        val searchStart = max(currentFrame, firstVocalFrame - 12)
        val searchEnd = min(totalFrames, firstVocalFrame + 60)
        if (searchEnd <= searchStart) return

        var bestOffset = -1
        var bestDiff = 99.0
        for (j in searchStart until searchEnd) {
            if (refVoiced[j] > 0.35 && refPitches[j] > 0.0) {
                val d = abs(liveMidi - refPitches[j])
                val mod12 = d % 12.0
                val octD = min(mod12, 12.0 - mod12)
                val effD = min(d, octD + 0.8)
                if (effD < bestDiff) {
                    bestDiff = effD
                    bestOffset = j
                }
            }
        }
        if (bestOffset >= currentFrame && bestDiff <= 2.2) {
            currentFrame = bestOffset
            fractionalFrame = bestOffset.toDouble()
            accumulatedCosts.fill(50.0)
            accumulatedCosts[bestOffset] = 0.0
            hasLockedVocal = true
        }
    }

    fun step(
        liveMidi: Double,
        liveVoiced: Boolean
    ): Triple<Int, Double, Double> {
        if (totalFrames == 0) return Triple(0, 0.0, 0.75)

        val refExpectsVocal = isReferenceVocalExpected(currentFrame)

        // 1. If we are in an instrumental passage (intro / interlude) and singer hasn't locked ahead,
        // advance steadily at 1.0x so the accompaniment plays through the instrumental bars cleanly.
        if (!refExpectsVocal && !liveVoiced) {
            fractionalFrame = min((totalFrames - 1).toDouble(), fractionalFrame + 1.0)
            currentFrame = fractionalFrame.roundToInt().coerceIn(0, totalFrames - 1)
            accumulatedCosts.fill(50.0)
            accumulatedCosts[currentFrame] = 0.0
            confidence = max(0.70, confidence * 0.995)
            val refTime = if (currentFrame < refTimes.size) refTimes[currentFrame] else duration
            return Triple(currentFrame, refTime, confidence)
        }

        if (liveVoiced && !hasLockedVocal) {
            if (currentFrame < firstVocalFrame - 8) {
                tryVocalEntryLock(liveMidi)
            } else {
                hasLockedVocal = true
            }
        }

        // 2. Monotonic forward window [currentFrame .. currentFrame + windowSizeFrames]
        // Never allow wStart < currentFrame (prevents backward jumping!).
        val wStart = currentFrame
        val wEnd = min(totalFrames, currentFrame + windowSizeFrames)
        val winLen = max(1, wEnd - wStart)
        val localCosts = frameCosts(liveMidi, liveVoiced, wStart, wEnd)

        val effStay = if (!liveVoiced) 0.0 else stayPenalty
        val effStep = if (!liveVoiced) 0.14 else stepPenalty
        val effSkip = if (!liveVoiced) 0.35 else skipPenalty

        val newWin = DoubleArray(winLen)
        var minCost = Double.MAX_VALUE
        for (idx in 0 until winLen) {
            val j = wStart + idx
            val prevStay = accumulatedCosts[j] + effStay
            val prevStep = if (j > 0) accumulatedCosts[j - 1] + effStep else 1e5
            val prevSkip = if (j > 1) accumulatedCosts[j - 2] + effSkip else 1e5
            // Small distance bias keeps argmin anchored near the wavefront unless strong match pulls it
            val distBias = idx * 0.018
            val v = localCosts[idx] + minOf(prevStay, prevStep, prevSkip) + distBias
            newWin[idx] = v
            if (v < minCost) minCost = v
        }

        var sumWin = 0.0
        var bestLocalIdx = 0
        var bestWinVal = Double.MAX_VALUE
        for (idx in 0 until winLen) {
            newWin[idx] -= minCost
            sumWin += newWin[idx]
            if (newWin[idx] < bestWinVal) {
                bestWinVal = newWin[idx]
                bestLocalIdx = idx
            }
        }

        accumulatedCosts.fill(1e4)
        for (idx in 0 until winLen) {
            accumulatedCosts[wStart + idx] = newWin[idx]
        }

        // Clamp per-hop frame advance to at most 2 frames (~2.0x real-time speed)
        // so noisy mic frames can NEVER teleport across bars.
        val rawAdvance = if (liveVoiced) {
            bestLocalIdx.coerceIn(0, 2)
        } else {
            0
        }
        val bestJ = (currentFrame + rawAdvance).coerceIn(0, totalFrames - 1)
        val bestLocalCost = localCosts[bestJ - wStart]

        val sharpnessConf = if (winLen > 2) {
            val spread = (sumWin / winLen) - bestWinVal
            1.0 / (1.0 + exp(-2.0 * (spread - 1.0)))
        } else {
            0.65
        }
        val matchConf = 1.0 - bestLocalCost
        val rawConf = 0.55 * sharpnessConf + 0.45 * matchConf

        if (liveVoiced) {
            confidence = 0.85 * confidence + 0.15 * rawConf
            frameHistory.addLast(bestJ)
            if (frameHistory.size > 40) {
                frameHistory.removeFirst()
                val deltaRef = frameHistory.last() - frameHistory.first()
                val rawRatio = deltaRef.toDouble() / max(1, frameHistory.size - 1)
                if (rawRatio in 0.70..1.35) {
                    estimatedTempoRatio = 0.92 * estimatedTempoRatio + 0.08 * rawRatio
                }
            }
        } else {
            confidence = max(0.55, confidence * 0.992)
        }

        currentFrame = bestJ
        fractionalFrame = bestJ.toDouble()
        val refTime = if (bestJ < refTimes.size) refTimes[bestJ] else duration
        return Triple(bestJ, refTime, confidence.coerceIn(0.0, 1.0))
    }

    fun jumpToTime(targetTime: Double) {
        if (totalFrames == 0) return
        var lo = 0
        var hi = refTimes.size - 1
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (refTimes[mid] < targetTime) lo = mid + 1 else hi = mid
        }
        val idx = lo.coerceIn(0, totalFrames - 1)
        currentFrame = idx
        fractionalFrame = idx.toDouble()
        accumulatedCosts.fill(50.0)
        accumulatedCosts[idx] = 0.0
        hasLockedVocal = true
        frameHistory.clear()
    }

    fun getProgressPercent(): Float {
        if (totalFrames <= 1) return 0f
        return ((currentFrame.toFloat() / (totalFrames - 1)) * 100f).coerceIn(0f, 100f)
    }
}

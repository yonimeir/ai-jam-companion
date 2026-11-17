package com.aijam.companion.engine

import com.aijam.companion.model.SongMap
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min

/**
 * Online Dynamic Time Warping (ODTW) score follower.
 * Tracks live singer position against the song's reference vocal contour,
 * handling intros, breath pauses, tempo drift, octave jumps, and forward skips.
 */
class OnlineScoreFollower(
    songMap: SongMap,
    private val windowSizeFrames: Int = 120,
    private val stepPenalty: Double = 0.0,
    private val stayPenalty: Double = 0.06,
    private val skipPenalty: Double = 0.03
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
    var estimatedTempoRatio: Double = 1.0
        private set
    var confidence: Double = 0.5
        private set

    private val accumulatedCosts = DoubleArray(totalFrames)
    private var hasLockedVocal = false
    private val frameHistory = ArrayDeque<Int>(48)
    private val recentVoicedPitches = ArrayDeque<Double>(20)
    private var mismatchStreak = 0

    init {
        reset()
    }

    fun reset() {
        currentFrame = 0
        estimatedTempoRatio = 1.0
        accumulatedCosts.fill(100.0)
        if (totalFrames > 0) accumulatedCosts[0] = 0.0
        confidence = 0.5
        hasLockedVocal = false
        frameHistory.clear()
        recentVoicedPitches.clear()
        mismatchStreak = 0
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
                costs[i] = 0.78
            } else {
                val diff = abs(liveMidi - rp)
                val mod12 = diff % 12.0
                val octDiff = min(mod12, 12.0 - mod12)
                val bestDiff = min(diff, octDiff + 1.2)
                val ratio = bestDiff / 2.2
                costs[i] = 1.0 - exp(-(ratio * ratio))
            }
        }
        return costs
    }

    private fun tryVocalEntryLock(liveMidi: Double) {
        if (totalFrames == 0) return
        val searchStart = max(0, firstVocalFrame - 15)
        val searchEnd = min(totalFrames, firstVocalFrame + 120)
        if (searchEnd <= searchStart) return

        var bestOffset = -1
        var bestDiff = 99.0
        for (j in searchStart until searchEnd) {
            if (refVoiced[j] > 0.35 && refPitches[j] > 0.0) {
                val d = abs(liveMidi - refPitches[j])
                if (d < bestDiff) {
                    bestDiff = d
                    bestOffset = j
                }
            }
        }
        if (bestOffset >= 0 && bestDiff <= 2.5) {
            currentFrame = bestOffset
            accumulatedCosts.fill(50.0)
            accumulatedCosts[bestOffset] = 0.0
            hasLockedVocal = true
        }
    }

    private fun tryGlobalResync(): Boolean {
        val m = recentVoicedPitches.size
        if (m < 8 || totalFrames <= m + 20) return false
        val seq = recentVoicedPitches.toDoubleArray()

        var bestIdx = -1
        var bestScore = 999.0
        var bestRawErr = 999.0
        val maxEnd = min(totalFrames - m, currentFrame + 600)

        var start = currentFrame
        while (start < maxEnd) {
            var voicedCount = 0
            var errSum = 0.0
            for (k in 0 until m) {
                val rp = refPitches[start + k]
                val rv = refVoiced[start + k] > 0.35 && rp > 0.0
                if (rv) {
                    voicedCount++
                    val d = abs(seq[k] - rp)
                    val mod12 = d % 12.0
                    val oct = min(mod12, 12.0 - mod12)
                    errSum += min(d, oct + 1.0)
                }
            }
            if (voicedCount >= m * 0.65) {
                val rawErr = errSum / voicedCount
                val score = rawErr + 0.0025 * (start - currentFrame)
                if (score < bestScore) {
                    bestScore = score
                    bestRawErr = rawErr
                    bestIdx = start + m - 1
                }
            }
            start += 2
        }

        if (bestIdx >= 0 && bestRawErr < 1.3) {
            currentFrame = bestIdx
            accumulatedCosts.fill(50.0)
            accumulatedCosts[bestIdx] = 0.0
            mismatchStreak = 0
            return true
        }
        return false
    }

    fun step(
        liveMidi: Double,
        liveVoiced: Boolean
    ): Triple<Int, Double, Double> {
        if (totalFrames == 0) return Triple(0, 0.0, 0.0)

        if (liveVoiced) {
            recentVoicedPitches.addLast(liveMidi)
            if (recentVoicedPitches.size > 16) recentVoicedPitches.removeFirst()

            if (!hasLockedVocal) {
                if (currentFrame < firstVocalFrame - 10) {
                    tryVocalEntryLock(liveMidi)
                } else {
                    hasLockedVocal = true
                }
            }
        }

        val wStart = max(0, currentFrame - 8)
        val wEnd = min(totalFrames, currentFrame + windowSizeFrames)
        val winLen = wEnd - wStart
        val localCosts = frameCosts(liveMidi, liveVoiced, wStart, wEnd)

        val effStay = if (!liveVoiced) 0.0 else stayPenalty
        val effStep = if (!liveVoiced) 0.12 else stepPenalty
        val effSkip = if (!liveVoiced) 0.30 else skipPenalty

        val newWin = DoubleArray(winLen)
        var minCost = Double.MAX_VALUE
        for (idx in 0 until winLen) {
            val j = wStart + idx
            val prevStay = accumulatedCosts[j] + effStay
            val prevStep = if (j > 0) accumulatedCosts[j - 1] + effStep else 1e5
            val prevSkip = if (j > 1) accumulatedCosts[j - 2] + effSkip else 1e5
            val prevLeap = if (j > 2) accumulatedCosts[j - 3] + effSkip * 3.0 else 1e5
            val v = localCosts[idx] + minOf(prevStay, prevStep, prevSkip, prevLeap)
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

        var bestJ = wStart + bestLocalIdx
        var bestLocalCost = localCosts[bestLocalIdx]

        if (liveVoiced) {
            if (bestLocalCost > 0.55) {
                mismatchStreak++
                if (mismatchStreak >= 25 && tryGlobalResync()) {
                    bestJ = currentFrame
                    bestLocalCost = 0.1
                }
            } else {
                mismatchStreak = 0
            }
        }

        val sharpnessConf = if (winLen > 2) {
            val spread = (sumWin / winLen) - bestWinVal
            1.0 / (1.0 + exp(-2.0 * (spread - 1.2)))
        } else {
            0.5
        }
        val matchConf = 1.0 - bestLocalCost
        val rawConf = 0.6 * sharpnessConf + 0.4 * matchConf

        if (liveVoiced) {
            confidence = 0.80 * confidence + 0.20 * rawConf
            frameHistory.addLast(bestJ)
            if (frameHistory.size > 40) {
                frameHistory.removeFirst()
                val deltaRef = frameHistory.last() - frameHistory.first()
                val rawRatio = deltaRef.toDouble() / max(1, frameHistory.size - 1)
                if (rawRatio in 0.3..2.2) {
                    estimatedTempoRatio = 0.9 * estimatedTempoRatio + 0.1 * rawRatio
                }
            }
        } else {
            confidence = max(0.35, confidence * 0.985)
        }

        currentFrame = bestJ
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
        accumulatedCosts.fill(50.0)
        accumulatedCosts[idx] = 0.0
        hasLockedVocal = true
        mismatchStreak = 0
    }

    fun getProgressPercent(): Float {
        if (totalFrames <= 1) return 0f
        return ((currentFrame.toFloat() / (totalFrames - 1)) * 100f).coerceIn(0f, 100f)
    }
}

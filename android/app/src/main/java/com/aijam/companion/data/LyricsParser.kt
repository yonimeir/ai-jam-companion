package com.aijam.companion.data

import com.aijam.companion.model.BarState
import com.aijam.companion.model.LyricLine
import com.aijam.companion.model.SongMap
import kotlin.math.abs

/**
 * Parses synced .lrc files or plain-text lyrics and aligns them to bars in a SongMap.
 */
object LyricsParser {

    private val LRC_TAG_REGEX = Regex("""\[(\d{1,3}):(\d{2})(?:[.:](\d{1,3}))?]""")
    private val METADATA_TAG_REGEX = Regex("""^\[[a-zA-Z]+:.*]$""")

    /**
     * Parses either LRC-timestamped lyrics or plain multi-line text.
     * Automatically detects whether timestamps are present.
     */
    fun parseAndAlign(rawText: String, songMap: SongMap): List<LyricLine> {
        val trimmed = rawText.trim()
        if (trimmed.isEmpty()) return emptyList()

        val lrcEntries = parseLrcEntries(trimmed)
        if (lrcEntries.isNotEmpty()) {
            return alignTimedEntries(lrcEntries, songMap)
        }
        return autoDistributePlainText(trimmed, songMap)
    }

    fun isLrcFormat(rawText: String): Boolean = LRC_TAG_REGEX.containsMatchIn(rawText)

    private fun parseLrcEntries(rawText: String): List<Pair<Double, String>> {
        val entries = mutableListOf<Pair<Double, String>>()
        for (rawLine in rawText.lines()) {
            val line = rawLine.trim()
            if (line.isEmpty()) continue
            val matches = LRC_TAG_REGEX.findAll(line).toList()
            if (matches.isEmpty()) {
                if (METADATA_TAG_REGEX.matches(line)) continue
                continue
            }
            val text = LRC_TAG_REGEX.replace(line, "").trim()
            if (text.isEmpty()) continue
            for (m in matches) {
                val mins = m.groupValues[1].toIntOrNull() ?: 0
                val secs = m.groupValues[2].toIntOrNull() ?: 0
                val fracRaw = m.groupValues[3]
                val fracSec = when (fracRaw.length) {
                    0 -> 0.0
                    1 -> fracRaw.toDouble() / 10.0
                    2 -> fracRaw.toDouble() / 100.0
                    else -> fracRaw.toDouble() / 1000.0
                }
                val timeSec = mins * 60.0 + secs + fracSec
                entries.add(timeSec to text)
            }
        }
        return entries.sortedBy { it.first }
    }

    private fun alignTimedEntries(
        entries: List<Pair<Double, String>>,
        songMap: SongMap
    ): List<LyricLine> {
        val duration = songMap.metadata.duration.coerceAtLeast(1.0)
        return entries.mapIndexed { idx, (start, text) ->
            val clampedStart = start.coerceIn(0.0, duration)
            val nextStart = entries.getOrNull(idx + 1)?.first ?: (clampedStart + 4.5)
            val end = nextStart.coerceIn(clampedStart + 0.5, duration)
            val bar = findNearestBar(clampedStart, songMap.states)
            LyricLine(
                startTime = clampedStart,
                endTime = end,
                bar = bar,
                text = text
            )
        }
    }

    /**
     * Distributes plain lyrics lines smoothly across vocal/song bars.
     * Skips an initial instrumental Intro section when enough bars remain.
     */
    private fun autoDistributePlainText(rawText: String, songMap: SongMap): List<LyricLine> {
        val lines = rawText.lines()
            .map { LRC_TAG_REGEX.replace(it, "").trim() }
            .filter { it.isNotBlank() && !METADATA_TAG_REGEX.matches(it) }
        if (lines.isEmpty()) return emptyList()

        val states = songMap.states
        if (states.isEmpty()) {
            val dur = songMap.metadata.duration.coerceAtLeast(lines.size * 2.0)
            val step = dur / lines.size
            return lines.mapIndexed { i, text ->
                LyricLine(
                    startTime = i * step,
                    endTime = (i + 1) * step,
                    bar = i + 1,
                    text = text
                )
            }
        }

        val vocalStartBar = findVocalStartBar(songMap)
        val candidateStates = states.filter { it.bar >= vocalStartBar }
            .ifEmpty { states }

        val firstStart = candidateStates.first().startTime
        val lastEnd = candidateStates.last().endTime
        val span = (lastEnd - firstStart).coerceAtLeast(lines.size * 1.5)
        val perLine = span / lines.size

        return lines.mapIndexed { idx, text ->
            val start = firstStart + idx * perLine
            val end = (firstStart + (idx + 1) * perLine).coerceAtMost(songMap.metadata.duration)
            val bar = findNearestBar(start, states)
            LyricLine(
                startTime = start,
                endTime = end.coerceAtLeast(start + 0.4),
                bar = bar,
                text = text
            )
        }
    }

    private fun findVocalStartBar(songMap: SongMap): Int {
        val introSection = songMap.sections.firstOrNull {
            it.name.equals("intro", ignoreCase = true) || it.label.equals("intro", ignoreCase = true)
        }
        val firstVocalFrameIdx = songMap.vocalAnchor.voicedProb.indexOfFirst { it > 0.4 }
        val firstVocalTime = if (firstVocalFrameIdx >= 0 && firstVocalFrameIdx < songMap.vocalAnchor.times.size) {
            songMap.vocalAnchor.times[firstVocalFrameIdx]
        } else {
            introSection?.endTime ?: 0.0
        }

        val thresholdTime = if (introSection != null && songMap.states.size > 6) {
            maxOf(introSection.startTime, minOf(introSection.endTime, firstVocalTime))
        } else {
            firstVocalTime
        }
        val match = songMap.states.firstOrNull { it.endTime > thresholdTime }
        return match?.bar ?: songMap.states.firstOrNull()?.bar ?: 1
    }

    private fun findNearestBar(timeSec: Double, states: List<BarState>): Int {
        if (states.isEmpty()) return 1
        val inside = states.firstOrNull { timeSec >= it.startTime && timeSec < it.endTime }
        if (inside != null) return inside.bar
        return states.minByOrNull { abs(it.startTime - timeSec) }?.bar ?: 1
    }

    fun toLrcString(lyrics: List<LyricLine>): String {
        return lyrics.joinToString("\n") { line ->
            val totalMs = (line.startTime * 1000.0).toInt().coerceAtLeast(0)
            val mins = totalMs / 60_000
            val secs = (totalMs % 60_000) / 1000
            val hundredths = (totalMs % 1000) / 10
            "[%02d:%02d.%02d] %s".format(mins, secs, hundredths, line.text)
        }
    }
}

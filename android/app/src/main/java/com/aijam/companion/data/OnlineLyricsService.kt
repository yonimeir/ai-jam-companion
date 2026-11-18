package com.aijam.companion.data

import com.aijam.companion.model.LyricLine
import com.aijam.companion.model.SongMap
import com.aijam.companion.model.SongSection
import com.aijam.companion.util.DiagnosticLogger
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import kotlin.math.abs
import kotlin.math.roundToInt
import org.json.JSONArray

data class OnlineLyricsResult(
    val trackName: String,
    val artistName: String,
    val rawLrcOrPlain: String,
    val isSyncedLrc: Boolean
)

/**
 * Fetches exact millisecond-synced LRC lyrics from LRCLIB (https://lrclib.net/api/search)
 * and refines song section boundaries (Intro, Verse, Chorus, Bridge, Outro) using vocal phrases.
 */
object OnlineLyricsService {

    private val NOISE_TOKENS_REGEX = Regex(
        """(?i)(\(.*?(?:official|video|audio|lyric|lyrics|visualizer|live|hd|4k|remaster).*?\)|\[.*?(?:official|video|audio|lyric|lyrics|hd|4k).*?]|\b(?:official\s+video|official\s+audio|lyric\s+video|lyrics|audio|hd|hq|320kbps|128kbps|mp3|m4a|wav|flac)\b)"""
    )

    fun cleanSearchQuery(rawTitle: String, rawArtist: String = ""): String {
        val cleanedTitle = rawTitle
            .replace("_", " ")
            .replace(NOISE_TOKENS_REGEX, " ")
            .replace(Regex("""^\d{1,2}\s*[-.]\s*"""), "")
            .replace(Regex("""\s+"""), " ")
            .trim()
        val cleanedArtist = rawArtist
            .takeIf { it.isNotBlank() && !it.equals("AI Jam", ignoreCase = true) && !it.equals("Unknown", ignoreCase = true) }
            ?.replace("_", " ")
            ?.trim()
            .orEmpty()

        return if (cleanedArtist.isNotEmpty() && !cleanedTitle.contains(cleanedArtist, ignoreCase = true)) {
            "$cleanedArtist $cleanedTitle".trim()
        } else {
            cleanedTitle
        }
    }

    /**
     * Queries LRCLIB for synced LRC lyrics matching the title/artist/duration.
     * Safe to call on a background dispatcher (Dispatchers.IO / Dispatchers.Default).
     */
    fun fetchLyricsOnline(
        title: String,
        artist: String = "",
        durationSec: Double = 0.0
    ): OnlineLyricsResult? {
        if (GreatExpectationsPreset.matchesSong(title, artist)) {
            val preset = GreatExpectationsPreset.buildSongMap(
                targetDurationSec = if (durationSec > 30.0) durationSec else 173.0
            )
            return OnlineLyricsResult(
                trackName = "Great Expectations",
                artistName = "Sienna Spiro",
                rawLrcOrPlain = LyricsParser.toLrcString(preset.lyrics),
                isSyncedLrc = true
            )
        }

        val query = cleanSearchQuery(title, artist)
        if (query.length < 2) return null

        return runCatching {
            val encoded = URLEncoder.encode(query, "UTF-8")
            val url = URL("https://lrclib.net/api/search?q=$encoded")
            val conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 5500
                readTimeout = 5500
                setRequestProperty("User-Agent", "AIJamCompanion/3.0 (Android Studio Native)")
                setRequestProperty("Accept", "application/json")
            }

            val code = conn.responseCode
            if (code !in 200..299) {
                DiagnosticLogger.log("LRCLIB", "HTTP $code for query='$query'")
                return null
            }

            val body = conn.inputStream.bufferedReader().use { it.readText() }
            val arr = JSONArray(body)
            if (arr.length() == 0) {
                DiagnosticLogger.log("LRCLIB", "No results found for query='$query'")
                return null
            }

            var bestSynced: OnlineLyricsResult? = null
            var bestSyncedDurDiff = Double.MAX_VALUE
            var bestPlain: OnlineLyricsResult? = null

            for (i in 0 until arr.length()) {
                val item = arr.getJSONObject(i)
                val track = item.optString("trackName", title)
                val art = item.optString("artistName", artist)
                val itemDur = item.optDouble("duration", 0.0)
                val synced = item.optString("syncedLyrics", "").trim()
                val plain = item.optString("plainLyrics", "").trim()
                val durDiff = if (durationSec > 10.0 && itemDur > 10.0) abs(itemDur - durationSec) else i * 2.0

                if (synced.isNotEmpty() && durDiff < bestSyncedDurDiff) {
                    bestSyncedDurDiff = durDiff
                    bestSynced = OnlineLyricsResult(
                        trackName = track,
                        artistName = art,
                        rawLrcOrPlain = synced,
                        isSyncedLrc = true
                    )
                } else if (bestPlain == null && plain.isNotEmpty()) {
                    bestPlain = OnlineLyricsResult(
                        trackName = track,
                        artistName = art,
                        rawLrcOrPlain = plain,
                        isSyncedLrc = false
                    )
                }
            }

            val chosen = bestSynced ?: bestPlain
            if (chosen != null) {
                DiagnosticLogger.log(
                    "LRCLIB",
                    "Matched '${chosen.trackName}' by '${chosen.artistName}' (synced=${chosen.isSyncedLrc})"
                )
            }
            chosen
        }.onFailure { err ->
            DiagnosticLogger.log("LRCLIB", "Online lyrics lookup skipped: ${err.javaClass.simpleName}: ${err.message}")
        }.getOrNull()
    }

    /**
     * Uses synced lyrics timestamps and repeating chorus lines to build musically accurate
     * Hebrew+English song sections (פתיח, בית 1, פזמון, בית 2, גשר, סיום) aligned to bars.
     */
    fun refineSectionsWithLyrics(songMap: SongMap, lyrics: List<LyricLine>): SongMap {
        if (lyrics.size < 4 || songMap.states.size < 6) {
            return songMap.copy(lyrics = lyrics)
        }

        val totalBars = songMap.states.size
        val duration = songMap.metadata.duration
        val firstLyricBar = lyrics.first().bar.coerceIn(1, totalBars)
        val lastLyricBar = lyrics.last().bar.coerceIn(firstLyricBar, totalBars)

        // Detect repeating lines (Chorus candidates) by normalized text prefix
        val normalizedCounts = mutableMapOf<String, Int>()
        for (line in lyrics) {
            val key = line.text.lowercase().replace(Regex("[^a-z0-9א-ת ]"), "").take(22).trim()
            if (key.length >= 6) {
                normalizedCounts[key] = (normalizedCounts[key] ?: 0) + 1
            }
        }

        // Partition lyrics into phrases separated by instrumental/breath gaps or 8-bar spans
        val phraseGroups = mutableListOf<MutableList<LyricLine>>()
        for (line in lyrics) {
            val currentGroup = phraseGroups.lastOrNull()
            if (currentGroup == null) {
                phraseGroups.add(mutableListOf(line))
            } else {
                val prev = currentGroup.last()
                val gapSec = line.startTime - prev.endTime
                val groupBarSpan = line.bar - currentGroup.first().bar
                if (gapSec >= 3.8 || groupBarSpan >= 8) {
                    phraseGroups.add(mutableListOf(line))
                } else {
                    currentGroup.add(line)
                }
            }
        }

        val newSections = mutableListOf<SongSection>()
        var currentStartBar = 1

        // 1. Leading Intro if first vocal enters at bar 3 or later
        if (firstLyricBar >= 3) {
            val introEndBar = firstLyricBar - 1
            val sTime = songMap.states.first().startTime
            val eTime = songMap.states.firstOrNull { it.bar == introEndBar }?.endTime ?: lyrics.first().startTime
            newSections.add(
                SongSection(
                    name = "intro",
                    label = "פתיח • Intro",
                    startTime = round3(sTime),
                    endTime = round3(eTime),
                    startBar = 1,
                    endBar = introEndBar
                )
            )
            currentStartBar = firstLyricBar
        }

        var verseCount = 0
        var chorusCount = 0

        phraseGroups.forEachIndexed { idx, group ->
            val groupStartBar = maxOf(currentStartBar, group.first().bar)
            val nextGroupStartBar = phraseGroups.getOrNull(idx + 1)?.first()?.bar
            val groupEndBar = if (nextGroupStartBar != null && nextGroupStartBar > groupStartBar) {
                nextGroupStartBar - 1
            } else if (idx == phraseGroups.lastIndex && totalBars - lastLyricBar >= 3) {
                lastLyricBar
            } else {
                totalBars
            }

            if (groupEndBar >= groupStartBar) {
                val hasRepeatingLine = group.any { line ->
                    val key = line.text.lowercase().replace(Regex("[^a-z0-9א-ת ]"), "").take(22).trim()
                    (normalizedCounts[key] ?: 0) >= 2
                }
                val isBridgeCandidate = idx == phraseGroups.size - 2 && phraseGroups.size >= 5 && !hasRepeatingLine

                val (secName, secLabel) = when {
                    isBridgeCandidate -> "bridge" to "גשר • Bridge"
                    hasRepeatingLine || (idx % 2 == 1 && verseCount >= 1) -> {
                        chorusCount++
                        "chorus_$chorusCount" to "פזמון $chorusCount • Chorus"
                    }
                    else -> {
                        verseCount++
                        "verse_$verseCount" to "בית $verseCount • Verse $verseCount"
                    }
                }

                val sTime = songMap.states.firstOrNull { it.bar == groupStartBar }?.startTime ?: group.first().startTime
                val eTime = songMap.states.firstOrNull { it.bar == groupEndBar }?.endTime ?: group.last().endTime
                newSections.add(
                    SongSection(
                        name = secName,
                        label = secLabel,
                        startTime = round3(sTime),
                        endTime = round3(eTime),
                        startBar = groupStartBar,
                        endBar = groupEndBar
                    )
                )
                currentStartBar = groupEndBar + 1
            }
        }

        // Trailing Outro if bars remain
        if (currentStartBar <= totalBars) {
            val sTime = songMap.states.firstOrNull { it.bar == currentStartBar }?.startTime ?: 0.0
            newSections.add(
                SongSection(
                    name = "outro",
                    label = "סיום • Outro",
                    startTime = round3(sTime),
                    endTime = round3(duration),
                    startBar = currentStartBar,
                    endBar = totalBars
                )
            )
        }

        if (newSections.isEmpty()) {
            return songMap.copy(lyrics = lyrics)
        }

        val updatedStates = songMap.states.map { st ->
            val sec = newSections.firstOrNull { st.bar in it.startBar..it.endBar }
            if (sec != null) st.copy(section = sec.name) else st
        }

        return songMap.copy(
            sections = newSections,
            states = updatedStates,
            lyrics = lyrics
        )
    }

    private fun round3(v: Double): Double = (v * 1000.0).roundToInt() / 1000.0
}

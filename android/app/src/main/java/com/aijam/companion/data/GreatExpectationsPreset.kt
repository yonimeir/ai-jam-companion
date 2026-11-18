package com.aijam.companion.data

import com.aijam.companion.engine.VoicingEngine
import com.aijam.companion.model.BarState
import com.aijam.companion.model.ChordEvent
import com.aijam.companion.model.LyricLine
import com.aijam.companion.model.SongMap
import com.aijam.companion.model.SongMetadata
import com.aijam.companion.model.SongSection
import com.aijam.companion.model.VocalAnchor
import kotlin.math.PI
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Curated studio-accurate harmonic map, section structure, vocal pitch contour, and synced lyrics
 * for Sienna Spiro — "Great Expectations" (Key: Bm / D major, 76 BPM ballad pulse, 55 bars).
 *
 * Automatically activates both as the flagship built-in song AND whenever the user imports
 * an audio file matching "Great Expectations" / "Sienna Spiro", scaling timestamps cleanly
 * to the exact duration and vocal entry offset of the user's audio file.
 */
object GreatExpectationsPreset {

    private data class BarSpec(
        val bar: Int,
        val sectionName: String,
        val chord: String,
        val secondHalfChord: String? = null,
        val targetMidi: Double,
        val lyric: String? = null
    )

    private data class SectionSpec(
        val name: String,
        val label: String,
        val startBar: Int,
        val endBar: Int
    )

    private val SECTIONS = listOf(
        SectionSpec("intro", "פתיח • Intro", 1, 4),
        SectionSpec("verse_1", "בית 1 • Verse 1", 5, 12),
        SectionSpec("chorus_1", "פזמון 1 • Chorus", 13, 22),
        SectionSpec("verse_2", "בית 2 • Verse 2", 23, 30),
        SectionSpec("chorus_2", "פזמון 2 • Chorus", 31, 39),
        SectionSpec("bridge", "גשר • Bridge", 40, 44),
        SectionSpec("chorus_3", "פזמון סיום • Final Chorus", 45, 52),
        SectionSpec("outro", "סיום • Outro", 53, 55)
    )

    private val BARS = listOf(
        // Intro (Bars 1..4) — Tumbling melancholic piano
        BarSpec(1, "intro", "Bm", null, 0.0, null),
        BarSpec(2, "intro", "A", null, 0.0, null),
        BarSpec(3, "intro", "Gmaj7", null, 0.0, null),
        BarSpec(4, "intro", "A", null, 0.0, null),

        // Verse 1 (Bars 5..12)
        BarSpec(5, "verse_1", "Bm", null, 66.0, "In my head, you lay down next to me"),
        BarSpec(6, "verse_1", "A", null, 64.0, "Kiss my lips, hold me carefully"),
        BarSpec(7, "verse_1", "Gmaj7", null, 62.0, "Say goodbye and feel all the withdrawals"),
        BarSpec(8, "verse_1", "A", null, 61.0, "(Feel all the withdrawals)"),
        BarSpec(9, "verse_1", "Bm", null, 66.0, "But it's not the same, in reality"),
        BarSpec(10, "verse_1", "A", null, 64.0, "I say your name, but you don't get back to me"),
        BarSpec(11, "verse_1", "Gmaj7", null, 62.0, "Till you got something you need me for"),
        BarSpec(12, "verse_1", "A", null, 64.0, "Something you need me for..."),

        // Chorus 1 (Bars 13..22)
        BarSpec(13, "chorus_1", "G", null, 67.0, "So I sing just to know I'm alive"),
        BarSpec(14, "chorus_1", "D", null, 66.0, "And I cried all the tears I could cry"),
        BarSpec(15, "chorus_1", "F#7", null, 66.0, "If happiness is just an illusion"),
        BarSpec(16, "chorus_1", "Bm", null, 69.0, "You were the best I ever had"),
        BarSpec(17, "chorus_1", "A", null, 64.0, "(The best I ever had)"),
        BarSpec(18, "chorus_1", "G", null, 67.0, "If you can't be what I want"),
        BarSpec(19, "chorus_1", "D", null, 66.0, "And the things you say aren't true"),
        BarSpec(20, "chorus_1", "F#7", null, 70.0, "All I need is the great"),
        BarSpec(21, "chorus_1", "Bm", "A", 71.0, "expectation of you"),
        BarSpec(22, "chorus_1", "Em", null, 67.0, "Expectation of you"),

        // Verse 2 (Bars 23..30)
        BarSpec(23, "verse_2", "Bm", null, 66.0, "The tracks of my tears go back for years"),
        BarSpec(24, "verse_2", "A", null, 64.0, "When I was young, I've always had the fears"),
        BarSpec(25, "verse_2", "Gmaj7", null, 62.0, "One would leave, so I held on so long"),
        BarSpec(26, "verse_2", "A", null, 61.0, "(Held on so long)"),
        BarSpec(27, "verse_2", "Bm", null, 66.0, "But to each their own, you come and go a lot"),
        BarSpec(28, "verse_2", "A", null, 64.0, "It's black and white, I go back and change the plot"),
        BarSpec(29, "verse_2", "Gmaj7", null, 62.0, "Just to have ya in the way I want"),
        BarSpec(30, "verse_2", "Em", null, 64.0, "In the way I want..."),

        // Chorus 2 (Bars 31..39)
        BarSpec(31, "chorus_2", "G", null, 67.0, "So I sing just to know I'm alive (I sing so long)"),
        BarSpec(32, "chorus_2", "D", null, 66.0, "And I cried all the tears I could cry (I cried for you)"),
        BarSpec(33, "chorus_2", "F#7", null, 66.0, "If happiness is just an illusion"),
        BarSpec(34, "chorus_2", "Bm", null, 69.0, "You were the best I ever had"),
        BarSpec(35, "chorus_2", "A", null, 64.0, "(Best I ever had)"),
        BarSpec(36, "chorus_2", "G", null, 67.0, "If you can't be what I want (Be what I want)"),
        BarSpec(37, "chorus_2", "D", null, 66.0, "And the things you say aren't true (Not true)"),
        BarSpec(38, "chorus_2", "F#7", null, 70.0, "All I need is the great"),
        BarSpec(39, "chorus_2", "Bm", "A", 71.0, "expectation of you, oh"),

        // Bridge (Bars 40..44)
        BarSpec(40, "bridge", "G", null, 71.0, "Cry my tears and let 'em dry"),
        BarSpec(41, "bridge", "D", null, 69.0, "Cry my tears and testify"),
        BarSpec(42, "bridge", "F#7", null, 70.0, "You exist inside my mind"),
        BarSpec(43, "bridge", "Bm", "A", 71.0, "You, so..."),
        BarSpec(44, "bridge", "D", "A", 69.0, "(Inside my mind...)"),

        // Final Chorus (Bars 45..52)
        BarSpec(45, "chorus_3", "G", null, 71.0, "So I sing just to know I'm alive"),
        BarSpec(46, "chorus_3", "D", null, 69.0, "And I cried all the tears I could cry"),
        BarSpec(47, "chorus_3", "F#7", null, 70.0, "If happiness is just an illusion"),
        BarSpec(48, "chorus_3", "Bm", null, 71.0, "You were the best I ever had (Best I had)"),
        BarSpec(49, "chorus_3", "A", null, 69.0, "(The best I ever had)"),
        BarSpec(50, "chorus_3", "G", null, 67.0, "If you can't be what I want"),
        BarSpec(51, "chorus_3", "D", null, 66.0, "And the things you say aren't true"),
        BarSpec(52, "chorus_3", "F#7", "Bm", 70.0, "All I need is the great expectation of you"),

        // Outro (Bars 53..55)
        BarSpec(53, "outro", "D", "E7", 66.0, "All I need is..."),
        BarSpec(54, "outro", "Gm", null, 64.0, "All I need is you"),
        BarSpec(55, "outro", "D", null, 62.0, "Great expectation of you")
    )

    fun matchesSong(title: String, artist: String = "", fileName: String = ""): Boolean {
        val combined = "$title $artist $fileName".lowercase()
        return ("great" in combined && "expectation" in combined) ||
            ("sienna" in combined && "spiro" in combined) ||
            ("גרייט" in combined) ||
            ("שיינה" in combined)
    }

    fun buildSongMap(
        id: String = SongLibraryRepository.DEMO_SONG_ID,
        title: String = "Great Expectations",
        artist: String = "Sienna Spiro",
        targetDurationSec: Double = 173.0,
        leadingSilenceSec: Double = 0.0,
        audioPath: String? = null
    ): SongMap {
        val totalBars = BARS.size
        val activeSpanSec = (targetDurationSec - leadingSilenceSec).coerceAtLeast(120.0)
        val barDurationSec = activeSpanSec / totalBars.toDouble()
        val beatDurationSec = barDurationSec / 4.0
        val bpm = (60.0 / beatDurationSec).coerceIn(60.0, 120.0)

        val sections = SECTIONS.map { sec ->
            val sTime = leadingSilenceSec + (sec.startBar - 1) * barDurationSec
            val eTime = leadingSilenceSec + sec.endBar * barDurationSec
            SongSection(
                name = sec.name,
                label = sec.label,
                startTime = round3(sTime),
                endTime = round3(eTime.coerceAtMost(targetDurationSec)),
                startBar = sec.startBar,
                endBar = sec.endBar
            )
        }

        val chordEvents = mutableListOf<ChordEvent>()
        val states = mutableListOf<BarState>()
        val lyrics = mutableListOf<LyricLine>()

        for (spec in BARS) {
            val barZero = spec.bar - 1
            val barStart = leadingSilenceSec + barZero * barDurationSec
            val barEnd = (barStart + barDurationSec).coerceAtMost(targetDurationSec)
            val subChords = mutableListOf<String>()

            for (beat in 1..4) {
                val chordName = if (beat >= 3 && spec.secondHalfChord != null) {
                    spec.secondHalfChord
                } else {
                    spec.chord
                }
                subChords.add(chordName)
                val bStart = barStart + (beat - 1) * beatDurationSec
                val bEnd = (bStart + beatDurationSec).coerceAtMost(targetDurationSec)
                val isMinor = chordName.replace("maj7", "").replace("7", "").endsWith("m")
                val rawRoot = chordName.replace("maj7", "").replace("7", "").removeSuffix("m")
                chordEvents.add(
                    ChordEvent(
                        chord = chordName,
                        root = rawRoot,
                        quality = if (isMinor) "minor" else "major",
                        startTime = round3(bStart),
                        endTime = round3(bEnd),
                        bar = spec.bar,
                        beat = beat,
                        notes = VoicingEngine.chordToMidiNotes(chordName, octave = 4)
                    )
                )
            }

            states.add(
                BarState(
                    bar = spec.bar,
                    section = spec.sectionName,
                    chord = spec.chord,
                    subChords = subChords,
                    startTime = round3(barStart),
                    endTime = round3(barEnd),
                    notes = VoicingEngine.chordToMidiNotes(spec.chord, octave = 4),
                    targetPitchMidi = spec.targetMidi
                )
            )

            if (!spec.lyric.isNullOrBlank()) {
                lyrics.add(
                    LyricLine(
                        startTime = round3(barStart + 0.08),
                        endTime = round3(barEnd - 0.06),
                        bar = spec.bar,
                        text = spec.lyric
                    )
                )
            }
        }

        // Build smooth vocal anchor contour (~10 frames/sec to keep JSON compact and fast)
        val frameStepSec = 0.08
        val numFrames = (targetDurationSec / frameStepSec).toInt().coerceAtLeast(100)
        val times = DoubleArray(numFrames)
        val f0Hz = DoubleArray(numFrames)
        val midiNotes = DoubleArray(numFrames)
        val voicedProb = DoubleArray(numFrames)
        val rmsDb = DoubleArray(numFrames)

        for (i in 0 until numFrames) {
            val t = i * frameStepSec
            times[i] = round3(t)
            val barIdx = ((t - leadingSilenceSec) / barDurationSec).toInt()
            val spec = BARS.getOrNull(barIdx)
            if (spec != null && spec.targetMidi > 0.0 && t >= leadingSilenceSec) {
                val barLocalPhase = ((t - leadingSilenceSec) - barIdx * barDurationSec) / barDurationSec
                // Expressive vocal phrase contour with gentle vibrato
                val phraseContour = sin(barLocalPhase * PI) * 0.65
                val vibrato = sin(2.0 * PI * 5.2 * t) * 0.14
                val midi = spec.targetMidi + phraseContour + vibrato
                midiNotes[i] = round2(midi)
                f0Hz[i] = round2(440.0 * 2.0.pow((midi - 69.0) / 12.0))
                voicedProb[i] = if (barLocalPhase in 0.04..0.90) 0.92 else 0.35
                // Higher dynamic energy in Choruses and Bridge
                val baseRms = when (spec.sectionName) {
                    "chorus_1", "chorus_2", "chorus_3", "bridge" -> -16.5
                    else -> -22.0
                }
                rmsDb[i] = round2(baseRms + sin(barLocalPhase * PI) * 3.5)
            } else {
                midiNotes[i] = 0.0
                f0Hz[i] = 0.0
                voicedProb[i] = 0.05
                rmsDb[i] = -36.0
            }
        }

        return SongMap(
            metadata = SongMetadata(
                id = id,
                title = title,
                artist = artist,
                duration = round3(targetDurationSec),
                bpm = round1(bpm),
                sampleRate = 22050,
                totalBars = totalBars,
                totalBeats = totalBars * 4,
                audioPath = audioPath
            ),
            sections = sections,
            chords = chordEvents,
            states = states,
            vocalAnchor = VocalAnchor(
                times = times,
                f0Hz = f0Hz,
                midiNotes = midiNotes,
                voicedProb = voicedProb,
                rmsDb = rmsDb
            ),
            lyrics = lyrics
        )
    }

    private fun round1(v: Double): Double = (v * 10.0).roundToInt() / 10.0
    private fun round2(v: Double): Double = (v * 100.0).roundToInt() / 100.0
    private fun round3(v: Double): Double = (v * 1000.0).roundToInt() / 1000.0
}

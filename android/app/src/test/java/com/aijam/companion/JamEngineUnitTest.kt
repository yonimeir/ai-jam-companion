package com.aijam.companion

import com.aijam.companion.audio.OfflineSongAnalyzer
import com.aijam.companion.data.LyricsParser
import com.aijam.companion.engine.Fft
import com.aijam.companion.engine.HarmonicStateMachine
import com.aijam.companion.engine.MidiExporter
import com.aijam.companion.engine.OnlineScoreFollower
import com.aijam.companion.engine.RealtimeJamTracker
import com.aijam.companion.engine.RealtimePitchDetector
import com.aijam.companion.engine.VelocityMapper
import com.aijam.companion.engine.VoicingEngine
import com.aijam.companion.model.AccompanimentStyle
import com.aijam.companion.model.BarState
import com.aijam.companion.model.ChordEvent
import com.aijam.companion.model.EngineMode
import com.aijam.companion.model.LyricLine
import com.aijam.companion.model.SongMap
import com.aijam.companion.model.SongMapJson
import com.aijam.companion.model.SongMetadata
import com.aijam.companion.model.SongSection
import com.aijam.companion.model.VocalAnchor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

class JamEngineUnitTest {

    private fun buildSyntheticSongMap(): SongMap {
        val sr = 22050
        val hop = 512
        val totalFrames = 400
        val times = DoubleArray(totalFrames) { it * hop.toDouble() / sr }
        val f0 = DoubleArray(totalFrames) { i ->
            when {
                i < 40 -> 0.0 // Intro silence
                i < 160 -> 261.63 // C4
                i < 280 -> 329.63 // E4
                else -> 392.00 // G4
            }
        }
        val midi = DoubleArray(totalFrames) { i ->
            when {
                i < 40 -> 0.0
                i < 160 -> 60.0
                i < 280 -> 64.0
                else -> 67.0
            }
        }
        val vp = DoubleArray(totalFrames) { i -> if (i < 40) 0.0 else 0.92 }
        val rms = DoubleArray(totalFrames) { i -> if (i < 40) -60.0 else -18.0 }

        val duration = times.last() + 0.023
        val chords = listOf(
            ChordEvent("C", "C", "major", 0.0, 2.4, 1, 1, listOf(60, 64, 67)),
            ChordEvent("Am", "A", "minor", 2.4, 4.8, 2, 1, listOf(69, 72, 76)),
            ChordEvent("F", "F", "major", 4.8, 7.2, 3, 1, listOf(65, 69, 72)),
            ChordEvent("G", "G", "major", 7.2, duration, 4, 1, listOf(67, 71, 74))
        )
        val states = listOf(
            BarState(1, "intro", "C", listOf("C"), 0.0, 2.4, listOf(60, 64, 67), 60.0),
            BarState(2, "verse_1", "Am", listOf("Am"), 2.4, 4.8, listOf(69, 72, 76), 60.0),
            BarState(3, "verse_1", "F", listOf("F"), 4.8, 7.2, listOf(65, 69, 72), 64.0),
            BarState(4, "chorus_1", "G", listOf("G"), 7.2, duration, listOf(67, 71, 74), 67.0)
        )
        val sections = listOf(
            SongSection("intro", "Intro", 0.0, 2.4, 1, 1),
            SongSection("verse_1", "Verse 1", 2.4, 7.2, 2, 3),
            SongSection("chorus_1", "Chorus 1", 7.2, duration, 4, 4)
        )
        return SongMap(
            metadata = SongMetadata("test_song", "Test Ballad", "Unit Test", duration, 100.0, sr, 4, 16),
            sections = sections,
            chords = chords,
            states = states,
            vocalAnchor = VocalAnchor(times, f0, midi, vp, rms),
            lyrics = listOf(LyricLine(2.4, 4.8, 2, "First lyric line"))
        )
    }

    @Test
    fun fftRoundTripRestoresSignal() {
        val n = 64
        val orig = DoubleArray(n) { i -> sin(2.0 * PI * 3 * i / n) + 0.5 * sin(2.0 * PI * 9 * i / n) }
        val real = orig.copyOf()
        val imag = DoubleArray(n)
        Fft.transform(real, imag, inverse = false)
        Fft.transform(real, imag, inverse = true)

        for (i in 0 until n) {
            assertEquals(orig[i], real[i], 1e-9)
            assertEquals(0.0, imag[i], 1e-9)
        }
    }

    @Test
    fun pitchDetectorDetects440HzAndSilence() {
        val sr = 22050
        val detector = RealtimePitchDetector(sampleRate = sr)
        val sine440 = FloatArray(2048) { i ->
            (0.4 * sin(2.0 * PI * 440.0 * i / sr)).toFloat()
        }
        val voiced = detector.processChunk(sine440)
        assertTrue(voiced.isVoiced)
        assertEquals(440.0, voiced.f0Hz, 2.5)
        assertEquals(69.0, voiced.midiNote, 0.15)

        val silence = FloatArray(2048) { 0f }
        val unvoiced = detector.processChunk(silence)
        assertFalse(unvoiced.isVoiced)
    }

    @Test
    fun scoreFollowerLocksAndJumpsCleanly() {
        val map = buildSyntheticSongMap()
        val follower = OnlineScoreFollower(map)

        val (idx, _, conf) = follower.step(liveMidi = 60.0, liveVoiced = true)
        assertTrue("Expected vocal lock >= 35, got $idx", idx >= 35)
        assertTrue(conf in 0.0..1.0)

        follower.jumpToTime(6.0)
        assertTrue(follower.getProgressPercent() > 50f)
    }

    @Test
    fun harmonicStateMachineHandlesVampAndTransitions() {
        val map = buildSyntheticSongMap()
        val hsm = HarmonicStateMachine(map, confidenceThreshold = 0.55)

        // Manual vamp hold
        hsm.forceVampHold = true
        val held = hsm.update(refTime = 3.0, confidence = 0.9, isVoiced = true, dt = 0.023)
        assertEquals(EngineMode.VAMPING, held.engineMode)

        // Release vamp hold and confirm transition to Am (refTime = 3.0)
        hsm.forceVampHold = false
        hsm.update(refTime = 3.0, confidence = 0.9, isVoiced = true, dt = 0.023)
        val confirmed = hsm.update(refTime = 3.0, confidence = 0.9, isVoiced = true, dt = 0.023)
        assertEquals(EngineMode.PLAYING, confirmed.engineMode)
        assertEquals("Am", confirmed.chord)
    }

    @Test
    fun voicingEngineAndVelocityMapperWorkCorrectly() {
        val cVoicing = VoicingEngine.generatePianoVoicing("C", AccompanimentStyle.BALLAD_ARPEGGIO, 1, null)
        assertTrue(cVoicing.bassNote != null)
        assertTrue(cVoicing.harmonyNotes.size >= 3)

        val amVoicing = VoicingEngine.generatePianoVoicing(
            "Am",
            AccompanimentStyle.RHYTHMIC_COMP,
            1,
            cVoicing.harmonyNotes
        )
        val avgShift = abs(amVoicing.harmonyNotes.average() - cVoicing.harmonyNotes.average())
        assertTrue("Voice leading should keep chord center close, got shift=$avgShift", avgShift < 7.0)

        val drums = VoicingEngine.generateDrumPattern(1, AccompanimentStyle.RHYTHMIC_COMP, 90, false)
        assertTrue(drums.any { it.note == VoicingEngine.DRUM_KICK })

        val mapper = VelocityMapper()
        val quiet = mapper.mapRmsToVelocity(-45.0)
        val loud = mapper.mapRmsToVelocity(-12.0)
        assertTrue(loud > quiet)
    }

    @Test
    fun realtimeTrackerAndMidiExportProduceValidSmf() {
        val map = buildSyntheticSongMap()
        val tracker = RealtimeJamTracker(map, includeDrums = true)
        val sr = 22050
        val chunk = FloatArray(512) { i -> (0.35 * sin(2.0 * PI * 261.63 * i / sr)).toFloat() }

        repeat(8) {
            tracker.processAudioBuffer(chunk)
        }

        assertTrue(tracker.scheduledTimeline.isNotEmpty())
        val midiBytes = MidiExporter.buildMidiBytes(tracker.scheduledTimeline, tracker.bpm)
        assertTrue(midiBytes.size > 32)
        // Check 'MThd' header and 'MTrk' chunk
        assertEquals('M'.code.toByte(), midiBytes[0])
        assertEquals('T'.code.toByte(), midiBytes[1])
        assertEquals('h'.code.toByte(), midiBytes[2])
        assertEquals('d'.code.toByte(), midiBytes[3])
        val text = String(midiBytes, Charsets.ISO_8859_1)
        assertTrue(text.contains("MTrk"))
    }

    @Test
    fun offlineAnalyzerProcessesSyntheticAudio() {
        val sr = 22050
        val durSec = 8.0
        val totalSamples = (sr * durSec).toInt()
        val samples = FloatArray(totalSamples)

        // 120 BPM pulse + C major -> A minor harmonic progression + melody
        val beatSec = 0.5
        for (i in 0 until totalSamples) {
            val t = i.toDouble() / sr
            val chordFreqs = if (t < 4.0) listOf(130.81, 261.63, 329.63, 392.00) else listOf(110.0, 220.0, 261.63, 329.63)
            val beatPhase = (t % beatSec) / beatSec
            val pulseEnv = if (beatPhase < 0.25) 1.0 else 0.35
            var sum = 0.0
            for (f in chordFreqs) sum += sin(2.0 * PI * f * t)
            // Add vocal melody in middle register
            sum += 0.9 * sin(2.0 * PI * 440.0 * t)
            samples[i] = (0.14 * pulseEnv * sum).toFloat()
        }

        val map = OfflineSongAnalyzer.analyze(
            samples = samples,
            sampleRate = sr,
            id = "synth_test",
            title = "Synthetic C-Am",
            artist = "Test"
        )
        assertTrue(map.metadata.bpm in 58.0..175.0)
        assertTrue(map.chords.isNotEmpty())
        assertTrue(map.states.isNotEmpty())
        assertTrue(map.sections.isNotEmpty())
        assertTrue(map.vocalAnchor.times.size == map.vocalAnchor.midiNotes.size)
    }

    @Test
    fun lyricsParserAndSongMapJsonRoundTrip() {
        val map = buildSyntheticSongMap()
        val lrc = """
            [ti:Test]
            [00:00.50] שורה ראשונה בפתיח
            [00:02.60] שורה שנייה בבית
            [00:07.30] שורה שלישית בפזמון
        """.trimIndent()
        val parsedLrc = LyricsParser.parseAndAlign(lrc, map)
        assertEquals(3, parsedLrc.size)
        assertEquals(1, parsedLrc[0].bar)
        assertEquals(2, parsedLrc[1].bar)
        assertEquals(4, parsedLrc[2].bar)

        val plain = "שורה א\nשורה ב\nשורה ג"
        val autoSynced = LyricsParser.parseAndAlign(plain, map)
        assertEquals(3, autoSynced.size)
        assertTrue(autoSynced[0].startTime < autoSynced[1].startTime)

        val jsonStr = SongMapJson.toJson(map.copy(lyrics = parsedLrc))
        val restored = SongMapJson.fromJson(jsonStr, fallbackId = "fallback")
        assertEquals(map.metadata.title, restored.metadata.title)
        assertEquals(map.states.size, restored.states.size)
        assertEquals(3, restored.lyrics.size)
        assertEquals("שורה שנייה בבית", restored.lyrics[1].text)
    }

    @Test
    fun bundledDemoAssetParsesCleanly() {
        val assetFile = java.io.File("src/main/assets/demo_ballad_map.json")
        if (!assetFile.exists()) return
        val demo = SongMapJson.fromJson(assetFile.readText(), fallbackId = "demo_ballad")
        assertEquals("Pop Ballad Standard", demo.metadata.title)
        assertEquals(22, demo.states.size)
        assertTrue(demo.vocalAnchor.times.size > 1000)
        assertTrue(demo.lyrics.isNotEmpty())
    }
}

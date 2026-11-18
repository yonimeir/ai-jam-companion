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
    fun scoreFollowerMonotonicAndAdvancesThroughInstrumentalIntro() {
        val map = buildSyntheticSongMap()
        val follower = OnlineScoreFollower(map)

        // 1. During instrumental intro (frames 0..39), follower should advance smoothly even with silence
        var prevTime = 0.0
        repeat(42) {
            val (_, refTime, _) = follower.step(liveMidi = 0.0, liveVoiced = false)
            assertTrue("Score follower must never jump backward ($refTime < $prevTime)", refTime >= prevTime)
            prevTime = refTime
        }
        assertTrue("Expected follower to reach end of instrumental intro (~0.9s), got $prevTime", prevTime >= 0.85)

        // 2. Now sing through the verse with occasional pitch jitter and breath pauses — must stay strictly monotonic
        for (step in 0 until 150) {
            val isBreath = step in 55..65
            val sungMidi = when {
                isBreath -> 0.0
                step < 90 -> 60.0 + (step % 3 - 1) * 0.35
                else -> 64.0 + (step % 3 - 1) * 0.35
            }
            val (_, refTime, conf) = follower.step(liveMidi = sungMidi, liveVoiced = !isBreath)
            assertTrue("Monotonic violation at step $step: $refTime < $prevTime", refTime >= prevTime)
            assertTrue("Max forward step per hop exceeded: ${refTime - prevTime}", (refTime - prevTime) < 0.08)
            assertTrue(conf in 0.0..1.0)
            prevTime = refTime
        }

        follower.jumpToTime(6.0)
        assertTrue(follower.getProgressPercent() > 50f)
    }

    @Test
    fun harmonicStateMachineHandlesVampAndSameHarmonySmoothly() {
        val map = buildSyntheticSongMap()
        val hsm = HarmonicStateMachine(map, confidenceThreshold = 0.55)

        // Within Bar 1 (C major), moving across beats must stay in PLAYING mode (never CONFIDENCE_WAIT)
        val beat2 = hsm.update(refTime = 0.7, confidence = 0.85, isVoiced = true, dt = 0.023, refExpectsVocal = false)
        assertEquals(EngineMode.PLAYING, beat2.engineMode)
        assertEquals("C", beat2.chord)

        // Manual vamp hold
        hsm.forceVampHold = true
        val held = hsm.update(refTime = 3.0, confidence = 0.9, isVoiced = true, dt = 0.023, refExpectsVocal = true)
        assertEquals(EngineMode.VAMPING, held.engineMode)

        // Release vamp hold and confirm transition to Am (refTime = 3.0)
        hsm.forceVampHold = false
        hsm.update(refTime = 3.0, confidence = 0.9, isVoiced = true, dt = 0.023, refExpectsVocal = true)
        val confirmed = hsm.update(refTime = 3.0, confidence = 0.9, isVoiced = true, dt = 0.023, refExpectsVocal = true)
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
    fun realtimeTrackerSteadyClockAndDiagnosticLoggerWorkCleanly() {
        val map = buildSyntheticSongMap()
        com.aijam.companion.util.DiagnosticLogger.clear()
        com.aijam.companion.util.DiagnosticLogger.onSessionStart("DEMO", map.metadata.title)

        val tracker = RealtimeJamTracker(map, includeDrums = true)
        var prevTime = 0.0
        var beatsTriggered = 0
        repeat(120) {
            val res = tracker.stepSteadyClock(dtSec = 512.0 / 22050.0, speedMultiplier = 1.0)
            assertTrue("Steady clock must be monotonic", res.refTimeSec >= prevTime)
            if (res.triggeredNotes.isNotEmpty()) beatsTriggered++
            prevTime = res.refTimeSec
        }

        assertTrue("Expected at least 4 steady beats triggered, got $beatsTriggered", beatsTriggered >= 4)
        assertTrue(tracker.scheduledTimeline.isNotEmpty())
        val midiBytes = MidiExporter.buildMidiBytes(tracker.scheduledTimeline, tracker.bpm)
        assertTrue(midiBytes.size > 32)
        assertEquals('M'.code.toByte(), midiBytes[0])
        assertEquals('T'.code.toByte(), midiBytes[1])
        assertEquals('h'.code.toByte(), midiBytes[2])
        assertEquals('d'.code.toByte(), midiBytes[3])

        val report = com.aijam.companion.util.DiagnosticLogger.buildFullReport(
            songMap = map,
            liveState = com.aijam.companion.model.LiveJamState(
                isRunning = true,
                currentChord = tracker.lastStatus?.chord ?: "C",
                currentBar = tracker.lastStatus?.bar ?: 1,
                currentBeat = tracker.lastStatus?.beat ?: 1,
                refTimeSec = prevTime
            )
        )
        assertTrue(report.contains("=== AI Jam Companion — Diagnostic Report ==="))
        assertTrue(report.contains("backward=0.00s"))
    }

    @Test
    fun offlineAnalyzerProcessesArpeggiatedPianoBalladStableChords() {
        val sr = 22050
        val durSec = 8.0
        val totalSamples = (sr * durSec).toInt()
        val samples = FloatArray(totalSamples)

        // Simulate an arpeggiated piano ballad (like "Great Expectations"):
        // 120 BPM (0.5s per beat, 2.0s per bar):
        // First 4.0s (2 bars): arpeggiated C major (C3 -> G3 -> C4 -> E4) + sustained pad + vocal melody
        // Next 4.0s (2 bars): arpeggiated A minor (A2 -> E3 -> A3 -> C4) + sustained pad + vocal melody
        val beatSec = 0.5
        for (i in 0 until totalSamples) {
            val t = i.toDouble() / sr
            val isFirstHalf = t < 4.0
            val beatIdx = ((t / beatSec).toInt()) % 4
            val arpFreq = if (isFirstHalf) {
                when (beatIdx) {
                    0 -> 130.81 // C3
                    1 -> 196.00 // G3
                    2 -> 261.63 // C4
                    else -> 329.63 // E4
                }
            } else {
                when (beatIdx) {
                    0 -> 110.00 // A2
                    1 -> 164.81 // E3
                    2 -> 220.00 // A3
                    else -> 261.63 // C4
                }
            }
            val chordTriad = if (isFirstHalf) {
                listOf(130.81, 261.63, 329.63, 392.00)
            } else {
                listOf(110.0, 220.0, 261.63, 329.63)
            }
            val beatPhase = (t % beatSec) / beatSec
            val pulseEnv = if (beatPhase < 0.25) 1.0 else 0.40
            var sum = 0.7 * sin(2.0 * PI * arpFreq * t)
            for (f in chordTriad) sum += 0.35 * sin(2.0 * PI * f * t)
            // Foreground vocal melody with harmonics after 1.0s intro
            if (t >= 1.0) {
                val vocalF0 = if (isFirstHalf) 523.25 else 440.0
                sum += 0.65 * sin(2.0 * PI * vocalF0 * t) + 0.30 * sin(2.0 * PI * 2 * vocalF0 * t)
            }
            samples[i] = (0.14 * pulseEnv * sum).toFloat()
        }

        val map = OfflineSongAnalyzer.analyze(
            samples = samples,
            sampleRate = sr,
            id = "synth_ballad_test",
            title = "Arpeggiated Piano Ballad",
            artist = "Test"
        )
        assertTrue(map.metadata.bpm in 58.0..175.0)
        assertTrue(map.chords.isNotEmpty())
        assertTrue(map.states.isNotEmpty())
        // Verify bar chords are stable (at most 2 distinct chords per bar)
        assertTrue(map.states.all { it.subChords.distinct().size <= 2 })
        assertTrue(map.sections.isNotEmpty())
        assertTrue(map.vocalAnchor.times.size == map.vocalAnchor.midiNotes.size)
        assertTrue(com.aijam.companion.util.DiagnosticLogger.lastAnalysisReport.isNotBlank())
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
    fun bundledDemoAssetAndGreatExpectationsPresetParseCleanly() {
        val assetFile = java.io.File("src/main/assets/demo_ballad_map.json")
        if (assetFile.exists()) {
            val demo = SongMapJson.fromJson(assetFile.readText(), fallbackId = "demo_ballad")
            assertEquals("Great Expectations", demo.metadata.title)
            assertEquals("Sienna Spiro", demo.metadata.artist)
            assertEquals(55, demo.states.size)
            assertTrue(demo.sections.any { "פתיח" in it.label })
            assertTrue(demo.sections.any { "פזמון" in it.label })
            assertTrue(demo.lyrics.size >= 40)
            assertEquals("Bm", demo.states.first().chord)
            // Verify intro frames (t = 5.0s) are unvoiced (< 0.2) so intro plays instrumentally
            val introIdx = demo.vocalAnchor.times.indexOfFirst { it >= 5.0 }
            if (introIdx >= 0) {
                assertTrue(demo.vocalAnchor.voicedProb[introIdx] < 0.2)
            }
        }

        assertTrue(com.aijam.companion.data.GreatExpectationsPreset.matchesSong("Sienna Spiro - Great Expectations (Official Audio)"))
        val preset = com.aijam.companion.data.GreatExpectationsPreset.buildSongMap(targetDurationSec = 173.0)
        assertEquals(55, preset.states.size)
        assertEquals("Bm", preset.states[0].chord)
        assertEquals("Gmaj7", preset.states[2].chord)
        assertEquals("G", preset.states[12].chord)
        assertEquals("F#7", preset.states[14].chord)
        assertTrue(preset.lyrics.first().text.contains("In my head"))
    }
}


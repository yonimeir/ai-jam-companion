"""
AI Jam Companion - Comprehensive Test Suite
Validates all stages end-to-end:
- Stage A: Offline chord detection, pYIN + REPET-SIM vocal pitch extraction, song mapping
- Stage B: Vectorized FFT-YIN real-time pitch detection, Online DTW score following
- Stage C: Harmonic State Machine, confidence threshold, Vamp loop logic
- Stage D: Dynamic velocity mapping, smooth voice-leading, minimalist drums, MIDI & WAV synthesis
"""

import json
import os
import sys
import tempfile
import unittest
import numpy as np

# Ensure project root is in sys.path
sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

from offline_pipeline.chord_detector import create_chord_templates, chord_to_midi_notes
from realtime_engine.pitch_detector import RealtimePitchDetector
from realtime_engine.harmonic_state_machine import HarmonicStateMachine, EngineState
from realtime_engine.tracker import RealtimeJamTracker
from synthesis.voicing import generate_piano_voicing, generate_drum_pattern
from synthesis.midi_generator import VelocityMapper
from synthesis.piano_synth import AcousticPianoRenderer


class TestJamCompanionPipeline(unittest.TestCase):

    def test_chord_templates_and_voice_leading(self):
        templates = create_chord_templates()
        self.assertEqual(len(templates), 24)
        self.assertIn("C", templates)
        self.assertIn("Am", templates)

        self.assertEqual(chord_to_midi_notes("C", octave=4), [60, 64, 67])
        self.assertEqual(chord_to_midi_notes("Am", octave=4), [69, 72, 76])

        # Test smooth voice leading minimizes hand movement between C and F
        v_c = generate_piano_voicing("C", style="ballad_arpeggio", beat_in_bar=1)
        v_f = generate_piano_voicing("F", style="ballad_arpeggio", beat_in_bar=1, prev_notes=v_c["harmony_notes"])
        center_c = np.mean(v_c["harmony_notes"])
        center_f = np.mean(v_f["harmony_notes"])
        self.assertLess(abs(center_f - center_c), 5.0)

        # Test minimalist drum pattern
        drums_b1 = generate_drum_pattern(beat_in_bar=1, style="ballad_arpeggio", velocity=80)
        self.assertGreaterEqual(len(drums_b1), 2)

    def test_realtime_fft_yin_pitch_detector(self):
        sr = 22050
        duration = 0.1  # 2205 samples
        freq = 440.0    # A4, MIDI 69
        t = np.linspace(0, duration, int(sr * duration), endpoint=False)
        chunk = 0.7 * np.sin(2 * np.pi * freq * t)

        detector = RealtimePitchDetector(sample_rate=sr)
        frame = detector.process_chunk(chunk)

        self.assertTrue(frame.is_voiced)
        self.assertAlmostEqual(frame.f0_hz, 440.0, delta=3.0)
        self.assertAlmostEqual(frame.midi_note, 69.0, delta=0.2)
        self.assertGreater(frame.confidence, 0.8)

    def test_dynamic_velocity_mapper(self):
        mapper = VelocityMapper(min_rms_db=-45.0, max_rms_db=-10.0, min_vel=40, max_vel=110)
        vel_quiet = mapper.map_rms_to_velocity(-42.0)
        self.assertLessEqual(vel_quiet, 55)

        for _ in range(5):
            vel_loud = mapper.map_rms_to_velocity(-12.0)
        self.assertGreaterEqual(vel_loud, 95)
        self.assertGreater(vel_loud, vel_quiet)

    def test_harmonic_state_machine_and_vamp(self):
        mock_song_map = {
            "metadata": {"bpm": 120.0, "duration": 8.0},
            "sections": [{"name": "verse", "label": "Verse", "start_bar": 1, "end_bar": 2}],
            "chords": [
                {"chord": "C", "root": "C", "quality": "major", "start_time": 0.0, "end_time": 2.0, "bar": 1, "beat": 1, "notes": [60, 64, 67]},
                {"chord": "G", "root": "G", "quality": "major", "start_time": 2.0, "end_time": 4.0, "bar": 2, "beat": 1, "notes": [55, 59, 62]},
            ],
        }
        sm = HarmonicStateMachine(mock_song_map, confidence_threshold=0.6, pause_to_vamp_sec=0.3)

        st1 = sm.update(ref_time=0.5, confidence=0.8, is_voiced=True, dt=0.05)
        self.assertEqual(st1.chord, "C")
        self.assertEqual(st1.engine_state, EngineState.PLAYING)

        for _ in range(8):
            st_pause = sm.update(ref_time=0.5, confidence=0.8, is_voiced=False, dt=0.05)
        self.assertEqual(st_pause.engine_state, EngineState.VAMPING)
        self.assertEqual(st_pause.chord, "C")

        sm.update(ref_time=2.5, confidence=0.85, is_voiced=True, dt=0.05)
        st_resume = sm.update(ref_time=2.5, confidence=0.85, is_voiced=True, dt=0.05)
        self.assertEqual(st_resume.chord, "G")
        self.assertEqual(st_resume.engine_state, EngineState.PLAYING)

    def test_midi_and_piano_synthesis(self):
        renderer = AcousticPianoRenderer(sample_rate=22050)
        tone = renderer.render_piano_note(midi_note=60, duration_sec=0.5, velocity=80)
        self.assertGreater(len(tone), 0)
        self.assertFalse(np.isnan(tone).any())

        timeline = [{"notes": [48, 60, 64, 67], "start_beat": 0.0, "duration_beats": 1.0, "velocity": 85}]
        mix = renderer.render_accompaniment_track(timeline, bpm=120.0)
        self.assertEqual(mix.shape[1], 2)
        self.assertGreater(np.max(np.abs(mix)), 0.01)

    def test_end_to_end_demo_alignment_and_midi_timeline(self):
        import soundfile as sf
        base = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
        map_path = os.path.join(base, "song_maps", "demo_ballad_map.json")
        voc_path = os.path.join(base, "audio_samples", "demo_ballad_vocals.wav")
        if not (os.path.exists(map_path) and os.path.exists(voc_path)):
            self.skipTest("Demo assets missing")

        with open(map_path, "r", encoding="utf-8") as f:
            song_map = json.load(f)
        y, sr = sf.read(voc_path)

        tracker = RealtimeJamTracker(song_map=song_map, sample_rate=sr, include_drums=True)
        for i in range(0, len(y), 1024):
            ev = tracker.process_audio_buffer(y[i : i + 1024])

        # Verify full-song tracking accuracy
        self.assertGreaterEqual(tracker.score_follower.get_progress_percent(), 95.0)
        # Verify monotonic performance beats (no overlapping VAMP beat collisions)
        beats = [item["start_beat"] for item in tracker.scheduled_timeline]
        self.assertEqual(beats, sorted(beats))
        self.assertEqual(len(beats), len(set(beats)))

        # Verify MIDI export works cleanly
        with tempfile.NamedTemporaryFile(suffix=".mid", delete=True) as tmp:
            tracker.midi_builder.export_midi(tmp.name)
            self.assertGreater(os.path.getsize(tmp.name), 100)


if __name__ == "__main__":
    unittest.main()

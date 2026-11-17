"""
AI Jam Companion - Comprehensive Test Suite
Validates all stages end-to-end:
- Stage A: Offline chord detection, pYIN + REPET-SIM vocal pitch extraction, song mapping
- Stage B: Vectorized FFT-YIN real-time pitch detection, Online DTW score following
- Stage C: Harmonic State Machine, confidence threshold, Vamp loop logic
- Stage D: Dynamic velocity mapping, smooth voice-leading, minimalist drums, MIDI & WAV synthesis
- Backend API: FastAPI routes and WebSocket real-time streaming
"""

import json
import os
import sys
import tempfile
import unittest
import numpy as np

# Ensure project root is in sys.path
sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

from offline_pipeline.chord_detector import (
    create_chord_templates, chord_to_midi_notes, extract_chords_from_audio
)
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

    def test_fastapi_server_routes_and_websocket(self):
        from fastapi.testclient import TestClient
        import server

        client = TestClient(server.app)
        res = client.get("/api/health")
        self.assertEqual(res.status_code, 200)
        self.assertEqual(res.json()["status"], "ok")

        res_map = client.get("/api/song-map/current")
        self.assertEqual(res_map.status_code, 200)
        self.assertIn("states", res_map.json())

        # Test WebSocket real-time streaming
        with client.websocket_connect("/ws/jam") as ws:
            ws.send_json({"type": "config", "style": "ballad", "drums": True})
            pcm = (0.5 * np.sin(2 * np.pi * 440.0 * np.linspace(0, 0.1, 2205))).astype(np.float32)
            ws.send_bytes(pcm.tobytes())
            msg = ws.receive_json()
            self.assertEqual(msg["type"], "state")
            self.assertIn("chord", msg["data"])

    def test_youtube_url_extraction_and_share_target(self):
        from unittest.mock import patch
        from fastapi.testclient import TestClient
        from offline_pipeline.extract_song_map import extract_media_url
        import server

        self.assertEqual(
            extract_media_url("Check out this track https://youtu.be/dQw4w9WgXcQ?si=xyz123!"),
            "https://youtu.be/dQw4w9WgXcQ?si=xyz123",
        )
        self.assertEqual(
            extract_media_url("https://music.youtube.com/watch?v=abcDEF12345&list=RDAMVM"),
            "https://music.youtube.com/watch?v=abcDEF12345&list=RDAMVM",
        )
        self.assertEqual(
            extract_media_url("https://www.youtube.com/shorts/9bZkp7q19f0"),
            "https://www.youtube.com/shorts/9bZkp7q19f0",
        )
        self.assertIsNone(extract_media_url("just some plain text without a link"))

        client = TestClient(server.app)

        # PWA Manifest & Service Worker & Icons
        manifest_res = client.get("/manifest.json")
        self.assertEqual(manifest_res.status_code, 200)
        manifest = manifest_res.json()
        self.assertEqual(manifest["share_target"]["action"], "/share")
        self.assertEqual(manifest["share_target"]["params"]["url"], "url")
        self.assertEqual(manifest["share_target"]["params"]["text"], "text")

        self.assertEqual(client.get("/sw.js").status_code, 200)
        self.assertEqual(client.get("/icon.svg").status_code, 200)
        self.assertEqual(client.get("/icon-192.png").status_code, 200)
        self.assertEqual(client.get("/api/audio/active-vocals").status_code, 200)

        # Web Share Target redirect (Android YouTube sends URL inside `text`)
        share_res = client.get(
            "/share",
            params={"text": "Amazing ballad https://youtu.be/abc12345?si=999", "title": "My YouTube Song"},
            follow_redirects=False,
        )
        self.assertEqual(share_res.status_code, 302)
        loc = share_res.headers["location"]
        self.assertIn("url=https%3A%2F%2Fyoutu.be%2Fabc12345%3Fsi%3D999", loc)
        self.assertIn("title=My+YouTube+Song", loc)

        # Validation on invalid URL
        bad_res = client.post("/api/analyze-url", json={"url": "not-a-valid-url"})
        self.assertEqual(bad_res.status_code, 400)

        # Mocked YouTube download using local demo audio to verify full pipeline integration
        base = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
        demo_wav = os.path.join(base, "audio_samples", "demo_ballad_full.wav")
        if os.path.exists(demo_wav):
            def fake_dl(url, output_dir):
                return demo_wav, {
                    "title": "Mocked YouTube Ballad",
                    "artist": "YouTube Creator",
                    "source_url": url,
                    "video_id": "abc12345",
                }

            with patch("offline_pipeline.extract_song_map.download_audio_with_metadata", side_effect=fake_dl):
                ok_res = client.post(
                    "/api/analyze-url",
                    json={"url": "Share link https://youtu.be/abc12345"},
                )
            self.assertEqual(ok_res.status_code, 200)
            body = ok_res.json()
            self.assertEqual(body["metadata"]["title"], "Mocked YouTube Ballad")
            self.assertEqual(body["metadata"]["artist"], "YouTube Creator")
            self.assertEqual(body["metadata"]["source_url"], "https://youtu.be/abc12345")
            self.assertGreater(len(body["states"]), 0)

            # Verify async job submission & status polling
            import time
            with patch("offline_pipeline.extract_song_map.download_audio_with_metadata", side_effect=fake_dl):
                async_res = client.post(
                    "/api/analyze-url-async",
                    json={"url": "https://youtu.be/abc12345"},
                )
                self.assertEqual(async_res.status_code, 200)
                job_id = async_res.json()["job_id"]
                final_payload = None
                for _ in range(100):
                    st = client.get(f"/api/analyze/status/{job_id}")
                    self.assertEqual(st.status_code, 200)
                    data = st.json()
                    if data["status"] in ("done", "error"):
                        final_payload = data
                        break
                    time.sleep(0.05)
                self.assertIsNotNone(final_payload)
                self.assertEqual(final_payload["status"], "done")
                self.assertEqual(final_payload["song_map"]["metadata"]["title"], "Mocked YouTube Ballad")


if __name__ == "__main__":
    unittest.main()

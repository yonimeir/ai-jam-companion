"""
Real-Time Jam Companion Coordinator (Stages B, C, D)
Integrates sliding ring-buffer audio framing, FFT-YIN pitch detection,
Online DTW score following, Harmonic State Machine, voice-leading piano voicings,
optional minimalist drums, dynamic velocity mapping, and live performance export.
"""

from typing import Dict, Any, List, Optional, Callable
import numpy as np

from realtime_engine.pitch_detector import RealtimePitchDetector, PitchFrame
from realtime_engine.dynamic_alignment import OnlineScoreFollower
from realtime_engine.harmonic_state_machine import HarmonicStateMachine, HarmonicState, EngineState
from synthesis.voicing import generate_piano_voicing, generate_drum_pattern
from synthesis.midi_generator import VelocityMapper, AdaptiveMidiBuilder

NOTE_NAMES = ["C", "C#", "D", "D#", "E", "F", "F#", "G", "G#", "A", "A#", "B"]


def midi_to_note_name(midi_val: float) -> str:
    if midi_val <= 0:
        return "---"
    m_round = int(round(midi_val))
    note_name = NOTE_NAMES[m_round % 12]
    octave = (m_round // 12) - 1
    cents = int(round((midi_val - m_round) * 100))
    cents_str = f"{cents:+d}¢" if abs(cents) > 5 else ""
    return f"{note_name}{octave}{cents_str}"


class RealtimeJamTracker:
    def __init__(
        self,
        song_map: Dict[str, Any],
        sample_rate: int = 22050,
        window_size: int = 2048,
        hop_size: int = 512,
        confidence_threshold: float = 0.55,
        accompaniment_style: str = "ballad_arpeggio",
        include_drums: bool = False,
        event_callback: Optional[Callable[[Dict[str, Any]], None]] = None
    ):
        self.song_map = song_map
        self.sr = sample_rate
        self.window_size = window_size
        self.hop_size = hop_size
        self.bpm = float(song_map.get("metadata", {}).get("bpm", 120.0))
        self.style = accompaniment_style
        self.include_drums = include_drums
        self.callback = event_callback

        # Modules
        self.pitch_detector = RealtimePitchDetector(sample_rate=sample_rate)
        self.score_follower = OnlineScoreFollower(song_map=song_map)
        self.state_machine = HarmonicStateMachine(
            song_map=song_map,
            confidence_threshold=confidence_threshold
        )
        self.velocity_mapper = VelocityMapper()
        self.midi_builder = AdaptiveMidiBuilder(bpm=self.bpm)

        # Sliding audio buffer (matches offline hop_length=512 cadence)
        self._audio_buf = np.zeros(0, dtype=np.float32)
        self.hop_dt = float(hop_size / sample_rate)
        self.elapsed_time = 0.0
        self.performance_beat = 0.0
        self.last_beat_token = -1
        self.prev_rh_notes: Optional[List[int]] = None
        self.scheduled_timeline: List[Dict[str, Any]] = []
        self.last_status: Optional[Dict[str, Any]] = None

    def reset(self):
        """Reset all trackers for a fresh performance."""
        self.score_follower.reset()
        self.state_machine.current_chord_idx = 0
        self.state_machine.confirm_count = 0
        self.state_machine.unvoiced_duration = 0.0
        self.state_machine.vamp_beat = 1
        self.state_machine.vamp_timer = 0.0
        self.state_machine.engine_state = EngineState.PLAYING
        self._audio_buf = np.zeros(0, dtype=np.float32)
        self.elapsed_time = 0.0
        self.performance_beat = 0.0
        self.last_beat_token = -1
        self.prev_rh_notes = None
        self.scheduled_timeline.clear()
        self.midi_builder = AdaptiveMidiBuilder(bpm=self.bpm)
        self.last_status = None

    def set_style(self, style: str):
        self.style = style

    def set_confidence_threshold(self, threshold: float):
        self.state_machine.confidence_threshold = float(np.clip(threshold, 0.2, 0.95))

    def set_include_drums(self, enabled: bool):
        self.include_drums = bool(enabled)

    def _process_single_hop(self, window: np.ndarray) -> Dict[str, Any]:
        """Processes one 2048-sample analysis window advancing by hop_size (512 samples)."""
        self.elapsed_time += self.hop_dt
        sec_per_beat = 60.0 / self.bpm
        self.performance_beat += self.hop_dt / sec_per_beat

        # 1. Real-time FFT-YIN pitch & dynamics detection (Stage B)
        pitch_frame: PitchFrame = self.pitch_detector.process_chunk(window)

        # 2. Dynamic Alignment via Online DTW (Stage B)
        aligned_frame, ref_time, align_conf = self.score_follower.step(
            live_midi=pitch_frame.midi_note,
            live_confidence=pitch_frame.confidence,
            live_is_voiced=pitch_frame.is_voiced
        )
        progress_pct = self.score_follower.get_progress_percent()

        # 3. Harmonic State Machine Decision (Stage C)
        h_state: HarmonicState = self.state_machine.update(
            ref_time=ref_time,
            confidence=align_conf,
            is_voiced=pitch_frame.is_voiced,
            dt=self.hop_dt
        )

        # 4. Adaptive Velocity Mapping & Piano/Drum Trigger (Stage D)
        velocity = self.velocity_mapper.map_rms_to_velocity(pitch_frame.rms_db)

        is_vamping = h_state.engine_state == EngineState.VAMPING
        if is_vamping:
            current_beat = h_state.vamp_cycle_beat
            beat_token = int(self.elapsed_time / sec_per_beat)
        else:
            current_beat = h_state.beat
            # Quantize to musical beat along reference timeline
            beat_token = int(ref_time / sec_per_beat)

        triggered_notes: List[int] = []
        drum_hits: List[Dict[str, Any]] = []

        if beat_token != self.last_beat_token:
            self.last_beat_token = beat_token

            voicing = generate_piano_voicing(
                chord_name=h_state.chord,
                style=self.style,
                beat_in_bar=current_beat,
                prev_notes=self.prev_rh_notes
            )

            if voicing["harmony_notes"]:
                self.prev_rh_notes = voicing["harmony_notes"]

            if voicing["bass_note"] is not None:
                triggered_notes.append(voicing["bass_note"])
            triggered_notes.extend(voicing["harmony_notes"])

            # Record on monotonic live performance timeline (prevents VAMP note collisions)
            beat_start = round(self.performance_beat, 3)
            if triggered_notes and voicing["duration_beats"] > 0:
                self.midi_builder.add_notes(
                    notes=triggered_notes,
                    start_beat=beat_start,
                    duration_beats=voicing["duration_beats"],
                    velocity=velocity
                )
                self.scheduled_timeline.append({
                    "notes": triggered_notes,
                    "start_beat": beat_start,
                    "duration_beats": voicing["duration_beats"],
                    "velocity": velocity
                })

            if self.include_drums:
                drum_hits = generate_drum_pattern(
                    beat_in_bar=current_beat,
                    style=self.style,
                    velocity=velocity,
                    is_vamping=is_vamping
                )
                if drum_hits:
                    self.midi_builder.add_drum_hits(drum_hits, start_beat=beat_start)

        status_event = {
            "elapsed_sec": round(self.elapsed_time, 2),
            "ref_time_sec": round(ref_time, 2),
            "progress_pct": progress_pct,
            "section": h_state.section,
            "bar": h_state.bar,
            "beat": current_beat,
            "chord": h_state.chord,
            "engine_state": h_state.engine_state.value,
            "confidence": h_state.confidence,
            "tempo_ratio": round(self.score_follower.estimated_tempo_ratio, 2),
            "singer_f0": pitch_frame.f0_hz,
            "singer_note": midi_to_note_name(pitch_frame.midi_note),
            "singer_midi": pitch_frame.midi_note,
            "singer_rms_db": pitch_frame.rms_db,
            "is_voiced": pitch_frame.is_voiced,
            "piano_velocity": velocity,
            "triggered_notes": triggered_notes,
            "drum_hits": drum_hits
        }

        self.last_status = status_event
        if self.callback:
            self.callback(status_event)
        return status_event

    def process_audio_buffer(self, audio_chunk: np.ndarray) -> Dict[str, Any]:
        """
        Accepts an incoming audio buffer of arbitrary size (e.g. 512, 1024, 2048 samples),
        pushes it into the sliding ring buffer, and evaluates all ready hop frames.
        """
        chunk_f32 = np.asarray(audio_chunk, dtype=np.float32).flatten()
        if len(chunk_f32) == 0:
            return self.last_status or {}

        self._audio_buf = np.concatenate([self._audio_buf, chunk_f32])

        latest_event = self.last_status
        while len(self._audio_buf) >= self.window_size:
            window = self._audio_buf[: self.window_size]
            latest_event = self._process_single_hop(window)
            self._audio_buf = self._audio_buf[self.hop_size :]

        if latest_event is None:
            # First tiny chunk before buffer reaches window_size
            return {
                "elapsed_sec": round(self.elapsed_time, 2),
                "ref_time_sec": 0.0,
                "progress_pct": 0.0,
                "section": "Intro",
                "bar": 1,
                "beat": 1,
                "chord": self.state_machine.chords[0]["chord"],
                "engine_state": "PLAYING",
                "confidence": 0.5,
                "tempo_ratio": 1.0,
                "singer_f0": 0.0,
                "singer_note": "---",
                "singer_midi": 0.0,
                "singer_rms_db": -100.0,
                "is_voiced": False,
                "piano_velocity": self.velocity_mapper.min_vel,
                "triggered_notes": [],
                "drum_hits": []
            }
        return latest_event

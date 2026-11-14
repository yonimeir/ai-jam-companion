"""
Adaptive MIDI Generator and Dynamic Velocity Mapping
Translates live vocal dynamics (RMS amplitude) to piano velocity and constructs
multi-track MIDI files (Acoustic Grand Piano + optional Minimalist Drums).
"""

from typing import List, Dict, Any
import numpy as np
import mido


class VelocityMapper:
    def __init__(
        self,
        min_rms_db: float = -45.0,
        max_rms_db: float = -12.0,
        min_vel: int = 38,
        max_vel: int = 112,
        attack_coeff: float = 0.6,
        release_coeff: float = 0.15
    ):
        self.min_rms = min_rms_db
        self.max_rms = max_rms_db
        self.min_vel = min_vel
        self.max_vel = max_vel
        self.attack_coeff = attack_coeff
        self.release_coeff = release_coeff
        self.smoothed_vel = float(min_vel)

    def map_rms_to_velocity(self, rms_db: float) -> int:
        """
        Maps live singer RMS dB to MIDI velocity [1, 127] with dynamic envelope smoothing.
        """
        clamped_db = np.clip(rms_db, self.min_rms, self.max_rms)
        norm = (clamped_db - self.min_rms) / (self.max_rms - self.min_rms + 1e-6)
        curved = float(norm ** 1.3)
        target_vel = self.min_vel + curved * (self.max_vel - self.min_vel)

        if target_vel > self.smoothed_vel:
            self.smoothed_vel += self.attack_coeff * (target_vel - self.smoothed_vel)
        else:
            self.smoothed_vel += self.release_coeff * (target_vel - self.smoothed_vel)

        return int(np.clip(np.round(self.smoothed_vel), 1, 127))


class AdaptiveMidiBuilder:
    def __init__(self, bpm: float = 120.0):
        self.bpm = bpm
        self.ticks_per_beat = 480
        self.mid = mido.MidiFile(ticks_per_beat=self.ticks_per_beat)

        # Track 0: Acoustic Grand Piano (Channel 0)
        self.track = mido.MidiTrack()
        self.mid.tracks.append(self.track)
        tempo_val = mido.bpm2tempo(bpm)
        self.track.append(mido.MetaMessage("track_name", name="AI Jam Acoustic Piano", time=0))
        self.track.append(mido.MetaMessage("set_tempo", tempo=tempo_val, time=0))
        self.track.append(mido.Message("program_change", program=0, channel=0, time=0))

        self.active_notes: List[Dict[str, Any]] = []

    def add_notes(
        self,
        notes: List[int],
        start_beat: float,
        duration_beats: float,
        velocity: int,
        channel: int = 0
    ):
        """Adds a group of notes at a specific musical beat."""
        if duration_beats <= 0 or not notes:
            return
        start_tick = max(0, int(round(start_beat * self.ticks_per_beat)))
        dur_ticks = max(20, int(round(duration_beats * self.ticks_per_beat)))
        end_tick = start_tick + max(10, dur_ticks - 15)

        for n in notes:
            if 0 <= n <= 127:
                self.active_notes.append({
                    "note": int(n),
                    "on_tick": start_tick,
                    "off_tick": end_tick,
                    "velocity": int(np.clip(velocity, 1, 127)),
                    "channel": int(channel)
                })

    def add_drum_hits(
        self,
        drum_hits: List[Dict[str, Any]],
        start_beat: float
    ):
        """Adds minimalist percussion hits on GM Drum Channel 9."""
        for hit in drum_hits:
            self.add_notes(
                notes=[hit["note"]],
                start_beat=start_beat,
                duration_beats=0.25,
                velocity=hit["velocity"],
                channel=9
            )

    def export_midi(self, file_path: str):
        """Compiles note list into delta-time MIDI events and writes file."""
        events = []
        for n_ev in self.active_notes:
            ch = n_ev.get("channel", 0)
            events.append((n_ev["on_tick"], 1, "note_on", n_ev["note"], n_ev["velocity"], ch))
            events.append((n_ev["off_tick"], 0, "note_off", n_ev["note"], 0, ch))

        # Sort by tick time (note_off before note_on at same tick)
        events.sort(key=lambda x: (x[0], x[1]))

        # Rebuild clean track keeping initial meta messages
        clean_track = mido.MidiTrack()
        for msg in self.track[:3]:
            clean_track.append(msg)

        curr_tick = 0
        for tick, _, ev_type, note, vel, ch in events:
            delta = max(0, tick - curr_tick)
            curr_tick = tick
            clean_track.append(mido.Message(ev_type, note=note, velocity=vel, time=delta, channel=ch))

        clean_track.append(mido.MetaMessage("end_of_track", time=self.ticks_per_beat))
        self.mid.tracks[0] = clean_track
        self.mid.save(file_path)

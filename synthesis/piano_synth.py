"""
Acoustic Piano Audio Synthesizer
Renders MIDI note events into high-fidelity acoustic piano audio (WAV)
using multi-harmonic physical modeling with hammer strike transients and stereo imaging.
"""

from typing import List, Dict, Any, Optional
import numpy as np
import soundfile as sf

def midi_to_hz(midi: int) -> float:
    return 440.0 * (2.0 ** ((midi - 69.0) / 12.0))

class AcousticPianoRenderer:
    def __init__(self, sample_rate: int = 44100):
        self.sr = sample_rate

    def render_piano_note(
        self,
        midi_note: int,
        duration_sec: float,
        velocity: int = 80
    ) -> np.ndarray:
        """
        Synthesizes a realistic grand piano tone.
        Higher velocity increases harmonic brightness and hammer strike.
        """
        freq = midi_to_hz(midi_note)
        vel_norm = np.clip(velocity / 127.0, 0.05, 1.0)
        
        # Duration with release tail
        total_dur = duration_sec + 0.8
        n_samples = int(self.sr * total_dur)
        t = np.linspace(0, total_dur, n_samples, endpoint=False)
        
        # 1. Harmonic series with piano inharmonicity coefficient (B ≈ 0.0001)
        B = 0.00015
        n_harmonics = min(12, int(self.sr / (2.5 * freq)))
        
        signal = np.zeros(n_samples)
        
        for h in range(1, n_harmonics + 1):
            # Inharmonic partial frequency: f_h = h * f0 * sqrt(1 + B * h^2)
            f_h = h * freq * np.sqrt(1.0 + B * (h ** 2))
            if f_h >= self.sr / 2:
                break
                
            # Decay rate increases with harmonic number
            decay_rate = (1.2 + 0.35 * h) * (1.0 + 0.015 * max(0, midi_note - 40))
            envelope = np.exp(-decay_rate * t)
            
            # Harmonic amplitude profile (higher harmonics excited more with higher velocity)
            harmonic_amp = (1.0 / (h ** 1.1)) * (vel_norm ** (0.3 + 0.15 * h))
            
            # Phase
            signal += harmonic_amp * envelope * np.sin(2.0 * np.pi * f_h * t)
            
        # 2. Percussive felt hammer strike transient (short low-pass noise burst)
        strike_dur = int(self.sr * 0.012) # 12ms
        if strike_dur < n_samples:
            noise = np.random.uniform(-1, 1, strike_dur)
            noise_env = np.exp(-np.linspace(0, 10, strike_dur))
            signal[:strike_dur] += 0.35 * vel_norm * noise * noise_env
            
        # 3. Overall note amplitude envelope (rapid attack, release at duration_sec)
        attack_len = int(self.sr * 0.005) # 5ms attack
        overall_env = np.ones(n_samples)
        if attack_len > 0:
            overall_env[:attack_len] = np.sin(np.linspace(0, np.pi / 2, attack_len))
            
        # Release damping when key is released
        rel_start = int(self.sr * duration_sec)
        if rel_start < n_samples:
            rel_len = n_samples - rel_start
            damp = np.exp(-np.linspace(0, 8.0, rel_len))
            overall_env[rel_start:] *= damp
            
        return signal * overall_env * vel_norm

    def render_accompaniment_track(
        self,
        notes_timeline: List[Dict[str, Any]],
        bpm: float,
        output_wav_path: Optional[str] = None
    ) -> np.ndarray:
        """
        Renders a list of scheduled piano notes into stereo audio.
        Each item in notes_timeline:
            {"notes": [midi1, midi2], "start_beat": 4.0, "duration_beats": 1.0, "velocity": 85}
        """
        if not notes_timeline:
            return np.zeros((self.sr * 2, 2))
            
        beat_sec = 60.0 / bpm
        max_end_sec = max(
            (item["start_beat"] + item["duration_beats"]) * beat_sec + 2.0
            for item in notes_timeline
        )
        total_samples = int(self.sr * max_end_sec)
        stereo_mix = np.zeros((total_samples, 2), dtype=np.float32)
        
        for item in notes_timeline:
            start_sec = item["start_beat"] * beat_sec
            start_s = int(start_sec * self.sr)
            dur_sec = item["duration_beats"] * beat_sec
            vel = item.get("velocity", 75)
            
            for note_midi in item.get("notes", []):
                rendered_note = self.render_piano_note(note_midi, dur_sec, vel)
                end_s = min(total_samples, start_s + len(rendered_note))
                chunk_len = end_s - start_s
                
                # Piano stereo panning: lower notes left (pan < 0.5), higher notes right (pan > 0.5)
                # MIDI 21 (A0) to 108 (C8)
                pan = np.clip((note_midi - 21) / (108 - 21), 0.15, 0.85)
                left_gain = float(np.cos(pan * np.pi / 2))
                right_gain = float(np.sin(pan * np.pi / 2))
                
                stereo_mix[start_s:end_s, 0] += left_gain * rendered_note[:chunk_len]
                stereo_mix[start_s:end_s, 1] += right_gain * rendered_note[:chunk_len]
                
        # Master limiter / normalization
        peak = np.max(np.abs(stereo_mix))
        if peak > 0.95:
            stereo_mix = (stereo_mix / peak) * 0.92
            
        if output_wav_path:
            sf.write(output_wav_path, stereo_mix, self.sr)
            print(f"[✓] Accompaniment rendered to WAV: {output_wav_path}")
            
        return stereo_mix

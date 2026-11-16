"""
Synthetic Demo Track Generator
Generates a realistic pop ballad track with acoustic piano accompaniment and melodic vocal track.
Outputs:
- demo_ballad_full.wav (Full mix)
- demo_ballad_vocals.wav (Isolated vocal track for real-time tracking tests)
"""

import os
from typing import Optional
import numpy as np
import soundfile as sf

def generate_piano_tone(freq: float, duration: float, sr: int, velocity: float = 0.8) -> np.ndarray:
    """Synthesizes an acoustic piano-like note with natural harmonics and decay envelope."""
    n_samples = int(sr * duration)
    t = np.linspace(0, duration, n_samples, endpoint=False)
    
    # Harmonics: fundamental + 2nd, 3rd, 4th, 5th, 6th with differing decay rates
    decay_rates = [2.0, 3.5, 5.0, 7.0, 9.0, 12.0]
    harmonic_amps = [1.0, 0.6, 0.4, 0.25, 0.15, 0.08]
    
    tone = np.zeros(n_samples)
    for h, (rate, amp) in enumerate(zip(decay_rates, harmonic_amps), 1):
        envelope = np.exp(-rate * t) * (1.0 - np.exp(-t * 200.0)) # rapid attack
        tone += amp * envelope * np.sin(2 * np.pi * freq * h * t)
        
    return tone * velocity

def generate_vocal_tone(freq: float, duration: float, sr: int, amplitude: float = 0.7) -> np.ndarray:
    """Synthesizes a human-like vocal singing tone with vibrato and vocal formants."""
    n_samples = int(sr * duration)
    t = np.linspace(0, duration, n_samples, endpoint=False)
    
    # Natural vocal vibrato (5.5 Hz, ~15 cents depth)
    vibrato = 1.0 + 0.015 * np.sin(2 * np.pi * 5.5 * t)
    phase = 2 * np.pi * freq * np.cumsum(vibrato) / sr
    
    # Formants simulation (open vowel 'Ah' / 'Oh' formants around 700Hz, 1200Hz, 2400Hz)
    vocal = (
        1.0 * np.sin(phase) +
        0.8 * np.sin(2 * phase) +
        0.5 * np.sin(3 * phase) +
        0.3 * np.sin(4 * phase) +
        0.15 * np.sin(5 * phase)
    )
    
    # Smooth attack and release envelope (breath envelope)
    attack = int(sr * 0.08)
    release = int(sr * 0.1)
    env = np.ones(n_samples)
    if attack > 0 and len(env) > attack:
        env[:attack] = np.sin(np.linspace(0, np.pi / 2, attack)) ** 2
    if release > 0 and len(env) > release:
        env[-release:] = np.cos(np.linspace(0, np.pi / 2, release)) ** 2
        
    return vocal * env * amplitude

def midi_to_freq(midi_num: int) -> float:
    return 440.0 * (2.0 ** ((midi_num - 69.0) / 12.0))

def build_demo_song(output_dir: Optional[str] = None):
    if output_dir is None:
        output_dir = os.path.dirname(os.path.abspath(__file__))
    os.makedirs(output_dir, exist_ok=True)
    sr = 22050
    bpm = 100.0
    beat_dur = 60.0 / bpm # 0.6s per beat
    bar_dur = 4 * beat_dur # 2.4s per bar
    
    # Chord progressions per section (Bar by Bar)
    # 4/4 meter
    song_structure = [
        # Intro (4 bars)
        ("Intro", "C",  [48, 52, 55, 60], []),
        ("Intro", "G",  [43, 47, 50, 55], []),
        ("Intro", "Am", [45, 48, 52, 57], []),
        ("Intro", "F",  [41, 45, 48, 53], []),
        
        # Verse 1 (4 bars) - with vocal melody
        ("Verse 1", "C",  [48, 52, 55, 60], [(60, 0.0, 1.0), (62, 1.0, 1.0), (64, 2.0, 1.5)]),
        ("Verse 1", "Em", [40, 47, 52, 55], [(64, 0.0, 1.2), (62, 1.5, 0.8), (60, 2.5, 1.2)]),
        ("Verse 1", "F",  [41, 45, 48, 53], [(65, 0.0, 1.0), (64, 1.0, 1.0), (62, 2.0, 1.8)]),
        ("Verse 1", "G",  [43, 47, 50, 55], [(62, 0.0, 1.5), (60, 2.0, 1.8)]),
        
        # Verse 1 Part 2 (4 bars)
        ("Verse 1", "C",  [48, 52, 55, 60], [(60, 0.0, 1.0), (64, 1.0, 1.0), (67, 2.0, 1.5)]),
        ("Verse 1", "Em", [40, 47, 52, 55], [(67, 0.0, 1.5), (65, 2.0, 1.0), (64, 3.0, 0.8)]),
        ("Verse 1", "F",  [41, 45, 48, 53], [(65, 0.0, 1.2), (64, 1.5, 1.0), (62, 2.5, 1.2)]),
        ("Verse 1", "G",  [43, 47, 50, 55], [(62, 0.0, 2.0), (64, 2.5, 1.2)]),
        
        # Chorus 1 (4 bars) - higher energy melody
        ("Chorus 1", "F",  [41, 48, 53, 57], [(69, 0.0, 1.2), (67, 1.5, 1.0), (65, 2.5, 1.2)]),
        ("Chorus 1", "G",  [43, 50, 55, 59], [(67, 0.0, 1.5), (69, 2.0, 1.0), (71, 3.0, 0.8)]),
        ("Chorus 1", "C",  [48, 52, 55, 60], [(72, 0.0, 2.0), (71, 2.0, 0.8), (69, 3.0, 0.8)]),
        ("Chorus 1", "Am", [45, 52, 57, 60], [(69, 0.0, 2.5)]),
        
        # Chorus 1 Part 2 (4 bars)
        ("Chorus 1", "F",  [41, 48, 53, 57], [(69, 0.0, 1.2), (67, 1.5, 1.0), (65, 2.5, 1.2)]),
        ("Chorus 1", "G",  [43, 50, 55, 59], [(67, 0.0, 1.5), (65, 2.0, 1.0), (62, 3.0, 0.8)]),
        ("Chorus 1", "C",  [48, 52, 55, 60], [(60, 0.0, 3.0)]),
        ("Chorus 1", "C",  [48, 52, 55, 60], [(60, 0.0, 2.0)]),
        
        # Outro (2 bars)
        ("Outro", "F",  [41, 48, 53, 57], [(65, 0.0, 2.0)]),
        ("Outro", "C",  [36, 48, 52, 55, 60], [(60, 0.0, 3.5)]),
    ]
    
    total_duration = len(song_structure) * bar_dur + 1.0
    total_samples = int(sr * total_duration)
    
    piano_track = np.zeros(total_samples)
    vocal_track = np.zeros(total_samples)
    
    for bar_idx, (section, chord, notes, vocal_notes) in enumerate(song_structure):
        bar_start_sample = int(bar_idx * bar_dur * sr)
        
        # Generate Piano comping / broken chord pattern for this bar
        # Bass on beat 1 (duration 2 beats)
        bass_note = notes[0]
        bass_audio = generate_piano_tone(midi_to_freq(bass_note), beat_dur * 2.0, sr, velocity=0.85)
        end_s = min(total_samples, bar_start_sample + len(bass_audio))
        piano_track[bar_start_sample:end_s] += bass_audio[:end_s - bar_start_sample]
        
        # Broken chords on beats 1, 2, 3, 4
        for b_i in range(4):
            chord_beat_sample = bar_start_sample + int(b_i * beat_dur * sr)
            # Voicing tone
            voice_note = notes[1 + (b_i % (len(notes) - 1))]
            voice_audio = generate_piano_tone(midi_to_freq(voice_note), beat_dur * 1.5, sr, velocity=0.65)
            end_s = min(total_samples, chord_beat_sample + len(voice_audio))
            piano_track[chord_beat_sample:end_s] += voice_audio[:end_s - chord_beat_sample]
            
        # Generate Vocal notes in this bar
        for midi_n, beat_offset, dur_beats in vocal_notes:
            v_start_sample = bar_start_sample + int(beat_offset * beat_dur * sr)
            v_dur_sec = dur_beats * beat_dur
            v_audio = generate_vocal_tone(midi_to_freq(midi_n), v_dur_sec, sr, amplitude=0.75)
            end_v = min(total_samples, v_start_sample + len(v_audio))
            vocal_track[v_start_sample:end_v] += v_audio[:end_v - v_start_sample]
            
    # Normalize
    max_p = np.max(np.abs(piano_track)) + 1e-6
    max_v = np.max(np.abs(vocal_track)) + 1e-6
    piano_track = (piano_track / max_p) * 0.7
    vocal_track = (vocal_track / max_v) * 0.8
    full_mix = piano_track * 0.6 + vocal_track * 0.7
    
    full_path = os.path.join(output_dir, "demo_ballad_full.wav")
    vocal_path = os.path.join(output_dir, "demo_ballad_vocals.wav")
    
    sf.write(full_path, full_mix, sr)
    sf.write(vocal_path, vocal_track, sr)
    print(f"[✓] Generated full mix: {full_path} ({len(song_structure)} bars, {total_duration:.1f}s)")
    print(f"[✓] Generated vocal stem: {vocal_path}")

if __name__ == "__main__":
    build_demo_song()

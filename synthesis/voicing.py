"""
Adaptive Piano Voicing, Voice-Leading, and Minimalist Drum Pattern Generator
Translates chords and harmonic states into smooth piano voicings (nearest inversion
voice leading), bass lines, stylistic rhythmic patterns, and subtle percussion.
"""

from typing import List, Dict, Any, Tuple, Optional
import numpy as np

NOTE_NAMES = ["C", "C#", "D", "D#", "E", "F", "F#", "G", "G#", "A", "A#", "B"]
NAME_TO_SEMITONE = {name: i for i, name in enumerate(NOTE_NAMES)}
ALIASES = {"Db": "C#", "Eb": "D#", "Gb": "F#", "Ab": "G#", "Bb": "A#"}

# General MIDI Drum Map (Channel 10 / index 9)
DRUM_KICK = 36        # Acoustic Bass Drum
DRUM_RIM = 37         # Side Stick / Rimshot (minimalist ballad feel)
DRUM_SNARE_BRUSH = 38 # Soft Acoustic Snare / Brush
DRUM_CLOSED_HH = 42   # Closed Hi-Hat
DRUM_RIDE = 51        # Ride Cymbal 1


def get_chord_intervals(chord_name: str) -> Tuple[int, List[int]]:
    """Returns (root_semitone, intervals_from_root)."""
    is_maj7 = "maj7" in chord_name
    is_7th = "7" in chord_name
    clean = chord_name.replace("maj7", "").replace("7", "")
    is_minor = clean.endswith("m")

    root_str = clean[:-1] if is_minor else clean
    root_str = ALIASES.get(root_str, root_str)
    root_semi = NAME_TO_SEMITONE.get(root_str, 0)

    if is_minor:
        intervals = [0, 3, 7]
        if is_7th:
            intervals.append(10)
    else:
        intervals = [0, 4, 7]
        if is_maj7:
            intervals.append(11)
        elif is_7th:
            intervals.append(10)

    return root_semi, intervals


def _apply_voice_leading(
    pitch_classes: List[int],
    prev_notes: Optional[List[int]] = None,
    center_midi: float = 65.0
) -> List[int]:
    """
    Chooses the chord inversion in Octave 4-5 (MIDI 57..79) that minimizes total
    semitone distance from the previous right-hand voicing (smooth voice leading).
    """
    target_center = float(np.mean(prev_notes)) if prev_notes else center_midi

    # Generate candidate inversions spanning octaves 3, 4, 5
    all_candidates = []
    for base_oct in (48, 60):
        voiced = []
        for pc in pitch_classes:
            n = base_oct + pc
            while n < 57:
                n += 12
            while n > 78:
                n -= 12
            voiced.append(n)
        voiced.sort()
        all_candidates.append(voiced)

        # 1st and 2nd inversions
        if len(voiced) >= 3:
            inv1 = sorted([voiced[1], voiced[2], voiced[0] + 12] + voiced[3:])
            inv2 = sorted([voiced[2], voiced[0] + 12, voiced[1] + 12] + voiced[3:])
            for inv in (inv1, inv2):
                if inv[-1] <= 81 and inv[0] >= 55:
                    all_candidates.append(inv)

    best_voicing = all_candidates[0]
    best_cost = 1e9
    for cand in all_candidates:
        c_center = float(np.mean(cand))
        cost = abs(c_center - target_center)
        if prev_notes and len(prev_notes) == len(cand):
            cost += 0.5 * sum(abs(a - b) for a, b in zip(sorted(cand), sorted(prev_notes)))
        if cost < best_cost:
            best_cost = cost
            best_voicing = cand

    return best_voicing


def generate_piano_voicing(
    chord_name: str,
    style: str = "ballad_arpeggio",
    beat_in_bar: int = 1,
    prev_notes: Optional[List[int]] = None
) -> Dict[str, Any]:
    """
    Generates smooth voice-led piano notes to play for the current beat.

    Returns:
        Dict with:
            bass_note: MIDI note number for left hand bass (or None)
            harmony_notes: List of MIDI note numbers for right hand harmony
            duration_beats: Note sustain duration in beats
            sustain: bool
    """
    root_semi, intervals = get_chord_intervals(chord_name)

    # Left hand bass: Octave 2-3 (MIDI 36-47)
    bass_root = 36 + root_semi
    if bass_root < 36:
        bass_root += 12
    bass_fifth = bass_root + 7

    # Right hand pitch classes
    pitch_classes = [(root_semi + iv) % 12 for iv in intervals]
    rh_voiced = _apply_voice_leading(pitch_classes, prev_notes=prev_notes)

    if style == "ballad_arpeggio":
        if beat_in_bar == 1:
            return {
                "bass_note": bass_root,
                "harmony_notes": rh_voiced,
                "duration_beats": 1.6,
                "sustain": True
            }
        elif beat_in_bar == 2:
            return {
                "bass_note": bass_fifth,
                "harmony_notes": [rh_voiced[0]] if rh_voiced else [bass_fifth + 12],
                "duration_beats": 1.1,
                "sustain": True
            }
        elif beat_in_bar == 3:
            mid_n = rh_voiced[1] if len(rh_voiced) > 1 else rh_voiced[0]
            return {
                "bass_note": None,
                "harmony_notes": [mid_n, rh_voiced[-1]],
                "duration_beats": 1.1,
                "sustain": True
            }
        else:
            top_n = rh_voiced[-1] + 12 if rh_voiced[-1] < 72 else rh_voiced[-1]
            return {
                "bass_note": None,
                "harmony_notes": [top_n],
                "duration_beats": 0.95,
                "sustain": True
            }

    elif style == "rhythmic_comp":
        if beat_in_bar in (1, 3):
            return {
                "bass_note": bass_root,
                "harmony_notes": rh_voiced,
                "duration_beats": 0.8,
                "sustain": False
            }
        else:
            return {
                "bass_note": None,
                "harmony_notes": rh_voiced,
                "duration_beats": 0.5,
                "sustain": False
            }

    else:  # "sustained_chords" / ambient
        if beat_in_bar == 1:
            return {
                "bass_note": bass_root,
                "harmony_notes": rh_voiced,
                "duration_beats": 3.8,
                "sustain": True
            }
        else:
            return {
                "bass_note": None,
                "harmony_notes": [],
                "duration_beats": 0.0,
                "sustain": True
            }


def generate_drum_pattern(
    beat_in_bar: int,
    style: str = "ballad_arpeggio",
    velocity: int = 75,
    is_vamping: bool = False
) -> List[Dict[str, Any]]:
    """
    Generates minimalist percussion events (Kick, Rimshot/Brush, Soft Hi-Hat)
    that complement the singer's dynamics without overpowering the vocal.
    """
    hits: List[Dict[str, Any]] = []
    vel_scale = max(0.35, min(1.0, velocity / 110.0))

    if style == "sustained_chords":
        # Ambient: only subtle ride/shaker shimmer on beat 1
        if beat_in_bar == 1:
            hits.append({"note": DRUM_RIDE, "velocity": int(36 * vel_scale)})
        return hits

    if style == "ballad_arpeggio":
        # Minimalist acoustic ballad groove
        if beat_in_bar == 1:
            hits.append({"note": DRUM_KICK, "velocity": int(68 * vel_scale)})
            hits.append({"note": DRUM_CLOSED_HH, "velocity": int(38 * vel_scale)})
        elif beat_in_bar == 2:
            hits.append({"note": DRUM_RIM, "velocity": int(48 * vel_scale)})
            hits.append({"note": DRUM_CLOSED_HH, "velocity": int(34 * vel_scale)})
        elif beat_in_bar == 3:
            if not is_vamping:
                hits.append({"note": DRUM_KICK, "velocity": int(56 * vel_scale)})
            hits.append({"note": DRUM_CLOSED_HH, "velocity": int(38 * vel_scale)})
        else:
            hits.append({"note": DRUM_RIM, "velocity": int(45 * vel_scale)})
            hits.append({"note": DRUM_CLOSED_HH, "velocity": int(34 * vel_scale)})
    else:
        # Rhythmic Pop groove
        if beat_in_bar == 1:
            hits.append({"note": DRUM_KICK, "velocity": int(82 * vel_scale)})
            hits.append({"note": DRUM_CLOSED_HH, "velocity": int(48 * vel_scale)})
        elif beat_in_bar == 2:
            hits.append({"note": DRUM_SNARE_BRUSH, "velocity": int(68 * vel_scale)})
            hits.append({"note": DRUM_CLOSED_HH, "velocity": int(44 * vel_scale)})
        elif beat_in_bar == 3:
            hits.append({"note": DRUM_KICK, "velocity": int(74 * vel_scale)})
            hits.append({"note": DRUM_CLOSED_HH, "velocity": int(48 * vel_scale)})
        else:
            hits.append({"note": DRUM_SNARE_BRUSH, "velocity": int(65 * vel_scale)})
            hits.append({"note": DRUM_CLOSED_HH, "velocity": int(44 * vel_scale)})

    return hits

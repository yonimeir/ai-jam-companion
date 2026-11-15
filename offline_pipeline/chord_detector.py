"""
Chord Detection Module
Extracts musical chords and tempo from audio using:
- Multi-band onset strength & tempogram-guided beat tracking
- Bass-weighted + full-range harmonic CQT chromagrams
- Global key estimation (Krumhansl-Schmuckler profiles)
- Key-aware Viterbi HMM decoding over Major, Minor, and 7th chord templates
"""

from typing import List, Dict, Any, Tuple
import numpy as np
import librosa

NOTE_NAMES = ["C", "C#", "D", "D#", "E", "F", "F#", "G", "G#", "A", "A#", "B"]
NOTE_TO_SEMITONE = {name: i for i, name in enumerate(NOTE_NAMES)}
ALIASES = {"Db": "C#", "Eb": "D#", "Gb": "F#", "Ab": "G#", "Bb": "A#"}

# Krumhansl-Schmuckler key profiles (Major & Minor)
MAJOR_PROFILE = np.array([6.35, 2.23, 3.48, 2.33, 4.38, 4.09, 2.52, 5.19, 2.39, 3.66, 2.29, 2.88], dtype=np.float32)
MINOR_PROFILE = np.array([6.33, 2.68, 3.52, 5.38, 2.60, 3.53, 2.54, 4.75, 3.98, 2.69, 3.34, 3.17], dtype=np.float32)

# Diatonic chord indices (semitone offsets from tonic) for Major and Minor keys
MAJOR_DIATONIC_OFFSETS = {
    0: "maj", 2: "min", 4: "min", 5: "maj", 7: "maj", 9: "min"
}
MINOR_DIATONIC_OFFSETS = {
    0: "min", 3: "maj", 5: "min", 7: "min", 8: "maj", 10: "maj"
}


def create_chord_templates(include_sevenths: bool = False) -> Dict[str, np.ndarray]:
    """
    Creates normalized pitch class profile (PCP) templates for Major and Minor triads
    (and optionally 7th chords).
    """
    templates = {}

    major_base = np.zeros(12, dtype=np.float32)
    major_base[0] = 1.0   # Root
    major_base[4] = 0.8   # Maj 3rd
    major_base[7] = 0.65  # P5

    minor_base = np.zeros(12, dtype=np.float32)
    minor_base[0] = 1.0   # Root
    minor_base[3] = 0.8   # Min 3rd
    minor_base[7] = 0.65  # P5

    dom7_base = np.zeros(12, dtype=np.float32)
    dom7_base[0] = 1.0
    dom7_base[4] = 0.75
    dom7_base[7] = 0.55
    dom7_base[10] = 0.50

    min7_base = np.zeros(12, dtype=np.float32)
    min7_base[0] = 1.0
    min7_base[3] = 0.75
    min7_base[7] = 0.55
    min7_base[10] = 0.50

    for i, note in enumerate(NOTE_NAMES):
        maj_roll = np.roll(major_base, i)
        min_roll = np.roll(minor_base, i)
        templates[f"{note}"] = maj_roll / (np.linalg.norm(maj_roll) + 1e-9)
        templates[f"{note}m"] = min_roll / (np.linalg.norm(min_roll) + 1e-9)
        if include_sevenths:
            d7_roll = np.roll(dom7_base, i)
            m7_roll = np.roll(min7_base, i)
            templates[f"{note}7"] = d7_roll / (np.linalg.norm(d7_roll) + 1e-9)
            templates[f"{note}m7"] = m7_roll / (np.linalg.norm(m7_roll) + 1e-9)

    return templates


CHORD_TEMPLATES = create_chord_templates(include_sevenths=False)


def chord_to_midi_notes(chord_name: str, octave: int = 4) -> List[int]:
    """Convert chord name (e.g., 'Am', 'C', 'G7', 'G#m') to MIDI note numbers."""
    is_7th = chord_name.endswith("7")
    is_maj7 = chord_name.endswith("maj7")
    clean = chord_name.replace("maj7", "").replace("7", "")
    is_minor = clean.endswith("m")
    root_str = clean[:-1] if is_minor else clean
    root_str = ALIASES.get(root_str, root_str)

    if root_str not in NOTE_TO_SEMITONE:
        root_str = "C"

    root_semi = NOTE_TO_SEMITONE[root_str]
    root_midi = 12 * (octave + 1) + root_semi

    if is_minor:
        notes = [root_midi, root_midi + 3, root_midi + 7]
        if is_7th:
            notes.append(root_midi + 10)
    else:
        notes = [root_midi, root_midi + 4, root_midi + 7]
        if is_maj7:
            notes.append(root_midi + 11)
        elif is_7th:
            notes.append(root_midi + 10)

    return notes


def estimate_key(chroma_mean: np.ndarray) -> Tuple[int, str]:
    """
    Estimates global key (tonic_semitone, 'major'|'minor') using Krumhansl-Schmuckler correlation.
    """
    best_corr = -1e9
    best_tonic = 0
    best_mode = "major"

    c_norm = chroma_mean - np.mean(chroma_mean)
    for tonic in range(12):
        maj_p = np.roll(MAJOR_PROFILE, tonic)
        min_p = np.roll(MINOR_PROFILE, tonic)

        maj_corr = float(np.dot(c_norm, maj_p - np.mean(maj_p)))
        min_corr = float(np.dot(c_norm, min_p - np.mean(min_p)))

        if maj_corr > best_corr:
            best_corr = maj_corr
            best_tonic = tonic
            best_mode = "major"
        if min_corr > best_corr:
            best_corr = min_corr
            best_tonic = tonic
            best_mode = "minor"

    return best_tonic, best_mode


def _build_diatonic_prior(template_keys: List[str], tonic: int, mode: str) -> np.ndarray:
    """Builds log-prior bonus for chords diatonic to the detected key."""
    prior = np.zeros(len(template_keys), dtype=np.float64)
    offsets = MAJOR_DIATONIC_OFFSETS if mode == "major" else MINOR_DIATONIC_OFFSETS

    for idx, cname in enumerate(template_keys):
        is_min = cname.endswith("m")
        root_str = cname[:-1] if is_min else cname
        semi = NOTE_TO_SEMITONE.get(root_str, 0)
        rel = (semi - tonic) % 12
        expected_qual = offsets.get(rel)
        if expected_qual == ("min" if is_min else "maj"):
            prior[idx] = 0.08  # Diatonic chord boost
        elif rel in (7, 5) and not is_min:
            prior[idx] = 0.03  # V or IV major borrow (common in minor/pop)
        else:
            prior[idx] = -0.04  # Slight penalty for distant chromatic chords

    return prior


def _viterbi_decode_chords(
    emission_scores: np.ndarray,
    diatonic_prior: np.ndarray,
    stay_bonus: float = 0.25
) -> List[int]:
    """
    Viterbi HMM decoding across beats.
    emission_scores: shape (n_chords, n_beats) cosine similarities in [0, 1].
    """
    n_states, n_steps = emission_scores.shape
    if n_steps == 0:
        return []

    dp = np.zeros((n_states, n_steps), dtype=np.float64)
    backptr = np.zeros((n_states, n_steps), dtype=np.int32)

    dp[:, 0] = emission_scores[:, 0] + diatonic_prior

    for t in range(1, n_steps):
        prev_scores = dp[:, t - 1]
        best_prev_global = int(np.argmax(prev_scores))
        best_prev_val = prev_scores[best_prev_global]

        for s in range(n_states):
            stay_val = prev_scores[s] + stay_bonus
            switch_val = best_prev_val
            if stay_val >= switch_val:
                dp[s, t] = stay_val + emission_scores[s, t] + diatonic_prior[s]
                backptr[s, t] = s
            else:
                dp[s, t] = switch_val + emission_scores[s, t] + diatonic_prior[s]
                backptr[s, t] = best_prev_global

    # Backtrack
    path = [0] * n_steps
    path[-1] = int(np.argmax(dp[:, -1]))
    for t in range(n_steps - 2, -1, -1):
        path[t] = int(backptr[path[t + 1], t + 1])

    return path


def _chunked_onset_strength(
    y: np.ndarray,
    sr: int,
    hop_length: int = 512,
    chunk_frames: int = 860,
    pad_frames: int = 44,
) -> np.ndarray:
    total_frames = 1 + len(y) // hop_length
    if total_frames <= chunk_frames:
        return librosa.onset.onset_strength(y=y, sr=sr, hop_length=hop_length, aggregate=np.median)

    out = np.zeros(total_frames, dtype=np.float32)
    for f_start in range(0, total_frames, chunk_frames):
        f_end = min(total_frames, f_start + chunk_frames)
        win_f_start = max(0, f_end - chunk_frames) if (f_end - f_start) < chunk_frames else f_start
        ctx_f_start = max(0, win_f_start - pad_frames)
        ctx_f_end = min(total_frames, f_end + pad_frames)
        s_start = ctx_f_start * hop_length
        s_end = min(len(y), ctx_f_end * hop_length)
        env = librosa.onset.onset_strength(
            y=y[s_start:s_end], sr=sr, hop_length=hop_length, aggregate=np.median
        )
        off = f_start - ctx_f_start
        n_take = min(f_end - f_start, max(0, len(env) - off))
        if n_take > 0:
            out[f_start:f_start + n_take] = env[off:off + n_take]
        del env
    return out


def estimate_accurate_bpm(y: np.ndarray, sr: int) -> Tuple[float, np.ndarray]:
    """
    Estimates accurate tempo and beat frames using onset envelope autocorrelation
    to avoid default 120 BPM bias on sparse/ballad tracks.
    """
    onset_env = _chunked_onset_strength(y=y, sr=sr)
    # Estimate tempo with broad prior across 55..170 BPM
    tempo_candidates = librosa.feature.tempo(
        onset_envelope=onset_env,
        sr=sr,
        start_bpm=100.0,
        std_bpm=35.0,
        prior=None
    )
    est_bpm = float(tempo_candidates[0] if isinstance(tempo_candidates, np.ndarray) else tempo_candidates)
    if est_bpm < 50.0 or est_bpm > 190.0:
        est_bpm = 100.0

    # Refine beat frames anchored to estimated tempo
    tempo_final, beat_frames = librosa.beat.beat_track(
        onset_envelope=onset_env,
        sr=sr,
        start_bpm=est_bpm,
        tightness=120
    )
    bpm_val = float(tempo_final[0] if isinstance(tempo_final, np.ndarray) and tempo_final.size > 0 else tempo_final)
    if bpm_val <= 0:
        bpm_val = est_bpm

    return bpm_val, beat_frames


def chunked_hpss_harmonic(
    y: np.ndarray,
    sr: int,
    chunk_sec: float = 10.0,
    pad_sec: float = 1.0,
) -> np.ndarray:
    """
    Computes harmonic component via librosa.effects.hpss in bounded time chunks
    to avoid ~800MB peak spectrogram allocations on 3-5 minute songs (512MB RAM limit).
    """
    n = len(y)
    chunk_samples = int(chunk_sec * sr)
    if n <= chunk_samples or chunk_samples <= 0:
        y_h, _ = librosa.effects.hpss(y)
        return y_h.astype(np.float32, copy=False)

    pad_samples = int(pad_sec * sr)
    out = np.empty(n, dtype=np.float32)
    for start in range(0, n, chunk_samples):
        end = min(n, start + chunk_samples)
        ctx_start = max(0, start - pad_samples)
        ctx_end = min(n, end + pad_samples)
        y_h_chunk, _ = librosa.effects.hpss(y[ctx_start:ctx_end])
        left = start - ctx_start
        out[start:end] = y_h_chunk[left:left + (end - start)]
        del y_h_chunk
    return out


def chunked_chroma_cqt(
    y: np.ndarray,
    sr: int,
    hop_length: int = 512,
    n_chroma: int = 12,
    fmin: float = None,
    n_octaves: int = 7,
    chunk_frames: int = 430,
    pad_frames: int = 44,
) -> np.ndarray:
    """
    Computes librosa.feature.chroma_cqt in bounded frame windows (~10s) to keep
    CQT filterbank/spectrogram memory small on long songs.
    """
    if fmin is None:
        fmin = librosa.note_to_hz("C1")
    total_frames = 1 + len(y) // hop_length
    if total_frames <= chunk_frames:
        return librosa.feature.chroma_cqt(
            y=y, sr=sr, hop_length=hop_length, n_chroma=n_chroma, fmin=fmin, n_octaves=n_octaves
        ).astype(np.float32, copy=False)

    out = np.empty((n_chroma, total_frames), dtype=np.float32)
    for f_start in range(0, total_frames, chunk_frames):
        f_end = min(total_frames, f_start + chunk_frames)
        win_f_start = max(0, f_end - chunk_frames) if (f_end - f_start) < chunk_frames else f_start
        ctx_f_start = max(0, win_f_start - pad_frames)
        ctx_f_end = min(total_frames, f_end + pad_frames)
        s_start = ctx_f_start * hop_length
        s_end = min(len(y), ctx_f_end * hop_length)
        c = librosa.feature.chroma_cqt(
            y=y[s_start:s_end],
            sr=sr,
            hop_length=hop_length,
            n_chroma=n_chroma,
            fmin=fmin,
            n_octaves=n_octaves,
        )
        off = f_start - ctx_f_start
        n_take = f_end - f_start
        out[:, f_start:f_end] = c[:, off:off + n_take]
        del c
    return out


def extract_chords_from_audio(
    y: np.ndarray,
    sr: int,
    beats_per_bar: int = 4,
    min_confidence: float = 0.35,
    bar_quantize: bool = True
) -> Tuple[List[Dict[str, Any]], float, np.ndarray]:
    """
    Extracts beat-synchronous chords from audio signal using bass-weighted chroma
    and key-aware Viterbi HMM decoding.

    Returns:
        chord_sequence: List of chord dicts with start_time, end_time, chord, bar, beat, notes
        bpm: Estimated tempo (BPM)
        beat_times: Array of beat times in seconds
    """
    # 1. Harmonic-Percussive separation (chunked for 512MB RAM safety)
    y_harmonic = chunked_hpss_harmonic(y, sr)

    # 2. Accurate Tempo & Beat Tracking
    bpm, beat_frames = estimate_accurate_bpm(y, sr)
    beat_times = librosa.frames_to_time(beat_frames, sr=sr)

    duration = len(y) / sr
    if len(beat_frames) < 4:
        frame_interval = 60.0 / bpm
        beat_times = np.arange(0, duration, frame_interval)
        beat_frames = librosa.time_to_frames(beat_times, sr=sr)

    # Prepend beat 0 if the first detected beat starts late (> 0.35 * beat_interval)
    beat_interval = 60.0 / bpm
    if len(beat_times) > 0 and beat_times[0] > 0.35 * beat_interval:
        prepended = []
        t_cur = beat_times[0] - beat_interval
        while t_cur >= 0.0:
            prepended.append(max(0.0, t_cur))
            t_cur -= beat_interval
        if not prepended or prepended[-1] > 0.05:
            prepended.append(0.0)
        prepended.reverse()
        beat_times = np.concatenate([np.array(prepended), beat_times])
        beat_frames = librosa.time_to_frames(beat_times, sr=sr)

    # 3. Compute Full-Range & Bass-Weighted CQT Chromagrams (chunked)
    chroma_full = chunked_chroma_cqt(
        y=y_harmonic, sr=sr, n_chroma=12, fmin=librosa.note_to_hz("C2"), n_octaves=5
    )
    chroma_bass = chunked_chroma_cqt(
        y=y_harmonic, sr=sr, n_chroma=12, fmin=librosa.note_to_hz("C2"), n_octaves=2
    )
    del y_harmonic
    min_frames = min(chroma_full.shape[1], chroma_bass.shape[1])
    # Combined chroma gives extra weight to bass root notes (helps distinguish C vs Am, F vs Dm)
    chroma_combined = 0.70 * chroma_full[:, :min_frames] + 0.30 * chroma_bass[:, :min_frames]
    del chroma_full, chroma_bass

    # 4. Synchronize chroma with beat frames
    chroma_sync = librosa.util.sync(chroma_combined, beat_frames, aggregate=np.median)
    del chroma_combined
    num_beats = chroma_sync.shape[1]

    # 5. Global key estimation & diatonic prior
    chroma_mean = np.mean(chroma_sync, axis=1)
    tonic, mode = estimate_key(chroma_mean)
    template_keys = list(CHORD_TEMPLATES.keys())
    template_matrix = np.array([CHORD_TEMPLATES[k] for k in template_keys], dtype=np.float64)
    diatonic_prior = _build_diatonic_prior(template_keys, tonic, mode)

    # 6. Compute beat emission scores
    emission_scores = np.zeros((len(template_keys), num_beats), dtype=np.float64)
    for b in range(num_beats):
        vec = chroma_sync[:, b]
        norm = np.linalg.norm(vec)
        if norm > 1e-4:
            emission_scores[:, b] = np.dot(template_matrix, vec / norm)

    # 7. Viterbi HMM Decoding (higher stay_bonus when bar_quantize is on)
    stay_bonus = 0.30 if bar_quantize else 0.18
    decoded_indices = _viterbi_decode_chords(emission_scores, diatonic_prior, stay_bonus=stay_bonus)
    decoded_chords = [template_keys[idx] for idx in decoded_indices]

    # 8. Convert to structured chord events with bar & beat numbers
    chords_list: List[Dict[str, Any]] = []
    total_beats = len(beat_times)

    for i in range(total_beats):
        t_start = float(beat_times[i])
        t_end = float(beat_times[i + 1]) if (i + 1 < total_beats) else float(duration)
        chord_name = decoded_chords[i] if i < len(decoded_chords) else "C"

        bar_idx = (i // beats_per_bar) + 1
        beat_idx = (i % beats_per_bar) + 1

        is_minor = chord_name.endswith("m")
        root_name = chord_name[:-1] if is_minor else chord_name
        quality = "minor" if is_minor else "major"
        midi_notes = chord_to_midi_notes(chord_name, octave=4)

        chords_list.append({
            "chord": chord_name,
            "root": root_name,
            "quality": quality,
            "start_time": round(t_start, 3),
            "end_time": round(t_end, 3),
            "bar": bar_idx,
            "beat": beat_idx,
            "notes": midi_notes
        })

    return chords_list, round(bpm, 1), beat_times

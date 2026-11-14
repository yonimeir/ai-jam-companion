"""
Vocal Pitch and Dynamics Contour Extractor
Extracts continuous F0 (fundamental frequency), voiced/unvoiced masks,
fractional MIDI note values, and RMS energy contour to serve as the reference anchor.
Includes foreground vocal separation via REPET-SIM (Non-Negative Matrix / Nearest-Neighbor
Filtering) so full-mix songs isolate the vocal melody away from repeating piano/bass backing.
"""

from typing import Dict, Any, Optional
import os
import numpy as np
import librosa
import scipy.ndimage


def isolate_foreground_vocals(y: np.ndarray, sr: int) -> np.ndarray:
    """
    Separates non-repeating foreground vocal melody from repeating instrumental backing
    using librosa REPET-SIM (Rafii & Pardo, 2012) and soft spectral masking.
    """
    S_full, phase = librosa.magphase(librosa.stft(y, n_fft=2048, hop_length=512))

    # Nearest-neighbor median filtering across similar frames isolates repeating background
    S_filter = librosa.decompose.nn_filter(
        S_full,
        aggregate=np.median,
        metric="cosine",
        width=int(librosa.time_to_frames(2.0, sr=sr, hop_length=512))
    )
    S_filter = np.minimum(S_full, S_filter)

    # Soft mask favoring foreground (vocals)
    margin_v = 2.0
    power = 2
    mask_v = librosa.util.softmask(
        S_full - S_filter,
        margin_v * S_filter,
        power=power
    )

    # Zero out sub-130Hz bass frequencies in vocal mask to avoid bass/kick bleed
    freqs = librosa.fft_frequencies(sr=sr, n_fft=2048)
    mask_v[freqs < 130.0, :] *= 0.05
    mask_v[freqs > 4200.0, :] *= 0.25

    S_foreground = mask_v * S_full
    y_vocals = librosa.istft(S_foreground * phase, hop_length=512, length=len(y))
    return y_vocals


def extract_pitch_contour(
    y: np.ndarray,
    sr: int,
    fmin: float = 110.0,   # A2 (filters out low piano bass)
    fmax: float = 1046.5,  # C6
    frame_length: int = 2048,
    hop_length: int = 512,
    isolate_vocals: bool = True,
    save_vocals_path: Optional[str] = None
) -> Dict[str, Any]:
    """
    Extracts continuous vocal pitch curve and energy envelope using pYIN (Probabilistic YIN).

    Returns:
        Dict containing:
            times: list of timestamps (seconds)
            f0_hz: list of F0 values in Hz (0.0 when unvoiced)
            midi_notes: list of fractional MIDI notes (0.0 when unvoiced)
            voiced_prob: confidence / voiced probability [0.0, 1.0]
            rms_db: vocal energy level in decibels
    """
    # 1. Foreground vocal isolation (removes repeating piano/drums/bass)
    if isolate_vocals:
        y_vocal = isolate_foreground_vocals(y, sr)
        y_harm, _ = librosa.effects.hpss(y_vocal)
    else:
        y_vocal = y
        y_harm, _ = librosa.effects.hpss(y)

    if save_vocals_path:
        import soundfile as sf
        os.makedirs(os.path.dirname(os.path.abspath(save_vocals_path)), exist_ok=True)
        peak = float(np.max(np.abs(y_vocal))) if len(y_vocal) > 0 else 0.0
        y_save = (y_vocal / peak * 0.9) if peak > 1e-6 else y_vocal
        sf.write(save_vocals_path, y_save.astype(np.float32), sr)

    # 2. Extract F0 using pYIN
    f0, voiced_flag, voiced_probs = librosa.pyin(
        y_harm,
        fmin=fmin,
        fmax=fmax,
        sr=sr,
        frame_length=frame_length,
        hop_length=hop_length,
        fill_na=0.0
    )

    times = librosa.times_like(f0, sr=sr, hop_length=hop_length)

    # 3. Compute RMS energy envelope on isolated vocal signal
    rms = librosa.feature.rms(y=y_harm, frame_length=frame_length, hop_length=hop_length)[0]
    rms_db = 20.0 * np.log10(np.maximum(rms, 1e-5))

    # Gate very quiet bleed as unvoiced (e.g. residual accompaniment during instrumental intro)
    silence_gate_db = np.max(rms_db) - 32.0 if len(rms_db) > 0 else -50.0
    silence_gate_db = max(silence_gate_db, -52.0)

    # 4. Convert F0 to fractional MIDI notes & clean outliers
    midi_notes = np.zeros_like(f0)
    voiced_probs_clean = np.nan_to_num(voiced_probs, nan=0.0)

    for i in range(len(f0)):
        if f0[i] > 0.0 and voiced_probs_clean[i] >= 0.25 and rms_db[i] >= silence_gate_db:
            midi_notes[i] = 69.0 + 12.0 * np.log2(f0[i] / 440.0)
        else:
            f0[i] = 0.0
            voiced_probs_clean[i] = 0.0

    # Median smoothing over 3 voiced frames to suppress single-frame octave glitches
    if len(midi_notes) >= 5:
        voiced_idx = midi_notes > 0
        if np.sum(voiced_idx) >= 5:
            smoothed = scipy.ndimage.median_filter(midi_notes, size=3)
            # Only apply where both original and neighbor are voiced
            midi_notes[voiced_idx] = np.where(smoothed[voiced_idx] > 0, smoothed[voiced_idx], midi_notes[voiced_idx])

    times_list = [round(float(t), 3) for t in times]
    f0_list = [round(float(f), 1) if f > 0 else 0.0 for f in f0]
    midi_list = [round(float(m), 2) if m > 0 else 0.0 for m in midi_notes]
    voiced_probs_list = [round(float(p), 3) for p in voiced_probs_clean]
    rms_db_list = [round(float(d), 1) for d in rms_db]

    return {
        "frame_count": len(times),
        "hop_length": hop_length,
        "sample_rate": sr,
        "times": times_list,
        "f0_hz": f0_list,
        "midi_notes": midi_list,
        "voiced_prob": voiced_probs_list,
        "rms_db": rms_db_list
    }

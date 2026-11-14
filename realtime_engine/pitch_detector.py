"""
Real-Time Pitch and Dynamics Detector
Implements ultra-fast vectorized FFT-YIN pitch detection with parabolic interpolation
and RMS energy calculation for low-latency microphone / stream processing.
"""

from dataclasses import dataclass
import numpy as np


@dataclass
class PitchFrame:
    f0_hz: float
    midi_note: float
    confidence: float
    rms_db: float
    is_voiced: bool


class RealtimePitchDetector:
    def __init__(
        self,
        sample_rate: int = 22050,
        fmin: float = 65.0,    # ~C2
        fmax: float = 1050.0,  # ~C6
        yin_threshold: float = 0.18,
        min_rms_db: float = -42.0
    ):
        self.sr = sample_rate
        self.fmin = fmin
        self.fmax = fmax
        self.yin_threshold = yin_threshold
        self.min_rms_db = min_rms_db

        # Tau search bounds (lag in samples)
        self.min_tau = max(2, int(np.floor(sample_rate / fmax)))
        self.max_tau = int(np.ceil(sample_rate / fmin))

    def process_chunk(self, audio_chunk: np.ndarray) -> PitchFrame:
        """
        Process a single audio buffer (recommended 1024 or 2048 samples at 22050 Hz).
        Uses vectorized FFT cross-correlation for <0.5ms execution per frame.
        """
        x = np.asarray(audio_chunk, dtype=np.float64)
        N = len(x)
        if N <= self.max_tau + 16:
            return PitchFrame(0.0, 0.0, 0.0, -100.0, False)

        # 1. Remove DC offset & compute RMS energy
        x = x - np.mean(x)
        rms = float(np.sqrt(np.mean(x ** 2)))
        rms_db = float(20.0 * np.log10(max(rms, 1e-5)))

        if rms_db < self.min_rms_db:
            return PitchFrame(0.0, 0.0, 0.0, rms_db, False)

        # 2. Vectorized YIN difference function via FFT
        W = N - self.max_tau
        fft_len = 1 << int(np.ceil(np.log2(N + W)))

        X_w = np.fft.rfft(x[:W], n=fft_len)
        X_full = np.fft.rfft(x, n=fft_len)
        cross_corr = np.fft.irfft(np.conj(X_w) * X_full, n=fft_len)[: self.max_tau + 1]

        cum_sq = np.concatenate([[0.0], np.cumsum(x ** 2)])
        e0 = cum_sq[W]
        taus = np.arange(self.max_tau + 1)
        e_tau = cum_sq[W + taus] - cum_sq[taus]

        diff = np.maximum(0.0, e0 + e_tau - 2.0 * cross_corr)

        # 3. Cumulative mean normalized difference function (CMNDF)
        cmndf = np.ones(self.max_tau + 1, dtype=np.float64)
        running_sum = np.cumsum(diff[1:])
        tau_idx = np.arange(1, self.max_tau + 1, dtype=np.float64)
        valid_sum = running_sum > 1e-12
        cmndf[1:][valid_sum] = diff[1:][valid_sum] / (running_sum[valid_sum] / tau_idx[valid_sum])

        # 4. Absolute thresholding: first local minimum below yin_threshold
        best_tau = 0
        sub_cmndf = cmndf[self.min_tau : self.max_tau]
        below = np.where(sub_cmndf < self.yin_threshold)[0]
        if len(below) > 0:
            tau = int(below[0]) + self.min_tau
            while (tau + 1 < self.max_tau) and (cmndf[tau + 1] < cmndf[tau]):
                tau += 1
            best_tau = tau
        else:
            if len(sub_cmndf) > 0:
                best_tau = int(np.argmin(sub_cmndf)) + self.min_tau

        if best_tau <= 0:
            return PitchFrame(0.0, 0.0, 0.0, rms_db, False)

        min_cmndf = float(cmndf[best_tau])

        # 5. Parabolic interpolation for sub-sample accuracy
        refined_tau = float(best_tau)
        if 0 < best_tau < len(cmndf) - 1:
            alpha = cmndf[best_tau - 1]
            beta = cmndf[best_tau]
            gamma = cmndf[best_tau + 1]
            denom = 2.0 * (alpha - 2.0 * beta + gamma)
            if abs(denom) > 1e-9:
                delta = np.clip((alpha - gamma) / denom, -0.5, 0.5)
                refined_tau += float(delta)

        f0 = float(self.sr / refined_tau)
        confidence = float(np.clip(1.0 - min_cmndf, 0.0, 1.0))

        is_voiced = (confidence >= 0.55) and (self.fmin <= f0 <= self.fmax)
        if not is_voiced:
            return PitchFrame(0.0, 0.0, round(confidence, 3), round(rms_db, 1), False)

        midi_note = float(69.0 + 12.0 * np.log2(f0 / 440.0))
        return PitchFrame(
            f0_hz=round(f0, 2),
            midi_note=round(midi_note, 2),
            confidence=round(confidence, 3),
            rms_db=round(rms_db, 1),
            is_voiced=True
        )

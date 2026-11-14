"""
Online Dynamic Time Warping (ODTW) Score Follower
Aligns live singer audio frames against the reference song map in real time.
Handles instrumental intros, pauses (holds position during Vamps), rubato
(tempo fluctuations), octave transpositions, and global jump/re-sync recovery.
"""

from typing import Dict, Any, Tuple, List
import numpy as np


class OnlineScoreFollower:
    def __init__(
        self,
        song_map: Dict[str, Any],
        window_size_frames: int = 120,  # ~2.8 seconds lookahead search window
        step_penalty: float = 0.0,
        stay_penalty: float = 0.06,
        skip_penalty: float = 0.03
    ):
        self.song_map = song_map
        vocal = song_map.get("vocal_anchor", {})
        self.ref_times = np.array(vocal.get("times", []), dtype=np.float64)
        self.ref_pitches = np.array(vocal.get("midi_notes", []), dtype=np.float64)
        self.ref_voiced = np.array(vocal.get("voiced_prob", []), dtype=np.float64)
        self.total_frames = len(self.ref_pitches)

        self.duration = float(song_map.get("metadata", {}).get("duration", 10.0))
        self.bpm = float(song_map.get("metadata", {}).get("bpm", 120.0))

        if len(self.ref_times) > 1:
            self.ref_dt = float(np.median(np.diff(self.ref_times)))
        else:
            self.ref_dt = 512.0 / 22050.0

        self.window_size = window_size_frames
        self.step_penalty = step_penalty
        self.stay_penalty = stay_penalty
        self.skip_penalty = skip_penalty

        voiced_indices = np.where((self.ref_voiced > 0.35) & (self.ref_pitches > 0))[0]
        self.first_vocal_frame = int(voiced_indices[0]) if len(voiced_indices) > 0 else 0

        # State tracking
        self.current_frame = 0
        self.estimated_tempo_ratio = 1.0
        self.accumulated_costs = np.zeros(self.total_frames, dtype=np.float64)
        self.confidence = 0.5
        self.is_active = False
        self._has_locked_vocal = False
        self._frame_history: List[int] = []
        self._recent_voiced_pitches: List[float] = []
        self._mismatch_streak = 0

        self.reset()

    def reset(self):
        """Reset score follower to the start of the song."""
        self.current_frame = 0
        self.estimated_tempo_ratio = 1.0
        self.accumulated_costs = np.full(self.total_frames, 100.0, dtype=np.float64)
        if self.total_frames > 0:
            self.accumulated_costs[0] = 0.0
        self.confidence = 0.5
        self.is_active = True
        self._has_locked_vocal = False
        self._frame_history = []
        self._recent_voiced_pitches = []
        self._mismatch_streak = 0

    def _frame_costs_vectorized(self, live_midi: float, live_voiced: bool, w_start: int, w_end: int) -> np.ndarray:
        """Vectorized local dissimilarity across the search window [w_start, w_end)."""
        ref_p = self.ref_pitches[w_start:w_end]
        ref_v = (self.ref_voiced[w_start:w_end] > 0.35) & (ref_p > 0)

        if not live_voiced:
            return np.where(ref_v, 0.35, 0.05)

        costs = np.full(w_end - w_start, 0.78, dtype=np.float64)
        if np.any(ref_v):
            diff = np.abs(live_midi - ref_p[ref_v])
            mod12 = diff % 12.0
            octave_diff = np.minimum(mod12, 12.0 - mod12)
            best_diff = np.minimum(diff, octave_diff + 1.2)
            costs[ref_v] = 1.0 - np.exp(-((best_diff / 2.2) ** 2))
        return costs

    def _try_vocal_entry_lock(self, live_midi: float):
        """Locks onto the first vocal phrase when the track begins with an instrumental intro."""
        if self.total_frames == 0:
            return
        search_start = max(0, self.first_vocal_frame - 15)
        search_end = min(self.total_frames, self.first_vocal_frame + 120)
        if search_end <= search_start:
            return

        ref_p = self.ref_pitches[search_start:search_end]
        ref_v = (self.ref_voiced[search_start:search_end] > 0.35) & (ref_p > 0)
        if not np.any(ref_v):
            return

        diffs = np.full(search_end - search_start, 99.0)
        diffs[ref_v] = np.abs(live_midi - ref_p[ref_v])
        best_offset = int(np.argmin(diffs))
        if diffs[best_offset] <= 2.5:
            anchor = search_start + best_offset
            self.current_frame = anchor
            self.accumulated_costs.fill(50.0)
            self.accumulated_costs[anchor] = 0.0
            self._has_locked_vocal = True

    def _try_global_resync(self) -> bool:
        """
        Matches recent trailing voiced pitch contour against the forward portion of the song
        to recover cleanly if the singer skipped ahead (e.g. Verse -> Chorus) or resumed late.
        """
        seq = np.array(self._recent_voiced_pitches, dtype=np.float64)
        m = len(seq)
        if m < 8 or self.total_frames <= m + 20:
            return False

        best_idx = -1
        best_score = 999.0
        best_raw_err = 999.0
        # Search within a bounded forward window (~14 seconds) preferring nearest strong match
        max_end = min(self.total_frames - m, self.current_frame + 600)
        for start in range(self.current_frame, max_end, 2):
            ref_slice = self.ref_pitches[start : start + m]
            ref_v = (self.ref_voiced[start : start + m] > 0.35) & (ref_slice > 0)
            if np.sum(ref_v) < m * 0.65:
                continue
            diff = np.abs(seq[ref_v] - ref_slice[ref_v])
            mod12 = diff % 12.0
            oct_diff = np.minimum(mod12, 12.0 - mod12)
            raw_err = float(np.mean(np.minimum(diff, oct_diff + 1.0)))
            score = raw_err + 0.0025 * (start - self.current_frame)
            if score < best_score:
                best_score = score
                best_raw_err = raw_err
                best_idx = start + m - 1

        if best_idx >= 0 and best_raw_err < 1.3:
            self.current_frame = best_idx
            self.accumulated_costs.fill(50.0)
            self.accumulated_costs[best_idx] = 0.0
            self._mismatch_streak = 0
            return True
        return False

    def step(self, live_midi: float, live_confidence: float, live_is_voiced: bool) -> Tuple[int, float, float]:
        """
        Processes one live audio frame (~23.2ms) and updates the current aligned position.
        """
        if self.total_frames == 0:
            return 0, 0.0, 0.0

        if live_is_voiced:
            self._recent_voiced_pitches.append(live_midi)
            if len(self._recent_voiced_pitches) > 16:
                self._recent_voiced_pitches.pop(0)

            if not self._has_locked_vocal:
                if self.current_frame < self.first_vocal_frame - 10:
                    self._try_vocal_entry_lock(live_midi)
                else:
                    self._has_locked_vocal = True

        w_start = max(0, self.current_frame - 8)
        w_end = min(self.total_frames, self.current_frame + self.window_size)

        local_costs = self._frame_costs_vectorized(live_midi, live_is_voiced, w_start, w_end)

        # During pauses/unvoiced frames, hold position strongly (stay_penalty=0) instead of drifting forward
        eff_stay = 0.0 if not live_is_voiced else self.stay_penalty
        eff_step = 0.12 if not live_is_voiced else self.step_penalty
        eff_skip = 0.30 if not live_is_voiced else self.skip_penalty

        prev = self.accumulated_costs
        new_win = np.empty(w_end - w_start, dtype=np.float64)

        for idx, j in enumerate(range(w_start, w_end)):
            prev_stay = prev[j] + eff_stay
            prev_step = (prev[j - 1] + eff_step) if j > 0 else 1e5
            prev_skip = (prev[j - 2] + eff_skip) if j > 1 else 1e5
            prev_leap = (prev[j - 3] + eff_skip * 3.0) if j > 2 else 1e5
            new_win[idx] = local_costs[idx] + min(prev_stay, prev_step, prev_skip, prev_leap)

        min_cost = float(np.min(new_win))
        new_win -= min_cost

        self.accumulated_costs.fill(1e4)
        self.accumulated_costs[w_start:w_end] = new_win

        best_j = int(w_start + np.argmin(new_win))
        best_local_cost = float(local_costs[best_j - w_start])

        # Global resync check if sustained voiced mismatch (singer skipped/jumped ahead)
        if live_is_voiced:
            if best_local_cost > 0.55:
                self._mismatch_streak += 1
                if self._mismatch_streak >= 25:
                    if self._try_global_resync():
                        best_j = self.current_frame
                        best_local_cost = 0.1
            else:
                self._mismatch_streak = 0

        if len(new_win) > 2:
            spread = float(np.mean(new_win) - np.min(new_win))
            sharpness_conf = 1.0 / (1.0 + np.exp(-2.0 * (spread - 1.2)))
        else:
            sharpness_conf = 0.5

        match_conf = float(1.0 - best_local_cost)
        raw_conf = 0.6 * sharpness_conf + 0.4 * match_conf

        if live_is_voiced:
            self.confidence = 0.80 * self.confidence + 0.20 * raw_conf
            self._frame_history.append(best_j)
            if len(self._frame_history) > 40:
                self._frame_history.pop(0)
                delta_ref = self._frame_history[-1] - self._frame_history[0]
                raw_ratio = delta_ref / max(1, len(self._frame_history) - 1)
                if 0.3 <= raw_ratio <= 2.2:
                    self.estimated_tempo_ratio = 0.9 * self.estimated_tempo_ratio + 0.1 * raw_ratio
        else:
            self.confidence = max(0.35, self.confidence * 0.985)

        self.current_frame = best_j
        ref_time = float(self.ref_times[best_j]) if best_j < len(self.ref_times) else self.duration

        return best_j, ref_time, round(float(np.clip(self.confidence, 0.0, 1.0)), 3)

    def jump_to_time(self, target_time: float):
        """Manually or programmatically re-anchor score follower to a timestamp."""
        if self.total_frames == 0:
            return
        idx = int(np.searchsorted(self.ref_times, target_time))
        idx = max(0, min(self.total_frames - 1, idx))
        self.current_frame = idx
        self.accumulated_costs.fill(50.0)
        self.accumulated_costs[idx] = 0.0
        self._has_locked_vocal = True
        self._mismatch_streak = 0

    def get_progress_percent(self) -> float:
        """Returns song progress [0.0, 100.0]%."""
        if self.total_frames <= 1:
            return 0.0
        return round((self.current_frame / (self.total_frames - 1)) * 100.0, 1)

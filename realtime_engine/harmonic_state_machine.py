"""
Harmonic State Machine (AI Jam Companion - Stage C)
Manages chord state, transitions, confidence thresholding, and Vamp looping
during pauses, breath stops, sustained notes, or vocal improvisations.
"""

from enum import Enum
from typing import Dict, Any, List, Optional
from dataclasses import dataclass

class EngineState(str, Enum):
    PLAYING = "PLAYING"                 # Normal synchronous advancing
    VAMPING = "VAMPING"                 # Looping current chord during pauses/holds
    CONFIDENCE_WAIT = "CONFIDENCE_WAIT" # Candidate transition pending confidence confirmation

@dataclass
class HarmonicState:
    chord: str
    root: str
    quality: str
    bar: int
    beat: int
    section: str
    notes: List[int]
    engine_state: EngineState
    confidence: float
    vamp_cycle_beat: int
    transition_occurred: bool

class HarmonicStateMachine:
    def __init__(
        self,
        song_map: Dict[str, Any],
        confidence_threshold: float = 0.58,
        min_confirm_frames: int = 2,
        pause_to_vamp_sec: float = 0.5
    ):
        self.song_map = song_map
        self.chords = song_map.get("chords", [])
        self.sections = song_map.get("sections", [])
        self.bpm = float(song_map.get("metadata", {}).get("bpm", 120.0))
        self.beat_duration = 60.0 / self.bpm
        
        self.confidence_threshold = confidence_threshold
        self.min_confirm_frames = min_confirm_frames
        self.pause_to_vamp_sec = pause_to_vamp_sec
        
        # State registers
        self.current_chord_idx = 0
        self.candidate_chord_idx = 0
        self.confirm_count = 0
        self.engine_state = EngineState.PLAYING
        
        # Vamp tracking
        self.unvoiced_duration = 0.0
        self.vamp_beat = 1
        self.vamp_timer = 0.0
        
        # Fallback default chord
        if not self.chords:
            self.chords = [{
                "chord": "C", "root": "C", "quality": "major",
                "start_time": 0.0, "end_time": 4.0, "bar": 1, "beat": 1,
                "notes": [60, 64, 67]
            }]

    def _find_chord_index_at_time(self, ref_time: float) -> int:
        """Finds chord index matching reference time."""
        for i, c in enumerate(self.chords):
            if c["start_time"] <= ref_time < c["end_time"]:
                return i
        if ref_time >= self.chords[-1]["end_time"]:
            return len(self.chords) - 1
        return 0

    def _get_section_for_bar(self, bar: int) -> str:
        for s in self.sections:
            if s["start_bar"] <= bar <= s["end_bar"]:
                return s.get("label", s["name"])
        return "Verse"

    def update(
        self,
        ref_time: float,
        confidence: float,
        is_voiced: bool,
        dt: float = 0.023
    ) -> HarmonicState:
        """
        Processes one alignment time step.
        
        Args:
            ref_time: Aligned song position in seconds
            confidence: Online DTW confidence [0.0, 1.0]
            is_voiced: Whether live singer is actively singing
            dt: Time delta of this audio step (e.g. hop length / sr)
        """
        transition_occurred = False
        
        # 1. Update pause / unvoiced accumulator for Vamp detection
        if not is_voiced:
            self.unvoiced_duration += dt
        else:
            self.unvoiced_duration = 0.0
            
        # 2. Determine target chord based on aligned position
        target_idx = self._find_chord_index_at_time(ref_time)
        
        # 3. Check for Vamp trigger (pause or held sustained state)
        if self.unvoiced_duration >= self.pause_to_vamp_sec:
            self.engine_state = EngineState.VAMPING
            self.vamp_timer += dt
            if self.vamp_timer >= self.beat_duration:
                self.vamp_timer -= self.beat_duration
                self.vamp_beat = (self.vamp_beat % 4) + 1
        else:
            # Active singing: check transitions
            if target_idx != self.current_chord_idx:
                if target_idx == self.candidate_chord_idx:
                    self.confirm_count += 1
                else:
                    self.candidate_chord_idx = target_idx
                    self.confirm_count = 1
                    
                # Evaluate confidence threshold
                if confidence >= self.confidence_threshold and self.confirm_count >= self.min_confirm_frames:
                    # Confident transition!
                    self.current_chord_idx = target_idx
                    self.engine_state = EngineState.PLAYING
                    self.confirm_count = 0
                    self.vamp_beat = 1
                    transition_occurred = True
                else:
                    self.engine_state = EngineState.CONFIDENCE_WAIT
            else:
                self.confirm_count = 0
                if self.engine_state != EngineState.VAMPING:
                    self.engine_state = EngineState.PLAYING
                    
        curr = self.chords[self.current_chord_idx]
        section = self._get_section_for_bar(curr["bar"])
        
        return HarmonicState(
            chord=curr["chord"],
            root=curr["root"],
            quality=curr["quality"],
            bar=curr["bar"],
            beat=curr["beat"],
            section=section,
            notes=curr["notes"],
            engine_state=self.engine_state,
            confidence=round(confidence, 3),
            vamp_cycle_beat=self.vamp_beat,
            transition_occurred=transition_occurred
        )

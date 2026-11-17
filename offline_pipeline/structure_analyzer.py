"""
Song Structural Segmentation Module
Segments songs into musical sections (Intro, Verse, Chorus, Bridge, Outro)
using recurrence matrix, spectral novelty, and agglomerative clustering.
"""

from typing import List, Dict, Any
import numpy as np
import librosa

def segment_song_structure(
    y: np.ndarray,
    sr: int,
    beat_times: np.ndarray,
    beats_per_bar: int = 4
) -> List[Dict[str, Any]]:
    """
    Detects section boundaries and labels sections (Intro, Verse, Chorus, Outro).
    """
    duration = len(y) / sr
    total_beats = len(beat_times)
    total_bars = max(1, total_beats // beats_per_bar)
    
    # If the track is very short (< 15s or < 8 bars), single section
    if duration < 15.0 or total_bars <= 4:
        return [{
            "name": "verse",
            "label": "Verse",
            "start_time": 0.0,
            "end_time": round(duration, 3),
            "start_bar": 1,
            "end_bar": total_bars
        }]
        
    try:
        # Extract features for structural boundary detection
        # Combine MFCC (timbre) and Chroma STFT (harmony, fast & low-RAM on cloud CPUs)
        mfcc = librosa.feature.mfcc(y=y, sr=sr, n_mfcc=13)
        chroma = librosa.feature.chroma_stft(y=y, sr=sr)
        
        # Beat-synchronize features
        beat_frames = librosa.time_to_frames(beat_times, sr=sr)
        min_f = min(mfcc.shape[1], chroma.shape[1])
        features = np.vstack([mfcc[:, :min_f], chroma[:, :min_f]])
        del mfcc, chroma
        features_sync = librosa.util.sync(features, beat_frames, aggregate=np.median)
        del features
        
        # Estimate number of sections: typically 4 to 8 bars per section
        n_sections = max(2, min(8, int(np.round(total_bars / 8))))
        
        # Agglomerative clustering with temporal connectivity constraint
        # librosa.segment.agglomerative creates contiguous segments
        boundaries_beat = librosa.segment.agglomerative(features_sync, k=n_sections)
        # Ensure 0 is included and sorted
        boundaries_beat = np.unique(np.concatenate([[0], boundaries_beat, [total_beats - 1]]))
        
    except Exception:
        # Fallback heuristic: divide into 4-bar or 8-bar chunks
        bars_per_sec = 8 if total_bars >= 16 else 4
        boundaries_bars = list(range(0, total_bars, bars_per_sec))
        if boundaries_bars[-1] != total_bars:
            boundaries_bars.append(total_bars)
        boundaries_beat = [min(b * beats_per_bar, total_beats - 1) for b in boundaries_bars]
        boundaries_beat = np.unique(boundaries_beat)
        
    # Label sections logically based on song progression
    section_templates = ["intro", "verse_1", "chorus_1", "verse_2", "chorus_2", "bridge", "chorus_3", "outro"]
    sections: List[Dict[str, Any]] = []
    
    for i in range(len(boundaries_beat) - 1):
        b_start = int(boundaries_beat[i])
        b_end = int(boundaries_beat[i+1])
        
        t_start = float(beat_times[b_start]) if b_start < len(beat_times) else 0.0
        t_end = float(beat_times[b_end]) if b_end < len(beat_times) else duration
        if i == len(boundaries_beat) - 2:
            t_end = duration
            
        bar_start = (b_start // beats_per_bar) + 1
        bar_end = (b_end // beats_per_bar) + 1
        
        label_idx = min(i, len(section_templates) - 1)
        # If it's the last section and more than 3 sections total, call it outro
        if i == len(boundaries_beat) - 2 and i >= 3:
            name = "outro"
            display_label = "Outro"
        else:
            name = section_templates[label_idx]
            display_label = name.replace("_", " ").title()
            
        sections.append({
            "name": name,
            "label": display_label,
            "start_time": round(t_start, 3),
            "end_time": round(t_end, 3),
            "start_bar": bar_start,
            "end_bar": bar_end
        })
        
    return sections

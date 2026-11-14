#!/usr/bin/env python3
"""
AI Jam Companion - Real-Time CLI Simulator & Alignment Runner
Streams audio (or live microphone / test tracks) in low-latency chunks,
performs real-time pitch detection, dynamic DTW score following,
harmonic state machine transitions, and adaptive piano MIDI/audio generation.
"""

import argparse
import json
import os
import sys
import time
from typing import Dict, Any, Optional

# Ensure project root is in sys.path
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

import numpy as np
import soundfile as sf

from realtime_engine.tracker import RealtimeJamTracker
from synthesis.piano_synth import AcousticPianoRenderer

# ANSI Color codes for rich terminal dashboard
RESET = "\033[0m"
BOLD = "\033[1m"
GREEN = "\033[92m"
YELLOW = "\033[93m"
CYAN = "\033[96m"
MAGENTA = "\033[95m"
RED = "\033[91m"
BLUE = "\033[94m"
DIM = "\033[2m"

def render_progress_bar(pct: float, width: int = 24) -> str:
    filled = int(width * (pct / 100.0))
    bar = "█" * filled + "░" * (width - filled)
    return f"{CYAN}[{bar}]{RESET} {pct:5.1f}%"

def render_confidence_bar(conf: float, width: int = 12) -> str:
    filled = int(width * conf)
    bar = "█" * filled + "░" * (width - filled)
    color = GREEN if conf >= 0.6 else (YELLOW if conf >= 0.4 else RED)
    return f"{color}[{bar}] {int(conf * 100)}%{RESET}"

def print_dashboard_line(ev: Dict[str, Any], total_bars: int):
    """Prints a structured live status line."""
    state_str = ev["engine_state"]
    if state_str == "PLAYING":
        state_badge = f"{GREEN}{BOLD}[ PLAYING ]{RESET}"
    elif state_str == "VAMPING":
        state_badge = f"{YELLOW}{BOLD}[ VAMPING (PAUSE) ]{RESET}"
    else:
        state_badge = f"{MAGENTA}{BOLD}[ WAIT_CONF ]{RESET}"
        
    chord_badge = f"{BOLD}{BLUE}[ {ev['chord']:^4} ]{RESET}"
    sec_badge = f"{DIM}{ev['section']:<9}{RESET}"
    bar_str = f"Bar {ev['bar']:02d}/{total_bars:02d} (b{ev['beat']})"
    conf_str = render_confidence_bar(ev["confidence"])
    prog_str = render_progress_bar(ev["progress_pct"])
    
    singer_info = f"Pitch: {CYAN}{ev['singer_note']:<6}{RESET} ({ev['singer_f0']:5.1f}Hz) | Vol: {ev['singer_rms_db']:5.1f}dB"
    tempo_str = f"{ev.get('tempo_ratio', 1.0):.2f}x"
    piano_info = f"Vel: {BOLD}{ev['piano_velocity']:3d}{RESET} | Spd: {tempo_str}"

    triggers = ""
    if ev.get("triggered_notes"):
        triggers = f" | {BOLD}🎹 {ev['triggered_notes']}{RESET}"

    line = f"\r{state_badge} {chord_badge} {sec_badge} {bar_str} | {prog_str} | {conf_str} | {singer_info} | {piano_info}{triggers}"
    sys.stdout.write(line)
    sys.stdout.flush()


def run_simulation(
    song_map_path: str,
    input_audio_path: Optional[str] = None,
    output_midi_path: str = "output_jam.mid",
    output_wav_path: Optional[str] = "output_jam.wav",
    speed_factor: float = 1.0,
    insert_pause_at_sec: Optional[float] = None,
    pause_duration_sec: float = 3.0,
    chunk_size: int = 1024,
    realtime_delay: bool = True,
    accompaniment_style: str = "ballad_arpeggio",
    include_drums: bool = False
):
    print(f"\n{BOLD}{CYAN}=== AI Jam Companion Real-Time Simulator ==={RESET}")
    print(f"Loading song map: {song_map_path}")

    with open(song_map_path, "r", encoding="utf-8") as f:
        song_map = json.load(f)

    meta = song_map.get("metadata", {})
    sr = int(meta.get("sample_rate", 22050))
    bpm = float(meta.get("bpm", 120.0))
    total_bars = int(meta.get("total_bars", 16))

    print(f"Song: {BOLD}{meta.get('title')}{RESET} by {meta.get('artist')}")
    print(f"Tempo: {bpm} BPM | Bars: {total_bars} | Sample Rate: {sr} Hz | Style: {accompaniment_style} | Drums: {include_drums}")

    if not input_audio_path:
        default_vocals = "/home/antigravity/ai_jam_companion/audio_samples/demo_ballad_vocals.wav"
        if os.path.exists(default_vocals):
            input_audio_path = default_vocals
        else:
            raise FileNotFoundError("No input audio provided and demo_ballad_vocals.wav not found.")

    print(f"Streaming singer audio from: {input_audio_path}")
    if speed_factor != 1.0:
        print(f"Singer tempo elasticity: {speed_factor:.2f}x speed")
    if insert_pause_at_sec:
        print(f"Simulating singer breath pause at t={insert_pause_at_sec:.1f}s for {pause_duration_sec:.1f}s (Testing VAMP!)")

    y_singer, in_sr = sf.read(input_audio_path)
    if y_singer.ndim > 1:
        y_singer = np.mean(y_singer, axis=1)
    if in_sr != sr:
        import librosa
        y_singer = librosa.resample(y_singer, orig_sr=in_sr, target_sr=sr)

    if speed_factor != 1.0:
        import librosa
        y_singer = librosa.effects.time_stretch(y_singer, rate=speed_factor)

    tracker = RealtimeJamTracker(
        song_map=song_map,
        sample_rate=sr,
        confidence_threshold=0.55,
        accompaniment_style=accompaniment_style,
        include_drums=include_drums
    )

    total_samples = len(y_singer)
    dt_chunk = chunk_size / sr

    print(f"\n{BOLD}Starting Real-Time Accompaniment Loop (Press Ctrl+C to abort)...{RESET}\n")

    pos = 0
    last_print = 0.0
    vamp_events = 0
    total_chunks = 0
    pause_remaining_samples = 0
    pause_triggered = False

    try:
        while pos < total_samples:
            chunk_start_time = time.time()
            current_sec = pos / sr

            if insert_pause_at_sec and not pause_triggered and current_sec >= insert_pause_at_sec:
                pause_triggered = True
                pause_remaining_samples = int(pause_duration_sec * sr)

            if pause_remaining_samples > 0:
                chunk = np.zeros(chunk_size, dtype=np.float32)
                pause_remaining_samples -= chunk_size
            else:
                chunk = y_singer[pos:pos + chunk_size]
                if len(chunk) < chunk_size:
                    chunk = np.pad(chunk, (0, chunk_size - len(chunk)))
                pos += chunk_size

            event = tracker.process_audio_buffer(chunk)
            total_chunks += 1
            if event.get("engine_state") == "VAMPING":
                vamp_events += 1

            if (time.time() - last_print) > 0.06:
                print_dashboard_line(event, total_bars)
                last_print = time.time()

            if realtime_delay:
                elapsed = time.time() - chunk_start_time
                sleep_time = dt_chunk - elapsed
                if sleep_time > 0:
                    time.sleep(sleep_time)

        print(f"\n\n{GREEN}{BOLD}[✓] Stream finished successfully!{RESET}")

    except KeyboardInterrupt:
        print(f"\n\n{YELLOW}[!] Stream stopped by user.{RESET}")

    tracker.midi_builder.export_midi(output_midi_path)
    print(f"{GREEN}[✓] Exported adaptive MIDI track: {output_midi_path}{RESET}")

    if output_wav_path:
        print("[*] Rendering acoustic piano audio track via AcousticPianoRenderer...")
        renderer = AcousticPianoRenderer(sample_rate=sr)
        renderer.render_accompaniment_track(
            notes_timeline=tracker.scheduled_timeline,
            bpm=bpm,
            output_wav_path=output_wav_path
        )
        print(f"{GREEN}[✓] Exported audio accompaniment: {output_wav_path}{RESET}")

    print(f"\n{BOLD}Summary Statistics:{RESET}")
    print(f"- Processed chunks: {total_chunks}")
    print(f"- VAMP cycles handled: {vamp_events} chunks")
    print(f"- Final aligned progress: {tracker.score_follower.get_progress_percent():.1f}%")
    print(f"- Estimated singer tempo ratio: {tracker.score_follower.estimated_tempo_ratio:.2f}x")
    print(f"- Accompaniment beat events generated: {len(tracker.scheduled_timeline)}")


def main():
    parser = argparse.ArgumentParser(description="AI Jam Companion - Real-time Simulator")
    parser.add_argument("-m", "--map", default="/home/antigravity/ai_jam_companion/song_maps/demo_ballad_map.json", help="Path to song map JSON")
    parser.add_argument("-i", "--input", default="/home/antigravity/ai_jam_companion/audio_samples/demo_ballad_vocals.wav", help="Input vocals audio file")
    parser.add_argument("--speed", type=float, default=1.0, help="Tempo factor (e.g. 1.2 for faster, 0.8 for slower)")
    parser.add_argument("--pause-at", type=float, default=None, help="Inject pause at t seconds (tests VAMP loop)")
    parser.add_argument("--pause-dur", type=float, default=3.0, help="Duration of pause in seconds")
    parser.add_argument("--style", default="ballad_arpeggio", choices=["ballad_arpeggio", "rhythmic_comp", "sustained_chords"], help="Accompaniment style")
    parser.add_argument("--drums", action="store_true", help="Include minimalist drums in MIDI output")
    parser.add_argument("--fast", action="store_true", help="Run without real-time delay (fast batch simulation)")
    parser.add_argument("--midi", default="output_jam.mid", help="Output MIDI file path")
    parser.add_argument("--wav", default="output_jam.wav", help="Output accompaniment WAV file path")

    args = parser.parse_args()

    run_simulation(
        song_map_path=args.map,
        input_audio_path=args.input,
        output_midi_path=args.midi,
        output_wav_path=args.wav,
        speed_factor=args.speed,
        insert_pause_at_sec=args.pause_at,
        pause_duration_sec=args.pause_dur,
        realtime_delay=not args.fast,
        accompaniment_style=args.style,
        include_drums=args.drums
    )


if __name__ == "__main__":
    main()

#!/usr/bin/env python3
"""
AI Jam Companion - Live Microphone CLI Runner
Captures low-latency microphone audio via sounddevice, runs real-time pitch tracking,
Online DTW alignment, Harmonic State Machine, and outputs live MIDI/FluidSynth piano
(with graceful WAV/MIDI export when run headless).
"""

import argparse
import json
import os
import sys
import time
import queue
from typing import Optional

BASE_DIR = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, BASE_DIR)

import numpy as np
from realtime_engine.tracker import RealtimeJamTracker
from simulate import print_dashboard_line, BOLD, CYAN, GREEN, YELLOW, RED, RESET


def open_synth_output(soundfont_path: Optional[str] = None, midi_port_name: Optional[str] = None):
    """
    Attempts to open either FluidSynth (SoundFont) or a system MIDI output port.
    Returns (synth_type, handle) or (None, None).
    """
    if soundfont_path and os.path.exists(soundfont_path):
        try:
            import fluidsynth
            fs = fluidsynth.Synth()
            fs.start()
            sfid = fs.sfload(soundfont_path)
            fs.program_select(0, sfid, 0, 0)
            return "fluidsynth", fs
        except Exception as e:
            print(f"{YELLOW}[!] FluidSynth unavailable ({e}), checking MIDI ports...{RESET}")

    try:
        import mido
        ports = mido.get_output_names()
        if ports:
            target = midi_port_name if (midi_port_name and midi_port_name in ports) else ports[0]
            port = mido.open_output(target)
            print(f"{GREEN}[✓] Connected to MIDI output port: {target}{RESET}")
            return "midi", port
    except Exception:
        pass

    return None, None


def run_live_mic(
    song_map_path: str,
    style: str = "ballad_arpeggio",
    include_drums: bool = False,
    soundfont: Optional[str] = None,
    midi_port: Optional[str] = None,
    output_midi: str = "live_session.mid"
):
    try:
        import sounddevice as sd
    except OSError as e:
        print(f"{RED}[!] PortAudio / sounddevice is not available in this environment: {e}{RESET}")
        print("    For headless testing without a microphone, use:")
        print(f"    {CYAN}python3 simulate.py -m {song_map_path}{RESET}")
        print("    Or launch the browser Web Studio:")
        print(f"    {CYAN}python3 server.py{RESET}")
        sys.exit(1)

    with open(song_map_path, "r", encoding="utf-8") as f:
        song_map = json.load(f)

    meta = song_map.get("metadata", {})
    sr = int(meta.get("sample_rate", 22050))
    total_bars = int(meta.get("total_bars", 16))
    block_size = 512  # ~23.2 ms low-latency buffer

    print(f"\n{BOLD}{CYAN}=== AI Jam Companion - LIVE MICROPHONE MODE ==={RESET}")
    print(f"Song: {BOLD}{meta.get('title')}{RESET} | BPM: {meta.get('bpm')} | Bars: {total_bars}")

    synth_type, synth_handle = open_synth_output(soundfont, midi_port)
    if synth_type is None:
        print(f"{YELLOW}[i] No live SoundFont/MIDI synth found. Visual chord tracking & MIDI recording active.{RESET}")

    tracker = RealtimeJamTracker(
        song_map=song_map,
        sample_rate=sr,
        confidence_threshold=0.55,
        accompaniment_style=style,
        include_drums=include_drums
    )

    audio_q: queue.Queue = queue.Queue()

    def audio_callback(indata, frames, time_info, status):
        audio_q.put(indata[:, 0].copy())

    active_notes = set()
    last_print = 0.0

    print(f"{GREEN}[*] Listening on default microphone ({sr} Hz)... Sing to begin! (Ctrl+C to stop){RESET}\n")

    try:
        with sd.InputStream(samplerate=sr, channels=1, blocksize=block_size, dtype="float32", callback=audio_callback):
            while True:
                chunk = audio_q.get()
                ev = tracker.process_audio_buffer(chunk)

                # Trigger live piano sound if synth attached
                if ev.get("triggered_notes") and synth_handle is not None:
                    vel = ev["piano_velocity"]
                    notes = ev["triggered_notes"]
                    if synth_type == "fluidsynth":
                        for old_n in list(active_notes):
                            synth_handle.noteoff(0, old_n)
                        active_notes.clear()
                        for n in notes:
                            synth_handle.noteon(0, n, vel)
                            active_notes.add(n)
                    elif synth_type == "midi":
                        import mido
                        for old_n in list(active_notes):
                            synth_handle.send(mido.Message("note_off", note=old_n, velocity=0))
                        active_notes.clear()
                        for n in notes:
                            synth_handle.send(mido.Message("note_on", note=n, velocity=vel))
                            active_notes.add(n)

                if (time.time() - last_print) > 0.06:
                    print_dashboard_line(ev, total_bars)
                    last_print = time.time()

    except KeyboardInterrupt:
        print(f"\n\n{YELLOW}[!] Live session stopped.{RESET}")
    finally:
        if synth_type == "fluidsynth" and synth_handle:
            synth_handle.delete()
        elif synth_type == "midi" and synth_handle:
            synth_handle.close()

        tracker.midi_builder.export_midi(output_midi)
        print(f"{GREEN}[✓] Saved recorded accompaniment MIDI to: {output_midi}{RESET}")


def main():
    parser = argparse.ArgumentParser(description="AI Jam Companion - Live Microphone Mode")
    parser.add_argument("-m", "--map", default=os.path.join(BASE_DIR, "song_maps", "demo_ballad_map.json"), help="Song map JSON")
    parser.add_argument("--style", default="ballad_arpeggio", choices=["ballad_arpeggio", "rhythmic_comp", "sustained_chords"])
    parser.add_argument("--drums", action="store_true", help="Enable minimalist drum triggers")
    parser.add_argument("--soundfont", default=None, help="Path to .sf2 SoundFont file for FluidSynth")
    parser.add_argument("--midi-port", default=None, help="Specific MIDI output port name")
    parser.add_argument("--output-midi", default="live_session.mid", help="Recorded MIDI output file")
    args = parser.parse_args()

    run_live_mic(
        song_map_path=args.map,
        style=args.style,
        include_drums=args.drums,
        soundfont=args.soundfont,
        midi_port=args.midi_port,
        output_midi=args.output_midi
    )


if __name__ == "__main__":
    main()

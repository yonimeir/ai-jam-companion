#!/usr/bin/env python3
"""
Offline Song Map Extractor (AI Jam Companion - Step 1)
Takes an audio file or YouTube/web URL, processes chords, song structure,
and vocal pitch contour, and exports a rich JSON state map.
"""

import argparse
import base64
import gc
import json
import os
import re
import shutil
import sys
import tempfile
import urllib.parse
from typing import Dict, Any, List, Optional, Tuple

# Add parent directory to sys.path
sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

import numpy as np
import librosa
import soundfile as sf

from offline_pipeline.chord_detector import extract_chords_from_audio
from offline_pipeline.pitch_extractor import extract_pitch_contour
from offline_pipeline.structure_analyzer import segment_song_structure

DEFAULT_PLACEHOLDER_TITLES = {"", "Demo Song", "Analyzed Track", "Custom Song"}
DEFAULT_PLACEHOLDER_ARTISTS = {"", "Unknown Artist", "AI Jam", "AI Jam User"}
URL_EXTRACT_RE = re.compile(r"https?://[^\s\"'<>]+", re.IGNORECASE)

YOUTUBE_CLIENT_CHAIN: List[Optional[List[str]]] = [
    ["android_vr", "web_embedded", "tv"],
    ["ios", "mweb", "web"],
    None,  # Default yt-dlp behavior
]


def extract_media_url(raw_text: str) -> Optional[str]:
    """
    Extracts the first HTTP/HTTPS media URL from raw text or mobile share payloads
    (e.g. YouTube Android Share: 'Check out this song https://youtu.be/abc123?si=xyz').
    """
    if not raw_text:
        return None
    match = URL_EXTRACT_RE.search(raw_text.strip())
    if not match:
        return None
    url = match.group(0).rstrip(".,;!?)}\"']")
    return url


def _is_youtube_url(url: str) -> bool:
    try:
        host = urllib.parse.urlparse(url).netloc.lower()
    except Exception:
        return False
    return (
        host == "youtu.be"
        or host.endswith(".youtu.be")
        or host == "youtube.com"
        or host.endswith(".youtube.com")
        or host == "youtube-nocookie.com"
        or host.endswith(".youtube-nocookie.com")
    )


def _resolve_cookies_file(output_dir: str) -> Optional[str]:
    """
    Resolves optional YouTube cookies from YTDLP_COOKIES_B64 (base64 Netscape cookies)
    or YOUTUBE_COOKIES (raw Netscape cookie text or file path).
    """
    b64_cookies = os.environ.get("YTDLP_COOKIES_B64", "").strip()
    if b64_cookies:
        try:
            decoded = base64.b64decode(b64_cookies).decode("utf-8", errors="replace")
            if decoded.strip():
                cookie_path = os.path.join(output_dir, "yt_cookies.txt")
                with open(cookie_path, "w", encoding="utf-8") as f:
                    f.write(decoded)
                return cookie_path
        except Exception as exc:
            print(f"[!] Warning: failed to decode YTDLP_COOKIES_B64: {exc}", file=sys.stderr)

    raw_cookies = os.environ.get("YOUTUBE_COOKIES", "").strip()
    if raw_cookies:
        if os.path.isfile(raw_cookies):
            return raw_cookies
        text = raw_cookies.replace("\\n", "\n") if ("\n" not in raw_cookies and "\\n" in raw_cookies) else raw_cookies
        cookie_path = os.path.join(output_dir, "yt_cookies.txt")
        with open(cookie_path, "w", encoding="utf-8") as f:
            f.write(text)
        return cookie_path

    return None


def _check_youtube_oembed(url: str) -> Dict[str, Any]:
    """
    Checks YouTube oEmbed endpoint to distinguish between a deleted/private video
    and a cloud-IP / geo / bot-check block when yt-dlp fails.
    """
    oembed_url = f"https://www.youtube.com/oembed?url={urllib.parse.quote(url, safe='')}&format=json"
    try:
        import httpx
        resp = httpx.get(oembed_url, timeout=5.0, follow_redirects=True)
        status_code = resp.status_code
        if status_code == 200:
            data = resp.json()
            return {
                "status": "public",
                "title": data.get("title"),
                "author_name": data.get("author_name"),
            }
        if status_code in (401, 403):
            return {"status": "private_or_restricted", "http_status": status_code}
        if status_code in (400, 404):
            return {"status": "not_found_or_deleted", "http_status": status_code}
        return {"status": "unknown", "http_status": status_code}
    except Exception as exc:
        return {"status": "unknown", "error": str(exc)}


def _format_youtube_failure_message(
    url: str,
    last_error: Optional[Exception],
    has_cookies: bool,
) -> str:
    oembed = _check_youtube_oembed(url)
    status = oembed.get("status")
    err_str = str(last_error) if last_error else "שגיאה לא ידועה"

    if status == "public":
        title_note = f" (\"{oembed['title']}\")" if oembed.get("title") else ""
        cookie_hint = (
            "מומלץ לעדכן את ה-Cookies במשתנה הסביבה YTDLP_COOKIES_B64 / YOUTUBE_COOKIES, או להעלות קובץ MP3/WAV ישירות."
            if has_cookies
            else "מומלץ להגדיר YTDLP_COOKIES_B64 או YOUTUBE_COOKIES בשרת, או להעלות קובץ MP3/WAV ישירות."
        )
        return (
            f"הסרטון קיים וזמין ביוטיוב{title_note}, אך יוטיוב חסם את ההורדה משרת הענן "
            f"(חסימת מדינה / כתובת שרת ענן / אימות בוטים). {cookie_hint}"
        )

    if status == "private_or_restricted":
        return (
            "הסרטון ביוטיוב הוא פרטי, מוגבל גיל/גישה או חסום להטמעה, ולכן לא ניתן להוריד אותו מהשרת. "
            "אפשר להוריד את האודיו במכשיר ולהעלות קובץ MP3/WAV ישירות."
        )

    if status == "not_found_or_deleted":
        return "הסרטון לא נמצא ביוטיוב — ייתכן שנמחק, הפך לפרטי או שהקישור אינו תקין."

    err_lower = err_str.lower()
    if "private video" in err_lower or "members-only" in err_lower:
        return "הסרטון ביוטיוב הוא פרטי או לחברים בלבד, ולכן לא ניתן להוריד אותו."
    if "not available in your country" in err_lower or "geo" in err_lower:
        return "הסרטון חסום במדינה של השרת (חסימת אזור). מומלץ להעלות קובץ MP3/WAV ישירות."
    if "sign in" in err_lower or "bot" in err_lower or "cookies" in err_lower:
        return (
            "יוטיוב דורש אימות / Cookies משרת הענן (חסימת בוטים). "
            "הגדר YTDLP_COOKIES_B64 / YOUTUBE_COOKIES או העלה קובץ MP3/WAV ישירות."
        )

    return (
        "הורדת האודיו מיוטיוב נכשלה בכל הלקוחות (ייתכן שהסרטון פרטי/מחוק או שקיימת חסימת מדינה/שרת ענן). "
        f"פרטי שגיאה: {err_str}"
    )


def download_audio_with_metadata(url: str, output_dir: str) -> Tuple[str, Dict[str, Any]]:
    """
    Download audio from YouTube / YouTube Music / web URL using yt-dlp
    with a multi-client fallback chain, EJS remote components, geo-bypass,
    optional cookies from environment variables, and YouTube oEmbed diagnostics.
    """
    try:
        import yt_dlp
    except ImportError:
        raise RuntimeError("yt-dlp is required for downloading audio from URLs. Please install yt-dlp.")

    clean_url = extract_media_url(url) or url.strip()
    is_yt = _is_youtube_url(clean_url)
    cookie_file = _resolve_cookies_file(output_dir)
    attempts: List[Optional[List[str]]] = YOUTUBE_CLIENT_CHAIN if is_yt else [None]

    last_error: Optional[Exception] = None
    for idx, clients in enumerate(attempts):
        attempt_dir = os.path.join(output_dir, f"attempt_{idx}")
        os.makedirs(attempt_dir, exist_ok=True)
        out_tmpl = os.path.join(attempt_dir, "downloaded_audio.%(ext)s")

        ydl_opts: Dict[str, Any] = {
            "format": "bestaudio/best",
            "outtmpl": out_tmpl,
            "noplaylist": True,
            "geo_bypass": True,
            "remote_components": ["ejs:github"],
            "postprocessors": [{
                "key": "FFmpegExtractAudio",
                "preferredcodec": "wav",
                "preferredquality": "192",
            }],
            "quiet": True,
            "no_warnings": True,
        }
        if cookie_file:
            ydl_opts["cookiefile"] = cookie_file
        if clients is not None:
            ydl_opts["extractor_args"] = {"youtube": {"player_client": clients}}

        try:
            media_meta: Dict[str, Any] = {"source_url": clean_url}
            with yt_dlp.YoutubeDL(ydl_opts) as ydl:
                info = ydl.extract_info(clean_url, download=True)
                if isinstance(info, dict):
                    # Handle single-entry playlist wrapper if yt-dlp returns entries
                    if "entries" in info and info["entries"]:
                        info = info["entries"][0]
                    media_meta["title"] = info.get("track") or info.get("title")
                    media_meta["artist"] = (
                        info.get("artist")
                        or info.get("creator")
                        or info.get("uploader")
                        or info.get("channel")
                    )
                    media_meta["source_url"] = info.get("webpage_url") or clean_url
                    media_meta["video_id"] = info.get("id")

            expected_wav = os.path.join(attempt_dir, "downloaded_audio.wav")
            if os.path.exists(expected_wav):
                return expected_wav, media_meta

            files = [os.path.join(attempt_dir, f) for f in os.listdir(attempt_dir)]
            if files:
                return files[0], media_meta

            raise FileNotFoundError("Failed to locate downloaded audio file.")
        except Exception as exc:
            last_error = exc
            client_label = ",".join(clients) if clients else "default"
            print(f"[!] yt-dlp attempt ({client_label}) failed: {exc}", file=sys.stderr)

    if is_yt:
        raise RuntimeError(
            _format_youtube_failure_message(clean_url, last_error, has_cookies=bool(cookie_file))
        )
    raise RuntimeError(f"הורדת האודיו מהקישור נכשלה: {last_error}")


def download_audio_from_url(url: str, output_dir: str) -> str:
    """Backward-compatible wrapper returning only the downloaded audio file path."""
    audio_path, _ = download_audio_with_metadata(url, output_dir)
    return audio_path


def generate_song_map(
    audio_path_or_url: str,
    output_json_path: Optional[str] = None,
    title: str = "Demo Song",
    artist: str = "Unknown Artist",
    target_sr: int = 22050,
    save_vocals_path: Optional[str] = None,
) -> Dict[str, Any]:
    """
    Main pipeline function: Processes audio (local file or YouTube/web URL/share text)
    and generates complete song state map.
    """
    temp_dir = None
    audio_file = audio_path_or_url
    media_meta: Dict[str, Any] = {}

    try:
        extracted_url = extract_media_url(audio_path_or_url) if not os.path.exists(audio_path_or_url) else None
        if extracted_url:
            print(f"[*] Downloading audio from URL: {extracted_url} ...")
            temp_dir = tempfile.mkdtemp(prefix="ai_jam_dl_")
            audio_file, media_meta = download_audio_with_metadata(extracted_url, temp_dir)
            print(f"[✓] Audio downloaded: {audio_file}")

            if title in DEFAULT_PLACEHOLDER_TITLES and media_meta.get("title"):
                title = str(media_meta["title"])
            if artist in DEFAULT_PLACEHOLDER_ARTISTS and media_meta.get("artist"):
                artist = str(media_meta["artist"])

        if not os.path.isfile(audio_file):
            raise FileNotFoundError(f"Audio file not found: {audio_file}")

        print(f"[*] Loading audio file: {audio_file} (resampling to {target_sr} Hz)...")
        y, sr = librosa.load(audio_file, sr=target_sr, mono=True)
        duration = float(len(y) / sr)
        print(f"[✓] Audio loaded: {duration:.2f} seconds ({len(y)} samples)")

        # 1. Harmonic and Chord Analysis
        print("[*] Extracting harmony, BPM, and chord sequence...")
        chords_list, bpm, beat_times = extract_chords_from_audio(y, sr)
        gc.collect()
        print(f"[✓] Detected BPM: {bpm:.1f}, Chords count: {len(chords_list)}")

        # 2. Song Structural Segmentation
        print("[*] Segmenting song structure (Intro, Verse, Chorus, Outro)...")
        sections = segment_song_structure(y, sr, beat_times)
        gc.collect()
        print(f"[✓] Detected {len(sections)} sections: {[s['name'] for s in sections]}")

        # 3. Vocal Pitch Contour (F0 reference anchor)
        print("[*] Extracting vocal pitch curve and energy contour via pYIN...")
        vocal_anchor = extract_pitch_contour(y, sr, save_vocals_path=save_vocals_path)
        del y
        gc.collect()
        voiced_count = sum(1 for p in vocal_anchor["f0_hz"] if p > 0)
        print(f"[✓] Extracted {vocal_anchor['frame_count']} frames ({voiced_count} voiced frames)")

        # 4. Build Harmonic State Map (State Machine Graph)
        states = []
        total_bars = max((c["bar"] for c in chords_list), default=1)

        for bar_num in range(1, total_bars + 1):
            bar_chords = [c for c in chords_list if c["bar"] == bar_num]
            if not bar_chords:
                continue

            bar_start_time = bar_chords[0]["start_time"]
            bar_end_time = bar_chords[-1]["end_time"]
            primary_chord = bar_chords[0]["chord"]

            sec_name = "verse"
            for s in sections:
                if s["start_bar"] <= bar_num <= s["end_bar"]:
                    sec_name = s["name"]
                    break

            v_indices = [
                i for i, t in enumerate(vocal_anchor["times"])
                if bar_start_time <= t <= bar_end_time
            ]
            target_pitches = [vocal_anchor["midi_notes"][i] for i in v_indices if vocal_anchor["midi_notes"][i] > 0]
            avg_pitch = float(np.mean(target_pitches)) if target_pitches else 0.0

            states.append({
                "bar": bar_num,
                "section": sec_name,
                "chord": primary_chord,
                "sub_chords": [c["chord"] for c in bar_chords],
                "start_time": bar_start_time,
                "end_time": bar_end_time,
                "notes": bar_chords[0]["notes"],
                "target_pitch_midi": round(avg_pitch, 2),
                "next_bar": bar_num + 1 if bar_num < total_bars else 1,
                "vamp_behavior": "loop_bar_comping"
            })

        metadata_block: Dict[str, Any] = {
            "title": title,
            "artist": artist,
            "duration": round(duration, 3),
            "bpm": round(bpm, 1),
            "sample_rate": sr,
            "total_bars": total_bars,
            "total_beats": len(beat_times),
        }
        if media_meta.get("source_url"):
            metadata_block["source_url"] = media_meta["source_url"]
        if media_meta.get("video_id"):
            metadata_block["video_id"] = media_meta["video_id"]

        song_map = {
            "metadata": metadata_block,
            "sections": sections,
            "chords": chords_list,
            "states": states,
            "vocal_anchor": vocal_anchor
        }

        if output_json_path:
            os.makedirs(os.path.dirname(os.path.abspath(output_json_path)), exist_ok=True)
            with open(output_json_path, "w", encoding="utf-8") as f:
                json.dump(song_map, f, indent=2, ensure_ascii=False)
            print(f"[✓] Successfully exported song map to: {output_json_path}")

        return song_map
    finally:
        if temp_dir and os.path.isdir(temp_dir):
            shutil.rmtree(temp_dir, ignore_errors=True)

def main():
    parser = argparse.ArgumentParser(description="AI Jam Companion - Offline Song Pre-Processor")
    parser.add_argument("input", help="Path to input audio file (mp3/wav/flac) or URL (YouTube/web)")
    parser.add_argument("-o", "--output", default="song_maps/song_map.json", help="Path to output JSON map")
    parser.add_argument("--title", default="Analyzed Track", help="Song Title")
    parser.add_argument("--artist", default="AI Jam", help="Artist Name")
    
    args = parser.parse_args()
    try:
        generate_song_map(args.input, args.output, title=args.title, artist=args.artist)
    except Exception as e:
        print(f"[!] Error processing audio: {e}", file=sys.stderr)
        import traceback
        traceback.print_exc()
        sys.exit(1)

if __name__ == "__main__":
    main()

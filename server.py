#!/usr/bin/env python3
"""
AI Jam Companion - Full-Stack Web & WebSocket Server
Connects the browser Web Studio directly to the Python Offline Analysis Pipeline
and the Real-Time Engine (FFT-YIN, Online DTW, Harmonic State Machine, Voice-Leading, MIDI/WAV Export).
"""

import asyncio
import json
import os
import struct
import sys
import tempfile
import urllib.parse
import zlib
from typing import Dict, Any, Optional

import numpy as np
from fastapi import FastAPI, WebSocket, WebSocketDisconnect, UploadFile, File, Form, HTTPException, Query, Body
from fastapi.responses import FileResponse, HTMLResponse, JSONResponse, RedirectResponse, Response
from fastapi.middleware.cors import CORSMiddleware

BASE_DIR = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, BASE_DIR)

from offline_pipeline.extract_song_map import generate_song_map, extract_media_url
from realtime_engine.tracker import RealtimeJamTracker
from synthesis.piano_synth import AcousticPianoRenderer

app = FastAPI(title="AI Jam Companion API", version="1.2.0")

app.add_middleware(
    CORSMiddleware,
    allow_origins=["*"],
    allow_methods=["*"],
    allow_headers=["*"],
)

DEMO_MAP_PATH = os.path.join(BASE_DIR, "song_maps", "demo_ballad_map.json")
DEMO_VOCALS_PATH = os.path.join(BASE_DIR, "audio_samples", "demo_ballad_vocals.wav")
ACTIVE_VOCALS_PATH = os.path.join(tempfile.gettempdir(), "ai_jam_active_vocals.wav")
WEB_INDEX_PATH = os.path.join(BASE_DIR, "web_studio", "index.html")
SESSION_DIR = tempfile.mkdtemp(prefix="ai_jam_session_")

# Active session state
session_state: Dict[str, Any] = {
    "song_map": None,
    "last_tracker": None,
    "active_audio_path": DEMO_VOCALS_PATH if os.path.exists(DEMO_VOCALS_PATH) else None,
}


def _make_png_icon(size: int) -> bytes:
    """Generate a clean cyan/indigo gradient app icon PNG without external dependencies."""
    rows = []
    cx, cy = size / 2.0, size / 2.0
    radius = size * 0.44
    for y in range(size):
        row = bytearray([0])  # filter type 0
        for x in range(size):
            t = (x + y) / (2.0 * size)
            # Indigo (#4f46e5) -> Cyan (#06b6d4)
            r = int(79 * (1 - t) + 6 * t)
            g = int(70 * (1 - t) + 182 * t)
            b = int(229 * (1 - t) + 212 * t)
            # Draw simple white piano keys motif in center
            in_box = (size * 0.24 <= x <= size * 0.76) and (size * 0.28 <= y <= size * 0.72)
            if in_box:
                rel_x = (x - size * 0.24) / (size * 0.52)
                rel_y = (y - size * 0.28) / (size * 0.44)
                is_black_key = rel_y < 0.6 and any(abs(rel_x - k) < 0.065 for k in (0.25, 0.5, 0.75))
                if is_black_key:
                    r, g, b = 15, 23, 42
                else:
                    r, g, b = 248, 250, 252
            dx, dy = x - cx, y - cy
            alpha = 255 if (dx * dx + dy * dy <= radius * radius or (size * 0.12 <= x <= size * 0.88 and size * 0.12 <= y <= size * 0.88)) else 255
            row.extend((r, g, b, alpha))
        rows.append(bytes(row))
    raw = b"".join(rows)

    def _chunk(tag: bytes, data: bytes) -> bytes:
        return struct.pack("!I", len(data)) + tag + data + struct.pack("!I", zlib.crc32(tag + data) & 0xFFFFFFFF)

    ihdr = struct.pack("!IIBBBBB", size, size, 8, 6, 0, 0, 0)
    return b"\x89PNG\r\n\x1a\n" + _chunk(b"IHDR", ihdr) + _chunk(b"IDAT", zlib.compress(raw, 6)) + _chunk(b"IEND", b"")


ICON_192_PNG = _make_png_icon(192)
ICON_512_PNG = _make_png_icon(512)


def _load_default_map() -> Dict[str, Any]:
    if os.path.exists(DEMO_MAP_PATH):
        with open(DEMO_MAP_PATH, "r", encoding="utf-8") as f:
            return json.load(f)
    return {
        "metadata": {"title": "Empty", "artist": "None", "bpm": 100.0, "duration": 16.0, "sample_rate": 22050, "total_bars": 4},
        "sections": [{"name": "verse", "label": "Verse", "start_time": 0.0, "end_time": 16.0, "start_bar": 1, "end_bar": 4}],
        "chords": [],
        "states": [],
        "vocal_anchor": {"times": [], "f0_hz": [], "midi_notes": [], "voiced_prob": [], "rms_db": []},
    }


session_state["song_map"] = _load_default_map()


@app.get("/", response_class=HTMLResponse)
async def serve_studio():
    if not os.path.exists(WEB_INDEX_PATH):
        raise HTTPException(status_code=404, detail="web_studio/index.html not found")
    with open(WEB_INDEX_PATH, "r", encoding="utf-8") as f:
        return HTMLResponse(content=f.read())


@app.get("/manifest.json")
async def pwa_manifest():
    manifest = {
        "name": "AI Jam Companion",
        "short_name": "AI Jam",
        "description": "ליווי מוזיקלי אדפטיבי חי לשירה ממיקרופון או ישירות מקישור יוטיוב",
        "start_url": "/",
        "scope": "/",
        "display": "standalone",
        "background_color": "#020617",
        "theme_color": "#06b6d4",
        "icons": [
            {"src": "/icon.svg", "sizes": "any", "type": "image/svg+xml", "purpose": "any"},
            {"src": "/icon-192.png", "sizes": "192x192", "type": "image/png", "purpose": "any maskable"},
            {"src": "/icon-512.png", "sizes": "512x512", "type": "image/png", "purpose": "any maskable"},
        ],
        "share_target": {
            "action": "/share",
            "method": "GET",
            "params": {
                "title": "title",
                "text": "text",
                "url": "url",
            },
        },
    }
    return JSONResponse(content=manifest, media_type="application/manifest+json")


@app.get("/sw.js")
async def pwa_service_worker():
    sw_code = """
const CACHE_NAME = 'ai-jam-companion-v1';
self.addEventListener('install', (event) => {
  self.skipWaiting();
});
self.addEventListener('activate', (event) => {
  event.waitUntil(self.clients.claim());
});
self.addEventListener('fetch', (event) => {
  event.respondWith(fetch(event.request).catch(() => caches.match(event.request)));
});
""".strip()
    return Response(content=sw_code, media_type="application/javascript")


@app.get("/icon.svg")
async def pwa_icon_svg():
    svg = """<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 128 128">
  <defs>
    <linearGradient id="bg" x1="0" y1="0" x2="1" y2="1">
      <stop offset="0%" stop-color="#06b6d4"/>
      <stop offset="100%" stop-color="#4f46e5"/>
    </linearGradient>
  </defs>
  <rect width="128" height="128" rx="28" fill="url(#bg)"/>
  <rect x="26" y="34" width="76" height="60" rx="8" fill="#f8fafc"/>
  <rect x="40" y="34" width="10" height="36" rx="2" fill="#0f172a"/>
  <rect x="59" y="34" width="10" height="36" rx="2" fill="#0f172a"/>
  <rect x="78" y="34" width="10" height="36" rx="2" fill="#0f172a"/>
</svg>"""
    return Response(content=svg, media_type="image/svg+xml")


@app.get("/icon-192.png")
async def pwa_icon_192():
    return Response(content=ICON_192_PNG, media_type="image/png")


@app.get("/icon-512.png")
async def pwa_icon_512():
    return Response(content=ICON_512_PNG, media_type="image/png")


@app.get("/share")
async def handle_web_share_target(
    url: Optional[str] = Query(None),
    text: Optional[str] = Query(None),
    title: Optional[str] = Query(None),
):
    """
    PWA Web Share Target handler:
    YouTube & YouTube Music on Android often send the link inside `text` rather than `url`.
    We extract the clean link and redirect to the studio UI which auto-starts analysis.
    """
    extracted = extract_media_url(url or "") or extract_media_url(text or "")
    params: Dict[str, str] = {}
    if extracted:
        params["url"] = extracted
    elif text:
        params["text"] = text
    if title:
        params["title"] = title
    query_str = urllib.parse.urlencode(params)
    target = f"/?{query_str}" if query_str else "/"
    return RedirectResponse(url=target, status_code=302)


@app.get("/api/health")
async def health():
    return {
        "status": "ok",
        "engine": "python-realtime-odtw",
        "song_loaded": session_state["song_map"]["metadata"].get("title"),
        "youtube_supported": True,
        "share_target": "/share",
    }


@app.get("/api/song-map/current")
async def get_current_song_map():
    return JSONResponse(content=session_state["song_map"])


@app.get("/api/song-map/demo")
async def get_demo_song_map():
    demo_map = _load_default_map()
    session_state["song_map"] = demo_map
    if os.path.exists(DEMO_VOCALS_PATH):
        session_state["active_audio_path"] = DEMO_VOCALS_PATH
    return JSONResponse(content=demo_map)


@app.get("/api/audio/demo-vocals")
async def get_demo_vocals():
    if not os.path.exists(DEMO_VOCALS_PATH):
        raise HTTPException(status_code=404, detail="demo_ballad_vocals.wav not found")
    return FileResponse(DEMO_VOCALS_PATH, media_type="audio/wav", filename="demo_ballad_vocals.wav")


@app.get("/api/audio/active-vocals")
async def get_active_vocals():
    audio_path = session_state.get("active_audio_path") or DEMO_VOCALS_PATH
    if not audio_path or not os.path.exists(audio_path):
        raise HTTPException(status_code=404, detail="No active vocals audio available")
    return FileResponse(audio_path, media_type="audio/wav", filename="active_vocals.wav")


async def _run_analysis(source: str, title: str, artist: str) -> Dict[str, Any]:
    out_json = os.path.join(SESSION_DIR, "latest_song_map.json")
    vocals_wav = os.path.join(SESSION_DIR, "active_vocals.wav")
    song_map = await asyncio.to_thread(
        generate_song_map,
        source,
        out_json,
        title,
        artist,
        22050,
        vocals_wav,
    )
    session_state["song_map"] = song_map
    if os.path.exists(vocals_wav):
        session_state["active_audio_path"] = vocals_wav
    return song_map


@app.post("/api/analyze")
async def analyze_song(
    file: Optional[UploadFile] = File(None),
    url: Optional[str] = Form(None),
    title: str = Form("Custom Song"),
    artist: str = Form("AI Jam User"),
):
    """
    Runs the Stage A Offline Pipeline on an uploaded audio file (MP3/WAV/FLAC/OGG)
    or a YouTube / media URL (including share text) and returns the extracted song_map.json.
    """
    if file is None and not (url and url.strip()):
        raise HTTPException(status_code=400, detail="Provide either an audio file or a YouTube/media URL.")

    temp_input = None
    try:
        if file is not None:
            suffix = os.path.splitext(file.filename or "upload.wav")[1] or ".wav"
            temp_input = os.path.join(SESSION_DIR, f"upload_{os.getpid()}{suffix}")
            content = await file.read()
            with open(temp_input, "wb") as out_f:
                out_f.write(content)
            source = temp_input
            if title == "Custom Song" and file.filename:
                title = os.path.splitext(file.filename)[0]
        else:
            clean_url = extract_media_url(url or "")
            if not clean_url:
                raise HTTPException(status_code=400, detail="Invalid URL. Please provide a valid http/https YouTube or media link.")
            source = clean_url

        song_map = await _run_analysis(source, title, artist)
        return JSONResponse(content=song_map)
    except HTTPException:
        raise
    except Exception as exc:
        raise HTTPException(status_code=500, detail=f"Analysis failed: {exc}")
    finally:
        if temp_input and os.path.exists(temp_input):
            try:
                os.remove(temp_input)
            except OSError:
                pass


@app.post("/api/analyze-url")
async def analyze_song_url_json(payload: Dict[str, Any] = Body(...)):
    """JSON convenience endpoint for analyzing a YouTube or web media link."""
    raw_url = str(payload.get("url") or payload.get("text") or "").strip()
    clean_url = extract_media_url(raw_url)
    if not clean_url:
        raise HTTPException(status_code=400, detail="Invalid or missing YouTube/media URL.")
    title = str(payload.get("title") or "Custom Song")
    artist = str(payload.get("artist") or "AI Jam User")
    try:
        song_map = await _run_analysis(clean_url, title, artist)
        return JSONResponse(content=song_map)
    except Exception as exc:
        raise HTTPException(status_code=500, detail=f"Analysis failed: {exc}")


@app.get("/api/export/midi")
async def export_midi():
    tracker: Optional[RealtimeJamTracker] = session_state.get("last_tracker")
    if tracker is None or not tracker.scheduled_timeline:
        raise HTTPException(status_code=400, detail="No live jam session recorded yet.")
    midi_path = os.path.join(SESSION_DIR, "live_jam_export.mid")
    tracker.midi_builder.export_midi(midi_path)
    return FileResponse(midi_path, media_type="audio/midi", filename="ai_jam_session.mid")


@app.get("/api/export/wav")
async def export_wav():
    tracker: Optional[RealtimeJamTracker] = session_state.get("last_tracker")
    if tracker is None or not tracker.scheduled_timeline:
        raise HTTPException(status_code=400, detail="No live jam session recorded yet.")
    wav_path = os.path.join(SESSION_DIR, "live_jam_export.wav")
    renderer = AcousticPianoRenderer(sample_rate=22050)
    await asyncio.to_thread(
        renderer.render_accompaniment_track,
        tracker.scheduled_timeline,
        tracker.bpm,
        wav_path,
    )
    return FileResponse(wav_path, media_type="audio/wav", filename="ai_jam_accompaniment.wav")


@app.websocket("/ws/jam")
async def websocket_jam(websocket: WebSocket):
    """
    Real-time bidirectional WebSocket endpoint:
    - Receives binary Float32 PCM audio frames (22050 Hz mono) or JSON control messages.
    - Streams back JSON tracking & accompaniment trigger events in <15ms.
    """
    await websocket.accept()
    song_map = session_state["song_map"]
    sr = int(song_map.get("metadata", {}).get("sample_rate", 22050))

    tracker = RealtimeJamTracker(
        song_map=song_map,
        sample_rate=sr,
        confidence_threshold=0.55,
        accompaniment_style="ballad_arpeggio",
        include_drums=False,
    )
    session_state["last_tracker"] = tracker

    try:
        while True:
            msg = await websocket.receive()

            if "bytes" in msg and msg["bytes"] is not None:
                raw_bytes = msg["bytes"]
                pcm = np.frombuffer(raw_bytes, dtype=np.float32)
                if len(pcm) > 0:
                    status = tracker.process_audio_buffer(pcm)
                    if status:
                        await websocket.send_json({"type": "state", "data": status})

            elif "text" in msg and msg["text"] is not None:
                payload = json.loads(msg["text"])
                msg_type = payload.get("type")

                if msg_type == "config":
                    if "style" in payload:
                        style_map = {
                            "ballad": "ballad_arpeggio",
                            "comp": "rhythmic_comp",
                            "ambient": "sustained_chords",
                        }
                        tracker.set_style(style_map.get(payload["style"], payload["style"]))
                    if "threshold" in payload:
                        tracker.set_confidence_threshold(float(payload["threshold"]))
                    if "drums" in payload:
                        tracker.set_include_drums(bool(payload["drums"]))

                elif msg_type == "jump_bar":
                    bar_idx = int(payload.get("bar", 1))
                    states = song_map.get("states", [])
                    target_time = 0.0
                    for s in states:
                        if s["bar"] == bar_idx:
                            target_time = float(s["start_time"])
                            break
                    tracker.score_follower.jump_to_time(target_time)
                    await websocket.send_json({"type": "ack", "action": "jump_bar", "bar": bar_idx})

                elif msg_type == "reset":
                    song_map = session_state["song_map"]
                    tracker = RealtimeJamTracker(
                        song_map=song_map,
                        sample_rate=int(song_map.get("metadata", {}).get("sample_rate", 22050)),
                        confidence_threshold=tracker.state_machine.confidence_threshold,
                        accompaniment_style=tracker.style,
                        include_drums=tracker.include_drums,
                    )
                    session_state["last_tracker"] = tracker
                    await websocket.send_json({"type": "ack", "action": "reset"})

    except WebSocketDisconnect:
        pass
    except Exception as exc:
        try:
            await websocket.send_json({"type": "error", "message": str(exc)})
        except Exception:
            pass


if __name__ == "__main__":
    import uvicorn
    port = int(os.environ.get("PORT", "8000"))
    print(f"[*] Starting AI Jam Companion Studio at http://0.0.0.0:{port}")
    uvicorn.run(app, host="0.0.0.0", port=port)

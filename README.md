# AI Jam Companion (ליווי מוזיקלי אדפטיבי חי)

מערכת ליווי מוזיקלי אינטראקטיבית המדמה נגן חי (פסנתר אקוסטי + תופים מינימליסטיים) המקשיב לזמר בזמן אמת, עוקב אחר המלודיה והדינמיקה, ומלווה בהרמוניה מדויקת – תוך תמיכה מלאה באלתורים, עצירות נשימה (Vamp) ושינויי טמפו דינמיים.

---

## 🏗️ ארכיטקטורת המערכת (System Architecture)

```
                                  [ קלט שיר / קישור / העלאת MP3 ]
                                           │
                                           ▼
┌─────────────────────────────────────────────────────────────────────────────────┐
│ שלב א': עיבוד ומיפוי מקדים (Offline Pre-Processing Pipeline)                   │
│  ├─ זיהוי טמפו וביטים (Tempogram-Guided Beat Tracking)                         │
│  ├─ חילוץ אקורדים (Bass-Weighted CQT Chroma + Key-Aware Viterbi HMM)           │
│  ├─ סגמנטציה מבנית (Agglomerative Clustering: Intro, Verse, Chorus, Outro)     │
│  └─ בידוד שירה וחילוץ F0 (REPET-SIM Foreground Isolation + pYIN + RMS Envelope) │
└──────────────────────────────────┬──────────────────────────────────────────────┘
                                   │
                             song_map.json
                                   │
                                   ▼
┌─────────────────────────────────────────────────────────────────────────────────┐
│ שלב ב'+ג': לולאת זמן אמת ומנוע קבלת החלטות (Real-Time Tracking & Alignment)      │
│  ├─ Ring Buffer חופף (2048 window / 512 hop) לסנכרון 1:1 עם המפה               │
│  ├─ זיהוי גובה צליל מהיר (Vectorized FFT-YIN < 0.5ms לפריים)                    │
│  ├─ יישור דינמי (Online DTW עם הערכת Tempo Ratio ושחזור קפיצות Global Resync) │
│  └─ מכונת מצבים הרמונית (Harmonic State Machine: Vamp Looping + סף ביטחון)     │
└──────────────────────────────────┬──────────────────────────────────────────────┘
                                   │
                                   ▼
┌─────────────────────────────────────────────────────────────────────────────────┐
│ שלב ד': סינתיזה וליווי מוזיקלי אדפטיבי (Adaptive Synthesis & Audio Generation)   │
│  ├─ מיפוי דינמיקה חי (RMS to Velocity: pp–ff עם מעטפת Attack/Release)           │
│  ├─ הולכת קולות חלקה (Smooth Voice-Leading + תופים מינימליסטיים אופציונליים)  │
│  └─ פלט משולב: Web Audio Studio (WS) / FluidSynth / MIDI (.mid) / WAV (.wav)    │
└─────────────────────────────────────────────────────────────────────────────────┘
```

---

## 📁 מבנה הפרויקט (Project Structure)

```text
ai_jam_companion/
├── offline_pipeline/                 # שלב א': ניתוח שירים מוקדם
│   ├── chord_detector.py             # זיהוי BPM, סולם (Krumhansl) ואקורדים ב-Viterbi HMM
│   ├── pitch_extractor.py            # בידוד שירה ב-REPET-SIM וחילוץ F0 ב-pYIN
│   ├── structure_analyzer.py         # סגמנטציית חלקי השיר (Intro, Verse, Chorus, Outro)
│   └── extract_song_map.py           # CLI להפקת קובץ song_map.json
│
├── realtime_engine/                  # שלב ב'+ג': לולאת המעקב ומכונת המצבים
│   ├── pitch_detector.py             # גלאי Vectorized FFT-YIN מהיר
│   ├── dynamic_alignment.py          # Online DTW + הערכת יחס טמפו + Global Resync
│   ├── harmonic_state_machine.py     # ניהול מצבי נגינה, Vamp Looping וסף ביטחון
│   └── tracker.py                    # רכז זמן-אמת עם Ring Buffer וטיימליין מונוטוני
│
├── synthesis/                        # שלב ד': מנוע הסינתיזה וה-MIDI
│   ├── voicing.py                    # הולכת קולות (Nearest Inversion) + תופים מינימליסטיים
│   ├── midi_generator.py             # מיפוי RMS לוולוסיטי וייצוא Multi-Channel MIDI
│   └── piano_synth.py                # סינתיסייזר פסנתר אקוסטי רב-הרמוני ל-WAV סטריאו
│
├── server.py                         # שרת FastAPI + WebSocket המחבר את הדפדפן למנוע הפייתון
├── live_mic.py                       # הרצת מיקרופון חי מקומי (sounddevice + FluidSynth/MIDI)
├── simulate.py                       # סימולטור CLI בזמן אמת עם דשבורד טרמינל צבעוני
├── web_studio/                       # סטודיו אינטראקטיבי בדפדפן (Dual-Mode)
│   └── index.html
├── audio_samples/                    # דגימות שיר בדיקה והרצות
├── song_maps/                        # מפות שירים JSON
├── tests/                            # בדיקות יחידה ואינטגרציה מקצה לקצה
│   └── test_pipeline.py
└── requirements.txt
```

---

## 🚀 התקנה והרצה (Quickstart)

### 1. התקנת תלויות
```bash
python3 -m venv .venv
source .venv/bin/activate
pip install -r requirements.txt
```

### 2. הפעלת הסטודיו המלא בדפדפן (מומלץ!)
```bash
python3 server.py
```
ולפתוח בדפדפן את: `http://localhost:8000`
* מאפשר **העלאת קובץ MP3/WAV** או **הדבקת קישור YouTube / YouTube Music** לניתוח אוטומטי מלא (הורדת אודיו + חילוץ שם השיר/אמן + מפת אקורדים ומלודיה + בידוד שירה), שירה במיקרופון חי, הפעלת ה-Demo Singer עם השמעת קול השירה והליווי, בחירת סגנון + תופים מינימליסטיים + Reverb, וייצוא ישיר של הביצוע החי לקבצי `.mid` ו-`.wav`.
* **שיתוף ישיר מתוך אפליקציית YouTube (PWA Web Share Target)**:
  * השרת מגיש `/manifest.json`, `/sw.js` ו-`/share` כתמיכת PWA מלאה.
  * כשפותחים את הסטודיו על `https://` (למשל דרך Cloudflare Tunnel / Tailscale / שרת ענן) או `http://localhost:8000` ומתקינים אותו כאפליקציה (`התקן כאפליקציה`), **AI Jam Companion** מופיע בתפריט השיתוף (**Share**) של YouTube ו-YouTube Music באנדרואיד — ושיתוף של שיר פותח ומנתח אותו אוטומטית!
  * בדפדפן במחשב ניתן להשתמש גם ב-Bookmarklet מתוך כפתור **📲 שיתוף מתוך יוטיוב** בסטודיו או לפתוח ישירות `http://localhost:8000/?url=<YOUTUBE_URL>`.

### 3. הפקת מפת שיר משורת הפקודה (Offline Step)
```bash
# מקובץ אודיו מקומי:
python3 offline_pipeline/extract_song_map.py audio_samples/demo_ballad_full.wav \
    -o song_maps/my_song_map.json \
    --title "My Song" \
    --artist "Singer"

# או ישירות מקישור יוטיוב:
python3 offline_pipeline/extract_song_map.py "https://youtu.be/<VIDEO_ID>" -o song_maps/yt_song.json
```

### 4. סימולטור זמן אמת בטרמינל / בדיקת מיקרופון חי
```bash
# סימולטור עם דשבורד חי, עצירת נשימה (VAMP), שינוי מהירות ותופים:
python3 simulate.py -m song_maps/demo_ballad_map.json -i audio_samples/demo_ballad_vocals.wav --speed 1.1 --pause-at 15.0 --drums

# מיקרופון חי (במחשב מקומי עם PortAudio / FluidSynth):
python3 live_mic.py -m song_maps/demo_ballad_map.json --drums --soundfont /path/to/piano.sf2
```

### 5. הרצת בדיקות אוטומטיות
```bash
python3 tests/test_pipeline.py -v
```

# AI Jam Companion — אפליקציית אנדרואיד מקומית (Native Kotlin + DSP)

מלווה מוזיקלי אינטראקטיבי בזמן אמת (פסנתר אקוסטי + תופים מינימליסטיים) הרץ **100% מקומית על מכשיר האנדרואיד** — ללא תלות בשרת ענן, ללא WebSockets וללא שיהוי רשת.

האפליקציה מקשיבה לזמר דרך מיקרופון המכשיר (`AudioRecord`) או מנגנת במצב הדמו המובנה, עוקבת אחר המלודיה והדינמיקה בזמן אמת, ומספקת ליווי הרמוני חלק עם תמיכה באלתורים (Vamp), סגמנטציה מבנית, מילות שיר מסונכרנות, וויזואליזציה חיה על הבמה.

---

## 📱 יכולות אפליקציית האנדרואיד (`android/`)

* **מנוע DSP וזמן אמת בקוטלין טהור (On-Device)**:
  * [`RealtimePitchDetector`](android/app/src/main/java/com/aijam/companion/engine/RealtimePitchDetector.kt): גלאי FFT-YIN מהיר (`2048` חלון / `512` קפיצה) + מיפוי RMS לדינמיקת נגינה (`pp–ff`).
  * [`OnlineScoreFollower`](android/app/src/main/java/com/aijam/companion/engine/OnlineScoreFollower.kt): יישור דינמי (Online DTW) סובלני לאוקטבות, נעילת כניסה קולית, מעקב טמפו דינמי ושחזור קפיצות (Global Resync).
  * [`HarmonicStateMachine`](android/app/src/main/java/com/aijam/companion/engine/HarmonicStateMachine.kt): מעבר אקורדים מותנה ביטחון וכניסה אוטומטית/ידנית ל־`VAMP` בהפסקות נשימה או אלתור.
  * [`VoicingEngine`](android/app/src/main/java/com/aijam/companion/engine/VoicingEngine.kt): הולכת קולות חלקה (Nearest Inversion) ב־3 סגנונות (`בלדה וארפג'יו`, `קצבי פופ/רוק`, `אמביינט מרווח`) + תופים מינימליסטיים.
  * [`PolyphonicAudioSynth`](android/app/src/main/java/com/aijam/companion/audio/PolyphonicAudioSynth.kt): סינתיסייזר סטריאו בזמן אמת על `AudioTrack` (`44.1 kHz`, Low Latency) עם מודל פסנתר פיזיקלי (הרמוניות אינהרמוניות + פטיש לבד), תופים, קול דמו אופציונלי ו־Reverb.
* **ניתוח שירים מקומי מקובץ (`MediaCodec` + `OfflineSongAnalyzer`)**:
  * ייבוא ישיר של קבצי `MP3 / WAV / M4A / OGG / FLAC` מהמכשיר (או פתיחה/שיתוף ישיר לאפליקציה דרך Intent).
  * פענוח אודיו חומרתי במכשיר, זיהוי BPM ופעימות (Spectral Flux), כרומגרמות בס+מלא, זיהוי סולם (Krumhansl-Schmuckler), פענוח אקורדים (Viterbi HMM) וחילוץ קו מלודיית שירה (`VocalAnchor`).
* **ויזואליזציה חיה ומילות שיר מסונכרנות**:
  * **Midnight Acoustic Stage Canvas (`60fps`)**: טבעות אנרגיה הפועמות לפי הביט והתופים, צורת גל שירה חיה, ואופק גובה צליל (Pitch Horizon) המשווה בזמן אמת בין קו המלודיה של השיר לשירת המשתמש.
  * **טלפרומפטר מילים מסונכרן**: הדגשת שורה פעילה עם פס התקדמות, רמזי אקורד, תמיכה בייבוא קבצי `.lrc` או הדבקת טקסט רגיל עם סנכרון אוטומטי על התיבות, וקפיצה מיידית בלחיצה על שורה.
  * **מקלדת פסנתר מוארת (`C3–B4`) ורצועת תיבות נגללת**, בתוספת ייצוא ישיר של הביצוע לקובץ MIDI (`.mid`).

---

## 📁 מבנה הפרויקט

```text
ai-jam-companion/
├── android/                          # אפליקציית Android Native (Kotlin + Jetpack Compose)
│   └── app/src/main/
│       ├── assets/demo_ballad_map.json
│       └── java/com/aijam/companion/
│           ├── MainActivity.kt
│           ├── audio/                # AudioFileDecoder, OfflineSongAnalyzer, PolyphonicAudioSynth, JamSessionController
│           ├── data/                 # LyricsParser (.lrc + סנכרון אוטומטי), SongLibraryRepository
│           ├── engine/               # Fft, RealtimePitchDetector, OnlineScoreFollower, HarmonicStateMachine, VoicingEngine, RealtimeJamTracker, MidiExporter
│           ├── model/                # SongModels + SongMapJson
│           └── ui/                   # Midnight Acoustic Stage UI, StageVisualizer, LyricsTeleprompter, PianoKeyboard, ChordRibbon, Sheets
├── offline_pipeline/                 # כלי CLI בפייתון לניתוח שירים אופליין
├── realtime_engine/                  # מנוע הייחוס והמעקב בפייתון
├── synthesis/                        # כלי MIDI / WAV בפייתון
├── song_maps/                        # מפות JSON לדוגמה
├── tests/                            # בדיקות אינטגרציה בפייתון
├── live_mic.py                       # הרצת מיקרופון חי בשורת הפקודה
└── simulate.py                       # סימולטור CLI עם דשבורד טרמינל
```

---

## 🛠️ בנייה והרצה

### בניית אפליקציית האנדרואיד (APK + בדיקות יחידה)
```bash
cd android
./gradlew testDebugUnitTest assembleDebug
```
קובץ ה־APK המוכן ייווצר ב־`android/app/build/outputs/apk/debug/app-debug.apk`.

### כלי ה־CLI בפייתון (אופציונלי)
```bash
python3 -m venv .venv
source .venv/bin/activate
pip install -r requirements.txt

# הרצת בדיקות פייתון:
python3 tests/test_pipeline.py -v

# הפקת מפת שיר מקובץ אודיו:
python3 offline_pipeline/extract_song_map.py path/to/song.wav -o song_maps/my_song.json
```

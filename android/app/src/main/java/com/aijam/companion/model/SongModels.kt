package com.aijam.companion.model

import org.json.JSONArray
import org.json.JSONObject

data class SongMetadata(
    val id: String = "demo_ballad",
    val title: String,
    val artist: String,
    val duration: Double,
    val bpm: Double,
    val sampleRate: Int = 22050,
    val totalBars: Int,
    val totalBeats: Int,
    val audioPath: String? = null
)

data class SongSection(
    val name: String,
    val label: String,
    val startTime: Double,
    val endTime: Double,
    val startBar: Int,
    val endBar: Int
)

data class ChordEvent(
    val chord: String,
    val root: String,
    val quality: String,
    val startTime: Double,
    val endTime: Double,
    val bar: Int,
    val beat: Int,
    val notes: List<Int>
)

data class BarState(
    val bar: Int,
    val section: String,
    val chord: String,
    val subChords: List<String>,
    val startTime: Double,
    val endTime: Double,
    val notes: List<Int>,
    val targetPitchMidi: Double
)

data class VocalAnchor(
    val times: DoubleArray,
    val f0Hz: DoubleArray,
    val midiNotes: DoubleArray,
    val voicedProb: DoubleArray,
    val rmsDb: DoubleArray
)

data class LyricLine(
    val startTime: Double,
    val endTime: Double,
    val bar: Int,
    val text: String
)

data class SongMap(
    val metadata: SongMetadata,
    val sections: List<SongSection>,
    val chords: List<ChordEvent>,
    val states: List<BarState>,
    val vocalAnchor: VocalAnchor,
    val lyrics: List<LyricLine> = emptyList()
)

enum class AccompanimentStyle(val id: String, val labelHe: String, val subtitleHe: String) {
    BALLAD_ARPEGGIO("ballad_arpeggio", "בלדה וארפג'יו", "פירוק רך וזורם"),
    RHYTHMIC_COMP("rhythmic_comp", "קצבי פופ/רוק", "ליווי פולס והדגשות"),
    SUSTAINED_CHORDS("sustained_chords", "אמביינט מרווח", "אקורדים פתוחים וארוכים")
}

enum class EngineMode(val labelHe: String) {
    PLAYING("מלווה בסנכרון"),
    VAMPING("גרוב מחזורי (VAMP)"),
    CONFIDENCE_WAIT("ממתין לאישור אקורד")
}

enum class InputMode(val labelHe: String) {
    MIC("מיקרופון חי"),
    DEMO("זמר דמו")
}

data class ScheduledNoteEvent(
    val notes: List<Int>,
    val startBeat: Double,
    val durationBeats: Double,
    val velocity: Int,
    val channel: Int = 0
)

data class LiveJamState(
    val isRunning: Boolean = false,
    val inputMode: InputMode = InputMode.DEMO,
    val engineMode: EngineMode = EngineMode.PLAYING,
    val currentChord: String = "C",
    val chordQuality: String = "Major Triad",
    val chordNotes: List<Int> = listOf(60, 64, 67),
    val currentBar: Int = 1,
    val totalBars: Int = 22,
    val currentBeat: Int = 1,
    val currentSection: String = "Intro",
    val refTimeSec: Double = 0.0,
    val durationSec: Double = 53.8,
    val progressPct: Float = 0f,
    val confidence: Float = 0.5f,
    val tempoRatio: Float = 1.0f,
    val singerF0Hz: Float = 0f,
    val singerNoteName: String = "---",
    val singerMidi: Float = 0f,
    val targetMidi: Float = 0f,
    val isVoiced: Boolean = false,
    val pianoVelocity: Int = 75,
    val activeKeysMidi: Set<Int> = emptySet(),
    val waveform: FloatArray = FloatArray(128),
    val pitchTrail: List<Float> = emptyList(),
    val targetPitchTrail: List<Float> = emptyList(),
    val beatPulse: Float = 0f,
    val drumPulse: Float = 0f,
    val isVampHolding: Boolean = false
)

object SongMapJson {

    fun fromJson(raw: String, fallbackId: String = "custom_song"): SongMap {
        val obj = JSONObject(raw)
        val metaObj = obj.getJSONObject("metadata")
        val metadata = SongMetadata(
            id = metaObj.optString("id", fallbackId).ifBlank { fallbackId },
            title = metaObj.optString("title", "Custom Song"),
            artist = metaObj.optString("artist", "AI Jam"),
            duration = metaObj.optDouble("duration", 60.0),
            bpm = metaObj.optDouble("bpm", 100.0),
            sampleRate = metaObj.optInt("sample_rate", 22050),
            totalBars = metaObj.optInt("total_bars", 16),
            totalBeats = metaObj.optInt("total_beats", 64),
            audioPath = metaObj.optString("audio_path", "").ifBlank { null }
        )

        val sectionsArr = obj.optJSONArray("sections") ?: JSONArray()
        val sections = buildList {
            for (i in 0 until sectionsArr.length()) {
                val s = sectionsArr.getJSONObject(i)
                add(
                    SongSection(
                        name = s.optString("name", "verse"),
                        label = s.optString("label", "Verse"),
                        startTime = s.optDouble("start_time", 0.0),
                        endTime = s.optDouble("end_time", metadata.duration),
                        startBar = s.optInt("start_bar", 1),
                        endBar = s.optInt("end_bar", metadata.totalBars)
                    )
                )
            }
        }

        val chordsArr = obj.optJSONArray("chords") ?: JSONArray()
        val chords = buildList {
            for (i in 0 until chordsArr.length()) {
                val c = chordsArr.getJSONObject(i)
                val notesArr = c.optJSONArray("notes") ?: JSONArray()
                val notes = List(notesArr.length()) { idx -> notesArr.getInt(idx) }
                add(
                    ChordEvent(
                        chord = c.optString("chord", "C"),
                        root = c.optString("root", "C"),
                        quality = c.optString("quality", "major"),
                        startTime = c.optDouble("start_time", 0.0),
                        endTime = c.optDouble("end_time", 0.5),
                        bar = c.optInt("bar", 1),
                        beat = c.optInt("beat", 1),
                        notes = notes
                    )
                )
            }
        }

        val statesArr = obj.optJSONArray("states") ?: JSONArray()
        val states = buildList {
            for (i in 0 until statesArr.length()) {
                val st = statesArr.getJSONObject(i)
                val subArr = st.optJSONArray("sub_chords") ?: JSONArray()
                val subChords = List(subArr.length()) { idx -> subArr.getString(idx) }
                val nArr = st.optJSONArray("notes") ?: JSONArray()
                val notes = List(nArr.length()) { idx -> nArr.getInt(idx) }
                add(
                    BarState(
                        bar = st.optInt("bar", i + 1),
                        section = st.optString("section", "Verse"),
                        chord = st.optString("chord", "C"),
                        subChords = subChords,
                        startTime = st.optDouble("start_time", 0.0),
                        endTime = st.optDouble("end_time", 4.0),
                        notes = notes,
                        targetPitchMidi = st.optDouble("target_pitch_midi", 0.0)
                    )
                )
            }
        }

        val vObj = obj.optJSONObject("vocal_anchor") ?: JSONObject()
        val timesArr = vObj.optJSONArray("times") ?: JSONArray()
        val f0Arr = vObj.optJSONArray("f0_hz") ?: JSONArray()
        val midiArr = vObj.optJSONArray("midi_notes") ?: JSONArray()
        val vpArr = vObj.optJSONArray("voiced_prob") ?: JSONArray()
        val rmsArr = vObj.optJSONArray("rms_db") ?: JSONArray()
        val nFrames = minOf(timesArr.length(), midiArr.length())
        val vocalAnchor = VocalAnchor(
            times = DoubleArray(nFrames) { timesArr.optDouble(it, 0.0) },
            f0Hz = DoubleArray(nFrames) { f0Arr.optDouble(it, 0.0) },
            midiNotes = DoubleArray(nFrames) { midiArr.optDouble(it, 0.0) },
            voicedProb = DoubleArray(nFrames) { vpArr.optDouble(it, 0.0) },
            rmsDb = DoubleArray(nFrames) { rmsArr.optDouble(it, -60.0) }
        )

        val lyricsArr = obj.optJSONArray("lyrics") ?: JSONArray()
        val lyrics = buildList {
            for (i in 0 until lyricsArr.length()) {
                val l = lyricsArr.getJSONObject(i)
                add(
                    LyricLine(
                        startTime = l.optDouble("start_time", 0.0),
                        endTime = l.optDouble("end_time", 4.0),
                        bar = l.optInt("bar", 1),
                        text = l.optString("text", "")
                    )
                )
            }
        }

        return SongMap(
            metadata = metadata,
            sections = sections,
            chords = chords,
            states = states,
            vocalAnchor = vocalAnchor,
            lyrics = lyrics
        )
    }

    fun toJson(songMap: SongMap): String {
        val root = JSONObject()
        val meta = JSONObject().apply {
            put("id", songMap.metadata.id)
            put("title", songMap.metadata.title)
            put("artist", songMap.metadata.artist)
            put("duration", songMap.metadata.duration)
            put("bpm", songMap.metadata.bpm)
            put("sample_rate", songMap.metadata.sampleRate)
            put("total_bars", songMap.metadata.totalBars)
            put("total_beats", songMap.metadata.totalBeats)
            if (songMap.metadata.audioPath != null) {
                put("audio_path", songMap.metadata.audioPath)
            }
        }
        root.put("metadata", meta)

        val secArr = JSONArray()
        songMap.sections.forEach { s ->
            secArr.put(
                JSONObject().apply {
                    put("name", s.name)
                    put("label", s.label)
                    put("start_time", s.startTime)
                    put("end_time", s.endTime)
                    put("start_bar", s.startBar)
                    put("end_bar", s.endBar)
                }
            )
        }
        root.put("sections", secArr)

        val chArr = JSONArray()
        songMap.chords.forEach { c ->
            chArr.put(
                JSONObject().apply {
                    put("chord", c.chord)
                    put("root", c.root)
                    put("quality", c.quality)
                    put("start_time", c.startTime)
                    put("end_time", c.endTime)
                    put("bar", c.bar)
                    put("beat", c.beat)
                    put("notes", JSONArray(c.notes))
                }
            )
        }
        root.put("chords", chArr)

        val stArr = JSONArray()
        songMap.states.forEach { st ->
            stArr.put(
                JSONObject().apply {
                    put("bar", st.bar)
                    put("section", st.section)
                    put("chord", st.chord)
                    put("sub_chords", JSONArray(st.subChords))
                    put("start_time", st.startTime)
                    put("end_time", st.endTime)
                    put("notes", JSONArray(st.notes))
                    put("target_pitch_midi", st.targetPitchMidi)
                }
            )
        }
        root.put("states", stArr)

        val vObj = JSONObject().apply {
            put("times", JSONArray(songMap.vocalAnchor.times.toList()))
            put("f0_hz", JSONArray(songMap.vocalAnchor.f0Hz.toList()))
            put("midi_notes", JSONArray(songMap.vocalAnchor.midiNotes.toList()))
            put("voiced_prob", JSONArray(songMap.vocalAnchor.voicedProb.toList()))
            put("rms_db", JSONArray(songMap.vocalAnchor.rmsDb.toList()))
        }
        root.put("vocal_anchor", vObj)

        val lyrArr = JSONArray()
        songMap.lyrics.forEach { l ->
            lyrArr.put(
                JSONObject().apply {
                    put("start_time", l.startTime)
                    put("end_time", l.endTime)
                    put("bar", l.bar)
                    put("text", l.text)
                }
            )
        }
        root.put("lyrics", lyrArr)
        return root.toString()
    }
}

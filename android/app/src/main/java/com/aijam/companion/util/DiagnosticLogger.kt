package com.aijam.companion.util

import android.os.Build
import com.aijam.companion.model.LiveJamState
import com.aijam.companion.model.SongMap
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

/**
 * In-app flight recorder and diagnostic logger.
 * Captures offline song analysis summaries, real-time sync/chord/beat events,
 * microphone RMS levels, and anomalies so the user can copy or share a full
 * diagnostic report directly from the device in one tap.
 */
object DiagnosticLogger {

    enum class Category {
        SYSTEM, ANALYSIS, PLAYBACK, SYNC, CHORD, VAMP, AUDIO, WARN, ERROR
    }

    data class LogEntry(
        val timestampMs: Long,
        val elapsedSessionSec: Double,
        val category: Category,
        val message: String
    )

    private const val MAX_ENTRIES = 1200
    private val lock = Any()
    private val entries = ArrayDeque<LogEntry>(MAX_ENTRIES)
    private var sessionStartMs: Long = System.currentTimeMillis()
    var lastAnalysisReport: String = "טרם בוצע ניתוח שיר בסשן זה."
        private set

    // Session telemetry counters
    private var totalBeatsTriggered = 0
    private var totalChordChanges = 0
    private var totalVampEntries = 0
    private var maxBackwardJumpSec = 0.0
    private var maxForwardJumpSec = 0.0
    private var voicedFramesCount = 0
    private var totalFramesCount = 0
    private var minMicRmsDb = 0.0
    private var maxMicRmsDb = -100.0
    private var lastRefTimeSec = 0.0

    fun startNewSession(modeName: String, songTitle: String) {
        synchronized(lock) {
            sessionStartMs = System.currentTimeMillis()
            totalBeatsTriggered = 0
            totalChordChanges = 0
            totalVampEntries = 0
            maxBackwardJumpSec = 0.0
            maxForwardJumpSec = 0.0
            voicedFramesCount = 0
            totalFramesCount = 0
            minMicRmsDb = 0.0
            maxMicRmsDb = -100.0
            lastRefTimeSec = 0.0
            logInternal(Category.PLAYBACK, "התחלת סשן ($modeName) עבור השיר: '$songTitle'")
        }
    }

    fun onSessionStart(modeName: String, songTitle: String) = startNewSession(modeName, songTitle)

    fun log(category: Category, message: String) {
        synchronized(lock) {
            logInternal(category, message)
        }
    }

    fun log(tag: String, message: String) {
        val cat = when (tag.uppercase(Locale.US)) {
            "IMPORT", "ANALYSIS" -> Category.ANALYSIS
            "DEMO", "SESSION", "MODE", "STYLE", "SEEK", "SONG" -> Category.PLAYBACK
            "VAMP" -> Category.VAMP
            "AUDIO", "MIC" -> Category.AUDIO
            "WARN" -> Category.WARN
            "ERROR", "MIC_ERR" -> Category.ERROR
            else -> Category.SYSTEM
        }
        log(cat, "[$tag] $message")
    }

    fun recordAnalysisSummary(
        songMap: SongMap,
        detectedKey: String,
        analysisDurationMs: Long,
        rawAudioSaved: Boolean
    ) {
        synchronized(lock) {
            val voicedRatio = if (songMap.vocalAnchor.voicedProb.isNotEmpty()) {
                val voiced = songMap.vocalAnchor.voicedProb.count { it > 0.35 }
                (voiced * 100.0 / songMap.vocalAnchor.voicedProb.size).roundToInt()
            } else 0

            val barsPreview = songMap.states.take(24).joinToString(" | ") { st ->
                "B${st.bar}:${st.chord}(${"%.1f".format(Locale.US, st.startTime)}s)"
            }
            val moreBars = if (songMap.states.size > 24) " ... (+${songMap.states.size - 24} תיבות)" else ""

            val sb = StringBuilder()
            sb.appendLine("• שם שיר: ${songMap.metadata.title} (${songMap.metadata.id})")
            sb.appendLine("• משך: ${"%.1f".format(Locale.US, songMap.metadata.duration)}s | טמפו: ${songMap.metadata.bpm} BPM | סולם משוער: $detectedKey")
            sb.appendLine("• תיבות: ${songMap.metadata.totalBars} | פעימות: ${songMap.metadata.totalBeats} | זמן עיבוד: ${analysisDurationMs}ms")
            sb.appendLine("• אודיו מקורי נשמר להשמעה: ${if (rawAudioSaved) "כן (${songMap.metadata.audioPath})" else "לא"}")
            sb.appendLine("• עוגן שירה (VocalAnchor): ${songMap.vocalAnchor.times.size} פריימים ($voicedRatio% שירה מזוהה)")
            sb.appendLine("• רצף תיבות ואקורדים: $barsPreview$moreBars")

            lastAnalysisReport = sb.toString().trim()
            logInternal(
                Category.ANALYSIS,
                "ניתוח הושלם: '${songMap.metadata.title}' | ${songMap.metadata.bpm} BPM | סולם $detectedKey | ${songMap.metadata.totalBars} תיבות"
            )
        }
    }

    fun recordFrameStep(
        refTimeSec: Double,
        bar: Int,
        beat: Int,
        chord: String,
        mode: String,
        isVoiced: Boolean,
        singerNote: String,
        rmsDb: Double,
        confidence: Double,
        tempoRatio: Double,
        beatTriggered: Boolean,
        chordChanged: Boolean,
        enteredVamp: Boolean
    ) {
        synchronized(lock) {
            totalFramesCount++
            if (isVoiced) voicedFramesCount++
            if (rmsDb > -95.0) {
                if (totalFramesCount == 1 || rmsDb < minMicRmsDb) minMicRmsDb = rmsDb
                if (rmsDb > maxMicRmsDb) maxMicRmsDb = rmsDb
            }

            if (totalFramesCount > 1) {
                val delta = refTimeSec - lastRefTimeSec
                if (delta < -0.05 && delta < maxBackwardJumpSec) {
                    maxBackwardJumpSec = delta
                    logInternal(
                        Category.WARN,
                        "קפיצה אחורה בזמן ייחוס: ${"%.2f".format(Locale.US, delta)}s (לתיבה $bar)"
                    )
                } else if (delta > 0.45 && delta > maxForwardJumpSec) {
                    maxForwardJumpSec = delta
                    logInternal(
                        Category.WARN,
                        "קפיצה חדה קדימה בזמן ייחוס: +${"%.2f".format(Locale.US, delta)}s (לתיבה $bar)"
                    )
                }
            }
            lastRefTimeSec = refTimeSec

            if (enteredVamp) {
                totalVampEntries++
                logInternal(Category.VAMP, "כניסה למצב VAMP בתיבה $bar על אקורד $chord")
            }
            if (chordChanged) {
                totalChordChanges++
                logInternal(
                    Category.CHORD,
                    "מעבר אקורד -> $chord (תיבה $bar, ביט $beat, t=${"%.2f".format(Locale.US, refTimeSec)}s, conf=${(confidence * 100).roundToInt()}%)"
                )
            }
            if (beatTriggered) {
                totalBeatsTriggered++
                if (beat == 1 || totalBeatsTriggered % 4 == 1) {
                    logInternal(
                        Category.SYNC,
                        "תיבה $bar ביט $beat | אקורד=$chord | מצב=$mode | t=${"%.2f".format(Locale.US, refTimeSec)}s | קול=$singerNote (${rmsDb.roundToInt()}dB) | x${"%.2f".format(Locale.US, tempoRatio)}"
                    )
                }
            }
        }
    }

    fun getRecentEntries(limit: Int = 120): List<LogEntry> {
        synchronized(lock) {
            val takeCount = limit.coerceAtMost(entries.size)
            return entries.takeLast(takeCount)
        }
    }

    fun clear() {
        synchronized(lock) {
            entries.clear()
            logInternal(Category.SYSTEM, "הלוגים נוקו ידנית")
        }
    }

    fun buildFullReport(songMap: SongMap, liveState: LiveJamState): String {
        synchronized(lock) {
            val dateFmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
            val timeFmt = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
            val voicedPct = if (totalFramesCount > 0) {
                (voicedFramesCount * 100.0 / totalFramesCount).roundToInt()
            } else 0

            val sb = StringBuilder()
            sb.appendLine("=== AI Jam Companion — Diagnostic Report ===")
            sb.appendLine("Generated: ${dateFmt.format(Date())}")
            sb.appendLine("Device: ${Build.MANUFACTURER} ${Build.MODEL} (Android ${Build.VERSION.RELEASE}, SDK ${Build.VERSION.SDK_INT})")
            sb.appendLine()
            sb.appendLine("--- 1. Live Engine Snapshot ---")
            sb.appendLine("Running: ${liveState.isRunning} | InputMode: ${liveState.inputMode} | EngineMode: ${liveState.engineMode}")
            sb.appendLine("Song: ${songMap.metadata.title} | BPM: ${songMap.metadata.bpm} | Position: ${"%.2f".format(Locale.US, liveState.refTimeSec)}s / ${"%.2f".format(Locale.US, liveState.durationSec)}s")
            sb.appendLine("Bar/Beat: ${liveState.currentBar}/${liveState.totalBars} (Beat ${liveState.currentBeat}) | Chord: ${liveState.currentChord}")
            sb.appendLine("Singer: note=${liveState.singerNoteName} (${liveState.singerF0Hz.roundToInt()}Hz, midi=${"%.1f".format(Locale.US, liveState.singerMidi)}) | TargetMidi=${"%.1f".format(Locale.US, liveState.targetMidi)} | Voiced=${liveState.isVoiced}")
            sb.appendLine("Confidence: ${(liveState.confidence * 100).roundToInt()}% | TempoRatio: x${"%.2f".format(Locale.US, liveState.tempoRatio)}")
            sb.appendLine()
            sb.appendLine("--- 2. Session Telemetry Counters ---")
            sb.appendLine("Frames Processed: $totalFramesCount | Voiced Frames: $voicedFramesCount ($voicedPct%)")
            sb.appendLine("Beats Triggered: $totalBeatsTriggered | Chord Transitions: $totalChordChanges | Vamp Entries: $totalVampEntries")
            sb.appendLine("Mic RMS Range: [${"%.1f".format(Locale.US, minMicRmsDb)} dB .. ${"%.1f".format(Locale.US, maxMicRmsDb)} dB]")
            sb.appendLine("Max RefTime Step: forward=+${"%.2f".format(Locale.US, maxForwardJumpSec)}s, backward=${"%.2f".format(Locale.US, maxBackwardJumpSec)}s")
            sb.appendLine()
            sb.appendLine("--- 3. Last Song Analysis Report ---")
            sb.appendLine(lastAnalysisReport)
            sb.appendLine()
            sb.appendLine("--- 4. Event Timeline (Last ${entries.size} events) ---")
            for (e in entries) {
                val wall = timeFmt.format(Date(e.timestampMs))
                val rel = "%6.2fs".format(Locale.US, e.elapsedSessionSec)
                sb.appendLine("[$wall | +$rel] [${e.category.name}] ${e.message}")
            }
            return sb.toString()
        }
    }

    private fun logInternal(category: Category, message: String) {
        val now = System.currentTimeMillis()
        val elapsedSec = ((now - sessionStartMs).coerceAtLeast(0L)) / 1000.0
        if (entries.size >= MAX_ENTRIES) {
            entries.removeFirst()
        }
        entries.addLast(
            LogEntry(
                timestampMs = now,
                elapsedSessionSec = elapsedSec,
                category = category,
                message = message
            )
        )
    }
}

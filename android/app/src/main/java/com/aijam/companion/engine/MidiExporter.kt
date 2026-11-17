package com.aijam.companion.engine

import com.aijam.companion.model.ScheduledNoteEvent
import java.io.ByteArrayOutputStream
import java.io.OutputStream
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Pure Kotlin Standard MIDI File (SMF Type 0, 480 PPQ) writer.
 * Exports recorded live jam piano (Channel 0) + drums (Channel 9) directly on device.
 */
object MidiExporter {

    private const val TICKS_PER_BEAT = 480

    private data class RawMidiEvent(
        val tick: Int,
        val order: Int, // 0 = noteOff, 1 = noteOn
        val status: Int,
        val data1: Int,
        val data2: Int
    )

    fun buildMidiBytes(timeline: List<ScheduledNoteEvent>, bpm: Double): ByteArray {
        val trackStream = ByteArrayOutputStream()

        // Tempo meta-event: FF 51 03 <3-byte microseconds per quarter note>
        val usPerQuarter = (60_000_000.0 / bpm.coerceIn(40.0, 220.0)).roundToInt()
        writeVarLen(trackStream, 0)
        trackStream.write(0xFF)
        trackStream.write(0x51)
        trackStream.write(0x03)
        trackStream.write((usPerQuarter shr 16) and 0xFF)
        trackStream.write((usPerQuarter shr 8) and 0xFF)
        trackStream.write(usPerQuarter and 0xFF)

        // Program change: Acoustic Grand Piano (0) on Channel 0
        writeVarLen(trackStream, 0)
        trackStream.write(0xC0)
        trackStream.write(0x00)

        val events = ArrayList<RawMidiEvent>(timeline.size * 6)
        for (item in timeline) {
            if (item.durationBeats <= 0.0 || item.notes.isEmpty()) continue
            val startTick = max(0, (item.startBeat * TICKS_PER_BEAT).roundToInt())
            val durTicks = max(20, (item.durationBeats * TICKS_PER_BEAT).roundToInt())
            val endTick = startTick + max(10, durTicks - 15)
            val ch = item.channel.coerceIn(0, 15)
            val vel = item.velocity.coerceIn(1, 127)

            for (note in item.notes) {
                if (note in 0..127) {
                    events.add(RawMidiEvent(startTick, 1, 0x90 or ch, note, vel))
                    events.add(RawMidiEvent(endTick, 0, 0x80 or ch, note, 0))
                }
            }
        }

        events.sortWith(compareBy<RawMidiEvent> { it.tick }.thenBy { it.order })

        var currTick = 0
        for (ev in events) {
            val delta = max(0, ev.tick - currTick)
            currTick = ev.tick
            writeVarLen(trackStream, delta)
            trackStream.write(ev.status)
            trackStream.write(ev.data1)
            trackStream.write(ev.data2)
        }

        // End of Track meta-event
        writeVarLen(trackStream, TICKS_PER_BEAT)
        trackStream.write(0xFF)
        trackStream.write(0x2F)
        trackStream.write(0x00)

        val trackBytes = trackStream.toByteArray()
        val out = ByteArrayOutputStream()
        // Header chunk: MThd, len=6, format=0, ntrks=1, division=480
        out.write(byteArrayOf('M'.code.toByte(), 'T'.code.toByte(), 'h'.code.toByte(), 'd'.code.toByte()))
        writeInt32(out, 6)
        writeInt16(out, 0)
        writeInt16(out, 1)
        writeInt16(out, TICKS_PER_BEAT)

        // Track chunk: MTrk, len, data
        out.write(byteArrayOf('M'.code.toByte(), 'T'.code.toByte(), 'r'.code.toByte(), 'k'.code.toByte()))
        writeInt32(out, trackBytes.size)
        out.write(trackBytes)
        return out.toByteArray()
    }

    private fun writeVarLen(out: OutputStream, value: Int) {
        var v = value
        var buffer = v and 0x7F
        while ((v ushr 7) > 0) {
            v = v ushr 7
            buffer = (buffer shl 8) or ((v and 0x7F) or 0x80)
        }
        while (true) {
            out.write(buffer and 0xFF)
            if ((buffer and 0x80) != 0) buffer = buffer ushr 8 else break
        }
    }

    private fun writeInt32(out: OutputStream, v: Int) {
        out.write((v ushr 24) and 0xFF)
        out.write((v ushr 16) and 0xFF)
        out.write((v ushr 8) and 0xFF)
        out.write(v and 0xFF)
    }

    private fun writeInt16(out: OutputStream, v: Int) {
        out.write((v ushr 8) and 0xFF)
        out.write(v and 0xFF)
    }
}

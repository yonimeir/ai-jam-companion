package com.aijam.companion.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Process
import com.aijam.companion.engine.DrumHit
import com.aijam.companion.engine.VoicingEngine
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.tanh

/**
 * Low-latency real-time polyphonic stereo synthesizer running directly on AudioTrack (44.1 kHz):
 * - Physical-modeled acoustic piano (inharmonic partials, felt hammer attack, stereo panning)
 * - Minimalist drum kit (kick, rimshot, brush snare, closed hi-hat, ride cymbal)
 * - Expressive Demo Singer formant oscillator (optional audible demo vocalist)
 * - Warm stereo Schroeder feedback reverb tank
 */
class PolyphonicAudioSynth(
    private val sampleRate: Int = 44100
) {
    private val maxVoices = 24
    private val twoPi = 2.0 * PI

    private sealed class Command {
        data class TriggerPiano(val midiNotes: List<Int>, val velocity: Int, val durationSec: Double) : Command()
        data class TriggerDrums(val hits: List<DrumHit>) : Command()
        object Panic : Command()
    }

    private class PianoVoice {
        var active = false
        var midi = 60
        var velocityGain = 0f
        var brightness = 0.5f
        var leftGain = 0.7f
        var rightGain = 0.7f
        var elapsedSec = 0f
        var durationSec = 1.0f
        var releaseEnv = 1f
        var phase1 = 0.0
        var phase2 = 0.0
        var phase3 = 0.0
        var phase4 = 0.0
        var inc1 = 0.0
        var inc2 = 0.0
        var inc3 = 0.0
        var inc4 = 0.0
        var decayRate = 1.4f
        var age = 0L
    }

    private class DrumVoice {
        var active = false
        var note = 36
        var gain = 0f
        var elapsedSec = 0f
        var durationSec = 0.3f
        var phase = 0.0
        var noiseState = 22222
    }

    private val commands = ConcurrentLinkedQueue<Command>()
    private val pianoPool = Array(maxVoices) { PianoVoice() }
    private val drumPool = Array(10) { DrumVoice() }
    private var voiceAgeCounter = 0L

    @Volatile var reverbMix: Float = 0.28f
    @Volatile private var demoTargetF0: Double = 0.0
    @Volatile private var demoTargetAmp: Float = 0f
    @Volatile private var demoVoiced: Boolean = false

    @Volatile private var running = false
    private var audioThread: Thread? = null

    fun start() {
        if (running) return
        running = true
        audioThread = Thread(::audioLoop, "AIJam-AudioSynth").apply {
            priority = Thread.MAX_PRIORITY
            start()
        }
    }

    fun stop() {
        running = false
        audioThread?.join(400)
        audioThread = null
        clearVoices()
    }

    fun panic() {
        demoVoiced = false
        demoTargetAmp = 0f
        commands.add(Command.Panic)
    }

    fun triggerPianoNotes(midiNotes: List<Int>, velocity: Int, durationSec: Double) {
        if (midiNotes.isEmpty()) return
        commands.add(Command.TriggerPiano(midiNotes.toList(), velocity.coerceIn(1, 127), durationSec.coerceIn(0.12, 4.5)))
    }

    fun triggerDrums(hits: List<DrumHit>) {
        if (hits.isEmpty()) return
        commands.add(Command.TriggerDrums(hits.toList()))
    }

    fun setDemoVoice(f0Hz: Double, voiced: Boolean, amplitude: Float) {
        demoTargetF0 = f0Hz
        demoVoiced = voiced && f0Hz > 50.0
        demoTargetAmp = if (demoVoiced) amplitude.coerceIn(0f, 0.35f) else 0f
    }

    private fun clearVoices() {
        commands.clear()
        pianoPool.forEach { it.active = false }
        drumPool.forEach { it.active = false }
    }

    private fun audioLoop() {
        Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO)
        val framesPerChunk = 512
        val minBufBytes = AudioTrack.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_OUT_STEREO,
            AudioFormat.ENCODING_PCM_FLOAT
        ).coerceAtLeast(framesPerChunk * 2 * 4 * 2)

        val track = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                    .setSampleRate(sampleRate)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                    .build()
            )
            .setBufferSizeInBytes(minBufBytes)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
            .build()

        // Stereo Schroeder-style comb delay lines
        val combLenL = (sampleRate * 0.043).toInt()
        val combLenR = (sampleRate * 0.059).toInt()
        val apLen = (sampleRate * 0.017).toInt()
        val combL = FloatArray(combLenL)
        val combR = FloatArray(combLenR)
        val apBuf = FloatArray(apLen)
        var cIdxL = 0
        var cIdxR = 0
        var apIdx = 0
        var lpL = 0f
        var lpR = 0f

        var demoPhase = 0.0
        var demoSmoothedF0 = 220.0
        var demoEnv = 0f
        var vibratoPhase = 0.0

        val dt = 1f / sampleRate
        val dtDouble = 1.0 / sampleRate
        val buffer = FloatArray(framesPerChunk * 2)

        try {
            track.play()
            while (running) {
                drainCommands()

                val revAmount = reverbMix.coerceIn(0f, 1f)
                val dryGain = 1f - revAmount * 0.25f
                val wetGain = revAmount * 0.48f

                for (i in 0 until framesPerChunk) {
                    var sumL = 0f
                    var sumR = 0f

                    // 1. Acoustic Piano voices
                    for (v in pianoPool) {
                        if (!v.active) continue
                        val t = v.elapsedSec
                        val attack = min(1f, t / 0.0045f)
                        val bodyEnv = exp(-t * v.decayRate)
                        if (t > v.durationSec) {
                            v.releaseEnv *= 0.9965f
                            if (v.releaseEnv < 0.002f) {
                                v.active = false
                                continue
                            }
                        }

                        val env = attack * bodyEnv * v.releaseEnv * v.velocityGain
                        val b = v.brightness
                        val p1 = sin(v.phase1).toFloat()
                        val p2 = sin(v.phase2).toFloat() * (0.34f + 0.16f * b) * exp(-t * 1.8f)
                        val p3 = sin(v.phase3).toFloat() * (0.12f + 0.14f * b) * exp(-t * 3.1f)
                        val p4 = sin(v.phase4).toFloat() * (0.04f + 0.09f * b) * exp(-t * 5.2f)
                        val hammer = if (t < 0.014f) {
                            (sin(v.phase2 * 2.5).toFloat() * exp(-t * 240f) * 0.16f * b)
                        } else 0f

                        val sample = (p1 + p2 + p3 + p4 + hammer) * env * 0.34f
                        sumL += sample * v.leftGain
                        sumR += sample * v.rightGain

                        v.phase1 = wrapPhase(v.phase1 + v.inc1)
                        v.phase2 = wrapPhase(v.phase2 + v.inc2)
                        v.phase3 = wrapPhase(v.phase3 + v.inc3)
                        v.phase4 = wrapPhase(v.phase4 + v.inc4)
                        v.elapsedSec += dt
                    }

                    // 2. Minimalist Drum hits
                    for (d in drumPool) {
                        if (!d.active) continue
                        val t = d.elapsedSec
                        if (t >= d.durationSec) {
                            d.active = false
                            continue
                        }
                        d.noiseState = (d.noiseState * 1664525 + 1013904223)
                        val white = (d.noiseState ushr 16).toShort() / 32768f

                        val hitSample = when (d.note) {
                            VoicingEngine.DRUM_KICK -> {
                                val f = 48.0 + 95.0 * exp(-t * 28.0)
                                d.phase = wrapPhase(d.phase + twoPi * f * dtDouble)
                                val click = if (t < 0.008f) white * exp(-t * 420f) * 0.25f else 0f
                                (sin(d.phase).toFloat() * exp(-t * 10.5f) + click) * 0.65f
                            }
                            VoicingEngine.DRUM_RIM -> {
                                val body = sin(twoPi * 410.0 * t).toFloat() * exp(-t * 42f) * 0.45f
                                val snap = white * exp(-t * 65f) * 0.35f
                                body + snap
                            }
                            VoicingEngine.DRUM_SNARE_BRUSH -> {
                                val body = sin(twoPi * 185.0 * t).toFloat() * exp(-t * 22f) * 0.32f
                                val brush = white * exp(-t * 15f) * 0.38f
                                body + brush
                            }
                            VoicingEngine.DRUM_CLOSED_HH -> {
                                val metallic = (sin(twoPi * 5240.0 * t) + sin(twoPi * 7620.0 * t)).toFloat() * 0.18f
                                (white * 0.6f + metallic) * exp(-t * 46f) * 0.26f
                            }
                            else -> { // Ride
                                val shimmer = (sin(twoPi * 3120.0 * t) + sin(twoPi * 4890.0 * t)).toFloat() * 0.22f
                                (white * 0.35f + shimmer) * exp(-t * 7.5f) * 0.28f
                            }
                        } * d.gain

                        sumL += hitSample * 0.85f
                        sumR += hitSample * 0.85f
                        d.elapsedSec += dt
                    }

                    // 3. Optional Demo Singer Formant Voice
                    val targetAmp = demoTargetAmp
                    demoEnv += (targetAmp - demoEnv) * 0.0045f
                    if (demoEnv > 0.0008f) {
                        val targetF0 = demoTargetF0
                        if (targetF0 > 50.0) {
                            demoSmoothedF0 += (targetF0 - demoSmoothedF0) * 0.025
                        }
                        vibratoPhase = wrapPhase(vibratoPhase + twoPi * 5.2 * dtDouble)
                        val vibF0 = demoSmoothedF0 * (1.0 + 0.0065 * sin(vibratoPhase))
                        demoPhase = wrapPhase(demoPhase + twoPi * vibF0 * dtDouble)
                        val voiceWave = (
                            0.62 * sin(demoPhase) +
                                0.26 * sin(2.0 * demoPhase + 0.3) +
                                0.11 * sin(3.0 * demoPhase) +
                                0.05 * sin(4.0 * demoPhase)
                            ).toFloat() * demoEnv
                        sumL += voiceWave * 0.72f
                        sumR += voiceWave * 0.72f
                    }

                    // 4. Stereo Reverb + Soft Limiter
                    val revIn = (sumL + sumR) * 0.5f
                    val dL = combL[cIdxL]
                    val dR = combR[cIdxR]
                    lpL = lpL * 0.35f + dL * 0.65f
                    lpR = lpR * 0.35f + dR * 0.65f
                    combL[cIdxL] = revIn + lpL * 0.74f
                    combR[cIdxR] = revIn + lpR * 0.72f
                    cIdxL = (cIdxL + 1) % combLenL
                    cIdxR = (cIdxR + 1) % combLenR

                    val apIn = (dL + dR) * 0.5f
                    val apDelayed = apBuf[apIdx]
                    val apOut = -apIn + apDelayed
                    apBuf[apIdx] = apIn + apDelayed * 0.45f
                    apIdx = (apIdx + 1) % apLen

                    val outL = tanh((sumL * dryGain + (dL + apOut * 0.35f) * wetGain).toDouble()).toFloat()
                    val outR = tanh((sumR * dryGain + (dR + apOut * 0.35f) * wetGain).toDouble()).toFloat()

                    val idx = i * 2
                    buffer[idx] = outL
                    buffer[idx + 1] = outR
                }

                track.write(buffer, 0, buffer.size, AudioTrack.WRITE_BLOCKING)
            }
        } finally {
            runCatching {
                track.pause()
                track.flush()
                track.stop()
                track.release()
            }
        }
    }

    private fun drainCommands() {
        while (true) {
            val cmd = commands.poll() ?: break
            when (cmd) {
                is Command.Panic -> {
                    pianoPool.forEach { it.active = false }
                    drumPool.forEach { it.active = false }
                }
                is Command.TriggerPiano -> {
                    for (note in cmd.midiNotes) {
                        allocatePianoVoice(note, cmd.velocity, cmd.durationSec.toFloat())
                    }
                }
                is Command.TriggerDrums -> {
                    for (hit in cmd.hits) {
                        allocateDrumVoice(hit.note, hit.velocity)
                    }
                }
            }
        }
    }

    private fun allocatePianoVoice(midi: Int, velocity: Int, durationSec: Float) {
        // Reuse inactive voice, same MIDI note, or steal oldest voice
        val slot = pianoPool.firstOrNull { !it.active }
            ?: pianoPool.firstOrNull { it.midi == midi }
            ?: pianoPool.minBy { it.age }

        val f0 = 440.0 * 2.0.pow((midi - 69) / 12.0)
        // Slight piano string inharmonicity
        val bCoeff = 0.00014
        val f1 = f0
        val f2 = f0 * 2.0 * kotlin.math.sqrt(1.0 + 4.0 * bCoeff)
        val f3 = f0 * 3.0 * kotlin.math.sqrt(1.0 + 9.0 * bCoeff)
        val f4 = f0 * 4.0 * kotlin.math.sqrt(1.0 + 16.0 * bCoeff)

        val pan = ((midi - 60) / 36f).coerceIn(-0.45f, 0.45f)
        val normVel = (velocity / 127f).coerceIn(0.15f, 1f)

        slot.active = true
        slot.midi = midi
        slot.velocityGain = normVel * normVel * 0.9f + 0.1f
        slot.brightness = normVel
        slot.leftGain = 0.72f - pan * 0.35f
        slot.rightGain = 0.72f + pan * 0.35f
        slot.elapsedSec = 0f
        slot.durationSec = durationSec
        slot.releaseEnv = 1f
        slot.phase1 = 0.0
        slot.phase2 = 0.0
        slot.phase3 = 0.0
        slot.phase4 = 0.0
        slot.inc1 = twoPi * f1 / sampleRate
        slot.inc2 = if (f2 < sampleRate * 0.45) twoPi * f2 / sampleRate else 0.0
        slot.inc3 = if (f3 < sampleRate * 0.45) twoPi * f3 / sampleRate else 0.0
        slot.inc4 = if (f4 < sampleRate * 0.45) twoPi * f4 / sampleRate else 0.0
        // Higher notes decay slightly faster like a real piano soundboard
        slot.decayRate = (1.15f + max(0, midi - 48) * 0.032f).coerceIn(1.0f, 3.2f)
        slot.age = ++voiceAgeCounter
    }

    private fun allocateDrumVoice(note: Int, velocity: Int) {
        val slot = drumPool.firstOrNull { !it.active } ?: drumPool.first()
        slot.active = true
        slot.note = note
        slot.gain = (velocity / 127f).coerceIn(0.1f, 1f)
        slot.elapsedSec = 0f
        slot.phase = 0.0
        slot.durationSec = when (note) {
            VoicingEngine.DRUM_KICK -> 0.28f
            VoicingEngine.DRUM_RIDE -> 0.55f
            VoicingEngine.DRUM_SNARE_BRUSH -> 0.22f
            else -> 0.14f
        }
    }

    private fun wrapPhase(p: Double): Double = if (p >= twoPi) p - twoPi else p
}

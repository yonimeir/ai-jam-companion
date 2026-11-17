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
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tanh

/**
 * Low-latency real-time polyphonic stereo synthesizer running directly on AudioTrack (44.1 kHz):
 * - Warm physical-modeled felt acoustic piano (click-free voice allocation, dynamic low-pass filter,
 *   natural soundboard decay, stereo panning)
 * - Soft acoustic ballad percussion (warm sub kick, wood rim, brush snare, filtered shaker/hat)
 * - Damped 4-comb + 2-allpass stereo studio reverb tank with zero metallic ringing
 */
class PolyphonicAudioSynth(
    private val sampleRate: Int = 44100
) {
    private val maxVoices = 32
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
        var brightness = 0.4f
        var leftGain = 0.7f
        var rightGain = 0.7f
        var elapsedSec = 0f
        var durationSec = 1.2f
        var releaseEnv = 1f
        var fastFadeOut = false
        var phase1 = 0.0
        var phase2 = 0.0
        var phase3 = 0.0
        var phase4 = 0.0
        var inc1 = 0.0
        var inc2 = 0.0
        var inc3 = 0.0
        var inc4 = 0.0
        var decayRate = 1.25f
        var lpState = 0f
        var age = 0L
    }

    private class DrumVoice {
        var active = false
        var note = 36
        var gain = 0f
        var elapsedSec = 0f
        var durationSec = 0.3f
        var phase = 0.0
        var lpState = 0f
        var hpState = 0f
        var noiseState = 22222
    }

    private val commands = ConcurrentLinkedQueue<Command>()
    private val pianoPool = Array(maxVoices) { PianoVoice() }
    private val drumPool = Array(12) { DrumVoice() }
    private var voiceAgeCounter = 0L

    @Volatile var reverbMix: Float = 0.26f

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
        commands.add(Command.Panic)
    }

    fun triggerPianoNotes(midiNotes: List<Int>, velocity: Int, durationSec: Double) {
        if (midiNotes.isEmpty()) return
        commands.add(
            Command.TriggerPiano(
                midiNotes = midiNotes.toList(),
                velocity = velocity.coerceIn(1, 127),
                durationSec = durationSec.coerceIn(0.25, 4.5)
            )
        )
    }

    fun triggerDrums(hits: List<DrumHit>) {
        if (hits.isEmpty()) return
        commands.add(Command.TriggerDrums(hits.toList()))
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

        // 4-comb parallel damped hall reverb + 2 allpass diffusers
        val cLen1 = (sampleRate * 0.0297).toInt()
        val cLen2 = (sampleRate * 0.0371).toInt()
        val cLen3 = (sampleRate * 0.0411).toInt()
        val cLen4 = (sampleRate * 0.0437).toInt()
        val comb1 = FloatArray(cLen1)
        val comb2 = FloatArray(cLen2)
        val comb3 = FloatArray(cLen3)
        val comb4 = FloatArray(cLen4)
        var cIdx1 = 0
        var cIdx2 = 0
        var cIdx3 = 0
        var cIdx4 = 0
        var cLp1 = 0f
        var cLp2 = 0f
        var cLp3 = 0f
        var cLp4 = 0f

        val apLen1 = (sampleRate * 0.0050).toInt()
        val apLen2 = (sampleRate * 0.0017).toInt()
        val apBuf1 = FloatArray(apLen1)
        val apBuf2 = FloatArray(apLen2)
        var apIdx1 = 0
        var apIdx2 = 0

        val dt = 1f / sampleRate
        val dtDouble = 1.0 / sampleRate
        val buffer = FloatArray(framesPerChunk * 2)

        try {
            track.play()
            while (running) {
                drainCommands()

                val revAmount = reverbMix.coerceIn(0f, 1f)
                val dryGain = 1f - revAmount * 0.18f
                val wetGain = revAmount * 0.28f

                for (i in 0 until framesPerChunk) {
                    var sumL = 0f
                    var sumR = 0f

                    // 1. Warm Felt Acoustic Piano voices
                    for (v in pianoPool) {
                        if (!v.active) continue
                        val t = v.elapsedSec
                        // Smooth 8ms cosine-like attack so re-strikes never click
                        val attack = min(1f, t / 0.008f)
                        val bodyEnv = exp(-t * v.decayRate)
                        if (v.fastFadeOut) {
                            v.releaseEnv *= 0.988f
                            if (v.releaseEnv < 0.002f) {
                                v.active = false
                                continue
                            }
                        } else if (t > v.durationSec) {
                            v.releaseEnv *= 0.9978f
                            if (v.releaseEnv < 0.0015f) {
                                v.active = false
                                continue
                            }
                        }

                        val env = attack * bodyEnv * v.releaseEnv * v.velocityGain
                        val b = v.brightness

                        // Warm harmonic profile: strong fundamental, gentle octave & twelfth, soft 4th partial
                        val p1 = sin(v.phase1).toFloat()
                        val p2 = sin(v.phase2).toFloat() * (0.26f + 0.12f * b) * exp(-t * 1.9f)
                        val p3 = sin(v.phase3).toFloat() * (0.07f + 0.08f * b) * exp(-t * 3.4f)
                        val p4 = sin(v.phase4).toFloat() * (0.015f + 0.035f * b) * exp(-t * 5.8f)

                        val rawVoice = (p1 + p2 + p3 + p4) * env * 0.28f
                        // Per-voice warm 1-pole low-pass filter that darkens naturally over time
                        val cutoffAlpha = (0.28f + 0.35f * b * exp(-t * 2.5f)).coerceIn(0.18f, 0.72f)
                        v.lpState += cutoffAlpha * (rawVoice - v.lpState)

                        sumL += v.lpState * v.leftGain
                        sumR += v.lpState * v.rightGain

                        v.phase1 = wrapPhase(v.phase1 + v.inc1)
                        v.phase2 = wrapPhase(v.phase2 + v.inc2)
                        v.phase3 = wrapPhase(v.phase3 + v.inc3)
                        v.phase4 = wrapPhase(v.phase4 + v.inc4)
                        v.elapsedSec += dt
                    }

                    // 2. Soft Acoustic Ballad Percussion
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
                                val f = 46.0 + 72.0 * exp(-t * 30.0)
                                d.phase = wrapPhase(d.phase + twoPi * f * dtDouble)
                                d.lpState += 0.18f * (white - d.lpState)
                                val feltThud = if (t < 0.012f) d.lpState * exp(-t * 260f) * 0.18f else 0f
                                (sin(d.phase).toFloat() * exp(-t * 11.5f) + feltThud) * 0.48f
                            }
                            VoicingEngine.DRUM_RIM -> {
                                val woodBody = sin(twoPi * 340.0 * t).toFloat() * exp(-t * 48f) * 0.28f
                                d.lpState += 0.35f * (white - d.lpState)
                                val woodClick = d.lpState * exp(-t * 75f) * 0.22f
                                (woodBody + woodClick) * 0.55f
                            }
                            VoicingEngine.DRUM_SNARE_BRUSH -> {
                                val body = sin(twoPi * 175.0 * t).toFloat() * exp(-t * 24f) * 0.20f
                                d.lpState += 0.28f * (white - d.lpState)
                                val brush = d.lpState * exp(-t * 14f) * 0.26f
                                (body + brush) * 0.55f
                            }
                            VoicingEngine.DRUM_CLOSED_HH -> {
                                // Band-filtered soft shaker/hat without harsh sine tones
                                d.lpState += 0.45f * (white - d.lpState)
                                val hp = white - d.lpState
                                hp * exp(-t * 48f) * 0.16f
                            }
                            else -> { // Soft Ride / Shaker swell
                                d.lpState += 0.38f * (white - d.lpState)
                                val hp = white - d.lpState
                                hp * exp(-t * 11.5f) * 0.15f
                            }
                        } * d.gain

                        sumL += hitSample * 0.78f
                        sumR += hitSample * 0.78f
                        d.elapsedSec += dt
                    }

                    // 3. Damped 4-Comb + 2-Allpass Stereo Hall Reverb
                    val revIn = (sumL + sumR) * 0.35f

                    val o1 = comb1[cIdx1]
                    cLp1 = cLp1 * 0.58f + o1 * 0.42f
                    comb1[cIdx1] = revIn + cLp1 * 0.62f
                    cIdx1 = (cIdx1 + 1) % cLen1

                    val o2 = comb2[cIdx2]
                    cLp2 = cLp2 * 0.58f + o2 * 0.42f
                    comb2[cIdx2] = revIn + cLp2 * 0.60f
                    cIdx2 = (cIdx2 + 1) % cLen2

                    val o3 = comb3[cIdx3]
                    cLp3 = cLp3 * 0.58f + o3 * 0.42f
                    comb3[cIdx3] = revIn + cLp3 * 0.58f
                    cIdx3 = (cIdx3 + 1) % cLen3

                    val o4 = comb4[cIdx4]
                    cLp4 = cLp4 * 0.58f + o4 * 0.42f
                    comb4[cIdx4] = revIn + cLp4 * 0.56f
                    cIdx4 = (cIdx4 + 1) % cLen4

                    val combMixL = (o1 + o3) * 0.5f
                    val combMixR = (o2 + o4) * 0.5f
                    val apIn = (combMixL + combMixR) * 0.5f

                    val apDel1 = apBuf1[apIdx1]
                    val apOut1 = -apIn * 0.5f + apDel1
                    apBuf1[apIdx1] = apIn + apDel1 * 0.5f
                    apIdx1 = (apIdx1 + 1) % apLen1

                    val apDel2 = apBuf2[apIdx2]
                    val apOut2 = -apOut1 * 0.5f + apDel2
                    apBuf2[apIdx2] = apOut1 + apDel2 * 0.5f
                    apIdx2 = (apIdx2 + 1) % apLen2

                    val wetL = (combMixL * 0.7f + apOut2 * 0.3f) * wetGain
                    val wetR = (combMixR * 0.7f + apOut2 * 0.3f) * wetGain

                    val outL = tanh((sumL * dryGain + wetL).toDouble()).toFloat()
                    val outR = tanh((sumR * dryGain + wetR).toDouble()).toFloat()

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
                    pianoPool.forEach {
                        if (it.active) it.fastFadeOut = true
                    }
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
        // Gently fade out any existing voice playing the same MIDI note instead of hard-resetting its phase
        for (v in pianoPool) {
            if (v.active && v.midi == midi && !v.fastFadeOut) {
                v.fastFadeOut = true
            }
        }

        // Prefer an inactive voice, or softly fade-finished voice, or oldest voice
        val slot = pianoPool.firstOrNull { !it.active }
            ?: pianoPool.filter { it.fastFadeOut }.minByOrNull { it.releaseEnv }
            ?: pianoPool.minBy { it.age }

        val wasActive = slot.active
        val f0 = 440.0 * 2.0.pow((midi - 69) / 12.0)
        // Subtle piano string inharmonicity
        val bCoeff = 0.00011
        val f1 = f0
        val f2 = f0 * 2.0 * sqrt(1.0 + 4.0 * bCoeff)
        val f3 = f0 * 3.0 * sqrt(1.0 + 9.0 * bCoeff)
        val f4 = f0 * 4.0 * sqrt(1.0 + 16.0 * bCoeff)

        val pan = ((midi - 60) / 38f).coerceIn(-0.42f, 0.42f)
        val normVel = (velocity / 127f).coerceIn(0.15f, 1f)
        // Lower register is slightly warmer/softer to avoid muddy booming
        val registerBalance = if (midi < 48) 0.78f else if (midi > 76) 0.82f else 1.0f

        slot.active = true
        slot.midi = midi
        slot.velocityGain = (normVel * normVel * 0.78f + 0.14f) * registerBalance
        slot.brightness = normVel * 0.85f
        slot.leftGain = 0.72f - pan * 0.32f
        slot.rightGain = 0.72f + pan * 0.32f
        slot.elapsedSec = 0f
        slot.durationSec = max(0.45f, durationSec * 1.15f)
        slot.releaseEnv = 1f
        slot.fastFadeOut = false
        if (!wasActive) {
            slot.phase1 = 0.0
            slot.phase2 = 0.0
            slot.phase3 = 0.0
            slot.phase4 = 0.0
            slot.lpState = 0f
        }
        slot.inc1 = twoPi * f1 / sampleRate
        slot.inc2 = if (f2 < sampleRate * 0.42) twoPi * f2 / sampleRate else 0.0
        slot.inc3 = if (f3 < sampleRate * 0.42) twoPi * f3 / sampleRate else 0.0
        slot.inc4 = if (f4 < sampleRate * 0.42) twoPi * f4 / sampleRate else 0.0
        slot.decayRate = (0.95f + max(0, midi - 45) * 0.026f).coerceIn(0.85f, 2.6f)
        slot.age = ++voiceAgeCounter
    }

    private fun allocateDrumVoice(note: Int, velocity: Int) {
        val slot = drumPool.firstOrNull { !it.active } ?: drumPool.first()
        slot.active = true
        slot.note = note
        slot.gain = (velocity / 127f).coerceIn(0.1f, 0.85f)
        slot.elapsedSec = 0f
        slot.phase = 0.0
        slot.lpState = 0f
        slot.hpState = 0f
        slot.durationSec = when (note) {
            VoicingEngine.DRUM_KICK -> 0.28f
            VoicingEngine.DRUM_RIDE -> 0.45f
            VoicingEngine.DRUM_SNARE_BRUSH -> 0.22f
            else -> 0.14f
        }
    }

    private fun wrapPhase(p: Double): Double = if (p >= twoPi) p - twoPi else p
}


package com.aijam.companion.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Process
import com.aijam.companion.engine.DrumHit
import com.aijam.companion.engine.VoicingEngine
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tanh

/**
 * Studio-grade Multi-Sampled Acoustic Grand Piano & Accompaniment Synthesizer (44.1 kHz Stereo):
 * - Loads 8 multi-sampled Acoustic Grand Piano reference WAVs (A1..E5) from assets/piano/
 *   (with an automatic 20-harmonic stiff-string wavetable fallback for headless unit tests)
 * - 4-point Hermite cubic interpolation for alias-free pitch shifting
 * - Humanized Pianist Chord Roll (18ms bass-to-treble micro-strumming so chords breathe like a real pianist)
 * - Velocity-sensitive felt low-pass filter & sustain pedal resonance
 * - Damped 4-comb + 2-allpass stereo concert hall reverb
 */
class PolyphonicAudioSynth(
    context: Context? = null,
    private val sampleRate: Int = 44100
) {
    private val maxVoices = 36
    private val twoPi = 2.0 * PI

    private data class PianoSampleZone(
        val rootMidi: Int,
        val sampleRate: Int,
        val pcm: FloatArray
    )

    private sealed class Command {
        data class TriggerPiano(val midiNotes: List<Int>, val velocity: Int, val durationSec: Double) : Command()
        data class TriggerDrums(val hits: List<DrumHit>) : Command()
        object Panic : Command()
    }

    private class PianoVoice {
        var active = false
        var midi = 60
        var zoneIndex = 0
        var samplePos = 0.0
        var sampleStep = 1.0
        var startDelaySec = 0f
        var velocityGain = 0f
        var brightness = 0.5f
        var leftGain = 0.7f
        var rightGain = 0.7f
        var elapsedSec = 0f
        var durationSec = 1.6f
        var releaseEnv = 1f
        var fastFadeOut = false
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
        var noiseState = 22222
    }

    private val sampleZones: List<PianoSampleZone> = loadSampleZones(context)
    private val commands = ConcurrentLinkedQueue<Command>()
    private val pianoPool = Array(maxVoices) { PianoVoice() }
    private val drumPool = Array(10) { DrumVoice() }
    private var voiceAgeCounter = 0L

    @Volatile var reverbMix: Float = 0.28f
    @Volatile var masterGain: Float = 1.0f

    @Volatile private var running = false
    private var audioThread: Thread? = null

    fun start() {
        if (running) return
        running = true
        audioThread = Thread(::audioLoop, "AIJam-GrandPianoSynth").apply {
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
        if (midiNotes.isEmpty() || masterGain <= 0.001f) return
        commands.add(
            Command.TriggerPiano(
                midiNotes = midiNotes.sorted(),
                velocity = velocity.coerceIn(1, 127),
                durationSec = durationSec.coerceIn(0.35, 5.0)
            )
        )
    }

    fun triggerDrums(hits: List<DrumHit>) {
        if (hits.isEmpty() || masterGain <= 0.001f) return
        commands.add(Command.TriggerDrums(hits.toList()))
    }

    private fun clearVoices() {
        commands.clear()
        pianoPool.forEach { it.active = false }
        drumPool.forEach { it.active = false }
    }

    private fun loadSampleZones(context: Context?): List<PianoSampleZone> {
        val assetDefs = listOf(
            33 to "piano/piano_33_a1.wav",
            40 to "piano/piano_40_e2.wav",
            45 to "piano/piano_45_a2.wav",
            52 to "piano/piano_52_e3.wav",
            57 to "piano/piano_57_a3.wav",
            64 to "piano/piano_64_e4.wav",
            69 to "piano/piano_69_a4.wav",
            76 to "piano/piano_76_e5.wav"
        )
        if (context != null) {
            val loaded = assetDefs.mapNotNull { (midi, path) ->
                runCatching {
                    val bytes = context.assets.open(path).use { it.readBytes() }
                    parseWav16Mono(midi, bytes)
                }.getOrNull()
            }
            if (loaded.isNotEmpty()) return loaded
        }
        // Fallback procedural multi-harmonic stiff-string zones for unit tests
        return assetDefs.map { (midi, _) -> buildProceduralZone(midi, 32000) }
    }

    private fun parseWav16Mono(rootMidi: Int, bytes: ByteArray): PianoSampleZone {
        require(bytes.size > 44) { "Invalid WAV asset" }
        val bb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        val sr = bb.getInt(24).coerceIn(8000, 96000)
        // Find 'data' chunk
        var offset = 12
        var dataOffset = 44
        var dataSize = bytes.size - 44
        while (offset + 8 <= bytes.size) {
            val id0 = bytes[offset].toInt().toChar()
            val id1 = bytes[offset + 1].toInt().toChar()
            val id2 = bytes[offset + 2].toInt().toChar()
            val id3 = bytes[offset + 3].toInt().toChar()
            val chunkSize = bb.getInt(offset + 4)
            if (id0 == 'd' && id1 == 'a' && id2 == 't' && id3 == 'a') {
                dataOffset = offset + 8
                dataSize = min(chunkSize, bytes.size - dataOffset)
                break
            }
            offset += 8 + max(0, chunkSize)
        }
        val numSamples = dataSize / 2
        val pcm = FloatArray(numSamples)
        var pos = dataOffset
        val scale = 1f / 32768f
        for (i in 0 until numSamples) {
            pcm[i] = bb.getShort(pos) * scale
            pos += 2
        }
        return PianoSampleZone(rootMidi = rootMidi, sampleRate = sr, pcm = pcm)
    }

    private fun buildProceduralZone(rootMidi: Int, sr: Int): PianoSampleZone {
        val dur = 2.4
        val n = (sr * dur).toInt()
        val pcm = FloatArray(n)
        val f0 = 440.0 * 2.0.pow((rootMidi - 69.0) / 12.0)
        val bCoeff = 0.00014
        for (h in 1..20) {
            val fn = f0 * h * sqrt(1.0 + bCoeff * h * h)
            if (fn >= sr * 0.42) break
            val amp = (1.0 / h.toDouble().pow(1.42)).toFloat()
            val decay = (1.2 + h * 0.45).toFloat()
            val phaseInc = (twoPi * fn / sr)
            var ph = (h * 0.37) % twoPi
            for (i in 0 until n) {
                val t = i.toFloat() / sr
                pcm[i] += sin(ph).toFloat() * amp * exp(-t * decay)
                ph += phaseInc
                if (ph >= twoPi) ph -= twoPi
            }
        }
        val attackSamples = (0.004 * sr).toInt().coerceAtLeast(1)
        for (i in 0 until attackSamples) {
            val a = 0.5f * (1f - cos(PI.toFloat() * i / attackSamples))
            pcm[i] *= a
        }
        var peak = 1e-5f
        for (v in pcm) {
            val av = abs(v)
            if (av > peak) peak = av
        }
        val norm = 0.85f / peak
        for (i in pcm.indices) pcm[i] *= norm
        return PianoSampleZone(rootMidi = rootMidi, sampleRate = sr, pcm = pcm)
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

        // Studio Concert Hall Reverb (4 parallel damped comb filters + 2 allpass diffusers)
        val cLen1 = (sampleRate * 0.0317).toInt()
        val cLen2 = (sampleRate * 0.0379).toInt()
        val cLen3 = (sampleRate * 0.0419).toInt()
        val cLen4 = (sampleRate * 0.0461).toInt()
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

        val apLen1 = (sampleRate * 0.0053).toInt()
        val apLen2 = (sampleRate * 0.0019).toInt()
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
                val mGain = masterGain.coerceIn(0f, 1.5f)
                val dryGain = (1f - revAmount * 0.16f) * mGain
                val wetGain = (revAmount * 0.32f) * mGain

                for (i in 0 until framesPerChunk) {
                    var sumL = 0f
                    var sumR = 0f

                    // 1. Multi-Sampled Acoustic Grand Piano Voices
                    for (v in pianoPool) {
                        if (!v.active) continue
                        if (v.startDelaySec > 0f) {
                            v.startDelaySec -= dt
                            continue
                        }

                        val zone = sampleZones[v.zoneIndex]
                        val pcm = zone.pcm
                        val pos = v.samplePos
                        val idx0 = pos.toInt()
                        if (idx0 >= pcm.size - 3) {
                            v.active = false
                            continue
                        }

                        if (v.fastFadeOut) {
                            v.releaseEnv *= 0.990f
                            if (v.releaseEnv < 0.0015f) {
                                v.active = false
                                continue
                            }
                        } else if (v.elapsedSec > v.durationSec) {
                            // Natural piano damper felt release (smooth, warm sustain tail)
                            v.releaseEnv *= 0.9988f
                            if (v.releaseEnv < 0.0012f) {
                                v.active = false
                                continue
                            }
                        }

                        // 4-point Hermite cubic interpolation for pristine pitch-shifting
                        val frac = (pos - idx0).toFloat()
                        val ym1 = pcm[(idx0 - 1).coerceAtLeast(0)]
                        val y0 = pcm[idx0]
                        val y1 = pcm[idx0 + 1]
                        val y2 = pcm[idx0 + 2]
                        val c0 = y0
                        val c1 = 0.5f * (y1 - ym1)
                        val c2 = ym1 - 2.5f * y0 + 2f * y1 - 0.5f * y2
                        val c3 = 0.5f * (y2 - ym1) + 1.5f * (y0 - y1)
                        val rawSample = ((c3 * frac + c2) * frac + c1) * frac + c0

                        // Dynamic velocity low-pass filter (warm felt tone on gentle notes, open brilliance on strong notes)
                        val cutoff = (0.36f + 0.54f * v.brightness * exp(-v.elapsedSec * 1.4f)).coerceIn(0.24f, 0.92f)
                        v.lpState += cutoff * (rawSample - v.lpState)

                        val voiceOut = v.lpState * v.velocityGain * v.releaseEnv * 0.44f
                        sumL += voiceOut * v.leftGain
                        sumR += voiceOut * v.rightGain

                        v.samplePos = pos + v.sampleStep
                        v.elapsedSec += dt
                    }

                    // 2. Optional Soft Acoustic Percussion (disabled by default for piano ballads)
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
                                val f = 44.0 + 62.0 * exp(-t * 32.0)
                                d.phase = wrapPhase(d.phase + twoPi * f * dtDouble)
                                sin(d.phase).toFloat() * exp(-t * 12.0f) * 0.36f
                            }
                            VoicingEngine.DRUM_RIM -> {
                                val woodBody = sin(twoPi * 320.0 * t).toFloat() * exp(-t * 55f) * 0.22f
                                d.lpState += 0.30f * (white - d.lpState)
                                (woodBody + d.lpState * exp(-t * 85f) * 0.15f) * 0.42f
                            }
                            else -> {
                                d.lpState += 0.35f * (white - d.lpState)
                                val hp = white - d.lpState
                                hp * exp(-t * 42f) * 0.10f
                            }
                        } * d.gain

                        sumL += hitSample * 0.65f
                        sumR += hitSample * 0.65f
                        d.elapsedSec += dt
                    }

                    // 3. Stereo Concert Hall Reverb
                    val revIn = (sumL + sumR) * 0.36f

                    val o1 = comb1[cIdx1]
                    cLp1 = cLp1 * 0.54f + o1 * 0.46f
                    comb1[cIdx1] = revIn + cLp1 * 0.66f
                    cIdx1 = (cIdx1 + 1) % cLen1

                    val o2 = comb2[cIdx2]
                    cLp2 = cLp2 * 0.54f + o2 * 0.46f
                    comb2[cIdx2] = revIn + cLp2 * 0.64f
                    cIdx2 = (cIdx2 + 1) % cLen2

                    val o3 = comb3[cIdx3]
                    cLp3 = cLp3 * 0.54f + o3 * 0.46f
                    comb3[cIdx3] = revIn + cLp3 * 0.62f
                    cIdx3 = (cIdx3 + 1) % cLen3

                    val o4 = comb4[cIdx4]
                    cLp4 = cLp4 * 0.54f + o4 * 0.46f
                    comb4[cIdx4] = revIn + cLp4 * 0.60f
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

                    val wetL = (combMixL * 0.72f + apOut2 * 0.28f) * wetGain
                    val wetR = (combMixR * 0.72f + apOut2 * 0.28f) * wetGain

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
                    val count = cmd.midiNotes.size
                    cmd.midiNotes.forEachIndexed { idx, note ->
                        // Humanized pianist chord roll: 18ms spread from bass to top note
                        val rollDelaySec = if (count > 1) idx * 0.018f else 0f
                        // Expressive voicing balance: warm grounded bass, gentle inner notes, singing top note
                        val noteVel = when {
                            idx == 0 && note < 52 -> (cmd.velocity * 0.94).toInt()
                            idx == count - 1 && count > 2 -> (cmd.velocity * 1.04).toInt().coerceAtMost(127)
                            count > 2 -> (cmd.velocity * 0.86).toInt()
                            else -> cmd.velocity
                        }
                        allocatePianoVoice(
                            midi = note,
                            velocity = noteVel,
                            durationSec = cmd.durationSec.toFloat(),
                            startDelaySec = rollDelaySec
                        )
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

    private fun allocatePianoVoice(
        midi: Int,
        velocity: Int,
        durationSec: Float,
        startDelaySec: Float
    ) {
        // Softly release any active voice playing the exact same MIDI note
        for (v in pianoPool) {
            if (v.active && v.midi == midi && !v.fastFadeOut) {
                v.fastFadeOut = true
            }
        }

        val slot = pianoPool.firstOrNull { !it.active }
            ?: pianoPool.filter { it.fastFadeOut }.minByOrNull { it.releaseEnv }
            ?: pianoPool.minBy { it.age }

        // Select closest multi-sampled Grand Piano zone
        var bestZoneIdx = 0
        var bestDist = Int.MAX_VALUE
        for (i in sampleZones.indices) {
            val dist = abs(sampleZones[i].rootMidi - midi)
            if (dist < bestDist) {
                bestDist = dist
                bestZoneIdx = i
            }
        }
        val zone = sampleZones[bestZoneIdx]
        val pitchRatio = 2.0.pow((midi - zone.rootMidi) / 12.0)
        val step = pitchRatio * (zone.sampleRate.toDouble() / sampleRate.toDouble())

        val pan = ((midi - 60) / 36f).coerceIn(-0.45f, 0.45f)
        val normVel = (velocity / 127f).coerceIn(0.18f, 1f)
        val registerGain = when {
            midi < 42 -> 0.88f
            midi > 76 -> 0.84f
            else -> 1.0f
        }

        slot.active = true
        slot.midi = midi
        slot.zoneIndex = bestZoneIdx
        slot.samplePos = 0.0
        slot.sampleStep = step
        slot.startDelaySec = startDelaySec
        slot.velocityGain = (normVel.pow(1.4f) * 0.82f + 0.16f) * registerGain
        slot.brightness = normVel
        slot.leftGain = 0.72f - pan * 0.34f
        slot.rightGain = 0.72f + pan * 0.34f
        slot.elapsedSec = 0f
        slot.durationSec = max(0.75f, durationSec * 1.35f)
        slot.releaseEnv = 1f
        slot.fastFadeOut = false
        slot.lpState = 0f
        slot.age = ++voiceAgeCounter
    }

    private fun allocateDrumVoice(note: Int, velocity: Int) {
        val slot = drumPool.firstOrNull { !it.active } ?: drumPool.first()
        slot.active = true
        slot.note = note
        slot.gain = (velocity / 127f).coerceIn(0.1f, 0.75f)
        slot.elapsedSec = 0f
        slot.phase = 0.0
        slot.lpState = 0f
        slot.durationSec = when (note) {
            VoicingEngine.DRUM_KICK -> 0.26f
            VoicingEngine.DRUM_RIDE -> 0.40f
            else -> 0.14f
        }
    }

    private fun wrapPhase(p: Double): Double = if (p >= twoPi) p - twoPi else p
}

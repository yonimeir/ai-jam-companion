package com.aijam.companion.audio

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.OpenableColumns
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.nio.ByteOrder
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

data class DecodedAudio(
    val samples: FloatArray,
    val sampleRate: Int,
    val title: String,
    val artist: String,
    val durationSec: Double
)

/**
 * Decodes local audio files (MP3, WAV, M4A, AAC, OGG, FLAC) into mono FloatArray at 22050 Hz
 * directly on-device using MediaExtractor + MediaCodec.
 */
object AudioFileDecoder {

    const val TARGET_SAMPLE_RATE = 22050
    private const val MAX_ANALYSIS_DURATION_SEC = 420.0 // 7 minutes cap to stay memory-safe

    suspend fun decodeUri(
        context: Context,
        uri: Uri,
        onProgress: (Float) -> Unit = {}
    ): DecodedAudio {
        val (title, artist) = extractMetadata(context, uri)
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null

        try {
            extractor.setDataSource(context, uri, null)
            val trackIdx = findAudioTrackIndex(extractor)
            require(trackIdx >= 0) { "לא נמצא ערוץ אודיו בקובץ שנבחר" }

            extractor.selectTrack(trackIdx)
            val format = extractor.getTrackFormat(trackIdx)
            val mime = format.getString(MediaFormat.KEY_MIME)
                ?: error("פורמט אודיו לא נתמך")
            val srcSampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            val channels = if (format.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) {
                format.getInteger(MediaFormat.KEY_CHANNEL_COUNT).coerceAtLeast(1)
            } else 1
            val estimatedDurationUs = if (format.containsKey(MediaFormat.KEY_DURATION)) {
                format.getLong(MediaFormat.KEY_DURATION)
            } else 0L

            codec = MediaCodec.createDecoderByType(mime)
            codec.configure(format, null, null, 0)
            codec.start()

            val maxInputSamples = (MAX_ANALYSIS_DURATION_SEC * srcSampleRate).toInt()
            val monoCapacity = if (estimatedDurationUs > 0) {
                min(maxInputSamples, ((estimatedDurationUs / 1_000_000.0) * srcSampleRate).toInt() + srcSampleRate)
            } else {
                srcSampleRate * 180
            }
            var monoOut = FloatArray(max(srcSampleRate * 4, monoCapacity))
            var monoCount = 0

            val bufferInfo = MediaCodec.BufferInfo()
            var inputDone = false
            var outputDone = false
            var pcmEncoding = AudioFormat.ENCODING_PCM_16BIT

            while (!outputDone) {
                currentCoroutineContext().ensureActive()

                if (!inputDone) {
                    val inIdx = codec.dequeueInputBuffer(10_000)
                    if (inIdx >= 0) {
                        val inBuf = codec.getInputBuffer(inIdx)!!
                        val sampleSize = extractor.readSampleData(inBuf, 0)
                        if (sampleSize < 0) {
                            codec.queueInputBuffer(
                                inIdx, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM
                            )
                            inputDone = true
                        } else {
                            val ptsUs = extractor.sampleTime
                            codec.queueInputBuffer(inIdx, 0, sampleSize, ptsUs, 0)
                            extractor.advance()
                            if (estimatedDurationUs > 0 && ptsUs > 0) {
                                val pct = (ptsUs.toFloat() / estimatedDurationUs).coerceIn(0f, 0.95f)
                                onProgress(pct)
                            }
                        }
                    }
                }

                val outIdx = codec.dequeueOutputBuffer(bufferInfo, 10_000)
                when {
                    outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val outFmt = codec.outputFormat
                        if (outFmt.containsKey(MediaFormat.KEY_PCM_ENCODING)) {
                            pcmEncoding = outFmt.getInteger(MediaFormat.KEY_PCM_ENCODING)
                        }
                    }
                    outIdx >= 0 -> {
                        val outBuf = codec.getOutputBuffer(outIdx)
                        if (outBuf != null && bufferInfo.size > 0) {
                            outBuf.position(bufferInfo.offset)
                            outBuf.limit(bufferInfo.offset + bufferInfo.size)
                            val chunk = decodePcmChunk(outBuf, channels, pcmEncoding)
                            if (monoCount + chunk.size > monoOut.size) {
                                val newCap = max(monoOut.size * 2, monoCount + chunk.size)
                                monoOut = monoOut.copyOf(min(newCap, maxInputSamples))
                            }
                            val copyLen = min(chunk.size, maxInputSamples - monoCount)
                            if (copyLen > 0) {
                                System.arraycopy(chunk, 0, monoOut, monoCount, copyLen)
                                monoCount += copyLen
                            }
                            if (monoCount >= maxInputSamples) {
                                outputDone = true
                            }
                        }
                        codec.releaseOutputBuffer(outIdx, false)
                        if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                            outputDone = true
                        }
                    }
                }
            }

            onProgress(1f)
            val trimmedMono = monoOut.copyOf(monoCount)
            require(trimmedMono.isNotEmpty()) { "הקובץ אינו מכיל נתוני אודיו תקינים" }

            val resampled = if (srcSampleRate == TARGET_SAMPLE_RATE) {
                trimmedMono
            } else {
                resampleLinear(trimmedMono, srcSampleRate, TARGET_SAMPLE_RATE)
            }
            val durationSec = resampled.size.toDouble() / TARGET_SAMPLE_RATE

            return DecodedAudio(
                samples = resampled,
                sampleRate = TARGET_SAMPLE_RATE,
                title = title,
                artist = artist,
                durationSec = durationSec
            )
        } finally {
            runCatching { codec?.stop() }
            runCatching { codec?.release() }
            runCatching { extractor.release() }
        }
    }

    private fun decodePcmChunk(
        buf: java.nio.ByteBuffer,
        channels: Int,
        pcmEncoding: Int
    ): FloatArray {
        val slice = buf.slice().order(ByteOrder.LITTLE_ENDIAN)
        return if (pcmEncoding == AudioFormat.ENCODING_PCM_FLOAT) {
            val fb = slice.asFloatBuffer()
            val totalFloats = fb.remaining()
            val frameCount = totalFloats / channels
            val out = FloatArray(frameCount)
            if (channels == 1) {
                fb.get(out)
            } else {
                val invCh = 1f / channels
                for (i in 0 until frameCount) {
                    var sum = 0f
                    for (c in 0 until channels) sum += fb.get()
                    out[i] = sum * invCh
                }
            }
            out
        } else {
            val sb = slice.asShortBuffer()
            val totalShorts = sb.remaining()
            val frameCount = totalShorts / channels
            val out = FloatArray(frameCount)
            val norm = 1f / 32768f
            if (channels == 1) {
                for (i in 0 until frameCount) {
                    out[i] = sb.get() * norm
                }
            } else {
                val invCh = norm / channels
                for (i in 0 until frameCount) {
                    var sum = 0f
                    for (c in 0 until channels) sum += sb.get()
                    out[i] = sum * invCh
                }
            }
            out
        }
    }

    private fun findAudioTrackIndex(extractor: MediaExtractor): Int {
        for (i in 0 until extractor.trackCount) {
            val fmt = extractor.getTrackFormat(i)
            val mime = fmt.getString(MediaFormat.KEY_MIME) ?: continue
            if (mime.startsWith("audio/")) return i
        }
        return -1
    }

    fun resampleLinear(input: FloatArray, srcRate: Int, dstRate: Int): FloatArray {
        if (input.isEmpty() || srcRate == dstRate) return input
        val ratio = srcRate.toDouble() / dstRate.toDouble()
        val outLen = max(1, floor(input.size / ratio).toInt())
        val out = FloatArray(outLen)
        val filterRadius = if (ratio > 1.2) ceil(ratio * 0.5).toInt() else 0

        for (i in 0 until outLen) {
            val srcPos = i * ratio
            if (filterRadius > 0) {
                val center = srcPos.toInt()
                val start = max(0, center - filterRadius)
                val end = min(input.lastIndex, center + filterRadius)
                var sum = 0f
                for (k in start..end) sum += input[k]
                out[i] = sum / (end - start + 1)
            } else {
                val i0 = srcPos.toInt().coerceIn(0, input.lastIndex)
                val i1 = min(i0 + 1, input.lastIndex)
                val frac = (srcPos - i0).toFloat()
                out[i] = input[i0] + frac * (input[i1] - input[i0])
            }
        }
        return out
    }

    private fun extractMetadata(context: Context, uri: Uri): Pair<String, String> {
        var title = ""
        var artist = ""

        val mmr = MediaMetadataRetriever()
        try {
            mmr.setDataSource(context, uri)
            title = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE)?.trim().orEmpty()
            artist = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST)?.trim().orEmpty()
        } catch (_: Exception) {
        } finally {
            runCatching { mmr.release() }
        }

        if (title.isBlank()) {
            title = queryDisplayName(context, uri)
                ?.substringBeforeLast('.')
                ?.replace('_', ' ')
                ?.trim()
                ?.ifBlank { "Custom Song" }
                ?: "Custom Song"
        }
        if (artist.isBlank()) {
            artist = "אודיו מקומי"
        }
        return title to artist
    }

    private fun queryDisplayName(context: Context, uri: Uri): String? {
        return runCatching {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                        if (idx >= 0) cursor.getString(idx) else null
                    } else null
                }
        }.getOrNull()
    }
}

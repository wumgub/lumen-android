package com.lumen.android.playback

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.tan

/**
 * Balance volume, the same as the desktop app's: each song's integrated
 * loudness (EBU R128, the measure Spotify and Apple Music use), and one fixed
 * gain that brings it to [TARGET_LUFS]. A fixed gain per song keeps its
 * dynamics as they are.
 */
const val TARGET_LUFS = -16.0
private const val MAX_DB = 12.0
private const val PEAK_CEILING = 0.89 // −1 dBFS

data class Loudness(val lufs: Double, val peak: Double)

/** The volume multiplier for a song: within ±12 dB, and never up into clipping. */
fun gainFor(lufs: Double, peak: Double): Double {
    val db = (TARGET_LUFS - lufs).coerceIn(-MAX_DB, MAX_DB)
    var g = 10.0.pow(db / 20)
    if (g > 1 && peak > 0) g = min(g, max(PEAK_CEILING / peak, 1.0))
    return g
}

/** EBU R128 integrated loudness of interleaved float PCM fed in with [add]. */
class R128(rate: Int, private val channels: Int) {
    private val filters = Array(channels) { KWeighting(rate) }
    private val hop = max(rate / 10, 1) // 100 ms
    private var inHop = 0
    private var sum = 0.0
    private val hops = ArrayList<Double>()
    var peak = 0.0
        private set

    fun add(samples: FloatArray, count: Int) {
        var i = 0
        while (i + channels <= count) {
            for (c in 0 until channels) {
                val x = samples[i + c].toDouble()
                peak = max(peak, abs(x))
                val y = filters[c].process(x)
                sum += y * y
            }
            if (++inHop == hop) {
                hops.add(sum / hop)
                sum = 0.0
                inHop = 0
            }
            i += channels
        }
    }

    /** LUFS, or null if it's silent or shorter than one 400 ms block. */
    fun integrated(): Double? {
        if (hops.size < 4) return null
        // 400 ms blocks, overlapping by 75%.
        val blocks = DoubleArray(hops.size - 3) { (hops[it] + hops[it + 1] + hops[it + 2] + hops[it + 3]) / 4 }
        fun lufs(power: Double) = -0.691 + 10 * log10(power)
        val loud = blocks.filter { it > 0 && lufs(it) > -70 }
        if (loud.isEmpty()) return null
        val gate = lufs(loud.average()) - 10
        return lufs(loud.filter { lufs(it) > gate }.average())
    }
}

/** R128's K-weighting: a high-shelf then a high-pass, at any sample rate. */
private class KWeighting(rate: Int) {
    private val b = DoubleArray(3)
    private val a = DoubleArray(3)
    private val b2 = doubleArrayOf(1.0, -2.0, 1.0)
    private val a2 = DoubleArray(3)
    private var x1 = 0.0; private var x2 = 0.0; private var y1 = 0.0; private var y2 = 0.0
    private var u1 = 0.0; private var u2 = 0.0; private var z1 = 0.0; private var z2 = 0.0

    init {
        var f0 = 1681.974450955533
        val g = 3.999843853973347
        var q = 0.7071752369554196
        var k = tan(PI * f0 / rate)
        val vh = 10.0.pow(g / 20)
        val vb = vh.pow(0.4996667741545416)
        var a0 = 1 + k / q + k * k
        b[0] = (vh + vb * k / q + k * k) / a0
        b[1] = 2 * (k * k - vh) / a0
        b[2] = (vh - vb * k / q + k * k) / a0
        a[1] = 2 * (k * k - 1) / a0
        a[2] = (1 - k / q + k * k) / a0
        f0 = 38.13547087602444
        q = 0.5003270373238773
        k = tan(PI * f0 / rate)
        a0 = 1 + k / q + k * k
        a2[1] = 2 * (k * k - 1) / a0
        a2[2] = (1 - k / q + k * k) / a0
    }

    fun process(x: Double): Double {
        val y = b[0] * x + b[1] * x1 + b[2] * x2 - a[1] * y1 - a[2] * y2
        x2 = x1; x1 = x; y2 = y1; y1 = y
        val z = b2[0] * y + b2[1] * u1 + b2[2] * u2 - a2[1] * z1 - a2[2] * z2
        u2 = u1; u1 = y; z2 = z1; z1 = z
        return z
    }
}

/** Decode a whole song with MediaCodec and measure it. Null if it can't be. */
fun measureLoudness(context: Context, uri: Uri): Loudness? {
    val extractor = MediaExtractor()
    try {
        extractor.setDataSource(context, uri, null)
        val track = (0 until extractor.trackCount).firstOrNull {
            extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
        } ?: return null
        extractor.selectTrack(track)
        val format = extractor.getTrackFormat(track)
        val codec = MediaCodec.createDecoderByType(format.getString(MediaFormat.KEY_MIME) ?: return null)
        var meter: R128? = null
        var encoding = AudioFormat.ENCODING_PCM_16BIT
        var floats = FloatArray(0)
        try {
            codec.configure(format, null, null, 0)
            codec.start()
            val info = MediaCodec.BufferInfo()
            var inputDone = false
            while (true) {
                if (!inputDone) {
                    val i = codec.dequeueInputBuffer(10_000)
                    if (i >= 0) {
                        val n = extractor.readSampleData(codec.getInputBuffer(i) ?: return null, 0)
                        if (n < 0) {
                            codec.queueInputBuffer(i, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            codec.queueInputBuffer(i, 0, n, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }
                val o = codec.dequeueOutputBuffer(info, 10_000)
                if (o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    val f = codec.outputFormat
                    if (f.containsKey(MediaFormat.KEY_PCM_ENCODING)) encoding = f.getInteger(MediaFormat.KEY_PCM_ENCODING)
                    meter = R128(f.getInteger(MediaFormat.KEY_SAMPLE_RATE), f.getInteger(MediaFormat.KEY_CHANNEL_COUNT))
                } else if (o >= 0) {
                    val m = meter ?: R128(
                        format.getInteger(MediaFormat.KEY_SAMPLE_RATE),
                        format.getInteger(MediaFormat.KEY_CHANNEL_COUNT),
                    ).also { meter = it }
                    val out = codec.getOutputBuffer(o)?.order(ByteOrder.nativeOrder())
                    if (out != null && info.size > 0) {
                        out.position(info.offset)
                        out.limit(info.offset + info.size)
                        val count = if (encoding == AudioFormat.ENCODING_PCM_FLOAT) info.size / 4 else info.size / 2
                        if (floats.size < count) floats = FloatArray(count)
                        if (encoding == AudioFormat.ENCODING_PCM_FLOAT) {
                            for (k in 0 until count) floats[k] = out.float
                        } else {
                            for (k in 0 until count) floats[k] = out.short / 32768f
                        }
                        m.add(floats, count)
                    }
                    codec.releaseOutputBuffer(o, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
                }
            }
        } finally {
            runCatching { codec.stop() }
            codec.release()
        }
        val m = meter ?: return null
        return m.integrated()?.let { Loudness(it, m.peak) }
    } catch (e: Exception) {
        return null
    } finally {
        extractor.release()
    }
}

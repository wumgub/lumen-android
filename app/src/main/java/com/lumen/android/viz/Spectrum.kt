package com.lumen.android.viz

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

const val FFT_SIZE = 2048
const val BINS = FFT_SIZE / 2

/** One frame of visualizer input, the same shape as the desktop app's. */
class AudioFrame {
    /** Frequency magnitudes, 0..255. */
    val freq = IntArray(BINS)

    /** Waveform, 0..255 with 128 as silence. */
    val wave = IntArray(BINS) { 128 }
    var bass = 0f
    var mid = 0f
    var treble = 0f
    var level = 0f
}

/**
 * A Web Audio `AnalyserNode` in plain Kotlin (fftSize 2048, smoothing 0.8,
 * -100..-30 dB), so the visualizers get the numbers they were written for.
 */
class Spectrum {
    val samples = FloatArray(FFT_SIZE)
    val frame = AudioFrame()

    private val window = FloatArray(FFT_SIZE) { i ->
        val x = i.toDouble() / FFT_SIZE
        (0.42 - 0.5 * cos(2 * PI * x) + 0.08 * cos(4 * PI * x)).toFloat()
    }
    private val re = FloatArray(FFT_SIZE)
    private val im = FloatArray(FFT_SIZE)
    private val smoothed = FloatArray(BINS)
    private val bitrev = IntArray(FFT_SIZE).also { r ->
        val bits = 11 // log2(2048)
        for (i in 0 until FFT_SIZE) {
            var x = i
            var y = 0
            repeat(bits) {
                y = (y shl 1) or (x and 1)
                x = x shr 1
            }
            r[i] = y
        }
    }
    private val cosT = FloatArray(FFT_SIZE / 2) { cos(2 * PI * it / FFT_SIZE).toFloat() }
    private val sinT = FloatArray(FFT_SIZE / 2) { sin(2 * PI * it / FFT_SIZE).toFloat() }

    /** Analyse [samples]. `dt` (seconds since the last frame) scales the smoothing to the frame rate. */
    fun analyse(dt: Float) {
        for (i in 0 until FFT_SIZE) {
            val j = bitrev[i]
            re[j] = samples[i] * window[i]
            im[j] = 0f
        }
        var size = 2
        while (size <= FFT_SIZE) {
            val half = size / 2
            val step = FFT_SIZE / size
            var start = 0
            while (start < FFT_SIZE) {
                for (k in 0 until half) {
                    val c = cosT[k * step]
                    val s = -sinT[k * step]
                    val a = start + k
                    val b = a + half
                    val tr = re[b] * c - im[b] * s
                    val ti = re[b] * s + im[b] * c
                    re[b] = re[a] - tr
                    im[b] = im[a] - ti
                    re[a] += tr
                    im[a] += ti
                }
                start += size
            }
            size *= 2
        }
        val tau = 0.8f.pow((dt * 60f).coerceIn(0.25f, 4f))
        val norm = 1f / FFT_SIZE
        val scale = 255f / (MAX_DB - MIN_DB)
        for (k in 0 until BINS) {
            val mag = sqrt(re[k] * re[k] + im[k] * im[k]) * norm
            val s = tau * smoothed[k] + (1 - tau) * mag
            smoothed[k] = if (s.isFinite()) s else 0f
            val db = 20f * log10(max(smoothed[k], 1e-12f))
            frame.freq[k] = (scale * (db - MIN_DB)).toInt().coerceIn(0, 255)
        }
        var sumSq = 0f
        for (k in 0 until BINS) {
            val w = (128f * (1f + samples[k])).toInt().coerceIn(0, 255)
            frame.wave[k] = w
            val v = (w - 128f) / 128f
            sumSq += v * v
        }
        frame.level = sqrt(sumSq / BINS)
        val b = (BINS * 0.08f).toInt()
        val m = (BINS * 0.4f).toInt()
        frame.bass = band(frame.freq, 0, b)
        frame.mid = band(frame.freq, b, m)
        frame.treble = band(frame.freq, m, BINS)
    }

    companion object {
        private const val MIN_DB = -100f
        private const val MAX_DB = -30f

        fun band(data: IntArray, from: Int, to: Int): Float {
            val end = minOf(to, data.size)
            if (end <= from) return 0f
            var sum = 0
            for (i in from until end) sum += data[i]
            return sum.toFloat() / (end - from) / 255f
        }
    }
}

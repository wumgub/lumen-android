package com.lumen.android.playback

import androidx.media3.common.C
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * The audio the visualizer draws. [TapAudioSink] copies each decoded buffer in
 * here with its presentation time, and records the audio clock (the time of
 * what's actually coming out of the speaker), so [read] can hand back exactly
 * the samples being heard rather than ones still sitting in the output buffer.
 * No microphone or RECORD_AUDIO permission involved.
 */
object AudioTap {
    private class Chunk(val startUs: Long, val samples: FloatArray, val rate: Int)

    private const val KEEP_US = 1_500_000L
    private val lock = Any()
    private val chunks = ArrayDeque<Chunk>()

    @Volatile private var rate = 44_100
    @Volatile private var channels = 2
    @Volatile private var encoding = C.ENCODING_PCM_16BIT

    @Volatile private var clockUs = Long.MIN_VALUE
    @Volatile private var clockAtNs = 0L
    @Volatile private var playing = false

    /** Only copy audio while a visualizer is on screen. */
    @Volatile var enabled = false
        set(value) {
            field = value
            if (!value) clear()
        }

    fun configure(sampleRate: Int, channelCount: Int, pcmEncoding: Int) {
        rate = sampleRate.coerceAtLeast(1)
        channels = channelCount.coerceAtLeast(1)
        encoding = pcmEncoding
    }

    fun write(buffer: ByteBuffer, presentationUs: Long) {
        if (!enabled) return
        val enc = encoding
        // Decoded audio is mostly 16-bit or float, but WAV and AIFF reach the
        // sink as they are in the file: 8, 24 or 32-bit, or big-endian.
        val bytes = when (enc) {
            C.ENCODING_PCM_8BIT -> 1
            C.ENCODING_PCM_16BIT, C.ENCODING_PCM_16BIT_BIG_ENDIAN -> 2
            C.ENCODING_PCM_24BIT -> 3
            C.ENCODING_PCM_32BIT, C.ENCODING_PCM_FLOAT -> 4
            else -> return
        }
        val ch = channels
        val order = if (enc == C.ENCODING_PCM_16BIT_BIG_ENDIAN) ByteOrder.BIG_ENDIAN else ByteOrder.LITTLE_ENDIAN
        val b = buffer.duplicate().order(order)
        val frames = b.remaining() / (bytes * ch)
        if (frames <= 0) return
        val out = FloatArray(frames)
        // Downmix to mono by averaging, as Web Audio's analyser does.
        val scale = 1f / ch
        for (f in 0 until frames) {
            var sum = 0f
            for (c in 0 until ch) sum += sample(b, enc)
            out[f] = sum * scale
        }
        synchronized(lock) {
            chunks.addLast(Chunk(presentationUs, out, rate))
            while (chunks.size > 1 && chunks.first().startUs < presentationUs - KEEP_US) chunks.removeFirst()
        }
    }

    /** The next sample in [b], from -1 to 1. */
    private fun sample(b: ByteBuffer, enc: Int): Float = when (enc) {
        C.ENCODING_PCM_8BIT -> ((b.get().toInt() and 0xff) - 128) / 128f
        C.ENCODING_PCM_24BIT -> {
            val lo = b.get().toInt() and 0xff
            val mid = b.get().toInt() and 0xff
            val hi = b.get().toInt() // keeps the sign
            ((hi shl 16) or (mid shl 8) or lo) / 8_388_608f
        }
        C.ENCODING_PCM_32BIT -> b.int / 2_147_483_648f
        C.ENCODING_PCM_FLOAT -> b.float
        else -> b.short / 32768f
    }

    fun clock(positionUs: Long) {
        clockUs = positionUs
        clockAtNs = System.nanoTime()
    }

    fun setPlaying(isPlaying: Boolean) {
        playing = isPlaying
    }

    fun clear() {
        synchronized(lock) { chunks.clear() }
    }

    /** Where the speaker is now, extrapolated a little past the last clock reading. */
    private fun nowUs(): Long {
        val c = clockUs
        if (c == Long.MIN_VALUE || !playing) return c
        val ahead = ((System.nanoTime() - clockAtNs) / 1000).coerceIn(0, 200_000)
        return c + ahead
    }

    /** Fill [out] with the mono samples that end right now. False means silence. */
    fun read(out: FloatArray): Boolean {
        out.fill(0f)
        if (!enabled || !playing) return false
        val end = nowUs()
        if (end == Long.MIN_VALUE) return false
        var filled = 0
        synchronized(lock) {
            var k = chunks.size - 1
            while (k >= 0 && filled < out.size) {
                val chunk = chunks[k--]
                val usPerSample = 1_000_000.0 / chunk.rate
                // Samples in this chunk that are already playing.
                val heard = ((end - chunk.startUs) / usPerSample).toInt().coerceIn(0, chunk.samples.size)
                var i = heard - 1
                while (i >= 0 && filled < out.size) {
                    out[out.size - 1 - filled] = chunk.samples[i]
                    filled++
                    i--
                }
            }
        }
        return filled > 0
    }
}

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
        val bytes = when (enc) {
            C.ENCODING_PCM_16BIT -> 2
            C.ENCODING_PCM_FLOAT -> 4
            else -> return
        }
        val ch = channels
        val b = buffer.duplicate().order(ByteOrder.nativeOrder())
        val frames = b.remaining() / (bytes * ch)
        if (frames <= 0) return
        val out = FloatArray(frames)
        // Downmix to mono by averaging, as Web Audio's analyser does.
        val scale = 1f / ch
        for (f in 0 until frames) {
            var sum = 0f
            for (c in 0 until ch) sum += if (enc == C.ENCODING_PCM_16BIT) b.short / 32768f else b.float
            out[f] = sum * scale
        }
        synchronized(lock) {
            chunks.addLast(Chunk(presentationUs, out, rate))
            while (chunks.size > 1 && chunks.first().startUs < presentationUs - KEEP_US) chunks.removeFirst()
        }
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

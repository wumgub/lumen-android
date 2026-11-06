package com.lumen.android.playback

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Lumen's volume, 0 to 1.5 (150%), shared by the UI and the playback service. */
object Volume {
    const val MAX = 1.5f

    @Volatile var level = 1f
        set(value) {
            field = value.coerceIn(0f, MAX)
        }
}

/**
 * Applies [Volume] to the PCM itself. ExoPlayer's own volume stops at 100%, so
 * the boost past that has to happen here; anything louder than full scale is
 * clipped.
 */
@OptIn(UnstableApi::class)
class GainProcessor : BaseAudioProcessor() {

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        if (inputAudioFormat.encoding != C.ENCODING_PCM_16BIT && inputAudioFormat.encoding != C.ENCODING_PCM_FLOAT) {
            throw AudioProcessor.UnhandledAudioFormatException(inputAudioFormat)
        }
        return inputAudioFormat
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val size = inputBuffer.remaining()
        if (size == 0) return
        val input = inputBuffer.order(ByteOrder.nativeOrder())
        val out = replaceOutputBuffer(size)
        val gain = Volume.level
        if (inputAudioFormat.encoding == C.ENCODING_PCM_16BIT) {
            while (input.remaining() >= 2) {
                val v = (input.short * gain).toInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
                out.putShort(v.toShort())
            }
        } else {
            while (input.remaining() >= 4) out.putFloat((input.float * gain).coerceIn(-1f, 1f))
        }
        input.position(input.limit())
        out.flip()
    }
}

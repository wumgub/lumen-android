package com.lumen.android.playback

import androidx.annotation.OptIn
import androidx.media3.common.Format
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.ForwardingAudioSink
import java.nio.ByteBuffer

/**
 * Wraps the real audio sink to feed [AudioTap]: every decoded buffer (before
 * the volume stage, so the visualizer doesn't shrink when you turn it down)
 * and the sink's audio clock.
 */
@OptIn(UnstableApi::class)
class TapAudioSink(sink: AudioSink) : ForwardingAudioSink(sink) {

    private var lastBuffer: ByteBuffer? = null
    private var lastPresentationUs = Long.MIN_VALUE

    override fun configure(inputFormat: Format, specifiedBufferSize: Int, outputChannels: IntArray?) {
        AudioTap.configure(inputFormat.sampleRate, inputFormat.channelCount, inputFormat.pcmEncoding)
        super.configure(inputFormat, specifiedBufferSize, outputChannels)
    }

    override fun handleBuffer(buffer: ByteBuffer, presentationTimeUs: Long, encodedAccessUnitCount: Int): Boolean {
        // A buffer the sink couldn't take in one go is offered again; copy it once.
        if (buffer !== lastBuffer || presentationTimeUs != lastPresentationUs) {
            lastBuffer = buffer
            lastPresentationUs = presentationTimeUs
            AudioTap.write(buffer, presentationTimeUs)
        }
        return super.handleBuffer(buffer, presentationTimeUs, encodedAccessUnitCount)
    }

    override fun getCurrentPositionUs(sourceEnded: Boolean): Long {
        val position = super.getCurrentPositionUs(sourceEnded)
        if (position != AudioSink.CURRENT_POSITION_NOT_SET) AudioTap.clock(position)
        return position
    }

    override fun play() {
        AudioTap.setPlaying(true)
        super.play()
    }

    override fun pause() {
        AudioTap.setPlaying(false)
        super.pause()
    }

    override fun flush() {
        forget()
        super.flush()
    }

    override fun reset() {
        forget()
        super.reset()
    }

    private fun forget() {
        lastBuffer = null
        lastPresentationUs = Long.MIN_VALUE
        AudioTap.clear()
    }
}

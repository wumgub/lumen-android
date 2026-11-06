package com.lumen.android.playback

import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import com.lumen.android.data.PlayEntity
import com.lumen.android.data.StatsDao
import kotlin.math.min
import kotlin.math.roundToLong
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Listening stats, with the desktop app's rules: listened time only grows on
 * forward playback (so seeking and pausing don't inflate it), a session
 * "counts" as a play once past half the song or four minutes, and it's saved
 * if it reached five seconds. Lives in the playback service, so it keeps
 * counting while the app is in the background.
 */
class ListenTracker(
    private val player: Player,
    private val dao: StatsDao,
    private val prefs: SharedPreferences,
    private val scope: CoroutineScope,
) : Player.Listener {

    private val handler = Handler(Looper.getMainLooper())
    private var trackId: Long? = null
    private var listened = 0.0
    private var lastPos = 0.0
    private var duration = 0.0
    private var counted = false

    private val tick = object : Runnable {
        override fun run() {
            sample()
            if (player.isPlaying) handler.postDelayed(this, TICK_MS)
        }
    }

    init {
        player.addListener(this)
    }

    override fun onIsPlayingChanged(isPlaying: Boolean) {
        sample()
        handler.removeCallbacks(tick)
        if (isPlaying) handler.postDelayed(tick, TICK_MS)
    }

    override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
        // Also fires when repeat-one loops, which banks the finished pass.
        bank()
        start(mediaItem)
    }

    override fun onPositionDiscontinuity(
        oldPosition: Player.PositionInfo,
        newPosition: Player.PositionInfo,
        reason: Int,
    ) {
        if (reason == Player.DISCONTINUITY_REASON_SEEK || reason == Player.DISCONTINUITY_REASON_SEEK_ADJUSTMENT) {
            // Jumping around doesn't count as listening.
            lastPos = newPosition.positionMs / 1000.0
        }
    }

    private fun start(item: MediaItem?) {
        trackId = item?.mediaId?.toLongOrNull()
        listened = 0.0
        lastPos = player.currentPosition / 1000.0
        duration = 0.0
        counted = false
    }

    private fun sample() {
        if (trackId == null) {
            start(player.currentMediaItem)
            return
        }
        val cur = player.currentPosition / 1000.0
        val d = if (player.duration > 0) player.duration / 1000.0 else duration
        val delta = cur - lastPos
        if (delta > 0 && delta < 2.5) {
            listened += delta
            if (!counted && d > 0 && listened >= min(d * 0.5, 240.0)) counted = true
        }
        lastPos = cur
        if (d > 0) duration = d
    }

    /** Save the current session (if it's long enough) and start over. */
    fun bank() {
        val id = trackId
        val seconds = listened
        val wasCounted = counted
        trackId = null
        listened = 0.0
        counted = false
        if (id == null || seconds < 5 || !prefs.getBoolean(KEY_STATS_ENABLED, true)) return
        val row = PlayEntity(
            trackId = id,
            playedAt = System.currentTimeMillis(),
            seconds = seconds.roundToLong(),
            counted = wasCounted,
        )
        scope.launch { dao.insert(row) }
    }

    fun release() {
        sample()
        bank()
        handler.removeCallbacks(tick)
        player.removeListener(this)
    }

    companion object {
        const val KEY_STATS_ENABLED = "statsEnabled"
        private const val TICK_MS = 500L
    }
}

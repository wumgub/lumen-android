package com.lumen.android.playback

import android.content.Context
import android.content.Intent
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.lumen.android.data.AppDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * Hosts the ExoPlayer in a MediaSessionService so playback keeps running in the
 * background with lockscreen / notification controls. The audio path carries
 * Lumen's volume stage (up to 150%) and the visualizer tap, and the service
 * records listening stats and evens out loudness (Balance volume).
 */
@OptIn(UnstableApi::class)
class PlaybackService : MediaSessionService() {

    private var session: MediaSession? = null
    private var tracker: ListenTracker? = null
    private var balance: BalanceVolume? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        val prefs = getSharedPreferences("lumen", Context.MODE_PRIVATE)
        Volume.level = prefs.getFloat(KEY_VOLUME, 1f)

        val audioAttributes = AudioAttributes.Builder()
            .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
            .setUsage(C.USAGE_MEDIA)
            .build()

        val player = ExoPlayer.Builder(this, LumenRenderersFactory(this))
            .setAudioAttributes(audioAttributes, /* handleAudioFocus = */ true)
            .setHandleAudioBecomingNoisy(true)
            .build()

        tracker = ListenTracker(player, AppDatabase.get(this).statsDao(), prefs, scope)
        balance = BalanceVolume(this, player, AppDatabase.get(this).loudnessDao(), prefs, scope)
        player.addListener(SkipBroken(player))
        session = MediaSession.Builder(this, player).build()
    }

    /**
     * A song that can't be played (gone, half-synced, or a format the phone
     * can't decode) would leave the player stopped with an error. Move on to
     * the next one instead, until every song in the queue has been tried.
     */
    private class SkipBroken(private val player: ExoPlayer) : Player.Listener {
        private var failures = 0

        override fun onPlayerError(error: PlaybackException) {
            lastFailed = player.currentMediaItem?.mediaMetadata?.title?.toString()
            failures++
            if (failures >= player.mediaItemCount || !player.hasNextMediaItem()) {
                // Nothing left to try. Play prepares it again.
                failures = 0
                return
            }
            player.seekToNextMediaItem()
            player.prepare()
            player.play()
        }

        override fun onPlaybackStateChanged(state: Int) {
            if (state == Player.STATE_READY) failures = 0
        }
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session

    override fun onTaskRemoved(rootIntent: Intent?) {
        val p = session?.player
        if (p == null || !p.playWhenReady || p.mediaItemCount == 0) {
            stopSelf()
        }
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        tracker?.release()
        tracker = null
        balance?.release()
        balance = null
        session?.run {
            player.release()
            release()
        }
        session = null
        // The scope isn't cancelled: the last session's save may still be running.
        super.onDestroy()
    }

    companion object {
        const val KEY_VOLUME = "volume"

        /** The title of the last song that couldn't be played, for the app's message. */
        @Volatile var lastFailed: String? = null
    }
}

package com.lumen.android.playback

import android.content.Context
import android.content.Intent
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
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
 * records listening stats.
 */
@OptIn(UnstableApi::class)
class PlaybackService : MediaSessionService() {

    private var session: MediaSession? = null
    private var tracker: ListenTracker? = null
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
        session = MediaSession.Builder(this, player).build()
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
    }
}

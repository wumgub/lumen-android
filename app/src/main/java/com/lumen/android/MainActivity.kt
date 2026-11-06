package com.lumen.android

import android.content.ComponentName
import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.MoreExecutors
import com.lumen.android.model.Track
import com.lumen.android.playback.PlaybackService
import com.lumen.android.playback.PlayerActions
import com.lumen.android.playback.PlayerState
import com.lumen.android.playback.QueueEntry
import com.lumen.android.playback.Volume
import com.lumen.android.theme.ThemeController
import com.lumen.android.ui.LumenApp
import com.lumen.android.ui.ThemeHost

class MainActivity : ComponentActivity() {

    private val playerState = PlayerState()
    private lateinit var themeController: ThemeController

    private lateinit var controllerFuture: ListenableFuture<MediaController>
    private var controller: MediaController? = null

    private val playerListener = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            playerState.isPlaying.value = isPlaying
        }

        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            pushCurrent(mediaItem)
            pushQueue()
        }

        override fun onTimelineChanged(timeline: Timeline, reason: Int) {
            pushQueue()
        }

        override fun onShuffleModeEnabledChanged(shuffleModeEnabled: Boolean) {
            playerState.shuffle.value = shuffleModeEnabled
        }

        override fun onRepeatModeChanged(repeatMode: Int) {
            playerState.repeatMode.value = repeatMode
        }
    }

    private val actions = PlayerActions(
        play = ::play,
        toggle = ::togglePlay,
        next = { controller?.seekToNext() },
        prev = { controller?.seekToPrevious() },
        seek = { controller?.seekTo(it) },
        toggleShuffle = { controller?.let { it.shuffleModeEnabled = !it.shuffleModeEnabled } },
        cycleRepeat = ::cycleRepeat,
        position = { controller?.currentPosition ?: 0L },
        duration = { controller?.duration ?: 0L },
        playNext = { enqueue(it, next = true) },
        addToQueue = { enqueue(it, next = false) },
        jumpTo = ::jumpTo,
        removeAt = { i -> controller?.let { if (i in 0 until it.mediaItemCount) it.removeMediaItem(i) } },
        clearUpcoming = ::clearUpcoming,
        setVolume = ::setVolume,
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val prefs = getSharedPreferences("lumen", Context.MODE_PRIVATE)
        themeController = ThemeController(prefs)
        Volume.level = prefs.getFloat(PlaybackService.KEY_VOLUME, 1f)
        playerState.volume.value = Volume.level

        setContent {
            ThemeHost(themeController = themeController, playerState = playerState) {
                LumenApp(playerState = playerState, actions = actions, themeController = themeController)
            }
        }
    }

    override fun onStart() {
        super.onStart()
        val token = SessionToken(this, ComponentName(this, PlaybackService::class.java))
        controllerFuture = MediaController.Builder(this, token).buildAsync()
        controllerFuture.addListener({
            val c = controllerFuture.get()
            controller = c
            c.addListener(playerListener)
            playerState.isPlaying.value = c.isPlaying
            playerState.shuffle.value = c.shuffleModeEnabled
            playerState.repeatMode.value = c.repeatMode
            pushCurrent(c.currentMediaItem)
            pushQueue()
        }, MoreExecutors.directExecutor())
    }

    override fun onStop() {
        controller?.removeListener(playerListener)
        controller = null
        MediaController.releaseFuture(controllerFuture)
        super.onStop()
    }

    private fun pushCurrent(item: MediaItem?) {
        playerState.currentId.value = item?.mediaId?.toLongOrNull()
        playerState.title.value = item?.mediaMetadata?.title?.toString()
        playerState.artist.value = item?.mediaMetadata?.artist?.toString()
        playerState.artUri.value = item?.mediaMetadata?.artworkUri?.toString()
    }

    private fun pushQueue() {
        val c = controller ?: return
        playerState.queue.value = (0 until c.mediaItemCount).map { i ->
            val item = c.getMediaItemAt(i)
            QueueEntry(
                trackId = item.mediaId.toLongOrNull(),
                title = item.mediaMetadata.title?.toString() ?: "",
                artist = item.mediaMetadata.artist?.toString() ?: "",
                artUri = item.mediaMetadata.artworkUri?.toString(),
            )
        }
        playerState.index.value = c.currentMediaItemIndex
    }

    private fun enqueue(tracks: List<Track>, next: Boolean) {
        val c = controller ?: return
        if (tracks.isEmpty()) return
        if (c.mediaItemCount == 0) {
            play(tracks, 0)
            return
        }
        val items = tracks.map { it.toMediaItem() }
        if (next) c.addMediaItems(c.currentMediaItemIndex + 1, items) else c.addMediaItems(items)
    }

    private fun jumpTo(index: Int) {
        val c = controller ?: return
        if (index !in 0 until c.mediaItemCount) return
        c.seekToDefaultPosition(index)
        c.play()
    }

    private fun clearUpcoming() {
        val c = controller ?: return
        val from = c.currentMediaItemIndex + 1
        if (from in 1 until c.mediaItemCount) c.removeMediaItems(from, c.mediaItemCount)
    }

    private fun setVolume(v: Float) {
        Volume.level = v
        playerState.volume.value = Volume.level
        getSharedPreferences("lumen", Context.MODE_PRIVATE).edit()
            .putFloat(PlaybackService.KEY_VOLUME, Volume.level).apply()
    }

    private fun play(tracks: List<Track>, index: Int) {
        val c = controller ?: return
        c.setMediaItems(tracks.map { it.toMediaItem() }, index, 0L)
        c.prepare()
        c.play()
    }

    private fun togglePlay() {
        val c = controller ?: return
        if (c.isPlaying) c.pause() else c.play()
    }

    private fun cycleRepeat() {
        val c = controller ?: return
        c.repeatMode = when (c.repeatMode) {
            Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL
            Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
            else -> Player.REPEAT_MODE_OFF
        }
    }
}

private fun Track.toMediaItem(): MediaItem {
    val metadata = MediaMetadata.Builder()
        .setTitle(title)
        .setArtist(artist)
        .apply { artUri?.let { setArtworkUri(it) } }
        .build()
    return MediaItem.Builder()
        .setMediaId(id.toString())
        .setUri(uri)
        .setMediaMetadata(metadata)
        .build()
}

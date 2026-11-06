package com.lumen.android.playback

import androidx.media3.common.Player
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * Snapshot of what's playing, pushed from the MediaController's listener and
 * observed by the Compose UI.
 */
class PlayerState {
    val isPlaying = MutableStateFlow(false)
    val currentId = MutableStateFlow<Long?>(null)
    val title = MutableStateFlow<String?>(null)
    val artist = MutableStateFlow<String?>(null)
    val artUri = MutableStateFlow<String?>(null)
    val shuffle = MutableStateFlow(false)
    val repeatMode = MutableStateFlow(Player.REPEAT_MODE_OFF)

    /** The play queue, in playback order, and where we are in it. */
    val queue = MutableStateFlow<List<QueueEntry>>(emptyList())
    val index = MutableStateFlow(-1)

    /** 0 to [Volume.MAX]. */
    val volume = MutableStateFlow(1f)
}

data class QueueEntry(val trackId: Long?, val title: String, val artist: String, val artUri: String?)

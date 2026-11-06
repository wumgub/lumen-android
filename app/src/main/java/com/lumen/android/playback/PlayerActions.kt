package com.lumen.android.playback

import com.lumen.android.model.Track

/** Callbacks the UI uses to drive playback, supplied by MainActivity. */
class PlayerActions(
    val play: (tracks: List<Track>, index: Int) -> Unit,
    val toggle: () -> Unit,
    val next: () -> Unit,
    val prev: () -> Unit,
    val seek: (positionMs: Long) -> Unit,
    val toggleShuffle: () -> Unit,
    val cycleRepeat: () -> Unit,
    val position: () -> Long,
    val duration: () -> Long,
    /** Insert after the current song (starts playing if nothing is). */
    val playNext: (tracks: List<Track>) -> Unit,
    /** Append to the end of the queue (starts playing if nothing is). */
    val addToQueue: (tracks: List<Track>) -> Unit,
    val jumpTo: (index: Int) -> Unit,
    val removeAt: (index: Int) -> Unit,
    /** Drop everything after the current song. */
    val clearUpcoming: () -> Unit,
    val setVolume: (volume: Float) -> Unit,
)

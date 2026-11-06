package com.lumen.android.model

import android.net.Uri

/** One audio track from the device's MediaStore, with the user's edits applied. */
data class Track(
    val id: Long,
    val title: String,
    val artist: String,
    val album: String,
    val albumId: Long,
    val durationMs: Long,
    val trackNo: Int,
    val uri: Uri,
    val artUri: Uri?,
    val discNo: Int = 0,
    val year: Int = 0,
    /** When MediaStore first saw the file, in epoch seconds. */
    val dateAdded: Long = 0,
    /** The user's free-text note; search matches it too. */
    val note: String = "",
)

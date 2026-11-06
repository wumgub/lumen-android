package com.lumen.android.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * The user's edits to one track, re-applied over every scan (the files are
 * never changed). A null field means "use what the file says".
 */
@Entity(tableName = "overrides")
data class OverrideEntity(
    @PrimaryKey val trackId: Long,
    val title: String? = null,
    val artist: String? = null,
    val album: String? = null,
    val note: String = "",
)

/**
 * An album's cover, when it isn't the first song's art. `source` is "track"
 * (use [trackId]'s art) or "custom" (an image copied to [path]).
 */
@Entity(tableName = "album_covers")
data class AlbumCoverEntity(
    @PrimaryKey val album: String,
    val source: String,
    val trackId: Long? = null,
    val path: String? = null,
)

/** One listening session, for the Stats screen. */
@Entity(tableName = "plays", indices = [Index("trackId"), Index("playedAt")])
data class PlayEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val trackId: Long,
    /** Epoch ms. */
    val playedAt: Long,
    /** Whole seconds actually listened. */
    val seconds: Long,
    /** Long enough to count as a play. */
    val counted: Boolean,
)

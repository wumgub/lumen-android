package com.lumen.android.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [
        PlaylistEntity::class,
        PlaylistTrackEntity::class,
        OverrideEntity::class,
        AlbumCoverEntity::class,
        PlayEntity::class,
    ],
    version = 2,
    exportSchema = false,
)
abstract class AppDatabase : RoomDatabase() {

    abstract fun playlistDao(): PlaylistDao
    abstract fun editsDao(): EditsDao
    abstract fun statsDao(): StatsDao

    companion object {
        @Volatile
        private var instance: AppDatabase? = null

        fun get(context: Context): AppDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(
                context.applicationContext,
                AppDatabase::class.java,
                "lumen.db",
            )
                // People's playlists, stats and edits live here now (some of
                // them imported from the WebView app), so a schema change must
                // come with a Migration. Never fall back to wiping it.
                .build().also { instance = it }
        }
    }
}

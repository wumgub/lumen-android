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
                // Nobody has this database yet (the WebView app kept its data in
                // IndexedDB), so schema changes can still start fresh. Once the
                // native app is released this has to become real migrations.
                .fallbackToDestructiveMigration()
                .build().also { instance = it }
        }
    }
}

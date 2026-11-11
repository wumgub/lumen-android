package com.lumen.android.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        PlaylistEntity::class,
        PlaylistTrackEntity::class,
        OverrideEntity::class,
        AlbumCoverEntity::class,
        PlayEntity::class,
        LoudnessEntity::class,
    ],
    version = 3,
    exportSchema = false,
)
abstract class AppDatabase : RoomDatabase() {

    abstract fun playlistDao(): PlaylistDao
    abstract fun editsDao(): EditsDao
    abstract fun statsDao(): StatsDao
    abstract fun loudnessDao(): LoudnessDao

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
                .addMigrations(MIGRATION_2_3)
                .build().also { instance = it }
        }

        /** 0.4.3: Balance volume's measurements. */
        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `loudness` (`mediaId` INTEGER NOT NULL, `lufs` REAL, " +
                        "`peak` REAL, `fileKey` TEXT NOT NULL, PRIMARY KEY(`mediaId`))",
                )
            }
        }
    }
}

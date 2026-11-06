package com.lumen.android.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface EditsDao {
    @Query("SELECT * FROM overrides")
    fun overrides(): Flow<List<OverrideEntity>>

    @Query("SELECT * FROM overrides WHERE trackId IN (:ids)")
    suspend fun overridesFor(ids: List<Long>): List<OverrideEntity>

    @Upsert
    suspend fun upsertOverrides(rows: List<OverrideEntity>)

    @Query("SELECT * FROM album_covers")
    fun covers(): Flow<List<AlbumCoverEntity>>

    @Query("SELECT * FROM album_covers WHERE album = :album")
    suspend fun cover(album: String): AlbumCoverEntity?

    @Upsert
    suspend fun upsertCover(cover: AlbumCoverEntity)

    @Query("DELETE FROM album_covers WHERE album = :album")
    suspend fun deleteCover(album: String)

    @Query("UPDATE album_covers SET album = :to WHERE album = :from")
    suspend fun moveCover(from: String, to: String)
}

@Dao
interface StatsDao {
    @Insert
    suspend fun insert(play: PlayEntity)

    @Insert
    suspend fun insertAll(plays: List<PlayEntity>)

    @Query("SELECT * FROM plays ORDER BY playedAt ASC")
    fun plays(): Flow<List<PlayEntity>>

    @Query("DELETE FROM plays")
    suspend fun clear()
}

package com.lumen.android.data

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query

/**
 * Balance volume: a song's measured loudness (LUFS) and sample peak, with the
 * file's size and modified time when it was measured, so a changed file gets
 * measured again. A null [lufs] means it couldn't be measured.
 */
@Entity(tableName = "loudness")
data class LoudnessEntity(
    @PrimaryKey val mediaId: Long,
    val lufs: Double?,
    val peak: Double?,
    val fileKey: String,
)

@Dao
interface LoudnessDao {
    @Query("SELECT * FROM loudness")
    suspend fun all(): List<LoudnessEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun put(row: LoudnessEntity)
}

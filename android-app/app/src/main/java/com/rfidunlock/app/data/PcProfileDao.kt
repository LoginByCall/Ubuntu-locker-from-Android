package com.rfidunlock.app.data

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface PcProfileDao {

    @Query("SELECT * FROM pc_profiles ORDER BY name COLLATE NOCASE ASC")
    fun observeAll(): Flow<List<PcProfile>>

    @Query("SELECT * FROM pc_profiles ORDER BY name COLLATE NOCASE ASC")
    suspend fun findAll(): List<PcProfile>

    @Query("SELECT * FROM pc_profiles WHERE id = :id LIMIT 1")
    suspend fun findById(id: String): PcProfile?

    @Query("SELECT COUNT(*) FROM pc_profiles")
    suspend fun count(): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(profile: PcProfile)

    @Update
    suspend fun update(profile: PcProfile)

    @Delete
    suspend fun delete(profile: PcProfile)
}

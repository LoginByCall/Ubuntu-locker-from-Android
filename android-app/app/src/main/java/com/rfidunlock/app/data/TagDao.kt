package com.rfidunlock.app.data

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface TagDao {

    @Query("SELECT * FROM tags ORDER BY name COLLATE NOCASE ASC")
    fun observeAll(): Flow<List<Tag>>

    @Query("SELECT * FROM tags WHERE uid = :uid LIMIT 1")
    suspend fun findByUid(uid: String): Tag?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(tag: Tag)

    @Update
    suspend fun update(tag: Tag)

    @Delete
    suspend fun delete(tag: Tag)
}

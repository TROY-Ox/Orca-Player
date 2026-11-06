package com.troy.orcaplayer.core.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface ProfileDao {
    @Query("SELECT * FROM profiles ORDER BY sortOrder, createdAt")
    fun observeAll(): Flow<List<ProfileEntity>>

    @Query("SELECT * FROM profiles WHERE id = :id")
    suspend fun get(id: String): ProfileEntity?

    @Query("SELECT COUNT(*) FROM profiles")
    suspend fun count(): Int

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIfMissing(profile: ProfileEntity)

    @Upsert
    suspend fun upsert(profile: ProfileEntity)
}

@Dao
interface ResumeDao {
    @Query("SELECT * FROM resume_positions WHERE profileId = :profileId AND mediaKey = :mediaKey")
    suspend fun get(profileId: String, mediaKey: String): ResumePositionEntity?

    @Query("SELECT * FROM resume_positions WHERE profileId = :profileId ORDER BY updatedAt DESC LIMIT :limit")
    fun observeRecent(profileId: String, limit: Int): Flow<List<ResumePositionEntity>>

    @Query("SELECT * FROM resume_positions WHERE profileId = :profileId")
    fun observeAll(profileId: String): Flow<List<ResumePositionEntity>>

    @Upsert
    suspend fun upsert(position: ResumePositionEntity)

    @Query("DELETE FROM resume_positions WHERE profileId = :profileId AND mediaKey = :mediaKey")
    suspend fun delete(profileId: String, mediaKey: String)
}

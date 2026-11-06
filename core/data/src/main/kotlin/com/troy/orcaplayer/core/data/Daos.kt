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

    @Query("DELETE FROM profiles WHERE id = :id")
    suspend fun delete(id: String)

    @Query("UPDATE profiles SET sortOrder = :order WHERE id = :id")
    suspend fun setOrder(id: String, order: Int)
}

@Dao
interface MyListDao {
    @Query("SELECT * FROM my_list WHERE profileId = :profileId ORDER BY sortOrder, addedAt DESC")
    fun observe(profileId: String): Flow<List<MyListEntity>>

    @Query("SELECT * FROM my_list")
    suspend fun everything(): List<MyListEntity>

    @Query("SELECT MIN(sortOrder) FROM my_list WHERE profileId = :profileId")
    suspend fun minOrder(profileId: String): Int?

    @Upsert
    suspend fun upsert(item: MyListEntity)

    @Query("DELETE FROM my_list WHERE profileId = :profileId AND itemKey = :itemKey")
    suspend fun delete(profileId: String, itemKey: String)

    @Query("UPDATE my_list SET sortOrder = :order WHERE profileId = :profileId AND itemKey = :itemKey")
    suspend fun setOrder(profileId: String, itemKey: String, order: Int)

    @Query("UPDATE my_list SET knownEpisodes = :count, lastEpisodeId = :last WHERE profileId = :profileId AND itemKey = :itemKey")
    suspend fun setKnownEpisodes(profileId: String, itemKey: String, count: Int, last: String?)
}

@Dao
interface WatchTimeDao {
    @Query("SELECT * FROM watch_time WHERE profileId = :profileId AND day = :day")
    fun observe(profileId: String, day: String): Flow<WatchTimeEntity?>

    @Query("SELECT * FROM watch_time WHERE profileId = :profileId AND day = :day")
    suspend fun get(profileId: String, day: String): WatchTimeEntity?

    @Upsert
    suspend fun upsert(entry: WatchTimeEntity)

    @Query("DELETE FROM watch_time WHERE day < :before")
    suspend fun prune(before: String)
}

@Dao
interface SkipMarkDao {
    @Query("SELECT * FROM skip_marks WHERE seriesKey = :seriesKey")
    suspend fun forSeries(seriesKey: String): List<SkipMarkEntity>

    @Upsert
    suspend fun upsert(mark: SkipMarkEntity)

    @Query("DELETE FROM skip_marks WHERE seriesKey = :seriesKey AND kind = :kind")
    suspend fun delete(seriesKey: String, kind: String)
}

@Dao
interface ResumeDao {
    @Query("SELECT * FROM resume_positions WHERE profileId = :profileId AND mediaKey = :mediaKey")
    suspend fun get(profileId: String, mediaKey: String): ResumePositionEntity?

    @Query("SELECT * FROM resume_positions WHERE profileId = :profileId ORDER BY updatedAt DESC LIMIT :limit")
    fun observeRecent(profileId: String, limit: Int): Flow<List<ResumePositionEntity>>

    @Query("SELECT * FROM resume_positions WHERE profileId = :profileId AND hidden = 0 ORDER BY updatedAt DESC LIMIT :limit")
    fun observeVisible(profileId: String, limit: Int): Flow<List<ResumePositionEntity>>

    @Query("UPDATE resume_positions SET hidden = 1 WHERE profileId = :profileId AND mediaKey = :mediaKey")
    suspend fun hide(profileId: String, mediaKey: String)

    @Query("SELECT * FROM resume_positions WHERE profileId = :profileId AND titleId = :titleId ORDER BY updatedAt DESC")
    suspend fun forTitle(profileId: String, titleId: String): List<ResumePositionEntity>

    @Query("SELECT * FROM resume_positions WHERE profileId = :profileId")
    fun observeAll(profileId: String): Flow<List<ResumePositionEntity>>

    @Upsert
    suspend fun upsert(position: ResumePositionEntity)

    @Query("DELETE FROM resume_positions WHERE profileId = :profileId AND mediaKey = :mediaKey")
    suspend fun delete(profileId: String, mediaKey: String)
}

@Dao
interface LibraryDao {
    @Query("SELECT * FROM library_items")
    fun observeItems(): Flow<List<LibraryItemEntity>>

    @Query("SELECT * FROM library_shows")
    fun observeShows(): Flow<List<LibraryShowEntity>>

    @Query("SELECT * FROM library_items")
    suspend fun items(): List<LibraryItemEntity>

    @Query("SELECT * FROM library_items WHERE id = :id")
    suspend fun item(id: String): LibraryItemEntity?

    @Query("SELECT * FROM library_items WHERE showKey = :showKey")
    suspend fun episodes(showKey: String): List<LibraryItemEntity>

    @Query("SELECT * FROM library_shows WHERE showKey = :showKey")
    suspend fun show(showKey: String): LibraryShowEntity?

    @Query("SELECT * FROM library_shows")
    suspend fun shows(): List<LibraryShowEntity>

    @Upsert
    suspend fun upsertItems(items: List<LibraryItemEntity>)

    @Upsert
    suspend fun upsertShow(show: LibraryShowEntity)

    @Query("DELETE FROM library_items WHERE id IN (:ids)")
    suspend fun deleteItems(ids: List<String>)

    @Query("DELETE FROM library_shows WHERE showKey NOT IN (SELECT DISTINCT showKey FROM library_items WHERE showKey IS NOT NULL)")
    suspend fun deleteEmptyShows()

    @Query("DELETE FROM library_items")
    suspend fun clearItems()

    @Query("DELETE FROM library_shows")
    suspend fun clearShows()

    @Query("SELECT * FROM library_watched WHERE profileId = :profileId")
    fun observeWatched(profileId: String): Flow<List<LibraryWatchedEntity>>

    @Upsert
    suspend fun markWatched(marks: List<LibraryWatchedEntity>)

    @Query("DELETE FROM library_watched WHERE profileId = :profileId AND itemId IN (:ids)")
    suspend fun unmarkWatched(profileId: String, ids: List<String>)
}

package com.boardgamenation.tracker.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.boardgamenation.tracker.data.db.entity.BggThingCacheEntity

@Dao
interface BggCacheDao {

    /**
     * Returns a cached body only if it is inside the TTL. Expiry is applied in SQL so
     * a stale row can never be mistaken for a hit by a caller that forgot to check.
     */
    @Query("SELECT * FROM bgg_thing_cache WHERE bgg_id = :bggId AND fetched_at >= :notBefore")
    suspend fun getFresh(bggId: Long, notBefore: Long): BggThingCacheEntity?

    @Query("SELECT * FROM bgg_thing_cache WHERE bgg_id = :bggId")
    suspend fun getAny(bggId: Long): BggThingCacheEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun put(entry: BggThingCacheEntity)

    @Query("DELETE FROM bgg_thing_cache WHERE bgg_id = :bggId")
    suspend fun evict(bggId: Long)

    @Query("DELETE FROM bgg_thing_cache WHERE fetched_at < :notAfter")
    suspend fun evictOlderThan(notAfter: Long)

    @Query("DELETE FROM bgg_thing_cache")
    suspend fun clear()

    @Query("SELECT COUNT(*) FROM bgg_thing_cache")
    suspend fun count(): Int
}

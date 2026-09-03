package com.boardgamenation.tracker.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Raw BGG `thing` responses, kept so metadata the app already has is never refetched.
 * Entries older than the TTL are treated as absent; the user can force a refresh.
 */
@Entity(tableName = "bgg_thing_cache")
data class BggThingCacheEntity(
    @PrimaryKey
    @ColumnInfo(name = "bgg_id") val bggId: Long,
    @ColumnInfo(name = "xml") val xml: String,
    @ColumnInfo(name = "fetched_at") val fetchedAt: Long
)

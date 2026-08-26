package com.boardgamenation.tracker.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * A local-only player profile. There is no authentication and no link to any account:
 * these are just names the user types once and reuses.
 */
@Entity(
    tableName = "players",
    indices = [Index(value = ["name"], unique = true)],
)
data class PlayerEntity(
    @PrimaryKey(autoGenerate = true)
    @ColumnInfo(name = "id") val id: Long = 0,
    @ColumnInfo(name = "name") val name: String,

    /** Exactly one row carries this: the device owner. */
    @ColumnInfo(name = "is_self", defaultValue = "0") val isSelf: Boolean = false,

    /** #RRGGBB, used for charts and the timer's player zones. */
    @ColumnInfo(name = "color_hex") val colorHex: String? = null,
    @ColumnInfo(name = "notes") val notes: String? = null,

    /** Hidden from pickers but kept so historical sessions stay intact. */
    @ColumnInfo(name = "archived", defaultValue = "0") val archived: Boolean = false,
)

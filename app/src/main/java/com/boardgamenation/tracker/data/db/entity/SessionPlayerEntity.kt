package com.boardgamenation.tracker.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * One participant in one session. [isWinner] is explicit rather than derived from
 * [placement] because plenty of games do not rank by score, and ties mean more than
 * one player can share placement 1.
 */
@Entity(
    tableName = "session_players",
    foreignKeys = [
        ForeignKey(
            entity = SessionEntity::class,
            parentColumns = ["id"],
            childColumns = ["session_id"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = PlayerEntity::class,
            parentColumns = ["id"],
            childColumns = ["player_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(value = ["session_id", "player_id"], unique = true),
        Index(value = ["player_id"]),
    ],
)
data class SessionPlayerEntity(
    @PrimaryKey(autoGenerate = true)
    @ColumnInfo(name = "id") val id: Long = 0,
    @ColumnInfo(name = "session_id") val sessionId: Long,
    @ColumnInfo(name = "player_id") val playerId: Long,

    /** Nullable: many games have no score at all. */
    @ColumnInfo(name = "score") val score: Double? = null,

    /** 1 is the winner. Tied players share a placement. */
    @ColumnInfo(name = "placement") val placement: Int? = null,
    @ColumnInfo(name = "is_winner", defaultValue = "0") val isWinner: Boolean = false,

    /** Role, character, colour, or faction played. */
    @ColumnInfo(name = "faction") val faction: String? = null,

    /** First time this player played this game. */
    @ColumnInfo(name = "is_new_player", defaultValue = "0") val isNewPlayer: Boolean = false,

    /** Total time on the turn clock across all of this player's turns. Timer only. */
    @ColumnInfo(name = "turn_time_ms") val turnTimeMs: Long? = null,

    /** Bank left when the timer stopped; negative means overtime. Timer only. */
    @ColumnInfo(name = "bank_time_remaining_ms") val bankTimeRemainingMs: Long? = null,
)

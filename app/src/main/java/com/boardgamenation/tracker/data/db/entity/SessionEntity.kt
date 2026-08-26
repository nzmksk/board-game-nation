package com.boardgamenation.tracker.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import com.boardgamenation.tracker.domain.model.CoopOutcome

/**
 * One row per play. [playerCount] is denormalised off session_players because almost
 * every statistic filters or groups by it and a join per row is not worth it.
 */
@Entity(
    tableName = "sessions",
    foreignKeys = [
        ForeignKey(
            entity = GameEntity::class,
            parentColumns = ["id"],
            childColumns = ["game_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(value = ["game_id"]),
        Index(value = ["played_on"]),
        Index(value = ["is_draft"]),
    ],
)
data class SessionEntity(
    @PrimaryKey(autoGenerate = true)
    @ColumnInfo(name = "id") val id: Long = 0,
    @ColumnInfo(name = "game_id") val gameId: Long,

    /** ISO-8601 date, YYYY-MM-DD. */
    @ColumnInfo(name = "played_on") val playedOn: String,

    /** Epoch ms; set only when the session was timed live. */
    @ColumnInfo(name = "started_at") val startedAt: Long? = null,
    @ColumnInfo(name = "ended_at") val endedAt: Long? = null,

    /** Actual elapsed minutes. Auto-filled by the timer, always editable. */
    @ColumnInfo(name = "duration_minutes") val durationMinutes: Int,
    @ColumnInfo(name = "player_count") val playerCount: Int,
    @ColumnInfo(name = "location") val location: String? = null,

    @ColumnInfo(name = "is_cooperative", defaultValue = "0") val isCooperative: Boolean = false,
    @ColumnInfo(name = "coop_outcome") val coopOutcome: CoopOutcome? = null,

    /** Abandoned before finishing: counted in play totals, excluded from duration averages. */
    @ColumnInfo(name = "is_incomplete", defaultValue = "0") val isIncomplete: Boolean = false,

    /** Someone was learning, which skews duration. Flagged separately in stats. */
    @ColumnInfo(name = "is_teaching_game", defaultValue = "0") val isTeachingGame: Boolean = false,

    /**
     * A session created by the timer but not yet saved by the user. Drafts are hidden
     * from every list and statistic until confirmed, and are offered for recovery on
     * the next launch after a process death.
     */
    @ColumnInfo(name = "is_draft", defaultValue = "0") val isDraft: Boolean = false,

    /** Time the table spent globally paused. Belongs to nobody; subtracted from duration. */
    @ColumnInfo(name = "paused_ms", defaultValue = "0") val pausedMs: Long = 0,

    /** SAF uri of an optional photo attachment. */
    @ColumnInfo(name = "photo_uri") val photoUri: String? = null,

    @ColumnInfo(name = "notes") val notes: String? = null,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
)

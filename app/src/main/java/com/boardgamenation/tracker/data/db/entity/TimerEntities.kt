package com.boardgamenation.tracker.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import com.boardgamenation.tracker.domain.model.ActiveClock
import com.boardgamenation.tracker.domain.model.BankExhaustedBehaviour
import com.boardgamenation.tracker.domain.model.TimerMode
import com.boardgamenation.tracker.domain.model.TimerRunState

/** A reusable clock configuration, optionally pinned to one game. */
@Entity(
    tableName = "timer_presets",
    foreignKeys = [
        ForeignKey(
            entity = GameEntity::class,
            parentColumns = ["id"],
            childColumns = ["game_id"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index(value = ["game_id"])]
)
data class TimerPresetEntity(
    @PrimaryKey(autoGenerate = true)
    @ColumnInfo(name = "id") val id: Long = 0,
    @ColumnInfo(name = "name") val name: String,
    @ColumnInfo(name = "turn_seconds", defaultValue = "60") val turnSeconds: Int = 60,
    @ColumnInfo(name = "bank_seconds", defaultValue = "600") val bankSeconds: Int = 600,
    @ColumnInfo(name = "warning_threshold_seconds", defaultValue = "10")
    val warningThresholdSeconds: Int = 10,
    @ColumnInfo(name = "sound_enabled", defaultValue = "1") val soundEnabled: Boolean = true,
    @ColumnInfo(name = "haptics_enabled", defaultValue = "1") val hapticsEnabled: Boolean = true,
    @ColumnInfo(name = "auto_pass_on_bank_empty", defaultValue = "0")
    val autoPassOnBankEmpty: Boolean = false,
    @ColumnInfo(name = "game_id") val gameId: Long? = null
)

/**
 * The live timer, persisted at every state transition so a process kill costs at most
 * one turn.
 *
 * Only one timer can run at a time, so this table holds a single row pinned to
 * [SINGLETON_ID]. Every duration here is *accumulated*: the running delta is folded in
 * before writing, and nothing stored depends on a clock base surviving the write. That
 * is what lets restore be a plain read rather than an attempt to reconstruct how much
 * time passed while the process was dead.
 *
 * [savedAtElapsedRealtime] and [savedAtWallClock] are recorded together purely to detect
 * a reboot on restore, since elapsedRealtime resets to zero and would otherwise look
 * like time travelling backwards.
 */
@Entity(
    tableName = "timer_state",
    foreignKeys = [
        ForeignKey(
            entity = SessionEntity::class,
            parentColumns = ["id"],
            childColumns = ["session_id"],
            onDelete = ForeignKey.SET_NULL
        ),
        ForeignKey(
            entity = GameEntity::class,
            parentColumns = ["id"],
            childColumns = ["game_id"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index(value = ["session_id"]), Index(value = ["game_id"])]
)
data class TimerStateEntity(
    @PrimaryKey
    @ColumnInfo(name = "id") val id: Long = SINGLETON_ID,

    /** The draft session this timer is filling in. */
    @ColumnInfo(name = "session_id") val sessionId: Long? = null,
    @ColumnInfo(name = "game_id") val gameId: Long,

    @ColumnInfo(name = "run_state") val runState: TimerRunState = TimerRunState.IDLE,

    /** Whether this clock counts seats down or the whole table up. */
    @ColumnInfo(name = "mode", defaultValue = "'TURN_BASED'")
    val mode: TimerMode = TimerMode.TURN_BASED,

    /** Accumulated play time for a count-up clock, which belongs to no seat. */
    @ColumnInfo(name = "table_time_ms", defaultValue = "0") val tableTimeMs: Long = 0,

    /** Index into the seat order, not a player id: seats can be skipped or reversed. */
    @ColumnInfo(name = "active_seat") val activeSeat: Int = 0,

    /** +1 clockwise, -1 after a direction-reversal effect. */
    @ColumnInfo(name = "direction", defaultValue = "1") val direction: Int = 1,

    @ColumnInfo(name = "active_clock") val activeClock: ActiveClock = ActiveClock.TURN,

    @ColumnInfo(name = "turn_seconds") val turnSeconds: Int = 60,
    @ColumnInfo(name = "bank_seconds") val bankSeconds: Int = 600,
    @ColumnInfo(name = "warning_threshold_seconds") val warningThresholdSeconds: Int = 10,
    @ColumnInfo(name = "sound_enabled", defaultValue = "1") val soundEnabled: Boolean = true,
    @ColumnInfo(name = "haptics_enabled", defaultValue = "1") val hapticsEnabled: Boolean = true,
    @ColumnInfo(name = "bank_exhausted_behaviour")
    val bankExhaustedBehaviour: BankExhaustedBehaviour = BankExhaustedBehaviour.FLAG_AND_OVERTIME,

    /** Wall-clock start of the play, used to fill in the session's started_at. */
    @ColumnInfo(name = "started_at") val startedAt: Long? = null,

    /** Time the table spent globally paused. Accrues to nobody. */
    @ColumnInfo(name = "accumulated_paused_ms", defaultValue = "0") val accumulatedPausedMs: Long = 0,

    @ColumnInfo(name = "saved_at_elapsed_realtime", defaultValue = "0")
    val savedAtElapsedRealtime: Long = 0,
    @ColumnInfo(name = "saved_at_wall_clock", defaultValue = "0") val savedAtWallClock: Long = 0,

    /** Serialised previous state, powering the single level of undo for a misclick. */
    @ColumnInfo(name = "undo_snapshot") val undoSnapshot: String? = null
) {
    companion object {
        const val SINGLETON_ID = 1L
    }
}

/** One seat at the table for the live timer. */
@Entity(
    tableName = "timer_seats",
    foreignKeys = [
        ForeignKey(
            entity = PlayerEntity::class,
            parentColumns = ["id"],
            childColumns = ["player_id"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index(value = ["seat_order"], unique = true), Index(value = ["player_id"])]
)
data class TimerSeatEntity(
    @PrimaryKey(autoGenerate = true)
    @ColumnInfo(name = "id") val id: Long = 0,
    @ColumnInfo(name = "player_id") val playerId: Long,
    @ColumnInfo(name = "seat_order") val seatOrder: Int,

    /** Remaining on this seat's current turn clock. Refilled at the start of each turn. */
    @ColumnInfo(name = "turn_remaining_ms") val turnRemainingMs: Long,

    /** Reserve. Never replenished; goes negative once overtime starts. */
    @ColumnInfo(name = "bank_remaining_ms") val bankRemainingMs: Long,

    /** Total time on the clock across every turn, written to session_players at stop. */
    @ColumnInfo(name = "total_turn_time_ms", defaultValue = "0") val totalTurnTimeMs: Long = 0,

    @ColumnInfo(name = "turns_taken", defaultValue = "0") val turnsTaken: Int = 0,
    @ColumnInfo(name = "timed_out", defaultValue = "0") val timedOut: Boolean = false,

    /** Temporarily out of the rotation. */
    @ColumnInfo(name = "skipped", defaultValue = "0") val skipped: Boolean = false
)

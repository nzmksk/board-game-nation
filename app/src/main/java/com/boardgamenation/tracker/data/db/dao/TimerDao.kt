package com.boardgamenation.tracker.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import com.boardgamenation.tracker.data.db.entity.TimerPresetEntity
import com.boardgamenation.tracker.data.db.entity.TimerSeatEntity
import com.boardgamenation.tracker.data.db.entity.TimerStateEntity
import com.boardgamenation.tracker.data.db.projection.TimerSeatWithPlayer
import kotlinx.coroutines.flow.Flow

@Dao
interface TimerDao {

    // --- presets ------------------------------------------------------------------

    @Query("SELECT * FROM timer_presets ORDER BY game_id IS NULL DESC, name COLLATE NOCASE")
    fun observePresets(): Flow<List<TimerPresetEntity>>

    @Query("SELECT * FROM timer_presets WHERE game_id = :gameId ORDER BY name COLLATE NOCASE")
    suspend fun presetsForGame(gameId: Long): List<TimerPresetEntity>

    @Query("SELECT * FROM timer_presets WHERE id = :id")
    suspend fun getPreset(id: Long): TimerPresetEntity?

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertPreset(preset: TimerPresetEntity): Long

    @Update
    suspend fun updatePreset(preset: TimerPresetEntity)

    @Query("DELETE FROM timer_presets WHERE id = :id")
    suspend fun deletePreset(id: Long)

    @Query("SELECT COUNT(*) FROM timer_presets")
    suspend fun countPresets(): Int

    // --- live state ---------------------------------------------------------------

    @Query("SELECT * FROM timer_state WHERE id = 1")
    fun observeState(): Flow<TimerStateEntity?>

    @Query("SELECT * FROM timer_state WHERE id = 1")
    suspend fun getState(): TimerStateEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun putState(state: TimerStateEntity)

    @Query("DELETE FROM timer_state")
    suspend fun clearState()

    @Query(
        """
        SELECT
            t.id AS seat_id, t.player_id, p.name AS player_name, p.color_hex,
            t.seat_order, t.turn_remaining_ms, t.bank_remaining_ms,
            t.total_turn_time_ms, t.turns_taken, t.timed_out, t.skipped
        FROM timer_seats t
        JOIN players p ON p.id = t.player_id
        ORDER BY t.seat_order
        """,
    )
    fun observeSeats(): Flow<List<TimerSeatWithPlayer>>

    @Query("SELECT * FROM timer_seats ORDER BY seat_order")
    suspend fun getSeats(): List<TimerSeatEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun putSeats(seats: List<TimerSeatEntity>)

    @Query("DELETE FROM timer_seats")
    suspend fun clearSeats()

    /**
     * One atomic checkpoint of the whole clock. Called at every state transition, which
     * is what bounds a process kill to losing at most the turn in progress.
     */
    @Transaction
    suspend fun checkpoint(state: TimerStateEntity, seats: List<TimerSeatEntity>) {
        putState(state)
        clearSeats()
        putSeats(seats.map { it.copy(id = 0) })
    }

    @Transaction
    suspend fun clearAll() {
        clearSeats()
        clearState()
    }
}

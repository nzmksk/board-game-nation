package com.boardgamenation.tracker.data.repository

import com.boardgamenation.tracker.core.time.AppClock
import com.boardgamenation.tracker.core.time.ElapsedTimeSource
import com.boardgamenation.tracker.data.db.dao.PlayerDao
import com.boardgamenation.tracker.data.db.dao.TimerDao
import com.boardgamenation.tracker.data.db.entity.TimerPresetEntity
import com.boardgamenation.tracker.data.db.entity.TimerSeatEntity
import com.boardgamenation.tracker.data.db.entity.TimerStateEntity
import com.boardgamenation.tracker.domain.model.TimerRunState
import com.boardgamenation.tracker.domain.timer.Seat
import com.boardgamenation.tracker.domain.timer.TimerConfig
import com.boardgamenation.tracker.domain.timer.TimerState
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class TimerRepository @Inject constructor(
    private val timerDao: TimerDao,
    private val playerDao: PlayerDao,
    private val clock: AppClock,
    private val elapsed: ElapsedTimeSource,
) {

    private val json = Json { ignoreUnknownKeys = true }

    fun observePresets(): Flow<List<TimerPresetEntity>> = timerDao.observePresets()

    suspend fun presetsForGame(gameId: Long): List<TimerPresetEntity> =
        timerDao.presetsForGame(gameId)

    suspend fun getPreset(id: Long): TimerPresetEntity? = timerDao.getPreset(id)

    suspend fun savePreset(preset: TimerPresetEntity): Long =
        if (preset.id == 0L) timerDao.insertPreset(preset) else {
            timerDao.updatePreset(preset)
            preset.id
        }

    suspend fun deletePreset(id: Long) = timerDao.deletePreset(id)

    /** Seeds a sensible default the first time the timer screen is opened. */
    suspend fun ensureDefaultPreset() {
        if (timerDao.countPresets() > 0) return
        timerDao.insertPreset(
            TimerPresetEntity(
                name = "Standard",
                turnSeconds = 60,
                bankSeconds = 600,
                warningThresholdSeconds = 10,
            ),
        )
        timerDao.insertPreset(
            TimerPresetEntity(
                name = "Quick",
                turnSeconds = 30,
                bankSeconds = 300,
                warningThresholdSeconds = 5,
            ),
        )
        timerDao.insertPreset(
            TimerPresetEntity(
                name = "Heavy Euro",
                turnSeconds = 120,
                bankSeconds = 1_800,
                warningThresholdSeconds = 15,
            ),
        )
    }

    /**
     * Persists the whole clock atomically.
     *
     * Called at every state transition and on a short repeating interval while running,
     * so a process death costs seconds rather than a turn.
     */
    suspend fun checkpoint(state: TimerState) {
        timerDao.checkpoint(state.toEntity(), state.seats.mapIndexed { index, seat ->
            seat.toEntity(index)
        })
    }

    /**
     * Reads back a clock that outlived its process.
     *
     * It always comes back **paused**, never running. The stored monotonic anchor cannot
     * be trusted across a process death (and is outright meaningless across a reboot,
     * where elapsed-since-boot resets to zero), so the honest thing is to hand the table
     * back a stopped clock at the last checkpoint and let them press resume.
     */
    suspend fun restore(): TimerState? {
        val entity = timerDao.getState() ?: return null
        if (entity.runState == TimerRunState.STOPPED) return null
        val seats = timerDao.getSeats().sortedBy { it.seatOrder }
        if (seats.isEmpty()) return null

        val names = playerDao.getAll().associateBy { it.id }
        return TimerState(
            gameId = entity.gameId,
            sessionId = entity.sessionId,
            config = TimerConfig(
                mode = entity.mode,
                turnMs = entity.turnSeconds * 1000L,
                bankMs = entity.bankSeconds * 1000L,
                warningMs = entity.warningThresholdSeconds * 1000L,
                soundEnabled = entity.soundEnabled,
                hapticsEnabled = entity.hapticsEnabled,
                bankExhausted = entity.bankExhaustedBehaviour,
            ),
            seats = seats.map { seat ->
                Seat(
                    playerId = seat.playerId,
                    name = names[seat.playerId]?.name.orEmpty(),
                    colorHex = names[seat.playerId]?.colorHex,
                    turnRemainingMs = seat.turnRemainingMs,
                    bankRemainingMs = seat.bankRemainingMs,
                    totalTurnTimeMs = seat.totalTurnTimeMs,
                    turnsTaken = seat.turnsTaken,
                    timedOut = seat.timedOut,
                    skipped = seat.skipped,
                )
            },
            activeSeat = entity.activeSeat,
            direction = entity.direction,
            tableTimeMs = entity.tableTimeMs,
            runState = TimerRunState.PAUSED,
            anchorElapsedMs = elapsed.elapsedMillis(),
            startedAtWallMs = entity.startedAt,
            accumulatedPausedMs = entity.accumulatedPausedMs,
            pauseAnchorElapsedMs = elapsed.elapsedMillis(),
            undoSnapshot = entity.undoSnapshot?.let { snapshot ->
                runCatching { json.decodeFromString(TimerState.serializer(), snapshot) }.getOrNull()
            },
        )
    }

    suspend fun clear() = timerDao.clearAll()

    private fun TimerState.toEntity() = TimerStateEntity(
        id = TimerStateEntity.SINGLETON_ID,
        sessionId = sessionId,
        gameId = gameId,
        runState = runState,
        mode = config.mode,
        tableTimeMs = tableTimeMs,
        activeSeat = activeSeat,
        direction = direction,
        activeClock = com.boardgamenation.tracker.domain.model.ActiveClock.TURN,
        turnSeconds = (config.turnMs / 1000).toInt(),
        bankSeconds = (config.bankMs / 1000).toInt(),
        warningThresholdSeconds = (config.warningMs / 1000).toInt(),
        soundEnabled = config.soundEnabled,
        hapticsEnabled = config.hapticsEnabled,
        bankExhaustedBehaviour = config.bankExhausted,
        startedAt = startedAtWallMs,
        accumulatedPausedMs = accumulatedPausedMs,
        savedAtElapsedRealtime = elapsed.elapsedMillis(),
        savedAtWallClock = clock.nowMillis(),
        undoSnapshot = undoSnapshot?.let {
            json.encodeToString(TimerState.serializer(), it.copy(undoSnapshot = null))
        },
    )

    private fun Seat.toEntity(order: Int) = TimerSeatEntity(
        playerId = playerId,
        seatOrder = order,
        turnRemainingMs = turnRemainingMs,
        bankRemainingMs = bankRemainingMs,
        totalTurnTimeMs = totalTurnTimeMs,
        turnsTaken = turnsTaken,
        timedOut = timedOut,
        skipped = skipped,
    )
}

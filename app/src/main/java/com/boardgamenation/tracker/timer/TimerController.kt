package com.boardgamenation.tracker.timer

import android.content.Context
import android.content.Intent
import android.os.Build
import com.boardgamenation.tracker.core.time.AppClock
import com.boardgamenation.tracker.core.time.ElapsedTimeSource
import com.boardgamenation.tracker.data.db.entity.PlayerEntity
import com.boardgamenation.tracker.data.repository.SessionRepository
import com.boardgamenation.tracker.data.repository.TimerRepository
import com.boardgamenation.tracker.di.ApplicationScope
import com.boardgamenation.tracker.domain.model.ParticipantForm
import com.boardgamenation.tracker.domain.model.TimerRunState
import com.boardgamenation.tracker.domain.timer.TimerConfig
import com.boardgamenation.tracker.domain.timer.TimerEngine
import com.boardgamenation.tracker.domain.timer.TimerPlayer
import com.boardgamenation.tracker.domain.timer.TimerProjection
import com.boardgamenation.tracker.domain.timer.TimerState
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

/** Things worth a sound, a buzz, or a colour change. */
sealed interface TimerEvent {
    /** The active clock has dropped below the warning threshold. */
    data class Warning(val playerName: String) : TimerEvent

    /** A player's bank has emptied. */
    data class BankExhausted(val playerName: String) : TimerEvent
    data class TurnPassed(val toPlayerName: String) : TimerEvent
    data object Stopped : TimerEvent
}

/**
 * Owns the running clock for the whole app.
 *
 * A singleton rather than something scoped to a screen, because the timer must keep
 * accurate time while the app is backgrounded and the screen is off. The foreground
 * service exists to keep the *process* alive and visible; the state lives here, so
 * rotating the phone or leaving the timer screen changes nothing.
 *
 * Two rules run through everything below:
 *
 *  1. Elapsed time only ever comes from [ElapsedTimeSource], never the wall clock.
 *  2. Every state transition is checkpointed to the database before it is announced.
 */
@Singleton
class TimerController @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val timerRepository: TimerRepository,
    private val sessionRepository: SessionRepository,
    private val elapsed: ElapsedTimeSource,
    private val clock: AppClock,
    @param:ApplicationScope private val scope: CoroutineScope,
) {

    private val _state = MutableStateFlow<TimerState?>(null)
    val state: StateFlow<TimerState?> = _state.asStateFlow()

    private val _projection = MutableStateFlow<TimerProjection?>(null)
    val projection: StateFlow<TimerProjection?> = _projection.asStateFlow()

    private val _events = MutableSharedFlow<TimerEvent>(extraBufferCapacity = 8)
    val events: SharedFlow<TimerEvent> = _events.asSharedFlow()

    /** Transitions are serialised: a double-tap on "pass" must not interleave. */
    private val mutex = Mutex()

    private var ticker: Job? = null
    private var warningAnnouncedForTurn = false
    private var exhaustionAnnouncedFor: Long? = null
    private var lastCheckpointElapsed = 0L

    /**
     * Reinstates a clock left behind by a previous process. Comes back paused; see
     * [TimerRepository.restore] for why.
     */
    suspend fun restoreIfPresent(): Boolean = mutex.withLock {
        if (_state.value != null) return@withLock true
        val restored = timerRepository.restore() ?: return@withLock false
        _state.value = restored
        refreshProjection()
        true
    }

    suspend fun setUp(
        gameId: Long,
        players: List<PlayerEntity>,
        config: TimerConfig,
    ) = mutex.withLock {
        val fresh = TimerEngine.create(
            gameId = gameId,
            players = players.map { TimerPlayer(it.id, it.name, it.colorHex) },
            config = config,
        )
        _state.value = fresh
        timerRepository.checkpoint(fresh)
        refreshProjection()
    }

    /**
     * Starts the clock, and with it the draft session.
     *
     * The draft exists from this moment precisely so that a process death mid-game leaves
     * something recoverable on the next launch instead of nothing.
     */
    suspend fun start() = mutex.withLock {
        val current = _state.value ?: return@withLock
        val sessionId = current.sessionId
            ?: sessionRepository.createDraft(
                gameId = current.gameId,
                players = current.seats.map {
                    ParticipantForm(
                        playerId = it.playerId,
                        playerName = it.name,
                        colorHex = it.colorHex,
                    )
                },
            )
        val started = TimerEngine
            .start(current.copy(sessionId = sessionId), elapsed.elapsedMillis(), clock.nowMillis())
        commitAndAnnounce(started)
        startForegroundService()
        startTicking()
    }

    suspend fun pause() = mutex.withLock {
        val current = _state.value ?: return@withLock
        commitAndAnnounce(TimerEngine.pause(current, elapsed.elapsedMillis()))
        stopTicking()
    }

    suspend fun resume() = mutex.withLock {
        val current = _state.value ?: return@withLock
        commitAndAnnounce(TimerEngine.resume(current, elapsed.elapsedMillis()))
        startForegroundService()
        startTicking()
    }

    suspend fun passTurn() = mutex.withLock {
        val current = _state.value ?: return@withLock
        val next = TimerEngine.passTurn(current, elapsed.elapsedMillis())
        warningAnnouncedForTurn = false
        exhaustionAnnouncedFor = null
        commitAndAnnounce(next)
        next.activeSeatOrNull?.let { _events.tryEmit(TimerEvent.TurnPassed(it.name)) }
    }

    suspend fun undoLastPass() = mutex.withLock {
        val current = _state.value ?: return@withLock
        commitAndAnnounce(TimerEngine.undo(current, elapsed.elapsedMillis()))
    }

    suspend fun reverseDirection() = mutex.withLock {
        val current = _state.value ?: return@withLock
        commitAndAnnounce(TimerEngine.reverseDirection(current))
    }

    suspend fun toggleSkip(seatIndex: Int) = mutex.withLock {
        val current = _state.value ?: return@withLock
        commitAndAnnounce(TimerEngine.toggleSkip(current, seatIndex, elapsed.elapsedMillis()))
    }

    suspend fun selectSeat(seatIndex: Int) = mutex.withLock {
        val current = _state.value ?: return@withLock
        commitAndAnnounce(TimerEngine.setActiveSeat(current, seatIndex, elapsed.elapsedMillis()))
    }

    /**
     * Stops the clock and hands back a session form pre-filled from it: the duration the
     * table actually played, minus time spent globally paused, with each player's turn
     * and bank totals attached.
     *
     * The draft is deliberately left in place until the user saves or discards the
     * session. Stopping the clock is not the same as throwing the evening away.
     */
    suspend fun stopAndSummarise(): TimerSummary? = mutex.withLock {
        val current = _state.value ?: return@withLock null
        val stopped = TimerEngine.stop(current, elapsed.elapsedMillis())
        _state.value = stopped
        timerRepository.checkpoint(stopped)
        refreshProjection()
        stopTicking()
        stopForegroundService()
        _events.tryEmit(TimerEvent.Stopped)

        val playedMs = TimerEngine.elapsedPlayMs(stopped, elapsed.elapsedMillis())
        val summary = TimerSummary(
            gameId = stopped.gameId,
            sessionId = stopped.sessionId,
            durationMinutes = ((playedMs / 60_000L).toInt()).coerceAtLeast(1),
            pausedMs = stopped.accumulatedPausedMs,
            startedAt = stopped.startedAtWallMs,
            endedAt = clock.nowMillis(),
            participants = stopped.seats.map { seat ->
                ParticipantForm(
                    playerId = seat.playerId,
                    playerName = seat.name,
                    colorHex = seat.colorHex,
                    // A count-up clock timed the table, so there is no per-player time to
                    // report. Writing zeroes would look like everyone sat there silently.
                    turnTimeMs = seat.totalTurnTimeMs.takeUnless { stopped.isCountUp },
                    bankTimeRemainingMs = seat.bankRemainingMs.takeUnless { stopped.isCountUp },
                )
            },
        )

        // The form is opened from the draft, not from this object, so the measurement
        // has to reach the row before the summary reaches the screen.
        summary.sessionId?.let { sessionId ->
            sessionRepository.recordTimerResult(
                sessionId = sessionId,
                durationMinutes = summary.durationMinutes,
                startedAt = summary.startedAt,
                endedAt = summary.endedAt,
                pausedMs = summary.pausedMs,
                participants = summary.participants,
            )
        }
        summary
    }

    /** Clears the clock and the draft it created. Used by "discard" after stopping. */
    suspend fun discard() = mutex.withLock {
        val current = _state.value
        current?.sessionId?.let { sessionRepository.discardDraft(it) }
        _state.value = null
        _projection.value = null
        timerRepository.clear()
        stopTicking()
        stopForegroundService()
    }

    /** Clears the clock but keeps the session, once it has been saved properly. */
    suspend fun release() = mutex.withLock {
        _state.value = null
        _projection.value = null
        timerRepository.clear()
        stopTicking()
        stopForegroundService()
    }

    private suspend fun commitAndAnnounce(next: TimerState) {
        _state.value = next
        timerRepository.checkpoint(next)
        lastCheckpointElapsed = elapsed.elapsedMillis()
        refreshProjection()
    }

    private fun refreshProjection() {
        val current = _state.value ?: run {
            _projection.value = null
            return
        }
        _projection.value = TimerEngine.project(current, elapsed.elapsedMillis())
    }

    /**
     * Redraws four times a second while running.
     *
     * The tick only *reads* the clock; it never advances it. Everything displayed is
     * derived from the monotonic anchor, so a tick arriving late, early, or not at all
     * changes nothing about how much time a player has actually used.
     */
    private fun startTicking() {
        if (ticker?.isActive == true) return
        ticker = scope.launch {
            while (isActive) {
                val current = _state.value
                if (current == null || !current.isRunning) break
                val projected = TimerEngine.project(current, elapsed.elapsedMillis())
                _projection.value = projected
                announceThresholds(projected)
                maybeCheckpoint()
                if (TimerEngine.shouldAutoPass(projected)) passTurn()
                delay(TICK_MS)
            }
        }
    }

    private fun stopTicking() {
        ticker?.cancel()
        ticker = null
    }

    private fun announceThresholds(projection: TimerProjection) {
        val active = projection.activePlayer ?: return
        if (projection.isWarning && !warningAnnouncedForTurn) {
            warningAnnouncedForTurn = true
            _events.tryEmit(TimerEvent.Warning(active.name))
        }
        if (active.bankRemainingMs <= 0 && exhaustionAnnouncedFor != active.playerId) {
            exhaustionAnnouncedFor = active.playerId
            _events.tryEmit(TimerEvent.BankExhausted(active.name))
        }
    }

    /**
     * A periodic checkpoint on top of the per-transition ones. A long turn can run for
     * many minutes; without this, a process kill in the middle of one would lose all of
     * it rather than a few seconds.
     *
     * Takes the same lock as every transition and re-reads the state inside it. The tick
     * that scheduled this may have observed a state that a pass has since replaced, and
     * writing the stale one back would undo the pass.
     */
    private suspend fun maybeCheckpoint() {
        val now = elapsed.elapsedMillis()
        if (now - lastCheckpointElapsed < CHECKPOINT_INTERVAL_MS) return
        mutex.withLock {
            val current = _state.value ?: return@withLock
            if (!current.isRunning) return@withLock
            lastCheckpointElapsed = now
            val committed = TimerEngine.commit(current, now)
            _state.value = committed
            timerRepository.checkpoint(committed)
        }
    }

    private fun startForegroundService() {
        val intent = Intent(context, TimerService::class.java).setAction(TimerService.ACTION_START)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(intent)
        } else {
            context.startService(intent)
        }
    }

    private fun stopForegroundService() {
        context.startService(
            Intent(context, TimerService::class.java).setAction(TimerService.ACTION_STOP),
        )
    }

    val isActive: Boolean
        get() = _state.value?.runState in setOf(TimerRunState.RUNNING, TimerRunState.PAUSED)

    private companion object {
        const val TICK_MS = 250L
        const val CHECKPOINT_INTERVAL_MS = 10_000L
    }
}

/** What the timer hands to the session form when it stops. */
data class TimerSummary(
    val gameId: Long,
    val sessionId: Long?,
    val durationMinutes: Int,
    val pausedMs: Long,
    val startedAt: Long?,
    val endedAt: Long?,
    val participants: List<ParticipantForm>,
)

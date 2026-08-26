package com.boardgamenation.tracker.domain.timer

import com.boardgamenation.tracker.domain.model.ActiveClock
import com.boardgamenation.tracker.domain.model.BankExhaustedBehaviour
import com.boardgamenation.tracker.domain.model.TimerRunState
import kotlinx.serialization.Serializable

/** Just enough of a player to seat them; keeps the engine free of database types. */
data class TimerPlayer(val id: Long, val name: String, val colorHex: String? = null)

@Serializable
data class TimerConfig(
    val turnMs: Long = 60_000,
    val bankMs: Long = 600_000,
    val warningMs: Long = 10_000,
    val soundEnabled: Boolean = true,
    val hapticsEnabled: Boolean = true,
    val bankExhausted: BankExhaustedBehaviour = BankExhaustedBehaviour.FLAG_AND_OVERTIME,
)

/**
 * One seat at the table.
 *
 * [turnRemainingMs] and [bankRemainingMs] are the values as of the last committed
 * transition, not live values. What the active seat has spent since then is derived from
 * the monotonic anchor in [TimerState]; see [TimerEngine.project].
 */
@Serializable
data class Seat(
    val playerId: Long,
    val name: String,
    val colorHex: String? = null,
    val turnRemainingMs: Long,
    val bankRemainingMs: Long,
    val totalTurnTimeMs: Long = 0,
    val turnsTaken: Int = 0,
    val timedOut: Boolean = false,
    val skipped: Boolean = false,
)

/**
 * The whole clock.
 *
 * The critical field is [anchorElapsedMs]: a reading from a *monotonic* source taken at
 * the last transition. Everything the display shows is that anchor subtracted from the
 * current monotonic reading. No value is ever decremented on a tick, so a dropped frame,
 * a doze, or a wall-clock change cannot make the numbers drift.
 */
@Serializable
data class TimerState(
    val gameId: Long,
    val sessionId: Long? = null,
    val config: TimerConfig = TimerConfig(),
    val seats: List<Seat> = emptyList(),
    val activeSeat: Int = 0,

    /** +1 for the usual direction, -1 after a reversal effect. */
    val direction: Int = 1,
    val runState: TimerRunState = TimerRunState.IDLE,

    /** Monotonic reading at the last transition. Meaningless while paused or idle. */
    val anchorElapsedMs: Long = 0,

    /** Wall clock at the start of play, used to fill in the session's started_at. */
    val startedAtWallMs: Long? = null,

    /** Time the table spent globally paused. Accrues to nobody. */
    val accumulatedPausedMs: Long = 0,

    /** Monotonic reading when the current pause began. */
    val pauseAnchorElapsedMs: Long? = null,

    /** One level of undo, for a misclicked pass. */
    val undoSnapshot: TimerState? = null,
) {
    val isRunning: Boolean get() = runState == TimerRunState.RUNNING
    val activeSeatOrNull: Seat? get() = seats.getOrNull(activeSeat)
}

/** What the UI and the notification draw, computed fresh for a given instant. */
data class TimerProjection(
    val state: TimerState,
    val seats: List<SeatDisplay>,
    val activeSeat: Int,
    val activeClock: ActiveClock,

    /** The number under the active player: turn remaining, or bank, or overtime. */
    val displayMs: Long,
    val isWarning: Boolean,
    val elapsedPlayMs: Long,
) {
    val activePlayer: SeatDisplay? get() = seats.getOrNull(activeSeat)
}

data class SeatDisplay(
    val seat: Seat,
    val turnRemainingMs: Long,
    val bankRemainingMs: Long,
    val totalTurnTimeMs: Long,
    val isActive: Boolean,
    val clock: ActiveClock,
) {
    val playerId: Long get() = seat.playerId
    val name: String get() = seat.name
    val colorHex: String? get() = seat.colorHex
    val turnsTaken: Int get() = seat.turnsTaken
    val timedOut: Boolean get() = seat.timedOut
    val skipped: Boolean get() = seat.skipped
}

/**
 * The timer state machine.
 *
 * Pure: every function takes the current state plus a monotonic reading and returns a
 * new state. Nothing here touches Android, a database, or a coroutine, which is what
 * makes the whole of the clock's behaviour testable by handing it a fake clock and
 * advancing it.
 */
object TimerEngine {

    /** Seats everyone with full clocks, in the order given. */
    fun create(
        gameId: Long,
        players: List<TimerPlayer>,
        config: TimerConfig,
    ): TimerState = TimerState(
        gameId = gameId,
        config = config,
        seats = players.map { player ->
            Seat(
                playerId = player.id,
                name = player.name,
                colorHex = player.colorHex,
                turnRemainingMs = config.turnMs,
                bankRemainingMs = config.bankMs,
            )
        },
    )

    fun start(state: TimerState, nowElapsedMs: Long, nowWallMs: Long): TimerState =
        state.copy(
            runState = TimerRunState.RUNNING,
            anchorElapsedMs = nowElapsedMs,
            startedAtWallMs = state.startedAtWallMs ?: nowWallMs,
            pauseAnchorElapsedMs = null,
        )

    /**
     * Folds everything spent since the anchor into the active seat and re-anchors.
     *
     * This is the only place a seat's stored numbers change, and it is called at every
     * transition, which is why a checkpoint written here is enough to survive a process
     * kill with at most the current turn lost.
     */
    fun commit(state: TimerState, nowElapsedMs: Long): TimerState {
        if (!state.isRunning) return state
        val seat = state.activeSeatOrNull ?: return state
        val spent = (nowElapsedMs - state.anchorElapsedMs).coerceAtLeast(0)
        if (spent == 0L) return state

        val (turnRemaining, bankRemaining) = spend(seat, spent)
        val updated = seat.copy(
            turnRemainingMs = turnRemaining,
            bankRemainingMs = bankRemaining,
            totalTurnTimeMs = seat.totalTurnTimeMs + spent,
            timedOut = seat.timedOut || bankRemaining <= 0,
        )
        return state.copy(
            seats = state.seats.replaceAt(state.activeSeat, updated),
            anchorElapsedMs = nowElapsedMs,
        )
    }

    /**
     * Applies a duration to a seat's two clocks.
     *
     * The turn clock absorbs the time first; only its overrun reaches the bank. That is
     * the whole rule of the dual timer: the bank is frozen until the turn allowance is
     * gone, and never replenished afterwards.
     */
    private fun spend(seat: Seat, spentMs: Long): Pair<Long, Long> {
        val turnAfter = seat.turnRemainingMs - spentMs
        if (turnAfter >= 0) return turnAfter to seat.bankRemainingMs
        // Whatever the turn clock could not cover comes out of the bank, which is
        // allowed to go negative so overtime stays on the record.
        val overflow = -turnAfter
        val alreadyOverdrawn = (-seat.turnRemainingMs).coerceAtLeast(0)
        val newlyFromBank = overflow - alreadyOverdrawn
        return turnAfter to (seat.bankRemainingMs - newlyFromBank)
    }

    /**
     * Ends the active player's turn and moves to the next seat.
     *
     * Their turn clock refills for next time; their bank carries over untouched. The
     * pre-pass state is kept as the undo snapshot, since passing by accident is the one
     * misclick that actually costs somebody time.
     */
    fun passTurn(state: TimerState, nowElapsedMs: Long): TimerState {
        val committed = commit(state, nowElapsedMs)
        val seat = committed.activeSeatOrNull ?: return committed

        val refreshed = seat.copy(
            turnRemainingMs = committed.config.turnMs,
            turnsTaken = seat.turnsTaken + 1,
        )
        val seats = committed.seats.replaceAt(committed.activeSeat, refreshed)
        val next = nextSeatIndex(seats, committed.activeSeat, committed.direction)

        return committed.copy(
            seats = seats,
            activeSeat = next,
            anchorElapsedMs = nowElapsedMs,
            // Snapshots never nest: one level of undo, as specified.
            undoSnapshot = state.copy(undoSnapshot = null),
        )
    }

    /** Restores the state from before the last pass, then re-anchors to now. */
    fun undo(state: TimerState, nowElapsedMs: Long): TimerState {
        val snapshot = state.undoSnapshot ?: return state
        return snapshot.copy(
            anchorElapsedMs = nowElapsedMs,
            runState = state.runState,
            accumulatedPausedMs = state.accumulatedPausedMs,
            pauseAnchorElapsedMs = state.pauseAnchorElapsedMs,
            undoSnapshot = null,
        )
    }

    fun pause(state: TimerState, nowElapsedMs: Long): TimerState {
        if (!state.isRunning) return state
        return commit(state, nowElapsedMs).copy(
            runState = TimerRunState.PAUSED,
            pauseAnchorElapsedMs = nowElapsedMs,
        )
    }

    /** Paused time is banked globally so it can be subtracted from session duration. */
    fun resume(state: TimerState, nowElapsedMs: Long): TimerState {
        if (state.runState != TimerRunState.PAUSED) return state
        val pausedFor = state.pauseAnchorElapsedMs
            ?.let { (nowElapsedMs - it).coerceAtLeast(0) }
            ?: 0
        return state.copy(
            runState = TimerRunState.RUNNING,
            anchorElapsedMs = nowElapsedMs,
            accumulatedPausedMs = state.accumulatedPausedMs + pausedFor,
            pauseAnchorElapsedMs = null,
        )
    }

    fun stop(state: TimerState, nowElapsedMs: Long): TimerState =
        commit(state, nowElapsedMs).copy(
            runState = TimerRunState.STOPPED,
            pauseAnchorElapsedMs = null,
        )

    /** For games with a direction-reversal effect. */
    fun reverseDirection(state: TimerState): TimerState = state.copy(direction = -state.direction)

    /** Takes a seat out of the rotation without removing their accumulated time. */
    fun toggleSkip(state: TimerState, seatIndex: Int, nowElapsedMs: Long): TimerState {
        val seat = state.seats.getOrNull(seatIndex) ?: return state
        val committed = commit(state, nowElapsedMs)
        val seats = committed.seats.replaceAt(seatIndex, seat.copy(skipped = !seat.skipped))
        // Skipping whoever is currently up has to move the turn along, or the clock would
        // keep running for somebody who is no longer playing.
        val activeSeat = if (seatIndex == committed.activeSeat && !seat.skipped) {
            nextSeatIndex(seats, committed.activeSeat, committed.direction)
        } else {
            committed.activeSeat
        }
        return committed.copy(seats = seats, activeSeat = activeSeat, anchorElapsedMs = nowElapsedMs)
    }

    fun setActiveSeat(state: TimerState, seatIndex: Int, nowElapsedMs: Long): TimerState =
        commit(state, nowElapsedMs).copy(activeSeat = seatIndex, anchorElapsedMs = nowElapsedMs)

    /**
     * What the clock reads at [nowElapsedMs], without changing anything.
     *
     * Called on every frame, so it must stay allocation-light and must never be the
     * thing that advances state.
     */
    fun project(state: TimerState, nowElapsedMs: Long): TimerProjection {
        val spent = if (state.isRunning) {
            (nowElapsedMs - state.anchorElapsedMs).coerceAtLeast(0)
        } else {
            0
        }

        val displays = state.seats.mapIndexed { index, seat ->
            val isActive = index == state.activeSeat
            val (turn, bank) = if (isActive && spent > 0) spend(seat, spent) else {
                seat.turnRemainingMs to seat.bankRemainingMs
            }
            SeatDisplay(
                seat = seat,
                turnRemainingMs = turn,
                bankRemainingMs = bank,
                totalTurnTimeMs = seat.totalTurnTimeMs + if (isActive) spent else 0,
                isActive = isActive,
                clock = clockFor(turn, bank),
            )
        }

        val active = displays.getOrNull(state.activeSeat)
        val clock = active?.clock ?: ActiveClock.TURN
        val display = when (clock) {
            ActiveClock.TURN -> active?.turnRemainingMs ?: 0
            ActiveClock.BANK, ActiveClock.OVERTIME -> active?.bankRemainingMs ?: 0
        }

        return TimerProjection(
            state = state,
            seats = displays,
            activeSeat = state.activeSeat,
            activeClock = clock,
            displayMs = display,
            isWarning = state.isRunning && display in 1..state.config.warningMs,
            elapsedPlayMs = elapsedPlayMs(state, nowElapsedMs),
        )
    }

    private fun clockFor(turnRemaining: Long, bankRemaining: Long): ActiveClock = when {
        turnRemaining > 0 -> ActiveClock.TURN
        bankRemaining > 0 -> ActiveClock.BANK
        else -> ActiveClock.OVERTIME
    }

    /** Total play time so far, with globally paused time excluded. */
    fun elapsedPlayMs(state: TimerState, nowElapsedMs: Long): Long {
        val played = state.seats.sumOf { it.totalTurnTimeMs }
        val live = if (state.isRunning) {
            (nowElapsedMs - state.anchorElapsedMs).coerceAtLeast(0)
        } else {
            0
        }
        return played + live
    }

    /**
     * Whether the active player's bank has run out and the configuration says to move
     * on. The default is to flag and keep counting: this is a friendly game aid, not a
     * tournament enforcer.
     */
    fun shouldAutoPass(projection: TimerProjection): Boolean =
        projection.state.config.bankExhausted == BankExhaustedBehaviour.AUTO_PASS &&
            projection.activeClock == ActiveClock.OVERTIME

    /**
     * The next seat that is actually playing. Falls back to the current seat when
     * everyone else is skipped, rather than looping forever.
     */
    fun nextSeatIndex(seats: List<Seat>, from: Int, direction: Int): Int {
        if (seats.isEmpty()) return 0
        val size = seats.size
        var candidate = from
        repeat(size) {
            candidate = ((candidate + direction) % size + size) % size
            if (!seats[candidate].skipped) return candidate
        }
        return from
    }

    private fun <T> List<T>.replaceAt(index: Int, value: T): List<T> =
        toMutableList().also { if (index in it.indices) it[index] = value }
}

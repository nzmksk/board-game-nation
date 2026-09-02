package com.boardgamenation.tracker.domain.timer

import com.boardgamenation.tracker.core.time.FakeElapsedTimeSource
import com.boardgamenation.tracker.domain.model.ActiveClock
import com.boardgamenation.tracker.domain.model.BankExhaustedBehaviour
import com.boardgamenation.tracker.domain.model.TimerMode
import com.boardgamenation.tracker.domain.model.TimerRunState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The clock's behaviour, driven by a hand-cranked monotonic source.
 *
 * Because the engine is pure and takes the elapsed reading as an argument, "three
 * minutes pass" is one line of test rather than a wait, and every assertion below is
 * about exact millisecond arithmetic rather than an approximation.
 */
class TimerEngineTest {

    private lateinit var clock: FakeElapsedTimeSource
    private lateinit var config: TimerConfig

    private val players = listOf(
        TimerPlayer(1, "Aina"),
        TimerPlayer(2, "Ben"),
        TimerPlayer(3, "Chandra"),
    )

    @Before
    fun setUp() {
        clock = FakeElapsedTimeSource(current = 1_000_000)
        config = TimerConfig(turnMs = 60_000, bankMs = 300_000, warningMs = 10_000)
    }

    private fun started(): TimerState =
        TimerEngine.start(TimerEngine.create(1, players, config), clock.elapsedMillis(), 0)

    @Test
    fun `everyone starts with a full turn clock and a full bank`() {
        val state = TimerEngine.create(1, players, config)
        assertEquals(3, state.seats.size)
        state.seats.forEach {
            assertEquals(60_000, it.turnRemainingMs)
            assertEquals(300_000, it.bankRemainingMs)
        }
        assertEquals(TimerRunState.IDLE, state.runState)
    }

    @Test
    fun `the turn clock drains and the bank stays frozen`() {
        var state = started()
        clock.advance(20_000)

        val projection = TimerEngine.project(state, clock.elapsedMillis())
        assertEquals(40_000, projection.displayMs)
        assertEquals(ActiveClock.TURN, projection.activeClock)
        // The whole point of the bank is that it is untouched until the turn is spent.
        assertEquals(300_000, projection.seats[0].bankRemainingMs)

        state = TimerEngine.commit(state, clock.elapsedMillis())
        assertEquals(300_000, state.seats[0].bankRemainingMs)
    }

    @Test
    fun `the bank only starts draining once the turn clock is gone`() {
        var state = started()
        clock.advance(90_000)

        val projection = TimerEngine.project(state, clock.elapsedMillis())
        assertEquals(ActiveClock.BANK, projection.activeClock)
        // 90s spent against a 60s turn allowance: exactly 30s should leave the bank.
        assertEquals(270_000, projection.seats[0].bankRemainingMs)
        assertEquals(270_000, projection.displayMs)

        state = TimerEngine.commit(state, clock.elapsedMillis())
        assertEquals(270_000, state.seats[0].bankRemainingMs)
        assertEquals(90_000, state.seats[0].totalTurnTimeMs)
    }

    @Test
    fun `a bank drained across several commits loses exactly the right amount`() {
        var state = started()
        // Committing mid-turn must not double-charge the overrun.
        repeat(9) {
            clock.advance(10_000)
            state = TimerEngine.commit(state, clock.elapsedMillis())
        }
        assertEquals(270_000, state.seats[0].bankRemainingMs)
        assertEquals(90_000, state.seats[0].totalTurnTimeMs)
    }

    @Test
    fun `passing refills the turn clock and carries the bank over`() {
        var state = started()
        clock.advance(90_000)
        state = TimerEngine.passTurn(state, clock.elapsedMillis())

        assertEquals(1, state.activeSeat)
        assertEquals(60_000, state.seats[0].turnRemainingMs)
        assertEquals(270_000, state.seats[0].bankRemainingMs)
        assertEquals(1, state.seats[0].turnsTaken)

        // The next player is untouched by what the previous one spent.
        assertEquals(60_000, state.seats[1].turnRemainingMs)
        assertEquals(300_000, state.seats[1].bankRemainingMs)
    }

    @Test
    fun `an emptied bank goes into overtime and flags the player`() {
        var state = started()
        clock.advance(400_000)
        state = TimerEngine.commit(state, clock.elapsedMillis())

        val projection = TimerEngine.project(state, clock.elapsedMillis())
        assertEquals(ActiveClock.OVERTIME, projection.activeClock)
        // 400s against 60s turn plus 300s bank leaves 40s of overtime on the record.
        assertEquals(-40_000, state.seats[0].bankRemainingMs)
        assertTrue(state.seats[0].timedOut)
    }

    @Test
    fun `auto pass is off by default and on only when configured`() {
        var state = started()
        clock.advance(400_000)
        val flagging = TimerEngine.project(state, clock.elapsedMillis())
        assertFalse(TimerEngine.shouldAutoPass(flagging))

        state = TimerEngine.create(
            1,
            players,
            config.copy(bankExhausted = BankExhaustedBehaviour.AUTO_PASS),
        )
        state = TimerEngine.start(state, clock.elapsedMillis(), 0)
        clock.advance(400_000)
        assertTrue(TimerEngine.shouldAutoPass(TimerEngine.project(state, clock.elapsedMillis())))
    }

    @Test
    fun `paused time accrues to nobody and is tracked separately`() {
        var state = started()
        clock.advance(20_000)
        state = TimerEngine.pause(state, clock.elapsedMillis())
        assertEquals(TimerRunState.PAUSED, state.runState)

        clock.advance(120_000)
        state = TimerEngine.resume(state, clock.elapsedMillis())

        // Two minutes of table talk cost the active player nothing.
        assertEquals(40_000, state.seats[0].turnRemainingMs)
        assertEquals(120_000, state.accumulatedPausedMs)

        clock.advance(10_000)
        assertEquals(30_000, TimerEngine.project(state, clock.elapsedMillis()).displayMs)
    }

    @Test
    fun `a paused clock does not move`() {
        var state = started()
        clock.advance(20_000)
        state = TimerEngine.pause(state, clock.elapsedMillis())
        val atPause = TimerEngine.project(state, clock.elapsedMillis()).displayMs

        clock.advance(600_000)
        assertEquals(atPause, TimerEngine.project(state, clock.elapsedMillis()).displayMs)
    }

    @Test
    fun `undo restores the state from before the last pass`() {
        var state = started()
        clock.advance(30_000)
        state = TimerEngine.passTurn(state, clock.elapsedMillis())
        assertEquals(1, state.activeSeat)
        assertNotNull(state.undoSnapshot)

        state = TimerEngine.undo(state, clock.elapsedMillis())
        assertEquals(0, state.activeSeat)
        // Undo is a single level, so the snapshot is consumed.
        assertNull(state.undoSnapshot)
    }

    @Test
    fun `undo snapshots never nest`() {
        var state = started()
        clock.advance(10_000)
        state = TimerEngine.passTurn(state, clock.elapsedMillis())
        clock.advance(10_000)
        state = TimerEngine.passTurn(state, clock.elapsedMillis())
        assertNull(state.undoSnapshot?.undoSnapshot)
    }

    @Test
    fun `reversing direction sends the turn back the other way`() {
        var state = started()
        clock.advance(5_000)
        state = TimerEngine.passTurn(state, clock.elapsedMillis())
        assertEquals(1, state.activeSeat)

        state = TimerEngine.reverseDirection(state)
        clock.advance(5_000)
        state = TimerEngine.passTurn(state, clock.elapsedMillis())
        assertEquals(0, state.activeSeat)
    }

    @Test
    fun `direction wraps around the table in both directions`() {
        var state = started()
        state = TimerEngine.reverseDirection(state)
        clock.advance(1_000)
        state = TimerEngine.passTurn(state, clock.elapsedMillis())
        assertEquals(2, state.activeSeat)
    }

    @Test
    fun `a skipped seat is passed over`() {
        var state = started()
        state = TimerEngine.toggleSkip(state, seatIndex = 1, nowElapsedMs = clock.elapsedMillis())
        clock.advance(5_000)
        state = TimerEngine.passTurn(state, clock.elapsedMillis())
        assertEquals(2, state.activeSeat)
    }

    @Test
    fun `skipping the active seat moves the turn along`() {
        var state = started()
        clock.advance(5_000)
        state = TimerEngine.toggleSkip(state, seatIndex = 0, nowElapsedMs = clock.elapsedMillis())
        assertEquals(1, state.activeSeat)
        assertTrue(state.seats[0].skipped)
    }

    @Test
    fun `nextSeatIndex stays put when everyone else is skipped`() {
        val seats = listOf(
            Seat(1, "Aina", null, 0, 0),
            Seat(2, "Ben", null, 0, 0, skipped = true),
        )
        assertEquals(0, TimerEngine.nextSeatIndex(seats, from = 0, direction = 1))
    }

    @Test
    fun `the warning fires only inside the threshold`() {
        val state = started()
        clock.advance(45_000)
        assertFalse(TimerEngine.project(state, clock.elapsedMillis()).isWarning)

        clock.advance(10_000)
        assertTrue(TimerEngine.project(state, clock.elapsedMillis()).isWarning)
    }

    @Test
    fun `elapsed play time sums every player's turns`() {
        var state = started()
        clock.advance(30_000)
        state = TimerEngine.passTurn(state, clock.elapsedMillis())
        clock.advance(45_000)
        state = TimerEngine.passTurn(state, clock.elapsedMillis())
        clock.advance(15_000)

        assertEquals(90_000, TimerEngine.elapsedPlayMs(state, clock.elapsedMillis()))
    }

    @Test
    fun `projection never mutates the state it is given`() {
        val state = started()
        clock.advance(90_000)
        TimerEngine.project(state, clock.elapsedMillis())
        TimerEngine.project(state, clock.elapsedMillis())

        // Reading the clock twice must not charge the player twice.
        assertEquals(60_000, state.seats[0].turnRemainingMs)
        assertEquals(300_000, state.seats[0].bankRemainingMs)
    }

    @Test
    fun `stopping records the totals each player actually used`() {
        var state = started()
        clock.advance(75_000)
        state = TimerEngine.passTurn(state, clock.elapsedMillis())
        clock.advance(30_000)
        state = TimerEngine.stop(state, clock.elapsedMillis())

        assertEquals(TimerRunState.STOPPED, state.runState)
        assertEquals(75_000, state.seats[0].totalTurnTimeMs)
        assertEquals(285_000, state.seats[0].bankRemainingMs)
        assertEquals(30_000, state.seats[1].totalTurnTimeMs)
        assertEquals(300_000, state.seats[1].bankRemainingMs)
    }

    /**
     * The elapsed source is monotonic, so a value from before the anchor can only mean a
     * bug or a reboot. Either way the clock must not run backwards.
     */
    @Test
    fun `time never runs backwards`() {
        val state = started()
        val projection = TimerEngine.project(state, clock.elapsedMillis() - 60_000)
        assertEquals(60_000, projection.displayMs)
    }

    // --- count-up -------------------------------------------------------------------

    private fun countUpConfig() = TimerConfig(mode = TimerMode.COUNT_UP)

    private fun countingUp(seated: List<TimerPlayer> = players): TimerState =
        TimerEngine.start(
            TimerEngine.create(1, seated, countUpConfig()),
            clock.elapsedMillis(),
            0,
        )

    @Test
    fun `a count-up clock counts the table up from zero`() {
        val state = countingUp()

        assertEquals(0, TimerEngine.project(state, clock.elapsedMillis()).displayMs)

        clock.advance(90_000)
        val projection = TimerEngine.project(state, clock.elapsedMillis())

        assertEquals(90_000, projection.displayMs)
        assertEquals(90_000, projection.elapsedPlayMs)
    }

    @Test
    fun `counting up never warns and never leaves anybody up`() {
        val state = countingUp()
        clock.advance(10_000_000)

        val projection = TimerEngine.project(state, clock.elapsedMillis())

        assertFalse("nothing is running out", projection.isWarning)
        assertTrue("nobody is up on a table clock", projection.seats.none { it.isActive })
        assertFalse(TimerEngine.shouldAutoPass(projection))
    }

    @Test
    fun `count-up time accrues to the table and not to a seat`() {
        var state = countingUp()
        clock.advance(120_000)

        state = TimerEngine.commit(state, clock.elapsedMillis())

        assertEquals(120_000, state.tableTimeMs)
        state.seats.forEach { seat ->
            assertEquals("no seat is charged for table time", 0, seat.totalTurnTimeMs)
            assertEquals(config.turnMs, seat.turnRemainingMs)
        }
    }

    @Test
    fun `a paused count-up clock holds its reading`() {
        var state = countingUp()
        clock.advance(60_000)

        state = TimerEngine.pause(state, clock.elapsedMillis())
        clock.advance(300_000)

        assertEquals(60_000, TimerEngine.project(state, clock.elapsedMillis()).displayMs)

        state = TimerEngine.resume(state, clock.elapsedMillis())
        clock.advance(30_000)

        assertEquals(90_000, TimerEngine.project(state, clock.elapsedMillis()).displayMs)
        assertEquals("the pause is banked, not played", 300_000, state.accumulatedPausedMs)
    }

    @Test
    fun `stopping a count-up clock records the whole game`() {
        var state = countingUp()
        clock.advance(45 * 60_000L)

        state = TimerEngine.stop(state, clock.elapsedMillis())

        assertEquals(TimerRunState.STOPPED, state.runState)
        assertEquals(45 * 60_000L, TimerEngine.elapsedPlayMs(state, clock.elapsedMillis()))
    }

    @Test
    fun `passing a turn does nothing on a count-up clock`() {
        var state = countingUp()
        clock.advance(60_000)

        state = TimerEngine.passTurn(state, clock.elapsedMillis())

        assertEquals(0, state.activeSeat)
        assertEquals(0, state.seats[0].turnsTaken)
        assertNull(state.undoSnapshot)
        assertEquals(60_000, TimerEngine.project(state, clock.elapsedMillis()).displayMs)
    }

    @Test
    fun `a solo count-up clock works with one player at the table`() {
        val state = countingUp(listOf(players.first()))
        clock.advance(25 * 60_000L)

        assertEquals(25 * 60_000L, TimerEngine.project(state, clock.elapsedMillis()).displayMs)
    }
}

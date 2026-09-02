package com.boardgamenation.tracker.data.db

import com.boardgamenation.tracker.data.repository.StatsRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Whether going first wins games.
 *
 * The rate on its own is not the finding -- 50% is unremarkable at a table of two and
 * extraordinary at a table of five -- so most of what is asserted here is which plays
 * the query is willing to count, and the chance baseline it holds the rate against.
 */
@RunWith(RobolectricTestRunner::class)
class FirstPlayerWinRateTest {

    private lateinit var db: AppDatabase
    private lateinit var repository: StatsRepository
    private var gameId = 0L
    private val players = mutableListOf<Long>()

    @Before
    fun setUp() = runTest {
        db = DatabaseTestFixture.database()
        repository = StatsRepository(db.statsDao(), DatabaseTestFixture.clock)
        gameId = db.gameDao().insert(DatabaseTestFixture.game("Catan"))
        repeat(5) { index ->
            players += db.playerDao().insert(
                DatabaseTestFixture.player("P${index + 1}", isSelf = index == 0),
            )
        }
    }

    @After
    fun tearDown() = db.close()

    /**
     * One play seating [seats] players, with [winners] of them winning.
     *
     * The player in seat 1 is the starting player unless [orderRecorded] is false, in
     * which case nobody holds a seat -- the shape of a play logged without an order.
     * Winners are counted from the front, so [firstWon] decides whether the first seat
     * is among them.
     */
    private suspend fun play(
        seats: Int = 2,
        firstWon: Boolean = true,
        winners: Int = 1,
        orderRecorded: Boolean = true,
        game: Long = gameId,
        isCooperative: Boolean = false,
        isIncomplete: Boolean = false,
        isDraft: Boolean = false,
    ) {
        val sessionId = db.sessionDao().insertSession(
            DatabaseTestFixture.session(
                game,
                playedOn = "2026-02-01",
                playerCount = seats,
                isCooperative = isCooperative,
                isIncomplete = isIncomplete,
                isDraft = isDraft,
            ),
        )
        // The winners are seated at the back when the first player lost, so the count
        // of winners stays what the caller asked for either way.
        val winningSeats = if (firstWon) (0 until winners) else (seats - winners until seats)
        db.sessionDao().insertParticipants(
            (0 until seats).map { seat ->
                DatabaseTestFixture.participant(
                    sessionId,
                    players[seat],
                    isWinner = seat in winningSeats,
                    turnOrder = if (orderRecorded) seat + 1 else null,
                )
            },
        )
    }

    private suspend fun record(game: Long? = null) = repository.firstPlayerRecord(game).first()

    @Test
    fun `the record counts the plays where the starting player won`() = runTest {
        play(firstWon = true)
        play(firstWon = true)
        play(firstWon = false)
        play(firstWon = false)

        val record = record()
        assertEquals(4, record.plays)
        assertEquals(2, record.wins)
        assertEquals(50, record.winPercent)
    }

    @Test
    fun `chance is what the first seat would win if the seat meant nothing`() = runTest {
        play(seats = 4, firstWon = true)
        play(seats = 4, firstWon = false)

        // One winner of four, twice over: a quarter of the plays, by chance alone.
        assertEquals(25, record().expectedPercent)
    }

    @Test
    fun `tables of different sizes each contribute their own chance`() = runTest {
        play(seats = 2, firstWon = true)
        play(seats = 5, firstWon = true)

        // Half and a fifth, averaged -- not one winner in seven.
        assertEquals(35, record().expectedPercent)
    }

    @Test
    fun `a shared win counts as a win and raises what chance expects`() = runTest {
        play(seats = 4, firstWon = true, winners = 2)

        val record = record()
        assertEquals(1, record.wins)
        // Two winners of four: the first seat had an even chance of being one of them.
        assertEquals(50, record.expectedPercent)
    }

    @Test
    fun `the edge is how far past chance the first seat is`() = runTest {
        play(seats = 4, firstWon = true)
        play(seats = 4, firstWon = true)
        play(seats = 4, firstWon = false)
        play(seats = 4, firstWon = false)

        // Half the plays against a quarter expected.
        assertEquals(25, record().edgePoints)
    }

    @Test
    fun `going first can be worth less than nothing`() = runTest {
        play(seats = 4, firstWon = false)
        play(seats = 4, firstWon = false)
        play(seats = 4, firstWon = false)
        play(seats = 4, firstWon = false)

        assertEquals(-25, record().edgePoints)
    }

    @Test
    fun `a play with no order recorded is not evidence either way`() = runTest {
        play(orderRecorded = false)
        play(orderRecorded = false)

        val record = record()
        assertEquals(0, record.plays)
        assertNull(record.expectedWinRate)
    }

    @Test
    fun `a co-op play says nothing about who started`() = runTest {
        // The table wins together, so the first player wins whenever anybody does.
        play(isCooperative = true, firstWon = true, winners = 2)

        assertEquals(0, record().plays)
    }

    @Test
    fun `an abandoned play has no result to count`() = runTest {
        play(isIncomplete = true)

        assertEquals(0, record().plays)
    }

    @Test
    fun `a draft is not a play yet`() = runTest {
        play(isDraft = true)

        assertEquals(0, record().plays)
    }

    @Test
    fun `a solo play would be a guaranteed win and is left out`() = runTest {
        play(seats = 1, firstWon = true)

        assertEquals(0, record().plays)
    }

    @Test
    fun `a play nobody won would drag the rate down for no reason`() = runTest {
        play(seats = 2, firstWon = false, winners = 0)

        assertEquals(0, record().plays)
    }

    @Test
    fun `the advantage can be read for one game rather than the shelf`() = runTest {
        val root = db.gameDao().insert(DatabaseTestFixture.game("Root"))
        play(game = gameId, firstWon = true)
        play(game = root, firstWon = false)
        play(game = root, firstWon = false)

        assertEquals(3, record().plays)
        assertEquals(1, record(gameId).plays)
        assertEquals(100, record(gameId).winPercent)
        assertEquals(0, record(root).winPercent)
    }

    @Test
    fun `an empty history reports nothing rather than a zero rate`() = runTest {
        val record = record()
        assertEquals(0, record.plays)
        assertEquals(0, record.wins)
        assertNull(record.expectedPercent)
        assertNull(record.edgePoints)
    }
}

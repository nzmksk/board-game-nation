package com.boardgamenation.tracker.data.db

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * How the head-to-head list is ordered.
 *
 * The record is the sort key, not the sample size: the opponents beaten most often come
 * first, and among equal win counts the one who has won back the least. How many plays
 * the two have shared does not enter into it.
 */
@RunWith(RobolectricTestRunner::class)
class HeadToHeadTest {

    private lateinit var db: AppDatabase
    private var gameId = 0L
    private var me = 0L

    @Before
    fun setUp() = runTest {
        db = DatabaseTestFixture.database()
        gameId = db.gameDao().insert(DatabaseTestFixture.game("Catan"))
        me = db.playerDao().insert(DatabaseTestFixture.player("Hafiz", isSelf = true))
    }

    @After
    fun tearDown() = db.close()

    private suspend fun opponent(name: String) =
        db.playerDao().insert(DatabaseTestFixture.player(name))

    /** Plays out a record of [wins] to [losses] between the owner and one opponent. */
    private suspend fun record(opponentId: Long, wins: Int, losses: Int) {
        repeat(wins + losses) { index ->
            val sessionId = db.sessionDao().insertSession(
                DatabaseTestFixture.session(gameId, playedOn = "2026-02-01"),
            )
            val selfWon = index < wins
            db.sessionDao().insertParticipants(
                listOf(
                    DatabaseTestFixture.participant(sessionId, me, isWinner = selfWon),
                    DatabaseTestFixture.participant(sessionId, opponentId, isWinner = !selfWon),
                ),
            )
        }
    }

    private suspend fun records(): List<String> =
        db.statsDao().observeHeadToHead().first().map { "${it.selfWins}-${it.opponentWins}" }

    @Test
    fun `records rank by wins first, then by fewest losses`() = runTest {
        // Inserted in an order that is not the answer, so passing means the SQL sorted it.
        record(opponent("Alia"), wins = 5, losses = 6)
        record(opponent("Ben"), wins = 10, losses = 13)
        record(opponent("Cara"), wins = 10, losses = 0)
        record(opponent("Danish"), wins = 5, losses = 3)
        record(opponent("Elena"), wins = 10, losses = 5)

        assertEquals(listOf("10-0", "10-5", "10-13", "5-3", "5-6"), records())
    }

    @Test
    fun `a thin winning record still outranks a thick losing one`() = runTest {
        record(opponent("Regular"), wins = 2, losses = 20)
        record(opponent("Rare"), wins = 3, losses = 0)

        val names = db.statsDao().observeHeadToHead().first().map { it.opponentName }
        assertEquals(listOf("Rare", "Regular"), names)
    }

    @Test
    fun `identical records fall back to name`() = runTest {
        record(opponent("Zaki"), wins = 4, losses = 2)
        record(opponent("Amir"), wins = 4, losses = 2)

        val names = db.statsDao().observeHeadToHead().first().map { it.opponentName }
        assertEquals(listOf("Amir", "Zaki"), names)
    }
}

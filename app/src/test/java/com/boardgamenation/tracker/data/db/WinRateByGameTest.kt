package com.boardgamenation.tracker.data.db

import com.boardgamenation.tracker.data.repository.StatsRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * What the player profile lists under "win rate by game".
 *
 * Every game with a competitive play appears, whatever the sample size, and each row
 * carries the record it was computed from -- the screen has no other way to tell a
 * lucky first play from a game somebody actually owns.
 */
@RunWith(RobolectricTestRunner::class)
class WinRateByGameTest {

    private lateinit var db: AppDatabase
    private lateinit var repository: StatsRepository
    private var me = 0L
    private var ben = 0L

    @Before
    fun setUp() = runTest {
        db = DatabaseTestFixture.database()
        repository = StatsRepository(db.statsDao(), DatabaseTestFixture.clock)
        me = db.playerDao().insert(DatabaseTestFixture.player("Hafiz", isSelf = true))
        ben = db.playerDao().insert(DatabaseTestFixture.player("Ben"))
    }

    @After
    fun tearDown() = db.close()

    /**
     * Plays [wins] winning and [losses] losing sessions of a game, creating it first.
     * A co-op or draft session is still a session against the same opponent; those
     * flags are what the query is expected to filter on.
     */
    private suspend fun record(
        title: String,
        wins: Int,
        losses: Int,
        isCooperative: Boolean = false,
        isDraft: Boolean = false,
    ): Long {
        val gameId = db.gameDao().insert(DatabaseTestFixture.game(title))
        repeat(wins + losses) { index ->
            val sessionId = db.sessionDao().insertSession(
                DatabaseTestFixture.session(
                    gameId,
                    playedOn = "2026-02-01",
                    isCooperative = isCooperative,
                    isDraft = isDraft,
                ),
            )
            val won = index < wins
            db.sessionDao().insertParticipants(
                listOf(
                    DatabaseTestFixture.participant(sessionId, me, isWinner = won),
                    DatabaseTestFixture.participant(sessionId, ben, isWinner = !won),
                ),
            )
        }
        return gameId
    }

    private suspend fun rows() = repository.winRateByGame(me).first()

    private suspend fun titles() = rows().map { it.title }

    @Test
    fun `a game played once appears alongside the games played often`() = runTest {
        record("Catan", wins = 3, losses = 3)
        record("Azul", wins = 0, losses = 1)
        record("Wingspan", wins = 1, losses = 0)

        assertEquals(setOf("Catan", "Azul", "Wingspan"), titles().toSet())
    }

    @Test
    fun `each row carries the record the rate came from`() = runTest {
        record("Catan", wins = 3, losses = 1)

        val row = rows().single()
        assertEquals(4, row.plays)
        assertEquals(3, row.wins)
        assertEquals(75.0, row.winRate, 0.001)
    }

    @Test
    fun `rates rank high to low`() = runTest {
        record("Azul", wins = 1, losses = 3)
        record("Catan", wins = 3, losses = 1)
        record("Root", wins = 1, losses = 1)

        assertEquals(listOf("Catan", "Root", "Azul"), titles())
    }

    @Test
    fun `an equal rate puts the bigger sample first`() = runTest {
        // Both are unbeaten. Only the play count separates them, and one win is a
        // thinner claim to 100% than five.
        record("Beginner's Luck", wins = 1, losses = 0)
        record("Old Faithful", wins = 5, losses = 0)

        assertEquals(listOf("Old Faithful", "Beginner's Luck"), titles())
    }

    @Test
    fun `an identical record falls back to the title`() = runTest {
        record("Zoo", wins = 2, losses = 1)
        record("Ark", wins = 2, losses = 1)

        assertEquals(listOf("Ark", "Zoo"), titles())
    }

    @Test
    fun `co-op plays are no part of a player's record`() = runTest {
        record("Pandemic", wins = 4, losses = 0, isCooperative = true)
        record("Catan", wins = 1, losses = 1)

        assertEquals(listOf("Catan"), titles())
    }

    @Test
    fun `a draft is not a play yet`() = runTest {
        record("Catan", wins = 2, losses = 0, isDraft = true)

        assertEquals(emptyList<String>(), titles())
    }

    @Test
    fun `the list is the player's own, not the table's`() = runTest {
        record("Catan", wins = 3, losses = 1)

        val theirs = repository.winRateByGame(ben).first().single()
        assertEquals(1, theirs.wins)
        assertEquals(25.0, theirs.winRate, 0.001)
    }
}

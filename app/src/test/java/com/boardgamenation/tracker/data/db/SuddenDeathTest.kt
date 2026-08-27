package com.boardgamenation.tracker.data.db

import com.boardgamenation.tracker.data.repository.SessionRepository
import com.boardgamenation.tracker.domain.model.CoopOutcome
import com.boardgamenation.tracker.domain.model.ParticipantForm
import com.boardgamenation.tracker.domain.model.ScoringMode
import com.boardgamenation.tracker.domain.model.SessionEndCondition
import com.boardgamenation.tracker.domain.model.SessionForm
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.LocalDate

/**
 * Plays that end the moment a condition is met, and the rule that recording one must not
 * disturb how the game is normally scored.
 *
 * 7 Wonders Duel is the case this was built for: military or scientific supremacy stops
 * the game before anyone counts a victory point, so there are no final scores to rank by,
 * but the play absolutely finished and absolutely has a winner.
 */
@RunWith(RobolectricTestRunner::class)
class SuddenDeathTest {

    private lateinit var db: AppDatabase
    private lateinit var repository: SessionRepository
    private var gameId = 0L
    private var me = 0L
    private var opponent = 0L

    @Before
    fun setUp() = runTest {
        db = DatabaseTestFixture.database()
        repository = SessionRepository(
            sessionDao = db.sessionDao(),
            gameDao = db.gameDao(),
            playerDao = db.playerDao(),
            clock = DatabaseTestFixture.clock,
        )
        gameId = db.gameDao().insert(
            DatabaseTestFixture.game("7 Wonders Duel").copy(
                scoringMode = ScoringMode.RANKED_SCORES,
                suddenDeathPossible = true,
            ),
        )
        me = db.playerDao().insert(DatabaseTestFixture.player("Hafiz", isSelf = true))
        opponent = db.playerDao().insert(DatabaseTestFixture.player("Aisyah"))
    }

    @After
    fun tearDown() = db.close()

    private fun form(
        players: List<Pair<Long, Double?>>,
        endCondition: SessionEndCondition? = null,
        endReason: String? = null,
        mode: ScoringMode = ScoringMode.RANKED_SCORES,
    ) = SessionForm(
        gameId = gameId,
        playedOn = LocalDate.parse("2026-02-01"),
        durationMinutes = 30,
        scoringMode = mode,
        endCondition = endCondition,
        endReason = endReason,
        participants = players.map { (playerId, score) ->
            ParticipantForm(playerId = playerId, playerName = "p$playerId", score = score)
        },
    )

    @Test
    fun `a sudden-death play ranks by order even with no scores at all`() = runTest {
        val id = repository.save(
            form(
                players = listOf(me to null, opponent to null),
                endCondition = SessionEndCondition.SUDDEN_DEATH,
                endReason = "Military supremacy",
            ),
        )

        val rows = db.sessionDao().getParticipants(id).associateBy { it.playerId }

        assertEquals(1, rows[me]!!.placement)
        assertEquals(2, rows[opponent]!!.placement)
        assertTrue(rows[me]!!.isWinner)
        assertFalse(rows[opponent]!!.isWinner)
        assertNull("no final scoring happened", rows[me]!!.score)
    }

    /**
     * Without the end condition this same form would derive nothing: every score is null,
     * so `PlacementCalculator.derive` correctly refuses to rank anyone. That contrast is
     * the whole reason the field exists.
     */
    @Test
    fun `the same scoreless form without a sudden death produces no winner`() = runTest {
        val id = repository.save(form(players = listOf(me to null, opponent to null)))
        val rows = db.sessionDao().getParticipants(id)

        assertTrue("nothing to rank by", rows.all { it.placement == null })
        assertTrue(rows.none { it.isWinner })
    }

    @Test
    fun `a partial score entered on a sudden-death play is kept but does not decide it`() =
        runTest {
            // The player placed second has the higher number: the game ended before that
            // number meant anything.
            val id = repository.save(
                form(
                    players = listOf(me to 12.0, opponent to 40.0),
                    endCondition = SessionEndCondition.SUDDEN_DEATH,
                    endReason = "Scientific supremacy",
                ),
            )

            val rows = db.sessionDao().getParticipants(id).associateBy { it.playerId }

            assertEquals(1, rows[me]!!.placement)
            assertTrue(rows[me]!!.isWinner)
            assertEquals(12.0, rows[me]!!.score!!, 0.001)
            assertFalse("the higher score did not win", rows[opponent]!!.isWinner)
        }

    @Test
    fun `the end condition and its reason are persisted`() = runTest {
        val id = repository.save(
            form(
                players = listOf(me to null, opponent to null),
                endCondition = SessionEndCondition.SUDDEN_DEATH,
                endReason = "Military supremacy",
            ),
        )

        val session = db.sessionDao().getSession(id)!!

        assertEquals(SessionEndCondition.SUDDEN_DEATH, session.endCondition)
        assertEquals("Military supremacy", session.endReason)
        assertFalse("a sudden death is not an abandoned game", session.isIncomplete)
    }

    /** A stale reason must not survive on a play that was scored normally after all. */
    @Test
    fun `a reason is not stored when the play ended in final scoring`() = runTest {
        val id = repository.save(
            form(
                players = listOf(me to 20.0, opponent to 15.0),
                endCondition = null,
                endReason = "Military supremacy",
            ),
        )

        assertNull(db.sessionDao().getSession(id)!!.endReason)
    }

    @Test
    fun `previously used reasons come back for the next play of that game`() = runTest {
        repository.save(
            form(
                players = listOf(me to null, opponent to null),
                endCondition = SessionEndCondition.SUDDEN_DEATH,
                endReason = "Military supremacy",
            ),
        )

        assertEquals(
            listOf("Military supremacy"),
            repository.observeEndReasonsFor(gameId).first(),
        )
    }

    // --- the game's own scoring must survive being played -------------------------

    @Test
    fun `saving a sudden-death play leaves the game's scoring mode alone`() = runTest {
        repository.save(
            form(
                players = listOf(me to null, opponent to null),
                endCondition = SessionEndCondition.SUDDEN_DEATH,
                endReason = "Military supremacy",
            ),
        )

        assertEquals(ScoringMode.RANKED_SCORES, db.gameDao().getGame(gameId)!!.scoringMode)
    }

    /**
     * Quick log asks who won rather than for scores. It used to say so by forcing the
     * form's scoring mode to NONE, which the save path then wrote back onto the game --
     * so one quick log permanently stripped the score fields off a scored game.
     */
    @Test
    fun `a quick log leaves the game's scoring mode alone`() = runTest {
        repository.save(
            form(players = listOf(me to null, opponent to null)).copy(
                derivePlacements = false,
                participants = listOf(
                    ParticipantForm(playerId = me, playerName = "me", isWinner = true),
                    ParticipantForm(playerId = opponent, playerName = "them"),
                ),
            ),
        )

        assertEquals(ScoringMode.RANKED_SCORES, db.gameDao().getGame(gameId)!!.scoringMode)
    }

    @Test
    fun `a quick log keeps the winner the caller chose`() = runTest {
        val id = repository.save(
            form(players = listOf(me to null, opponent to null)).copy(
                derivePlacements = false,
                participants = listOf(
                    ParticipantForm(playerId = me, playerName = "me", isWinner = true),
                    ParticipantForm(playerId = opponent, playerName = "them"),
                ),
            ),
        )

        val rows = db.sessionDao().getParticipants(id).associateBy { it.playerId }

        assertTrue(rows[me]!!.isWinner)
        assertFalse(rows[opponent]!!.isWinner)
    }

    /**
     * Co-op still derives, because the table's single outcome is what turns into a flag
     * on every player. Quick log relies on that to record a loss.
     */
    @Test
    fun `a co-op loss marks nobody a winner`() = runTest {
        val coopGame = db.gameDao().insert(
            DatabaseTestFixture.game("Pandemic").copy(scoringMode = ScoringMode.COOPERATIVE),
        )
        val id = repository.save(
            SessionForm(
                gameId = coopGame,
                playedOn = LocalDate.parse("2026-02-02"),
                durationMinutes = 45,
                scoringMode = ScoringMode.COOPERATIVE,
                coopOutcome = CoopOutcome.LOSS,
                participants = listOf(
                    ParticipantForm(playerId = me, playerName = "me"),
                    ParticipantForm(playerId = opponent, playerName = "them"),
                ),
            ),
        )

        assertTrue(db.sessionDao().getParticipants(id).none { it.isWinner })
    }
}

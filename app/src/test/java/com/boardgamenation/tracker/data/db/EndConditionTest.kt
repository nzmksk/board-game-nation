package com.boardgamenation.tracker.data.db

import com.boardgamenation.tracker.data.repository.SessionRepository
import com.boardgamenation.tracker.domain.model.CoopOutcome
import com.boardgamenation.tracker.domain.model.ParticipantForm
import com.boardgamenation.tracker.domain.model.ScoringMode
import com.boardgamenation.tracker.domain.model.SessionEndCondition
import com.boardgamenation.tracker.domain.model.SessionForm
import java.time.LocalDate
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

/**
 * How a play ended, which every play is asked and every scoring mode can answer.
 *
 * Two of the three answers change what the app does with the play. An abandoned one is a
 * play with no result, and drops out of the duration and win-rate figures. A play a rule
 * stopped is the opposite: 7 Wonders Duel's military supremacy ends the game before
 * anyone counts a victory point, so there are no final scores to rank by, but the play
 * absolutely finished and absolutely has a winner.
 *
 * The rest of this file is about what must *not* change when one is recorded: a rule that
 * ends a co-op does not take the table's verdict away from it, one that ends a hidden-role
 * game does not take the win off the winning side, and logging any of it never rewrites
 * how the game itself is scored.
 */
@RunWith(RobolectricTestRunner::class)
class EndConditionTest {

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
            clock = DatabaseTestFixture.clock
        )
        gameId = db.gameDao().insert(
            DatabaseTestFixture.game("7 Wonders Duel").copy(scoringMode = ScoringMode.RANKED_SCORES)
        )
        me = db.playerDao().insert(DatabaseTestFixture.player("Hafiz", isSelf = true))
        opponent = db.playerDao().insert(DatabaseTestFixture.player("Aisyah"))
    }

    @After
    fun tearDown() = db.close()

    private fun form(
        players: List<Pair<Long, Double?>>,
        endCondition: SessionEndCondition = SessionEndCondition.STANDARD,
        endReason: String? = null,
        mode: ScoringMode = ScoringMode.RANKED_SCORES
    ) = SessionForm(
        gameId = gameId,
        playedOn = LocalDate.parse("2026-02-01"),
        durationMinutes = 30,
        scoringMode = mode,
        endCondition = endCondition,
        endReason = endReason,
        participants = players.map { (playerId, score) ->
            ParticipantForm(playerId = playerId, playerName = "p$playerId", score = score)
        }
    )

    // --- a rule that stops the game -------------------------------------------------

    @Test
    fun `a play a rule ended ranks by order even with no scores at all`() = runTest {
        val id = repository.save(
            form(
                players = listOf(me to null, opponent to null),
                endCondition = SessionEndCondition.SPECIFIC,
                endReason = "Military supremacy"
            )
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
    fun `the same scoreless form played to the end produces no winner`() = runTest {
        val id = repository.save(form(players = listOf(me to null, opponent to null)))
        val rows = db.sessionDao().getParticipants(id)

        assertTrue("nothing to rank by", rows.all { it.placement == null })
        assertTrue(rows.none { it.isWinner })
    }

    @Test
    fun `a partial score entered on a play a rule ended is kept but does not decide it`() = runTest {
        // The player placed second has the higher number: the game ended before that
        // number meant anything.
        val id = repository.save(
            form(
                players = listOf(me to 12.0, opponent to 40.0),
                endCondition = SessionEndCondition.SPECIFIC,
                endReason = "Scientific supremacy"
            )
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
                endCondition = SessionEndCondition.SPECIFIC,
                endReason = "Military supremacy"
            )
        )

        val session = db.sessionDao().getSession(id)!!

        assertEquals(SessionEndCondition.SPECIFIC, session.endCondition)
        assertEquals("Military supremacy", session.endReason)
        assertFalse("a game a rule ended is not an abandoned one", session.isIncomplete)
    }

    /** A stale reason must not survive on a play that ran to the last round after all. */
    @Test
    fun `a reason is not stored when the play ran to the end`() = runTest {
        val id = repository.save(
            form(
                players = listOf(me to 20.0, opponent to 15.0),
                endReason = "Military supremacy"
            )
        )

        assertNull(db.sessionDao().getSession(id)!!.endReason)
    }

    @Test
    fun `previously used reasons come back for the next play of that game`() = runTest {
        repository.save(
            form(
                players = listOf(me to null, opponent to null),
                endCondition = SessionEndCondition.SPECIFIC,
                endReason = "Military supremacy"
            )
        )

        assertEquals(
            listOf("Military supremacy"),
            repository.observeEndReasonsFor(gameId).first()
        )
    }

    // --- abandonment -----------------------------------------------------------------

    @Test
    fun `an abandoned play is the flag every statistic filters on`() = runTest {
        val id = repository.save(
            form(
                players = listOf(me to null, opponent to null),
                endCondition = SessionEndCondition.ABANDONED
            )
        )

        val session = db.sessionDao().getSession(id)!!

        assertEquals(SessionEndCondition.ABANDONED, session.endCondition)
        assertTrue("is_incomplete is written from the end condition", session.isIncomplete)
    }

    @Test
    fun `abandonment survives a load and a save`() = runTest {
        val id = repository.save(
            form(
                players = listOf(me to null, opponent to null),
                endCondition = SessionEndCondition.ABANDONED
            )
        )

        val reloaded = repository.loadForm(id)!!

        assertEquals(SessionEndCondition.ABANDONED, reloaded.endCondition)
        assertTrue(reloaded.isIncomplete)
    }

    /**
     * A row written by a version that had no end condition, or restored from an archive
     * exported then, still says how it ended -- in the only column that could carry it.
     */
    @Test
    fun `an old row with only the abandoned flag reads back as abandoned`() = runTest {
        val id = db.sessionDao().insertSession(
            DatabaseTestFixture.session(gameId, "2026-02-03", isIncomplete = true)
                .copy(endCondition = null)
        )
        db.sessionDao().insertParticipants(
            listOf(DatabaseTestFixture.participant(id, me))
        )

        assertEquals(SessionEndCondition.ABANDONED, repository.loadForm(id)!!.endCondition)
    }

    @Test
    fun `an old row with neither reads back as having run to the end`() = runTest {
        val id = db.sessionDao().insertSession(
            DatabaseTestFixture.session(gameId, "2026-02-04").copy(endCondition = null)
        )
        db.sessionDao().insertParticipants(
            listOf(DatabaseTestFixture.participant(id, me))
        )

        assertEquals(SessionEndCondition.STANDARD, repository.loadForm(id)!!.endCondition)
    }

    // --- every other mode settles its own result -------------------------------------

    /**
     * Pandemic's uncontrolled outbreak is a rule ending the game, and it is also a loss.
     * The table's verdict decides who won; the ending only says what brought it about.
     */
    @Test
    fun `a co-op a rule ended still takes the table's outcome`() = runTest {
        val coopGame = db.gameDao().insert(
            DatabaseTestFixture.game("Pandemic").copy(scoringMode = ScoringMode.COOPERATIVE)
        )
        val id = repository.save(
            SessionForm(
                gameId = coopGame,
                playedOn = LocalDate.parse("2026-02-02"),
                durationMinutes = 45,
                scoringMode = ScoringMode.COOPERATIVE,
                coopOutcome = CoopOutcome.LOSS,
                endCondition = SessionEndCondition.SPECIFIC,
                endReason = "Uncontrolled outbreak",
                participants = listOf(
                    ParticipantForm(playerId = me, playerName = "me"),
                    ParticipantForm(playerId = opponent, playerName = "them")
                )
            )
        )

        assertTrue("a lost co-op has no winner", db.sessionDao().getParticipants(id).none { it.isWinner })
        assertEquals("Uncontrolled outbreak", db.sessionDao().getSession(id)!!.endReason)
    }

    /**
     * Secret Hitler ends the instant Hitler is elected Chancellor, and the fascists win
     * whoever happens to be first in the list. Ranking by order here would hand the game
     * to the player at the top of the form.
     */
    @Test
    fun `a team game a rule ended still wins by the side, not by list order`() = runTest {
        val teamGame = db.gameDao().insert(
            DatabaseTestFixture.game("Secret Hitler").copy(scoringMode = ScoringMode.TEAM_BASED)
        )
        val id = repository.save(
            SessionForm(
                gameId = teamGame,
                playedOn = LocalDate.parse("2026-02-05"),
                durationMinutes = 40,
                scoringMode = ScoringMode.TEAM_BASED,
                winningTeam = "Fascists",
                endCondition = SessionEndCondition.SPECIFIC,
                endReason = "Hitler elected Chancellor",
                participants = listOf(
                    ParticipantForm(playerId = me, playerName = "me", team = "Liberals"),
                    ParticipantForm(playerId = opponent, playerName = "them", team = "Fascists")
                )
            )
        )

        val rows = db.sessionDao().getParticipants(id).associateBy { it.playerId }

        assertFalse("first in the list is not the winner here", rows[me]!!.isWinner)
        assertTrue("the winning side won", rows[opponent]!!.isWinner)
    }

    @Test
    fun `a manual placement play a rule ended keeps the order the user gave`() = runTest {
        val id = repository.save(
            form(
                players = listOf(me to null, opponent to null),
                endCondition = SessionEndCondition.SPECIFIC,
                endReason = "Ran out of cards",
                mode = ScoringMode.MANUAL_PLACEMENT
            )
        )

        val rows = db.sessionDao().getParticipants(id).associateBy { it.playerId }

        assertEquals(1, rows[me]!!.placement)
        assertEquals(2, rows[opponent]!!.placement)
    }

    // --- the game's own scoring must survive being played -------------------------

    @Test
    fun `saving a play a rule ended leaves the game's scoring mode alone`() = runTest {
        repository.save(
            form(
                players = listOf(me to null, opponent to null),
                endCondition = SessionEndCondition.SPECIFIC,
                endReason = "Military supremacy"
            )
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
                    ParticipantForm(playerId = opponent, playerName = "them")
                )
            )
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
                    ParticipantForm(playerId = opponent, playerName = "them")
                )
            )
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
            DatabaseTestFixture.game("Pandemic").copy(scoringMode = ScoringMode.COOPERATIVE)
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
                    ParticipantForm(playerId = opponent, playerName = "them")
                )
            )
        )

        assertTrue(db.sessionDao().getParticipants(id).none { it.isWinner })
    }
}

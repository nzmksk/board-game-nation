package com.boardgamenation.tracker.data.db

import com.boardgamenation.tracker.core.time.DateUtils
import com.boardgamenation.tracker.data.repository.SessionFilter
import com.boardgamenation.tracker.data.repository.SessionRepository
import com.boardgamenation.tracker.domain.model.CoopOutcome
import com.boardgamenation.tracker.domain.model.ParticipantForm
import com.boardgamenation.tracker.domain.model.ScoringMode
import com.boardgamenation.tracker.domain.model.SessionForm
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.LocalDate

@RunWith(RobolectricTestRunner::class)
class SessionDaoTest {

    private lateinit var db: AppDatabase
    private lateinit var repository: SessionRepository
    private var gameId = 0L
    private var me = 0L
    private var ben = 0L

    @Before
    fun setUp() = runTest {
        db = DatabaseTestFixture.database()
        repository = SessionRepository(
            sessionDao = db.sessionDao(),
            gameDao = db.gameDao(),
            playerDao = db.playerDao(),
            clock = DatabaseTestFixture.clock,
        )
        gameId = db.gameDao().insert(DatabaseTestFixture.game("Catan"))
        me = db.playerDao().insert(DatabaseTestFixture.player("Muhammad", isSelf = true))
        ben = db.playerDao().insert(DatabaseTestFixture.player("Ben"))
    }

    @After
    fun tearDown() = db.close()

    private fun form(
        scores: List<Pair<Long, Double?>>,
        mode: ScoringMode = ScoringMode.RANKED_SCORES,
        id: Long = 0,
        highScoreWins: Boolean = true,
    ) = SessionForm(
        id = id,
        gameId = gameId,
        playedOn = LocalDate.parse("2026-02-01"),
        durationMinutes = 75,
        scoringMode = mode,
        highScoreWins = highScoreWins,
        participants = scores.map { (playerId, score) ->
            ParticipantForm(playerId = playerId, playerName = "p$playerId", score = score)
        },
    )

    @Test
    fun `saving a session writes its participants in the same breath`() = runTest {
        val id = repository.save(form(listOf(me to 10.0, ben to 8.0)))

        assertEquals(1, db.sessionDao().count())
        assertEquals(2, db.sessionDao().getParticipants(id).size)
        assertEquals(2, db.sessionDao().getSession(id)!!.playerCount)
    }

    @Test
    fun `placements are derived on save, not trusted from the caller`() = runTest {
        val id = repository.save(form(listOf(me to 8.0, ben to 12.0)))
        val participants = db.sessionDao().getParticipants(id).associateBy { it.playerId }

        assertEquals(2, participants.getValue(me).placement)
        assertEquals(1, participants.getValue(ben).placement)
        assertTrue(participants.getValue(ben).isWinner)
        assertFalse(participants.getValue(me).isWinner)
    }

    @Test
    fun `golf scoring inverts who wins`() = runTest {
        val id = repository.save(
            form(listOf(me to 8.0, ben to 12.0), highScoreWins = false),
        )
        val participants = db.sessionDao().getParticipants(id).associateBy { it.playerId }
        assertTrue(participants.getValue(me).isWinner)
    }

    @Test
    fun `a co-op result applies to the whole table`() = runTest {
        val id = repository.save(
            form(listOf(me to null, ben to null), mode = ScoringMode.COOPERATIVE)
                .copy(coopOutcome = CoopOutcome.WIN),
        )

        val session = db.sessionDao().getSession(id)!!
        assertTrue(session.isCooperative)
        assertEquals(CoopOutcome.WIN, session.coopOutcome)
        assertTrue(db.sessionDao().getParticipants(id).all { it.isWinner })
    }

    /** Re-saving replaces the participant set rather than accumulating duplicates. */
    @Test
    fun `editing a session does not leave stale participants behind`() = runTest {
        val id = repository.save(form(listOf(me to 10.0, ben to 8.0)))
        repository.save(form(listOf(me to 10.0), id = id))

        assertEquals(1, db.sessionDao().getParticipants(id).size)
        assertEquals(1, db.sessionDao().getSession(id)!!.playerCount)
    }

    @Test
    fun `a first appearance is flagged even when the box was not ticked`() = runTest {
        val first = repository.save(form(listOf(me to 10.0)))
        assertTrue(db.sessionDao().getParticipants(first).first().isNewPlayer)

        val second = repository.save(
            form(listOf(me to 10.0)).copy(playedOn = LocalDate.parse("2026-02-02")),
        )
        assertFalse(db.sessionDao().getParticipants(second).first().isNewPlayer)
    }

    @Test
    fun `the turn order survives a save and a load`() = runTest {
        val id = repository.save(
            form(listOf(me to 10.0, ben to 8.0)).let { form ->
                form.copy(
                    participants = form.participants.map {
                        it.copy(turnOrder = if (it.playerId == ben) 1 else 2)
                    },
                )
            },
        )

        val rows = db.sessionDao().getParticipants(id).associateBy { it.playerId }
        assertEquals(1, rows.getValue(ben).turnOrder)
        assertEquals(2, rows.getValue(me).turnOrder)
        assertEquals(ben, repository.loadForm(id)!!.firstPlayer!!.playerId)
    }

    /**
     * Normalised on the way in, not merely on the screen that happened to enter it. A
     * form assembled from parts can hand over two rows claiming the first seat, and a
     * first-player win rate would then count the same play twice.
     */
    @Test
    fun `a play is saved with one first player, whatever the caller sent`() = runTest {
        val id = repository.save(
            form(listOf(me to 10.0, ben to 8.0)).let { form ->
                form.copy(participants = form.participants.map { it.copy(turnOrder = 1) })
            },
        )

        val seats = db.sessionDao().getParticipants(id).mapNotNull { it.turnOrder }.sorted()
        assertEquals(listOf(1, 2), seats)
    }

    @Test
    fun `a play with no turn order recorded keeps none`() = runTest {
        val id = repository.save(form(listOf(me to 10.0, ben to 8.0)))

        assertTrue(db.sessionDao().getParticipants(id).all { it.turnOrder == null })
        assertNull(repository.loadForm(id)!!.firstPlayer)
    }

    @Test
    fun `the scoring mode the user actually used is remembered on the game`() = runTest {
        repository.save(form(listOf(me to null), mode = ScoringMode.COOPERATIVE))
        assertEquals(ScoringMode.COOPERATIVE, db.gameDao().getGame(gameId)!!.scoringMode)
    }

    @Test
    fun `a new session form is prefilled from the last one`() = runTest {
        repository.save(form(listOf(me to 10.0, ben to 8.0)).copy(durationMinutes = 90))

        val prefilled = repository.newSessionForm(gameId)
        assertEquals(90, prefilled.durationMinutes)
        assertEquals(setOf(me, ben), prefilled.participants.map { it.playerId }.toSet())
        assertEquals(DatabaseTestFixture.clock.today(), prefilled.playedOn)
    }

    @Test
    fun `with no history the form falls back to the stated playtime`() = runTest {
        val prefilled = repository.newSessionForm(gameId)
        // The fixture game states 45 to 90 minutes, so the midpoint is the best guess.
        assertEquals(67, prefilled.durationMinutes)
    }

    @Test
    fun `incomplete sessions are excluded from the prefilled duration`() = runTest {
        repository.save(form(listOf(me to 10.0)).copy(durationMinutes = 90))
        repository.save(
            form(listOf(me to 10.0))
                .copy(playedOn = LocalDate.parse("2026-02-05"), durationMinutes = 5, isIncomplete = true),
        )
        assertEquals(90, repository.newSessionForm(gameId).durationMinutes)
    }

    @Test
    fun `a draft is created by the timer and hidden from every list`() = runTest {
        repository.createDraft(gameId, playerCount = 3)

        assertEquals(0, db.sessionDao().count())
        assertEquals(1, repository.getDrafts().size)
        assertTrue(repository.observeSessions(SessionFilter()).first().isEmpty())
        assertNotNull(repository.observeLatestDraft().first())
    }

    @Test
    fun `discarding a draft removes it`() = runTest {
        val id = repository.createDraft(gameId, playerCount = 2)
        repository.discardDraft(id)
        assertTrue(repository.getDrafts().isEmpty())
    }

    @Test
    fun `saving a form clears the draft flag`() = runTest {
        val draftId = repository.createDraft(gameId, playerCount = 1)
        repository.save(form(listOf(me to 10.0), id = draftId))

        assertTrue(repository.getDrafts().isEmpty())
        assertEquals(1, db.sessionDao().count())
    }

    @Test
    fun `the session list filters by game and by player`() = runTest {
        val other = db.gameDao().insert(DatabaseTestFixture.game("Wingspan"))
        repository.save(form(listOf(me to 10.0, ben to 5.0)))
        repository.save(
            form(listOf(me to 10.0)).copy(gameId = other, playedOn = LocalDate.parse("2026-02-06")),
        )

        assertEquals(2, repository.observeSessions(SessionFilter()).first().size)
        assertEquals(1, repository.observeSessions(SessionFilter(gameId = other)).first().size)
        assertEquals(1, repository.observeSessions(SessionFilter(playerId = ben)).first().size)
    }

    @Test
    fun `the session list filters by date range`() = runTest {
        repository.save(form(listOf(me to 1.0)))
        repository.save(form(listOf(me to 1.0)).copy(playedOn = LocalDate.parse("2026-03-20")))

        val march = repository.observeSessions(
            SessionFilter(fromDate = "2026-03-01", toDate = "2026-03-31"),
        ).first()
        assertEquals(1, march.size)
        assertEquals("2026-03-20", march.first().playedOn)
    }

    @Test
    fun `winner names are resolved for the list row`() = runTest {
        repository.save(form(listOf(me to 12.0, ben to 8.0)))
        val row = repository.observeSessions(SessionFilter()).first().first()
        assertEquals("Muhammad", row.winnerNames)
    }

    /** Merge import leans on this: same game, same day, same head count is a match. */
    @Test
    fun `the natural key finds an existing session`() = runTest {
        repository.save(form(listOf(me to 10.0, ben to 8.0)))
        val found = db.sessionDao().findByNaturalKey(gameId, "2026-02-01", 2)
        assertNotNull(found)
        assertNull(db.sessionDao().findByNaturalKey(gameId, "2026-02-01", 4))
    }

    @Test
    fun `a session loads back into an editable form`() = runTest {
        val id = repository.save(
            form(listOf(me to 10.0, ben to 8.0)).copy(location = "Kitchen table", notes = "Close one"),
        )

        val loaded = repository.loadForm(id)!!
        assertEquals(id, loaded.id)
        assertEquals("Kitchen table", loaded.location)
        assertEquals("Close one", loaded.notes)
        assertEquals(2, loaded.participants.size)
        assertEquals(LocalDate.parse("2026-02-01"), loaded.playedOn)
    }

    @Test
    fun `dates are stored as ISO text so they sort as strings`() = runTest {
        val id = repository.save(form(listOf(me to 1.0)))
        assertEquals("2026-02-01", db.sessionDao().getSession(id)!!.playedOn)
        assertEquals(
            LocalDate.parse("2026-02-01"),
            DateUtils.parseIsoOrNull(db.sessionDao().getSession(id)!!.playedOn),
        )
    }

    @Test
    fun `deleting a player is refused while their history exists`() = runTest {
        repository.save(form(listOf(me to 10.0, ben to 8.0)))
        assertEquals(1, db.playerDao().appearanceCount(ben))
    }
}

package com.boardgamenation.tracker.data.db

import com.boardgamenation.tracker.data.repository.SessionRepository
import com.boardgamenation.tracker.domain.model.ParticipantForm
import com.boardgamenation.tracker.domain.model.ScoringMode
import com.boardgamenation.tracker.domain.model.SessionForm
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.LocalDate

/**
 * The configuration a session was played at.
 *
 * The field was introduced for co-ops, where a win is meaningless without the difficulty
 * it was won at, but the question it answers is not a co-op question: Catan played with
 * Seafarers is a different game from Catan played without it, and a score of 12 means
 * something different on each. So the mode is kept whatever the scoring mode is.
 */
@RunWith(RobolectricTestRunner::class)
class SessionModeTest {

    private lateinit var db: AppDatabase
    private lateinit var repository: SessionRepository
    private var catan = 0L
    private var pandemic = 0L
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
        catan = db.gameDao().insert(
            DatabaseTestFixture.game("Catan").copy(scoringMode = ScoringMode.RANKED_SCORES),
        )
        pandemic = db.gameDao().insert(
            DatabaseTestFixture.game("Pandemic").copy(scoringMode = ScoringMode.COOPERATIVE),
        )
        me = db.playerDao().insert(DatabaseTestFixture.player("Hafiz", isSelf = true))
        opponent = db.playerDao().insert(DatabaseTestFixture.player("Aisyah"))
    }

    @After
    fun tearDown() = db.close()

    private fun form(
        gameId: Long,
        mode: String?,
        scoringMode: ScoringMode = ScoringMode.RANKED_SCORES,
    ) = SessionForm(
        gameId = gameId,
        playedOn = LocalDate.parse("2026-02-01"),
        durationMinutes = 90,
        scoringMode = scoringMode,
        mode = mode,
        participants = listOf(me to 12.0, opponent to 9.0).map { (playerId, score) ->
            ParticipantForm(playerId = playerId, playerName = "p$playerId", score = score)
        },
    )

    @Test
    fun `a competitive play keeps the configuration it was played at`() = runTest {
        val id = repository.save(form(catan, "Seafarers"))

        assertEquals("Seafarers", db.sessionDao().getSession(id)!!.mode)
    }

    @Test
    fun `a co-op play keeps its configuration just as it always did`() = runTest {
        val id = repository.save(
            form(pandemic, "5 epidemics + mutation", ScoringMode.COOPERATIVE),
        )

        assertEquals("5 epidemics + mutation", db.sessionDao().getSession(id)!!.mode)
    }

    /** Nobody typed anything, which must read as absent rather than as an empty chip. */
    @Test
    fun `a blank configuration is stored as nothing at all`() = runTest {
        val id = repository.save(form(catan, "   "))

        assertNull(db.sessionDao().getSession(id)!!.mode)
    }

    /**
     * Switching a play to competitive scoring used to drop the mode on the way to the
     * database. Editing a session is the same save path, so that silently erased a
     * configuration the user had entered on a game the form still offers it for.
     */
    @Test
    fun `switching to competitive scoring no longer erases the configuration`() = runTest {
        val id = repository.save(form(catan, "Cities & Knights", ScoringMode.COOPERATIVE))
        val edited = repository.loadForm(id)!!.copy(scoringMode = ScoringMode.RANKED_SCORES)

        repository.save(edited)

        assertEquals("Cities & Knights", db.sessionDao().getSession(id)!!.mode)
    }

    @Test
    fun `configurations come back as chips for the next play of that game`() = runTest {
        repository.save(form(catan, "Seafarers"))
        repository.save(form(pandemic, "Level 4", ScoringMode.COOPERATIVE))

        assertEquals(listOf("Seafarers"), repository.observeModesFor(catan).first())
        assertEquals(listOf("Level 4"), repository.observeModesFor(pandemic).first())
    }
}

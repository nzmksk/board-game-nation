package com.boardgamenation.tracker.data.db

import com.boardgamenation.tracker.data.repository.SessionRepository
import com.boardgamenation.tracker.domain.model.ParticipantForm
import com.boardgamenation.tracker.domain.model.ScoringMode
import com.boardgamenation.tracker.domain.model.SessionForm
import com.boardgamenation.tracker.domain.share.ShareCard
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.LocalDate

/**
 * Changing a play's scoring mode, run the whole way through: log with scores, switch the
 * mode, save, reopen, and build the card the share button would draw.
 *
 * The pieces are covered a layer at a time in `SessionDaoTest`. This is here because the
 * bug it stands for (#43) was only ever visible at the end of the chain -- every screen
 * hid the stale scores, and the picture did not -- so the chain is what is worth
 * holding together.
 */
@RunWith(RobolectricTestRunner::class)
class ScoringModeChangeTest {

    private lateinit var db: AppDatabase
    private lateinit var repository: SessionRepository
    private var gameId = 0L
    private var me = 0L
    private var ben = 0L

    @Before
    fun setUp() = runTest {
        db = DatabaseTestFixture.database()
        repository = SessionRepository(
            db.sessionDao(), db.gameDao(), db.playerDao(), DatabaseTestFixture.clock,
        )
        gameId = db.gameDao().insert(DatabaseTestFixture.game("Catan"))
        me = db.playerDao().insert(DatabaseTestFixture.player("Muhammad", isSelf = true))
        ben = db.playerDao().insert(DatabaseTestFixture.player("Ben"))
    }

    @After
    fun tearDown() = db.close()

    private fun form(mode: ScoringMode, id: Long = 0) = SessionForm(
        id = id,
        gameId = gameId,
        gameTitle = "Catan",
        playedOn = LocalDate.parse("2026-02-01"),
        durationMinutes = 75,
        scoringMode = mode,
        participants = listOf(
            ParticipantForm(playerId = me, playerName = "Muhammad", score = 10.0),
            ParticipantForm(playerId = ben, playerName = "Ben", score = 8.0),
        ),
    )

    @Test
    fun `the shared card loses the scores when the play stops having scoring`() = runTest {
        // 1-2. Log an activity with scores and save it.
        val id = repository.save(form(ScoringMode.RANKED_SCORES))
        assertTrue(ShareCard.of(repository.loadForm(id)!!).hasScores)

        listOf(
            ScoringMode.MANUAL_PLACEMENT,
            ScoringMode.COOPERATIVE,
            ScoringMode.TEAM_BASED,
            ScoringMode.NONE,
        ).forEach { mode ->
            // 3-5. Open it, change the scoring, save.
            val reopened = repository.loadForm(id)!!
            repository.save(reopened.copy(scoringMode = mode, participants = reopened.participants))

            // 6-8. Open it again and share.
            val card = ShareCard.of(repository.loadForm(id)!!)
            assertFalse("$mode still shows scores", card.hasScores)
            assertTrue("$mode lost the players", card.standings.size == 2)
            assertEquals("$mode", null, card.standings.first().scoreText)
        }
    }
}

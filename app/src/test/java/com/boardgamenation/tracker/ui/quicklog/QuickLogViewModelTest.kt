package com.boardgamenation.tracker.ui.quicklog

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.boardgamenation.tracker.data.db.AppDatabase
import com.boardgamenation.tracker.data.db.DatabaseTestFixture
import com.boardgamenation.tracker.data.repository.AchievementRepository
import com.boardgamenation.tracker.data.repository.GameRepository
import com.boardgamenation.tracker.data.repository.PlayerRepository
import com.boardgamenation.tracker.data.repository.SessionRepository
import com.boardgamenation.tracker.domain.achievement.AchievementEvaluator
import com.boardgamenation.tracker.domain.model.CoopOutcome
import com.boardgamenation.tracker.domain.model.ScoringMode
import com.boardgamenation.tracker.domain.usecase.SaveSessionUseCase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
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
 * Quick log must not change the game it is logging.
 *
 * The sheet records who won rather than asking for scores, and it used to say so by
 * forcing the form's scoring mode to NONE. `SessionRepository.save` writes the mode a
 * session used back onto the game, so a single quick log of a scored game permanently
 * reset it to "No scoring" and the full form opened without score fields ever after.
 *
 * The repository-level contract is covered in `SuddenDeathTest`; this exercises the sheet
 * itself, which is where the mistake actually lived.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class QuickLogViewModelTest {

    private lateinit var db: AppDatabase
    private lateinit var viewModel: QuickLogViewModel
    private var scoredGame = 0L
    private var coopGame = 0L
    private var me = 0L
    private var friend = 0L

    @Before
    fun setUp() = runBlocking {
        // viewModelScope dispatches to Main, and Room's suspend DAOs would otherwise hop
        // to Room's own executor. Unconfined plus direct executors keeps the whole of
        // save() on this thread, so an assertion right after it sees the finished work.
        Dispatchers.setMain(UnconfinedTestDispatcher())
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            AppDatabase::class.java,
        )
            .allowMainThreadQueries()
            .setQueryExecutor { it.run() }
            .setTransactionExecutor { it.run() }
            .build()
        val dispatcher = UnconfinedTestDispatcher()
        val clock = DatabaseTestFixture.clock

        val sessionRepository = SessionRepository(
            sessionDao = db.sessionDao(),
            gameDao = db.gameDao(),
            playerDao = db.playerDao(),
            clock = clock,
        )
        val achievements = AchievementRepository(
            context = ApplicationProvider.getApplicationContext(),
            achievementDao = db.achievementDao(),
            statsDao = db.achievementStatsDao(),
            evaluator = AchievementEvaluator(
                achievementDao = db.achievementDao(),
                statsDao = db.achievementStatsDao(),
                clock = clock,
            ),
            io = dispatcher,
        )

        scoredGame = db.gameDao().insert(
            DatabaseTestFixture.game("7 Wonders Duel").copy(
                scoringMode = ScoringMode.RANKED_SCORES,
                highScoreWins = true,
            ),
        )
        coopGame = db.gameDao().insert(
            DatabaseTestFixture.game("Pandemic").copy(scoringMode = ScoringMode.COOPERATIVE),
        )
        me = db.playerDao().insert(DatabaseTestFixture.player("Hafiz", isSelf = true))
        friend = db.playerDao().insert(DatabaseTestFixture.player("Aisyah"))

        viewModel = QuickLogViewModel(
            sessionRepository = sessionRepository,
            saveSession = SaveSessionUseCase(sessionRepository, achievements),
            playerRepository = PlayerRepository(db.playerDao()),
            gameRepository = GameRepository(
                gameDao = db.gameDao(),
                tagDao = db.tagDao(),
                sessionDao = db.sessionDao(),
                clock = clock,
            ),
            clock = clock,
        )
    }

    @After
    fun tearDown() {
        db.close()
        Dispatchers.resetMain()
    }

    @Test
    fun `quick logging a scored game leaves its scoring mode intact`() = runBlocking {
        viewModel.selectGame(scoredGame)
        viewModel.toggleWinner(me)
        viewModel.save()

        // Asserted first: without it this test would pass just as happily if the
        // quick log had silently saved nothing at all.
        assertEquals(1, db.sessionDao().count())
        assertEquals(
            "a quick log must not rewrite how the game is scored",
            ScoringMode.RANKED_SCORES,
            db.gameDao().getGame(scoredGame)!!.scoringMode,
        )
    }

    @Test
    fun `the winner the sheet was given is the winner that is stored`() = runBlocking {
        viewModel.selectGame(scoredGame)
        viewModel.toggleWinner(me)
        viewModel.save()

        val session = db.sessionDao().getAllSessions().single()
        val rows = db.sessionDao().getParticipants(session.id).associateBy { it.playerId }

        assertTrue(rows.getValue(me).isWinner)
        rows.filterKeys { it != me }.values.forEach {
            assertFalse("only the chosen player won", it.isWinner)
        }
    }

    @Test
    fun `a co-op win is recorded for the whole table`() = runBlocking {
        viewModel.selectGame(coopGame)
        viewModel.toggleWinner(me)
        viewModel.save()

        val session = db.sessionDao().getAllSessions().single()

        assertEquals(CoopOutcome.WIN, session.coopOutcome)
        assertTrue(db.sessionDao().getParticipants(session.id).all { it.isWinner })
        assertEquals(ScoringMode.COOPERATIVE, db.gameDao().getGame(coopGame)!!.scoringMode)
    }

    /**
     * The co-op path still runs through `applyCoop`, which is what turns the table's one
     * outcome into a flag on every player. Skipping it would mark everybody a winner.
     */
    @Test
    fun `a co-op loss marks nobody a winner`() = runBlocking {
        viewModel.selectGame(coopGame)
        viewModel.save()

        val session = db.sessionDao().getAllSessions().single()

        assertEquals(CoopOutcome.LOSS, session.coopOutcome)
        assertTrue(db.sessionDao().getParticipants(session.id).none { it.isWinner })
    }

    // --- the configuration a play was set up at -----------------------------------

    @Test
    fun `a configuration typed into the sheet is saved with the session`() = runBlocking {
        viewModel.selectGame(scoredGame)
        viewModel.setMode("Seafarers")
        viewModel.toggleWinner(me)
        viewModel.save()

        assertEquals("Seafarers", db.sessionDao().getAllSessions().single().mode)
    }

    /**
     * The chips are the reason the field is on the sheet at all: the second play of a
     * setup is one tap rather than retyping it inside a bottom sheet.
     */
    @Test
    fun `configurations from earlier plays are offered when the game is picked`() =
        runBlocking {
            viewModel.selectGame(scoredGame)
            viewModel.setMode("Pantheon")
            viewModel.toggleWinner(me)
            viewModel.save()

            viewModel.selectGame(scoredGame)

            assertEquals(listOf("Pantheon"), viewModel.state.value.previousModes)
        }

    /** Picking a different game must not leave the previous game's chips on screen. */
    @Test
    fun `switching game clears both the configuration and the chips`() = runBlocking {
        viewModel.selectGame(scoredGame)
        viewModel.setMode("Pantheon")
        viewModel.toggleWinner(me)
        viewModel.save()

        viewModel.selectGame(scoredGame)
        viewModel.toggleMode("Pantheon")
        viewModel.selectGame(coopGame)

        assertNull("a configuration belongs to the game it was typed for", viewModel.state.value.form.mode)
        assertEquals(emptyList<String>(), viewModel.state.value.previousModes)
    }

    @Test
    fun `tapping the chosen chip again clears it`() = runBlocking {
        viewModel.selectGame(scoredGame)
        viewModel.toggleMode("Pantheon")
        assertEquals("Pantheon", viewModel.state.value.form.mode)

        viewModel.toggleMode("Pantheon")

        assertNull(viewModel.state.value.form.mode)
    }
}

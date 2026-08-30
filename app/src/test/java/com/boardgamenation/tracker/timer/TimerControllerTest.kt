package com.boardgamenation.tracker.timer

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.boardgamenation.tracker.core.time.FakeElapsedTimeSource
import com.boardgamenation.tracker.data.db.AppDatabase
import com.boardgamenation.tracker.data.db.DatabaseTestFixture
import com.boardgamenation.tracker.data.db.entity.PlayerEntity
import com.boardgamenation.tracker.data.repository.SessionRepository
import com.boardgamenation.tracker.data.repository.TimerRepository
import com.boardgamenation.tracker.domain.timer.TimerConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The handover from the clock to the session form.
 *
 * The form is opened from the draft row rather than from the summary object, so what the
 * clock measured is only "preserved" if it reached the database. These tests go through
 * the controller and read back what the form would load.
 */
@RunWith(RobolectricTestRunner::class)
class TimerControllerTest {

    private val scheduler = TestCoroutineScheduler()
    private val dispatcher = StandardTestDispatcher(scheduler)
    private val appScope = CoroutineScope(dispatcher + Job())
    private val elapsed = FakeElapsedTimeSource()

    private lateinit var db: AppDatabase
    private lateinit var sessionRepository: SessionRepository
    private lateinit var controller: TimerController
    private var gameId = 0L
    private lateinit var me: PlayerEntity
    private lateinit var ben: PlayerEntity

    @Before
    fun setUp() = runTest(dispatcher) {
        db = DatabaseTestFixture.database()
        sessionRepository = SessionRepository(
            sessionDao = db.sessionDao(),
            gameDao = db.gameDao(),
            playerDao = db.playerDao(),
            clock = DatabaseTestFixture.clock,
        )
        controller = TimerController(
            context = ApplicationProvider.getApplicationContext<Context>(),
            timerRepository = TimerRepository(
                timerDao = db.timerDao(),
                playerDao = db.playerDao(),
                clock = DatabaseTestFixture.clock,
                elapsed = elapsed,
            ),
            sessionRepository = sessionRepository,
            elapsed = elapsed,
            clock = DatabaseTestFixture.clock,
            scope = appScope,
        )
        gameId = db.gameDao().insert(DatabaseTestFixture.game("Catan"))
        me = db.playerDao().let { dao ->
            dao.getPlayer(dao.insert(DatabaseTestFixture.player("Muhammad", isSelf = true)))!!
        }
        ben = db.playerDao().let { dao ->
            dao.getPlayer(dao.insert(DatabaseTestFixture.player("Ben")))!!
        }
    }

    @After
    fun tearDown() {
        appScope.cancel()
        db.close()
    }

    /** Muhammad plays for 90s, Ben for 150s, and the clock is stopped. */
    private suspend fun playFourMinutes() {
        controller.setUp(
            gameId = gameId,
            players = listOf(me, ben),
            config = TimerConfig(turnMs = 60_000, bankMs = 600_000),
        )
        controller.start()
        elapsed.advance(90_000)
        controller.passTurn()
        elapsed.advance(150_000)
    }

    @Test
    fun `stopping the clock leaves the played duration on the draft`() = runTest(dispatcher) {
        playFourMinutes()
        val summary = controller.stopAndSummarise()!!

        val form = sessionRepository.loadForm(summary.sessionId!!)!!
        assertEquals(gameId, form.gameId)
        assertEquals(4, form.durationMinutes)
    }

    @Test
    fun `stopping the clock leaves the table on the draft, in turn order`() = runTest(dispatcher) {
        playFourMinutes()
        val summary = controller.stopAndSummarise()!!

        val form = sessionRepository.loadForm(summary.sessionId!!)!!
        assertEquals(listOf(me.id, ben.id), form.participants.map { it.playerId })
        assertEquals(listOf("Muhammad", "Ben"), form.participants.map { it.playerName })
    }

    @Test
    fun `each player's turn and bank totals survive the handover`() = runTest(dispatcher) {
        playFourMinutes()
        val summary = controller.stopAndSummarise()!!

        val form = sessionRepository.loadForm(summary.sessionId!!)!!
        assertEquals(listOf(90_000L, 150_000L), form.participants.map { it.turnTimeMs })
        // A 60s turn clock covers the first minute; the rest comes out of the bank.
        assertEquals(
            listOf(570_000L, 510_000L),
            form.participants.map { it.bankTimeRemainingMs },
        )
    }

    @Test
    fun `the clock's own timestamps reach the form`() = runTest(dispatcher) {
        playFourMinutes()
        controller.pause()
        elapsed.advance(30_000)
        controller.resume()
        val summary = controller.stopAndSummarise()!!

        val form = sessionRepository.loadForm(summary.sessionId!!)!!
        assertEquals(DatabaseTestFixture.NOW, form.startedAt)
        assertEquals(DatabaseTestFixture.NOW, form.endedAt)
        assertEquals(30_000L, form.pausedMs)
    }

    /**
     * Stopping is not saving. The row the form opens is still a draft until the user
     * commits it, so it must stay out of the session list and out of the statistics.
     */
    @Test
    fun `the handed-over row is still a draft`() = runTest(dispatcher) {
        playFourMinutes()
        val summary = controller.stopAndSummarise()!!

        assertEquals(0, db.sessionDao().count())
        assertTrue(db.sessionDao().getSession(summary.sessionId!!)!!.isDraft)
    }

    @Test
    fun `a draft knows the table before the clock is ever stopped`() = runTest(dispatcher) {
        controller.setUp(gameId, listOf(me, ben), TimerConfig())
        controller.start()

        val draft = sessionRepository.getDrafts().single()
        assertNotNull(sessionRepository.loadForm(draft.id))
        assertEquals(
            listOf(me.id, ben.id),
            db.sessionDao().getParticipants(draft.id).map { it.playerId },
        )

        controller.discard()
    }
}

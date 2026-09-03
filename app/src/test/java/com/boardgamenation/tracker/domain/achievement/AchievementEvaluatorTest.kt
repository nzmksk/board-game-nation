package com.boardgamenation.tracker.domain.achievement

import com.boardgamenation.tracker.data.db.AppDatabase
import com.boardgamenation.tracker.data.db.DatabaseTestFixture
import com.boardgamenation.tracker.data.db.entity.AchievementEntity
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
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

@RunWith(RobolectricTestRunner::class)
class AchievementEvaluatorTest {

    private lateinit var db: AppDatabase
    private lateinit var evaluator: AchievementEvaluator
    private val json = Json

    @Before
    fun setUp() {
        db = DatabaseTestFixture.database()
        evaluator = AchievementEvaluator(
            achievementDao = db.achievementDao(),
            statsDao = db.achievementStatsDao(),
            clock = DatabaseTestFixture.clock
        )
    }

    @After
    fun tearDown() = db.close()

    private suspend fun define(code: String, rule: AchievementRule, hidden: Boolean = false) {
        db.achievementDao().insertDefinitions(
            listOf(
                AchievementEntity(
                    code = code,
                    name = code,
                    description = code,
                    icon = "EmojiEvents",
                    category = "Test",
                    targetValue = rule.target.takeIf { it > 0 },
                    isHidden = hidden,
                    ruleJson = json.encodeToString(AchievementRule.serializer(), rule)
                )
            )
        )
    }

    private suspend fun logPlays(count: Int, gameId: Long, startDay: Int = 1) {
        repeat(count) { index ->
            db.sessionDao().insertSession(
                DatabaseTestFixture.session(
                    gameId = gameId,
                    playedOn = "2026-02-%02d".format(startDay + index)
                )
            )
        }
    }

    @Test
    fun `a count threshold unlocks once it is reached`() = runTest {
        val gameId = db.gameDao().insert(DatabaseTestFixture.game("Catan"))
        define("plays_3", AchievementRule(RuleType.COUNT_THRESHOLD, Metric.TOTAL_PLAYS, target = 3.0))

        logPlays(2, gameId)
        assertTrue(evaluator.evaluate().isEmpty())

        logPlays(1, gameId, startDay = 3)
        val unlocked = evaluator.evaluate()
        assertEquals(listOf("plays_3"), unlocked.map { it.code })
    }

    /**
     * The property the spec calls out explicitly: evaluation is idempotent. It is
     * enforced by the unique index on achievement_id, not by bookkeeping in Kotlin.
     */
    @Test
    fun `evaluating twice never double unlocks`() = runTest {
        val gameId = db.gameDao().insert(DatabaseTestFixture.game("Catan"))
        define("first_play", AchievementRule(RuleType.COUNT_THRESHOLD, Metric.TOTAL_PLAYS, target = 1.0))
        logPlays(1, gameId)

        assertEquals(1, evaluator.evaluate().size)
        // Already unlocked, so the second pass has nothing to consider.
        assertEquals(0, evaluator.evaluate().size)
        assertEquals(1, db.achievementDao().countUnlocks())
    }

    @Test
    fun `an unlocked achievement is not re-examined`() = runTest {
        val gameId = db.gameDao().insert(DatabaseTestFixture.game("Catan"))
        define("first_play", AchievementRule(RuleType.COUNT_THRESHOLD, Metric.TOTAL_PLAYS, target = 1.0))
        logPlays(1, gameId)
        evaluator.evaluate()

        assertTrue(db.achievementDao().getLockedDefinitions().isEmpty())
    }

    @Test
    fun `per game threshold looks at the most played single game`() = runTest {
        val a = db.gameDao().insert(DatabaseTestFixture.game("Catan"))
        val b = db.gameDao().insert(DatabaseTestFixture.game("Wingspan"))
        define("same_game_3", AchievementRule(RuleType.PER_GAME_THRESHOLD, target = 3.0))

        // Six plays, but spread across two games: not the same game three times.
        logPlays(2, a, startDay = 1)
        logPlays(2, b, startDay = 5)
        assertTrue(evaluator.evaluate().isEmpty())

        logPlays(1, a, startDay = 10)
        assertEquals(listOf("same_game_3"), evaluator.evaluate().map { it.code })
    }

    @Test
    fun `a time window counts plays inside one day`() = runTest {
        val gameId = db.gameDao().insert(DatabaseTestFixture.game("Catan"))
        define("day_3", AchievementRule(RuleType.TIME_WINDOW, period = Period.DAY, target = 3.0))

        repeat(3) {
            db.sessionDao().insertSession(DatabaseTestFixture.session(gameId, "2026-02-01"))
        }
        assertEquals(listOf("day_3"), evaluator.evaluate().map { it.code })
    }

    @Test
    fun `a streak needs consecutive days, not merely three plays`() = runTest {
        val gameId = db.gameDao().insert(DatabaseTestFixture.game("Catan"))
        define("streak_3", AchievementRule(RuleType.STREAK, period = Period.DAY, target = 3.0))

        listOf("2026-02-01", "2026-02-03", "2026-02-05").forEach {
            db.sessionDao().insertSession(DatabaseTestFixture.session(gameId, it))
        }
        assertTrue(evaluator.evaluate().isEmpty())

        db.sessionDao().insertSession(DatabaseTestFixture.session(gameId, "2026-02-04"))
        assertEquals(listOf("streak_3"), evaluator.evaluate().map { it.code })
    }

    @Test
    fun `an attribute rule reads a single session's head count`() = runTest {
        val gameId = db.gameDao().insert(DatabaseTestFixture.game("Party game"))
        define(
            "six_players",
            AchievementRule(RuleType.ATTRIBUTE, attribute = Attribute.SESSION_PLAYER_COUNT, target = 6.0)
        )

        db.sessionDao().insertSession(
            DatabaseTestFixture.session(gameId, "2026-02-01", playerCount = 5)
        )
        assertTrue(evaluator.evaluate().isEmpty())

        db.sessionDao().insertSession(
            DatabaseTestFixture.session(gameId, "2026-02-02", playerCount = 7)
        )
        assertEquals(listOf("six_players"), evaluator.evaluate().map { it.code })
    }

    @Test
    fun `session duration is measured in hours`() = runTest {
        val gameId = db.gameDao().insert(DatabaseTestFixture.game("Epic"))
        define(
            "three_hours",
            AchievementRule(
                RuleType.ATTRIBUTE,
                attribute = Attribute.SESSION_DURATION_HOURS,
                target = 3.0
            )
        )

        db.sessionDao().insertSession(
            DatabaseTestFixture.session(gameId, "2026-02-01", durationMinutes = 200)
        )
        assertEquals(listOf("three_hours"), evaluator.evaluate().map { it.code })
    }

    @Test
    fun `a cost per play rule reads the other way round`() = runTest {
        val gameId = db.gameDao().insert(DatabaseTestFixture.game("Catan", price = 100.0))
        define(
            "cpp_under_20",
            AchievementRule(
                RuleType.COLLECTION,
                scope = Scope.COST_PER_PLAY_UNDER,
                target = 20.0,
                comparison = Comparison.AT_MOST
            )
        )

        logPlays(4, gameId)
        // 100 over 4 plays is 25 a play: not there yet.
        assertTrue(evaluator.evaluate().isEmpty())

        logPlays(1, gameId, startDay = 5)
        assertEquals(listOf("cpp_under_20"), evaluator.evaluate().map { it.code })
    }

    /** Zero is not an accomplishment: a fresh install must not unlock a value goal. */
    @Test
    fun `an at most rule does not unlock on an empty collection`() = runTest {
        define(
            "cpp_under_20",
            AchievementRule(
                RuleType.COLLECTION,
                scope = Scope.COST_PER_PLAY_UNDER,
                target = 20.0,
                comparison = Comparison.AT_MOST
            )
        )
        assertTrue(evaluator.evaluate().isEmpty())
    }

    @Test
    fun `playing everything you own unlocks against a target that moves`() = runTest {
        val a = db.gameDao().insert(DatabaseTestFixture.game("Catan"))
        db.gameDao().insert(DatabaseTestFixture.game("Wingspan"))
        define("no_shelf_of_shame", AchievementRule(RuleType.COLLECTION, scope = Scope.NO_UNPLAYED_GAMES))

        logPlays(1, a)
        assertTrue(evaluator.evaluate().isEmpty())

        val b = db.gameDao().getGameByTitle("Wingspan")!!.id
        logPlays(1, b, startDay = 5)
        assertEquals(listOf("no_shelf_of_shame"), evaluator.evaluate().map { it.code })
    }

    @Test
    fun `a win ratio needs a big enough sample`() = runTest {
        val gameId = db.gameDao().insert(DatabaseTestFixture.game("Catan"))
        val me = db.playerDao().insert(DatabaseTestFixture.player("Me", isSelf = true))
        define("win_rate", AchievementRule(RuleType.RATIO, target = 70.0, minPlays = 4))

        repeat(3) { index ->
            val sessionId = db.sessionDao().insertSession(
                DatabaseTestFixture.session(gameId, "2026-02-0${index + 1}")
            )
            db.sessionDao().insertParticipants(
                listOf(DatabaseTestFixture.participant(sessionId, me, isWinner = true))
            )
        }
        // Three from three is a perfect record, but not yet a meaningful one.
        assertTrue(evaluator.evaluate().isEmpty())

        val fourth = db.sessionDao().insertSession(DatabaseTestFixture.session(gameId, "2026-02-04"))
        db.sessionDao().insertParticipants(
            listOf(DatabaseTestFixture.participant(fourth, me, isWinner = true))
        )
        assertEquals(listOf("win_rate"), evaluator.evaluate().map { it.code })
    }

    /**
     * Deleting a session can pull the ground out from under an achievement it earned.
     * Leaving an undeserved trophy on the shelf would make the whole screen untrustworthy.
     */
    @Test
    fun `reconcile withdraws an unlock the data no longer supports`() = runTest {
        val gameId = db.gameDao().insert(DatabaseTestFixture.game("Catan"))
        define("plays_3", AchievementRule(RuleType.COUNT_THRESHOLD, Metric.TOTAL_PLAYS, target = 3.0))

        logPlays(3, gameId)
        evaluator.evaluate()
        val achievementId = db.achievementDao().findByCode("plays_3")!!.id
        assertNotNull(db.achievementDao().findUnlock(achievementId))

        db.sessionDao().getAllSessions().take(1).forEach { db.sessionDao().deleteSession(it.id) }
        evaluator.reconcile()
        assertNull(db.achievementDao().findUnlock(achievementId))
    }

    @Test
    fun `reconcile reinstates an unlock that is deserved again`() = runTest {
        val gameId = db.gameDao().insert(DatabaseTestFixture.game("Catan"))
        define("plays_2", AchievementRule(RuleType.COUNT_THRESHOLD, Metric.TOTAL_PLAYS, target = 2.0))

        logPlays(2, gameId)
        evaluator.reconcile()
        val achievementId = db.achievementDao().findByCode("plays_2")!!.id
        assertNotNull(db.achievementDao().findUnlock(achievementId))
    }

    @Test
    fun `progress reports how far along a locked achievement is`() = runTest {
        val gameId = db.gameDao().insert(DatabaseTestFixture.game("Catan"))
        val rule = AchievementRule(RuleType.COUNT_THRESHOLD, Metric.TOTAL_PLAYS, target = 50.0)
        logPlays(10, gameId)

        val progress = evaluator.progressOf(rule, evaluator.snapshot())
        assertEquals(10.0, progress.current, 0.001)
        assertEquals(50.0, progress.target, 0.001)
        assertEquals(0.2f, progress.fraction, 0.001f)
        assertFalse(progress.satisfied)
    }

    /** A rule from a newer version of the app must not take the screen down with it. */
    @Test
    fun `an unreadable rule never unlocks and never crashes`() = runTest {
        val rule = evaluator.parseRule("{ this is not json }")
        assertEquals(RuleType.UNKNOWN, rule.type)
        assertFalse(evaluator.progressOf(rule, evaluator.snapshot()).satisfied)
    }

    @Test
    fun `an unknown rule type is tolerated`() = runTest {
        val rule = evaluator.parseRule("""{"type":"TELEPORTATION","target":5}""")
        assertEquals(RuleType.UNKNOWN, rule.type)
    }
}

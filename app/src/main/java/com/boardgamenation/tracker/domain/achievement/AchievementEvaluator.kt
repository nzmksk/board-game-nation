package com.boardgamenation.tracker.domain.achievement

import com.boardgamenation.tracker.core.time.AppClock
import com.boardgamenation.tracker.core.time.DateUtils
import com.boardgamenation.tracker.data.db.dao.AchievementDao
import com.boardgamenation.tracker.data.db.dao.AchievementStatsDao
import com.boardgamenation.tracker.data.db.entity.AchievementEntity
import com.boardgamenation.tracker.data.db.entity.AchievementUnlockEntity
import com.boardgamenation.tracker.domain.stats.Streaks
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Every number the rules can be measured against, gathered once.
 *
 * Taking one snapshot and evaluating every rule against it means an evaluation pass is
 * a fixed number of aggregate queries no matter how many achievements exist, instead of
 * one query per achievement.
 */
data class AchievementSnapshot(
    val totalPlays: Int = 0,
    val totalHours: Double = 0.0,
    val gamesOwned: Int = 0,
    val distinctGamesPlayed: Int = 0,
    val distinctMechanicsPlayed: Int = 0,
    val gamesTaught: Int = 0,
    val gamesRated: Int = 0,
    val distinctPlayers: Int = 0,
    val maxPlaysOfSingleGame: Int = 0,
    val maxPlaysInOneDay: Int = 0,
    val maxPlaysInOneWeek: Int = 0,
    val maxPlaysInOneMonth: Int = 0,
    val maxSessionPlayerCount: Int = 0,
    val maxSessionDurationMinutes: Int = 0,
    val maxWeightPlayed: Double = 0.0,
    val unplayedOwnedCount: Int = 0,
    val lowestCostPerPlay: Double = 0.0,
    val fullyPlayedMechanics: Int = 0,
    val longestDayStreak: Int = 0,
    val longestWeekStreak: Int = 0,
    val longestMonthStreak: Int = 0,
    val currentDayStreak: Int = 0,
    val currentWeekStreak: Int = 0,
    val longestWinRun: Int = 0,
    val longestLossRun: Int = 0,
    val bestWinRate: Double = 0.0,
    /** Win rate needs a minimum sample, and that minimum varies per rule. */
    val bestWinRateByMinPlays: Map<Int, Double> = emptyMap(),
)

/** What one rule currently reads, and whether that is enough to unlock it. */
data class RuleProgress(
    val current: Double,
    val target: Double,
    val satisfied: Boolean,
) {
    val fraction: Float
        get() = if (target <= 0.0) 0f else (current / target).coerceIn(0.0, 1.0).toFloat()
}

/**
 * The rule engine.
 *
 * Runs after every session insert, update and delete, and after collection changes. It
 * is idempotent by construction: unlocks are inserted with a conflict strategy of IGNORE
 * against a unique index on achievement_id, so evaluating twice cannot double-unlock,
 * and an already-unlocked achievement is never re-examined.
 */
@Singleton
class AchievementEvaluator @Inject constructor(
    private val achievementDao: AchievementDao,
    private val statsDao: AchievementStatsDao,
    private val clock: AppClock,
) {

    private val json = Json { ignoreUnknownKeys = true }

    /**
     * Reads every metric. The min-play thresholds that RATIO rules ask for are collected
     * first so each distinct threshold costs one query rather than one per rule.
     */
    suspend fun snapshot(ratioMinPlays: Set<Int> = emptySet()): AchievementSnapshot {
        val dates = statsDao.playDates().mapNotNull { DateUtils.parseIsoOrNull(it) }
        val today = clock.today()
        val results = statsDao.selfResultsInOrder()

        val dayStreak = Streaks.byDay(dates, today)
        val weekStreak = Streaks.byWeek(dates, today)
        val monthStreak = Streaks.byMonth(dates, today)

        val rates = ratioMinPlays.associateWith { statsDao.bestWinRate(it) }

        return AchievementSnapshot(
            totalPlays = statsDao.totalPlays(),
            totalHours = statsDao.totalHours(),
            gamesOwned = statsDao.gamesOwned(),
            distinctGamesPlayed = statsDao.distinctGamesPlayed(),
            distinctMechanicsPlayed = statsDao.distinctMechanicsPlayed(),
            gamesTaught = statsDao.gamesTaught(),
            gamesRated = statsDao.gamesRated(),
            distinctPlayers = statsDao.distinctPlayers(),
            maxPlaysOfSingleGame = statsDao.maxPlaysOfSingleGame(),
            maxPlaysInOneDay = statsDao.maxPlaysInOneDay(),
            maxPlaysInOneWeek = statsDao.maxPlaysInOneWeek(),
            maxPlaysInOneMonth = statsDao.maxPlaysInOneMonth(),
            maxSessionPlayerCount = statsDao.maxSessionPlayerCount(),
            maxSessionDurationMinutes = statsDao.maxSessionDurationMinutes(),
            maxWeightPlayed = statsDao.maxWeightPlayed(),
            unplayedOwnedCount = statsDao.unplayedOwnedCount(),
            lowestCostPerPlay = statsDao.lowestCostPerPlay(),
            fullyPlayedMechanics = statsDao.fullyPlayedMechanicCount(minGames = 3),
            longestDayStreak = dayStreak.longest,
            longestWeekStreak = weekStreak.longest,
            longestMonthStreak = monthStreak.longest,
            currentDayStreak = dayStreak.current,
            currentWeekStreak = weekStreak.current,
            longestWinRun = Streaks.longestRunOf(results, value = true),
            longestLossRun = Streaks.longestRunOf(results, value = false),
            bestWinRate = rates.values.maxOrNull() ?: 0.0,
            bestWinRateByMinPlays = rates,
        )
    }

    fun parseRule(ruleJson: String): AchievementRule = try {
        json.decodeFromString(AchievementRule.serializer(), ruleJson)
    } catch (_: Exception) {
        // A malformed or future-dated rule must not take the achievements screen down
        // with it. It simply never unlocks.
        AchievementRule(type = RuleType.UNKNOWN)
    }

    /** Where one rule currently stands, for the progress bar under a locked tile. */
    fun progressOf(rule: AchievementRule, snapshot: AchievementSnapshot): RuleProgress {
        val current = currentValue(rule, snapshot)
        val target = effectiveTarget(rule, snapshot)
        val satisfied = when {
            target <= 0.0 -> false
            rule.comparison == Comparison.AT_LEAST -> current >= target
            // "At most" only counts once there is something to measure: a brand-new
            // collection has a cost-per-play of zero, which is not an accomplishment.
            else -> current > 0.0 && current <= target
        }
        return RuleProgress(current = current, target = target, satisfied = satisfied)
    }

    /**
     * Most targets are the constant from the JSON. "Play everything you own" is the
     * exception: its target is however many games are on the shelf today, so buying a
     * game legitimately re-locks it.
     */
    private fun effectiveTarget(rule: AchievementRule, s: AchievementSnapshot): Double =
        if (rule.type == RuleType.COLLECTION && rule.scope == Scope.NO_UNPLAYED_GAMES) {
            s.gamesOwned.toDouble()
        } else {
            rule.target
        }

    private fun currentValue(rule: AchievementRule, s: AchievementSnapshot): Double =
        when (rule.type) {
            RuleType.COUNT_THRESHOLD, RuleType.BREADTH -> metricValue(rule.metric, s)

            RuleType.PER_GAME_THRESHOLD -> s.maxPlaysOfSingleGame.toDouble()

            RuleType.STREAK -> when (rule.period) {
                Period.DAY -> s.longestDayStreak.toDouble()
                Period.WEEK -> s.longestWeekStreak.toDouble()
                Period.MONTH -> s.longestMonthStreak.toDouble()
                Period.NONE -> 0.0
            }

            RuleType.TIME_WINDOW -> when (rule.period) {
                Period.DAY -> s.maxPlaysInOneDay.toDouble()
                Period.WEEK -> s.maxPlaysInOneWeek.toDouble()
                Period.MONTH -> s.maxPlaysInOneMonth.toDouble()
                Period.NONE -> 0.0
            }

            RuleType.ATTRIBUTE -> when (rule.attribute) {
                Attribute.SESSION_PLAYER_COUNT -> s.maxSessionPlayerCount.toDouble()
                Attribute.SESSION_DURATION_HOURS -> s.maxSessionDurationMinutes / 60.0
                Attribute.GAME_WEIGHT -> s.maxWeightPlayed
                Attribute.NONE -> 0.0
            }

            RuleType.RATIO -> s.bestWinRateByMinPlays[rule.minPlays] ?: 0.0

            RuleType.COLLECTION -> when (rule.scope) {
                // Inverted deliberately: the achievement is having none left, so progress
                // is "games played" out of "games owned".
                Scope.NO_UNPLAYED_GAMES ->
                    (s.gamesOwned - s.unplayedOwnedCount).toDouble()
                Scope.COST_PER_PLAY_UNDER -> s.lowestCostPerPlay
                Scope.MECHANIC_COMPLETED -> s.fullyPlayedMechanics.toDouble()
                Scope.NONE -> 0.0
            }

            RuleType.UNKNOWN -> 0.0
        }

    private fun metricValue(metric: Metric, s: AchievementSnapshot): Double = when (metric) {
        Metric.TOTAL_PLAYS -> s.totalPlays.toDouble()
        Metric.TOTAL_HOURS -> s.totalHours
        Metric.GAMES_OWNED -> s.gamesOwned.toDouble()
        Metric.DISTINCT_GAMES_PLAYED -> s.distinctGamesPlayed.toDouble()
        Metric.DISTINCT_MECHANICS_PLAYED -> s.distinctMechanicsPlayed.toDouble()
        Metric.GAMES_TAUGHT -> s.gamesTaught.toDouble()
        Metric.GAMES_RATED -> s.gamesRated.toDouble()
        Metric.DISTINCT_PLAYERS -> s.distinctPlayers.toDouble()
        Metric.WIN_STREAK -> s.longestWinRun.toDouble()
        Metric.LOSS_STREAK -> s.longestLossRun.toDouble()
        Metric.NONE -> 0.0
    }

    /**
     * The evaluation pass. Only locked achievements are considered, and unlocks are
     * inserted with IGNORE, so calling this twice in a row is a no-op the second time.
     *
     * Returns whatever it newly unlocked, so the caller can show a snackbar without
     * having to diff the table itself.
     */
    suspend fun evaluate(triggeringSessionId: Long? = null): List<AchievementEntity> {
        val locked = achievementDao.getLockedDefinitions()
        if (locked.isEmpty()) return emptyList()

        val rules = locked.associateWith { parseRule(it.ruleJson) }
        val minPlays = rules.values
            .filter { it.type == RuleType.RATIO }
            .map { it.minPlays }
            .toSet()
        val snapshot = snapshot(minPlays)

        val now = clock.nowMillis()
        val unlocked = mutableListOf<AchievementEntity>()
        val rows = mutableListOf<AchievementUnlockEntity>()

        rules.forEach { (definition, rule) ->
            val progress = progressOf(rule, snapshot)
            if (progress.satisfied) {
                unlocked += definition
                rows += AchievementUnlockEntity(
                    achievementId = definition.id,
                    unlockedAt = now,
                    progressValue = progress.current,
                    sessionId = triggeringSessionId,
                )
            }
        }

        if (rows.isNotEmpty()) achievementDao.insertUnlocks(rows)
        return unlocked
    }

    /**
     * Re-checks unlocks after a destructive edit. Deleting or editing a session can pull
     * the ground out from under an achievement that session earned, and leaving a
     * now-undeserved trophy on the shelf would make the whole screen untrustworthy.
     */
    suspend fun reconcile() {
        val all = achievementDao.getAllDefinitions()
        val rules = all.associateWith { parseRule(it.ruleJson) }
        val minPlays = rules.values
            .filter { it.type == RuleType.RATIO }
            .map { it.minPlays }
            .toSet()
        val snapshot = snapshot(minPlays)

        val unlocks = achievementDao.getAllUnlocks().associateBy { it.achievementId }
        rules.forEach { (definition, rule) ->
            val progress = progressOf(rule, snapshot)
            val existing = unlocks[definition.id]
            when {
                existing != null && !progress.satisfied ->
                    achievementDao.deleteUnlock(definition.id)
                existing != null ->
                    achievementDao.updateProgress(definition.id, progress.current)
                progress.satisfied -> achievementDao.insertUnlock(
                    AchievementUnlockEntity(
                        achievementId = definition.id,
                        unlockedAt = clock.nowMillis(),
                        progressValue = progress.current,
                    ),
                )
            }
        }
    }
}

package com.boardgamenation.tracker.data.repository

import com.boardgamenation.tracker.core.time.AppClock
import com.boardgamenation.tracker.core.time.DateUtils
import com.boardgamenation.tracker.data.db.dao.StatsDao
import com.boardgamenation.tracker.data.db.projection.CostPerPlayRow
import com.boardgamenation.tracker.data.db.projection.DurationVsExpectedRow
import com.boardgamenation.tracker.data.db.projection.HeadToHeadRow
import com.boardgamenation.tracker.data.db.projection.LabelledValue
import com.boardgamenation.tracker.data.db.projection.PlayerStandingRow
import com.boardgamenation.tracker.data.db.projection.SessionListItem
import com.boardgamenation.tracker.domain.model.TagKind
import com.boardgamenation.tracker.domain.stats.StreakResult
import com.boardgamenation.tracker.domain.stats.Streaks
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class StatsRepository @Inject constructor(
    private val statsDao: StatsDao,
    private val clock: AppClock,
) {

    // Collection
    fun ownedBaseGames(): Flow<Int> = statsDao.observeOwnedBaseGameCount()
    fun ownedExpansions(): Flow<Int> = statsDao.observeOwnedExpansionCount()
    fun collectionValue(): Flow<Double> = statsDao.observeCollectionValue()
    fun byMechanic(limit: Int = 12): Flow<List<LabelledValue>> =
        statsDao.observeTagDistribution(TagKind.MECHANIC.name, limit)
    fun byCategory(limit: Int = 12): Flow<List<LabelledValue>> =
        statsDao.observeTagDistribution(TagKind.CATEGORY.name, limit)
    fun weightDistribution(): Flow<List<LabelledValue>> = statsDao.observeWeightDistribution()
    fun playerCountCoverage(): Flow<List<LabelledValue>> = statsDao.observePlayerCountCoverage()
    fun unplayedGames(): Flow<List<LabelledValue>> = statsDao.observeUnplayedGames()
    fun unratedOwned(): Flow<Int> = statsDao.observeUnratedOwnedCount()

    // Plays
    fun totalPlays(): Flow<Int> = statsDao.observeTotalPlays()
    fun totalMinutes(): Flow<Int> = statsDao.observeTotalMinutes()
    fun distinctGamesPlayed(): Flow<Int> = statsDao.observeDistinctGamesPlayed()
    fun playsByMonth(): Flow<List<LabelledValue>> = statsDao.observePlaysByMonth()
    fun playsByDayOfWeek(): Flow<List<LabelledValue>> = statsDao.observePlaysByDayOfWeek()
    fun mostPlayed(limit: Int = 10): Flow<List<LabelledValue>> = statsDao.observeMostPlayed(limit)
    fun longestSessions(limit: Int = 5): Flow<List<SessionListItem>> =
        statsDao.observeExtremeSessions(longest = true, limit = limit)
    fun shortestSessions(limit: Int = 5): Flow<List<SessionListItem>> =
        statsDao.observeExtremeSessions(longest = false, limit = limit)
    fun durationVsExpected(minPlays: Int = 2, limit: Int = 10): Flow<List<DurationVsExpectedRow>> =
        statsDao.observeDurationVsExpected(minPlays, limit)
    fun hIndex(): Flow<Int> = statsDao.observeHIndex()

    /**
     * The weekly streak.
     *
     * The database returns one short key per week that saw a play — a handful of rows,
     * not a table — and the consecutive-run arithmetic happens here, because SQLite on
     * API 26 has no window functions to do it in SQL.
     */
    fun weeklyStreak(): Flow<StreakResult> = statsDao.observeDaysWithPlays().map { rows ->
        val dates = rows.mapNotNull { DateUtils.parseIsoOrNull(it.label) }
        Streaks.byWeek(dates, clock.today())
    }

    fun dailyStreak(): Flow<StreakResult> = statsDao.observeDaysWithPlays().map { rows ->
        val dates = rows.mapNotNull { DateUtils.parseIsoOrNull(it.label) }
        Streaks.byDay(dates, clock.today())
    }

    // Value
    fun bestValue(limit: Int = 5): Flow<List<CostPerPlayRow>> =
        statsDao.observeCostPerPlay(cheapestFirst = true, limit = limit)
    fun worstValue(limit: Int = 5): Flow<List<CostPerPlayRow>> =
        statsDao.observeCostPerPlay(cheapestFirst = false, limit = limit)
    fun overallCostPerPlay(): Flow<Double?> = statsDao.observeOverallCostPerPlay()
    fun spendByYear(): Flow<List<LabelledValue>> = statsDao.observeSpendByYear()
    fun deadWeight(limit: Int = 5): Flow<List<LabelledValue>> = statsDao.observeDeadWeight(limit)

    // Players
    fun standings(gameId: Long? = null): Flow<List<PlayerStandingRow>> =
        statsDao.observeStandings(gameId)

    fun headToHead(): Flow<List<HeadToHeadRow>> = statsDao.observeHeadToHead()

    /**
     * The opponent who beats the user most often, over a sample large enough to mean
     * something. Three shared plays is not a rivalry.
     */
    fun nemesis(minPlays: Int = 3): Flow<HeadToHeadRow?> = statsDao.observeHeadToHead().map { rows ->
        rows.filter { it.sharedPlays >= minPlays }
            .maxByOrNull { it.opponentWins.toDouble() / it.sharedPlays }
    }

    fun winRateByGame(playerId: Long, minPlays: Int = 2): Flow<List<LabelledValue>> =
        statsDao.observeWinRateByGame(playerId, minPlays)

    fun averageScoreByGame(playerId: Long, limit: Int = 10): Flow<List<LabelledValue>> =
        statsDao.observeAverageScoreByGame(playerId, limit)
}

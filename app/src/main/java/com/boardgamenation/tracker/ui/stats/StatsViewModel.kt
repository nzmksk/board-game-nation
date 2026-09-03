package com.boardgamenation.tracker.ui.stats

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.boardgamenation.tracker.data.db.projection.CostPerPlayRow
import com.boardgamenation.tracker.data.db.projection.DurationVsExpectedRow
import com.boardgamenation.tracker.data.db.projection.FirstPlayerRecord
import com.boardgamenation.tracker.data.db.projection.HeadToHeadRow
import com.boardgamenation.tracker.data.db.projection.LabelledValue
import com.boardgamenation.tracker.data.db.projection.PlayerStandingRow
import com.boardgamenation.tracker.data.db.projection.SessionListItem
import com.boardgamenation.tracker.data.prefs.SettingsRepository
import com.boardgamenation.tracker.data.repository.StatsRepository
import com.boardgamenation.tracker.domain.stats.StreakResult
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

data class CollectionStats(
    val ownedGames: Int = 0,
    val expansions: Int = 0,
    val value: Double = 0.0,
    val currency: String = "MYR",
    val byMechanic: List<LabelledValue> = emptyList(),
    val byCategory: List<LabelledValue> = emptyList(),
    val weights: List<LabelledValue> = emptyList(),
    val playerCoverage: List<LabelledValue> = emptyList(),
    val unplayed: List<LabelledValue> = emptyList(),
    val unrated: Int = 0
)

data class PlayStats(
    val totalPlays: Int = 0,
    val totalMinutes: Int = 0,
    val distinctGames: Int = 0,
    val byMonth: List<LabelledValue> = emptyList(),
    val byDayOfWeek: List<LabelledValue> = emptyList(),
    val mostPlayed: List<LabelledValue> = emptyList(),
    val longest: List<SessionListItem> = emptyList(),
    val shortest: List<SessionListItem> = emptyList(),
    val durationVsExpected: List<DurationVsExpectedRow> = emptyList(),
    val streak: StreakResult = StreakResult(0, 0),
    val hIndex: Int = 0,

    /** Empty until a play names a starting player; the card reads that as no data. */
    val firstPlayer: FirstPlayerRecord = FirstPlayerRecord(0, 0, null)
)

data class ValueStats(
    val bestValue: List<CostPerPlayRow> = emptyList(),
    val worstValue: List<CostPerPlayRow> = emptyList(),
    val overallCostPerPlay: Double? = null,
    val spendByYear: List<LabelledValue> = emptyList(),
    val deadWeight: List<LabelledValue> = emptyList(),
    val currency: String = "MYR"
)

data class PlayerStats(
    val standings: List<PlayerStandingRow> = emptyList(),
    val headToHead: List<HeadToHeadRow> = emptyList(),
    val nemesis: HeadToHeadRow? = null
)

@HiltViewModel
class StatsViewModel @Inject constructor(private val statsRepository: StatsRepository, settingsRepository: SettingsRepository) :
    ViewModel() {

    private val currency = settingsRepository.settings.map { it.defaultCurrency }

    // Each tab is its own combine rather than one giant state object, so opening the
    // Value tab does not subscribe to the player queries and vice versa.

    /** Typed rather than a list of Any, so a reordered combine cannot silently miscast. */
    private data class CollectionTotals(val owned: Int, val expansions: Int, val value: Double, val currency: String)

    val collection: StateFlow<CollectionStats> = combine(
        combine(
            statsRepository.ownedBaseGames(),
            statsRepository.ownedExpansions(),
            statsRepository.collectionValue(),
            currency,
            ::CollectionTotals
        ),
        statsRepository.byMechanic(),
        statsRepository.byCategory(),
        combine(
            statsRepository.weightDistribution(),
            statsRepository.playerCountCoverage()
        ) { weights, coverage -> weights to coverage },
        combine(
            statsRepository.unplayedGames(),
            statsRepository.unratedOwned()
        ) { unplayed, unrated -> unplayed to unrated }
    ) { totals, mechanics, categories, (weights, coverage), (unplayed, unrated) ->
        CollectionStats(
            ownedGames = totals.owned,
            expansions = totals.expansions,
            value = totals.value,
            currency = totals.currency,
            byMechanic = mechanics,
            byCategory = categories,
            weights = weights,
            playerCoverage = coverage,
            unplayed = unplayed,
            unrated = unrated
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), CollectionStats())

    val plays: StateFlow<PlayStats> = combine(
        combine(
            statsRepository.totalPlays(),
            statsRepository.totalMinutes(),
            statsRepository.distinctGamesPlayed(),
            statsRepository.hIndex()
        ) { plays, minutes, distinct, hIndex -> listOf(plays, minutes, distinct, hIndex) },
        combine(
            statsRepository.playsByMonth(),
            statsRepository.playsByDayOfWeek(),
            statsRepository.mostPlayed()
        ) { months, days, most -> Triple(months, days, most) },
        combine(
            statsRepository.longestSessions(),
            statsRepository.shortestSessions(),
            statsRepository.firstPlayerRecord(),
            ::Triple
        ),
        statsRepository.durationVsExpected(),
        statsRepository.weeklyStreak()
    ) { totals, (months, days, most), (longest, shortest, firstPlayer), divergence, streak ->
        PlayStats(
            totalPlays = totals[0],
            totalMinutes = totals[1],
            distinctGames = totals[2],
            hIndex = totals[3],
            byMonth = months,
            byDayOfWeek = days,
            mostPlayed = most,
            longest = longest,
            shortest = shortest,
            durationVsExpected = divergence,
            streak = streak,
            firstPlayer = firstPlayer
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), PlayStats())

    val value: StateFlow<ValueStats> = combine(
        statsRepository.bestValue(),
        statsRepository.worstValue(),
        statsRepository.overallCostPerPlay(),
        statsRepository.spendByYear(),
        combine(statsRepository.deadWeight(), currency) { dead, code -> dead to code }
    ) { best, worst, overall, spend, (dead, code) ->
        ValueStats(
            bestValue = best,
            worstValue = worst,
            overallCostPerPlay = overall,
            spendByYear = spend,
            deadWeight = dead,
            currency = code
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ValueStats())

    val players: StateFlow<PlayerStats> = combine(
        statsRepository.standings(),
        statsRepository.headToHead(),
        statsRepository.nemesis()
    ) { standings, headToHead, nemesis ->
        PlayerStats(standings, headToHead, nemesis)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), PlayerStats())
}

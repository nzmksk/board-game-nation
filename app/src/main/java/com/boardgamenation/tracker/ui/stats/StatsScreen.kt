package com.boardgamenation.tracker.ui.stats

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.boardgamenation.tracker.R
import com.boardgamenation.tracker.core.time.DurationFormat
import com.boardgamenation.tracker.data.db.projection.CostPerPlayRow
import com.boardgamenation.tracker.data.db.projection.LabelledValue
import com.boardgamenation.tracker.ui.components.DivergingBarChart
import com.boardgamenation.tracker.ui.components.FirstPlayerAdvantage
import com.boardgamenation.tracker.ui.components.HorizontalBarChart
import com.boardgamenation.tracker.ui.components.LineChart
import com.boardgamenation.tracker.ui.components.PlayerDot
import com.boardgamenation.tracker.ui.components.SectionHeader
import com.boardgamenation.tracker.ui.components.StatTile
import com.boardgamenation.tracker.ui.components.VerticalBarChart
import com.boardgamenation.tracker.ui.components.currentLocale
import com.boardgamenation.tracker.ui.theme.LocalChartColors
import java.time.DayOfWeek
import java.time.format.TextStyle
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StatsScreen(viewModel: StatsViewModel = hiltViewModel()) {
    var tab by remember { mutableIntStateOf(0) }
    val tabs = listOf(
        R.string.stats_tab_collection,
        R.string.stats_tab_plays,
        R.string.stats_tab_value,
        R.string.stats_tab_players
    )

    Scaffold(
        topBar = { TopAppBar(title = { Text(stringResource(R.string.stats_title)) }) }
    ) { padding ->
        Column(Modifier.padding(padding)) {
            // Primary rather than secondary: these four are this screen's top-level
            // navigation, not a subdivision of something above them.
            PrimaryTabRow(selectedTabIndex = tab) {
                tabs.forEachIndexed { index, labelRes ->
                    Tab(
                        selected = tab == index,
                        onClick = { tab = index },
                        text = { Text(stringResource(labelRes)) }
                    )
                }
            }
            when (tab) {
                0 -> CollectionTab(viewModel)
                1 -> PlaysTab(viewModel)
                2 -> ValueTab(viewModel)
                else -> PlayersTab(viewModel)
            }
        }
    }
}

@Composable
private fun CollectionTab(viewModel: StatsViewModel) {
    val stats by viewModel.collection.collectAsStateWithLifecycle()
    val locale = currentLocale()
    LazyColumn(contentPadding = PaddingValues(bottom = 32.dp)) {
        item {
            Row(
                Modifier.padding(16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                StatTile(
                    label = stringResource(R.string.stats_total_games),
                    value = stats.ownedGames.toString(),
                    modifier = Modifier.weight(1f)
                )
                StatTile(
                    label = stringResource(R.string.stats_total_expansions),
                    value = stats.expansions.toString(),
                    modifier = Modifier.weight(1f)
                )
                StatTile(
                    label = stringResource(R.string.stats_collection_value),
                    value = String.format(locale, "%,.0f", stats.value),
                    supporting = stats.currency,
                    modifier = Modifier.weight(1.2f)
                )
            }
        }

        item { SectionHeader(stringResource(R.string.stats_by_mechanic)) }
        item { ChartCard { HorizontalBarChart(stats.byMechanic.toPairs()) } }

        item { SectionHeader(stringResource(R.string.stats_by_category)) }
        item { ChartCard { HorizontalBarChart(stats.byCategory.toPairs()) } }

        item { SectionHeader(stringResource(R.string.stats_weight_distribution)) }
        item { ChartCard { VerticalBarChart(stats.weights.toPairs()) } }

        item { SectionHeader(stringResource(R.string.stats_player_coverage)) }
        item { ChartCard { VerticalBarChart(stats.playerCoverage.toPairs()) } }

        item { SectionHeader(stringResource(R.string.stats_shelf_of_shame)) }
        item {
            ChartCard {
                if (stats.unplayed.isEmpty()) {
                    Text(
                        text = stringResource(R.string.stats_shelf_of_shame_empty),
                        style = MaterialTheme.typography.bodyMedium
                    )
                } else {
                    Column {
                        Text(
                            text = stringResource(
                                R.string.stats_shelf_of_shame_body,
                                stats.unplayed.size
                            ),
                            style = MaterialTheme.typography.bodyMedium
                        )
                        Spacer(Modifier.height(8.dp))
                        // Sorted by how long it has sat there, which is the part that
                        // stings and therefore the part worth showing.
                        stats.unplayed.take(10).forEach { row ->
                            Text(
                                text = stringResource(
                                    R.string.stats_unplayed_row,
                                    row.label,
                                    row.value.roundToInt()
                                ),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
            }
        }

        item {
            ChartCard {
                Text(
                    text = stringResource(R.string.stats_unrated_count, stats.unrated),
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        }
    }
}

@Composable
private fun PlaysTab(viewModel: StatsViewModel) {
    val stats by viewModel.plays.collectAsStateWithLifecycle()
    val locale = currentLocale()
    LazyColumn(contentPadding = PaddingValues(bottom = 32.dp)) {
        item {
            Row(
                Modifier.padding(16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                StatTile(
                    label = stringResource(R.string.stats_total_plays),
                    value = stats.totalPlays.toString(),
                    modifier = Modifier.weight(1f)
                )
                StatTile(
                    label = stringResource(R.string.stats_total_hours),
                    value = DurationFormat.hoursOneDecimal(stats.totalMinutes),
                    modifier = Modifier.weight(1f)
                )
                StatTile(
                    label = stringResource(R.string.stats_distinct_games),
                    value = stats.distinctGames.toString(),
                    modifier = Modifier.weight(1f)
                )
            }
        }

        item {
            Row(
                Modifier.padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                StatTile(
                    label = stringResource(R.string.stats_streak),
                    value = stats.streak.current.toString(),
                    supporting = stringResource(
                        R.string.dashboard_streak_longest,
                        stats.streak.longest
                    ),
                    modifier = Modifier.weight(1f)
                )
                StatTile(
                    label = stringResource(R.string.stats_h_index),
                    value = stats.hIndex.toString(),
                    supporting = stringResource(R.string.stats_h_index_body, stats.hIndex),
                    modifier = Modifier.weight(1.4f)
                )
            }
        }

        item { SectionHeader(stringResource(R.string.stats_plays_over_time)) }
        item { ChartCard { LineChart(stats.byMonth.toPairs()) } }

        item { SectionHeader(stringResource(R.string.stats_most_played)) }
        item { ChartCard { HorizontalBarChart(stats.mostPlayed.toPairs()) } }

        item { SectionHeader(stringResource(R.string.stats_by_day_of_week)) }
        item {
            ChartCard {
                VerticalBarChart(
                    stats.byDayOfWeek.map { row ->
                        val index = row.label.toIntOrNull() ?: 0
                        val day = if (index == 0) DayOfWeek.SUNDAY else DayOfWeek.of(index)
                        day.getDisplayName(TextStyle.SHORT, locale) to row.value
                    }
                )
            }
        }

        item { SectionHeader(stringResource(R.string.stats_first_player)) }
        item {
            ChartCard {
                Column {
                    Text(
                        text = stringResource(R.string.stats_first_player_body),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(8.dp))
                    FirstPlayerAdvantage(stats.firstPlayer)
                }
            }
        }

        item { SectionHeader(stringResource(R.string.stats_duration_vs_bgg)) }
        item {
            ChartCard {
                Column {
                    Text(
                        text = stringResource(R.string.stats_duration_vs_bgg_body),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(8.dp))
                    DivergingBarChart(
                        stats.durationVsExpected.map { row ->
                            val delta = row.actualAvg - row.statedAvg
                            val label = DurationFormat.minutes(kotlin.math.abs(delta).roundToInt())
                            Triple(
                                row.title,
                                delta,
                                if (delta >= 0) "+$label" else "-$label"
                            )
                        }
                    )
                }
            }
        }

        item { SectionHeader(stringResource(R.string.stats_longest_sessions)) }
        item {
            ChartCard {
                HorizontalBarChart(
                    stats.longest.map { it.gameTitle to it.durationMinutes.toDouble() },
                    valueFormatter = { DurationFormat.minutes(it.roundToInt()) }
                )
            }
        }

        item { SectionHeader(stringResource(R.string.stats_shortest_sessions)) }
        item {
            ChartCard {
                HorizontalBarChart(
                    stats.shortest.map { it.gameTitle to it.durationMinutes.toDouble() },
                    valueFormatter = { DurationFormat.minutes(it.roundToInt()) }
                )
            }
        }
    }
}

@Composable
private fun ValueTab(viewModel: StatsViewModel) {
    val stats by viewModel.value.collectAsStateWithLifecycle()
    val locale = currentLocale()
    LazyColumn(contentPadding = PaddingValues(bottom = 32.dp)) {
        item {
            // Cost per play gets the first card: it is the number that actually changes
            // what somebody buys next.
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer
                ),
                modifier = Modifier.fillMaxWidth().padding(16.dp)
            ) {
                Column(Modifier.padding(16.dp)) {
                    Text(
                        text = stringResource(R.string.stats_overall_cost_per_play),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                    Text(
                        text = stats.overallCostPerPlay
                            ?.let { String.format(locale, "%.2f %s", it, stats.currency) }
                            ?: stringResource(R.string.stats_no_data),
                        style = MaterialTheme.typography.headlineMedium,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                }
            }
        }

        item { SectionHeader(stringResource(R.string.stats_most_economical)) }
        item { ChartCard { CostPerPlayList(stats.bestValue) } }

        item { SectionHeader(stringResource(R.string.stats_least_economical)) }
        item { ChartCard { CostPerPlayList(stats.worstValue) } }

        item { SectionHeader(stringResource(R.string.stats_spend_by_year)) }
        item { ChartCard { VerticalBarChart(stats.spendByYear.toPairs()) } }

        item { SectionHeader(stringResource(R.string.stats_dead_weight)) }
        item {
            ChartCard {
                HorizontalBarChart(
                    stats.deadWeight.toPairs(),
                    valueFormatter = { String.format(locale, "%.0f", it) }
                )
            }
        }
    }
}

@Composable
private fun CostPerPlayList(rows: List<CostPerPlayRow>) {
    val locale = currentLocale()
    if (rows.isEmpty()) {
        Text(
            text = stringResource(R.string.stats_no_data),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        return
    }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        rows.forEach { row ->
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = row.title,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    text = stringResource(R.string.stats_plays_value, row.playCount),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(0.dp))
                Spacer(Modifier.width(8.dp))
                Text(
                    text = String.format(
                        locale,
                        "%.2f %s",
                        row.costPerPlay,
                        row.currency
                    ),
                    style = MaterialTheme.typography.labelMedium
                )
            }
        }
    }
}

@Composable
private fun PlayersTab(viewModel: StatsViewModel) {
    val stats by viewModel.players.collectAsStateWithLifecycle()
    val chartColors = LocalChartColors.current

    LazyColumn(contentPadding = PaddingValues(bottom = 32.dp)) {
        stats.nemesis?.let { nemesis ->
            item {
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.tertiaryContainer
                    ),
                    modifier = Modifier.fillMaxWidth().padding(16.dp)
                ) {
                    Column(Modifier.padding(16.dp)) {
                        Text(
                            text = stringResource(R.string.stats_nemesis),
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onTertiaryContainer
                        )
                        Text(
                            text = stringResource(
                                R.string.stats_nemesis_body,
                                nemesis.opponentName,
                                (nemesis.opponentWins * 100 / nemesis.sharedPlays.coerceAtLeast(1))
                            ),
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onTertiaryContainer
                        )
                    }
                }
            }
        }

        item { SectionHeader(stringResource(R.string.stats_head_to_head)) }
        items(stats.headToHead.size) { index ->
            val row = stats.headToHead[index]
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                PlayerDot(chartColors.forPlayer(row.colorHex, index))
                Spacer(Modifier.width(8.dp))
                Text(
                    text = row.opponentName,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    text = stringResource(
                        R.string.stats_head_to_head_record,
                        row.selfWins,
                        row.opponentWins
                    ),
                    style = MaterialTheme.typography.labelLarge
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = stringResource(R.string.stats_shared_plays, row.sharedPlays),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        item { SectionHeader(stringResource(R.string.players_title)) }
        items(stats.standings.size) { index ->
            val row = stats.standings[index]
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                PlayerDot(chartColors.forPlayer(row.colorHex, index))
                Spacer(Modifier.width(8.dp))
                Text(
                    text = row.playerName,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    text = stringResource(
                        R.string.stats_win_rate_value,
                        if (row.plays > 0) row.wins * 100 / row.plays else 0
                    ),
                    style = MaterialTheme.typography.labelMedium
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = stringResource(R.string.stats_plays_value, row.plays),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun ChartCard(content: @Composable () -> Unit) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow
        ),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)
    ) {
        Column(Modifier.padding(16.dp)) { content() }
    }
}

private fun List<LabelledValue>.toPairs(): List<Pair<String, Double>> = map { it.label to it.value }

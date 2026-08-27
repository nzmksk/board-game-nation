package com.boardgamenation.tracker.ui.gamedetail

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
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
import com.boardgamenation.tracker.data.db.entity.GameEntity
import com.boardgamenation.tracker.data.db.projection.GameAggregates
import com.boardgamenation.tracker.domain.model.TagKind
import com.boardgamenation.tracker.ui.collection.labelRes
import com.boardgamenation.tracker.ui.components.GameThumbnail
import com.boardgamenation.tracker.ui.components.KeyValueRow
import com.boardgamenation.tracker.ui.components.LoadingRows
import com.boardgamenation.tracker.ui.components.SectionHeader
import com.boardgamenation.tracker.ui.components.StatTile
import com.boardgamenation.tracker.ui.sessions.SessionRow
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GameDetailScreen(
    onBack: () -> Unit,
    onEdit: (Long) -> Unit,
    onLogPlay: (Long) -> Unit,
    onStartTimer: (Long) -> Unit,
    onOpenSession: (Long) -> Unit,
    onOpenGame: (Long) -> Unit,
    onRate: (Long) -> Unit,
    viewModel: GameDetailViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val deletePrompt by viewModel.deletePrompt.collectAsStateWithLifecycle()
    val deleted by viewModel.deleted.collectAsStateWithLifecycle()
    var lendDialogOpen by remember { mutableStateOf(false) }

    LaunchedEffect(deleted) { if (deleted) onBack() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = state.game?.title.orEmpty(),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.action_back),
                        )
                    }
                },
                actions = {
                    IconButton(onClick = { onEdit(viewModel.gameId) }) {
                        Icon(
                            Icons.Filled.Edit,
                            contentDescription = stringResource(R.string.action_edit),
                        )
                    }
                    IconButton(onClick = viewModel::requestDelete) {
                        Icon(
                            Icons.Filled.Delete,
                            contentDescription = stringResource(R.string.action_delete),
                        )
                    }
                },
            )
        },
    ) { padding ->
        val game = state.game
        if (state.isLoading) {
            LoadingRows(modifier = Modifier.padding(padding))
            return@Scaffold
        }
        if (game == null) {
            Text(
                text = stringResource(R.string.error_not_found),
                modifier = Modifier.padding(padding).padding(24.dp),
            )
            return@Scaffold
        }

        LazyColumn(
            modifier = Modifier.padding(padding),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 32.dp),
        ) {
            item { HeaderCard(game, state) }

            item {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Button(
                        onClick = { onLogPlay(game.id) },
                        modifier = Modifier.weight(1f),
                    ) { Text(stringResource(R.string.game_detail_log_play)) }
                    OutlinedButton(
                        onClick = { onStartTimer(game.id) },
                        modifier = Modifier.weight(1f),
                    ) { Text(stringResource(R.string.game_detail_start_timer)) }
                }
            }

            item { StatsRow(state) }

            if (state.tags.isNotEmpty()) {
                item { SectionHeader(stringResource(R.string.game_detail_tags)) }
                item {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState())
                            .padding(horizontal = 16.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        state.tags.forEach { tag ->
                            AssistChip(onClick = {}, label = { Text(tag.name) })
                        }
                    }
                }
            }

            item { SectionHeader(stringResource(R.string.game_detail_rating)) }
            item { RatingSection(state, onRate = { onRate(game.id) }) }

            item { SectionHeader(stringResource(R.string.settings_general)) }
            item {
                MetadataSection(
                    game = game,
                    designers = state.tags
                        .filter { it.kind == TagKind.DESIGNER }
                        .map { it.name },
                )
            }

            item { SectionHeader(stringResource(R.string.game_detail_expansions)) }
            if (state.expansions.isEmpty()) {
                item {
                    Text(
                        text = stringResource(R.string.game_detail_no_expansions),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                    )
                }
            } else {
                items(state.expansions.size) { index ->
                    val expansion = state.expansions[index]
                    Text(
                        text = expansion.title,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onOpenGame(expansion.id) }
                            .padding(horizontal = 16.dp, vertical = 10.dp),
                    )
                }
            }

            item { SectionHeader(stringResource(R.string.game_detail_play_history)) }
            if (state.sessions.isEmpty()) {
                item {
                    Text(
                        text = stringResource(R.string.game_detail_never_played),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                    )
                }
            } else {
                items(state.sessions.size) { index ->
                    SessionRow(
                        session = state.sessions[index],
                        showGameTitle = false,
                        onClick = { onOpenSession(state.sessions[index].id) },
                    )
                }
            }

            item { LendSection(game, state, onLend = { lendDialogOpen = true }, onReturn = viewModel::markReturned) }
        }
    }

    deletePrompt?.let { prompt ->
        AlertDialog(
            onDismissRequest = viewModel::dismissDelete,
            title = { Text(stringResource(R.string.game_detail_delete_title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (prompt.sessionCount > 0) {
                        Text(
                            stringResource(
                                if (prompt.sessionCount == 1) {
                                    R.string.game_detail_delete_with_sessions
                                } else {
                                    R.string.game_detail_delete_with_sessions_plural
                                },
                                prompt.sessionCount,
                            ),
                        )
                    }
                    if (prompt.expansionCount > 0) {
                        Text(
                            stringResource(
                                if (prompt.expansionCount == 1) {
                                    R.string.game_detail_delete_with_expansions
                                } else {
                                    R.string.game_detail_delete_with_expansions_plural
                                },
                                prompt.expansionCount,
                            ),
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = viewModel::confirmDelete) {
                    Text(
                        text = stringResource(R.string.action_delete),
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = viewModel::dismissDelete) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
        )
    }

    if (lendDialogOpen) {
        var person by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { lendDialogOpen = false },
            title = { Text(stringResource(R.string.game_detail_lend)) },
            text = {
                OutlinedTextField(
                    value = person,
                    onValueChange = { person = it },
                    singleLine = true,
                    label = { Text(stringResource(R.string.players_name)) },
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.lend(person)
                        lendDialogOpen = false
                    },
                    enabled = person.isNotBlank(),
                ) { Text(stringResource(R.string.action_save)) }
            },
            dismissButton = {
                TextButton(onClick = { lendDialogOpen = false }) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
        )
    }
}

@Composable
private fun HeaderCard(game: GameEntity, state: GameDetailUiState) {
    Row(Modifier.padding(16.dp)) {
        GameThumbnail(path = game.thumbnailPath, title = game.title, size = 96.dp)
        Spacer(Modifier.width(16.dp))
        Column {
            Text(game.title, style = MaterialTheme.typography.titleLarge)
            game.yearPublished?.let {
                Text(
                    text = it.toString(),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                text = stringResource(game.status.labelRes()),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
            )
            if (game.minPlayers != null && game.maxPlayers != null) {
                Text(
                    text = stringResource(
                        R.string.unit_players_range, game.minPlayers, game.maxPlayers,
                    ),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            game.bestPlayerCount?.let {
                Text(
                    text = stringResource(R.string.game_detail_best_at, it),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (state.daysOnLoan != null && !game.inPossession) {
                Text(
                    text = stringResource(R.string.game_detail_lent_since, state.daysOnLoan.toInt()),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.tertiary,
                )
            }
        }
    }
}

@Composable
private fun StatsRow(state: GameDetailUiState) {
    val aggregates = state.aggregates ?: GameAggregates(0, 0, null, null, null, null, null, null, 0, 0)
    Column(Modifier.padding(horizontal = 16.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            StatTile(
                label = stringResource(R.string.game_detail_plays),
                value = aggregates.playCount.toString(),
                modifier = Modifier.weight(1f),
            )
            StatTile(
                label = stringResource(R.string.game_detail_hours),
                value = DurationFormat.hoursOneDecimal(aggregates.totalMinutes),
                modifier = Modifier.weight(1f),
            )
            StatTile(
                label = stringResource(R.string.game_detail_win_rate),
                value = if (aggregates.selfPlays > 0) {
                    "${aggregates.wins * 100 / aggregates.selfPlays}%"
                } else {
                    "—"
                },
                modifier = Modifier.weight(1f),
            )
        }
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            StatTile(
                label = stringResource(R.string.game_detail_avg_duration),
                value = aggregates.avgMinutes?.let { DurationFormat.minutes(it.toInt()) } ?: "—",
                supporting = state.game?.let { game ->
                    // Showing what BGG claims next to what actually happens is the point;
                    // the divergence is usually the more interesting number.
                    if (game.minPlaytimeMinutes != null && game.maxPlaytimeMinutes != null) {
                        stringResource(R.string.game_detail_stated_duration) +
                            " ${game.minPlaytimeMinutes}–${game.maxPlaytimeMinutes}m"
                    } else {
                        null
                    }
                },
                modifier = Modifier.weight(1f),
            )
            StatTile(
                label = stringResource(R.string.game_detail_cost_per_play),
                value = state.costPerPlay?.let {
                    String.format(Locale.getDefault(), "%.2f", it)
                } ?: "—",
                supporting = state.game?.currency,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun RatingSection(state: GameDetailUiState, onRate: () -> Unit) {
    Column(Modifier.padding(horizontal = 16.dp)) {
        val current = state.currentRating
        if (current == null) {
            Text(
                text = stringResource(R.string.game_detail_no_rating),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainer,
                ),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(Modifier.padding(16.dp)) {
                    Text(
                        text = stringResource(
                            R.string.rate_computed,
                            String.format(Locale.getDefault(), "%.1f", current.computedScore),
                        ),
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Text(
                        text = "${current.rubricName} · ${current.ratedOn}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(8.dp))
                    LinearProgressIndicator(
                        progress = { (current.computedScore / 10.0).toFloat() },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    current.notes?.let {
                        Spacer(Modifier.height(8.dp))
                        Text(it, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
            if (state.ratings.size > 1) {
                Spacer(Modifier.height(8.dp))
                Text(
                    text = stringResource(R.string.game_detail_rating_history),
                    style = MaterialTheme.typography.labelMedium,
                )
                state.ratings.drop(1).forEach { rating ->
                    Text(
                        text = "${rating.ratedOn} · ${
                            String.format(Locale.getDefault(), "%.1f", rating.computedScore)
                        } (${rating.rubricName})",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        OutlinedButton(onClick = onRate) { Text(stringResource(R.string.game_detail_rate)) }
    }
}

@Composable
private fun MetadataSection(game: GameEntity, designers: List<String>) {
    Column {
        game.weight?.let {
            KeyValueRow(
                stringResource(R.string.game_detail_weight),
                String.format(Locale.getDefault(), "%.2f", it),
            )
        }
        game.bggRating?.let {
            KeyValueRow(
                stringResource(R.string.game_detail_bgg_rating),
                String.format(Locale.getDefault(), "%.1f", it),
            )
        }
        designers.takeIf { it.isNotEmpty() }?.let {
            KeyValueRow(stringResource(R.string.game_detail_designers), it.joinToString(", "))
        }
        game.publisher?.let { KeyValueRow(stringResource(R.string.game_detail_publisher), it) }
        game.price?.let {
            KeyValueRow(
                stringResource(R.string.game_edit_price),
                stringResource(
                    R.string.unit_money,
                    game.currency,
                    String.format(Locale.getDefault(), "%.2f", it),
                ),
            )
        }
        KeyValueRow(stringResource(R.string.game_edit_date_added), game.dateAdded)
        game.purchaseNote?.let {
            KeyValueRow(stringResource(R.string.game_edit_purchase_note), it)
        }
        game.notes?.let { KeyValueRow(stringResource(R.string.game_detail_notes), it) }
    }
}

@Composable
private fun LendSection(
    game: GameEntity,
    state: GameDetailUiState,
    onLend: () -> Unit,
    onReturn: () -> Unit,
) {
    HorizontalDivider(Modifier.padding(vertical = 8.dp))
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            if (game.inPossession) {
                Text(
                    text = stringResource(R.string.collection_filter_in_possession),
                    style = MaterialTheme.typography.bodyMedium,
                )
            } else {
                Text(
                    text = stringResource(R.string.game_detail_lent_to, game.lentTo.orEmpty()),
                    style = MaterialTheme.typography.bodyMedium,
                )
                state.daysOnLoan?.let {
                    Text(
                        text = stringResource(R.string.game_detail_lent_since, it.toInt()),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        if (game.inPossession) {
            OutlinedButton(onClick = onLend) { Text(stringResource(R.string.game_detail_lend)) }
        } else {
            OutlinedButton(onClick = onReturn) { Text(stringResource(R.string.game_detail_return)) }
        }
    }
}

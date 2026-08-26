package com.boardgamenation.tracker.ui.sessions

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
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
import com.boardgamenation.tracker.data.db.projection.SessionListItem
import com.boardgamenation.tracker.ui.components.EmptyState
import com.boardgamenation.tracker.ui.components.GameThumbnail
import com.boardgamenation.tracker.ui.components.LoadingRows

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SessionListScreen(
    onOpenSession: (Long) -> Unit,
    onNewSession: () -> Unit,
    viewModel: SessionListViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    Scaffold(
        topBar = { TopAppBar(title = { Text(stringResource(R.string.sessions_title)) }) },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = onNewSession,
                icon = { Icon(Icons.Filled.Add, contentDescription = null) },
                text = { Text(stringResource(R.string.session_edit_new)) },
            )
        },
    ) { padding ->
        Column(Modifier.padding(padding)) {
            FilterRow(
                state = state,
                onGame = viewModel::setGame,
                onPlayer = viewModel::setPlayer,
                onClear = viewModel::clearFilters,
            )

            when {
                state.isLoading -> LoadingRows()
                state.sessions.isEmpty() -> EmptyState(
                    title = stringResource(
                        if (state.filter.gameId != null || state.filter.playerId != null) {
                            R.string.sessions_empty_filtered
                        } else {
                            R.string.sessions_empty
                        },
                    ),
                    icon = Icons.AutoMirrored.Filled.List,
                )

                else -> LazyColumn(
                    contentPadding = PaddingValues(bottom = 96.dp),
                ) {
                    items(state.sessions, key = { it.id }) { session ->
                        SessionRow(
                            session = session,
                            showGameTitle = true,
                            onClick = { onOpenSession(session.id) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun FilterRow(
    state: SessionListUiState,
    onGame: (Long?) -> Unit,
    onPlayer: (Long?) -> Unit,
    onClear: () -> Unit,
) {
    var gameMenu by remember { mutableStateOf(false) }
    var playerMenu by remember { mutableStateOf(false) }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 12.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val filteredGame = state.games.firstOrNull { it.id == state.filter.gameId }
        val filteredPlayer = state.players.firstOrNull { it.id == state.filter.playerId }

        if (state.filter.gameId != null || state.filter.playerId != null) {
            FilterChip(
                selected = true,
                onClick = onClear,
                label = { Text(stringResource(R.string.action_clear)) },
                leadingIcon = { Icon(Icons.Filled.Close, contentDescription = null) },
            )
        }

        Box {
            FilterChip(
                selected = filteredGame != null,
                onClick = { gameMenu = true },
                label = {
                    Text(filteredGame?.title ?: stringResource(R.string.session_filter_any_game))
                },
            )
            DropdownMenu(expanded = gameMenu, onDismissRequest = { gameMenu = false }) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.session_filter_any_game)) },
                    onClick = {
                        onGame(null)
                        gameMenu = false
                    },
                )
                state.games.forEach { game ->
                    DropdownMenuItem(
                        text = { Text(game.title) },
                        onClick = {
                            onGame(game.id)
                            gameMenu = false
                        },
                    )
                }
            }
        }

        Box {
            FilterChip(
                selected = filteredPlayer != null,
                onClick = { playerMenu = true },
                label = {
                    Text(filteredPlayer?.name ?: stringResource(R.string.session_filter_any_player))
                },
            )
            DropdownMenu(expanded = playerMenu, onDismissRequest = { playerMenu = false }) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.session_filter_any_player)) },
                    onClick = {
                        onPlayer(null)
                        playerMenu = false
                    },
                )
                state.players.forEach { player ->
                    DropdownMenuItem(
                        text = { Text(player.name) },
                        onClick = {
                            onPlayer(player.id)
                            playerMenu = false
                        },
                    )
                }
            }
        }
    }
}

/** Shared by the session list, the dashboard, and a game's play history. */
@Composable
fun SessionRow(
    session: SessionListItem,
    showGameTitle: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        ),
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 3.dp)
            .clickable(onClick = onClick),
    ) {
        Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
            if (showGameTitle) {
                GameThumbnail(
                    path = session.thumbnailPath,
                    title = session.gameTitle,
                    size = 44.dp,
                )
                Spacer(Modifier.width(12.dp))
            }
            Column(Modifier.weight(1f)) {
                if (showGameTitle) {
                    Text(
                        text = session.gameTitle,
                        style = MaterialTheme.typography.titleSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Text(
                    text = "${session.playedOn} · ${DurationFormat.minutes(session.durationMinutes)}" +
                        " · ${stringResource(R.string.session_players_count, session.playerCount)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                val outcome = when {
                    session.isCooperative && session.coopWon ->
                        stringResource(R.string.session_coop_win)
                    session.isCooperative -> stringResource(R.string.session_coop_loss)
                    !session.winnerNames.isNullOrBlank() ->
                        stringResource(R.string.session_winner, session.winnerNames)
                    else -> null
                }
                outcome?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (session.isIncomplete) {
                        Text(
                            text = stringResource(R.string.session_incomplete),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (session.isTeachingGame) {
                        Text(
                            text = stringResource(R.string.session_teaching),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

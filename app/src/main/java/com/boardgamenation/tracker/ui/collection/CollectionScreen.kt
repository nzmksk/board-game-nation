package com.boardgamenation.tracker.ui.collection

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.automirrored.filled.ViewList
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Casino
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SearchBar
import androidx.compose.material3.SearchBarDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.boardgamenation.tracker.R
import com.boardgamenation.tracker.core.time.DurationFormat
import com.boardgamenation.tracker.data.db.projection.GameListItem
import com.boardgamenation.tracker.domain.model.CollectionLayout
import com.boardgamenation.tracker.domain.model.CollectionSort
import com.boardgamenation.tracker.domain.model.GameStatus
import com.boardgamenation.tracker.domain.model.PlaytimeBucket
import com.boardgamenation.tracker.domain.model.TagKind
import com.boardgamenation.tracker.ui.components.ConfirmDialog
import com.boardgamenation.tracker.ui.components.EmptyState
import com.boardgamenation.tracker.ui.components.GameThumbnail
import com.boardgamenation.tracker.ui.components.LoadingRows
import com.boardgamenation.tracker.ui.components.currentLocale

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun CollectionScreen(onOpenGame: (Long) -> Unit, onAddGame: () -> Unit, viewModel: CollectionViewModel = hiltViewModel()) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    var searchActive by remember { mutableStateOf(false) }
    var sortMenuOpen by remember { mutableStateOf(false) }
    var tagPickerOpen by remember { mutableStateOf(false) }
    var bulkDeleteOpen by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            if (state.inSelectionMode) {
                SelectionTopBar(
                    count = state.selection.size,
                    onClear = viewModel::clearSelection,
                    onSelectAll = viewModel::selectAll,
                    onSetStatus = viewModel::bulkSetStatus,
                    onAddTag = { tagPickerOpen = true },
                    onDelete = { bulkDeleteOpen = true }
                )
            } else {
                TopAppBar(
                    title = { Text(stringResource(R.string.collection_title)) },
                    actions = {
                        IconButton(onClick = viewModel::toggleLayout) {
                            Icon(
                                imageVector = if (state.layout == CollectionLayout.LIST) {
                                    Icons.Filled.GridView
                                } else {
                                    Icons.AutoMirrored.Filled.ViewList
                                },
                                contentDescription = stringResource(
                                    if (state.layout == CollectionLayout.LIST) {
                                        R.string.collection_layout_grid
                                    } else {
                                        R.string.collection_layout_list
                                    }
                                )
                            )
                        }
                        Box {
                            IconButton(onClick = { sortMenuOpen = true }) {
                                Icon(
                                    Icons.AutoMirrored.Filled.Sort,
                                    contentDescription = stringResource(R.string.action_sort)
                                )
                            }
                            SortMenu(
                                expanded = sortMenuOpen,
                                current = state.filter.sort,
                                ascending = state.filter.ascending,
                                onDismiss = { sortMenuOpen = false },
                                onSelect = {
                                    viewModel.setSort(it)
                                    sortMenuOpen = false
                                }
                            )
                        }
                    }
                )
            }
        },
        floatingActionButton = {
            if (!state.inSelectionMode) {
                ExtendedFloatingActionButton(
                    onClick = onAddGame,
                    icon = { Icon(Icons.Filled.Add, contentDescription = null) },
                    text = { Text(stringResource(R.string.collection_add_game)) }
                )
            }
        }
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            SearchBar(
                inputField = {
                    SearchBarDefaults.InputField(
                        query = state.filter.search,
                        onQueryChange = viewModel::onSearchChange,
                        onSearch = { searchActive = false },
                        expanded = false,
                        onExpandedChange = { searchActive = it },
                        placeholder = { Text(stringResource(R.string.collection_search_hint)) },
                        trailingIcon = {
                            if (state.filter.search.isNotEmpty()) {
                                IconButton(onClick = { viewModel.onSearchChange("") }) {
                                    Icon(
                                        Icons.Filled.Close,
                                        contentDescription = stringResource(R.string.action_clear)
                                    )
                                }
                            }
                        }
                    )
                },
                expanded = false,
                onExpandedChange = { searchActive = it },
                windowInsets = WindowInsets(0, 0, 0, 0),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 4.dp)
            ) {}

            FilterChipRow(
                state = state,
                onToggleStatus = viewModel::toggleStatus,
                onPlayerCount = viewModel::setPlayerCount,
                onPlaytime = viewModel::setPlaytime,
                onToggleTag = viewModel::toggleTag,
                onRated = viewModel::setRated,
                onInPossession = viewModel::toggleInPossession,
                onExpansions = viewModel::toggleExpansions,
                onClear = viewModel::clearFilters
            )

            Text(
                text = pluralStringResource(
                    R.plurals.collection_count,
                    state.games.size,
                    state.games.size
                ),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
            )

            when {
                state.isLoading -> LoadingRows()

                state.games.isEmpty() && state.filter.isActive -> EmptyState(
                    title = stringResource(R.string.collection_empty_filtered),
                    icon = Icons.Filled.Casino
                )

                state.games.isEmpty() -> EmptyState(
                    title = stringResource(R.string.collection_empty),
                    body = stringResource(R.string.collection_empty_body),
                    icon = Icons.Filled.Casino
                )

                state.layout == CollectionLayout.LIST -> GameList(
                    games = state.games,
                    selection = state.selection,
                    onOpen = onOpenGame,
                    onToggleSelect = viewModel::toggleSelection
                )

                else -> GameGrid(
                    games = state.games,
                    selection = state.selection,
                    onOpen = onOpenGame,
                    onToggleSelect = viewModel::toggleSelection
                )
            }
        }
    }

    if (tagPickerOpen) {
        AlertDialog(
            onDismissRequest = { tagPickerOpen = false },
            title = { Text(stringResource(R.string.collection_bulk_tag)) },
            text = {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    state.tags.forEach { tag ->
                        FilterChip(
                            selected = false,
                            onClick = {
                                viewModel.bulkAddTag(tag.id)
                                tagPickerOpen = false
                            },
                            label = { Text(tag.name) }
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { tagPickerOpen = false }) {
                    Text(stringResource(R.string.action_cancel))
                }
            }
        )
    }

    if (bulkDeleteOpen) {
        // Bulk delete cascades every play of every selected game, so it asks first.
        ConfirmDialog(
            title = stringResource(R.string.collection_bulk_delete),
            body = pluralStringResource(
                R.plurals.collection_bulk_delete_body,
                state.selection.size,
                state.selection.size
            ),
            confirmLabel = stringResource(R.string.action_delete),
            destructive = true,
            onConfirm = {
                bulkDeleteOpen = false
                viewModel.bulkDelete()
            },
            onDismiss = { bulkDeleteOpen = false }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SelectionTopBar(
    count: Int,
    onClear: () -> Unit,
    onSelectAll: () -> Unit,
    onSetStatus: (GameStatus) -> Unit,
    onAddTag: () -> Unit,
    onDelete: () -> Unit
) {
    var menuOpen by remember { mutableStateOf(false) }
    TopAppBar(
        title = { Text(pluralStringResource(R.plurals.collection_selected, count, count)) },
        navigationIcon = {
            IconButton(onClick = onClear) {
                Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.action_close))
            }
        },
        actions = {
            IconButton(onClick = onSelectAll) {
                Icon(
                    Icons.Filled.Check,
                    contentDescription = stringResource(R.string.action_select_all)
                )
            }
            Box {
                IconButton(onClick = { menuOpen = true }) {
                    Icon(
                        Icons.AutoMirrored.Filled.Sort,
                        contentDescription = stringResource(R.string.action_more_options)
                    )
                }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    GameStatus.entries.forEach { status ->
                        DropdownMenuItem(
                            text = { Text(stringResource(status.labelRes())) },
                            onClick = {
                                onSetStatus(status)
                                menuOpen = false
                            }
                        )
                    }
                    HorizontalDivider()
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.collection_bulk_tag)) },
                        onClick = {
                            onAddTag()
                            menuOpen = false
                        }
                    )
                    DropdownMenuItem(
                        text = {
                            Text(
                                text = stringResource(R.string.collection_bulk_delete),
                                color = MaterialTheme.colorScheme.error
                            )
                        },
                        onClick = {
                            onDelete()
                            menuOpen = false
                        }
                    )
                }
            }
        }
    )
}

@Composable
private fun SortMenu(
    expanded: Boolean,
    current: CollectionSort,
    ascending: Boolean,
    onDismiss: () -> Unit,
    onSelect: (CollectionSort) -> Unit
) {
    DropdownMenu(expanded = expanded, onDismissRequest = onDismiss) {
        CollectionSort.entries.forEach { sort ->
            DropdownMenuItem(
                text = {
                    Text(
                        text = stringResource(sort.labelRes()),
                        fontWeight = if (sort == current) FontWeight.Bold else FontWeight.Normal
                    )
                },
                trailingIcon = {
                    if (sort == current) {
                        Text(if (ascending) "↑" else "↓")
                    }
                },
                onClick = { onSelect(sort) }
            )
        }
    }
}

@Composable
private fun FilterChipRow(
    state: CollectionUiState,
    onToggleStatus: (GameStatus) -> Unit,
    onPlayerCount: (Int?) -> Unit,
    onPlaytime: (PlaytimeBucket?) -> Unit,
    onToggleTag: (Long) -> Unit,
    onRated: (Boolean?) -> Unit,
    onInPossession: () -> Unit,
    onExpansions: () -> Unit,
    onClear: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (state.filter.activeCount > 0) {
            FilterChip(
                selected = true,
                onClick = onClear,
                label = {
                    Text(stringResource(R.string.collection_filter_clear, state.filter.activeCount))
                },
                leadingIcon = { Icon(Icons.Filled.Close, contentDescription = null) }
            )
        }

        GameStatus.entries.forEach { status ->
            FilterChip(
                selected = status in state.filter.statuses,
                onClick = { onToggleStatus(status) },
                label = { Text(stringResource(status.labelRes())) }
            )
        }

        (2..6).forEach { count ->
            FilterChip(
                selected = state.filter.playerCount == count,
                onClick = { onPlayerCount(count) },
                label = { Text(pluralStringResource(R.plurals.unit_players, count, count)) }
            )
        }

        PlaytimeBucket.entries.forEach { bucket ->
            FilterChip(
                selected = state.filter.playtime == bucket,
                onClick = { onPlaytime(bucket) },
                label = { Text(stringResource(bucket.labelRes())) }
            )
        }

        FilterChip(
            selected = state.filter.rated == true,
            onClick = { onRated(true) },
            label = { Text(stringResource(R.string.collection_filter_rated)) }
        )
        FilterChip(
            selected = state.filter.rated == false,
            onClick = { onRated(false) },
            label = { Text(stringResource(R.string.collection_filter_unrated)) }
        )
        FilterChip(
            selected = state.filter.inPossessionOnly,
            onClick = onInPossession,
            label = { Text(stringResource(R.string.collection_filter_in_possession)) }
        )
        FilterChip(
            selected = !state.filter.includeExpansions,
            onClick = onExpansions,
            label = { Text(stringResource(R.string.collection_filter_hide_expansions)) }
        )

        // Capped per kind rather than over one flat list. Designers moved into the same
        // table as mechanics and categories, and a single take(20) would let a collection
        // with a lot of designers push every mechanic off the end of the row. Every kind
        // is walked, not just the curated three, so a CUSTOM tag -- which is what an
        // unrecognised kind restored from CSV falls back to -- stays selectable here.
        TagKind.entries.forEach { kind ->
            state.tags.filter { it.kind == kind }.take(TAG_CHIPS_PER_KIND).forEach { tag ->
                FilterChip(
                    selected = tag.id in state.filter.tagIds,
                    onClick = { onToggleTag(tag.id) },
                    label = { Text(tag.name) }
                )
            }
        }
    }
}

@Composable
private fun GameList(games: List<GameListItem>, selection: Set<Long>, onOpen: (Long) -> Unit, onToggleSelect: (Long) -> Unit) {
    LazyColumn(
        contentPadding = androidx.compose.foundation.layout.PaddingValues(
            start = 12.dp,
            end = 12.dp,
            top = 4.dp,
            bottom = 96.dp
        ),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        // Keyed so recomposition survives reordering, which is what keeps a 500-row list
        // smooth when the sort changes.
        items(games, key = { it.id }) { game ->
            GameRow(
                game = game,
                selected = game.id in selection,
                onOpen = { onOpen(game.id) },
                onToggleSelect = { onToggleSelect(game.id) }
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun GameRow(game: GameListItem, selected: Boolean, onOpen: () -> Unit, onToggleSelect: () -> Unit) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = if (selected) {
                MaterialTheme.colorScheme.secondaryContainer
            } else {
                MaterialTheme.colorScheme.surfaceContainerLow
            }
        ),
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(onClick = onOpen, onLongClick = onToggleSelect)
    ) {
        Row(
            modifier = Modifier.padding(10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            GameThumbnail(path = game.thumbnailPath, title = game.title)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = game.title,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = game.subtitle(),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (game.lentTo != null) {
                    Text(
                        text = stringResource(R.string.game_detail_lent_to, game.lentTo),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.tertiary
                    )
                }
            }
            Spacer(Modifier.width(8.dp))
            Column(horizontalAlignment = Alignment.End) {
                if (game.playCount > 0) {
                    Text(
                        text = pluralStringResource(
                            R.plurals.stats_plays_value,
                            game.playCount,
                            game.playCount
                        ),
                        style = MaterialTheme.typography.labelMedium
                    )
                }
                game.rating?.let {
                    Text(
                        text = String.format(currentLocale(), "%.1f", it),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }
        }
    }
}

@Composable
private fun GameGrid(games: List<GameListItem>, selection: Set<Long>, onOpen: (Long) -> Unit, onToggleSelect: (Long) -> Unit) {
    LazyVerticalGrid(
        columns = GridCells.Adaptive(minSize = 112.dp),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(
            start = 12.dp,
            end = 12.dp,
            top = 4.dp,
            bottom = 96.dp
        ),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        items(games, key = { it.id }) { game ->
            GameTile(
                game = game,
                selected = game.id in selection,
                onOpen = { onOpen(game.id) },
                onToggleSelect = { onToggleSelect(game.id) }
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun GameTile(game: GameListItem, selected: Boolean, onOpen: () -> Unit, onToggleSelect: () -> Unit) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = if (selected) {
                MaterialTheme.colorScheme.secondaryContainer
            } else {
                MaterialTheme.colorScheme.surfaceContainerLow
            }
        ),
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(onClick = onOpen, onLongClick = onToggleSelect)
    ) {
        Column(Modifier.padding(8.dp)) {
            GameThumbnail(
                path = game.thumbnailPath,
                title = game.title,
                size = 96.dp,
                modifier = Modifier.align(Alignment.CenterHorizontally)
            )
            Spacer(Modifier.height(6.dp))
            // Two lines whether the title needs them or not. A grid row is only as tall as
            // its tallest tile, so letting a one-line title shrink its own card left the
            // row ragged -- one short card sitting beside a tall one.
            Text(
                text = game.title,
                style = MaterialTheme.typography.labelLarge,
                minLines = TITLE_LINES,
                maxLines = TITLE_LINES,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = if (game.playCount > 0) {
                    pluralStringResource(R.plurals.stats_plays_value, game.playCount, game.playCount)
                } else {
                    stringResource(R.string.game_detail_never_played)
                },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun GameListItem.subtitle(): String {
    val parts = buildList {
        if (minPlayers != null && maxPlayers != null) {
            add(
                if (minPlayers == maxPlayers) {
                    pluralStringResource(R.plurals.unit_players, minPlayers, minPlayers)
                } else {
                    pluralStringResource(
                        R.plurals.unit_players_range,
                        maxPlayers,
                        minPlayers,
                        maxPlayers
                    )
                }
            )
        }
        maxPlaytimeMinutes?.let { add(DurationFormat.minutes(it)) }
        weight?.let { add(stringResource(R.string.unit_weight, String.format(currentLocale(), "%.1f", it))) }
    }
    return parts.joinToString(" · ").ifEmpty { stringResource(status.labelRes()) }
}

internal fun GameStatus.labelRes(): Int = when (this) {
    GameStatus.OWNED -> R.string.status_owned
    GameStatus.WISHLIST -> R.string.status_wishlist
    GameStatus.PREORDERED -> R.string.status_preordered
    GameStatus.SOLD -> R.string.status_sold
    GameStatus.LENT_OUT -> R.string.status_lent_out
}

internal fun CollectionSort.labelRes(): Int = when (this) {
    CollectionSort.TITLE -> R.string.collection_sort_title
    CollectionSort.DATE_ADDED -> R.string.collection_sort_date_added
    CollectionSort.PLAY_COUNT -> R.string.collection_sort_play_count
    CollectionSort.RATING -> R.string.collection_sort_rating
    CollectionSort.PRICE -> R.string.collection_sort_price
    CollectionSort.COST_PER_PLAY -> R.string.collection_sort_cost_per_play
    CollectionSort.LAST_PLAYED -> R.string.collection_sort_last_played
    CollectionSort.WEIGHT -> R.string.collection_sort_weight
}

internal fun PlaytimeBucket.labelRes(): Int = when (this) {
    PlaytimeBucket.UNDER_30 -> R.string.collection_playtime_under_30
    PlaytimeBucket.THIRTY_TO_60 -> R.string.collection_playtime_30_60
    PlaytimeBucket.SIXTY_TO_120 -> R.string.collection_playtime_60_120
    PlaytimeBucket.OVER_120 -> R.string.collection_playtime_over_120
}

/** Per-kind cap on the filter row, so no one kind can crowd out the others. */
private const val TAG_CHIPS_PER_KIND = 8

/** Lines a grid tile reserves for the title, so every tile in a row is the same height. */
private const val TITLE_LINES = 2

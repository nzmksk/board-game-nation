package com.boardgamenation.tracker.ui.gameedit

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.InputChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.boardgamenation.tracker.R
import com.boardgamenation.tracker.domain.model.GameStatus
import com.boardgamenation.tracker.domain.model.ScoringMode
import com.boardgamenation.tracker.domain.model.TagKind
import com.boardgamenation.tracker.ui.collection.labelRes
import com.boardgamenation.tracker.ui.components.IsoDateField
import com.boardgamenation.tracker.ui.components.SectionHeader

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun GameEditScreen(
    onBack: () -> Unit,
    onSaved: (Long) -> Unit,
    onSearchBgg: () -> Unit,
    bggEnabled: Boolean,
    viewModel: GameEditViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val saved by viewModel.saved.collectAsStateWithLifecycle()

    LaunchedEffect(saved) { saved?.let(onSaved) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        stringResource(
                            if (state.isNew) {
                                R.string.game_edit_new_title
                            } else {
                                R.string.game_edit_edit_title
                            }
                        )
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.action_back)
                        )
                    }
                },
                actions = {
                    if (state.isNew && bggEnabled) {
                        IconButton(onClick = onSearchBgg) {
                            Icon(
                                Icons.Filled.Search,
                                contentDescription = stringResource(R.string.game_edit_search_bgg)
                            )
                        }
                    }
                    Button(onClick = viewModel::save, enabled = state.canSave) {
                        Text(stringResource(R.string.action_save))
                    }
                }
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier.padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                OutlinedTextField(
                    value = state.title,
                    onValueChange = { value -> viewModel.update { it.copy(title = value) } },
                    label = { Text(stringResource(R.string.game_edit_title_label)) },
                    isError = state.titleError,
                    supportingText = if (state.titleError) {
                        { Text(stringResource(R.string.game_edit_title_required)) }
                    } else {
                        null
                    },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }

            item {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    NumberField(
                        value = state.yearPublished,
                        label = stringResource(R.string.game_edit_year),
                        onChange = { value -> viewModel.update { it.copy(yearPublished = value) } },
                        modifier = Modifier.weight(1f)
                    )
                    NumberField(
                        value = state.weight,
                        label = stringResource(R.string.game_edit_weight),
                        decimal = true,
                        onChange = { value -> viewModel.update { it.copy(weight = value) } },
                        modifier = Modifier.weight(1f)
                    )
                }
            }

            item {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    NumberField(
                        value = state.minPlayers,
                        label = stringResource(R.string.game_edit_min_players),
                        onChange = { value -> viewModel.update { it.copy(minPlayers = value) } },
                        modifier = Modifier.weight(1f)
                    )
                    NumberField(
                        value = state.maxPlayers,
                        label = stringResource(R.string.game_edit_max_players),
                        onChange = { value -> viewModel.update { it.copy(maxPlayers = value) } },
                        modifier = Modifier.weight(1f)
                    )
                    OutlinedTextField(
                        value = state.bestPlayerCount,
                        onValueChange = { v -> viewModel.update { it.copy(bestPlayerCount = v) } },
                        label = { Text(stringResource(R.string.game_edit_best_players)) },
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                }
            }

            item {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    NumberField(
                        value = state.minPlaytime,
                        label = stringResource(R.string.game_edit_min_playtime),
                        onChange = { value -> viewModel.update { it.copy(minPlaytime = value) } },
                        modifier = Modifier.weight(1f)
                    )
                    NumberField(
                        value = state.maxPlaytime,
                        label = stringResource(R.string.game_edit_max_playtime),
                        onChange = { value -> viewModel.update { it.copy(maxPlaytime = value) } },
                        modifier = Modifier.weight(1f)
                    )
                }
            }

            item {
                OutlinedTextField(
                    value = state.publisher,
                    onValueChange = { v -> viewModel.update { it.copy(publisher = v) } },
                    label = { Text(stringResource(R.string.game_edit_publisher)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }

            item { SectionHeader(stringResource(R.string.game_detail_purchase)) }

            item {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    IsoDateField(
                        value = state.dateAdded,
                        label = stringResource(R.string.game_edit_date_added),
                        onChange = { v -> viewModel.update { it.copy(dateAdded = v) } },
                        modifier = Modifier.weight(1.4f)
                    )
                    NumberField(
                        value = state.price,
                        label = stringResource(R.string.game_edit_price),
                        decimal = true,
                        onChange = { v -> viewModel.update { it.copy(price = v) } },
                        modifier = Modifier.weight(1f)
                    )
                    OutlinedTextField(
                        value = state.currency,
                        onValueChange = { v -> viewModel.update { it.copy(currency = v) } },
                        label = { Text(stringResource(R.string.game_edit_currency)) },
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                }
            }

            item {
                OutlinedTextField(
                    value = state.purchaseNote,
                    onValueChange = { v -> viewModel.update { it.copy(purchaseNote = v) } },
                    label = { Text(stringResource(R.string.game_edit_purchase_note)) },
                    modifier = Modifier.fillMaxWidth()
                )
            }

            item { SectionHeader(stringResource(R.string.game_edit_status)) }

            item {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    GameStatus.entries.forEach { status ->
                        FilterChip(
                            selected = state.status == status,
                            onClick = { viewModel.update { it.copy(status = status) } },
                            label = { Text(stringResource(status.labelRes())) }
                        )
                    }
                }
            }

            if (state.status == GameStatus.WISHLIST) {
                item {
                    Column {
                        Text(
                            text = stringResource(R.string.game_edit_wishlist_priority),
                            style = MaterialTheme.typography.labelLarge
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            (1..5).forEach { priority ->
                                FilterChip(
                                    selected = state.wishlistPriority == priority,
                                    onClick = {
                                        viewModel.update { it.copy(wishlistPriority = priority) }
                                    },
                                    label = { Text(priority.toString()) }
                                )
                            }
                        }
                    }
                }
            }

            item { SectionHeader(stringResource(R.string.game_edit_scoring_mode)) }

            item {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ScoringMode.entries.forEach { mode ->
                        FilterChip(
                            selected = state.scoringMode == mode,
                            onClick = { viewModel.update { it.copy(scoringMode = mode) } },
                            label = { Text(stringResource(mode.labelRes())) }
                        )
                    }
                }
            }

            if (state.scoringMode == ScoringMode.RANKED_SCORES) {
                item {
                    ToggleRow(
                        label = stringResource(
                            if (state.highScoreWins) {
                                R.string.game_edit_high_score_wins
                            } else {
                                R.string.game_edit_low_score_wins
                            }
                        ),
                        checked = state.highScoreWins,
                        onChange = { v -> viewModel.update { it.copy(highScoreWins = v) } }
                    )
                }
            }

            item {
                ToggleRow(
                    label = stringResource(R.string.game_edit_is_expansion),
                    checked = state.isExpansion,
                    onChange = { v -> viewModel.update { it.copy(isExpansion = v) } }
                )
            }

            if (state.isExpansion) {
                item {
                    BaseGamePicker(
                        state = state,
                        onSelect = { id -> viewModel.update { it.copy(baseGameId = id) } }
                    )
                }
            }

            item { SectionHeader(stringResource(R.string.game_edit_tags)) }

            item {
                TagEditor(
                    label = stringResource(R.string.collection_filter_mechanic),
                    tags = state.mechanics,
                    onAdd = { viewModel.addTag(TagKind.MECHANIC, it) },
                    onRemove = { viewModel.removeTag(TagKind.MECHANIC, it) }
                )
            }

            item {
                TagEditor(
                    label = stringResource(R.string.collection_filter_category),
                    tags = state.categories,
                    onAdd = { viewModel.addTag(TagKind.CATEGORY, it) },
                    onRemove = { viewModel.removeTag(TagKind.CATEGORY, it) }
                )
            }

            item {
                TagEditor(
                    label = stringResource(R.string.game_edit_designers),
                    tags = state.designers,
                    onAdd = { viewModel.addTag(TagKind.DESIGNER, it) },
                    onRemove = { viewModel.removeTag(TagKind.DESIGNER, it) }
                )
            }

            item {
                OutlinedTextField(
                    value = state.notes,
                    onValueChange = { v -> viewModel.update { it.copy(notes = v) } },
                    label = { Text(stringResource(R.string.game_edit_notes)) },
                    minLines = 3,
                    modifier = Modifier.fillMaxWidth()
                )
            }

            item { Spacer(Modifier.height(48.dp)) }
        }
    }
}

@Composable
private fun NumberField(value: String, label: String, onChange: (String) -> Unit, modifier: Modifier = Modifier, decimal: Boolean = false) {
    OutlinedTextField(
        value = value,
        onValueChange = { input ->
            // Filtering here rather than validating on save keeps the field honest while
            // still allowing an empty or half-typed value.
            val allowed = input.filter { it.isDigit() || (decimal && it == '.') }
            onChange(allowed)
        },
        label = { Text(label) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(
            keyboardType = if (decimal) KeyboardType.Decimal else KeyboardType.Number
        ),
        modifier = modifier
    )
}

@Composable
private fun ToggleRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BaseGamePicker(state: GameEditState, onSelect: (Long?) -> Unit) {
    var open by remember { mutableStateOf(false) }
    val selected = state.baseGameOptions.firstOrNull { it.id == state.baseGameId }
    Box {
        OutlinedButton(onClick = { open = true }, modifier = Modifier.fillMaxWidth()) {
            Text(selected?.title ?: stringResource(R.string.game_edit_base_game_none))
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.game_edit_base_game_none)) },
                onClick = {
                    onSelect(null)
                    open = false
                }
            )
            state.baseGameOptions.forEach { game ->
                DropdownMenuItem(
                    text = { Text(game.title) },
                    onClick = {
                        onSelect(game.id)
                        open = false
                    }
                )
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TagEditor(label: String, tags: List<String>, onAdd: (String) -> Unit, onRemove: (String) -> Unit) {
    var input by remember { mutableStateOf("") }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(label, style = MaterialTheme.typography.labelLarge)
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = input,
                onValueChange = { input = it },
                label = { Text(stringResource(R.string.game_edit_tag_hint)) },
                singleLine = true,
                modifier = Modifier.weight(1f)
            )
            Spacer(Modifier.width(8.dp))
            IconButton(
                onClick = {
                    onAdd(input)
                    input = ""
                },
                enabled = input.isNotBlank()
            ) {
                Icon(Icons.Filled.Add, contentDescription = stringResource(R.string.action_add))
            }
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            tags.forEach { tag ->
                InputChip(
                    selected = false,
                    onClick = { onRemove(tag) },
                    label = { Text(tag) },
                    trailingIcon = {
                        Icon(
                            Icons.Filled.Close,
                            contentDescription = stringResource(R.string.action_delete)
                        )
                    }
                )
            }
        }
    }
}

internal fun ScoringMode.labelRes(): Int = when (this) {
    ScoringMode.RANKED_SCORES -> R.string.scoring_ranked
    ScoringMode.MANUAL_PLACEMENT -> R.string.scoring_manual
    ScoringMode.COOPERATIVE -> R.string.scoring_coop
    ScoringMode.TEAM_BASED -> R.string.scoring_teams
    ScoringMode.NONE -> R.string.scoring_none
}

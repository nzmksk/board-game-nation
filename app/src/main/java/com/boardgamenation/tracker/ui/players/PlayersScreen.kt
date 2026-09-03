package com.boardgamenation.tracker.ui.players

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
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
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.boardgamenation.tracker.R
import com.boardgamenation.tracker.data.db.entity.PlayerEntity
import com.boardgamenation.tracker.data.db.projection.PlayerRow
import com.boardgamenation.tracker.data.repository.DeletePlayerOutcome
import com.boardgamenation.tracker.data.repository.PlayerRepository
import com.boardgamenation.tracker.ui.components.EmptyState
import com.boardgamenation.tracker.ui.components.PlayerDot
import com.boardgamenation.tracker.ui.theme.LocalChartColors
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

@HiltViewModel
class PlayersViewModel @Inject constructor(private val repository: PlayerRepository) : ViewModel() {

    val players: StateFlow<List<PlayerRow>> = repository.observeWithCounts()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    fun add(name: String, colorHex: String?) {
        if (name.isBlank()) return
        viewModelScope.launch {
            runCatching { repository.add(name, colorHex) }
                .onFailure { _message.value = it.message }
        }
    }

    fun update(player: PlayerEntity) {
        viewModelScope.launch { repository.update(player) }
    }

    fun setSelf(id: Long) {
        viewModelScope.launch { repository.setSelf(id) }
    }

    fun setArchived(id: Long, archived: Boolean) {
        viewModelScope.launch { repository.setArchived(id, archived) }
    }

    /** Refuses rather than rewriting history; archiving is offered instead. */
    fun delete(player: PlayerEntity, onBlocked: (Int) -> Unit) {
        viewModelScope.launch {
            when (val outcome = repository.delete(player.id)) {
                is DeletePlayerOutcome.HasHistory -> onBlocked(outcome.appearances)
                DeletePlayerOutcome.Deleted -> Unit
            }
        }
    }

    fun clearMessage() {
        _message.value = null
    }
}

/** Why a delete was refused, carried as data rather than as a string to be re-parsed. */
private data class BlockedDelete(val name: String, val appearances: Int)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlayersScreen(onBack: () -> Unit, onOpenPlayer: (Long) -> Unit, viewModel: PlayersViewModel = hiltViewModel()) {
    val players by viewModel.players.collectAsStateWithLifecycle()
    var addOpen by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<PlayerEntity?>(null) }
    var blocked by remember { mutableStateOf<BlockedDelete?>(null) }
    val chartColors = LocalChartColors.current

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.players_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.action_back)
                        )
                    }
                }
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { addOpen = true }) {
                Icon(Icons.Filled.Add, contentDescription = stringResource(R.string.players_add))
            }
        }
    ) { padding ->
        if (players.isEmpty()) {
            EmptyState(
                title = stringResource(R.string.players_empty),
                modifier = Modifier.padding(padding)
            )
            return@Scaffold
        }

        LazyColumn(
            modifier = Modifier.padding(padding),
            contentPadding = PaddingValues(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            items(players.size, key = { players[it].player.id }) { index ->
                val row = players[index]
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainerLow
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onOpenPlayer(row.player.id) }
                ) {
                    Row(
                        Modifier.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        PlayerDot(
                            chartColors.forPlayer(row.player.colorHex, index),
                            size = 14.dp
                        )
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(
                                text = if (row.player.isSelf) {
                                    stringResource(R.string.players_name_self, row.player.name)
                                } else {
                                    row.player.name
                                },
                                style = MaterialTheme.typography.titleSmall
                            )
                            Text(
                                text = stringResource(
                                    R.string.players_stats,
                                    pluralStringResource(
                                        R.plurals.stats_plays_value,
                                        row.plays,
                                        row.plays
                                    ),
                                    pluralStringResource(R.plurals.players_wins, row.wins, row.wins)
                                ),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        TextButton(onClick = { editing = row.player }) {
                            Text(stringResource(R.string.action_edit))
                        }
                    }
                }
            }
        }
    }

    if (addOpen) {
        PlayerDialog(
            initial = null,
            onDismiss = { addOpen = false },
            onSave = { name, colour, _ ->
                viewModel.add(name, colour)
                addOpen = false
            },
            onDelete = null
        )
    }

    editing?.let { player ->
        PlayerDialog(
            initial = player,
            onDismiss = { editing = null },
            onSave = { name, colour, archived ->
                viewModel.update(
                    player.copy(name = name, colorHex = colour, archived = archived)
                )
                editing = null
            },
            onSetSelf = {
                viewModel.setSelf(player.id)
                editing = null
            },
            onDelete = {
                viewModel.delete(player) { appearances ->
                    blocked = BlockedDelete(player.name, appearances)
                }
                editing = null
            }
        )
    }

    blocked?.let { refusal ->
        AlertDialog(
            onDismissRequest = { blocked = null },
            title = { Text(stringResource(R.string.action_delete)) },
            text = {
                Text(
                    pluralStringResource(
                        R.plurals.players_cannot_delete,
                        refusal.appearances,
                        refusal.name,
                        refusal.appearances
                    )
                )
            },
            confirmButton = {
                TextButton(onClick = { blocked = null }) {
                    Text(stringResource(R.string.action_close))
                }
            }
        )
    }
}

@Composable
private fun PlayerDialog(
    initial: PlayerEntity?,
    onDismiss: () -> Unit,
    onSave: (String, String?, Boolean) -> Unit,
    onSetSelf: (() -> Unit)? = null,
    onDelete: (() -> Unit)?
) {
    var name by remember { mutableStateOf(initial?.name.orEmpty()) }
    var colour by remember { mutableStateOf(initial?.colorHex.orEmpty()) }
    var archived by remember { mutableStateOf(initial?.archived ?: false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(if (initial == null) R.string.players_add else R.string.action_edit)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(stringResource(R.string.players_name)) },
                    singleLine = true
                )
                OutlinedTextField(
                    value = colour,
                    onValueChange = { colour = it },
                    label = { Text(stringResource(R.string.players_colour)) },
                    placeholder = { Text(stringResource(R.string.players_colour_hint)) },
                    singleLine = true
                )
                if (initial != null) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = stringResource(R.string.players_archived),
                            modifier = Modifier.weight(1f)
                        )
                        Switch(checked = archived, onCheckedChange = { archived = it })
                    }
                    if (onSetSelf != null && !initial.isSelf) {
                        TextButton(onClick = onSetSelf) {
                            Text(stringResource(R.string.players_is_self))
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onSave(name.trim(), colour.trim().ifBlank { null }, archived) },
                enabled = name.isNotBlank()
            ) { Text(stringResource(R.string.action_save)) }
        },
        dismissButton = {
            Row {
                if (onDelete != null) {
                    TextButton(onClick = onDelete) {
                        Text(
                            text = stringResource(R.string.action_delete),
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                }
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
            }
        }
    )
}

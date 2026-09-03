package com.boardgamenation.tracker.ui.sessionedit

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.EmojiEvents
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.boardgamenation.tracker.R
import com.boardgamenation.tracker.core.time.DateUtils
import com.boardgamenation.tracker.domain.model.CoopOutcome
import com.boardgamenation.tracker.domain.model.ParticipantForm
import com.boardgamenation.tracker.domain.model.ScoringMode
import com.boardgamenation.tracker.domain.model.Seating
import com.boardgamenation.tracker.domain.model.SessionEndCondition
import com.boardgamenation.tracker.share.shareImageChooser
import com.boardgamenation.tracker.ui.components.ConfirmDialog
import com.boardgamenation.tracker.ui.components.IsoDateField
import com.boardgamenation.tracker.ui.components.PlayerDot
import com.boardgamenation.tracker.ui.components.SectionHeader
import com.boardgamenation.tracker.ui.gameedit.labelRes
import com.boardgamenation.tracker.ui.theme.LocalChartColors

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun SessionEditScreen(onBack: () -> Unit, onSaved: (Long, List<String>) -> Unit, viewModel: SessionEditViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var deleteOpen by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val snackbarHost = remember { SnackbarHostState() }

    // Resolved at composition: the events arrive on a coroutine, which has no
    // composable scope to read resources from.
    val chooserTitle = stringResource(R.string.share_chooser_title)
    val shareFailed = stringResource(R.string.share_failed)

    // The photo picker hands back a uri the app can only read while the permission
    // lasts, so the read grant is persisted before the uri is stored.
    val photoPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        runCatching {
            context.contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
        }
        viewModel.update { it.copy(photoUri = uri.toString()) }
    }

    LaunchedEffect(Unit) {
        viewModel.events.collect { event ->
            when (event) {
                is SessionEditEvent.Saved -> onSaved(event.sessionId, event.unlockedNames)

                SessionEditEvent.Deleted -> onBack()

                is SessionEditEvent.ShareReady -> context.startActivity(
                    shareImageChooser(event.image, event.label, chooserTitle)
                )

                // A snackbar rather than a dialog: nothing was lost, the picture simply
                // did not get made, and the play is still sitting there to try again on.
                SessionEditEvent.ShareFailed -> snackbarHost.showSnackbar(shareFailed)
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        stringResource(
                            if (state.isNew) {
                                R.string.session_edit_new
                            } else {
                                R.string.session_edit_existing
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
                    if (!state.isNew) {
                        IconButton(onClick = viewModel::share, enabled = !state.isSharing) {
                            Icon(
                                Icons.Filled.Share,
                                contentDescription = stringResource(R.string.action_share)
                            )
                        }
                        IconButton(onClick = { deleteOpen = true }) {
                            Icon(
                                Icons.Filled.Delete,
                                contentDescription = stringResource(R.string.action_delete)
                            )
                        }
                    }
                    Button(onClick = viewModel::save, enabled = !state.isSaving) {
                        Text(stringResource(R.string.action_save))
                    }
                }
            )
        },
        snackbarHost = { SnackbarHost(snackbarHost) }
    ) { padding ->
        LazyColumn(
            modifier = Modifier.padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            state.validationError?.let { messageRes ->
                item {
                    Text(
                        text = stringResource(messageRes),
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }

            item { GamePicker(state, viewModel::selectGame) }

            item {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    IsoDateField(
                        value = DateUtils.toIso(state.form.playedOn),
                        label = stringResource(R.string.session_edit_date),
                        onChange = { value ->
                            DateUtils.parseIsoOrNull(value)?.let { date ->
                                viewModel.update { it.copy(playedOn = date) }
                            }
                        },
                        modifier = Modifier.weight(1.3f)
                    )
                    OutlinedTextField(
                        value = state.form.durationMinutes.toString(),
                        onValueChange = { value ->
                            val minutes = value.filter { it.isDigit() }.toIntOrNull() ?: 0
                            viewModel.update { it.copy(durationMinutes = minutes) }
                        },
                        label = { Text(stringResource(R.string.session_edit_duration)) },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.weight(1f)
                    )
                }
            }

            item {
                OutlinedTextField(
                    value = state.form.location.orEmpty(),
                    onValueChange = { value -> viewModel.update { it.copy(location = value) } },
                    label = { Text(stringResource(R.string.session_edit_location)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }

            item { SectionHeader(stringResource(R.string.game_edit_scoring_mode)) }
            item {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ScoringMode.entries.forEach { mode ->
                        FilterChip(
                            selected = state.form.scoringMode == mode,
                            onClick = { viewModel.update { it.copy(scoringMode = mode) } },
                            label = { Text(stringResource(mode.labelRes())) }
                        )
                    }
                }
            }

            if (state.form.scoringMode == ScoringMode.COOPERATIVE) {
                item {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(
                            selected = state.form.coopOutcome == CoopOutcome.WIN,
                            onClick = { viewModel.setCoopOutcome(CoopOutcome.WIN) },
                            label = { Text(stringResource(R.string.session_edit_coop_win)) }
                        )
                        FilterChip(
                            selected = state.form.coopOutcome == CoopOutcome.LOSS,
                            onClick = { viewModel.setCoopOutcome(CoopOutcome.LOSS) },
                            label = { Text(stringResource(R.string.session_edit_coop_loss)) }
                        )
                    }
                }
            }

            // Every game is set up more than one way: a co-op's modules or level, but
            // equally Catan's scenario, Azul's board side, the expansions on the table.
            // The result alone does not say which of those was played.
            item {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = state.form.mode.orEmpty(),
                        onValueChange = { value ->
                            viewModel.update { it.copy(mode = value) }
                        },
                        label = { Text(stringResource(R.string.session_edit_mode)) },
                        placeholder = { Text(stringResource(R.string.session_edit_mode_hint)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    if (state.previousModes.isNotEmpty()) {
                        Text(
                            text = stringResource(R.string.session_edit_mode_previous),
                            style = MaterialTheme.typography.labelSmall
                        )
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            state.previousModes.forEach { mode ->
                                FilterChip(
                                    selected = state.form.mode == mode,
                                    onClick = { viewModel.update { it.copy(mode = mode) } },
                                    label = { Text(mode) }
                                )
                            }
                        }
                    }
                }
            }

            // Sides are named on the player cards below; this is only the result. The
            // chips are the sides on the form plus any this game has been played with
            // before, so a regular group never retypes "Liberals".
            if (state.form.isTeamBased) {
                item { SectionHeader(stringResource(R.string.session_edit_winning_team)) }
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        val teams = (state.form.teams + state.previousTeams)
                            .distinctBy { it.lowercase() }
                        if (teams.isEmpty()) {
                            Text(
                                text = stringResource(
                                    R.string.session_edit_winning_team_help
                                ),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        } else {
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                teams.forEach { team ->
                                    FilterChip(
                                        selected = state.form.winningTeam == team,
                                        onClick = { viewModel.setWinningTeam(team) },
                                        label = { Text(team) }
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // Asked of every play, whatever the scoring. Any game can be given up on,
            // and the games a rule can stop early are not a category the collection
            // sorts into up front -- a co-op is lost to an uncontrolled outbreak, a
            // hidden-role game is won the instant Hitler is elected, a scored game is
            // stopped by military supremacy.
            item { SectionHeader(stringResource(R.string.session_edit_ended_by)) }
            item {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SessionEndCondition.entries.forEach { condition ->
                        FilterChip(
                            selected = state.form.endCondition == condition,
                            onClick = { viewModel.setEndCondition(condition) },
                            label = { Text(stringResource(condition.labelRes())) }
                        )
                    }
                }
            }

            when (state.form.endCondition) {
                SessionEndCondition.SPECIFIC -> item {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            // Only a scored play loses its result to an early ending;
                            // every other mode still decides one the usual way.
                            text = stringResource(
                                if (state.form.ranksByOrder) {
                                    R.string.session_edit_ended_specific_help
                                } else {
                                    R.string.session_edit_ended_specific_help_other
                                }
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        OutlinedTextField(
                            value = state.form.endReason.orEmpty(),
                            onValueChange = { value ->
                                viewModel.update { it.copy(endReason = value) }
                            },
                            label = { Text(stringResource(R.string.session_edit_end_reason)) },
                            placeholder = {
                                Text(stringResource(R.string.session_edit_end_reason_hint))
                            },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                        if (state.previousEndReasons.isNotEmpty()) {
                            Text(
                                text = stringResource(R.string.session_edit_end_reason_previous),
                                style = MaterialTheme.typography.labelSmall
                            )
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                state.previousEndReasons.forEach { reason ->
                                    FilterChip(
                                        selected = state.form.endReason == reason,
                                        onClick = {
                                            viewModel.update { it.copy(endReason = reason) }
                                        },
                                        label = { Text(reason) }
                                    )
                                }
                            }
                        }
                    }
                }

                SessionEndCondition.ABANDONED -> item {
                    Text(
                        text = stringResource(R.string.session_edit_ended_abandoned_help),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                SessionEndCondition.STANDARD -> Unit
            }

            item { SectionHeader(stringResource(R.string.session_edit_players)) }
            item { PlayerPicker(state, viewModel) }

            items(state.form.participants.size) { index ->
                val participant = state.form.participants[index]
                ParticipantCard(
                    participant = participant,
                    index = index,
                    mode = state.form.scoringMode,
                    // A play a rule stopped is ranked by the order the user puts the
                    // players in, but any partial score they enter is still kept.
                    showOrdering = state.form.scoringMode == ScoringMode.MANUAL_PLACEMENT ||
                        state.form.ranksByOrder,
                    showScore = state.form.scoringMode.recordsScores,
                    showTeam = state.form.isTeamBased,
                    previousTeams = state.previousTeams,
                    onScore = { score ->
                        viewModel.updateParticipant(participant.playerId) { it.copy(score = score) }
                    },
                    onFaction = { faction ->
                        viewModel.updateParticipant(participant.playerId) {
                            it.copy(faction = faction)
                        }
                    },
                    onTeam = { team -> viewModel.setParticipantTeam(participant.playerId, team) },
                    onToggleWinner = { viewModel.toggleWinner(participant.playerId) },
                    onToggleNew = {
                        viewModel.updateParticipant(participant.playerId) {
                            it.copy(isNewPlayer = !it.isNewPlayer)
                        }
                    },
                    onMove = { delta -> viewModel.moveParticipant(participant.playerId, delta) },
                    onRemove = { viewModel.removeParticipant(participant.playerId) }
                )
            }

            // After the cards, because it can only name players who are already at the
            // table, and separate from the list order above: in manual-placement mode
            // that order is the finishing ranking, which has nothing to do with who
            // started.
            if (state.form.participants.isNotEmpty()) {
                item { SectionHeader(stringResource(R.string.session_edit_turn_order)) }
                item {
                    TurnOrderPicker(
                        participants = state.form.participants,
                        onToggle = viewModel::toggleTurnOrder,
                        onClear = viewModel::clearTurnOrder
                    )
                }

                // A second arrangement rather than a reading of the one above. Who went
                // first and who sat where are different answers, and a table that
                // rotates its first player every round changes one of them nightly
                // while nobody moves chairs.
                item { SectionHeader(stringResource(R.string.session_edit_seating)) }
                item {
                    SeatingPicker(
                        participants = state.form.participants,
                        onToggle = viewModel::toggleSeat,
                        onClear = viewModel::clearSeating
                    )
                }
            }

            if (state.availableExpansions.isNotEmpty()) {
                item { SectionHeader(stringResource(R.string.session_edit_expansions)) }
                item {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        state.availableExpansions.forEach { expansion ->
                            FilterChip(
                                selected = expansion.id in state.form.expansionIds,
                                onClick = { viewModel.toggleExpansion(expansion.id) },
                                label = { Text(expansion.title) }
                            )
                        }
                    }
                }
            }

            item {
                ToggleRow(
                    label = stringResource(R.string.session_edit_teaching),
                    checked = state.form.isTeachingGame,
                    onChange = { value -> viewModel.update { it.copy(isTeachingGame = value) } }
                )
            }

            item {
                OutlinedTextField(
                    value = state.form.notes.orEmpty(),
                    onValueChange = { value -> viewModel.update { it.copy(notes = value) } },
                    label = { Text(stringResource(R.string.session_edit_notes)) },
                    minLines = 3,
                    modifier = Modifier.fillMaxWidth()
                )
            }

            item {
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = stringResource(
                            if (state.form.photoUri.isNullOrBlank()) {
                                R.string.session_edit_photo
                            } else {
                                R.string.session_edit_photo_attached
                            }
                        ),
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.weight(1f)
                    )
                    if (!state.form.photoUri.isNullOrBlank()) {
                        TextButton(
                            onClick = { viewModel.update { it.copy(photoUri = null) } }
                        ) { Text(stringResource(R.string.session_edit_remove_photo)) }
                    }
                    OutlinedButton(
                        onClick = {
                            photoPicker.launch(
                                PickVisualMediaRequest(
                                    ActivityResultContracts.PickVisualMedia.ImageOnly
                                )
                            )
                        }
                    ) { Text(stringResource(R.string.action_add)) }
                }
            }

            item { Spacer(Modifier.height(48.dp)) }
        }
    }

    if (deleteOpen) {
        ConfirmDialog(
            title = stringResource(R.string.session_edit_delete_title),
            body = stringResource(R.string.session_edit_delete_body),
            confirmLabel = stringResource(R.string.action_delete),
            destructive = true,
            onConfirm = {
                deleteOpen = false
                viewModel.delete()
            },
            onDismiss = { deleteOpen = false }
        )
    }
}

@Composable
private fun GamePicker(state: SessionEditUiState, onSelect: (Long) -> Unit) {
    var open by remember { mutableStateOf(false) }
    val selected = state.games.firstOrNull { it.id == state.form.gameId }
    Box {
        OutlinedButton(onClick = { open = true }, modifier = Modifier.fillMaxWidth()) {
            Text(selected?.title ?: stringResource(R.string.session_edit_choose_game))
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            state.games.forEach { game ->
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
private fun PlayerPicker(state: SessionEditUiState, viewModel: SessionEditViewModel) {
    var newName by remember { mutableStateOf("") }
    val chosen = state.form.participants.map { it.playerId }.toSet()

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        // Ordered by recency, so the people who actually play are the first thing seen.
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            state.players.forEach { player ->
                FilterChip(
                    selected = player.id in chosen,
                    onClick = {
                        if (player.id in chosen) {
                            viewModel.removeParticipant(player.id)
                        } else {
                            viewModel.addPlayer(player)
                        }
                    },
                    label = { Text(player.name) }
                )
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = newName,
                onValueChange = { newName = it },
                label = { Text(stringResource(R.string.session_edit_new_player_hint)) },
                singleLine = true,
                modifier = Modifier.weight(1f)
            )
            Spacer(Modifier.width(8.dp))
            OutlinedButton(
                onClick = {
                    viewModel.addNewPlayer(newName)
                    newName = ""
                },
                enabled = newName.isNotBlank()
            ) { Text(stringResource(R.string.action_add)) }
        }
    }
}

/**
 * Turn order is entered by tapping names in sequence rather than by dragging a second
 * list into shape. It is quicker, and it is honest about a partial answer: tapping one
 * name and stopping records who started and claims nothing about the rest of the table.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TurnOrderPicker(participants: List<ParticipantForm>, onToggle: (Long) -> Unit, onClear: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = stringResource(R.string.session_edit_turn_order_help),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            participants.forEach { participant ->
                val seat = participant.turnOrder
                FilterChip(
                    selected = seat != null,
                    onClick = { onToggle(participant.playerId) },
                    label = {
                        Text(
                            if (seat == null) {
                                participant.playerName
                            } else {
                                stringResource(
                                    R.string.session_edit_turn_order_seat,
                                    seat,
                                    participant.playerName
                                )
                            }
                        )
                    }
                )
            }
        }
        if (participants.any { it.turnOrder != null }) {
            TextButton(onClick = onClear) {
                Text(stringResource(R.string.session_edit_turn_order_clear))
            }
        }
    }
}

/**
 * The seating is entered by tapping names going round the table, the same interaction
 * the turn order uses, because it is the same shape of answer: a position per player,
 * built in sequence and edited freely.
 *
 * What it adds is the ring line underneath. The chips can show that three people have
 * chairs; only the ring shows that it closes -- that the player in the last chair is
 * sitting next to the player in the first -- which is the adjacency this screen is
 * being asked to record. Until every player has a chair it says so instead, rather than
 * showing neighbours that an unseated player might be sitting between.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SeatingPicker(participants: List<ParticipantForm>, onToggle: (Long) -> Unit, onClear: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = stringResource(R.string.session_edit_seating_help),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            participants.forEach { participant ->
                val seat = participant.seat
                FilterChip(
                    selected = seat != null,
                    onClick = { onToggle(participant.playerId) },
                    label = {
                        Text(
                            if (seat == null) {
                                participant.playerName
                            } else {
                                stringResource(
                                    R.string.session_edit_seating_seat,
                                    seat,
                                    participant.playerName
                                )
                            }
                        )
                    }
                )
            }
        }

        val seated = participants.filter { it.seat != null }.sortedBy { it.seat }
        if (seated.isNotEmpty()) {
            Text(
                text = if (Seating.isComplete(participants)) {
                    // The first name repeats at the end on purpose: that is the wrap,
                    // and the wrap is the whole reason the arrangement is a ring.
                    val names = seated.map { it.playerName } + seated.first().playerName
                    stringResource(
                        R.string.session_edit_seating_ring,
                        names.joinToString(stringResource(R.string.session_edit_seating_ring_separator))
                    )
                } else {
                    stringResource(R.string.session_edit_seating_partial)
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            TextButton(onClick = onClear) {
                Text(stringResource(R.string.session_edit_seating_clear))
            }
        }
    }
}

@Composable
private fun ParticipantCard(
    participant: ParticipantForm,
    index: Int,
    mode: ScoringMode,
    showOrdering: Boolean,
    showScore: Boolean,
    showTeam: Boolean,
    previousTeams: List<String>,
    onScore: (Double?) -> Unit,
    onFaction: (String) -> Unit,
    onTeam: (String) -> Unit,
    onToggleWinner: () -> Unit,
    onToggleNew: () -> Unit,
    onMove: (Int) -> Unit,
    onRemove: () -> Unit
) {
    val chartColors = LocalChartColors.current
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow
        ),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                PlayerDot(chartColors.forPlayer(participant.colorHex, index))
                Spacer(Modifier.width(8.dp))
                Text(
                    text = participant.playerName,
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.weight(1f)
                )
                if (showOrdering) {
                    IconButton(onClick = { onMove(-1) }) {
                        Icon(
                            Icons.Filled.ArrowUpward,
                            contentDescription = stringResource(R.string.session_edit_move_up)
                        )
                    }
                    IconButton(onClick = { onMove(1) }) {
                        Icon(
                            Icons.Filled.ArrowDownward,
                            contentDescription = stringResource(R.string.session_edit_move_down)
                        )
                    }
                }
                if (mode != ScoringMode.COOPERATIVE && mode != ScoringMode.TEAM_BASED) {
                    IconButton(onClick = onToggleWinner) {
                        Icon(
                            imageVector = Icons.Filled.EmojiEvents,
                            contentDescription = stringResource(R.string.session_edit_winner),
                            tint = if (participant.isWinner) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            }
                        )
                    }
                }
                IconButton(onClick = onRemove) {
                    Icon(
                        Icons.Filled.Close,
                        contentDescription = stringResource(R.string.session_edit_remove_player)
                    )
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (showScore) {
                    OutlinedTextField(
                        value = participant.score?.let {
                            if (it % 1.0 == 0.0) it.toInt().toString() else it.toString()
                        }.orEmpty(),
                        onValueChange = { value ->
                            onScore(value.filter { it.isDigit() || it == '-' || it == '.' }.toDoubleOrNull())
                        },
                        label = { Text(stringResource(R.string.session_edit_score)) },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.weight(1f)
                    )
                }
                OutlinedTextField(
                    value = participant.faction.orEmpty(),
                    onValueChange = onFaction,
                    label = { Text(stringResource(R.string.session_edit_faction)) },
                    singleLine = true,
                    modifier = Modifier.weight(1.4f)
                )
            }

            if (showTeam) {
                OutlinedTextField(
                    value = participant.team.orEmpty(),
                    onValueChange = onTeam,
                    label = { Text(stringResource(R.string.session_edit_team)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                if (previousTeams.isNotEmpty()) {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        previousTeams.forEach { team ->
                            FilterChip(
                                selected = participant.team == team,
                                onClick = { onTeam(team) },
                                label = { Text(team) }
                            )
                        }
                    }
                }
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = stringResource(R.string.session_edit_first_time),
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.weight(1f)
                )
                Switch(checked = participant.isNewPlayer, onCheckedChange = { onToggleNew() })
            }

            participant.turnTimeMs?.let { turnMs ->
                Text(
                    text = com.boardgamenation.tracker.core.time.DurationFormat.longClock(turnMs),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

private fun SessionEndCondition.labelRes(): Int = when (this) {
    SessionEndCondition.STANDARD -> R.string.session_edit_ended_standard
    SessionEndCondition.SPECIFIC -> R.string.session_edit_ended_specific
    SessionEndCondition.ABANDONED -> R.string.session_edit_ended_abandoned
}

@Composable
private fun ToggleRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

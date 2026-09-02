package com.boardgamenation.tracker.ui.quicklog

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
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
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.boardgamenation.tracker.R
import com.boardgamenation.tracker.core.time.AppClock
import com.boardgamenation.tracker.core.time.DateUtils
import com.boardgamenation.tracker.data.db.entity.GameEntity
import com.boardgamenation.tracker.data.db.entity.PlayerEntity
import com.boardgamenation.tracker.data.repository.GameRepository
import com.boardgamenation.tracker.data.repository.PlayerRepository
import com.boardgamenation.tracker.data.repository.SessionRepository
import com.boardgamenation.tracker.domain.model.CoopOutcome
import com.boardgamenation.tracker.domain.model.ParticipantForm
import com.boardgamenation.tracker.domain.model.ScoringMode
import com.boardgamenation.tracker.domain.model.SessionForm
import com.boardgamenation.tracker.domain.usecase.SaveSessionUseCase
import com.boardgamenation.tracker.ui.components.IsoDateField
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.time.LocalDate
import javax.inject.Inject

data class QuickLogState(
    val games: List<GameEntity> = emptyList(),
    val players: List<PlayerEntity> = emptyList(),
    val form: SessionForm = SessionForm(playedOn = LocalDate.now()),
    val winnerIds: Set<Long> = emptySet(),

    /** Configurations already recorded for this game, offered as one-tap chips. */
    val previousModes: List<String> = emptyList(),

    val savedUnlocks: List<String>? = null,
    val isSaving: Boolean = false,
) {
    val canSave: Boolean
        get() = form.gameId != 0L && form.participants.isNotEmpty() && !isSaving
}

/**
 * The twenty-second path.
 *
 * Everything except "which game" is pre-answered: today's date, the lineup from the last
 * play of that game, and the duration that game actually takes at this table. What is
 * left is picking a game and tapping a winner.
 */
@HiltViewModel
class QuickLogViewModel @Inject constructor(
    private val sessionRepository: SessionRepository,
    private val saveSession: SaveSessionUseCase,
    private val playerRepository: PlayerRepository,
    gameRepository: GameRepository,
    clock: AppClock,
) : ViewModel() {

    private val _state = MutableStateFlow(QuickLogState(form = SessionForm(playedOn = clock.today())))
    val state: StateFlow<QuickLogState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            _state.value = _state.value.copy(
                games = gameRepository.observeBaseGames().first(),
                players = playerRepository.observeByRecency().first(),
            )
        }
    }

    fun selectGame(gameId: Long) {
        viewModelScope.launch {
            val prefilled = sessionRepository.newSessionForm(gameId)
            _state.value = _state.value.copy(
                form = prefilled,
                winnerIds = emptySet(),
                // Deliberately not pre-filled from the last play the way the lineup and
                // duration are: those are safe to be wrong about, a configuration is
                // the thing that makes the result mean what it means.
                previousModes = sessionRepository.observeModesFor(gameId).first(),
            )
        }
    }

    fun togglePlayer(player: PlayerEntity) {
        val form = _state.value.form
        val present = form.participants.any { it.playerId == player.id }
        _state.value = _state.value.copy(
            form = form.copy(
                participants = if (present) {
                    form.participants.filterNot { it.playerId == player.id }
                } else {
                    form.participants + ParticipantForm(
                        playerId = player.id,
                        playerName = player.name,
                        colorHex = player.colorHex,
                    )
                },
            ),
            winnerIds = _state.value.winnerIds - player.id,
        )
    }

    fun addPlayer(name: String) {
        if (name.isBlank()) return
        viewModelScope.launch {
            val id = playerRepository.findOrCreate(name)
            val player = playerRepository.getPlayer(id) ?: return@launch
            _state.value = _state.value.copy(
                players = playerRepository.observeByRecency().first(),
            )
            togglePlayer(player)
        }
    }

    /** Ties are allowed here too, so this toggles rather than selecting exclusively. */
    fun toggleWinner(playerId: Long) {
        val current = _state.value.winnerIds
        _state.value = _state.value.copy(
            winnerIds = if (playerId in current) current - playerId else current + playerId,
        )
    }

    /** Tapping the chip that is already chosen clears it, so a mistap is one tap back. */
    fun toggleMode(mode: String) {
        val current = _state.value.form.mode
        setMode(if (current == mode) null else mode)
    }

    fun setMode(mode: String?) {
        _state.value = _state.value.copy(form = _state.value.form.copy(mode = mode))
    }

    fun setDuration(minutes: Int) {
        _state.value = _state.value.copy(form = _state.value.form.copy(durationMinutes = minutes))
    }

    fun setDate(date: LocalDate) {
        _state.value = _state.value.copy(form = _state.value.form.copy(playedOn = date))
    }

    fun save() {
        val current = _state.value
        if (!current.canSave) return
        _state.value = current.copy(isSaving = true)

        viewModelScope.launch {
            // Quick log records who won directly rather than asking for scores, so
            // nothing should derive placements from nothing. That is said with the
            // transient flag rather than by changing the scoring mode: the mode is
            // written back onto the game when a session saves, so forcing it to NONE
            // here used to reset the game's remembered scoring after a single quick log.
            //
            // Co-op is the exception that still derives: the table has one outcome and
            // applyCoop is what turns it into a flag on every player, including marking
            // everybody a loser when the table lost.
            val coop = current.form.scoringMode == ScoringMode.COOPERATIVE
            val form = current.form.copy(
                derivePlacements = coop,
                coopOutcome = if (coop) {
                    if (current.winnerIds.isNotEmpty()) CoopOutcome.WIN else CoopOutcome.LOSS
                } else {
                    null
                },
                participants = current.form.participants.map {
                    it.copy(isWinner = it.playerId in current.winnerIds)
                },
            )
            val result = saveSession(form)
            _state.value = _state.value.copy(
                isSaving = false,
                savedUnlocks = result.newlyUnlocked.map { it.name },
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun QuickLogSheet(
    onDismiss: () -> Unit,
    onSaved: (List<String>) -> Unit,
    onOpenFullForm: (Long) -> Unit,
    viewModel: QuickLogViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var newPlayerName by remember { mutableStateOf("") }

    LaunchedEffect(state.savedUnlocks) {
        state.savedUnlocks?.let(onSaved)
    }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            Modifier
                .padding(horizontal = 20.dp)
                .padding(bottom = 24.dp)
                .heightIn(max = 560.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = stringResource(R.string.quick_log_title),
                style = MaterialTheme.typography.titleLarge,
            )

            Text(
                text = stringResource(R.string.session_edit_game),
                style = MaterialTheme.typography.labelLarge,
            )
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                state.games.forEach { game ->
                    FilterChip(
                        selected = state.form.gameId == game.id,
                        onClick = { viewModel.selectGame(game.id) },
                        label = { Text(game.title) },
                    )
                }
            }

            if (state.form.gameId != 0L) {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    IsoDateField(
                        value = DateUtils.toIso(state.form.playedOn),
                        label = stringResource(R.string.session_edit_date),
                        onChange = { value ->
                            DateUtils.parseIsoOrNull(value)?.let(viewModel::setDate)
                        },
                        modifier = Modifier.weight(1.3f),
                    )
                    OutlinedTextField(
                        value = state.form.durationMinutes.toString(),
                        onValueChange = { value ->
                            viewModel.setDuration(value.filter { it.isDigit() }.toIntOrNull() ?: 0)
                        },
                        label = { Text(stringResource(R.string.session_edit_duration)) },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.weight(1f),
                    )
                }

                Text(
                    text = stringResource(R.string.session_edit_players),
                    style = MaterialTheme.typography.labelLarge,
                )
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    state.players.forEach { player ->
                        FilterChip(
                            selected = state.form.participants.any { it.playerId == player.id },
                            onClick = { viewModel.togglePlayer(player) },
                            label = { Text(player.name) },
                        )
                    }
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = newPlayerName,
                        onValueChange = { newPlayerName = it },
                        label = { Text(stringResource(R.string.session_edit_new_player_hint)) },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                    Spacer(Modifier.width(8.dp))
                    OutlinedButton(
                        onClick = {
                            viewModel.addPlayer(newPlayerName)
                            newPlayerName = ""
                        },
                        enabled = newPlayerName.isNotBlank(),
                    ) { Text(stringResource(R.string.action_add)) }
                }

                if (state.form.participants.isNotEmpty()) {
                    Text(
                        text = stringResource(R.string.quick_log_who_won),
                        style = MaterialTheme.typography.labelLarge,
                    )
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        state.form.participants.forEach { participant ->
                            FilterChip(
                                selected = participant.playerId in state.winnerIds,
                                onClick = { viewModel.toggleWinner(participant.playerId) },
                                label = { Text(participant.playerName) },
                            )
                        }
                    }
                }

                // Last, and optional, so it never stands between the sheet and the
                // twenty-second save. The chips are the point of putting it here at all:
                // a table that keeps playing Catan with Seafarers taps it once.
                Text(
                    text = stringResource(R.string.session_edit_mode),
                    style = MaterialTheme.typography.labelLarge,
                )
                if (state.previousModes.isNotEmpty()) {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        state.previousModes.forEach { mode ->
                            FilterChip(
                                selected = state.form.mode == mode,
                                onClick = { viewModel.toggleMode(mode) },
                                label = { Text(mode) },
                            )
                        }
                    }
                }
                OutlinedTextField(
                    value = state.form.mode.orEmpty(),
                    onValueChange = { value -> viewModel.setMode(value) },
                    placeholder = { Text(stringResource(R.string.session_edit_mode_hint)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Button(
                    onClick = viewModel::save,
                    enabled = state.canSave,
                    modifier = Modifier.weight(1f),
                ) { Text(stringResource(R.string.action_save)) }

                // The escape hatch to scores, factions, expansions and notes.
                TextButton(
                    onClick = { onOpenFullForm(state.form.gameId) },
                    enabled = state.form.gameId != 0L,
                ) { Text(stringResource(R.string.session_edit_full_form)) }
            }

            Box(Modifier.height(8.dp))
        }
    }
}

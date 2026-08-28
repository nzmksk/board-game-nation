package com.boardgamenation.tracker.ui.sessionedit

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import com.boardgamenation.tracker.R
import com.boardgamenation.tracker.core.time.AppClock
import com.boardgamenation.tracker.data.db.entity.GameEntity
import com.boardgamenation.tracker.data.db.entity.PlayerEntity
import com.boardgamenation.tracker.data.repository.GameRepository
import com.boardgamenation.tracker.data.repository.PlayerRepository
import com.boardgamenation.tracker.data.repository.SessionRepository
import com.boardgamenation.tracker.domain.model.CoopOutcome
import com.boardgamenation.tracker.domain.model.ParticipantForm
import com.boardgamenation.tracker.domain.model.ScoringMode
import com.boardgamenation.tracker.domain.model.SessionForm
import com.boardgamenation.tracker.domain.model.SessionEndCondition
import com.boardgamenation.tracker.domain.usecase.DeleteSessionUseCase
import com.boardgamenation.tracker.domain.usecase.EditSessionUseCase
import com.boardgamenation.tracker.domain.usecase.SaveSessionUseCase
import com.boardgamenation.tracker.ui.navigation.Route
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.time.LocalDate
import javax.inject.Inject

data class SessionEditUiState(
    val form: SessionForm = SessionForm(playedOn = LocalDate.now()),
    val games: List<GameEntity> = emptyList(),
    val players: List<PlayerEntity> = emptyList(),
    val availableExpansions: List<GameEntity> = emptyList(),

    /** Whether this game can end early, which is what puts "Ended by" on the form. */
    val suddenDeathPossible: Boolean = false,

    /** Reasons already recorded for this game, offered as chips instead of retyping. */
    val previousEndReasons: List<String> = emptyList(),

    /** Configurations already recorded for this game, offered the same way. */
    val previousModes: List<String> = emptyList(),

    val isNew: Boolean = true,
    val isSaving: Boolean = false,
    val validationError: Int? = null,
)

/** Emitted once, when the screen should close. */
sealed interface SessionEditEvent {
    data class Saved(val sessionId: Long, val unlockedNames: List<String>) : SessionEditEvent
    data object Deleted : SessionEditEvent
}

@HiltViewModel
class SessionEditViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val sessionRepository: SessionRepository,
    private val gameRepository: GameRepository,
    private val playerRepository: PlayerRepository,
    private val saveSession: SaveSessionUseCase,
    private val editSession: EditSessionUseCase,
    private val deleteSession: DeleteSessionUseCase,
    private val clock: AppClock,
) : ViewModel() {

    private val route = savedStateHandle.toRoute<Route.SessionEdit>()

    private val _state = MutableStateFlow(SessionEditUiState())
    val state: StateFlow<SessionEditUiState> = _state.asStateFlow()

    private val _events = MutableSharedFlow<SessionEditEvent>(extraBufferCapacity = 4)
    val events: SharedFlow<SessionEditEvent> = _events.asSharedFlow()

    init {
        viewModelScope.launch {
            val games = gameRepository.observeBaseGames().first()
            val players = playerRepository.observeByRecency().first()

            val form = when {
                route.sessionId != 0L -> sessionRepository.loadForm(route.sessionId)
                route.gameId != 0L -> sessionRepository.newSessionForm(route.gameId)
                else -> SessionForm(playedOn = clock.today())
            } ?: SessionForm(playedOn = clock.today())

            _state.value = SessionEditUiState(
                form = form,
                games = games,
                players = players,
                availableExpansions = form.gameId
                    .takeIf { it != 0L }
                    ?.let { gameRepository.observeExpansions(it).first() }
                    .orEmpty(),
                suddenDeathPossible = form.gameId
                    .takeIf { it != 0L }
                    ?.let { gameRepository.getGame(it)?.suddenDeathPossible } == true,
                previousEndReasons = form.gameId
                    .takeIf { it != 0L }
                    ?.let { sessionRepository.observeEndReasonsFor(it).first() }
                    .orEmpty(),
                previousModes = form.gameId
                    .takeIf { it != 0L }
                    ?.let { sessionRepository.observeModesFor(it).first() }
                    .orEmpty(),
                isNew = route.sessionId == 0L,
            )
        }
    }

    /**
     * Choosing a game re-derives the defaults, because the whole point of the prefill is
     * that it comes from that game's own history.
     */
    fun selectGame(gameId: Long) {
        viewModelScope.launch {
            val prefilled = sessionRepository.newSessionForm(gameId)
            val current = _state.value.form
            _state.value = _state.value.copy(
                form = prefilled.copy(
                    id = current.id,
                    playedOn = current.playedOn,
                    // Anything the user has already entered survives the switch.
                    participants = current.participants.ifEmpty { prefilled.participants },
                    notes = current.notes,
                    location = current.location,
                ),
                availableExpansions = gameRepository.observeExpansions(gameId).first(),
                suddenDeathPossible = gameRepository.getGame(gameId)?.suddenDeathPossible == true,
                previousEndReasons = sessionRepository.observeEndReasonsFor(gameId).first(),
                previousModes = sessionRepository.observeModesFor(gameId).first(),
                validationError = null,
            )
        }
    }

    fun update(block: (SessionForm) -> SessionForm) {
        _state.value = _state.value.copy(form = block(_state.value.form), validationError = null)
    }

    fun addPlayer(player: PlayerEntity) {
        val form = _state.value.form
        if (form.participants.any { it.playerId == player.id }) return
        update {
            it.copy(
                participants = it.participants + ParticipantForm(
                    playerId = player.id,
                    playerName = player.name,
                    colorHex = player.colorHex,
                ),
            )
        }
    }

    /** The inline "add new player" affordance in the picker. */
    fun addNewPlayer(name: String) {
        if (name.isBlank()) return
        viewModelScope.launch {
            val id = playerRepository.findOrCreate(name)
            val player = playerRepository.getPlayer(id) ?: return@launch
            _state.value = _state.value.copy(
                players = playerRepository.observeByRecency().first(),
            )
            addPlayer(player)
        }
    }

    fun removeParticipant(playerId: Long) {
        update { it.copy(participants = it.participants.filterNot { p -> p.playerId == playerId }) }
    }

    fun updateParticipant(playerId: Long, block: (ParticipantForm) -> ParticipantForm) {
        update { form ->
            form.copy(
                participants = form.participants.map {
                    if (it.playerId == playerId) block(it) else it
                },
            )
        }
    }

    /** Manual placement mode: the list order is the ranking. */
    fun moveParticipant(playerId: Long, delta: Int) {
        update { form ->
            val list = form.participants.toMutableList()
            val index = list.indexOfFirst { it.playerId == playerId }
            val target = index + delta
            if (index < 0 || target !in list.indices) return@update form
            val item = list.removeAt(index)
            list.add(target, item)
            form.copy(participants = list)
        }
    }

    /** Ties are legal, so this toggles rather than making winning exclusive. */
    fun toggleWinner(playerId: Long) {
        updateParticipant(playerId) { it.copy(isWinner = !it.isWinner) }
    }

    fun setCoopOutcome(outcome: CoopOutcome) {
        update { it.copy(coopOutcome = outcome) }
    }

    /**
     * Switching back to an ordinary ending clears the reason too, so a stale
     * "Military supremacy" cannot survive on a play that was scored normally.
     */
    fun setEndCondition(condition: SessionEndCondition?) {
        update {
            it.copy(
                endCondition = condition,
                endReason = if (condition == null) null else it.endReason,
            )
        }
    }

    fun toggleExpansion(gameId: Long) {
        update { form ->
            form.copy(
                expansionIds = if (gameId in form.expansionIds) {
                    form.expansionIds - gameId
                } else {
                    form.expansionIds + gameId
                },
            )
        }
    }

    fun save() {
        val form = _state.value.form
        when {
            form.gameId == 0L -> {
                _state.value = _state.value.copy(
                    validationError = R.string.session_edit_needs_game,
                )
                return
            }
            form.participants.isEmpty() -> {
                _state.value = _state.value.copy(
                    validationError = R.string.session_edit_needs_players,
                )
                return
            }
        }

        _state.value = _state.value.copy(isSaving = true)
        viewModelScope.launch {
            val result = if (_state.value.isNew) saveSession(form) else editSession(form)
            _events.emit(
                SessionEditEvent.Saved(result.sessionId, result.newlyUnlocked.map { it.name }),
            )
        }
    }

    fun delete() {
        val id = _state.value.form.id
        if (id == 0L) return
        viewModelScope.launch {
            deleteSession(id)
            _events.emit(SessionEditEvent.Deleted)
        }
    }

    val scoringMode: ScoringMode get() = _state.value.form.scoringMode
}

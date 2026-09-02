package com.boardgamenation.tracker.ui.sessionedit

import android.net.Uri
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
import com.boardgamenation.tracker.domain.model.TurnOrder
import com.boardgamenation.tracker.domain.share.ShareCard
import com.boardgamenation.tracker.domain.usecase.DeleteSessionUseCase
import com.boardgamenation.tracker.domain.usecase.EditSessionUseCase
import com.boardgamenation.tracker.domain.usecase.SaveSessionUseCase
import com.boardgamenation.tracker.share.SessionShareImages
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

    /** Sides this game has been played with before, offered the same way. */
    val previousTeams: List<String> = emptyList(),

    val isNew: Boolean = true,
    val isSaving: Boolean = false,

    /** True while the result card is being drawn, which takes a moment on a big table. */
    val isSharing: Boolean = false,
    val validationError: Int? = null,
)

/** Emitted once, for something the screen has to do rather than draw. */
sealed interface SessionEditEvent {
    data class Saved(val sessionId: Long, val unlockedNames: List<String>) : SessionEditEvent
    data object Deleted : SessionEditEvent

    /** A rendered result card, ready for the share sheet. */
    data class ShareReady(val image: Uri, val label: String) : SessionEditEvent

    data object ShareFailed : SessionEditEvent
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
    private val shareImages: SessionShareImages,
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
                previousTeams = form.gameId
                    .takeIf { it != 0L }
                    ?.let { sessionRepository.observeTeamsFor(it).first() }
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
                previousTeams = sessionRepository.observeTeamsFor(gameId).first(),
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

    /** Taking a player out closes the gap they leave in the turn order. */
    fun removeParticipant(playerId: Long) {
        update { form ->
            form.copy(
                participants = TurnOrder.normalise(
                    form.participants.filterNot { it.playerId == playerId },
                ),
            )
        }
    }

    /**
     * Turn order is entered by naming players in sequence: an untouched player joins the
     * end of the order, and one already in it drops out while everyone behind closes up.
     */
    fun toggleTurnOrder(playerId: Long) {
        update { it.copy(participants = TurnOrder.toggle(it.participants, playerId)) }
    }

    fun clearTurnOrder() {
        update { it.copy(participants = TurnOrder.clear(it.participants)) }
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

    /** Tapping the winning side again clears it, for a play still being argued over. */
    fun setWinningTeam(team: String) {
        update { it.copy(winningTeam = team.takeUnless { chosen -> chosen == it.winningTeam }) }
    }

    /**
     * Renaming a side out from under the result would leave a winning team nothing is
     * on, so the choice follows the rename.
     */
    fun setParticipantTeam(playerId: Long, team: String) {
        val previous = _state.value.form.participants
            .firstOrNull { it.playerId == playerId }?.team
        updateParticipant(playerId) { it.copy(team = team) }
        if (previous != null && previous == _state.value.form.winningTeam) {
            update { it.copy(winningTeam = team.takeIf { name -> name.isNotBlank() }) }
        }
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

    /**
     * Draws this play's result card and hands it to the screen to share.
     *
     * The card is built from what was saved rather than from what is on the form. A
     * placement is derived when a session is written, so a form with edited scores in it
     * has not been ranked yet -- sharing it would publish a standings table the app
     * itself does not agree with. The picture is of the record.
     */
    fun share() {
        val id = _state.value.form.id
        if (id == 0L || _state.value.isSharing) return

        _state.value = _state.value.copy(isSharing = true)
        viewModelScope.launch {
            val form = sessionRepository.loadForm(id)
            val event = form
                ?.let { runCatching { shareImages.write(ShareCard.of(it)) }.getOrNull() }
                ?.let { SessionEditEvent.ShareReady(it, form.gameTitle) }
                ?: SessionEditEvent.ShareFailed
            _state.value = _state.value.copy(isSharing = false)
            _events.emit(event)
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

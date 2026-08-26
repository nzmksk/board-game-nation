package com.boardgamenation.tracker.ui.gamedetail

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import com.boardgamenation.tracker.data.db.entity.GameEntity
import com.boardgamenation.tracker.data.db.entity.TagEntity
import com.boardgamenation.tracker.data.db.projection.GameAggregates
import com.boardgamenation.tracker.data.db.projection.RatingWithRubric
import com.boardgamenation.tracker.data.db.projection.SessionListItem
import com.boardgamenation.tracker.data.repository.DeleteGameOutcome
import com.boardgamenation.tracker.data.repository.GameRepository
import com.boardgamenation.tracker.data.repository.RubricRepository
import com.boardgamenation.tracker.data.repository.SessionFilter
import com.boardgamenation.tracker.data.repository.SessionRepository
import com.boardgamenation.tracker.ui.navigation.Route
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class GameDetailUiState(
    val game: GameEntity? = null,
    val aggregates: GameAggregates? = null,
    val tags: List<TagEntity> = emptyList(),
    val sessions: List<SessionListItem> = emptyList(),
    val expansions: List<GameEntity> = emptyList(),
    val ratings: List<RatingWithRubric> = emptyList(),
    val daysOnLoan: Long? = null,
    val isLoading: Boolean = true,
) {
    val currentRating: RatingWithRubric? get() = ratings.firstOrNull()

    /**
     * Cost per play is the metric that most changes buying behaviour, so it is computed
     * even when there is only one play: a game bought last week and played once has a
     * cost per play, and it is a large one.
     */
    val costPerPlay: Double?
        get() {
            val price = game?.price ?: return null
            val plays = aggregates?.playCount ?: 0
            return if (plays > 0) price / plays else null
        }
}

/** Whether deleting needs a confirmation, and what it would take with it. */
data class DeletePrompt(val sessionCount: Int, val expansionCount: Int)

@HiltViewModel
class GameDetailViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val gameRepository: GameRepository,
    private val sessionRepository: SessionRepository,
    private val rubricRepository: RubricRepository,
) : ViewModel() {

    val gameId: Long = savedStateHandle.toRoute<Route.GameDetail>().gameId

    private val _deletePrompt = MutableStateFlow<DeletePrompt?>(null)
    val deletePrompt: StateFlow<DeletePrompt?> = _deletePrompt

    private val _deleted = MutableStateFlow(false)
    val deleted: StateFlow<Boolean> = _deleted

    val uiState: StateFlow<GameDetailUiState> = combine(
        gameRepository.observeGame(gameId),
        gameRepository.observeAggregates(gameId),
        gameRepository.observeTags(gameId),
        sessionRepository.observeSessions(SessionFilter(gameId = gameId)),
        combine(
            gameRepository.observeExpansions(gameId),
            rubricRepository.observeRatingsFor(gameId),
        ) { expansions, ratings -> expansions to ratings },
    ) { game, aggregates, tags, sessions, (expansions, ratings) ->
        GameDetailUiState(
            game = game,
            aggregates = aggregates,
            tags = tags,
            sessions = sessions,
            expansions = expansions,
            ratings = ratings,
            daysOnLoan = gameRepository.daysOnLoan(game?.lentDate),
            isLoading = false,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), GameDetailUiState())

    fun requestDelete() {
        viewModelScope.launch {
            when (val outcome = gameRepository.deleteGame(gameId, confirmed = false)) {
                is DeleteGameOutcome.NeedsConfirmation ->
                    _deletePrompt.value = DeletePrompt(outcome.sessionCount, outcome.expansionCount)
                DeleteGameOutcome.Deleted -> _deleted.value = true
            }
        }
    }

    fun confirmDelete() {
        viewModelScope.launch {
            gameRepository.deleteGame(gameId, confirmed = true)
            _deletePrompt.value = null
            _deleted.value = true
        }
    }

    fun dismissDelete() {
        _deletePrompt.value = null
    }

    fun lend(person: String) {
        if (person.isBlank()) return
        viewModelScope.launch { gameRepository.lendGame(gameId, person) }
    }

    fun markReturned() {
        viewModelScope.launch { gameRepository.returnGame(gameId) }
    }
}

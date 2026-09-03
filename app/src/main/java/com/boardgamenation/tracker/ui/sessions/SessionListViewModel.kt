package com.boardgamenation.tracker.ui.sessions

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import com.boardgamenation.tracker.data.db.entity.GameEntity
import com.boardgamenation.tracker.data.db.entity.PlayerEntity
import com.boardgamenation.tracker.data.db.projection.SessionListItem
import com.boardgamenation.tracker.data.repository.GameRepository
import com.boardgamenation.tracker.data.repository.PlayerRepository
import com.boardgamenation.tracker.data.repository.SessionFilter
import com.boardgamenation.tracker.data.repository.SessionRepository
import com.boardgamenation.tracker.ui.navigation.Route
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn

data class SessionListUiState(
    val sessions: List<SessionListItem> = emptyList(),
    val games: List<GameEntity> = emptyList(),
    val players: List<PlayerEntity> = emptyList(),
    val filter: SessionFilter = SessionFilter(),
    val isLoading: Boolean = true
)

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class SessionListViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val sessionRepository: SessionRepository,
    gameRepository: GameRepository,
    playerRepository: PlayerRepository
) : ViewModel() {

    private val route = savedStateHandle.toRoute<Route.Sessions>()

    // The list is reachable both from the bottom bar and from a game or player, so the
    // route's arguments seed the filter rather than being a separate screen.
    private val filter = MutableStateFlow(
        SessionFilter(
            gameId = route.gameId.takeIf { it != 0L },
            playerId = route.playerId.takeIf { it != 0L }
        )
    )

    val uiState: StateFlow<SessionListUiState> = combine(
        filter.flatMapLatest { sessionRepository.observeSessions(it) },
        gameRepository.observeBaseGames(),
        playerRepository.observeActive(),
        filter
    ) { sessions, games, players, currentFilter ->
        SessionListUiState(
            sessions = sessions,
            games = games,
            players = players,
            filter = currentFilter,
            isLoading = false
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SessionListUiState())

    fun setGame(gameId: Long?) {
        filter.value = filter.value.copy(gameId = gameId)
    }

    fun setPlayer(playerId: Long?) {
        filter.value = filter.value.copy(playerId = playerId)
    }

    fun setDateRange(from: String?, to: String?) {
        filter.value = filter.value.copy(fromDate = from, toDate = to)
    }

    fun clearFilters() {
        filter.value = SessionFilter()
    }
}

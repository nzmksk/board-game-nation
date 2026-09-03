package com.boardgamenation.tracker.ui.collection

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.boardgamenation.tracker.data.db.entity.TagEntity
import com.boardgamenation.tracker.data.db.projection.GameListItem
import com.boardgamenation.tracker.data.prefs.SettingsRepository
import com.boardgamenation.tracker.data.repository.GameRepository
import com.boardgamenation.tracker.domain.model.CollectionFilter
import com.boardgamenation.tracker.domain.model.CollectionLayout
import com.boardgamenation.tracker.domain.model.CollectionSort
import com.boardgamenation.tracker.domain.model.GameStatus
import com.boardgamenation.tracker.domain.model.PlaytimeBucket
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class CollectionUiState(
    val games: List<GameListItem> = emptyList(),
    val tags: List<TagEntity> = emptyList(),
    val filter: CollectionFilter = CollectionFilter(),
    val layout: CollectionLayout = CollectionLayout.LIST,
    val selection: Set<Long> = emptySet(),
    val isLoading: Boolean = true
) {
    val inSelectionMode: Boolean get() = selection.isNotEmpty()
}

@OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
@HiltViewModel
class CollectionViewModel @Inject constructor(
    private val gameRepository: GameRepository,
    private val settingsRepository: SettingsRepository
) : ViewModel() {

    private val filter = MutableStateFlow(CollectionFilter())
    private val selection = MutableStateFlow<Set<Long>>(emptySet())

    /**
     * Search is debounced, everything else is not.
     *
     * Typing re-runs the query on every keystroke otherwise, and each run is a fresh
     * statement across the whole collection; a chip tap is a single deliberate action
     * and should feel instant.
     */
    private val debouncedFilter = filter
        .debounce { if (it.search.isBlank()) 0L else SEARCH_DEBOUNCE_MS }
        .distinctUntilChanged()

    val uiState: StateFlow<CollectionUiState> = combine(
        gameRepository.observeCollection(debouncedFilter),
        gameRepository.observeTagsInUse(),
        filter,
        selection,
        settingsRepository.settings.map { it.collectionLayout }.distinctUntilChanged()
    ) { games, tags, currentFilter, selected, layout ->
        CollectionUiState(
            games = games,
            tags = tags,
            filter = currentFilter,
            layout = layout,
            // Selecting a game and then filtering it away would otherwise leave it
            // invisibly selected and quietly included in the next bulk action.
            selection = selected intersect games.map { it.id }.toSet(),
            isLoading = false
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), CollectionUiState())

    fun onSearchChange(query: String) {
        filter.value = filter.value.copy(search = query)
    }

    fun toggleStatus(status: GameStatus) {
        val current = filter.value.statuses
        filter.value = filter.value.copy(
            statuses = if (status in current) current - status else current + status
        )
    }

    fun setPlayerCount(count: Int?) {
        filter.value = filter.value.copy(
            playerCount = if (filter.value.playerCount == count) null else count
        )
    }

    fun setPlaytime(bucket: PlaytimeBucket?) {
        filter.value = filter.value.copy(
            playtime = if (filter.value.playtime == bucket) null else bucket
        )
    }

    fun toggleTag(tagId: Long) {
        val current = filter.value.tagIds
        filter.value = filter.value.copy(
            tagIds = if (tagId in current) current - tagId else current + tagId
        )
    }

    fun setRated(rated: Boolean?) {
        filter.value = filter.value.copy(
            rated = if (filter.value.rated == rated) null else rated
        )
    }

    fun toggleInPossession() {
        filter.value = filter.value.copy(inPossessionOnly = !filter.value.inPossessionOnly)
    }

    fun toggleExpansions() {
        filter.value = filter.value.copy(includeExpansions = !filter.value.includeExpansions)
    }

    /** Tapping the current sort flips its direction, which is what people expect. */
    fun setSort(sort: CollectionSort) {
        val current = filter.value
        filter.value = if (current.sort == sort) {
            current.copy(ascending = !current.ascending)
        } else {
            current.copy(sort = sort, ascending = defaultAscending(sort))
        }
    }

    /** Titles read best A to Z; counts and ratings read best largest first. */
    private fun defaultAscending(sort: CollectionSort): Boolean = when (sort) {
        CollectionSort.TITLE -> true
        CollectionSort.COST_PER_PLAY -> true
        else -> false
    }

    fun clearFilters() {
        filter.value = CollectionFilter(
            search = filter.value.search,
            sort = filter.value.sort,
            ascending = filter.value.ascending
        )
    }

    fun toggleLayout() {
        viewModelScope.launch {
            val next = when (uiState.value.layout) {
                CollectionLayout.LIST -> CollectionLayout.GRID
                CollectionLayout.GRID -> CollectionLayout.LIST
            }
            settingsRepository.setCollectionLayout(next)
        }
    }

    fun toggleSelection(gameId: Long) {
        val current = selection.value
        selection.value = if (gameId in current) current - gameId else current + gameId
    }

    fun selectAll() {
        selection.value = uiState.value.games.map { it.id }.toSet()
    }

    fun clearSelection() {
        selection.value = emptySet()
    }

    fun bulkSetStatus(status: GameStatus) {
        val ids = selection.value.toList()
        if (ids.isEmpty()) return
        viewModelScope.launch {
            gameRepository.setStatus(ids, status)
            selection.value = emptySet()
        }
    }

    fun bulkAddTag(tagId: Long) {
        val ids = selection.value.toList()
        if (ids.isEmpty()) return
        viewModelScope.launch {
            gameRepository.addTagsTo(ids, listOf(tagId))
            selection.value = emptySet()
        }
    }

    fun bulkDelete() {
        val ids = selection.value.toList()
        if (ids.isEmpty()) return
        viewModelScope.launch {
            gameRepository.deleteGames(ids)
            selection.value = emptySet()
        }
    }

    private companion object {
        const val SEARCH_DEBOUNCE_MS = 200L
    }
}

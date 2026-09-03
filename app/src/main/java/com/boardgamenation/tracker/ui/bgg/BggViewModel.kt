package com.boardgamenation.tracker.ui.bgg

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.boardgamenation.tracker.data.bgg.BggCollectionItem
import com.boardgamenation.tracker.data.bgg.BggError
import com.boardgamenation.tracker.data.bgg.BggSearchResult
import com.boardgamenation.tracker.data.prefs.SettingsRepository
import com.boardgamenation.tracker.data.repository.BggImportProgress
import com.boardgamenation.tracker.data.repository.BggRepository
import com.boardgamenation.tracker.data.repository.GameRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Errors are carried as a message plus a retryable flag rather than as a thrown
 * exception, because the screens have to say something specific and offer a retry only
 * when retrying could actually help.
 */
data class BggUiState(
    val configured: Boolean = false,
    val username: String = "",
    val query: String = "",
    val searchResults: List<BggSearchResult> = emptyList(),
    val collectionItems: List<BggCollectionItem> = emptyList(),
    val selected: Set<Long> = emptySet(),
    val alreadyOwned: Set<Long> = emptySet(),
    val isBusy: Boolean = false,
    val statusMessage: String? = null,
    val queuedRetrySeconds: Int? = null,
    val progress: Pair<Int, Int>? = null,
    val errorMessage: String? = null,
    val errorRetryable: Boolean = false,
    val importedCount: Int? = null
)

@HiltViewModel
class BggViewModel @Inject constructor(
    private val bggRepository: BggRepository,
    private val gameRepository: GameRepository,
    private val settingsRepository: SettingsRepository
) : ViewModel() {

    private val _state = MutableStateFlow(BggUiState(configured = bggRepository.isConfigured))
    val state: StateFlow<BggUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            _state.value = _state.value.copy(
                username = settingsRepository.settings.first().bggUsername
            )
        }
    }

    fun setQuery(value: String) {
        _state.value = _state.value.copy(query = value)
    }

    fun setUsername(value: String) {
        _state.value = _state.value.copy(username = value)
    }

    fun search() {
        val query = _state.value.query.trim()
        if (query.isEmpty()) return
        run {
            val results = bggRepository.search(query)
            val owned = results.mapNotNull { result ->
                gameRepository.getGameByBggId(result.bggId)?.let { result.bggId }
            }.toSet()
            _state.value = _state.value.copy(searchResults = results, alreadyOwned = owned)
        }
    }

    fun fetchCollection() {
        val username = _state.value.username.trim()
        if (username.isEmpty()) return
        viewModelScope.launch { settingsRepository.setBggUsername(username) }
        run {
            val items = bggRepository.fetchCollection(username) { progress ->
                when (progress) {
                    is BggImportProgress.Queued -> _state.value = _state.value.copy(
                        queuedRetrySeconds = progress.retryInSeconds
                    )

                    is BggImportProgress.Fetching -> _state.value = _state.value.copy(
                        queuedRetrySeconds = null
                    )

                    else -> Unit
                }
            }
            val owned = items.mapNotNull { item ->
                gameRepository.getGameByBggId(item.bggId)?.let { item.bggId }
            }.toSet()
            _state.value = _state.value.copy(
                collectionItems = items,
                alreadyOwned = owned,
                // Anything not already in the collection starts ticked, since importing
                // the new ones is what the screen is for.
                selected = items.map { it.bggId }.toSet() - owned,
                queuedRetrySeconds = null
            )
        }
    }

    fun toggleSelection(bggId: Long) {
        val current = _state.value.selected
        _state.value = _state.value.copy(
            selected = if (bggId in current) current - bggId else current + bggId
        )
    }

    fun selectAll() {
        _state.value = _state.value.copy(
            selected = _state.value.collectionItems.map { it.bggId }.toSet()
        )
    }

    fun clearSelection() {
        _state.value = _state.value.copy(selected = emptySet())
    }

    fun importSelected() {
        val ids = _state.value.selected.toList()
        if (ids.isEmpty()) return
        run {
            val things = bggRepository.things(ids)
            val count = bggRepository.importThings(things) { done, total ->
                _state.value = _state.value.copy(progress = done to total)
            }
            _state.value = _state.value.copy(importedCount = count, progress = null)
        }
    }

    fun importSingle(bggId: Long, onDone: (Long) -> Unit) {
        run {
            val things = bggRepository.things(listOf(bggId))
            bggRepository.importThings(things)
            gameRepository.getGameByBggId(bggId)?.let { onDone(it.id) }
        }
    }

    fun dismissError() {
        _state.value = _state.value.copy(errorMessage = null, errorRetryable = false)
    }

    fun dismissImported() {
        _state.value = _state.value.copy(importedCount = null)
    }

    /** Wraps a call so every BGG failure lands as a message, never as a silent no-op. */
    private fun run(block: suspend () -> Unit) {
        viewModelScope.launch {
            _state.value = _state.value.copy(
                isBusy = true,
                errorMessage = null,
                errorRetryable = false
            )
            try {
                block()
            } catch (e: BggError) {
                _state.value = _state.value.copy(
                    errorMessage = e.message,
                    errorRetryable = e.retryable
                )
            } catch (e: Exception) {
                _state.value = _state.value.copy(
                    errorMessage = e.message ?: e::class.simpleName,
                    errorRetryable = true
                )
            } finally {
                _state.value = _state.value.copy(isBusy = false, queuedRetrySeconds = null)
            }
        }
    }
}

package com.boardgamenation.tracker.ui.gameedit

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import com.boardgamenation.tracker.core.time.AppClock
import com.boardgamenation.tracker.core.time.DateUtils
import com.boardgamenation.tracker.data.db.entity.GameEntity
import com.boardgamenation.tracker.data.prefs.SettingsRepository
import com.boardgamenation.tracker.data.repository.GameRepository
import com.boardgamenation.tracker.domain.model.GameStatus
import com.boardgamenation.tracker.domain.model.ScoringMode
import com.boardgamenation.tracker.domain.model.TagKind
import com.boardgamenation.tracker.ui.navigation.Route
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * The edit form as plain text fields.
 *
 * Numbers are held as strings while being typed. A partially typed "12" is not an Int,
 * and coercing on every keystroke makes fields fight the user; parsing happens once, on
 * save, where a bad value can be reported.
 */
data class GameEditState(
    val id: Long = 0,
    val title: String = "",
    val yearPublished: String = "",
    val minPlayers: String = "",
    val maxPlayers: String = "",
    val bestPlayerCount: String = "",
    val minPlaytime: String = "",
    val maxPlaytime: String = "",
    val weight: String = "",
    val bggRating: String = "",
    val designers: String = "",
    val publisher: String = "",
    val dateAdded: String = "",
    val price: String = "",
    val currency: String = "MYR",
    val purchaseNote: String = "",
    val status: GameStatus = GameStatus.OWNED,
    val wishlistPriority: Int? = null,
    val isExpansion: Boolean = false,
    val baseGameId: Long? = null,
    val scoringMode: ScoringMode = ScoringMode.RANKED_SCORES,
    val highScoreWins: Boolean = true,
    val suddenDeathPossible: Boolean = false,
    val notes: String = "",
    val mechanics: List<String> = emptyList(),
    val categories: List<String> = emptyList(),
    val baseGameOptions: List<GameEntity> = emptyList(),
    val isNew: Boolean = true,
    val isSaving: Boolean = false,
    val titleError: Boolean = false,
) {
    val canSave: Boolean get() = title.isNotBlank() && !isSaving
}

@HiltViewModel
class GameEditViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val gameRepository: GameRepository,
    private val settingsRepository: SettingsRepository,
    private val clock: AppClock,
) : ViewModel() {

    private val gameId: Long = savedStateHandle.toRoute<Route.GameEdit>().gameId

    private val _state = MutableStateFlow(GameEditState())
    val state: StateFlow<GameEditState> = _state.asStateFlow()

    private val _saved = MutableStateFlow<Long?>(null)
    val saved: StateFlow<Long?> = _saved.asStateFlow()

    init {
        viewModelScope.launch {
            val defaultCurrency = settingsRepository.settings.first().defaultCurrency
            val bases = gameRepository.observeBaseGames().first()

            if (gameId == 0L) {
                _state.value = GameEditState(
                    dateAdded = DateUtils.toIso(clock.today()),
                    currency = defaultCurrency,
                    baseGameOptions = bases,
                    isNew = true,
                )
            } else {
                val game = gameRepository.getGame(gameId)
                val tags = gameRepository.observeTags(gameId).first()
                if (game != null) {
                    _state.value = GameEditState(
                        id = game.id,
                        title = game.title,
                        yearPublished = game.yearPublished?.toString().orEmpty(),
                        minPlayers = game.minPlayers?.toString().orEmpty(),
                        maxPlayers = game.maxPlayers?.toString().orEmpty(),
                        bestPlayerCount = game.bestPlayerCount.orEmpty(),
                        minPlaytime = game.minPlaytimeMinutes?.toString().orEmpty(),
                        maxPlaytime = game.maxPlaytimeMinutes?.toString().orEmpty(),
                        weight = game.weight?.toString().orEmpty(),
                        bggRating = game.bggRating?.toString().orEmpty(),
                        designers = game.designers.orEmpty(),
                        publisher = game.publisher.orEmpty(),
                        dateAdded = game.dateAdded,
                        price = game.price?.toString().orEmpty(),
                        currency = game.currency,
                        purchaseNote = game.purchaseNote.orEmpty(),
                        status = game.status,
                        wishlistPriority = game.wishlistPriority,
                        isExpansion = game.isExpansion,
                        baseGameId = game.baseGameId,
                        scoringMode = game.scoringMode,
                        highScoreWins = game.highScoreWins,
                        suddenDeathPossible = game.suddenDeathPossible,
                        notes = game.notes.orEmpty(),
                        mechanics = tags.filter { it.kind == TagKind.MECHANIC }.map { it.name },
                        categories = tags.filter { it.kind == TagKind.CATEGORY }.map { it.name },
                        // A game cannot be its own base game.
                        baseGameOptions = bases.filter { it.id != game.id },
                        isNew = false,
                    )
                }
            }
        }
    }

    fun update(block: (GameEditState) -> GameEditState) {
        _state.value = block(_state.value)
    }

    fun addMechanic(name: String) {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return
        _state.value = _state.value.let {
            it.copy(mechanics = (it.mechanics + trimmed).distinct())
        }
    }

    fun removeMechanic(name: String) {
        _state.value = _state.value.let { it.copy(mechanics = it.mechanics - name) }
    }

    fun addCategory(name: String) {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return
        _state.value = _state.value.let {
            it.copy(categories = (it.categories + trimmed).distinct())
        }
    }

    fun removeCategory(name: String) {
        _state.value = _state.value.let { it.copy(categories = it.categories - name) }
    }

    fun save() {
        val current = _state.value
        if (current.title.isBlank()) {
            _state.value = current.copy(titleError = true)
            return
        }
        _state.value = current.copy(isSaving = true, titleError = false)

        viewModelScope.launch {
            val now = clock.nowMillis()
            // Read once: bgg_id and the cached cover belong to the stored row, not to the
            // form, and must survive an edit that never showed them.
            val existing = if (current.isNew) null else gameRepository.getGame(current.id)
            val entity = GameEntity(
                id = current.id,
                bggId = existing?.bggId,
                title = current.title.trim(),
                yearPublished = current.yearPublished.toIntOrNull(),
                minPlayers = current.minPlayers.toIntOrNull(),
                maxPlayers = current.maxPlayers.toIntOrNull(),
                bestPlayerCount = current.bestPlayerCount.trim().ifBlank { null },
                minPlaytimeMinutes = current.minPlaytime.toIntOrNull(),
                maxPlaytimeMinutes = current.maxPlaytime.toIntOrNull(),
                weight = current.weight.toDoubleOrNull(),
                bggRating = current.bggRating.toDoubleOrNull(),
                designers = current.designers.trim().ifBlank { null },
                publisher = current.publisher.trim().ifBlank { null },
                thumbnailPath = existing?.thumbnailPath,
                dateAdded = current.dateAdded.ifBlank { DateUtils.toIso(clock.today()) },
                price = current.price.toDoubleOrNull(),
                currency = current.currency.ifBlank { "MYR" },
                purchaseNote = current.purchaseNote.trim().ifBlank { null },
                status = current.status,
                wishlistPriority = current.wishlistPriority
                    .takeIf { current.status == GameStatus.WISHLIST },
                inPossession = current.status != GameStatus.LENT_OUT,
                isExpansion = current.isExpansion,
                baseGameId = current.baseGameId.takeIf { current.isExpansion },
                scoringMode = current.scoringMode,
                highScoreWins = current.highScoreWins,
                suddenDeathPossible = current.suddenDeathPossible,
                notes = current.notes.trim().ifBlank { null },
                createdAt = now,
                updatedAt = now,
            )

            val mechanicIds = gameRepository.resolveTags(current.mechanics, TagKind.MECHANIC)
            val categoryIds = gameRepository.resolveTags(current.categories, TagKind.CATEGORY)
            val tagIds = mechanicIds + categoryIds

            val id = if (current.isNew) {
                gameRepository.addGame(entity, tagIds)
            } else {
                gameRepository.updateGame(entity, tagIds)
                current.id
            }
            _saved.value = id
        }
    }
}

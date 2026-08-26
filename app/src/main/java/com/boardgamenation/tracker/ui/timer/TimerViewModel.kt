package com.boardgamenation.tracker.ui.timer

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import com.boardgamenation.tracker.data.db.entity.GameEntity
import com.boardgamenation.tracker.data.db.entity.PlayerEntity
import com.boardgamenation.tracker.data.db.entity.TimerPresetEntity
import com.boardgamenation.tracker.data.prefs.SettingsRepository
import com.boardgamenation.tracker.data.repository.GameRepository
import com.boardgamenation.tracker.data.repository.PlayerRepository
import com.boardgamenation.tracker.data.repository.TimerRepository
import com.boardgamenation.tracker.domain.model.BankExhaustedBehaviour
import com.boardgamenation.tracker.domain.timer.TimerConfig
import com.boardgamenation.tracker.domain.timer.TimerProjection
import com.boardgamenation.tracker.timer.TimerController
import com.boardgamenation.tracker.timer.TimerEvent
import com.boardgamenation.tracker.timer.TimerSummary
import com.boardgamenation.tracker.ui.navigation.Route
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class TimerSetupState(
    val games: List<GameEntity> = emptyList(),
    val players: List<PlayerEntity> = emptyList(),
    val presets: List<TimerPresetEntity> = emptyList(),
    val gameId: Long = 0,
    /** Ordered: this list *is* the seating, so index equals turn order. */
    val seating: List<PlayerEntity> = emptyList(),
    val turnSeconds: Int = 60,
    val bankSeconds: Int = 600,
    val warningSeconds: Int = 10,
    val soundEnabled: Boolean = true,
    val hapticsEnabled: Boolean = true,
    val autoPass: Boolean = false,
    val keepScreenOn: Boolean = true,
) {
    val canStart: Boolean get() = gameId != 0L && seating.size >= 2
    val selectedGame: GameEntity? get() = games.firstOrNull { it.id == gameId }
}

@HiltViewModel
class TimerViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val controller: TimerController,
    private val timerRepository: TimerRepository,
    private val settingsRepository: SettingsRepository,
    gameRepository: GameRepository,
    playerRepository: PlayerRepository,
) : ViewModel() {

    private val initialGameId: Long =
        runCatching { savedStateHandle.toRoute<Route.TimerSetup>().gameId }.getOrDefault(0L)

    private val local = MutableStateFlow(TimerSetupState(gameId = initialGameId))

    val setupState: StateFlow<TimerSetupState> = combine(
        gameRepository.observeBaseGames(),
        playerRepository.observeByRecency(),
        timerRepository.observePresets(),
        settingsRepository.settings,
        local,
    ) { games, players, presets, settings, current ->
        current.copy(
            games = games,
            players = players,
            presets = presets,
            keepScreenOn = settings.keepScreenOnDuringTimer,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), TimerSetupState())

    /** The live clock, or null when nothing is running. */
    val projection: StateFlow<TimerProjection?> = controller.projection

    val events: SharedFlow<TimerEvent> = controller.events

    private val _summary = MutableStateFlow<TimerSummary?>(null)
    val summary: StateFlow<TimerSummary?> = _summary.asStateFlow()

    init {
        viewModelScope.launch {
            timerRepository.ensureDefaultPreset()
            val settings = settingsRepository.settings.first()
            settings.defaultTimerPresetId.takeIf { it != 0L }?.let(::applyPreset)
        }
    }

    fun selectGame(id: Long) {
        local.value = local.value.copy(gameId = id)
        viewModelScope.launch {
            // A preset pinned to this game beats the global default.
            timerRepository.presetsForGame(id).firstOrNull()?.let { preset ->
                local.value = local.value.copy(
                    turnSeconds = preset.turnSeconds,
                    bankSeconds = preset.bankSeconds,
                    warningSeconds = preset.warningThresholdSeconds,
                    soundEnabled = preset.soundEnabled,
                    hapticsEnabled = preset.hapticsEnabled,
                    autoPass = preset.autoPassOnBankEmpty,
                )
            }
        }
    }

    fun togglePlayer(player: PlayerEntity) {
        val current = local.value.seating
        local.value = local.value.copy(
            seating = if (current.any { it.id == player.id }) {
                current.filterNot { it.id == player.id }
            } else {
                current + player
            },
        )
    }

    fun movePlayer(playerId: Long, delta: Int) {
        val list = local.value.seating.toMutableList()
        val index = list.indexOfFirst { it.id == playerId }
        val target = index + delta
        if (index < 0 || target !in list.indices) return
        list.add(target, list.removeAt(index))
        local.value = local.value.copy(seating = list)
    }

    fun applyPreset(presetId: Long) {
        viewModelScope.launch {
            val preset = timerRepository.getPreset(presetId) ?: return@launch
            local.value = local.value.copy(
                turnSeconds = preset.turnSeconds,
                bankSeconds = preset.bankSeconds,
                warningSeconds = preset.warningThresholdSeconds,
                soundEnabled = preset.soundEnabled,
                hapticsEnabled = preset.hapticsEnabled,
                autoPass = preset.autoPassOnBankEmpty,
            )
        }
    }

    fun updateSetup(block: (TimerSetupState) -> TimerSetupState) {
        local.value = block(local.value)
    }

    fun setKeepScreenOn(enabled: Boolean) {
        viewModelScope.launch { settingsRepository.setKeepScreenOn(enabled) }
    }

    fun savePreset(name: String) {
        val current = local.value
        viewModelScope.launch {
            timerRepository.savePreset(
                TimerPresetEntity(
                    name = name.trim(),
                    turnSeconds = current.turnSeconds,
                    bankSeconds = current.bankSeconds,
                    warningThresholdSeconds = current.warningSeconds,
                    soundEnabled = current.soundEnabled,
                    hapticsEnabled = current.hapticsEnabled,
                    autoPassOnBankEmpty = current.autoPass,
                    gameId = current.gameId.takeIf { it != 0L },
                ),
            )
        }
    }

    fun start(onStarted: () -> Unit) {
        val current = local.value
        if (!current.canStart) return
        viewModelScope.launch {
            controller.setUp(
                gameId = current.gameId,
                players = current.seating,
                config = TimerConfig(
                    turnMs = current.turnSeconds * 1000L,
                    bankMs = current.bankSeconds * 1000L,
                    warningMs = current.warningSeconds * 1000L,
                    soundEnabled = current.soundEnabled,
                    hapticsEnabled = current.hapticsEnabled,
                    bankExhausted = if (current.autoPass) {
                        BankExhaustedBehaviour.AUTO_PASS
                    } else {
                        BankExhaustedBehaviour.FLAG_AND_OVERTIME
                    },
                ),
            )
            controller.start()
            onStarted()
        }
    }

    fun passTurn() = viewModelScope.launch { controller.passTurn() }
    fun pause() = viewModelScope.launch { controller.pause() }
    fun resume() = viewModelScope.launch { controller.resume() }
    fun undo() = viewModelScope.launch { controller.undoLastPass() }
    fun reverse() = viewModelScope.launch { controller.reverseDirection() }
    fun toggleSkip(seatIndex: Int) = viewModelScope.launch { controller.toggleSkip(seatIndex) }
    fun selectSeat(seatIndex: Int) = viewModelScope.launch { controller.selectSeat(seatIndex) }

    fun stop() {
        viewModelScope.launch { _summary.value = controller.stopAndSummarise() }
    }

    /** Throws the whole thing away, draft session included. */
    fun discard() {
        viewModelScope.launch {
            controller.discard()
            _summary.value = null
        }
    }

    /** Called once the stopped session has been handed to the session form. */
    fun releaseAfterSave() {
        viewModelScope.launch {
            controller.release()
            _summary.value = null
        }
    }

    fun dismissSummary() {
        _summary.value = null
    }
}

package com.boardgamenation.tracker.ui.settings

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.boardgamenation.tracker.R
import com.boardgamenation.tracker.data.backup.BackupScheduler
import com.boardgamenation.tracker.data.backup.DbBackup
import com.boardgamenation.tracker.data.csv.CsvExporter
import com.boardgamenation.tracker.data.csv.CsvImporter
import com.boardgamenation.tracker.data.csv.ImportPreview
import com.boardgamenation.tracker.data.db.entity.PlayerEntity
import com.boardgamenation.tracker.data.db.entity.TimerPresetEntity
import com.boardgamenation.tracker.data.dev.DevFixtures
import com.boardgamenation.tracker.data.prefs.AppSettings
import com.boardgamenation.tracker.data.prefs.SettingsRepository
import com.boardgamenation.tracker.data.repository.BggRepository
import com.boardgamenation.tracker.data.repository.DataMaintenanceRepository
import com.boardgamenation.tracker.data.repository.PlayerRepository
import com.boardgamenation.tracker.data.repository.TimerRepository
import com.boardgamenation.tracker.domain.model.ImportMode
import com.boardgamenation.tracker.domain.model.ThemeMode
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class SettingsUiState(
    val settings: AppSettings = AppSettings(),
    val players: List<PlayerEntity> = emptyList(),
    val presets: List<TimerPresetEntity> = emptyList(),
    val bggConfigured: Boolean = false
)

/** Feedback from a data operation, shown as a snackbar. */
data class DataMessage(val text: String, val isError: Boolean = false)

@HiltViewModel
class SettingsViewModel @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val settingsRepository: SettingsRepository,
    private val playerRepository: PlayerRepository,
    private val timerRepository: TimerRepository,
    private val csvExporter: CsvExporter,
    private val csvImporter: CsvImporter,
    private val dbBackup: DbBackup,
    private val maintenance: DataMaintenanceRepository,
    private val devFixtures: DevFixtures,
    private val backupScheduler: BackupScheduler,
    bggRepository: BggRepository
) : ViewModel() {

    val uiState: StateFlow<SettingsUiState> = combine(
        settingsRepository.settings,
        playerRepository.observeAll(),
        timerRepository.observePresets()
    ) { settings, players, presets ->
        SettingsUiState(settings, players, presets, bggRepository.isConfigured)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SettingsUiState())

    private val _message = MutableStateFlow<DataMessage?>(null)
    val message: StateFlow<DataMessage?> = _message.asStateFlow()

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    private val _importPreview = MutableStateFlow<Pair<Uri, ImportPreview>?>(null)
    val importPreview: StateFlow<Pair<Uri, ImportPreview>?> = _importPreview.asStateFlow()

    private val _restartRequired = MutableStateFlow(false)
    val restartRequired: StateFlow<Boolean> = _restartRequired.asStateFlow()

    fun setTheme(mode: ThemeMode) = viewModelScope.launch { settingsRepository.setTheme(mode) }

    fun setDynamicColor(enabled: Boolean) = viewModelScope.launch { settingsRepository.setDynamicColor(enabled) }

    fun setCurrency(code: String) = viewModelScope.launch { settingsRepository.setCurrency(code) }

    fun setSelf(playerId: Long) = viewModelScope.launch { playerRepository.setSelf(playerId) }

    fun setLendingDays(days: Int) = viewModelScope.launch { settingsRepository.setLendingReminderDays(days) }

    fun setDefaultPreset(id: Long) = viewModelScope.launch { settingsRepository.setDefaultTimerPreset(id) }

    fun setAchievementNotifications(enabled: Boolean) = viewModelScope.launch { settingsRepository.setAchievementNotifications(enabled) }

    fun setBackupsToKeep(count: Int) = viewModelScope.launch { settingsRepository.setBackupsToKeep(count) }

    /**
     * The scheduled job is only registered once a folder exists to write to; enabling it
     * without one would produce a weekly no-op and a false sense of safety.
     */
    fun setScheduledBackup(enabled: Boolean) {
        viewModelScope.launch {
            settingsRepository.setScheduledBackup(enabled)
            val directory = uiState.value.settings.backupDirectoryUri
            if (enabled && directory.isNotBlank()) backupScheduler.enable() else backupScheduler.disable()
        }
    }

    /** Persists the SAF permission so the background job can still write next week. */
    fun setBackupDirectory(uri: Uri) {
        viewModelScope.launch {
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                )
            }
            settingsRepository.setBackupDirectory(uri.toString())
            if (uiState.value.settings.scheduledBackupEnabled) backupScheduler.enable()
        }
    }

    fun exportCsv(treeUri: Uri) = runData {
        val result = csvExporter.exportToDirectory(treeUri)
        DataMessage(
            context.resources.getQuantityString(
                R.plurals.export_done,
                result.totalRows,
                result.totalRows,
                result.location
            )
        )
    }

    fun exportZip(uri: Uri) = runData {
        val result = csvExporter.exportToZip(uri)
        DataMessage(
            context.resources.getQuantityString(
                R.plurals.export_done,
                result.totalRows,
                result.totalRows,
                result.location
            )
        )
    }

    fun suggestedZipName(): String = csvExporter.suggestedZipName()

    fun suggestedBackupName(): String = dbBackup.suggestedFileName()

    /** Reads the file set and reports what would happen, before anything is written. */
    fun previewImport(uri: Uri, mode: ImportMode) {
        viewModelScope.launch {
            _busy.value = true
            runCatching { csvImporter.preview(uri, mode) }
                .onSuccess { _importPreview.value = uri to it }
                .onFailure {
                    _message.value = DataMessage(it.message.orEmpty(), isError = true)
                }
            _busy.value = false
        }
    }

    fun confirmImport(mode: ImportMode) {
        val (uri, _) = _importPreview.value ?: return
        _importPreview.value = null
        runData {
            val result = csvImporter.import(uri, mode)
            DataMessage(
                context.resources.getQuantityString(
                    R.plurals.import_done,
                    result.totalRows,
                    result.totalRows
                )
            )
        }
    }

    fun cancelImport() {
        _importPreview.value = null
    }

    fun backupDatabase(uri: Uri) = runData {
        val bytes = dbBackup.backupTo(uri)
        DataMessage(
            context.getString(
                R.string.backup_done,
                "${bytes / 1024} KB"
            )
        )
    }

    /**
     * Restoring swaps the file Room has open, so the process has to restart afterwards.
     * The flag tells the UI to say so rather than carrying on with a stale handle.
     */
    fun restoreDatabase(uri: Uri) {
        viewModelScope.launch {
            _busy.value = true
            runCatching { dbBackup.restoreFrom(uri) }
                .onSuccess {
                    _restartRequired.value = true
                    _message.value = DataMessage(
                        context.getString(R.string.restore_done)
                    )
                }
                .onFailure {
                    _message.value = DataMessage(it.message.orEmpty(), isError = true)
                }
            _busy.value = false
        }
    }

    fun wipeAll() = runData {
        maintenance.wipeUserData()
        DataMessage(context.getString(R.string.settings_wipe))
    }

    /**
     * Fills an empty install with a generated collection and two years of plays. Only
     * reachable from a debug build; it exists so every chart and achievement rule can be
     * seen working without anybody typing two hundred sessions.
     */
    fun generateFixtures() = runData { DataMessage(devFixtures.generate()) }

    fun clearMessage() {
        _message.value = null
    }

    private fun runData(block: suspend () -> DataMessage) {
        viewModelScope.launch {
            _busy.value = true
            runCatching { block() }
                .onSuccess { _message.value = it }
                .onFailure {
                    _message.value = DataMessage(it.message.orEmpty(), isError = true)
                }
            _busy.value = false
        }
    }
}

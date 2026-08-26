package com.boardgamenation.tracker.ui.settings

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.boardgamenation.tracker.BuildConfig
import com.boardgamenation.tracker.R
import com.boardgamenation.tracker.core.time.DateUtils
import com.boardgamenation.tracker.domain.model.ImportMode
import com.boardgamenation.tracker.domain.model.ThemeMode
import com.boardgamenation.tracker.ui.components.ConfirmDialog
import com.boardgamenation.tracker.ui.components.SectionHeader
import com.boardgamenation.tracker.ui.components.TypedConfirmDialog

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    onOpenPlayers: () -> Unit,
    onOpenRubrics: () -> Unit,
    onOpenAchievements: () -> Unit,
    onOpenBggImport: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()
    val busy by viewModel.busy.collectAsStateWithLifecycle()
    val preview by viewModel.importPreview.collectAsStateWithLifecycle()
    val restartRequired by viewModel.restartRequired.collectAsStateWithLifecycle()

    val snackbarHost = remember { SnackbarHostState() }
    var wipeOpen by remember { mutableStateOf(false) }
    var restoreUri by remember { mutableStateOf<android.net.Uri?>(null) }
    var importMode by remember { mutableStateOf(ImportMode.MERGE) }

    // Storage Access Framework throughout: the user picks the destination and the app
    // never asks for broad storage permission.
    val exportCsvLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree(),
    ) { uri -> uri?.let(viewModel::exportCsv) }

    val exportZipLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/zip"),
    ) { uri -> uri?.let(viewModel::exportZip) }

    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree(),
    ) { uri -> uri?.let { viewModel.previewImport(it, importMode) } }

    val importZipLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri -> uri?.let { viewModel.previewImport(it, importMode) } }

    val backupLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/octet-stream"),
    ) { uri -> uri?.let(viewModel::backupDatabase) }

    val restoreLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri -> restoreUri = uri }

    val backupDirLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree(),
    ) { uri -> uri?.let(viewModel::setBackupDirectory) }

    LaunchedEffect(message) {
        message?.let {
            snackbarHost.showSnackbar(it.text)
            viewModel.clearMessage()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.action_back),
                        )
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHost) },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.padding(padding),
            contentPadding = PaddingValues(bottom = 48.dp),
        ) {
            if (busy) {
                item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
            }

            item { SectionHeader(stringResource(R.string.settings_appearance)) }
            item {
                Column(Modifier.padding(horizontal = 16.dp)) {
                    Text(
                        text = stringResource(R.string.settings_theme),
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        ThemeMode.entries.forEach { mode ->
                            FilterChip(
                                selected = state.settings.themeMode == mode,
                                onClick = { viewModel.setTheme(mode) },
                                label = { Text(stringResource(mode.labelRes())) },
                            )
                        }
                    }
                }
            }
            item {
                SwitchRow(
                    label = stringResource(R.string.settings_dynamic_color),
                    supporting = if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.S) {
                        stringResource(R.string.settings_dynamic_color_unavailable)
                    } else {
                        null
                    },
                    checked = state.settings.dynamicColor,
                    enabled = android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S,
                    onChange = viewModel::setDynamicColor,
                )
            }

            item { SectionHeader(stringResource(R.string.settings_general)) }
            item {
                var currency by remember(state.settings.defaultCurrency) {
                    mutableStateOf(state.settings.defaultCurrency)
                }
                OutlinedTextField(
                    value = currency,
                    onValueChange = {
                        currency = it
                        viewModel.setCurrency(it)
                    },
                    label = { Text(stringResource(R.string.settings_currency)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                )
            }
            item {
                Column(Modifier.padding(16.dp)) {
                    Text(
                        text = stringResource(R.string.settings_self_player),
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        state.players.forEach { player ->
                            FilterChip(
                                selected = player.isSelf,
                                onClick = { viewModel.setSelf(player.id) },
                                label = { Text(player.name) },
                            )
                        }
                    }
                }
            }
            item {
                StepperRow(
                    label = stringResource(R.string.settings_lending_days),
                    value = stringResource(
                        R.string.settings_lending_days_value,
                        state.settings.lendingReminderDays,
                    ),
                    onDecrease = { viewModel.setLendingDays(state.settings.lendingReminderDays - 5) },
                    onIncrease = { viewModel.setLendingDays(state.settings.lendingReminderDays + 5) },
                )
            }

            item { SectionHeader(stringResource(R.string.settings_timer)) }
            item {
                Column(Modifier.padding(horizontal = 16.dp)) {
                    Text(
                        text = stringResource(R.string.settings_default_preset),
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        state.presets.forEach { preset ->
                            FilterChip(
                                selected = state.settings.defaultTimerPresetId == preset.id,
                                onClick = { viewModel.setDefaultPreset(preset.id) },
                                label = { Text(preset.name) },
                            )
                        }
                    }
                }
            }

            item { SectionHeader(stringResource(R.string.settings_achievements)) }
            item {
                SwitchRow(
                    label = stringResource(R.string.settings_achievement_notifications),
                    checked = state.settings.achievementNotifications,
                    onChange = viewModel::setAchievementNotifications,
                )
            }

            item { SectionHeader(stringResource(R.string.settings_data)) }
            item {
                Column {
                    ActionRow(stringResource(R.string.settings_export_csv)) {
                        exportCsvLauncher.launch(null)
                    }
                    ActionRow(stringResource(R.string.settings_export_zip)) {
                        exportZipLauncher.launch(viewModel.suggestedZipName())
                    }
                    ActionRow(stringResource(R.string.settings_import_csv)) {
                        importMode = ImportMode.MERGE
                        importLauncher.launch(null)
                    }
                    ActionRow(
                        "${stringResource(R.string.settings_import_csv)} (${
                            stringResource(R.string.import_mode_replace)
                        })",
                    ) {
                        importMode = ImportMode.REPLACE
                        importZipLauncher.launch(arrayOf("application/zip", "*/*"))
                    }
                    HorizontalDivider()
                    ActionRow(stringResource(R.string.settings_backup_db)) {
                        backupLauncher.launch(viewModel.suggestedBackupName())
                    }
                    ActionRow(stringResource(R.string.settings_restore_db)) {
                        restoreLauncher.launch(arrayOf("*/*"))
                    }
                    ActionRow(
                        label = stringResource(R.string.settings_backup_directory),
                        supporting = state.settings.backupDirectoryUri.ifBlank {
                            stringResource(R.string.settings_backup_directory_none)
                        },
                    ) { backupDirLauncher.launch(null) }
                    SwitchRow(
                        label = stringResource(R.string.settings_scheduled_backup),
                        supporting = if (state.settings.lastBackupAt > 0) {
                            stringResource(
                                R.string.settings_last_backup,
                                DateUtils.epochMillisToIso(state.settings.lastBackupAt),
                            )
                        } else {
                            stringResource(R.string.settings_last_backup_never)
                        },
                        checked = state.settings.scheduledBackupEnabled,
                        onChange = viewModel::setScheduledBackup,
                    )
                    StepperRow(
                        label = stringResource(R.string.settings_backups_to_keep),
                        value = state.settings.backupsToKeep.toString(),
                        onDecrease = { viewModel.setBackupsToKeep(state.settings.backupsToKeep - 1) },
                        onIncrease = { viewModel.setBackupsToKeep(state.settings.backupsToKeep + 1) },
                    )
                    HorizontalDivider()
                    if (BuildConfig.DEBUG) {
                        ActionRow(
                            label = stringResource(R.string.settings_generate_fixtures),
                            supporting = stringResource(R.string.settings_generate_fixtures_help),
                            onClick = viewModel::generateFixtures,
                        )
                    }
                    ActionRow(
                        label = stringResource(R.string.settings_wipe),
                        destructive = true,
                    ) { wipeOpen = true }
                }
            }

            item { SectionHeader(stringResource(R.string.bgg_title)) }
            item {
                ActionRow(
                    label = stringResource(R.string.settings_bgg_status),
                    supporting = stringResource(
                        if (state.bggConfigured) {
                            R.string.settings_bgg_configured
                        } else {
                            R.string.settings_bgg_not_configured
                        },
                    ),
                    onClick = if (state.bggConfigured) onOpenBggImport else null,
                )
            }
            if (!state.bggConfigured) {
                item {
                    Text(
                        text = stringResource(R.string.bgg_disabled_body),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                    )
                }
            }

            item { SectionHeader(stringResource(R.string.settings_about)) }
            item { ActionRow(stringResource(R.string.settings_manage_players), onClick = onOpenPlayers) }
            item { ActionRow(stringResource(R.string.settings_manage_rubrics), onClick = onOpenRubrics) }
            item {
                ActionRow(
                    stringResource(R.string.settings_achievements_link),
                    onClick = onOpenAchievements,
                )
            }
            item {
                Column(Modifier.padding(16.dp)) {
                    Text(
                        text = stringResource(R.string.settings_version, BuildConfig.VERSION_NAME),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    // Required attribution wherever BGG data is displayed.
                    Text(
                        text = stringResource(R.string.bgg_attribution),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }

    if (wipeOpen) {
        TypedConfirmDialog(
            title = stringResource(R.string.settings_wipe_title),
            body = stringResource(R.string.settings_wipe_body),
            requiredWord = stringResource(R.string.settings_wipe_confirm_word),
            confirmLabel = stringResource(R.string.settings_wipe),
            onConfirm = {
                wipeOpen = false
                viewModel.wipeAll()
            },
            onDismiss = { wipeOpen = false },
        )
    }

    restoreUri?.let { uri ->
        ConfirmDialog(
            title = stringResource(R.string.restore_title),
            body = stringResource(R.string.restore_body),
            destructive = true,
            onConfirm = {
                restoreUri = null
                viewModel.restoreDatabase(uri)
            },
            onDismiss = { restoreUri = null },
        )
    }

    // The preview is the last chance to back out, and it quotes real numbers rather than
    // asking the user to trust the file.
    preview?.let { (_, summary) ->
        val replacing = importMode == ImportMode.REPLACE
        AlertDialog(
            onDismissRequest = viewModel::cancelImport,
            title = { Text(stringResource(R.string.import_preview_title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(summary.describe())
                    if (summary.errors.isNotEmpty()) {
                        Text(
                            text = stringResource(
                                R.string.import_preview_errors, summary.errors.size,
                            ),
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    if (summary.missingFiles.isNotEmpty()) {
                        Text(
                            text = stringResource(
                                R.string.import_preview_missing,
                                summary.missingFiles.joinToString(", "),
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    summary.headerProblems.forEach {
                        Text(
                            text = it,
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    if (replacing) {
                        Text(
                            text = stringResource(R.string.import_mode_replace_help),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = { viewModel.confirmImport(importMode) },
                    enabled = summary.canProceed,
                ) { Text(stringResource(R.string.import_run)) }
            },
            dismissButton = {
                TextButton(onClick = viewModel::cancelImport) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
        )
    }

    if (restartRequired) {
        AlertDialog(
            onDismissRequest = {},
            title = { Text(stringResource(R.string.settings_restore_db)) },
            text = { Text(stringResource(R.string.restore_done)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        // Room holds an open handle to the file that was just replaced;
                        // the only safe thing to do is start over.
                        android.os.Process.killProcess(android.os.Process.myPid())
                    },
                ) { Text(stringResource(R.string.action_continue)) }
            },
        )
    }
}

@Composable
private fun SwitchRow(
    label: String,
    checked: Boolean,
    onChange: (Boolean) -> Unit,
    supporting: String? = null,
    enabled: Boolean = true,
) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyLarge)
            supporting?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Switch(checked = checked, onCheckedChange = onChange, enabled = enabled)
    }
}

@Composable
private fun ActionRow(
    label: String,
    supporting: String? = null,
    destructive: Boolean = false,
    onClick: (() -> Unit)? = null,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge,
            color = if (destructive) {
                MaterialTheme.colorScheme.error
            } else {
                MaterialTheme.colorScheme.onSurface
            },
        )
        supporting?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
            )
        }
    }
}

@Composable
private fun StepperRow(
    label: String,
    value: String,
    onDecrease: () -> Unit,
    onIncrease: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        IconButton(onClick = onDecrease) {
            Icon(
                Icons.Filled.Remove,
                contentDescription = stringResource(R.string.action_decrease),
            )
        }
        Text(value, style = MaterialTheme.typography.bodyLarge)
        IconButton(onClick = onIncrease) {
            Icon(
                Icons.Filled.Add,
                contentDescription = stringResource(R.string.action_increase),
            )
        }
    }
}

private fun ThemeMode.labelRes(): Int = when (this) {
    ThemeMode.LIGHT -> R.string.settings_theme_light
    ThemeMode.DARK -> R.string.settings_theme_dark
    ThemeMode.SYSTEM -> R.string.settings_theme_system
}

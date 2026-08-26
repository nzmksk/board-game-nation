package com.boardgamenation.tracker.ui.timer

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.boardgamenation.tracker.R
import com.boardgamenation.tracker.ui.components.PlayerDot
import com.boardgamenation.tracker.ui.components.SectionHeader
import com.boardgamenation.tracker.ui.theme.LocalChartColors

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun TimerSetupScreen(
    onStarted: () -> Unit,
    viewModel: TimerViewModel = hiltViewModel(),
) {
    val state by viewModel.setupState.collectAsStateWithLifecycle()
    val projection by viewModel.projection.collectAsStateWithLifecycle()
    var presetDialogOpen by remember { mutableStateOf(false) }
    val context = LocalContext.current

    /*
     * The clock runs in a foreground service, and from Android 13 its notification needs
     * a runtime grant. The timer works either way — the service and the wake lock do not
     * depend on it — so a refusal starts the clock anyway rather than blocking the game.
     */
    val notificationPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { viewModel.start(onStarted) }

    fun startTimer() {
        val needsPermission = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS,
            ) != PackageManager.PERMISSION_GRANTED
        if (needsPermission) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            viewModel.start(onStarted)
        }
    }

    // A clock recovered from a previous process, or one left running while the user
    // wandered off to another screen, goes straight back to the running view.
    LaunchedEffect(projection) { if (projection != null) onStarted() }

    Scaffold(
        topBar = { TopAppBar(title = { Text(stringResource(R.string.timer_setup_title)) }) },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item { SectionHeader(stringResource(R.string.timer_setup_game)) }
            item { GameDropdown(state, viewModel::selectGame) }

            item { SectionHeader(stringResource(R.string.timer_setup_players)) }
            item {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    state.players.forEach { player ->
                        FilterChip(
                            selected = state.seating.any { it.id == player.id },
                            onClick = { viewModel.togglePlayer(player) },
                            label = { Text(player.name) },
                        )
                    }
                }
            }

            // The seating list is the turn order, so it is editable in place rather than
            // being a separate "order" step.
            items(state.seating.size) { index ->
                val player = state.seating[index]
                val chartColors = LocalChartColors.current
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                    ),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Row(
                        Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = stringResource(R.string.timer_seat_position, index + 1),
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.width(8.dp))
                        PlayerDot(chartColors.forPlayer(player.colorHex, index))
                        Spacer(Modifier.width(8.dp))
                        Text(player.name, modifier = Modifier.weight(1f))
                        IconButton(onClick = { viewModel.movePlayer(player.id, -1) }) {
                            Icon(
                                Icons.Filled.ArrowUpward,
                                contentDescription = stringResource(R.string.session_edit_move_up),
                            )
                        }
                        IconButton(onClick = { viewModel.movePlayer(player.id, 1) }) {
                            Icon(
                                Icons.Filled.ArrowDownward,
                                contentDescription = stringResource(R.string.session_edit_move_down),
                            )
                        }
                    }
                }
            }

            item { SectionHeader(stringResource(R.string.timer_setup_preset)) }
            item {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    state.presets.forEach { preset ->
                        FilterChip(
                            selected = false,
                            onClick = { viewModel.applyPreset(preset.id) },
                            label = { Text(preset.name) },
                        )
                    }
                    FilterChip(
                        selected = false,
                        onClick = { presetDialogOpen = true },
                        label = { Text(stringResource(R.string.timer_setup_save_preset)) },
                    )
                }
            }

            item {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    SecondsField(
                        value = state.turnSeconds,
                        label = stringResource(R.string.timer_setup_turn_seconds),
                        onChange = { value -> viewModel.updateSetup { it.copy(turnSeconds = value) } },
                        modifier = Modifier.weight(1f),
                    )
                    SecondsField(
                        value = state.bankSeconds,
                        label = stringResource(R.string.timer_setup_bank_seconds),
                        onChange = { value -> viewModel.updateSetup { it.copy(bankSeconds = value) } },
                        modifier = Modifier.weight(1f),
                    )
                    SecondsField(
                        value = state.warningSeconds,
                        label = stringResource(R.string.timer_setup_warning_seconds),
                        onChange = { value ->
                            viewModel.updateSetup { it.copy(warningSeconds = value) }
                        },
                        modifier = Modifier.weight(1f),
                    )
                }
            }

            item {
                ToggleRow(
                    stringResource(R.string.timer_setup_sound),
                    state.soundEnabled,
                ) { value -> viewModel.updateSetup { it.copy(soundEnabled = value) } }
            }
            item {
                ToggleRow(
                    stringResource(R.string.timer_setup_haptics),
                    state.hapticsEnabled,
                ) { value -> viewModel.updateSetup { it.copy(hapticsEnabled = value) } }
            }
            item {
                Column {
                    ToggleRow(
                        stringResource(R.string.timer_setup_auto_pass),
                        state.autoPass,
                    ) { value -> viewModel.updateSetup { it.copy(autoPass = value) } }
                    Text(
                        text = stringResource(R.string.timer_setup_auto_pass_help),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            item {
                ToggleRow(
                    stringResource(R.string.timer_keep_screen_on),
                    state.keepScreenOn,
                    viewModel::setKeepScreenOn,
                )
            }

            item {
                Button(
                    onClick = ::startTimer,
                    enabled = state.canStart,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(stringResource(R.string.timer_setup_start)) }
            }

            if (!state.canStart) {
                item {
                    Text(
                        text = stringResource(
                            if (state.gameId == 0L) R.string.timer_no_game
                            else R.string.timer_setup_needs_players,
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            item { Spacer(Modifier.height(32.dp)) }
        }
    }

    if (presetDialogOpen) {
        var name by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { presetDialogOpen = false },
            title = { Text(stringResource(R.string.timer_setup_save_preset)) },
            text = {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    singleLine = true,
                    label = { Text(stringResource(R.string.timer_setup_preset_name)) },
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.savePreset(name)
                        presetDialogOpen = false
                    },
                    enabled = name.isNotBlank(),
                ) { Text(stringResource(R.string.action_save)) }
            },
            dismissButton = {
                TextButton(onClick = { presetDialogOpen = false }) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
        )
    }
}

@Composable
private fun GameDropdown(state: TimerSetupState, onSelect: (Long) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        OutlinedButton(onClick = { open = true }, modifier = Modifier.fillMaxWidth()) {
            Text(state.selectedGame?.title ?: stringResource(R.string.timer_no_game))
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            state.games.forEach { game ->
                DropdownMenuItem(
                    text = { Text(game.title) },
                    onClick = {
                        onSelect(game.id)
                        open = false
                    },
                )
            }
        }
    }
}

@Composable
private fun SecondsField(
    value: Int,
    label: String,
    onChange: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    OutlinedTextField(
        value = value.toString(),
        onValueChange = { input ->
            onChange(input.filter { it.isDigit() }.toIntOrNull() ?: 0)
        },
        label = { Text(label) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = modifier,
    )
}

@Composable
private fun ToggleRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

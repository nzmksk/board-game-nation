package com.boardgamenation.tracker.ui.timer

import android.app.Activity
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.view.WindowManager
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.boardgamenation.tracker.R
import com.boardgamenation.tracker.core.time.DurationFormat
import com.boardgamenation.tracker.domain.model.ActiveClock
import com.boardgamenation.tracker.domain.timer.SeatDisplay
import com.boardgamenation.tracker.domain.timer.TimerProjection
import com.boardgamenation.tracker.timer.TimerEvent
import com.boardgamenation.tracker.timer.TimerSummary
import com.boardgamenation.tracker.ui.theme.LocalChartColors
import com.boardgamenation.tracker.ui.theme.TimerDisplayStyle
import com.boardgamenation.tracker.ui.theme.TimerSecondaryStyle

/**
 * The running clock.
 *
 * Laid out for a phone flat on a table between people: large per-player zones, each one
 * a single tap target that ends that player's turn. The active zone is filled with the
 * player's colour and everyone else is outlined, so who is up is legible from across the
 * table without reading anything.
 */
@Composable
fun TimerRunningScreen(
    onExit: () -> Unit,
    onSaveSession: (gameId: Long, sessionId: Long) -> Unit,
    viewModel: TimerViewModel = hiltViewModel()
) {
    val projection by viewModel.projection.collectAsStateWithLifecycle()
    val summary by viewModel.summary.collectAsStateWithLifecycle()
    val setup by viewModel.setupState.collectAsStateWithLifecycle()
    var stopPromptOpen by remember { mutableStateOf(false) }

    KeepScreenOn(enabled = setup.keepScreenOn && projection != null)
    TimerFeedback(
        viewModel,
        soundEnabled = projection?.state?.config?.soundEnabled ?: false,
        hapticsEnabled = projection?.state?.config?.hapticsEnabled ?: false
    )

    val current = projection
    if (current == null) {
        LaunchedEffect(Unit) { onExit() }
        return
    }

    val landscape = LocalConfiguration.current.screenWidthDp > LocalConfiguration.current.screenHeightDp

    if (current.state.isCountUp) {
        CountUpBoard(
            projection = current,
            onPause = viewModel::pause,
            onResume = viewModel::resume,
            onStop = {
                viewModel.stop()
                stopPromptOpen = true
            }
        )
        StopPrompt(
            open = stopPromptOpen,
            summary = summary,
            onDismiss = { stopPromptOpen = false },
            onSave = { stopped ->
                stopPromptOpen = false
                onSaveSession(stopped.gameId, stopped.sessionId ?: 0L)
                viewModel.releaseAfterSave()
            },
            onExit = {
                stopPromptOpen = false
                onExit()
            },
            onDiscard = {
                stopPromptOpen = false
                viewModel.discard()
                onExit()
            }
        )
        return
    }

    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        ControlBar(
            projection = current,
            onPause = viewModel::pause,
            onResume = viewModel::resume,
            onUndo = viewModel::undo,
            onReverse = viewModel::reverse,
            onStop = {
                viewModel.stop()
                stopPromptOpen = true
            }
        )

        if (landscape) {
            Row(
                modifier = Modifier.fillMaxSize().padding(8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                current.seats.forEachIndexed { index, seat ->
                    PlayerZone(
                        seat = seat,
                        index = index,
                        projection = current,
                        onTap = {
                            if (seat.isActive) viewModel.passTurn() else viewModel.selectSeat(index)
                        },
                        onLongPress = { viewModel.toggleSkip(index) },
                        modifier = Modifier.weight(1f).fillMaxHeight()
                    )
                }
            }
        } else {
            Column(
                modifier = Modifier.fillMaxSize().padding(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                current.seats.forEachIndexed { index, seat ->
                    PlayerZone(
                        seat = seat,
                        index = index,
                        projection = current,
                        onTap = {
                            if (seat.isActive) viewModel.passTurn() else viewModel.selectSeat(index)
                        },
                        onLongPress = { viewModel.toggleSkip(index) },
                        modifier = Modifier.weight(1f).fillMaxWidth()
                    )
                }
            }
        }
    }

    StopPrompt(
        open = stopPromptOpen,
        summary = summary,
        onDismiss = { stopPromptOpen = false },
        onSave = { stopped ->
            stopPromptOpen = false
            onSaveSession(stopped.gameId, stopped.sessionId ?: 0L)
            viewModel.releaseAfterSave()
        },
        onExit = {
            stopPromptOpen = false
            onExit()
        },
        onDiscard = {
            stopPromptOpen = false
            viewModel.discard()
            onExit()
        }
    )
}

/** Save or throw away the play that just finished. Shared by both clocks. */
@Composable
private fun StopPrompt(
    open: Boolean,
    summary: TimerSummary?,
    onDismiss: () -> Unit,
    onSave: (TimerSummary) -> Unit,
    onExit: () -> Unit,
    onDiscard: () -> Unit
) {
    if (!open) return
    val played = summary?.durationMinutes ?: 0
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.timer_stop_title)) },
        text = { Text(stringResource(R.string.timer_stop_body, DurationFormat.minutes(played))) },
        confirmButton = {
            TextButton(
                onClick = {
                    val stopped = summary
                    if (stopped != null) onSave(stopped) else onExit()
                }
            ) { Text(stringResource(R.string.timer_stop_save)) }
        },
        dismissButton = {
            TextButton(onClick = onDiscard) {
                Text(
                    text = stringResource(R.string.timer_stop_discard),
                    color = MaterialTheme.colorScheme.error
                )
            }
        }
    )
}

/**
 * The whole-game clock.
 *
 * One number, counting up, big enough to read from across the table. There is nothing to
 * tap through: the game is either running or paused, and it ends when somebody says so.
 */
@Composable
private fun CountUpBoard(projection: TimerProjection, onPause: () -> Unit, onResume: () -> Unit, onStop: () -> Unit) {
    val running = projection.state.isRunning
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            text = stringResource(R.string.timer_count_up_elapsed),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = DurationFormat.longClock(projection.elapsedPlayMs),
            style = TimerDisplayStyle,
            color = MaterialTheme.colorScheme.onBackground,
            textAlign = TextAlign.Center
        )
        if (!running) {
            Text(
                text = stringResource(R.string.timer_paused),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.tertiary
            )
        }

        Spacer(Modifier.height(32.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            IconButton(onClick = if (running) onPause else onResume) {
                Icon(
                    imageVector = if (running) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                    contentDescription = stringResource(
                        if (running) R.string.timer_action_pause else R.string.timer_action_resume
                    )
                )
            }
            IconButton(onClick = onStop) {
                Icon(
                    Icons.Filled.Stop,
                    contentDescription = stringResource(R.string.timer_action_stop),
                    tint = MaterialTheme.colorScheme.error
                )
            }
        }

        if (projection.seats.isNotEmpty()) {
            Spacer(Modifier.height(32.dp))
            Text(
                text = stringResource(R.string.timer_count_up_at_table),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = projection.seats.joinToString(", ") { it.name },
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center
            )
        }
    }
}

@Composable
private fun ControlBar(
    projection: TimerProjection,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onUndo: () -> Unit,
    onReverse: () -> Unit,
    onStop: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        val running = projection.state.isRunning
        IconButton(onClick = if (running) onPause else onResume) {
            Icon(
                imageVector = if (running) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                contentDescription = stringResource(
                    if (running) R.string.timer_action_pause else R.string.timer_action_resume
                )
            )
        }
        IconButton(
            onClick = onUndo,
            enabled = projection.state.undoSnapshot != null
        ) {
            Icon(
                Icons.AutoMirrored.Filled.Undo,
                contentDescription = stringResource(R.string.timer_action_undo)
            )
        }
        IconButton(onClick = onReverse) {
            Icon(
                Icons.Filled.SwapHoriz,
                contentDescription = stringResource(R.string.timer_action_reverse)
            )
        }
        Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = stringResource(
                    R.string.timer_elapsed,
                    DurationFormat.longClock(projection.elapsedPlayMs)
                ),
                style = MaterialTheme.typography.labelMedium
            )
            if (!running) {
                Text(
                    text = stringResource(R.string.timer_paused),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.tertiary
                )
            }
        }
        IconButton(onClick = onStop) {
            Icon(
                Icons.Filled.Stop,
                contentDescription = stringResource(R.string.timer_action_stop),
                tint = MaterialTheme.colorScheme.error
            )
        }
    }
}

@Composable
private fun PlayerZone(
    seat: SeatDisplay,
    index: Int,
    projection: TimerProjection,
    onTap: () -> Unit,
    onLongPress: () -> Unit,
    modifier: Modifier = Modifier
) {
    val chartColors = LocalChartColors.current
    val playerColor = chartColors.forPlayer(seat.colorHex, index)

    // The warning shift is a colour change on top of the size and position cues, never
    // the only signal: the number itself is also counting down in plain sight.
    val warning = seat.isActive && projection.isWarning
    val timedOut = seat.bankRemainingMs <= 0

    val background by animateColorAsState(
        targetValue = when {
            !seat.isActive -> MaterialTheme.colorScheme.surfaceContainerLow
            timedOut -> chartColors.critical
            warning -> chartColors.warning
            else -> playerColor
        },
        label = "zoneBackground"
    )

    val onBackground = if (seat.isActive) {
        contrastingInk(background)
    } else {
        MaterialTheme.colorScheme.onSurface
    }

    Box(
        modifier = modifier
            .clip(RoundedCornerShape(16.dp))
            .background(background)
            .border(
                width = if (seat.isActive) 0.dp else 2.dp,
                color = playerColor,
                shape = RoundedCornerShape(16.dp)
            )
            .clickable(onClick = onTap)
            .semantics {
                contentDescription = "${seat.name}, ${DurationFormat.clock(
                    if (seat.isActive) projection.displayMs else seat.turnRemainingMs
                )}"
            }
            .padding(12.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = seat.name,
                style = MaterialTheme.typography.titleMedium,
                color = onBackground,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center
            )
            Spacer(Modifier.height(4.dp))

            if (seat.isActive) {
                Text(
                    text = DurationFormat.clock(projection.displayMs),
                    style = TimerDisplayStyle,
                    color = onBackground
                )
                Text(
                    text = stringResource(
                        when (projection.activeClock) {
                            ActiveClock.TURN -> R.string.timer_clock_turn
                            ActiveClock.BANK -> R.string.timer_clock_bank
                            ActiveClock.OVERTIME -> R.string.timer_clock_overtime
                        }
                    ),
                    style = MaterialTheme.typography.labelMedium,
                    color = onBackground
                )
            } else {
                Text(
                    text = DurationFormat.clock(seat.turnRemainingMs.coerceAtLeast(0)),
                    style = TimerSecondaryStyle,
                    color = onBackground
                )
            }

            Spacer(Modifier.height(4.dp))
            Text(
                text = stringResource(
                    R.string.timer_bank_label,
                    DurationFormat.longClock(seat.bankRemainingMs)
                ),
                style = MaterialTheme.typography.labelSmall,
                color = onBackground
            )
            Text(
                text = stringResource(R.string.timer_turns_taken, seat.turnsTaken),
                style = MaterialTheme.typography.labelSmall,
                color = onBackground
            )

            if (seat.skipped) {
                Text(
                    text = stringResource(R.string.timer_skipped),
                    style = MaterialTheme.typography.labelSmall,
                    color = onBackground
                )
            }
            if (timedOut) {
                Text(
                    text = stringResource(R.string.timer_timed_out),
                    style = MaterialTheme.typography.labelSmall,
                    color = onBackground
                )
            }
        }
    }
}

/** Picks black or white ink for whatever colour the player happens to have chosen. */
private fun contrastingInk(background: Color): Color = if (background.luminance() > 0.5f) Color.Black else Color.White

/**
 * Sound and vibration for the warning threshold and for a bank running out.
 *
 * Both go through the notification stream, so silent mode silences them: the timer is a
 * table aid, not something that should override the phone's own settings.
 */
@Composable
private fun TimerFeedback(viewModel: TimerViewModel, soundEnabled: Boolean, hapticsEnabled: Boolean) {
    val context = LocalContext.current
    LaunchedEffect(soundEnabled, hapticsEnabled) {
        viewModel.events.collect { event ->
            val (tone, pattern) = when (event) {
                is TimerEvent.Warning -> ToneGenerator.TONE_PROP_BEEP to 120L
                is TimerEvent.BankExhausted -> ToneGenerator.TONE_PROP_BEEP2 to 400L
                else -> return@collect
            }
            if (soundEnabled) {
                runCatching {
                    ToneGenerator(AudioManager.STREAM_NOTIFICATION, 70).apply {
                        startTone(tone, 200)
                    }
                }
            }
            if (hapticsEnabled) {
                runCatching {
                    val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        (context.getSystemService(VibratorManager::class.java))?.defaultVibrator
                    } else {
                        @Suppress("DEPRECATION")
                        context.getSystemService(Vibrator::class.java)
                    }
                    vibrator?.vibrate(
                        VibrationEffect.createOneShot(pattern, VibrationEffect.DEFAULT_AMPLITUDE)
                    )
                }
            }
        }
    }
}

/** Keeps the display awake while the clock is on screen, and releases it on the way out. */
@Composable
private fun KeepScreenOn(enabled: Boolean) {
    val context = LocalContext.current
    DisposableEffect(enabled) {
        val window = (context as? Activity)?.window
        if (enabled) {
            window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
        onDispose { window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
    }
}

package com.boardgamenation.tracker.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Casino
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.request.crossfade
import com.boardgamenation.tracker.R
import com.boardgamenation.tracker.core.time.DateUtils
import java.io.File
import java.time.ZoneOffset

/** The standard "there is nothing here, and here is what to do about it" panel. */
@Composable
fun EmptyState(
    title: String,
    body: String? = null,
    icon: ImageVector? = null,
    modifier: Modifier = Modifier,
    action: (@Composable () -> Unit)? = null
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        if (icon != null) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(48.dp)
            )
        }
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            textAlign = TextAlign.Center
        )
        if (body != null) {
            Text(
                text = body,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
        }
        action?.invoke()
    }
}

@Composable
fun SectionHeader(title: String, modifier: Modifier = Modifier, trailing: (@Composable () -> Unit)? = null) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.weight(1f)
        )
        trailing?.invoke()
    }
}

/**
 * A single headline figure. Deliberately not a chart: one number with a label reads
 * faster than any plot of one number could.
 */
@Composable
fun StatTile(label: String, value: String, modifier: Modifier = Modifier, supporting: String? = null) {
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer
        )
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = value,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1
            )
            if (supporting != null) {
                Text(
                    text = supporting,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

/**
 * Covers are rendered from a local file, never a remote url.
 *
 * The image was downloaded once at import time and lives in app-private storage, which
 * is what lets a 500-game list scroll without touching the network and keeps the
 * collection fully usable in airplane mode.
 */
@Composable
fun GameThumbnail(path: String?, title: String, modifier: Modifier = Modifier, size: androidx.compose.ui.unit.Dp = 56.dp) {
    val shape = RoundedCornerShape(8.dp)
    Box(
        modifier = modifier
            .size(size)
            .clip(shape)
            .background(MaterialTheme.colorScheme.surfaceContainerHighest),
        contentAlignment = Alignment.Center
    ) {
        val file = remember(path) { path?.let(::File)?.takeIf { it.exists() } }
        if (file != null) {
            AsyncImage(
                model = ImageRequest.Builder(androidx.compose.ui.platform.LocalContext.current)
                    .data(file)
                    .crossfade(true)
                    .build(),
                contentDescription = stringResource(R.string.cd_game_thumbnail, title),
                modifier = Modifier.fillMaxSize()
            )
        } else {
            Icon(
                imageVector = Icons.Filled.Casino,
                contentDescription = stringResource(R.string.cd_no_thumbnail),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(size / 2)
            )
        }
    }
}

/** A small colour dot always shown beside a name, never as the only cue. */
@Composable
fun PlayerDot(color: Color, modifier: Modifier = Modifier, size: androidx.compose.ui.unit.Dp = 10.dp) {
    Box(
        modifier = modifier
            .size(size)
            .clip(CircleShape)
            .background(color)
    )
}

@Composable
fun ConfirmDialog(
    title: String,
    body: String,
    confirmLabel: String = stringResource(R.string.action_confirm),
    dismissLabel: String = stringResource(R.string.action_cancel),
    destructive: Boolean = false,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(body) },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(
                    text = confirmLabel,
                    color = if (destructive) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.primary
                    }
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(dismissLabel) }
        }
    )
}

/**
 * Confirmation that cannot be given by muscle memory.
 *
 * Reserved for the two operations that destroy data outright — erasing everything, and a
 * replace-mode import — where a mis-tap would be unrecoverable.
 */
@Composable
fun TypedConfirmDialog(
    title: String,
    body: String,
    requiredWord: String,
    confirmLabel: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    var typed by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(body)
                OutlinedTextField(
                    value = typed,
                    onValueChange = { typed = it },
                    singleLine = true,
                    label = { Text(requiredWord) }
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = onConfirm,
                enabled = typed.trim().equals(requiredWord, ignoreCase = false)
            ) {
                Text(confirmLabel, color = MaterialTheme.colorScheme.error)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } }
    )
}

/** A progress bar with a caption, for imports and exports. */
@Composable
fun LabelledProgress(label: String, fraction: Float?, modifier: Modifier = Modifier) {
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(label, style = MaterialTheme.typography.bodyMedium)
        if (fraction == null) {
            LinearProgressIndicator(Modifier.fillMaxWidth())
        } else {
            LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth())
        }
    }
}

/**
 * A neutral placeholder shown while the first database read resolves, so lists do not
 * flash an "empty" state that is about to be contradicted.
 */
@Composable
fun LoadingRows(count: Int = 5, modifier: Modifier = Modifier) {
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        repeat(count) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(72.dp)
                    .padding(horizontal = 16.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            )
        }
    }
}

@Composable
fun KeyValueRow(label: String, value: String, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp),
        verticalAlignment = Alignment.Top
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(140.dp)
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f)
        )
    }
}

/**
 * A date field that can only be filled from a calendar.
 *
 * Dates are stored as ISO-8601 text throughout, which sorts correctly as a string and
 * survives a CSV round trip regardless of locale, but it only holds if what reaches the
 * database is actually a date. A free-text field accepted anything: "banana" would
 * persist happily into a NOT NULL column and then break the substr-based year statistics
 * and the ordering of every query that sorts on a date.
 *
 * So the field is read-only and the picker is the only way in. [value] and [onChange]
 * both speak ISO text; an unparseable stored value simply opens the picker on today
 * rather than refusing to open.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun IsoDateField(value: String, label: String, onChange: (String) -> Unit, modifier: Modifier = Modifier) {
    var open by remember { mutableStateOf(false) }

    OutlinedTextField(
        value = value,
        onValueChange = {},
        readOnly = true,
        label = { Text(label) },
        singleLine = true,
        trailingIcon = {
            IconButton(onClick = { open = true }) {
                Icon(
                    Icons.Filled.DateRange,
                    contentDescription = stringResource(R.string.action_pick_date)
                )
            }
        },
        modifier = modifier
    )

    if (open) {
        // The picker works in UTC millis; the conversion back to a local ISO date has to
        // go through UTC too, or a user east of Greenwich picking a date late in the day
        // would get the one before it.
        val state = rememberDatePickerState(
            initialSelectedDateMillis = DateUtils.parseIsoOrNull(value)
                ?.atStartOfDay(ZoneOffset.UTC)
                ?.toInstant()
                ?.toEpochMilli()
        )
        DatePickerDialog(
            onDismissRequest = { open = false },
            confirmButton = {
                TextButton(
                    onClick = {
                        state.selectedDateMillis?.let {
                            onChange(DateUtils.epochMillisToIso(it, ZoneOffset.UTC))
                        }
                        open = false
                    }
                ) { Text(stringResource(R.string.action_ok)) }
            },
            dismissButton = {
                TextButton(onClick = { open = false }) {
                    Text(stringResource(R.string.action_cancel))
                }
            }
        ) {
            DatePicker(state = state)
        }
    }
}

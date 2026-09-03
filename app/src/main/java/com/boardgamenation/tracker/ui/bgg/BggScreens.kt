package com.boardgamenation.tracker.ui.bgg

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.boardgamenation.tracker.R
import com.boardgamenation.tracker.ui.components.EmptyState
import com.boardgamenation.tracker.ui.components.LabelledProgress

/**
 * Shown wherever a BGG feature would be, when no token is configured.
 *
 * Deliberately explanatory rather than apologetic: this is a documented state with a
 * documented fix, and the rest of the app works without it.
 */
@Composable
fun BggUnconfigured(modifier: Modifier = Modifier) {
    EmptyState(
        title = stringResource(R.string.bgg_disabled_title),
        body = stringResource(R.string.bgg_disabled_body),
        modifier = modifier
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BggSearchScreen(onBack: () -> Unit, onImported: (Long) -> Unit, viewModel: BggViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.bgg_search_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.action_back)
                        )
                    }
                }
            )
        }
    ) { padding ->
        if (!state.configured) {
            BggUnconfigured(Modifier.padding(padding))
            return@Scaffold
        }

        Column(Modifier.padding(padding).padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = state.query,
                    onValueChange = viewModel::setQuery,
                    label = { Text(stringResource(R.string.bgg_search_hint)) },
                    singleLine = true,
                    modifier = Modifier.weight(1f)
                )
                Spacer(Modifier.width(8.dp))
                Button(onClick = viewModel::search, enabled = !state.isBusy) {
                    Text(stringResource(R.string.action_search))
                }
            }

            Spacer(Modifier.height(12.dp))
            ErrorBanner(state, onRetry = viewModel::search, onDismiss = viewModel::dismissError)

            if (state.isBusy) {
                LabelledProgress(stringResource(R.string.bgg_fetching), null)
            }

            LazyColumn(contentPadding = PaddingValues(vertical = 8.dp)) {
                items(state.searchResults.size, key = { state.searchResults[it].bggId }) { index ->
                    val result = state.searchResults[index]
                    val owned = result.bggId in state.alreadyOwned
                    Card(
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceContainerLow
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 3.dp)
                            .clickable(enabled = !owned && !state.isBusy) {
                                viewModel.importSingle(result.bggId, onImported)
                            }
                    ) {
                        Column(Modifier.padding(12.dp)) {
                            Text(result.name, style = MaterialTheme.typography.titleSmall)
                            Text(
                                text = buildString {
                                    result.yearPublished?.let { append(it) }
                                    if (result.isExpansion) {
                                        if (isNotEmpty()) append(" · ")
                                        append(stringResource(R.string.game_edit_is_expansion))
                                    }
                                    if (owned) {
                                        if (isNotEmpty()) append(" · ")
                                        append(stringResource(R.string.bgg_already_owned))
                                    }
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
                if (state.searchResults.isEmpty() && !state.isBusy && state.query.isNotBlank()) {
                    item {
                        Text(
                            text = stringResource(R.string.bgg_no_results),
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.padding(16.dp)
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BggImportScreen(onBack: () -> Unit, viewModel: BggViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.bgg_import_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.action_back)
                        )
                    }
                }
            )
        }
    ) { padding ->
        if (!state.configured) {
            BggUnconfigured(Modifier.padding(padding))
            return@Scaffold
        }

        Column(Modifier.padding(padding).padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = state.username,
                    onValueChange = viewModel::setUsername,
                    label = { Text(stringResource(R.string.bgg_username)) },
                    singleLine = true,
                    modifier = Modifier.weight(1f)
                )
                Spacer(Modifier.width(8.dp))
                Button(onClick = viewModel::fetchCollection, enabled = !state.isBusy) {
                    Text(stringResource(R.string.bgg_fetch))
                }
            }

            Spacer(Modifier.height(12.dp))
            ErrorBanner(
                state,
                onRetry = viewModel::fetchCollection,
                onDismiss = viewModel::dismissError
            )

            // The collection endpoint queues on BGG's side, so the wait is explained
            // rather than shown as an indefinite spinner.
            state.queuedRetrySeconds?.let { seconds ->
                Text(
                    text = stringResource(R.string.bgg_queued, seconds),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.tertiary,
                    modifier = Modifier.padding(vertical = 8.dp)
                )
            }

            state.progress?.let { (done, total) ->
                LabelledProgress(
                    label = "$done / $total",
                    fraction = if (total > 0) done.toFloat() / total else null
                )
                Spacer(Modifier.height(8.dp))
            }

            if (state.isBusy && state.progress == null && state.queuedRetrySeconds == null) {
                LabelledProgress(stringResource(R.string.bgg_fetching), null)
            }

            state.importedCount?.let { count ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = stringResource(R.string.bgg_import_done, count),
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.weight(1f)
                    )
                    TextButton(onClick = viewModel::dismissImported) {
                        Text(stringResource(R.string.action_close))
                    }
                }
            }

            if (state.collectionItems.isNotEmpty()) {
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TextButton(onClick = viewModel::selectAll) {
                        Text(stringResource(R.string.action_select_all))
                    }
                    TextButton(onClick = viewModel::clearSelection) {
                        Text(stringResource(R.string.action_clear))
                    }
                    Spacer(Modifier.weight(1f))
                    Button(
                        onClick = viewModel::importSelected,
                        enabled = state.selected.isNotEmpty() && !state.isBusy
                    ) {
                        Text(stringResource(R.string.bgg_import_selected, state.selected.size))
                    }
                }
            }

            LazyColumn(contentPadding = PaddingValues(vertical = 8.dp)) {
                items(
                    state.collectionItems.size,
                    key = { state.collectionItems[it].bggId }
                ) { index ->
                    val item = state.collectionItems[index]
                    val owned = item.bggId in state.alreadyOwned
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable { viewModel.toggleSelection(item.bggId) }
                            .padding(vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Checkbox(
                            checked = item.bggId in state.selected,
                            onCheckedChange = { viewModel.toggleSelection(item.bggId) }
                        )
                        Column(Modifier.weight(1f)) {
                            Text(
                                text = item.name,
                                style = MaterialTheme.typography.bodyLarge,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                text = buildString {
                                    item.yearPublished?.let { append(it) }
                                    if (owned) {
                                        if (isNotEmpty()) append(" · ")
                                        append(stringResource(R.string.bgg_already_owned))
                                    }
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }

            Text(
                text = stringResource(R.string.bgg_attribution),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp)
            )
        }
    }
}

@Composable
private fun ErrorBanner(state: BggUiState, onRetry: () -> Unit, onDismiss: () -> Unit) {
    val message = state.errorMessage ?: return
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer
        ),
        modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)
    ) {
        Column(Modifier.padding(12.dp)) {
            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onErrorContainer
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                // Retry is only offered when it could plausibly work: a rejected token
                // will not fix itself.
                if (state.errorRetryable) {
                    OutlinedButton(onClick = onRetry) {
                        Text(stringResource(R.string.action_retry))
                    }
                }
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_close)) }
            }
        }
    }
}

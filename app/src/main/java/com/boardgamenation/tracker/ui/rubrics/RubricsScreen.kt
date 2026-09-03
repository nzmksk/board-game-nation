package com.boardgamenation.tracker.ui.rubrics

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.boardgamenation.tracker.R
import com.boardgamenation.tracker.data.db.entity.RubricCriterionEntity
import com.boardgamenation.tracker.data.db.entity.RubricEntity
import com.boardgamenation.tracker.data.repository.RubricRepository
import com.boardgamenation.tracker.ui.components.EmptyState
import com.boardgamenation.tracker.ui.components.currentLocale
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class RubricsViewModel @Inject constructor(private val repository: RubricRepository) : ViewModel() {

    val rubrics: StateFlow<List<RubricEntity>> = repository.observeRubrics()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val expanded = MutableStateFlow<Long?>(null)
    val expandedRubricId: StateFlow<Long?> = expanded

    val criteria: StateFlow<List<RubricCriterionEntity>> = expanded
        .flatMapLatest { id ->
            if (id == null) flowOf(emptyList()) else repository.observeCriteria(id)
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun toggleExpanded(id: Long) {
        expanded.value = if (expanded.value == id) null else id
    }

    fun addRubric(name: String, description: String) {
        if (name.isBlank()) return
        viewModelScope.launch {
            repository.saveRubric(
                RubricEntity(name = name.trim(), description = description.ifBlank { null })
            )
        }
    }

    fun addCriterion(rubricId: Long, name: String, weight: Double, maxScore: Double) {
        if (name.isBlank()) return
        viewModelScope.launch {
            val order = criteria.value.size
            repository.saveCriterion(
                RubricCriterionEntity(
                    rubricId = rubricId,
                    name = name.trim(),
                    weight = weight,
                    maxScore = maxScore,
                    sortOrder = order
                )
            )
        }
    }

    fun deleteCriterion(id: Long) {
        viewModelScope.launch { repository.deleteCriterion(id) }
    }

    fun archive(rubric: RubricEntity) {
        viewModelScope.launch { repository.setArchived(rubric.id, true) }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RubricsScreen(onBack: () -> Unit, viewModel: RubricsViewModel = hiltViewModel()) {
    val rubrics by viewModel.rubrics.collectAsStateWithLifecycle()
    val criteria by viewModel.criteria.collectAsStateWithLifecycle()
    val expandedId by viewModel.expandedRubricId.collectAsStateWithLifecycle()
    var addRubricOpen by remember { mutableStateOf(false) }
    var addCriterionFor by remember { mutableStateOf<Long?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.rubrics_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.action_back)
                        )
                    }
                }
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { addRubricOpen = true }) {
                Icon(Icons.Filled.Add, contentDescription = stringResource(R.string.rubrics_add))
            }
        }
    ) { padding ->
        if (rubrics.isEmpty()) {
            EmptyState(
                title = stringResource(R.string.rubrics_empty),
                modifier = Modifier.padding(padding)
            )
            return@Scaffold
        }

        LazyColumn(
            modifier = Modifier.padding(padding),
            contentPadding = PaddingValues(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(rubrics.size, key = { rubrics[it].id }) { index ->
                val rubric = rubrics[index]
                val isExpanded = expandedId == rubric.id
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainerLow
                    ),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(Modifier.padding(12.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(
                                Modifier
                                    .weight(1f)
                                    .padding(end = 8.dp)
                            ) {
                                Text(rubric.name, style = MaterialTheme.typography.titleSmall)
                                rubric.description?.let {
                                    Text(
                                        text = it,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                            TextButton(onClick = { viewModel.toggleExpanded(rubric.id) }) {
                                Text(
                                    stringResource(
                                        if (isExpanded) R.string.cd_collapse else R.string.cd_expand
                                    )
                                )
                            }
                        }

                        if (isExpanded) {
                            Spacer(Modifier.width(8.dp))
                            criteria.forEach { criterion ->
                                Row(
                                    Modifier.fillMaxWidth().padding(vertical = 4.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(Modifier.weight(1f)) {
                                        Text(
                                            text = criterion.name,
                                            style = MaterialTheme.typography.bodyMedium
                                        )
                                        Text(
                                            text = String.format(
                                                currentLocale(),
                                                "%s ×%.1f · %s %.0f",
                                                stringResource(R.string.rubrics_criterion_weight),
                                                criterion.weight,
                                                stringResource(R.string.rubrics_criterion_max),
                                                criterion.maxScore
                                            ),
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                    IconButton(onClick = { viewModel.deleteCriterion(criterion.id) }) {
                                        Icon(
                                            Icons.Filled.Delete,
                                            contentDescription = stringResource(R.string.action_delete)
                                        )
                                    }
                                }
                            }
                            Row {
                                TextButton(onClick = { addCriterionFor = rubric.id }) {
                                    Text(stringResource(R.string.rubrics_add_criterion))
                                }
                                TextButton(onClick = { viewModel.archive(rubric) }) {
                                    Text(stringResource(R.string.rubrics_archive))
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    if (addRubricOpen) {
        var name by remember { mutableStateOf("") }
        var description by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { addRubricOpen = false },
            title = { Text(stringResource(R.string.rubrics_add)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedTextField(
                        value = name,
                        onValueChange = { name = it },
                        label = { Text(stringResource(R.string.rubrics_name)) },
                        singleLine = true
                    )
                    OutlinedTextField(
                        value = description,
                        onValueChange = { description = it },
                        label = { Text(stringResource(R.string.rubrics_description)) }
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.addRubric(name, description)
                        addRubricOpen = false
                    },
                    enabled = name.isNotBlank()
                ) { Text(stringResource(R.string.action_save)) }
            },
            dismissButton = {
                TextButton(onClick = { addRubricOpen = false }) {
                    Text(stringResource(R.string.action_cancel))
                }
            }
        )
    }

    addCriterionFor?.let { rubricId ->
        var name by remember { mutableStateOf("") }
        var weight by remember { mutableStateOf("1.0") }
        var maxScore by remember { mutableStateOf("10") }
        AlertDialog(
            onDismissRequest = { addCriterionFor = null },
            title = { Text(stringResource(R.string.rubrics_add_criterion)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedTextField(
                        value = name,
                        onValueChange = { name = it },
                        label = { Text(stringResource(R.string.rubrics_criterion_name)) },
                        singleLine = true
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = weight,
                            onValueChange = { weight = it.filter { c -> c.isDigit() || c == '.' } },
                            label = { Text(stringResource(R.string.rubrics_criterion_weight)) },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                            modifier = Modifier.weight(1f)
                        )
                        OutlinedTextField(
                            value = maxScore,
                            onValueChange = { maxScore = it.filter { c -> c.isDigit() || c == '.' } },
                            label = { Text(stringResource(R.string.rubrics_criterion_max)) },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.addCriterion(
                            rubricId,
                            name,
                            weight.toDoubleOrNull() ?: 1.0,
                            maxScore.toDoubleOrNull() ?: 10.0
                        )
                        addCriterionFor = null
                    },
                    enabled = name.isNotBlank()
                ) { Text(stringResource(R.string.action_save)) }
            },
            dismissButton = {
                TextButton(onClick = { addCriterionFor = null }) {
                    Text(stringResource(R.string.action_cancel))
                }
            }
        )
    }
}

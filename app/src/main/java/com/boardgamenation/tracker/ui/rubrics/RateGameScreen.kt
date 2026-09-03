package com.boardgamenation.tracker.ui.rubrics

import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import com.boardgamenation.tracker.R
import com.boardgamenation.tracker.data.db.entity.RubricCriterionEntity
import com.boardgamenation.tracker.data.db.entity.RubricEntity
import com.boardgamenation.tracker.data.repository.AchievementRepository
import com.boardgamenation.tracker.data.repository.GameRepository
import com.boardgamenation.tracker.data.repository.RubricRepository
import com.boardgamenation.tracker.ui.components.currentLocale
import com.boardgamenation.tracker.ui.navigation.Route
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

data class RateGameState(
    val gameTitle: String = "",
    val rubrics: List<RubricEntity> = emptyList(),
    val selectedRubricId: Long = 0,
    val criteria: List<RubricCriterionEntity> = emptyList(),
    val scores: Map<Long, Double> = emptyMap(),
    val notes: String = "",
    val computed: Double = 0.0,
    val saved: Boolean = false
) {
    val canSave: Boolean get() = selectedRubricId != 0L && scores.isNotEmpty()
}

@HiltViewModel
class RateGameViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val rubricRepository: RubricRepository,
    private val achievementRepository: AchievementRepository,
    gameRepository: GameRepository
) : ViewModel() {

    private val gameId: Long = savedStateHandle.toRoute<Route.RateGame>().gameId

    private val _state = MutableStateFlow(RateGameState())
    val state: StateFlow<RateGameState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            val rubrics = rubricRepository.observeRubrics().first()
            val game = gameRepository.getGame(gameId)
            _state.value = _state.value.copy(
                gameTitle = game?.title.orEmpty(),
                rubrics = rubrics
            )
            rubrics.firstOrNull()?.let { selectRubric(it.id) }
        }
    }

    fun selectRubric(rubricId: Long) {
        viewModelScope.launch {
            val criteria = rubricRepository.getCriteria(rubricId)
            _state.value = _state.value.copy(
                selectedRubricId = rubricId,
                criteria = criteria,
                // Scores belong to a rubric's criteria, so switching rubric starts over
                // rather than carrying meaningless ids across.
                scores = emptyMap(),
                computed = 0.0
            )
        }
    }

    fun setScore(criterionId: Long, score: Double) {
        val scores = _state.value.scores + (criterionId to score)
        _state.value = _state.value.copy(
            scores = scores,
            computed = rubricRepository.computeScore(_state.value.criteria, scores)
        )
    }

    fun setNotes(value: String) {
        _state.value = _state.value.copy(notes = value)
    }

    fun save() {
        val current = _state.value
        if (!current.canSave) return
        viewModelScope.launch {
            rubricRepository.saveRating(
                gameId = gameId,
                rubricId = current.selectedRubricId,
                scores = current.scores,
                notes = current.notes
            )
            // Rating a game can complete a "rate N games" achievement.
            achievementRepository.evaluateAfterSession(null)
            _state.value = _state.value.copy(saved = true)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun RateGameScreen(onBack: () -> Unit, viewModel: RateGameViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    LaunchedEffect(state.saved) { if (state.saved) onBack() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.rate_title, state.gameTitle)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.action_back)
                        )
                    }
                },
                actions = {
                    Button(onClick = viewModel::save, enabled = state.canSave) {
                        Text(stringResource(R.string.rate_save))
                    }
                }
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier.padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                Text(
                    text = stringResource(R.string.rate_choose_rubric),
                    style = MaterialTheme.typography.labelLarge
                )
            }
            item {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    state.rubrics.forEach { rubric ->
                        FilterChip(
                            selected = state.selectedRubricId == rubric.id,
                            onClick = { viewModel.selectRubric(rubric.id) },
                            label = { Text(rubric.name) }
                        )
                    }
                }
            }

            if (state.rubrics.isEmpty()) {
                item {
                    Text(
                        text = stringResource(R.string.rate_needs_rubric),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            item {
                // The normalised score updates as the sliders move, so the weighting is
                // visible rather than a black box revealed on save.
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer
                    ),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = stringResource(
                            R.string.rate_computed,
                            String.format(currentLocale(), "%.1f", state.computed)
                        ),
                        style = MaterialTheme.typography.titleLarge,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.padding(16.dp)
                    )
                }
            }

            items(state.criteria.size, key = { state.criteria[it].id }) { index ->
                val criterion = state.criteria[index]
                val score = state.scores[criterion.id] ?: 0.0
                Column {
                    Row(Modifier.fillMaxWidth()) {
                        Text(
                            text = criterion.name,
                            style = MaterialTheme.typography.bodyLarge,
                            modifier = Modifier.weight(1f)
                        )
                        Text(
                            text = String.format(
                                currentLocale(),
                                "%.1f / %.0f",
                                score,
                                criterion.maxScore
                            ),
                            style = MaterialTheme.typography.labelLarge
                        )
                    }
                    criterion.description?.let {
                        Text(
                            text = it,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Slider(
                        value = score.toFloat(),
                        onValueChange = { viewModel.setScore(criterion.id, it.toDouble()) },
                        valueRange = 0f..criterion.maxScore.toFloat(),
                        steps = (criterion.maxScore.toInt() * 2) - 1
                    )
                    Text(
                        text = String.format(
                            currentLocale(),
                            "%s ×%.1f",
                            stringResource(R.string.rubrics_criterion_weight),
                            criterion.weight
                        ),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            item {
                OutlinedTextField(
                    value = state.notes,
                    onValueChange = viewModel::setNotes,
                    label = { Text(stringResource(R.string.rate_notes)) },
                    minLines = 3,
                    modifier = Modifier.fillMaxWidth()
                )
            }

            item { Spacer(Modifier.height(32.dp)) }
        }
    }
}

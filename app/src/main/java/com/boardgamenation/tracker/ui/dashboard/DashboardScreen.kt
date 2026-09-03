package com.boardgamenation.tracker.ui.dashboard

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
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.EmojiEvents
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.boardgamenation.tracker.R
import com.boardgamenation.tracker.core.time.DateUtils
import com.boardgamenation.tracker.data.db.entity.SessionEntity
import com.boardgamenation.tracker.data.db.projection.AchievementWithUnlock
import com.boardgamenation.tracker.data.db.projection.SessionListItem
import com.boardgamenation.tracker.data.prefs.SettingsRepository
import com.boardgamenation.tracker.data.repository.AchievementRepository
import com.boardgamenation.tracker.data.repository.GameRepository
import com.boardgamenation.tracker.data.repository.SessionRepository
import com.boardgamenation.tracker.data.repository.StatsRepository
import com.boardgamenation.tracker.domain.stats.StreakResult
import com.boardgamenation.tracker.ui.components.EmptyState
import com.boardgamenation.tracker.ui.components.SectionHeader
import com.boardgamenation.tracker.ui.components.StatTile
import com.boardgamenation.tracker.ui.sessions.SessionRow
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class DashboardState(
    val recent: List<SessionListItem> = emptyList(),
    val streak: StreakResult = StreakResult(0, 0),
    val recentAchievements: List<AchievementWithUnlock> = emptyList(),
    val gamesOwned: Int = 0,
    val totalPlays: Int = 0,
    val draft: SessionEntity? = null,
    val draftGameTitle: String = "",
    val overdueLoans: Int = 0,
    val lendingThresholdDays: Int = 30
)

@HiltViewModel
class DashboardViewModel @Inject constructor(
    private val sessionRepository: SessionRepository,
    private val gameRepository: GameRepository,
    statsRepository: StatsRepository,
    achievementRepository: AchievementRepository,
    settingsRepository: SettingsRepository
) : ViewModel() {

    private val loans = MutableStateFlow(0 to 30)

    val state: StateFlow<DashboardState> = combine(
        sessionRepository.observeRecent(5),
        statsRepository.weeklyStreak(),
        achievementRepository.observeRecentlyUnlocked(3),
        combine(
            statsRepository.ownedBaseGames(),
            statsRepository.totalPlays()
        ) { owned, plays -> owned to plays },
        combine(
            sessionRepository.observeLatestDraft(),
            loans
        ) { draft, loanInfo -> draft to loanInfo }
    ) { recent, streak, achievements, (owned, plays), (draft, loanInfo) ->
        DashboardState(
            recent = recent,
            streak = streak,
            recentAchievements = achievements,
            gamesOwned = owned,
            totalPlays = plays,
            draft = draft,
            draftGameTitle = draft?.let { gameRepository.getGame(it.gameId)?.title }.orEmpty(),
            overdueLoans = loanInfo.first,
            lendingThresholdDays = loanInfo.second
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DashboardState())

    init {
        viewModelScope.launch {
            settingsRepository.settings.collect { settings ->
                loans.value = gameRepository
                    .overdueLoans(settings.lendingReminderDays).size to settings.lendingReminderDays
            }
        }
    }

    fun discardDraft() {
        val id = state.value.draft?.id ?: return
        viewModelScope.launch { sessionRepository.discardDraft(id) }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DashboardScreen(
    onStartTimer: () -> Unit,
    onOpenSession: (Long) -> Unit,
    onOpenSessions: () -> Unit,
    onOpenAchievements: () -> Unit,
    onResumeDraft: (Long) -> Unit,
    viewModel: DashboardViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    Scaffold(
        topBar = { TopAppBar(title = { Text(stringResource(R.string.dashboard_title)) }) }
    ) { padding ->
        LazyColumn(
            modifier = Modifier.padding(padding),
            contentPadding = PaddingValues(bottom = 16.dp)
        ) {
            // A draft left behind by a killed process is the first thing offered, because
            // an evening's data is the most valuable thing the app could lose.
            state.draft?.let { draft ->
                item {
                    Card(
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.tertiaryContainer
                        ),
                        modifier = Modifier.fillMaxWidth().padding(16.dp)
                    ) {
                        Column(Modifier.padding(16.dp)) {
                            Text(
                                text = stringResource(R.string.dashboard_draft_title),
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.onTertiaryContainer
                            )
                            Text(
                                text = stringResource(
                                    R.string.dashboard_draft_body,
                                    state.draftGameTitle
                                ),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onTertiaryContainer
                            )
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Button(onClick = { onResumeDraft(draft.id) }) {
                                    Text(stringResource(R.string.dashboard_draft_save))
                                }
                                TextButton(onClick = viewModel::discardDraft) {
                                    Text(stringResource(R.string.dashboard_draft_discard))
                                }
                            }
                        }
                    }
                }
            }

            item {
                Row(
                    Modifier.fillMaxWidth().padding(16.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    StatTile(
                        label = stringResource(R.string.stats_total_games),
                        value = state.gamesOwned.toString(),
                        modifier = Modifier.weight(1f)
                    )
                    StatTile(
                        label = stringResource(R.string.stats_total_plays),
                        value = state.totalPlays.toString(),
                        modifier = Modifier.weight(1f)
                    )
                    StatTile(
                        label = stringResource(R.string.dashboard_streak_title),
                        value = state.streak.current.toString(),
                        supporting = pluralStringResource(
                            R.plurals.dashboard_streak_longest,
                            state.streak.longest,
                            state.streak.longest
                        ),
                        modifier = Modifier.weight(1.2f)
                    )
                }
            }

            item {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedButton(onClick = onStartTimer, modifier = Modifier.weight(1f)) {
                        Icon(Icons.Filled.Timer, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.dashboard_start_timer))
                    }
                }
            }

            if (state.overdueLoans > 0) {
                item {
                    Card(
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.secondaryContainer
                        ),
                        modifier = Modifier.fillMaxWidth().padding(16.dp)
                    ) {
                        Text(
                            text = pluralStringResource(
                                R.plurals.dashboard_overdue_loans,
                                state.overdueLoans,
                                state.overdueLoans,
                                state.lendingThresholdDays
                            ),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSecondaryContainer,
                            modifier = Modifier.padding(16.dp)
                        )
                    }
                }
            }

            if (state.recentAchievements.isNotEmpty()) {
                item {
                    SectionHeader(stringResource(R.string.dashboard_recent_achievement)) {
                        TextButton(onClick = onOpenAchievements) {
                            Text(stringResource(R.string.dashboard_view_all))
                        }
                    }
                }
                items(state.recentAchievements.size) { index ->
                    val achievement = state.recentAchievements[index]
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            Icons.Filled.EmojiEvents,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary
                        )
                        Column(Modifier.padding(start = 12.dp)) {
                            Text(achievement.name, style = MaterialTheme.typography.bodyLarge)
                            Text(
                                text = achievement.unlockedAt
                                    ?.let { DateUtils.epochMillisToIso(it) }
                                    .orEmpty(),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }

            item {
                SectionHeader(stringResource(R.string.dashboard_recent_sessions)) {
                    TextButton(onClick = onOpenSessions) {
                        Text(stringResource(R.string.dashboard_view_all))
                    }
                }
            }

            if (state.recent.isEmpty()) {
                item {
                    EmptyState(
                        title = stringResource(R.string.dashboard_no_sessions),
                        icon = Icons.Filled.Add
                    )
                }
            } else {
                items(state.recent.size, key = { state.recent[it].id }) { index ->
                    SessionRow(
                        session = state.recent[index],
                        showGameTitle = true,
                        onClick = { onOpenSession(state.recent[index].id) }
                    )
                }
            }
        }
    }
}

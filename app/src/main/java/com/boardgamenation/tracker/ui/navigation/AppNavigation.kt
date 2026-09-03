package com.boardgamenation.tracker.ui.navigation

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.boardgamenation.tracker.R
import com.boardgamenation.tracker.ui.achievements.AchievementsScreen
import com.boardgamenation.tracker.ui.bgg.BggImportScreen
import com.boardgamenation.tracker.ui.bgg.BggSearchScreen
import com.boardgamenation.tracker.ui.collection.CollectionScreen
import com.boardgamenation.tracker.ui.dashboard.DashboardScreen
import com.boardgamenation.tracker.ui.gamedetail.GameDetailScreen
import com.boardgamenation.tracker.ui.gameedit.GameEditScreen
import com.boardgamenation.tracker.ui.more.MoreScreen
import com.boardgamenation.tracker.ui.onboarding.OnboardingScreen
import com.boardgamenation.tracker.ui.players.PlayerDetailScreen
import com.boardgamenation.tracker.ui.players.PlayersScreen
import com.boardgamenation.tracker.ui.rubrics.RateGameScreen
import com.boardgamenation.tracker.ui.rubrics.RubricsScreen
import com.boardgamenation.tracker.ui.sessionedit.SessionEditScreen
import com.boardgamenation.tracker.ui.sessions.SessionListScreen
import com.boardgamenation.tracker.ui.settings.SettingsScreen
import com.boardgamenation.tracker.ui.stats.StatsScreen
import com.boardgamenation.tracker.ui.timer.TimerRunningScreen
import com.boardgamenation.tracker.ui.timer.TimerSetupScreen
import kotlinx.coroutines.launch

/**
 * The whole navigation graph.
 *
 * Routes are type-safe objects rather than string templates, so a destination that needs
 * a game id cannot be reached without one.
 */
@Composable
fun AppNavigation(
    bggEnabled: Boolean,
    onboardingComplete: Boolean,
    announceAchievements: Boolean,
    navController: NavHostController = rememberNavController()
) {
    val backStack by navController.currentBackStackEntryAsState()
    val snackbarHost = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    // Resolved at composition: the snackbar fires from a coroutine, which has no
    // composable scope to read resources from.
    val unlockedOne = stringResource(R.string.achievements_unlocked_toast, "%s")
    val unlockedMany = stringResource(R.string.achievements_unlocked_multiple, 0)

    fun announceUnlocks(unlocked: List<String>) {
        if (unlocked.isEmpty() || !announceAchievements) return
        val text = if (unlocked.size == 1) {
            unlockedOne.replace("%s", unlocked.first())
        } else {
            unlockedMany.replace("0", unlocked.size.toString())
        }
        // A snackbar, never a modal: the play is already saved and nothing should
        // interrupt logging the next one.
        scope.launch { snackbarHost.showSnackbar(text) }
    }

    val currentDestination = backStack?.destination
    val showBottomBar = TopLevelDestination.entries.any { destination ->
        currentDestination?.hierarchyContains(destination) == true
    }

    Scaffold(
        bottomBar = {
            if (showBottomBar) {
                NavigationBar {
                    TopLevelDestination.entries.forEach { destination ->
                        val selected = currentDestination?.hierarchyContains(destination) == true
                        NavigationBarItem(
                            selected = selected,
                            onClick = {
                                navController.navigate(destination.route) {
                                    // One entry per tab, on top of Collection, so back always
                                    // walks tab -> Collection -> out.
                                    //
                                    // Deliberately no saveState/restoreState. In a flat graph
                                    // popUpTo(Collection) { saveState = true } files the stack
                                    // it just popped -- the tab being left -- under Collection's
                                    // own key, so the next restoreState navigate to Collection
                                    // put that tab straight back on screen.
                                    popUpTo(Route.Collection)
                                    launchSingleTop = true
                                }
                            },
                            icon = {
                                Icon(destination.icon, contentDescription = null)
                            },
                            label = { Text(stringResource(destination.labelRes)) }
                        )
                    }
                }
            }
        },
        snackbarHost = { SnackbarHost(snackbarHost) },
        containerColor = MaterialTheme.colorScheme.background
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            NavHost(
                navController = navController,
                startDestination = if (onboardingComplete) Route.Collection else Route.Onboarding
            ) {
                composable<Route.Onboarding> {
                    OnboardingScreen(
                        onDone = {
                            navController.navigate(Route.Collection) {
                                popUpTo(Route.Onboarding) { inclusive = true }
                            }
                        }
                    )
                }

                composable<Route.Dashboard> {
                    DashboardScreen(
                        onQuickLogSaved = ::announceUnlocks,
                        onOpenFullForm = { gameId ->
                            navController.navigate(Route.SessionEdit(gameId = gameId))
                        },
                        onStartTimer = { navController.navigate(Route.TimerSetup()) },
                        onOpenSession = { navController.navigate(Route.SessionEdit(sessionId = it)) },
                        onOpenSessions = { navController.navigate(Route.Sessions()) },
                        onOpenAchievements = { navController.navigate(Route.Achievements) },
                        onResumeDraft = { navController.navigate(Route.SessionEdit(sessionId = it)) }
                    )
                }

                composable<Route.Collection> {
                    CollectionScreen(
                        onOpenGame = { navController.navigate(Route.GameDetail(it)) },
                        onAddGame = { navController.navigate(Route.GameEdit()) }
                    )
                }

                composable<Route.GameDetail> {
                    GameDetailScreen(
                        onBack = navController::popBackStack,
                        onEdit = { navController.navigate(Route.GameEdit(it)) },
                        onLogPlay = { navController.navigate(Route.SessionEdit(gameId = it)) },
                        onStartTimer = { navController.navigate(Route.TimerSetup(it)) },
                        onOpenSession = { navController.navigate(Route.SessionEdit(sessionId = it)) },
                        onOpenGame = { navController.navigate(Route.GameDetail(it)) },
                        onRate = { navController.navigate(Route.RateGame(it)) }
                    )
                }

                composable<Route.GameEdit> {
                    GameEditScreen(
                        onBack = navController::popBackStack,
                        onSaved = { id ->
                            navController.navigate(Route.GameDetail(id)) {
                                popUpTo(Route.Collection)
                            }
                        },
                        onSearchBgg = { navController.navigate(Route.BggSearch) },
                        bggEnabled = bggEnabled
                    )
                }

                composable<Route.BggSearch> {
                    BggSearchScreen(
                        onBack = navController::popBackStack,
                        onImported = { id ->
                            navController.navigate(Route.GameDetail(id)) {
                                popUpTo(Route.Collection)
                            }
                        }
                    )
                }

                composable<Route.BggImport> {
                    BggImportScreen(onBack = navController::popBackStack)
                }

                composable<Route.Sessions> {
                    SessionListScreen(
                        onOpenSession = { navController.navigate(Route.SessionEdit(sessionId = it)) },
                        onNewSession = { navController.navigate(Route.SessionEdit()) }
                    )
                }

                composable<Route.SessionEdit> {
                    SessionEditScreen(
                        onBack = navController::popBackStack,
                        onSaved = { _, unlocked ->
                            navController.popBackStack()
                            announceUnlocks(unlocked)
                        }
                    )
                }

                composable<Route.TimerSetup> {
                    TimerSetupScreen(
                        onStarted = {
                            navController.navigate(Route.TimerRunning) {
                                launchSingleTop = true
                            }
                        }
                    )
                }

                composable<Route.TimerRunning> {
                    TimerRunningScreen(
                        onExit = {
                            navController.navigate(Route.TimerSetup()) {
                                popUpTo(Route.TimerRunning) { inclusive = true }
                            }
                        },
                        onSaveSession = { gameId, sessionId ->
                            navController.navigate(
                                Route.SessionEdit(sessionId = sessionId, gameId = gameId)
                            ) {
                                popUpTo(Route.TimerRunning) { inclusive = true }
                            }
                        }
                    )
                }

                composable<Route.Stats> { StatsScreen() }

                composable<Route.Achievements> {
                    AchievementsScreen(onBack = navController::popBackStack)
                }

                composable<Route.Players> {
                    PlayersScreen(
                        onBack = navController::popBackStack,
                        onOpenPlayer = { navController.navigate(Route.PlayerDetail(it)) }
                    )
                }

                composable<Route.PlayerDetail> {
                    PlayerDetailScreen(
                        onBack = navController::popBackStack,
                        onOpenSession = { navController.navigate(Route.SessionEdit(sessionId = it)) }
                    )
                }

                composable<Route.Rubrics> {
                    RubricsScreen(onBack = navController::popBackStack)
                }

                composable<Route.RateGame> {
                    RateGameScreen(onBack = navController::popBackStack)
                }

                composable<Route.More> {
                    MoreScreen(
                        bggEnabled = bggEnabled,
                        onDashboard = { navController.navigate(Route.Dashboard) },
                        onAchievements = { navController.navigate(Route.Achievements) },
                        onPlayers = { navController.navigate(Route.Players) },
                        onRubrics = { navController.navigate(Route.Rubrics) },
                        onBggImport = { navController.navigate(Route.BggImport) },
                        onSettings = { navController.navigate(Route.Settings) }
                    )
                }

                composable<Route.Settings> {
                    SettingsScreen(
                        onBack = navController::popBackStack,
                        onOpenPlayers = { navController.navigate(Route.Players) },
                        onOpenRubrics = { navController.navigate(Route.Rubrics) },
                        onOpenAchievements = { navController.navigate(Route.Achievements) },
                        onOpenBggImport = { navController.navigate(Route.BggImport) }
                    )
                }
            }
        }
    }
}

/** Matches a destination against a bottom-bar tab, including nested routes. */
private fun androidx.navigation.NavDestination.hierarchyContains(destination: TopLevelDestination): Boolean = when (destination) {
    TopLevelDestination.COLLECTION -> hasRoute(Route.Collection::class)

    TopLevelDestination.SESSIONS -> hasRoute(Route.Sessions::class)

    TopLevelDestination.TIMER ->
        hasRoute(Route.TimerSetup::class) || hasRoute(Route.TimerRunning::class)

    TopLevelDestination.STATS -> hasRoute(Route.Stats::class)

    TopLevelDestination.MORE -> hasRoute(Route.More::class)
}

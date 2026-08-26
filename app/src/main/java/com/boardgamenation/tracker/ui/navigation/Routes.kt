package com.boardgamenation.tracker.ui.navigation

import androidx.annotation.StringRes
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material.icons.filled.Timer
import androidx.compose.ui.graphics.vector.ImageVector
import com.boardgamenation.tracker.R
import kotlinx.serialization.Serializable

/**
 * Every destination, as a type-safe route.
 *
 * Arguments are constructor parameters rather than string templates, so a navigation
 * call that forgets a game id is a compile error instead of a crash at the destination.
 */
sealed interface Route {

    @Serializable data object Dashboard : Route

    @Serializable data object Collection : Route

    @Serializable data class GameDetail(val gameId: Long) : Route

    /** [gameId] of 0 means a new game. */
    @Serializable data class GameEdit(val gameId: Long = 0) : Route

    @Serializable data object BggSearch : Route

    @Serializable data object BggImport : Route

    @Serializable data class Sessions(val gameId: Long = 0, val playerId: Long = 0) : Route

    /** [sessionId] of 0 means a new session; [gameId] preselects the game. */
    @Serializable data class SessionEdit(val sessionId: Long = 0, val gameId: Long = 0) : Route

    @Serializable data class TimerSetup(val gameId: Long = 0) : Route

    @Serializable data object TimerRunning : Route

    @Serializable data object Stats : Route

    @Serializable data object Achievements : Route

    @Serializable data object Players : Route

    @Serializable data class PlayerDetail(val playerId: Long) : Route

    @Serializable data object Rubrics : Route

    @Serializable data class RubricEdit(val rubricId: Long = 0) : Route

    @Serializable data class RateGame(val gameId: Long) : Route

    @Serializable data object More : Route

    @Serializable data object Settings : Route

    @Serializable data object DataManagement : Route

    @Serializable data object Onboarding : Route
}

/** The five bottom-bar destinations. */
enum class TopLevelDestination(
    val route: Route,
    @param:StringRes val labelRes: Int,
    val icon: ImageVector,
) {
    COLLECTION(Route.Collection, R.string.nav_collection, Icons.Filled.GridView),
    SESSIONS(Route.Sessions(), R.string.nav_sessions, Icons.AutoMirrored.Filled.List),
    TIMER(Route.TimerSetup(), R.string.nav_timer, Icons.Filled.Timer),
    STATS(Route.Stats, R.string.nav_stats, Icons.Filled.BarChart),
    MORE(Route.More, R.string.nav_more, Icons.Filled.MoreHoriz),
}

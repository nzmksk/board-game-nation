package com.boardgamenation.tracker.ui.navigation

import android.content.Context
import androidx.navigation.NavHostController
import androidx.navigation.compose.ComposeNavigator
import androidx.navigation.compose.composable
import androidx.navigation.createGraph
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Back-stack behaviour of the bottom bar.
 *
 * The graph here mirrors the shape [AppNavigation] builds -- five tabs on a flat graph
 * with Collection as the start destination, plus a couple of detail destinations that sit
 * on top of a tab. The tap options must stay in sync with the bottom bar's onClick.
 */
@RunWith(RobolectricTestRunner::class)
class BottomBarBackStackTest {

    private fun controller(): NavHostController {
        val context: Context = ApplicationProvider.getApplicationContext()
        val navController = NavHostController(context)
        navController.navigatorProvider.addNavigator(ComposeNavigator())
        navController.graph = navController.createGraph(startDestination = Route.Collection) {
            composable<Route.Collection> {}
            composable<Route.Sessions> {}
            composable<Route.TimerSetup> {}
            composable<Route.Stats> {}
            composable<Route.More> {}
            composable<Route.GameDetail> {}
            composable<Route.Settings> {}
        }
        return navController
    }

    /** Mirrors the bottom bar's onClick in [AppNavigation]. */
    private fun NavHostController.tap(destination: TopLevelDestination) {
        navigate(destination.route) {
            popUpTo(Route.Collection)
            launchSingleTop = true
        }
    }

    /** Route names without arguments, e.g. "Collection", "GameDetail". */
    private fun NavHostController.top(): String = name(currentBackStackEntry?.destination?.route)

    private fun NavHostController.stack(): List<String> =
        currentBackStack.value.drop(1).map { name(it.destination.route) }

    private fun name(route: String?): String =
        route?.substringAfterLast('.')?.substringBefore('?')?.substringBefore('/') ?: "none"

    private val TopLevelDestination.rootName: String
        get() = when (this) {
            TopLevelDestination.COLLECTION -> "Collection"
            TopLevelDestination.SESSIONS -> "Sessions"
            TopLevelDestination.TIMER -> "TimerSetup"
            TopLevelDestination.STATS -> "Stats"
            TopLevelDestination.MORE -> "More"
        }

    @Test
    fun `collection tab from another tab lands on collection`() {
        val navController = controller()

        navController.tap(TopLevelDestination.STATS)
        assertEquals("Stats", navController.top())

        navController.tap(TopLevelDestination.COLLECTION)
        assertEquals("Collection", navController.top())
        assertEquals(listOf("Collection"), navController.stack())
    }

    @Test
    fun `collection tab lands on collection with a game open underneath`() {
        val navController = controller()
        navController.navigate(Route.GameDetail(1))
        navController.tap(TopLevelDestination.STATS)

        navController.tap(TopLevelDestination.COLLECTION)

        assertEquals("Collection", navController.top())
        assertEquals(listOf("Collection"), navController.stack())
    }

    @Test
    fun `a tab sits on top of collection and backs out to it`() {
        val navController = controller()

        navController.tap(TopLevelDestination.SESSIONS)
        assertEquals(listOf("Collection", "Sessions"), navController.stack())

        navController.popBackStack()
        assertEquals("Collection", navController.top())
    }

    /**
     * The regression net: whatever the user did before, tapping a tab shows that tab and
     * leaves at most one entry above Collection.
     */
    @Test
    fun `every tap sequence lands on the tapped tab`() {
        val taps = TopLevelDestination.entries
        val extras = listOf<Pair<String, NavHostController.() -> Unit>>(
            "open game" to { navigate(Route.GameDetail(1)) },
            "back" to { popBackStack(); Unit },
        )
        val actions = taps.map { it.name to { c: NavHostController -> c.tap(it) } } +
            extras.map { (label, action) -> label to { c: NavHostController -> c.action() } }

        fun walk(prefix: List<Pair<String, (NavHostController) -> Unit>>, depth: Int) {
            if (depth == 0) return
            for (action in actions) {
                val sequence = prefix + action
                val navController = controller()
                // An empty back stack means the user backed out of the app; anything
                // after that is not a state the running app can be in.
                val exited = sequence.any { (_, run) ->
                    run(navController)
                    navController.stack().isEmpty()
                }
                if (exited) continue
                val tapped = taps.firstOrNull { it.name == action.first }
                if (tapped != null) {
                    val trace = sequence.joinToString(" -> ") { it.first }
                    val stack = navController.stack()
                    assertEquals("$trace -> $stack", tapped.rootName, navController.top())
                    // Collection alone, or Collection plus the tab on top of it.
                    assertTrue("$trace -> $stack", stack.size <= 2)
                    assertEquals("$trace -> $stack", "Collection", stack.first())
                }
                walk(sequence, depth - 1)
            }
        }
        walk(emptyList(), 4)
    }
}

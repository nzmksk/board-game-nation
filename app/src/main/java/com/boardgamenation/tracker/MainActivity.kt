package com.boardgamenation.tracker

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import com.boardgamenation.tracker.data.AppInitializer
import com.boardgamenation.tracker.data.prefs.AppSettings
import com.boardgamenation.tracker.data.prefs.SettingsRepository
import com.boardgamenation.tracker.data.repository.BggRepository
import com.boardgamenation.tracker.ui.navigation.AppNavigation
import com.boardgamenation.tracker.ui.theme.BoardGameNationTheme
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * The single activity. Everything else is Compose.
 */
@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject lateinit var settingsRepository: SettingsRepository

    @Inject lateinit var bggRepository: BggRepository

    @Inject lateinit var appInitializer: AppInitializer

    /**
     * Held so the splash can stay up until the stored theme is known. Painting the
     * default palette first and correcting it a frame later is a visible flash.
     */
    private var firstSettings: AppSettings? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        val splash = installSplashScreen()
        splash.setKeepOnScreenCondition { firstSettings == null }
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        // Seeding, achievement reconciliation, and recovery of any timer left behind by a
        // previous process. All idempotent, so running it on every launch is safe.
        appInitializer.initialise()

        lifecycleScope.launch { firstSettings = settingsRepository.settings.first() }

        setContent {
            val settings by settingsRepository.settings
                .collectAsStateWithLifecycle(initialValue = firstSettings ?: AppSettings())

            BoardGameNationTheme(
                themeMode = settings.themeMode,
                dynamicColor = settings.dynamicColor
            ) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    AppNavigation(
                        bggEnabled = bggRepository.isConfigured,
                        onboardingComplete = settings.onboardingComplete,
                        announceAchievements = settings.achievementNotifications
                    )
                }
            }
        }
    }
}

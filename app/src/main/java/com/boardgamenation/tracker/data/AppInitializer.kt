package com.boardgamenation.tracker.data

import com.boardgamenation.tracker.data.backup.BackupScheduler
import com.boardgamenation.tracker.data.prefs.SettingsRepository
import com.boardgamenation.tracker.data.repository.AchievementRepository
import com.boardgamenation.tracker.data.repository.RubricRepository
import com.boardgamenation.tracker.data.repository.TimerRepository
import com.boardgamenation.tracker.di.ApplicationScope
import com.boardgamenation.tracker.timer.TimerController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Work that has to happen once per launch, before the user can do any damage.
 *
 * Everything here is idempotent, because it runs on every cold start rather than only
 * on a genuine first run: seeding checks for existing rows, achievement reconciliation
 * only inserts codes it has not seen, and timer recovery is a no-op when there is no
 * abandoned clock.
 */
@Singleton
class AppInitializer @Inject constructor(
    private val achievementRepository: AchievementRepository,
    private val rubricRepository: RubricRepository,
    private val timerRepository: TimerRepository,
    private val timerController: TimerController,
    private val settingsRepository: SettingsRepository,
    private val backupScheduler: BackupScheduler,
    @param:ApplicationScope private val scope: CoroutineScope,
) {

    fun initialise() {
        scope.launch {
            // Definitions first: the achievements screen and the evaluator both need
            // them, and reconciling on every launch is how a new app version's
            // achievements appear without a migration.
            achievementRepository.seedFromAsset()
            rubricRepository.seedDefaultsIfEmpty()
            timerRepository.ensureDefaultPreset()

            // A clock left running by a process that died comes back paused.
            timerController.restoreIfPresent()

            // WorkManager only registers the periodic job if the user asked for it; the
            // call is cheap and keeps the schedule alive across reinstalls of the app.
            val settings = settingsRepository.settings.first()
            if (settings.scheduledBackupEnabled && settings.backupDirectoryUri.isNotBlank()) {
                backupScheduler.enable()
            }
        }
    }
}

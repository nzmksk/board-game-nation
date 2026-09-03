package com.boardgamenation.tracker.data.prefs

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.boardgamenation.tracker.domain.model.CollectionLayout
import com.boardgamenation.tracker.domain.model.ThemeMode
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

/** Every user preference, as one observable object. */
data class AppSettings(
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val dynamicColor: Boolean = true,
    val defaultCurrency: String = "MYR",
    val bggUsername: String = "",
    val collectionLayout: CollectionLayout = CollectionLayout.LIST,
    val defaultTimerPresetId: Long = 0,
    val keepScreenOnDuringTimer: Boolean = true,
    val achievementNotifications: Boolean = true,
    val backupDirectoryUri: String = "",
    val scheduledBackupEnabled: Boolean = false,
    val backupsToKeep: Int = 8,
    val lastBackupAt: Long = 0,
    val lendingReminderDays: Int = 30,
    val onboardingComplete: Boolean = false
)

@Singleton
class SettingsRepository @Inject constructor(@param:ApplicationContext private val context: Context) {

    val settings: Flow<AppSettings> = context.dataStore.data.map { prefs ->
        AppSettings(
            themeMode = ThemeMode.fromStorage(prefs[Keys.THEME]),
            dynamicColor = prefs[Keys.DYNAMIC_COLOR] ?: true,
            defaultCurrency = prefs[Keys.CURRENCY] ?: "MYR",
            bggUsername = prefs[Keys.BGG_USERNAME].orEmpty(),
            collectionLayout = if (prefs[Keys.GRID_LAYOUT] == true) {
                CollectionLayout.GRID
            } else {
                CollectionLayout.LIST
            },
            defaultTimerPresetId = prefs[Keys.DEFAULT_PRESET]?.toLong() ?: 0L,
            keepScreenOnDuringTimer = prefs[Keys.KEEP_SCREEN_ON] ?: true,
            achievementNotifications = prefs[Keys.ACHIEVEMENT_NOTIFICATIONS] ?: true,
            backupDirectoryUri = prefs[Keys.BACKUP_DIR].orEmpty(),
            scheduledBackupEnabled = prefs[Keys.BACKUP_SCHEDULED] ?: false,
            backupsToKeep = prefs[Keys.BACKUPS_TO_KEEP] ?: 8,
            lastBackupAt = prefs[Keys.LAST_BACKUP]?.toLongOrNull() ?: 0L,
            lendingReminderDays = prefs[Keys.LENDING_DAYS] ?: 30,
            onboardingComplete = prefs[Keys.ONBOARDING_DONE] ?: false
        )
    }

    suspend fun setTheme(mode: ThemeMode) = put { it[Keys.THEME] = mode.name }

    suspend fun setDynamicColor(enabled: Boolean) = put { it[Keys.DYNAMIC_COLOR] = enabled }

    suspend fun setCurrency(code: String) = put { it[Keys.CURRENCY] = code.trim().uppercase() }

    suspend fun setBggUsername(name: String) = put { it[Keys.BGG_USERNAME] = name.trim() }

    suspend fun setCollectionLayout(layout: CollectionLayout) = put { it[Keys.GRID_LAYOUT] = layout == CollectionLayout.GRID }

    suspend fun setDefaultTimerPreset(id: Long) = put { it[Keys.DEFAULT_PRESET] = id.toInt() }

    suspend fun setKeepScreenOn(enabled: Boolean) = put { it[Keys.KEEP_SCREEN_ON] = enabled }

    suspend fun setAchievementNotifications(enabled: Boolean) = put { it[Keys.ACHIEVEMENT_NOTIFICATIONS] = enabled }

    suspend fun setBackupDirectory(uri: String) = put { it[Keys.BACKUP_DIR] = uri }

    suspend fun setScheduledBackup(enabled: Boolean) = put { it[Keys.BACKUP_SCHEDULED] = enabled }

    suspend fun setBackupsToKeep(count: Int) = put { it[Keys.BACKUPS_TO_KEEP] = count.coerceIn(1, 52) }

    suspend fun setLastBackupAt(millis: Long) = put { it[Keys.LAST_BACKUP] = millis.toString() }

    suspend fun setLendingReminderDays(days: Int) = put { it[Keys.LENDING_DAYS] = days.coerceIn(1, 365) }

    suspend fun setOnboardingComplete(done: Boolean) = put { it[Keys.ONBOARDING_DONE] = done }

    private suspend fun put(block: (androidx.datastore.preferences.core.MutablePreferences) -> Unit) {
        context.dataStore.edit(block)
    }

    private object Keys {
        val THEME = stringPreferencesKey("theme_mode")
        val DYNAMIC_COLOR = booleanPreferencesKey("dynamic_color")
        val CURRENCY = stringPreferencesKey("default_currency")
        val BGG_USERNAME = stringPreferencesKey("bgg_username")
        val GRID_LAYOUT = booleanPreferencesKey("collection_grid")
        val DEFAULT_PRESET = intPreferencesKey("default_timer_preset")
        val KEEP_SCREEN_ON = booleanPreferencesKey("keep_screen_on")
        val ACHIEVEMENT_NOTIFICATIONS = booleanPreferencesKey("achievement_notifications")
        val BACKUP_DIR = stringPreferencesKey("backup_directory")
        val BACKUP_SCHEDULED = booleanPreferencesKey("backup_scheduled")
        val BACKUPS_TO_KEEP = intPreferencesKey("backups_to_keep")

        /** Stored as text: epoch millis overflow the Int-typed preference key. */
        val LAST_BACKUP = stringPreferencesKey("last_backup_at")
        val LENDING_DAYS = intPreferencesKey("lending_reminder_days")
        val ONBOARDING_DONE = booleanPreferencesKey("onboarding_complete")
    }
}

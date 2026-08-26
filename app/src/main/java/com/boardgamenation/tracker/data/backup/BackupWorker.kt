package com.boardgamenation.tracker.data.backup

import android.content.Context
import androidx.core.net.toUri
import androidx.hilt.work.HiltWorker
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.boardgamenation.tracker.core.time.AppClock
import com.boardgamenation.tracker.data.prefs.SettingsRepository
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.flow.first
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The weekly backup.
 *
 * This is the single feature standing between the user and total data loss when the
 * phone dies, so it errs toward running: no network constraint, no charging requirement,
 * and a retry rather than a failure if the destination is momentarily unavailable.
 */
@HiltWorker
class BackupWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val dbBackup: DbBackup,
    private val settingsRepository: SettingsRepository,
    private val clock: AppClock,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val settings = settingsRepository.settings.first()
        if (!settings.scheduledBackupEnabled) return Result.success()

        val directory = settings.backupDirectoryUri.takeIf { it.isNotBlank() }
            // Nothing to write to. Not a failure worth retrying: the user has to choose
            // a folder before this can do anything.
            ?: return Result.success()

        return try {
            val treeUri = directory.toUri()
            dbBackup.backupIntoDirectory(treeUri)
            dbBackup.pruneOldBackups(treeUri, settings.backupsToKeep)
            settingsRepository.setLastBackupAt(clock.nowMillis())
            Result.success()
        } catch (_: SecurityException) {
            // The persisted uri permission was revoked, most likely because the folder
            // was deleted or moved. Retrying will not fix it.
            Result.failure()
        } catch (_: Exception) {
            Result.retry()
        }
    }

    companion object {
        const val WORK_NAME = "weekly-backup"
    }
}

/** Owns the WorkManager registration so the settings screen has one thing to call. */
@Singleton
class BackupScheduler @Inject constructor(
    private val workManager: WorkManager,
) {

    fun enable() {
        val request = PeriodicWorkRequestBuilder<BackupWorker>(7, TimeUnit.DAYS)
            .setConstraints(
                Constraints.Builder()
                    .setRequiresBatteryNotLow(true)
                    .build(),
            )
            .setBackoffCriteria(androidx.work.BackoffPolicy.EXPONENTIAL, 1, TimeUnit.HOURS)
            .build()

        // KEEP rather than REPLACE, so toggling the setting off and on does not reset the
        // schedule and push the next backup a full week away.
        workManager.enqueueUniquePeriodicWork(
            BackupWorker.WORK_NAME,
            ExistingPeriodicWorkPolicy.KEEP,
            request,
        )
    }

    fun disable() {
        workManager.cancelUniqueWork(BackupWorker.WORK_NAME)
    }
}

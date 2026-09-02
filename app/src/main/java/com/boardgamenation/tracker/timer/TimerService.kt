package com.boardgamenation.tracker.timer

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.boardgamenation.tracker.MainActivity
import com.boardgamenation.tracker.R
import com.boardgamenation.tracker.core.time.DurationFormat
import com.boardgamenation.tracker.domain.model.ActiveClock
import com.boardgamenation.tracker.domain.model.TimerRunState
import com.boardgamenation.tracker.domain.timer.TimerProjection
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Keeps the clock alive and visible.
 *
 * The service does not own the timer — [TimerController] does. Its job is narrower and
 * entirely about the platform: hold a foreground notification so the process is not
 * killed mid-game, hold a partial wake lock so the CPU keeps running with the screen
 * off, and surrender both the moment the clock stops.
 */
@AndroidEntryPoint
class TimerService : Service() {

    @Inject lateinit var controller: TimerController

    private val scope = CoroutineScope(SupervisorJob() + kotlinx.coroutines.Dispatchers.Main)
    private var wakeLock: PowerManager.WakeLock? = null
    private var observer: Job? = null
    private var lastNotifiedSecond = -1L

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopEverything()
                return START_NOT_STICKY
            }
            ACTION_PAUSE -> scope.launch { controller.pause() }
            ACTION_RESUME -> scope.launch { controller.resume() }
            ACTION_PASS -> scope.launch { controller.passTurn() }
            else -> Unit
        }

        startForeground(NOTIFICATION_ID, buildNotification(controller.projection.value))
        acquireWakeLock()
        observeProjection()
        // Not sticky: a clock resurrected by the system with no state would be worse than
        // no clock at all. Recovery goes through the draft-session prompt instead.
        return START_NOT_STICKY
    }

    private fun observeProjection() {
        if (observer?.isActive == true) return
        observer = scope.launch {
            controller.projection.collectLatest { projection ->
                if (projection == null || projection.state.runState == TimerRunState.STOPPED) {
                    stopEverything()
                    return@collectLatest
                }
                // The clock ticks four times a second; the notification does not need to.
                val second = projection.displayMs / 1000
                if (second != lastNotifiedSecond) {
                    lastNotifiedSecond = second
                    NotificationManagerCompat.from(this@TimerService)
                        .notifyIfPermitted(NOTIFICATION_ID, buildNotification(projection))
                }
                if (projection.state.isRunning) acquireWakeLock() else releaseWakeLock()
            }
        }
    }

    private fun NotificationManagerCompat.notifyIfPermitted(
        id: Int,
        notification: android.app.Notification,
    ) {
        // POST_NOTIFICATIONS may be denied on API 33+. The foreground notification itself
        // is still shown by the system; the update is simply skipped.
        runCatching { notify(id, notification) }
    }

    private fun buildNotification(projection: TimerProjection?): android.app.Notification {
        val contentIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        // A count-up clock has nobody up: the whole notification is that a game is
        // running and how long it has been running for.
        val countUp = projection?.state?.isCountUp == true
        val player = projection?.activePlayer?.name
            ?: getString(R.string.timer_notification_idle)
        val title = if (countUp) {
            getString(R.string.timer_notification_game)
        } else {
            getString(R.string.timer_notification_title, player)
        }
        val clockLabel = when {
            countUp -> getString(R.string.timer_clock_elapsed)
            projection?.activeClock == ActiveClock.BANK -> getString(R.string.timer_clock_bank)
            projection?.activeClock == ActiveClock.OVERTIME ->
                getString(R.string.timer_clock_overtime)
            else -> getString(R.string.timer_clock_turn)
        }
        val time = projection?.let { DurationFormat.longClock(it.displayMs) }.orEmpty()
        val running = projection?.state?.isRunning == true

        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_timer_notification)
            .setContentTitle(title)
            .setContentText(getString(R.string.timer_notification_body, clockLabel, time))
            .setContentIntent(contentIntent)
            .setOngoing(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_STOPWATCH)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)

        if (running) {
            // Nothing to pass on a table clock.
            if (!countUp) {
                builder.addAction(
                    0,
                    getString(R.string.timer_action_pass),
                    servicePendingIntent(ACTION_PASS, 1),
                )
            }
            builder.addAction(
                0,
                getString(R.string.timer_action_pause),
                servicePendingIntent(ACTION_PAUSE, 2),
            )
        } else {
            builder.addAction(
                0,
                getString(R.string.timer_action_resume),
                servicePendingIntent(ACTION_RESUME, 3),
            )
        }

        return builder.build()
    }

    private fun servicePendingIntent(action: String, requestCode: Int): PendingIntent =
        PendingIntent.getService(
            this,
            requestCode,
            Intent(this, TimerService::class.java).setAction(action),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

    /**
     * A partial wake lock, held only while the clock is actually running. It is what
     * keeps time accurate with the screen off; holding it any longer than that would be
     * a battery bug.
     */
    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        val power = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKE_LOCK_TAG).apply {
            setReferenceCounted(false)
            acquire(WAKE_LOCK_TIMEOUT_MS)
        }
    }

    private fun releaseWakeLock() {
        wakeLock?.takeIf { it.isHeld }?.release()
        wakeLock = null
    }

    private fun stopEverything() {
        observer?.cancel()
        observer = null
        releaseWakeLock()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        releaseWakeLock()
        scope.cancel()
        super.onDestroy()
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.timer_channel_name),
            // Low importance: the notification is a status readout, not an interruption.
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = getString(R.string.timer_channel_description)
            setShowBadge(false)
            enableVibration(false)
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    companion object {
        const val ACTION_START = "com.boardgamenation.tracker.timer.START"
        const val ACTION_STOP = "com.boardgamenation.tracker.timer.STOP"
        const val ACTION_PAUSE = "com.boardgamenation.tracker.timer.PAUSE"
        const val ACTION_RESUME = "com.boardgamenation.tracker.timer.RESUME"
        const val ACTION_PASS = "com.boardgamenation.tracker.timer.PASS"

        private const val CHANNEL_ID = "timer"
        private const val NOTIFICATION_ID = 1001
        private const val WAKE_LOCK_TAG = "BoardGameNation:timer"

        /** A safety net; the lock is released explicitly on pause and stop. */
        private const val WAKE_LOCK_TIMEOUT_MS = 6L * 60 * 60 * 1000
    }
}

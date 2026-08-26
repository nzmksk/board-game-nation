package com.boardgamenation.tracker.core.time

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Wall-clock time, behind an interface so tests can pin "now" instead of racing it.
 *
 * This is deliberately *not* the source used for measuring elapsed time. Wall clock can
 * jump backwards on an NTP correction or a manual change; see [ElapsedTimeSource].
 */
interface AppClock {
    fun nowMillis(): Long
    fun today(): LocalDate
    fun zone(): ZoneId
}

@Singleton
class SystemAppClock @Inject constructor() : AppClock {
    override fun nowMillis(): Long = System.currentTimeMillis()
    override fun today(): LocalDate = LocalDate.now()
    override fun zone(): ZoneId = ZoneId.systemDefault()
}

/**
 * Monotonic time since boot, used for anything that measures a duration.
 *
 * The timer never accumulates ticks and never subtracts wall-clock stamps: it takes
 * deltas from this source, so a dropped frame, a doze, or the user changing the system
 * time cannot corrupt how much time a player has actually spent.
 */
interface ElapsedTimeSource {
    fun elapsedMillis(): Long
}

/** A hand-cranked source for tests: nothing advances unless the test says so. */
class FakeElapsedTimeSource(var current: Long = 0L) : ElapsedTimeSource {
    override fun elapsedMillis(): Long = current
    fun advance(millis: Long) {
        current += millis
    }
}

class FixedClock(
    private val millis: Long,
    private val zone: ZoneId = ZoneId.of("UTC"),
) : AppClock {
    override fun nowMillis(): Long = millis
    override fun today(): LocalDate = Instant.ofEpochMilli(millis).atZone(zone).toLocalDate()
    override fun zone(): ZoneId = zone
}

package com.boardgamenation.tracker.core.time

import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.time.temporal.ChronoUnit
import java.util.Locale

/**
 * Dates without a time component are stored as ISO-8601 text, so they sort correctly as
 * strings and survive a CSV round trip unchanged regardless of the device locale.
 */
object DateUtils {

    private val ISO: DateTimeFormatter = DateTimeFormatter.ISO_LOCAL_DATE
    private val MONTH_KEY: DateTimeFormatter =
        DateTimeFormatter.ofPattern("yyyy-MM", Locale.ROOT)

    fun toIso(date: LocalDate): String = date.format(ISO)

    fun parseIsoOrNull(value: String?): LocalDate? = value?.takeIf { it.isNotBlank() }?.let {
        try {
            LocalDate.parse(it.trim(), ISO)
        } catch (_: DateTimeParseException) {
            null
        }
    }

    fun epochMillisToIso(millis: Long, zone: ZoneId = ZoneId.systemDefault()): String =
        toIso(Instant.ofEpochMilli(millis).atZone(zone).toLocalDate())

    fun monthKey(date: LocalDate): String = date.format(MONTH_KEY)

    /**
     * The same `%Y-%W` key SQLite produces, so streak maths in Kotlin lines up exactly
     * with the week grouping done in SQL. `%W` counts Monday-started weeks from 00.
     */
    fun weekKey(date: LocalDate): String {
        val jan1 = LocalDate.of(date.year, 1, 1)
        val daysSinceJan1 = ChronoUnit.DAYS.between(jan1, date).toInt()
        val jan1DowMondayZero = (jan1.dayOfWeek.value + 6) % 7
        val week = (daysSinceJan1 + jan1DowMondayZero) / 7
        return String.format(Locale.ROOT, "%04d-%02d", date.year, week)
    }

    fun daysBetween(from: LocalDate, to: LocalDate): Long = ChronoUnit.DAYS.between(from, to)

    /** Monday-based, matching [weekKey]. */
    fun startOfWeek(date: LocalDate): LocalDate = date.minusDays(((date.dayOfWeek.value + 6) % 7).toLong())

    fun dayOfWeekLabel(sqliteDayIndex: Int): DayOfWeek = when (sqliteDayIndex) {
        0 -> DayOfWeek.SUNDAY
        else -> DayOfWeek.of(sqliteDayIndex)
    }
}

/**
 * Formats a duration for display. Timers show mm:ss because a board game turn is
 * measured in seconds; session lengths show hours because plays are measured in hours.
 */
object DurationFormat {

    /** `m:ss`, or `-m:ss` once a bank has gone into overtime. */
    fun clock(millis: Long): String {
        val negative = millis < 0
        val totalSeconds = (kotlin.math.abs(millis) + 999) / 1000
        val minutes = totalSeconds / 60
        val seconds = totalSeconds % 60
        val sign = if (negative) "-" else ""
        return String.format(Locale.ROOT, "%s%d:%02d", sign, minutes, seconds)
    }

    /** `h:mm:ss` for long banks, falling back to `m:ss` under an hour. */
    fun longClock(millis: Long): String {
        val negative = millis < 0
        val totalSeconds = (kotlin.math.abs(millis) + 999) / 1000
        val hours = totalSeconds / 3600
        val minutes = (totalSeconds % 3600) / 60
        val seconds = totalSeconds % 60
        val sign = if (negative) "-" else ""
        return if (hours > 0) {
            String.format(Locale.ROOT, "%s%d:%02d:%02d", sign, hours, minutes, seconds)
        } else {
            String.format(Locale.ROOT, "%s%d:%02d", sign, minutes, seconds)
        }
    }

    /** `2h 15m`, or `45m` when under an hour. */
    fun minutes(totalMinutes: Int): String {
        if (totalMinutes < 60) return "${totalMinutes}m"
        val hours = totalMinutes / 60
        val mins = totalMinutes % 60
        return if (mins == 0) "${hours}h" else "${hours}h ${mins}m"
    }

    fun hoursOneDecimal(totalMinutes: Int): String = String.format(Locale.ROOT, "%.1f", totalMinutes / 60.0)
}

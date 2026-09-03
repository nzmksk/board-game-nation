package com.boardgamenation.tracker.domain.stats

import com.boardgamenation.tracker.core.time.DateUtils
import java.time.LocalDate
import java.time.YearMonth
import java.time.temporal.ChronoUnit

data class StreakResult(
    /** The run that is still alive as of the reference date, zero if it has lapsed. */
    val current: Int,
    val longest: Int
)

/**
 * Consecutive-period runs.
 *
 * These are computed in Kotlin from a distinct list of playing days rather than in SQL,
 * because "consecutive" needs either a window function (unavailable on SQLite 3.19,
 * which minSdk 26 ships) or a recursive CTE that would be slower and far harder to read.
 * The input is one short string per day the user played, not a table scan.
 *
 * Periods are bucketed before comparing, so two plays in the same week count once, and
 * week and month runs cross year boundaries correctly instead of resetting at week 52.
 */
object Streaks {

    fun byDay(playDates: List<LocalDate>, today: LocalDate): StreakResult {
        val (longest, tail, last) = run(playDates.distinct().sorted()) { a, b ->
            ChronoUnit.DAYS.between(a, b) == 1L
        }
        val stillAlive = last != null && ChronoUnit.DAYS.between(last, today) <= 1L
        return StreakResult(current = if (stillAlive) tail else 0, longest = longest)
    }

    fun byWeek(playDates: List<LocalDate>, today: LocalDate): StreakResult {
        val weeks = playDates.map { DateUtils.startOfWeek(it) }.distinct().sorted()
        val (longest, tail, last) = run(weeks) { a, b -> ChronoUnit.DAYS.between(a, b) == 7L }
        val thisWeek = DateUtils.startOfWeek(today)
        val stillAlive = last != null &&
            (last == thisWeek || ChronoUnit.DAYS.between(last, thisWeek) == 7L)
        return StreakResult(current = if (stillAlive) tail else 0, longest = longest)
    }

    fun byMonth(playDates: List<LocalDate>, today: LocalDate): StreakResult {
        val months = playDates.map { YearMonth.from(it) }.distinct().sorted()
        var longest = 0
        var tail = 0
        var previous: YearMonth? = null
        months.forEach { month ->
            // Bound locally so the null check narrows the type for the comparison that
            // follows; reading the captured var twice would not.
            val prior = previous
            tail = if (prior != null && prior.plusMonths(1) == month) tail + 1 else 1
            previous = month
            if (tail > longest) longest = tail
        }
        val thisMonth = YearMonth.from(today)
        val last = previous
        val stillAlive = last != null &&
            (last == thisMonth || last.plusMonths(1) == thisMonth)
        return StreakResult(current = if (stillAlive) tail else 0, longest = longest)
    }

    /** Longest run of the same boolean value, used for "win ten in a row". */
    fun longestRunOf(results: List<Boolean>, value: Boolean): Int {
        var longest = 0
        var run = 0
        results.forEach { result ->
            run = if (result == value) run + 1 else 0
            if (run > longest) longest = run
        }
        return longest
    }

    /**
     * Walks a sorted, de-duplicated list of period anchors and returns the longest run,
     * the run still open at the end, and the last anchor seen. Whether that trailing run
     * is still *current* depends on the period, so each caller decides that itself.
     */
    private fun run(sorted: List<LocalDate>, isConsecutive: (LocalDate, LocalDate) -> Boolean): Triple<Int, Int, LocalDate?> {
        var longest = 0
        var tail = 0
        var previous: LocalDate? = null
        sorted.forEach { date ->
            val prior = previous
            tail = if (prior != null && isConsecutive(prior, date)) tail + 1 else 1
            previous = date
            if (tail > longest) longest = tail
        }
        return Triple(longest, tail, previous)
    }
}

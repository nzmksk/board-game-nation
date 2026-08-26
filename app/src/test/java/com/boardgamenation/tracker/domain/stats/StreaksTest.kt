package com.boardgamenation.tracker.domain.stats

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate

class StreaksTest {

    private fun dates(vararg iso: String) = iso.map(LocalDate::parse)

    @Test
    fun `consecutive days count as a run`() {
        val result = Streaks.byDay(
            dates("2026-03-01", "2026-03-02", "2026-03-03"),
            today = LocalDate.parse("2026-03-03"),
        )
        assertEquals(3, result.longest)
        assertEquals(3, result.current)
    }

    @Test
    fun `a gap breaks the run`() {
        val result = Streaks.byDay(
            dates("2026-03-01", "2026-03-02", "2026-03-05", "2026-03-06"),
            today = LocalDate.parse("2026-03-06"),
        )
        assertEquals(2, result.longest)
        assertEquals(2, result.current)
    }

    /** Yesterday still counts as alive; the day is not over until it is over. */
    @Test
    fun `a run that ended yesterday is still current`() {
        val result = Streaks.byDay(
            dates("2026-03-01", "2026-03-02"),
            today = LocalDate.parse("2026-03-03"),
        )
        assertEquals(2, result.current)
    }

    @Test
    fun `a lapsed run is no longer current but is still the longest`() {
        val result = Streaks.byDay(
            dates("2026-03-01", "2026-03-02", "2026-03-03"),
            today = LocalDate.parse("2026-03-20"),
        )
        assertEquals(3, result.longest)
        assertEquals(0, result.current)
    }

    @Test
    fun `two plays on one day count once`() {
        val result = Streaks.byDay(
            dates("2026-03-01", "2026-03-01", "2026-03-02"),
            today = LocalDate.parse("2026-03-02"),
        )
        assertEquals(2, result.longest)
    }

    @Test
    fun `several plays in one week count as one week`() {
        // Monday, Wednesday, Saturday of the same week, then the following Tuesday.
        val result = Streaks.byWeek(
            dates("2026-03-02", "2026-03-04", "2026-03-07", "2026-03-10"),
            today = LocalDate.parse("2026-03-10"),
        )
        assertEquals(2, result.longest)
        assertEquals(2, result.current)
    }

    /**
     * The reason week runs are computed from Monday-anchored dates rather than from
     * SQLite's `%Y-%W` keys: a run crossing new year would otherwise reset.
     */
    @Test
    fun `a week run survives a year boundary`() {
        val result = Streaks.byWeek(
            dates("2025-12-22", "2025-12-29", "2026-01-05"),
            today = LocalDate.parse("2026-01-05"),
        )
        assertEquals(3, result.longest)
        assertEquals(3, result.current)
    }

    @Test
    fun `consecutive months count as a run`() {
        val result = Streaks.byMonth(
            dates("2025-11-04", "2025-12-20", "2026-01-02"),
            today = LocalDate.parse("2026-01-02"),
        )
        assertEquals(3, result.longest)
        assertEquals(3, result.current)
    }

    @Test
    fun `a skipped month breaks the run`() {
        val result = Streaks.byMonth(
            dates("2025-11-04", "2026-01-02", "2026-02-02"),
            today = LocalDate.parse("2026-02-02"),
        )
        assertEquals(2, result.longest)
    }

    @Test
    fun `no plays means no streak`() {
        val result = Streaks.byDay(emptyList(), today = LocalDate.parse("2026-03-01"))
        assertEquals(0, result.longest)
        assertEquals(0, result.current)
    }

    @Test
    fun `the longest run of one result is found`() {
        val results = listOf(true, true, false, true, true, true, false)
        assertEquals(3, Streaks.longestRunOf(results, value = true))
        assertEquals(1, Streaks.longestRunOf(results, value = false))
    }

    @Test
    fun `an unbroken run of losses is measured too`() {
        assertEquals(4, Streaks.longestRunOf(List(4) { false }, value = false))
    }
}

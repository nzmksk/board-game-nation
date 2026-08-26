package com.boardgamenation.tracker.data.db.query

import com.boardgamenation.tracker.domain.model.CollectionFilter
import com.boardgamenation.tracker.domain.model.CollectionSort
import com.boardgamenation.tracker.domain.model.GameStatus
import com.boardgamenation.tracker.domain.model.PlaytimeBucket
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The collection query is assembled rather than fixed, so these tests are about the two
 * things that could go wrong when assembling SQL: values reaching the statement as text,
 * and the ordering not saying what it means.
 */
class GameQueryBuilderTest {

    /**
     * Checked against the join's tail rather than the word WHERE, which legitimately
     * appears inside the play-count and rating subqueries.
     */
    @Test
    fun `an unfiltered query appends no where clause and sorts by title`() {
        val query = GameQueryBuilder.build(CollectionFilter())
        assertFalse(query.appendedClause().contains("WHERE"))
        assertEquals(0, query.argCount)
        assertTrue(query.sql.contains("ORDER BY g.title COLLATE NOCASE ASC"))
    }

    @Test
    fun `a filtered query appends a where clause`() {
        val query = GameQueryBuilder.build(CollectionFilter(inPossessionOnly = true))
        assertTrue(query.appendedClause().contains("WHERE"))
    }

    /** Nothing typed by the user may ever end up concatenated into the SQL text. */
    @Test
    fun `the search term is bound, never interpolated`() {
        val query = GameQueryBuilder.build(CollectionFilter(search = "Catan"))
        assertFalse(query.sql.contains("Catan"))
        assertTrue(query.sql.contains("g.title LIKE ?"))
    }

    @Test
    fun `wildcards typed into the search box are escaped`() {
        assertEquals("100\\% Wool", GameQueryBuilder.escapeLike("100% Wool"))
        assertEquals("snake\\_case", GameQueryBuilder.escapeLike("snake_case"))
        assertEquals("back\\\\slash", GameQueryBuilder.escapeLike("back\\slash"))
    }

    @Test
    fun `an escape clause accompanies every LIKE`() {
        val query = GameQueryBuilder.build(CollectionFilter(search = "50%"))
        assertTrue(query.sql.contains("ESCAPE"))
    }

    @Test
    fun `a status filter produces one placeholder per status`() {
        val query = GameQueryBuilder.build(
            CollectionFilter(statuses = setOf(GameStatus.OWNED, GameStatus.WISHLIST)),
        )
        assertTrue(query.sql.contains("g.status IN (?, ?)"))
    }

    @Test
    fun `a player count filter checks the game's range`() {
        val query = GameQueryBuilder.build(CollectionFilter(playerCount = 4))
        assertTrue(query.sql.contains("g.min_players <= ?"))
        assertTrue(query.sql.contains("g.max_players >= ?"))
    }

    @Test
    fun `a playtime bucket filters on the stated maximum`() {
        val query = GameQueryBuilder.build(CollectionFilter(playtime = PlaytimeBucket.UNDER_30))
        assertTrue(query.sql.contains("g.max_playtime_minutes"))
    }

    @Test
    fun `tag filters use an exists subquery rather than a join`() {
        val query = GameQueryBuilder.build(CollectionFilter(tagIds = setOf(1L, 2L, 3L)))
        // A join would multiply rows for a game carrying several matching tags.
        assertTrue(query.sql.contains("EXISTS (SELECT 1 FROM game_tags"))
        assertTrue(query.sql.contains("gt.tag_id IN (?, ?, ?)"))
    }

    @Test
    fun `rated and unrated are opposite subqueries`() {
        assertTrue(
            GameQueryBuilder.build(CollectionFilter(rated = true))
                .sql.contains("EXISTS (SELECT 1 FROM game_ratings"),
        )
        assertTrue(
            GameQueryBuilder.build(CollectionFilter(rated = false))
                .sql.contains("NOT EXISTS (SELECT 1 FROM game_ratings"),
        )
    }

    @Test
    fun `an unset rated filter adds no clause at all`() {
        val query = GameQueryBuilder.build(CollectionFilter(rated = null))
        assertFalse(query.sql.contains("game_ratings r WHERE"))
    }

    /**
     * An unplayed game sorting to the top of "cheapest per play" would be a lie, so nulls
     * are pushed to the end of every ordering.
     */
    @Test
    fun `nulls sort last`() {
        val query = GameQueryBuilder.build(
            CollectionFilter(sort = CollectionSort.COST_PER_PLAY, ascending = true),
        )
        assertTrue(query.sql.contains("cost_per_play IS NULL, cost_per_play ASC"))
    }

    @Test
    fun `every sort falls back to title so the order is stable`() {
        CollectionSort.entries.filter { it != CollectionSort.TITLE }.forEach { sort ->
            val query = GameQueryBuilder.build(CollectionFilter(sort = sort))
            assertTrue(
                "sort $sort should tie-break on title",
                query.sql.contains("g.title COLLATE NOCASE ASC"),
            )
        }
    }

    @Test
    fun `descending is honoured`() {
        val query = GameQueryBuilder.build(
            CollectionFilter(sort = CollectionSort.PLAY_COUNT, ascending = false),
        )
        assertTrue(query.sql.contains("play_count DESC"))
    }

    @Test
    fun `hiding expansions adds the flag check`() {
        assertTrue(
            GameQueryBuilder.build(CollectionFilter(includeExpansions = false))
                .sql.contains("g.is_expansion = 0"),
        )
        assertFalse(
            GameQueryBuilder.build(CollectionFilter(includeExpansions = true))
                .sql.contains("g.is_expansion = 0"),
        )
    }

    /**
     * Counting bound arguments rather than the word AND, since a single clause can
     * contain several of its own.
     */
    @Test
    fun `several filters each contribute their own bound arguments`() {
        val query = GameQueryBuilder.build(
            CollectionFilter(
                search = "a",
                statuses = setOf(GameStatus.OWNED),
                playerCount = 2,
                inPossessionOnly = true,
            ),
        )
        // One for the search term, one for the status, two for the player count; the
        // in-possession flag is a literal.
        assertEquals(4, query.argCount)
    }

    /** Everything after the fixed FROM/JOIN block: the part the builder assembles. */
    private fun androidx.sqlite.db.SupportSQLiteQuery.appendedClause(): String =
        sql.substringAfter("pc ON pc.game_id = g.id")

    @Test
    fun `the active filter count matches what the chips show`() {
        val filter = CollectionFilter(
            statuses = setOf(GameStatus.OWNED),
            playerCount = 4,
            inPossessionOnly = true,
        )
        assertEquals(3, filter.activeCount)
        assertTrue(filter.isActive)
        assertFalse(CollectionFilter().isActive)
    }
}

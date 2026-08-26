package com.boardgamenation.tracker.data.db.query

import androidx.sqlite.db.SimpleSQLiteQuery
import androidx.sqlite.db.SupportSQLiteQuery
import com.boardgamenation.tracker.domain.model.CollectionFilter
import com.boardgamenation.tracker.domain.model.CollectionSort

/**
 * Assembles the collection list query.
 *
 * Play count, last-played date, rating and cost-per-play are all derived in SQL rather
 * than in Kotlin, because the list sorts by them: computing them in memory would mean
 * loading the whole collection to sort it, which is exactly what the 500-game/5000-session
 * performance target rules out.
 *
 * Values are always bound as arguments, never interpolated. The only text ever
 * concatenated into the SQL comes from this file's own constants.
 */
object GameQueryBuilder {

    private const val SELECT = """
        SELECT
            g.id, g.title, g.year_published, g.thumbnail_path, g.status,
            g.min_players, g.max_players, g.min_playtime_minutes, g.max_playtime_minutes,
            g.weight, g.price, g.currency, g.in_possession, g.is_expansion,
            g.wishlist_priority, g.date_added, g.lent_to, g.lent_date,
            COALESCE(pc.play_count, 0) AS play_count,
            pc.last_played AS last_played,
            (
                SELECT gr.computed_score FROM game_ratings gr
                WHERE gr.game_id = g.id
                ORDER BY gr.rated_on DESC, gr.id DESC LIMIT 1
            ) AS rating,
            CASE
                WHEN g.price IS NOT NULL AND COALESCE(pc.play_count, 0) > 0
                THEN g.price / pc.play_count
            END AS cost_per_play
        FROM games g
        LEFT JOIN (
            SELECT game_id, COUNT(*) AS play_count, MAX(played_on) AS last_played
            FROM sessions WHERE is_draft = 0 GROUP BY game_id
        ) pc ON pc.game_id = g.id
    """

    fun build(filter: CollectionFilter): SupportSQLiteQuery {
        val where = mutableListOf<String>()
        val args = mutableListOf<Any?>()

        if (filter.search.isNotBlank()) {
            // Escaping the wildcards keeps a literal % or _ in the search box from
            // quietly matching everything.
            where += "g.title LIKE ? ESCAPE '\\'"
            args += "%" + escapeLike(filter.search.trim()) + "%"
        }

        if (filter.statuses.isNotEmpty()) {
            where += "g.status IN (" + placeholders(filter.statuses.size) + ")"
            filter.statuses.forEach { args += it.name }
        }

        filter.playerCount?.let { count ->
            where += "(g.min_players IS NOT NULL AND g.max_players IS NOT NULL " +
                "AND g.min_players <= ? AND g.max_players >= ?)"
            args += count
            args += count
        }

        filter.playtime?.let { bucket ->
            where += "(g.max_playtime_minutes IS NOT NULL " +
                "AND g.max_playtime_minutes >= ? AND g.max_playtime_minutes <= ?)"
            args += bucket.minMinutes
            args += bucket.maxMinutes
        }

        if (filter.tagIds.isNotEmpty()) {
            where += "EXISTS (SELECT 1 FROM game_tags gt WHERE gt.game_id = g.id " +
                "AND gt.tag_id IN (" + placeholders(filter.tagIds.size) + "))"
            filter.tagIds.forEach { args += it }
        }

        when (filter.rated) {
            true -> where += "EXISTS (SELECT 1 FROM game_ratings r WHERE r.game_id = g.id)"
            false -> where += "NOT EXISTS (SELECT 1 FROM game_ratings r WHERE r.game_id = g.id)"
            null -> Unit
        }

        if (filter.inPossessionOnly) where += "g.in_possession = 1"
        if (!filter.includeExpansions) where += "g.is_expansion = 0"

        val sql = buildString {
            append(SELECT)
            if (where.isNotEmpty()) {
                append(" WHERE ")
                append(where.joinToString(" AND "))
            }
            append(" ORDER BY ")
            append(orderBy(filter))
        }
        return SimpleSQLiteQuery(sql, args.toTypedArray())
    }

    /**
     * NULLs are pushed last in every ordering. An unpriced or unplayed game sorting to
     * the top of "cheapest per play" would be a lie, not a result.
     */
    private fun orderBy(filter: CollectionFilter): String {
        val dir = if (filter.ascending) "ASC" else "DESC"
        val tieBreak = "g.title COLLATE NOCASE ASC"
        return when (filter.sort) {
            CollectionSort.TITLE -> "g.title COLLATE NOCASE $dir"
            CollectionSort.DATE_ADDED -> "g.date_added $dir, $tieBreak"
            CollectionSort.PLAY_COUNT -> "play_count $dir, $tieBreak"
            CollectionSort.RATING -> "rating IS NULL, rating $dir, $tieBreak"
            CollectionSort.PRICE -> "g.price IS NULL, g.price $dir, $tieBreak"
            CollectionSort.COST_PER_PLAY -> "cost_per_play IS NULL, cost_per_play $dir, $tieBreak"
            CollectionSort.LAST_PLAYED -> "last_played IS NULL, last_played $dir, $tieBreak"
            CollectionSort.WEIGHT -> "g.weight IS NULL, g.weight $dir, $tieBreak"
        }
    }

    private fun placeholders(count: Int): String = List(count) { "?" }.joinToString(", ")

    internal fun escapeLike(raw: String): String = buildString {
        raw.forEach { c ->
            when (c) {
                '\\', '%', '_' -> {
                    append('\\')
                    append(c)
                }
                else -> append(c)
            }
        }
    }
}

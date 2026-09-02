package com.boardgamenation.tracker.data.db.dao

import androidx.room.Dao
import androidx.room.Query
import com.boardgamenation.tracker.data.db.projection.CostPerPlayRow
import com.boardgamenation.tracker.data.db.projection.DurationVsExpectedRow
import com.boardgamenation.tracker.data.db.projection.HeadToHeadRow
import com.boardgamenation.tracker.data.db.projection.LabelledValue
import com.boardgamenation.tracker.data.db.projection.PlayerStandingRow
import com.boardgamenation.tracker.data.db.projection.SessionListItem
import kotlinx.coroutines.flow.Flow

/**
 * Every statistic is an aggregate query. Nothing here loads a table into memory to
 * count it, which is what keeps the stats screen usable at 5,000 sessions.
 *
 * Deliberately free of window functions: minSdk 26 ships SQLite 3.19, which predates
 * them. The two genuinely sequential metrics (streaks) return a compact distinct-period
 * list that the repository walks, rather than a full table scan in Kotlin.
 */
@Dao
interface StatsDao {

    // --- collection ---------------------------------------------------------------

    @Query("SELECT COUNT(*) FROM games WHERE status IN ('OWNED', 'LENT_OUT') AND is_expansion = 0")
    fun observeOwnedBaseGameCount(): Flow<Int>

    @Query("SELECT COUNT(*) FROM games WHERE status IN ('OWNED', 'LENT_OUT') AND is_expansion = 1")
    fun observeOwnedExpansionCount(): Flow<Int>

    /** Wishlist and sold copies are deliberately outside the collection's value. */
    @Query(
        """
        SELECT COALESCE(SUM(price), 0) FROM games
        WHERE status IN ('OWNED', 'LENT_OUT') AND price IS NOT NULL
        """,
    )
    fun observeCollectionValue(): Flow<Double>

    @Query(
        """
        SELECT t.name AS label, COUNT(DISTINCT gt.game_id) * 1.0 AS value
        FROM tags t
        JOIN game_tags gt ON gt.tag_id = t.id
        JOIN games g ON g.id = gt.game_id
        WHERE t.kind = :kind AND g.status IN ('OWNED', 'LENT_OUT')
        GROUP BY t.id
        ORDER BY value DESC, t.name COLLATE NOCASE
        LIMIT :limit
        """,
    )
    fun observeTagDistribution(kind: String, limit: Int): Flow<List<LabelledValue>>

    @Query(
        """
        SELECT
            CASE
                WHEN weight < 1.5 THEN '1.0-1.5'
                WHEN weight < 2.0 THEN '1.5-2.0'
                WHEN weight < 2.5 THEN '2.0-2.5'
                WHEN weight < 3.0 THEN '2.5-3.0'
                WHEN weight < 3.5 THEN '3.0-3.5'
                WHEN weight < 4.0 THEN '3.5-4.0'
                ELSE '4.0+'
            END AS label,
            COUNT(*) * 1.0 AS value
        FROM games
        WHERE weight IS NOT NULL AND status IN ('OWNED', 'LENT_OUT')
        GROUP BY label
        ORDER BY label
        """,
    )
    fun observeWeightDistribution(): Flow<List<LabelledValue>>

    /**
     * How many owned games support each head count. The literal series stands in for a
     * numbers table, which SQLite has no built-in equivalent of.
     */
    @Query(
        """
        SELECT n.c AS label, (
            SELECT COUNT(*) FROM games g
            WHERE g.status IN ('OWNED', 'LENT_OUT') AND g.is_expansion = 0
              AND g.min_players IS NOT NULL AND g.max_players IS NOT NULL
              AND g.min_players <= n.c AND g.max_players >= n.c
        ) * 1.0 AS value
        FROM (
            SELECT 1 AS c UNION ALL SELECT 2 UNION ALL SELECT 3 UNION ALL SELECT 4
            UNION ALL SELECT 5 UNION ALL SELECT 6 UNION ALL SELECT 7 UNION ALL SELECT 8
        ) n
        ORDER BY n.c
        """,
    )
    fun observePlayerCountCoverage(): Flow<List<LabelledValue>>

    /** The shelf of shame: owned and never played. */
    @Query(
        """
        SELECT g.title AS label, julianday('now') - julianday(g.date_added) AS value
        FROM games g
        WHERE g.status IN ('OWNED', 'LENT_OUT')
          AND NOT EXISTS (SELECT 1 FROM sessions s WHERE s.game_id = g.id AND s.is_draft = 0)
        ORDER BY g.date_added ASC
        """,
    )
    fun observeUnplayedGames(): Flow<List<LabelledValue>>

    @Query(
        """
        SELECT COUNT(*) FROM games g
        WHERE g.status IN ('OWNED', 'LENT_OUT')
          AND NOT EXISTS (SELECT 1 FROM game_ratings r WHERE r.game_id = g.id)
        """,
    )
    fun observeUnratedOwnedCount(): Flow<Int>

    // --- plays --------------------------------------------------------------------

    @Query("SELECT COUNT(*) FROM sessions WHERE is_draft = 0")
    fun observeTotalPlays(): Flow<Int>

    @Query("SELECT COALESCE(SUM(duration_minutes), 0) FROM sessions WHERE is_draft = 0")
    fun observeTotalMinutes(): Flow<Int>

    @Query("SELECT COUNT(DISTINCT game_id) FROM sessions WHERE is_draft = 0")
    fun observeDistinctGamesPlayed(): Flow<Int>

    @Query(
        """
        SELECT strftime('%Y-%m', played_on) AS label, COUNT(*) * 1.0 AS value
        FROM sessions WHERE is_draft = 0
        GROUP BY label ORDER BY label
        """,
    )
    fun observePlaysByMonth(): Flow<List<LabelledValue>>

    @Query(
        """
        SELECT strftime('%w', played_on) AS label, COUNT(*) * 1.0 AS value
        FROM sessions WHERE is_draft = 0
        GROUP BY label ORDER BY label
        """,
    )
    fun observePlaysByDayOfWeek(): Flow<List<LabelledValue>>

    @Query(
        """
        SELECT g.title AS label, COUNT(*) * 1.0 AS value
        FROM sessions s JOIN games g ON g.id = s.game_id
        WHERE s.is_draft = 0
        GROUP BY g.id ORDER BY value DESC, g.title COLLATE NOCASE
        LIMIT :limit
        """,
    )
    fun observeMostPlayed(limit: Int): Flow<List<LabelledValue>>

    @Query(
        """
        SELECT
            s.id, s.game_id, g.title AS game_title, g.thumbnail_path,
            s.played_on, s.duration_minutes, s.player_count, s.location,
            s.is_cooperative, (s.coop_outcome = 'WIN') AS coop_won, s.mode,
            s.is_incomplete, s.is_teaching_game, s.end_reason,
            (
                SELECT GROUP_CONCAT(p.name, ', ') FROM session_players sp
                JOIN players p ON p.id = sp.player_id
                WHERE sp.session_id = s.id AND sp.is_winner = 1
            ) AS winner_names
        FROM sessions s JOIN games g ON g.id = s.game_id
        WHERE s.is_draft = 0 AND s.is_incomplete = 0
        ORDER BY CASE WHEN :longest = 1 THEN -s.duration_minutes ELSE s.duration_minutes END
        LIMIT :limit
        """,
    )
    fun observeExtremeSessions(longest: Boolean, limit: Int): Flow<List<SessionListItem>>

    /**
     * Actual average duration against the midpoint of BGG's stated range. Only games
     * with a few plays qualify, because one teaching game is not evidence.
     */
    @Query(
        """
        SELECT
            g.id AS game_id, g.title,
            AVG(s.duration_minutes) AS actual_avg,
            (g.min_playtime_minutes + g.max_playtime_minutes) / 2.0 AS stated_avg,
            COUNT(*) AS play_count
        FROM sessions s JOIN games g ON g.id = s.game_id
        WHERE s.is_draft = 0 AND s.is_incomplete = 0 AND s.is_teaching_game = 0
          AND g.min_playtime_minutes IS NOT NULL AND g.max_playtime_minutes IS NOT NULL
        GROUP BY g.id
        HAVING COUNT(*) >= :minPlays
        ORDER BY ABS(AVG(s.duration_minutes) -
                     (g.min_playtime_minutes + g.max_playtime_minutes) / 2.0) DESC
        LIMIT :limit
        """,
    )
    fun observeDurationVsExpected(minPlays: Int, limit: Int): Flow<List<DurationVsExpectedRow>>

    /** Distinct ISO weeks that saw a play, newest first. Small by construction. */
    @Query(
        """
        SELECT DISTINCT strftime('%Y-%W', played_on) AS label, 1.0 AS value
        FROM sessions WHERE is_draft = 0
        ORDER BY label DESC
        """,
    )
    fun observeWeeksWithPlays(): Flow<List<LabelledValue>>

    @Query(
        """
        SELECT DISTINCT played_on AS label, 1.0 AS value
        FROM sessions WHERE is_draft = 0
        ORDER BY label DESC
        """,
    )
    fun observeDaysWithPlays(): Flow<List<LabelledValue>>

    /**
     * H-index: the largest N where at least N games have been played at least N times.
     * The correlated count is the window-function-free way to rank the play counts.
     */
    @Query(
        """
        SELECT COUNT(*) FROM (
            SELECT s.game_id AS gid, COUNT(*) AS plays
            FROM sessions s WHERE s.is_draft = 0 GROUP BY s.game_id
        ) t
        WHERE t.plays >= (
            SELECT COUNT(*) FROM (
                SELECT s2.game_id AS gid2, COUNT(*) AS plays2
                FROM sessions s2 WHERE s2.is_draft = 0 GROUP BY s2.game_id
            ) t2
            WHERE t2.plays2 >= t.plays
        )
        """,
    )
    fun observeHIndex(): Flow<Int>

    // --- value --------------------------------------------------------------------

    @Query(
        """
        SELECT
            g.id AS game_id, g.title, g.price, g.currency,
            COUNT(s.id) AS play_count,
            g.price / COUNT(s.id) AS cost_per_play
        FROM games g
        JOIN sessions s ON s.game_id = g.id AND s.is_draft = 0
        WHERE g.price IS NOT NULL AND g.price > 0 AND g.status IN ('OWNED', 'LENT_OUT')
        GROUP BY g.id
        HAVING COUNT(s.id) > 0
        ORDER BY CASE WHEN :cheapestFirst = 1 THEN g.price / COUNT(s.id)
                      ELSE -(g.price / COUNT(s.id)) END
        LIMIT :limit
        """,
    )
    fun observeCostPerPlay(cheapestFirst: Boolean, limit: Int): Flow<List<CostPerPlayRow>>

    @Query(
        """
        SELECT
            COALESCE(SUM(g.price), 0) /
            NULLIF((SELECT COUNT(*) FROM sessions s2
                    JOIN games g2 ON g2.id = s2.game_id
                    WHERE s2.is_draft = 0 AND g2.price IS NOT NULL
                      AND g2.status IN ('OWNED', 'LENT_OUT')), 0)
        FROM games g
        WHERE g.price IS NOT NULL AND g.status IN ('OWNED', 'LENT_OUT')
        """,
    )
    fun observeOverallCostPerPlay(): Flow<Double?>

    @Query(
        """
        SELECT substr(date_added, 1, 4) AS label, COALESCE(SUM(price), 0) AS value
        FROM games
        WHERE price IS NOT NULL AND status IN ('OWNED', 'LENT_OUT', 'SOLD')
        GROUP BY label ORDER BY label
        """,
    )
    fun observeSpendByYear(): Flow<List<LabelledValue>>

    /** Owned, priced, and still never played: the purchases that have earned nothing. */
    @Query(
        """
        SELECT g.title AS label, g.price AS value
        FROM games g
        WHERE g.price IS NOT NULL AND g.price > 0 AND g.status IN ('OWNED', 'LENT_OUT')
          AND NOT EXISTS (SELECT 1 FROM sessions s WHERE s.game_id = g.id AND s.is_draft = 0)
        ORDER BY g.price DESC
        LIMIT :limit
        """,
    )
    fun observeDeadWeight(limit: Int): Flow<List<LabelledValue>>

    // --- players ------------------------------------------------------------------

    @Query(
        """
        SELECT
            p.id AS player_id, p.name AS player_name, p.color_hex,
            COUNT(sp.id) AS plays,
            COALESCE(SUM(sp.is_winner), 0) AS wins,
            AVG(sp.score) AS avg_score
        FROM players p
        JOIN session_players sp ON sp.player_id = p.id
        JOIN sessions s ON s.id = sp.session_id AND s.is_draft = 0
        WHERE (:gameId IS NULL OR s.game_id = :gameId)
        GROUP BY p.id
        HAVING COUNT(sp.id) > 0
        ORDER BY wins * 1.0 / COUNT(sp.id) DESC, plays DESC
        """,
    )
    fun observeStandings(gameId: Long?): Flow<List<PlayerStandingRow>>

    /**
     * Head-to-head against the device owner. Only competitive sessions count: in a
     * co-op everybody wins or loses together, which says nothing about who is better.
     */
    @Query(
        """
        SELECT
            p.id AS opponent_id, p.name AS opponent_name, p.color_hex,
            COUNT(*) AS shared_plays,
            COALESCE(SUM(self.is_winner), 0) AS self_wins,
            COALESCE(SUM(opp.is_winner), 0) AS opponent_wins
        FROM session_players opp
        JOIN players p ON p.id = opp.player_id
        JOIN sessions s ON s.id = opp.session_id AND s.is_draft = 0 AND s.is_cooperative = 0
        JOIN session_players self ON self.session_id = s.id
        JOIN players sp2 ON sp2.id = self.player_id AND sp2.is_self = 1
        WHERE p.is_self = 0
        GROUP BY p.id
        ORDER BY shared_plays DESC, p.name COLLATE NOCASE
        """,
    )
    fun observeHeadToHead(): Flow<List<HeadToHeadRow>>

    /**
     * Sudden-death plays are excluded. A game that ended the moment a condition was met
     * never reached final scoring, so any number recorded against it is a partial count
     * and averaging it together with full scores understates the average.
     */
    @Query(
        """
        SELECT g.title AS label, AVG(sp.score) AS value
        FROM session_players sp
        JOIN sessions s ON s.id = sp.session_id AND s.is_draft = 0
            AND s.end_condition IS NULL
        JOIN games g ON g.id = s.game_id
        WHERE sp.player_id = :playerId AND sp.score IS NOT NULL
        GROUP BY g.id
        ORDER BY value DESC
        LIMIT :limit
        """,
    )
    fun observeAverageScoreByGame(playerId: Long, limit: Int): Flow<List<LabelledValue>>

    @Query(
        """
        SELECT g.title AS label,
               COALESCE(SUM(sp.is_winner), 0) * 100.0 / COUNT(*) AS value
        FROM session_players sp
        JOIN sessions s ON s.id = sp.session_id AND s.is_draft = 0 AND s.is_cooperative = 0
        JOIN games g ON g.id = s.game_id
        WHERE sp.player_id = :playerId
        GROUP BY g.id
        HAVING COUNT(*) >= :minPlays
        ORDER BY value DESC, label COLLATE NOCASE
        """,
    )
    fun observeWinRateByGame(playerId: Long, minPlays: Int): Flow<List<LabelledValue>>
}

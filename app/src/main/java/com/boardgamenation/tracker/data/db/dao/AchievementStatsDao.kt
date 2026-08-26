package com.boardgamenation.tracker.data.db.dao

import androidx.room.Dao
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/**
 * The numbers the achievement engine measures against.
 *
 * Each is an aggregate: the evaluator asks the database for one figure rather than
 * loading rows to count them. The two ordered queries at the bottom are the exceptions,
 * because "consecutive" cannot be expressed without window functions, which SQLite on
 * API 26 does not have.
 */
@Dao
interface AchievementStatsDao {

    /**
     * A cheap query whose only job is to re-emit when anything the achievements depend
     * on changes. Room invalidates per table, so any insert, update or delete on these
     * tables re-triggers the whole snapshot, not just ones that move this number.
     */
    @Query(
        """
        SELECT (SELECT COUNT(*) FROM sessions) + (SELECT COUNT(*) FROM session_players)
             + (SELECT COUNT(*) FROM games) + (SELECT COUNT(*) FROM game_tags)
             + (SELECT COUNT(*) FROM game_ratings)
        """,
    )
    fun observeInvalidationToken(): Flow<Int>

    @Query("SELECT COUNT(*) FROM sessions WHERE is_draft = 0")
    suspend fun totalPlays(): Int

    @Query("SELECT COALESCE(SUM(duration_minutes), 0) / 60.0 FROM sessions WHERE is_draft = 0")
    suspend fun totalHours(): Double

    @Query("SELECT COUNT(*) FROM games WHERE status IN ('OWNED', 'LENT_OUT')")
    suspend fun gamesOwned(): Int

    @Query("SELECT COUNT(DISTINCT game_id) FROM sessions WHERE is_draft = 0")
    suspend fun distinctGamesPlayed(): Int

    @Query(
        """
        SELECT COUNT(DISTINCT t.id) FROM tags t
        JOIN game_tags gt ON gt.tag_id = t.id
        WHERE t.kind = 'MECHANIC'
          AND EXISTS (SELECT 1 FROM sessions s WHERE s.game_id = gt.game_id AND s.is_draft = 0)
        """,
    )
    suspend fun distinctMechanicsPlayed(): Int

    /** Distinct games where somebody was being taught, not the number of teaching plays. */
    @Query(
        """
        SELECT COUNT(DISTINCT s.game_id) FROM sessions s
        WHERE s.is_draft = 0 AND s.is_teaching_game = 1
          AND EXISTS (
              SELECT 1 FROM session_players sp
              WHERE sp.session_id = s.id AND sp.is_new_player = 1
          )
        """,
    )
    suspend fun gamesTaught(): Int

    @Query("SELECT COUNT(DISTINCT game_id) FROM game_ratings")
    suspend fun gamesRated(): Int

    @Query(
        """
        SELECT COUNT(DISTINCT sp.player_id) FROM session_players sp
        JOIN sessions s ON s.id = sp.session_id AND s.is_draft = 0
        """,
    )
    suspend fun distinctPlayers(): Int

    @Query(
        """
        SELECT COALESCE(MAX(c), 0) FROM (
            SELECT COUNT(*) AS c FROM sessions WHERE is_draft = 0 GROUP BY game_id
        )
        """,
    )
    suspend fun maxPlaysOfSingleGame(): Int

    @Query(
        """
        SELECT COALESCE(MAX(c), 0) FROM (
            SELECT COUNT(*) AS c FROM sessions WHERE is_draft = 0 GROUP BY played_on
        )
        """,
    )
    suspend fun maxPlaysInOneDay(): Int

    @Query(
        """
        SELECT COALESCE(MAX(c), 0) FROM (
            SELECT COUNT(*) AS c FROM sessions WHERE is_draft = 0
            GROUP BY strftime('%Y-%W', played_on)
        )
        """,
    )
    suspend fun maxPlaysInOneWeek(): Int

    @Query(
        """
        SELECT COALESCE(MAX(c), 0) FROM (
            SELECT COUNT(*) AS c FROM sessions WHERE is_draft = 0
            GROUP BY strftime('%Y-%m', played_on)
        )
        """,
    )
    suspend fun maxPlaysInOneMonth(): Int

    @Query("SELECT COALESCE(MAX(player_count), 0) FROM sessions WHERE is_draft = 0")
    suspend fun maxSessionPlayerCount(): Int

    @Query("SELECT COALESCE(MAX(duration_minutes), 0) FROM sessions WHERE is_draft = 0")
    suspend fun maxSessionDurationMinutes(): Int

    @Query(
        """
        SELECT COALESCE(MAX(g.weight), 0) FROM games g
        WHERE g.weight IS NOT NULL
          AND EXISTS (SELECT 1 FROM sessions s WHERE s.game_id = g.id AND s.is_draft = 0)
        """,
    )
    suspend fun maxWeightPlayed(): Double

    /** The device owner's best win rate, as a percentage, over any game with enough plays. */
    @Query(
        """
        SELECT COALESCE(MAX(rate), 0) FROM (
            SELECT COALESCE(SUM(sp.is_winner), 0) * 100.0 / COUNT(*) AS rate
            FROM session_players sp
            JOIN players p ON p.id = sp.player_id AND p.is_self = 1
            JOIN sessions s ON s.id = sp.session_id AND s.is_draft = 0 AND s.is_cooperative = 0
            GROUP BY s.game_id
            HAVING COUNT(*) >= :minPlays
        )
        """,
    )
    suspend fun bestWinRate(minPlays: Int): Double

    @Query(
        """
        SELECT COUNT(*) FROM games g
        WHERE g.status IN ('OWNED', 'LENT_OUT')
          AND NOT EXISTS (SELECT 1 FROM sessions s WHERE s.game_id = g.id AND s.is_draft = 0)
        """,
    )
    suspend fun unplayedOwnedCount(): Int

    @Query(
        """
        SELECT COALESCE(MIN(cpp), 0) FROM (
            SELECT g.price / COUNT(s.id) AS cpp
            FROM games g
            JOIN sessions s ON s.game_id = g.id AND s.is_draft = 0
            WHERE g.price IS NOT NULL AND g.price > 0
            GROUP BY g.id
            HAVING COUNT(s.id) > 0
        )
        """,
    )
    suspend fun lowestCostPerPlay(): Double

    /**
     * Mechanics where every owned game carrying the tag has been played at least once.
     * A mechanic with no owned games is not an accomplishment, hence the HAVING.
     */
    @Query(
        """
        SELECT COUNT(*) FROM (
            SELECT t.id
            FROM tags t
            JOIN game_tags gt ON gt.tag_id = t.id
            JOIN games g ON g.id = gt.game_id AND g.status IN ('OWNED', 'LENT_OUT')
            WHERE t.kind = 'MECHANIC'
            GROUP BY t.id
            HAVING COUNT(*) >= :minGames AND SUM(
                CASE WHEN EXISTS (
                    SELECT 1 FROM sessions s WHERE s.game_id = g.id AND s.is_draft = 0
                ) THEN 0 ELSE 1 END
            ) = 0
        )
        """,
    )
    suspend fun fullyPlayedMechanicCount(minGames: Int): Int

    // --- ordered, for the sequential metrics ---------------------------------------

    /** Distinct ISO dates with a play, ascending. One short string per playing day. */
    @Query("SELECT DISTINCT played_on FROM sessions WHERE is_draft = 0 ORDER BY played_on")
    suspend fun playDates(): List<String>

    /** Distinct year-week keys with a play, ascending. */
    @Query(
        """
        SELECT DISTINCT strftime('%Y-%W', played_on) AS wk
        FROM sessions WHERE is_draft = 0 ORDER BY wk
        """,
    )
    suspend fun playWeeks(): List<String>

    /** Distinct year-month keys with a play, ascending. */
    @Query(
        """
        SELECT DISTINCT strftime('%Y-%m', played_on) AS mo
        FROM sessions WHERE is_draft = 0 ORDER BY mo
        """,
    )
    suspend fun playMonths(): List<String>

    /**
     * The device owner's competitive results in play order. A single boolean column, so
     * even a few thousand plays is a trivial read, and it is the only way to answer
     * "ten in a row" without window functions.
     */
    @Query(
        """
        SELECT sp.is_winner FROM session_players sp
        JOIN players p ON p.id = sp.player_id AND p.is_self = 1
        JOIN sessions s ON s.id = sp.session_id AND s.is_draft = 0 AND s.is_cooperative = 0
        ORDER BY s.played_on, s.id
        """,
    )
    suspend fun selfResultsInOrder(): List<Boolean>
}

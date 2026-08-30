package com.boardgamenation.tracker.data.db.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.RawQuery
import androidx.room.Transaction
import androidx.room.Update
import androidx.sqlite.db.SupportSQLiteQuery
import com.boardgamenation.tracker.data.db.entity.GameEntity
import com.boardgamenation.tracker.data.db.entity.GameRatingEntity
import com.boardgamenation.tracker.data.db.entity.GameTagCrossRef
import com.boardgamenation.tracker.data.db.entity.SessionEntity
import com.boardgamenation.tracker.data.db.projection.FactionRecord
import com.boardgamenation.tracker.data.db.projection.GameAggregates
import com.boardgamenation.tracker.data.db.projection.GameListItem
import com.boardgamenation.tracker.domain.model.GameStatus
import kotlinx.coroutines.flow.Flow

@Dao
interface GameDao {

    /**
     * The collection list. The shape of the filter varies far too much for a static
     * query, so [com.boardgamenation.tracker.data.db.query.GameQueryBuilder] assembles
     * the SQL and Room still re-runs it whenever any observed table changes.
     */
    @RawQuery(
        observedEntities = [
            GameEntity::class,
            SessionEntity::class,
            GameRatingEntity::class,
            GameTagCrossRef::class,
        ],
    )
    fun observeCollection(query: SupportSQLiteQuery): Flow<List<GameListItem>>

    @Query("SELECT * FROM games WHERE id = :id")
    fun observeGame(id: Long): Flow<GameEntity?>

    @Query("SELECT * FROM games WHERE id = :id")
    suspend fun getGame(id: Long): GameEntity?

    @Query("SELECT * FROM games WHERE bgg_id = :bggId LIMIT 1")
    suspend fun getGameByBggId(bggId: Long): GameEntity?

    @Query("SELECT * FROM games WHERE title = :title COLLATE NOCASE LIMIT 1")
    suspend fun getGameByTitle(title: String): GameEntity?

    @Query("SELECT * FROM games ORDER BY title COLLATE NOCASE")
    suspend fun getAllGames(): List<GameEntity>

    /** Base games only, for pickers where an expansion makes no sense as the subject. */
    @Query(
        """
        SELECT * FROM games
        WHERE is_expansion = 0 AND status IN ('OWNED', 'LENT_OUT')
        ORDER BY title COLLATE NOCASE
        """,
    )
    fun observeBaseGames(): Flow<List<GameEntity>>

    @Query("SELECT * FROM games WHERE base_game_id = :baseGameId ORDER BY title COLLATE NOCASE")
    fun observeExpansionsOf(baseGameId: Long): Flow<List<GameEntity>>

    @Query("SELECT * FROM games WHERE base_game_id = :baseGameId ORDER BY title COLLATE NOCASE")
    suspend fun getExpansionsOf(baseGameId: Long): List<GameEntity>

    /**
     * Everything the game detail screen needs in one pass. Incomplete sessions are
     * counted as plays but kept out of the duration averages, because an abandoned game
     * says nothing useful about how long the game takes.
     */
    @Query(
        """
        SELECT
            COUNT(*) AS play_count,
            COALESCE(SUM(s.duration_minutes), 0) AS total_minutes,
            AVG(CASE WHEN s.is_incomplete = 0 THEN s.duration_minutes END) AS avg_minutes,
            AVG(CASE WHEN s.is_incomplete = 0 AND s.is_teaching_game = 0
                     THEN s.duration_minutes END) AS avg_minutes_non_teaching,
            MIN(CASE WHEN s.is_incomplete = 0 THEN s.duration_minutes END) AS shortest_minutes,
            MAX(CASE WHEN s.is_incomplete = 0 THEN s.duration_minutes END) AS longest_minutes,
            MIN(s.played_on) AS first_played,
            MAX(s.played_on) AS last_played,
            COALESCE(SUM(CASE WHEN s.is_cooperative = 1 THEN (s.coop_outcome = 'WIN')
                              ELSE EXISTS (
                                  SELECT 1 FROM session_players sp
                                  JOIN players p ON p.id = sp.player_id
                                  WHERE sp.session_id = s.id AND sp.is_winner = 1 AND p.is_self = 1
                              ) END), 0) AS wins,
            COALESCE(SUM(EXISTS (
                SELECT 1 FROM session_players sp
                JOIN players p ON p.id = sp.player_id
                WHERE sp.session_id = s.id AND p.is_self = 1
            )), 0) AS self_plays
        FROM sessions s
        WHERE s.game_id = :gameId AND s.is_draft = 0
        """,
    )
    fun observeAggregates(gameId: Long): Flow<GameAggregates>

    /**
     * Win rate per faction for one game, over every player who has played it.
     *
     * Abandoned plays are excluded, the same way they are excluded from the duration
     * averages: a game nobody finished has no winner, and counting it would drag every
     * faction down as though each had lost.
     *
     * Co-operative plays are counted. A game where the table shares one result still
     * asks a real balance question -- which spirit, which character, which role tends
     * to be at the table when the table wins.
     *
     * Grouped case-insensitively so "Alexandria" and "alexandria" are one faction; the
     * name shown is whichever spelling SQLite picks out of the group, which is stable
     * for a given set of rows.
     */
    @Query(
        """
        SELECT
            sp.faction AS faction,
            COUNT(*) AS plays,
            COALESCE(SUM(sp.is_winner), 0) AS wins
        FROM session_players sp
        JOIN sessions s ON s.id = sp.session_id
        WHERE s.game_id = :gameId AND s.is_draft = 0 AND s.is_incomplete = 0
          AND sp.faction IS NOT NULL AND trim(sp.faction) <> ''
        GROUP BY sp.faction COLLATE NOCASE
        ORDER BY (wins * 1.0 / plays) DESC, plays DESC, faction COLLATE NOCASE
        """,
    )
    fun observeFactionRecords(gameId: Long): Flow<List<FactionRecord>>

    @Query(
        """
        SELECT * FROM games
        WHERE in_possession = 0 AND lent_date IS NOT NULL
        ORDER BY lent_date ASC
        """,
    )
    fun observeLentOut(): Flow<List<GameEntity>>

    /**
     * Loans older than [cutoffIsoDate]. The comparison is a plain string compare, which
     * is exactly right for ISO-8601 dates and needs no date functions.
     */
    @Query(
        """
        SELECT * FROM games
        WHERE in_possession = 0 AND lent_date IS NOT NULL AND lent_date <= :cutoffIsoDate
        ORDER BY lent_date ASC
        """,
    )
    suspend fun getLoansOlderThan(cutoffIsoDate: String): List<GameEntity>

    @Query("SELECT COUNT(*) FROM games WHERE status IN ('OWNED', 'LENT_OUT')")
    fun observeOwnedCount(): Flow<Int>

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(game: GameEntity): Long

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertAll(games: List<GameEntity>): List<Long>

    @Update
    suspend fun update(game: GameEntity)

    @Delete
    suspend fun delete(game: GameEntity)

    @Query("DELETE FROM games WHERE id IN (:ids)")
    suspend fun deleteByIds(ids: List<Long>)

    @Query("UPDATE games SET status = :status, updated_at = :now WHERE id IN (:ids)")
    suspend fun setStatus(ids: List<Long>, status: GameStatus, now: Long)

    @Query(
        """
        UPDATE games
        SET in_possession = 0, lent_to = :person, lent_date = :isoDate,
            status = 'LENT_OUT', updated_at = :now
        WHERE id = :gameId
        """,
    )
    suspend fun markLent(gameId: Long, person: String, isoDate: String, now: Long)

    @Query(
        """
        UPDATE games
        SET in_possession = 1, lent_to = NULL, lent_date = NULL,
            status = 'OWNED', updated_at = :now
        WHERE id = :gameId
        """,
    )
    suspend fun markReturned(gameId: Long, now: Long)

    @Query("SELECT COUNT(*) FROM sessions WHERE game_id = :gameId AND is_draft = 0")
    suspend fun sessionCountFor(gameId: Long): Int

    @Query("SELECT COUNT(*) FROM games")
    suspend fun count(): Int

    @Query("DELETE FROM games")
    suspend fun deleteAll()

    /**
     * Replaces the tag set for a game wholesale. Doing it in one transaction keeps the
     * game from ever being observed mid-swap with half its tags missing.
     */
    @Transaction
    suspend fun replaceTags(gameId: Long, tagIds: List<Long>) {
        clearTags(gameId)
        if (tagIds.isNotEmpty()) {
            insertTagLinks(tagIds.map { GameTagCrossRef(gameId = gameId, tagId = it) })
        }
    }

    @Query("DELETE FROM game_tags WHERE game_id = :gameId")
    suspend fun clearTags(gameId: Long)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertTagLinks(links: List<GameTagCrossRef>)
}

package com.boardgamenation.tracker.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import com.boardgamenation.tracker.data.db.entity.SessionEntity
import com.boardgamenation.tracker.data.db.entity.SessionExpansionEntity
import com.boardgamenation.tracker.data.db.entity.SessionPlayerEntity
import com.boardgamenation.tracker.data.db.projection.SessionListItem
import com.boardgamenation.tracker.data.db.projection.SessionParticipant
import kotlinx.coroutines.flow.Flow

@Dao
interface SessionDao {

    /**
     * The session list. Nullable bind parameters stand in for "no filter", which keeps
     * this a single prepared statement instead of a hand-assembled one.
     *
     * Drafts are excluded everywhere: an unfinished timer session is not a play yet.
     */
    @Query(
        """
        SELECT
            s.id, s.game_id, g.title AS game_title, g.thumbnail_path,
            s.played_on, s.duration_minutes, s.player_count, s.location,
            s.is_cooperative, (s.coop_outcome = 'WIN') AS coop_won, s.mode,
            s.is_incomplete, s.is_teaching_game, s.end_reason,
            (
                SELECT sp.team FROM session_players sp
                WHERE sp.session_id = s.id AND sp.is_winner = 1
                  AND sp.team IS NOT NULL AND trim(sp.team) <> ''
                LIMIT 1
            ) AS winning_team,
            (
                SELECT GROUP_CONCAT(p.name, ', ') FROM session_players sp
                JOIN players p ON p.id = sp.player_id
                WHERE sp.session_id = s.id AND sp.is_winner = 1
            ) AS winner_names,
            (
                SELECT p.name FROM session_players sp
                JOIN players p ON p.id = sp.player_id
                WHERE sp.session_id = s.id AND sp.turn_order = 1
            ) AS first_player_name
        FROM sessions s
        JOIN games g ON g.id = s.game_id
        WHERE s.is_draft = 0
          AND (:gameId IS NULL OR s.game_id = :gameId)
          AND (:playerId IS NULL OR EXISTS (
                SELECT 1 FROM session_players sp
                WHERE sp.session_id = s.id AND sp.player_id = :playerId))
          AND (:fromDate IS NULL OR s.played_on >= :fromDate)
          AND (:toDate IS NULL OR s.played_on <= :toDate)
        ORDER BY s.played_on DESC, s.id DESC
        """
    )
    fun observeSessions(gameId: Long?, playerId: Long?, fromDate: String?, toDate: String?): Flow<List<SessionListItem>>

    @Query(
        """
        SELECT
            s.id, s.game_id, g.title AS game_title, g.thumbnail_path,
            s.played_on, s.duration_minutes, s.player_count, s.location,
            s.is_cooperative, (s.coop_outcome = 'WIN') AS coop_won, s.mode,
            s.is_incomplete, s.is_teaching_game, s.end_reason,
            (
                SELECT sp.team FROM session_players sp
                WHERE sp.session_id = s.id AND sp.is_winner = 1
                  AND sp.team IS NOT NULL AND trim(sp.team) <> ''
                LIMIT 1
            ) AS winning_team,
            (
                SELECT GROUP_CONCAT(p.name, ', ') FROM session_players sp
                JOIN players p ON p.id = sp.player_id
                WHERE sp.session_id = s.id AND sp.is_winner = 1
            ) AS winner_names,
            (
                SELECT p.name FROM session_players sp
                JOIN players p ON p.id = sp.player_id
                WHERE sp.session_id = s.id AND sp.turn_order = 1
            ) AS first_player_name
        FROM sessions s
        JOIN games g ON g.id = s.game_id
        WHERE s.is_draft = 0
        ORDER BY s.played_on DESC, s.id DESC
        LIMIT :limit
        """
    )
    fun observeRecent(limit: Int): Flow<List<SessionListItem>>

    @Query("SELECT * FROM sessions WHERE id = :id")
    fun observeSession(id: Long): Flow<SessionEntity?>

    @Query("SELECT * FROM sessions WHERE id = :id")
    suspend fun getSession(id: Long): SessionEntity?

    @Query("SELECT * FROM sessions WHERE is_draft = 0 ORDER BY played_on, id")
    suspend fun getAllSessions(): List<SessionEntity>

    @Query("SELECT * FROM sessions ORDER BY played_on, id")
    suspend fun getAllSessionsIncludingDrafts(): List<SessionEntity>

    /**
     * Natural-key lookup used by CSV merge import. A game played twice on one day with
     * the same head count is genuinely ambiguous, so the importer treats it as a match
     * rather than silently duplicating.
     */
    @Query(
        """
        SELECT * FROM sessions
        WHERE game_id = :gameId AND played_on = :playedOn AND player_count = :playerCount
        LIMIT 1
        """
    )
    suspend fun findByNaturalKey(gameId: Long, playedOn: String, playerCount: Int): SessionEntity?

    /**
     * The players on one session, ranked.
     *
     * Rows are written in the order the form holds them, so `sp.id` breaks ties by that
     * order rather than by name. Unplaced rows are the case that matters: a draft handed
     * over by the timer is ordered by nothing else, and its order is the turn order the
     * user sat the table in.
     */
    @Query(
        """
        SELECT
            sp.id AS session_player_id, sp.player_id, p.name AS player_name, p.color_hex,
            sp.score, sp.placement, sp.is_winner, sp.faction, sp.turn_order, sp.seat,
            sp.team,
            sp.is_new_player, sp.turn_time_ms, sp.bank_time_remaining_ms
        FROM session_players sp
        JOIN players p ON p.id = sp.player_id
        WHERE sp.session_id = :sessionId
        ORDER BY sp.placement IS NULL, sp.placement, sp.id
        """
    )
    fun observeParticipants(sessionId: Long): Flow<List<SessionParticipant>>

    @Query(
        """
        SELECT
            sp.id AS session_player_id, sp.player_id, p.name AS player_name, p.color_hex,
            sp.score, sp.placement, sp.is_winner, sp.faction, sp.turn_order, sp.seat,
            sp.team,
            sp.is_new_player, sp.turn_time_ms, sp.bank_time_remaining_ms
        FROM session_players sp
        JOIN players p ON p.id = sp.player_id
        WHERE sp.session_id = :sessionId
        ORDER BY sp.placement IS NULL, sp.placement, sp.id
        """
    )
    suspend fun getParticipants(sessionId: Long): List<SessionParticipant>

    @Query("SELECT * FROM session_players")
    suspend fun getAllSessionPlayers(): List<SessionPlayerEntity>

    @Query("SELECT * FROM session_expansions WHERE session_id = :sessionId")
    fun observeExpansions(sessionId: Long): Flow<List<SessionExpansionEntity>>

    @Query("SELECT * FROM session_expansions")
    suspend fun getAllSessionExpansions(): List<SessionExpansionEntity>

    /** Prefills the duration field with what this game actually takes at this table. */
    @Query(
        """
        SELECT AVG(duration_minutes) FROM sessions
        WHERE game_id = :gameId AND is_draft = 0 AND is_incomplete = 0
        """
    )
    suspend fun averageDurationFor(gameId: Long): Double?

    /**
     * How often a player has played a game, ignoring one session.
     *
     * The exclusion is what makes this answer "before this play" rather than "in total":
     * the session being saved may already have rows of its own, and counting them would
     * have every player look like a returning one. Pass 0 for a session that does not
     * exist yet, which no row can match.
     */
    @Query(
        """
        SELECT COUNT(*) FROM session_players sp
        JOIN sessions s ON s.id = sp.session_id AND s.is_draft = 0
        WHERE sp.player_id = :playerId AND s.game_id = :gameId
          AND sp.session_id != :excludingSessionId
        """
    )
    suspend fun timesPlayerPlayedGame(playerId: Long, gameId: Long, excludingSessionId: Long): Int

    /**
     * Sudden-death reasons already recorded for this game, newest first, so the form can
     * offer "Military supremacy" as a chip on the second play rather than asking the
     * user to type it again.
     */
    @Query(
        """
        SELECT end_reason FROM sessions
        WHERE game_id = :gameId AND is_draft = 0 AND end_reason IS NOT NULL
        GROUP BY end_reason COLLATE NOCASE
        ORDER BY MAX(played_on) DESC
        LIMIT :limit
        """
    )
    fun observeEndReasonsFor(gameId: Long, limit: Int = 6): Flow<List<String>>

    /** Configurations this game has already been played at, newest first. */
    @Query(
        """
        SELECT mode FROM sessions
        WHERE game_id = :gameId AND is_draft = 0 AND mode IS NOT NULL AND trim(mode) <> ''
        GROUP BY mode COLLATE NOCASE
        ORDER BY MAX(played_on) DESC
        LIMIT :limit
        """
    )
    fun observeModesFor(gameId: Long, limit: Int = 6): Flow<List<String>>

    /** Sides this game has already been played with, newest first. */
    @Query(
        """
        SELECT sp.team FROM session_players sp
        JOIN sessions s ON s.id = sp.session_id
        WHERE s.game_id = :gameId AND s.is_draft = 0
          AND sp.team IS NOT NULL AND trim(sp.team) <> ''
        GROUP BY sp.team COLLATE NOCASE
        ORDER BY MAX(s.played_on) DESC
        LIMIT :limit
        """
    )
    fun observeTeamsFor(gameId: Long, limit: Int = 8): Flow<List<String>>

    // --- drafts -------------------------------------------------------------------

    @Query("SELECT * FROM sessions WHERE is_draft = 1 ORDER BY created_at DESC")
    suspend fun getDrafts(): List<SessionEntity>

    @Query("SELECT * FROM sessions WHERE is_draft = 1 ORDER BY created_at DESC LIMIT 1")
    fun observeLatestDraft(): Flow<SessionEntity?>

    @Query("DELETE FROM sessions WHERE is_draft = 1")
    suspend fun deleteDrafts()

    // --- writes -------------------------------------------------------------------

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertSession(session: SessionEntity): Long

    @Update
    suspend fun updateSession(session: SessionEntity)

    @Query("DELETE FROM sessions WHERE id = :id")
    suspend fun deleteSession(id: Long)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertParticipants(rows: List<SessionPlayerEntity>)

    @Query("DELETE FROM session_players WHERE session_id = :sessionId")
    suspend fun clearParticipants(sessionId: Long)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertExpansions(rows: List<SessionExpansionEntity>)

    @Query("DELETE FROM session_expansions WHERE session_id = :sessionId")
    suspend fun clearExpansions(sessionId: Long)

    /**
     * Writes a session and everything hanging off it as one unit, so a half-saved play
     * can never be observed.
     */
    @Transaction
    suspend fun saveComplete(session: SessionEntity, participants: List<SessionPlayerEntity>, expansionIds: List<Long>): Long {
        val id = if (session.id == 0L) {
            insertSession(session)
        } else {
            updateSession(session)
            session.id
        }
        clearParticipants(id)
        insertParticipants(participants.map { it.copy(id = 0, sessionId = id) })
        clearExpansions(id)
        insertExpansions(expansionIds.map { SessionExpansionEntity(sessionId = id, gameId = it) })
        return id
    }

    /**
     * Writes a draft and the players it currently knows about as one unit.
     *
     * Separate from [saveComplete] because the expansions belong to the session form,
     * not to the clock: the timer must not clear a choice it knows nothing about.
     */
    @Transaction
    suspend fun saveDraft(session: SessionEntity, participants: List<SessionPlayerEntity>): Long {
        val id = if (session.id == 0L) {
            insertSession(session)
        } else {
            updateSession(session)
            session.id
        }
        clearParticipants(id)
        insertParticipants(participants.map { it.copy(id = 0, sessionId = id) })
        return id
    }

    @Query("SELECT COUNT(*) FROM sessions WHERE is_draft = 0")
    suspend fun count(): Int

    @Query("SELECT COUNT(*) FROM session_players")
    suspend fun countParticipants(): Int

    @Query("SELECT COUNT(*) FROM session_expansions")
    suspend fun countExpansions(): Int

    @Query("DELETE FROM sessions")
    suspend fun deleteAll()
}

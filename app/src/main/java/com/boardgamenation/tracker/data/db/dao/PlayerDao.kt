package com.boardgamenation.tracker.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.boardgamenation.tracker.data.db.entity.PlayerEntity
import com.boardgamenation.tracker.data.db.projection.PlayerRow
import kotlinx.coroutines.flow.Flow

@Dao
interface PlayerDao {

    @Query("SELECT * FROM players WHERE archived = 0 ORDER BY is_self DESC, name COLLATE NOCASE")
    fun observeActive(): Flow<List<PlayerEntity>>

    @Query("SELECT * FROM players ORDER BY is_self DESC, name COLLATE NOCASE")
    fun observeAll(): Flow<List<PlayerEntity>>

    @Query("SELECT * FROM players")
    suspend fun getAll(): List<PlayerEntity>

    @Query("SELECT * FROM players WHERE id = :id")
    suspend fun getPlayer(id: Long): PlayerEntity?

    @Query("SELECT * FROM players WHERE id = :id")
    fun observePlayer(id: Long): Flow<PlayerEntity?>

    @Query("SELECT * FROM players WHERE name = :name COLLATE NOCASE LIMIT 1")
    suspend fun findByName(name: String): PlayerEntity?

    @Query("SELECT * FROM players WHERE is_self = 1 LIMIT 1")
    suspend fun getSelf(): PlayerEntity?

    @Query("SELECT * FROM players WHERE is_self = 1 LIMIT 1")
    fun observeSelf(): Flow<PlayerEntity?>

    /**
     * Players ordered by how recently they were at the table, so the picker puts the
     * usual suspects first instead of making the user hunt alphabetically.
     */
    @Query(
        """
        SELECT p.* FROM players p
        LEFT JOIN (
            SELECT sp.player_id, MAX(s.played_on) AS last_played
            FROM session_players sp
            JOIN sessions s ON s.id = sp.session_id AND s.is_draft = 0
            GROUP BY sp.player_id
        ) r ON r.player_id = p.id
        WHERE p.archived = 0
        ORDER BY p.is_self DESC, r.last_played IS NULL, r.last_played DESC,
                 p.name COLLATE NOCASE
        """,
    )
    fun observeByRecency(): Flow<List<PlayerEntity>>

    /** The player set from the most recent completed session of a game. */
    @Query(
        """
        SELECT p.* FROM players p
        JOIN session_players sp ON sp.player_id = p.id
        WHERE sp.session_id = (
            SELECT s.id FROM sessions s
            WHERE s.game_id = :gameId AND s.is_draft = 0
            ORDER BY s.played_on DESC, s.id DESC LIMIT 1
        )
        ORDER BY p.name COLLATE NOCASE
        """,
    )
    suspend fun lastLineupFor(gameId: Long): List<PlayerEntity>

    @Query(
        """
        SELECT p.*,
            (SELECT COUNT(*) FROM session_players sp
             JOIN sessions s ON s.id = sp.session_id AND s.is_draft = 0
             WHERE sp.player_id = p.id) AS plays,
            (SELECT COUNT(*) FROM session_players sp
             JOIN sessions s ON s.id = sp.session_id AND s.is_draft = 0
             WHERE sp.player_id = p.id AND sp.is_winner = 1) AS wins
        FROM players p
        ORDER BY p.is_self DESC, plays DESC, p.name COLLATE NOCASE
        """,
    )
    fun observeWithCounts(): Flow<List<PlayerRow>>

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(player: PlayerEntity): Long

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAll(players: List<PlayerEntity>): List<Long>

    @Update
    suspend fun update(player: PlayerEntity)

    /** Resolves a name to an id, creating the player if the name is new. */
    suspend fun upsertByName(name: String): Long {
        val trimmed = name.trim()
        findByName(trimmed)?.let { return it.id }
        val id = insert(PlayerEntity(name = trimmed))
        return if (id > 0) id else findByName(trimmed)?.id ?: 0L
    }

    /** Clears the flag everywhere before setting it, so exactly one self can exist. */
    @Query("UPDATE players SET is_self = 0")
    suspend fun clearSelfFlag()

    @Query("UPDATE players SET is_self = 1 WHERE id = :id")
    suspend fun setSelfFlag(id: Long)

    @Query("UPDATE players SET archived = :archived WHERE id = :id")
    suspend fun setArchived(id: Long, archived: Boolean)

    @Query("DELETE FROM players WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("SELECT COUNT(*) FROM session_players WHERE player_id = :id")
    suspend fun appearanceCount(id: Long): Int

    @Query("SELECT COUNT(*) FROM players")
    suspend fun count(): Int

    @Query("DELETE FROM players")
    suspend fun deleteAll()
}

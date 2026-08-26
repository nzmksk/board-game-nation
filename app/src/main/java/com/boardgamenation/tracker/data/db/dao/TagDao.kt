package com.boardgamenation.tracker.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.boardgamenation.tracker.data.db.entity.GameTagCrossRef
import com.boardgamenation.tracker.data.db.entity.TagEntity
import com.boardgamenation.tracker.domain.model.TagKind
import kotlinx.coroutines.flow.Flow

@Dao
interface TagDao {

    @Query("SELECT * FROM tags ORDER BY kind, name COLLATE NOCASE")
    fun observeAll(): Flow<List<TagEntity>>

    @Query("SELECT * FROM tags WHERE kind = :kind ORDER BY name COLLATE NOCASE")
    fun observeByKind(kind: TagKind): Flow<List<TagEntity>>

    /** Only tags that at least one game carries, so filter chips are never dead ends. */
    @Query(
        """
        SELECT t.* FROM tags t
        WHERE EXISTS (SELECT 1 FROM game_tags gt WHERE gt.tag_id = t.id)
        ORDER BY t.kind, t.name COLLATE NOCASE
        """,
    )
    fun observeInUse(): Flow<List<TagEntity>>

    @Query("SELECT * FROM tags")
    suspend fun getAll(): List<TagEntity>

    @Query("SELECT * FROM tags WHERE name = :name AND kind = :kind LIMIT 1")
    suspend fun find(name: String, kind: TagKind): TagEntity?

    @Query(
        """
        SELECT t.* FROM tags t
        JOIN game_tags gt ON gt.tag_id = t.id
        WHERE gt.game_id = :gameId
        ORDER BY t.kind, t.name COLLATE NOCASE
        """,
    )
    fun observeForGame(gameId: Long): Flow<List<TagEntity>>

    @Query(
        """
        SELECT t.* FROM tags t
        JOIN game_tags gt ON gt.tag_id = t.id
        WHERE gt.game_id = :gameId
        ORDER BY t.kind, t.name COLLATE NOCASE
        """,
    )
    suspend fun getForGame(gameId: Long): List<TagEntity>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(tag: TagEntity): Long

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAll(tags: List<TagEntity>): List<Long>

    /**
     * Resolves a tag by natural key, creating it if new. Returns the id either way, so
     * importers and the BGG mapper never have to care which happened.
     */
    suspend fun upsertByName(name: String, kind: TagKind): Long {
        val trimmed = name.trim()
        find(trimmed, kind)?.let { return it.id }
        val id = insert(TagEntity(name = trimmed, kind = kind))
        return if (id > 0) id else find(trimmed, kind)?.id ?: 0L
    }

    @Query("DELETE FROM tags WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("SELECT * FROM game_tags")
    suspend fun getAllLinks(): List<GameTagCrossRef>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertLinks(links: List<GameTagCrossRef>)

    /** Drops tags nothing points at any more, so the chip row does not silt up. */
    @Query("DELETE FROM tags WHERE NOT EXISTS (SELECT 1 FROM game_tags gt WHERE gt.tag_id = tags.id)")
    suspend fun pruneOrphans()

    @Query("SELECT COUNT(*) FROM tags")
    suspend fun count(): Int

    @Query("DELETE FROM tags")
    suspend fun deleteAll()

    @Query("DELETE FROM game_tags")
    suspend fun deleteAllLinks()

    @Query("SELECT COUNT(*) FROM game_tags")
    suspend fun countLinks(): Int
}

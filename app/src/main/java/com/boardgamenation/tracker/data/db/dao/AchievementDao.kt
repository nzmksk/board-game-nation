package com.boardgamenation.tracker.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.boardgamenation.tracker.data.db.entity.AchievementEntity
import com.boardgamenation.tracker.data.db.entity.AchievementUnlockEntity
import com.boardgamenation.tracker.data.db.projection.AchievementWithUnlock
import kotlinx.coroutines.flow.Flow

@Dao
interface AchievementDao {

    @Query(
        """
        SELECT
            a.id, a.code, a.name, a.description, a.icon, a.category,
            a.target_value, a.is_hidden, a.sort_order, a.rule_json,
            u.unlocked_at, u.progress_value
        FROM achievements a
        LEFT JOIN achievement_unlocks u ON u.achievement_id = a.id
        ORDER BY a.category, a.sort_order, a.name COLLATE NOCASE
        """,
    )
    fun observeAll(): Flow<List<AchievementWithUnlock>>

    @Query(
        """
        SELECT
            a.id, a.code, a.name, a.description, a.icon, a.category,
            a.target_value, a.is_hidden, a.sort_order, a.rule_json,
            u.unlocked_at, u.progress_value
        FROM achievements a
        JOIN achievement_unlocks u ON u.achievement_id = a.id
        ORDER BY u.unlocked_at DESC
        LIMIT :limit
        """,
    )
    fun observeRecentlyUnlocked(limit: Int): Flow<List<AchievementWithUnlock>>

    @Query("SELECT COUNT(*) FROM achievement_unlocks")
    fun observeUnlockedCount(): Flow<Int>

    @Query("SELECT COUNT(*) FROM achievements")
    fun observeTotalCount(): Flow<Int>

    @Query("SELECT * FROM achievements ORDER BY sort_order, id")
    suspend fun getAllDefinitions(): List<AchievementEntity>

    @Query("SELECT * FROM achievements WHERE code = :code LIMIT 1")
    suspend fun findByCode(code: String): AchievementEntity?

    /** Only the ones still worth evaluating: an unlock is permanent. */
    @Query(
        """
        SELECT * FROM achievements
        WHERE NOT EXISTS (
            SELECT 1 FROM achievement_unlocks u WHERE u.achievement_id = achievements.id
        )
        ORDER BY sort_order, id
        """,
    )
    suspend fun getLockedDefinitions(): List<AchievementEntity>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertDefinitions(rows: List<AchievementEntity>): List<Long>

    /**
     * Refreshes display text on upgrade without touching identity or unlocks, so a
     * reworded achievement stays the same achievement.
     */
    @Query(
        """
        UPDATE achievements
        SET name = :name, description = :description, icon = :icon, category = :category,
            target_value = :targetValue, is_hidden = :isHidden, sort_order = :sortOrder,
            rule_json = :ruleJson
        WHERE code = :code
        """,
    )
    suspend fun updateDefinitionByCode(
        code: String,
        name: String,
        description: String,
        icon: String,
        category: String,
        targetValue: Double?,
        isHidden: Boolean,
        sortOrder: Int,
        ruleJson: String,
    )

    /**
     * IGNORE on conflict is what makes evaluation idempotent: the unique index on
     * achievement_id turns a second unlock attempt into a no-op instead of a duplicate.
     */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertUnlock(unlock: AchievementUnlockEntity): Long

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertUnlocks(unlocks: List<AchievementUnlockEntity>): List<Long>

    @Query("SELECT * FROM achievement_unlocks")
    suspend fun getAllUnlocks(): List<AchievementUnlockEntity>

    @Query("SELECT * FROM achievement_unlocks WHERE achievement_id = :achievementId LIMIT 1")
    suspend fun findUnlock(achievementId: Long): AchievementUnlockEntity?

    /**
     * Editing or deleting a session can invalidate an unlock that session earned.
     * Re-evaluation reinstates anything still deserved.
     */
    @Query("DELETE FROM achievement_unlocks WHERE achievement_id = :achievementId")
    suspend fun deleteUnlock(achievementId: Long)

    @Query("UPDATE achievement_unlocks SET progress_value = :progress WHERE achievement_id = :id")
    suspend fun updateProgress(id: Long, progress: Double)

    @Query("SELECT COUNT(*) FROM achievements")
    suspend fun countDefinitions(): Int

    @Query("SELECT COUNT(*) FROM achievement_unlocks")
    suspend fun countUnlocks(): Int

    @Query("DELETE FROM achievement_unlocks")
    suspend fun deleteAllUnlocks()
}

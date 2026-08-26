package com.boardgamenation.tracker.data.db

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import com.boardgamenation.tracker.data.db.dao.AchievementDao
import com.boardgamenation.tracker.data.db.dao.AchievementStatsDao
import com.boardgamenation.tracker.data.db.dao.BggCacheDao
import com.boardgamenation.tracker.data.db.dao.GameDao
import com.boardgamenation.tracker.data.db.dao.PlayerDao
import com.boardgamenation.tracker.data.db.dao.RubricDao
import com.boardgamenation.tracker.data.db.dao.SessionDao
import com.boardgamenation.tracker.data.db.dao.StatsDao
import com.boardgamenation.tracker.data.db.dao.TagDao
import com.boardgamenation.tracker.data.db.dao.TimerDao
import com.boardgamenation.tracker.data.db.entity.AchievementEntity
import com.boardgamenation.tracker.data.db.entity.AchievementUnlockEntity
import com.boardgamenation.tracker.data.db.entity.BggThingCacheEntity
import com.boardgamenation.tracker.data.db.entity.GameEntity
import com.boardgamenation.tracker.data.db.entity.GameRatingEntity
import com.boardgamenation.tracker.data.db.entity.GameRatingScoreEntity
import com.boardgamenation.tracker.data.db.entity.GameTagCrossRef
import com.boardgamenation.tracker.data.db.entity.PlayerEntity
import com.boardgamenation.tracker.data.db.entity.RubricCriterionEntity
import com.boardgamenation.tracker.data.db.entity.RubricEntity
import com.boardgamenation.tracker.data.db.entity.SessionEntity
import com.boardgamenation.tracker.data.db.entity.SessionExpansionEntity
import com.boardgamenation.tracker.data.db.entity.SessionPlayerEntity
import com.boardgamenation.tracker.data.db.entity.TagEntity
import com.boardgamenation.tracker.data.db.entity.TimerPresetEntity
import com.boardgamenation.tracker.data.db.entity.TimerSeatEntity
import com.boardgamenation.tracker.data.db.entity.TimerStateEntity

/**
 * The single local database. There is no server and no sync: this file is the whole
 * source of truth, which is also why the backup features exist.
 *
 * Schemas are exported to app/schemas and committed. `fallbackToDestructiveMigration`
 * is never called anywhere in this project; every version bump ships a migration in
 * [Migrations] and a test that runs it against a real old database.
 */
@Database(
    entities = [
        GameEntity::class,
        TagEntity::class,
        GameTagCrossRef::class,
        PlayerEntity::class,
        SessionEntity::class,
        SessionPlayerEntity::class,
        SessionExpansionEntity::class,
        RubricEntity::class,
        RubricCriterionEntity::class,
        GameRatingEntity::class,
        GameRatingScoreEntity::class,
        AchievementEntity::class,
        AchievementUnlockEntity::class,
        TimerPresetEntity::class,
        TimerStateEntity::class,
        TimerSeatEntity::class,
        BggThingCacheEntity::class,
    ],
    version = AppDatabase.VERSION,
    exportSchema = true,
)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {

    abstract fun gameDao(): GameDao
    abstract fun tagDao(): TagDao
    abstract fun playerDao(): PlayerDao
    abstract fun sessionDao(): SessionDao
    abstract fun statsDao(): StatsDao
    abstract fun rubricDao(): RubricDao
    abstract fun achievementDao(): AchievementDao
    abstract fun achievementStatsDao(): AchievementStatsDao
    abstract fun timerDao(): TimerDao
    abstract fun bggCacheDao(): BggCacheDao

    companion object {
        const val VERSION = 1
        const val NAME = "board_game_nation.db"
    }
}

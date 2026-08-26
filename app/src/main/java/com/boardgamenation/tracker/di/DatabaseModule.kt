package com.boardgamenation.tracker.di

import android.content.Context
import androidx.room.Room
import com.boardgamenation.tracker.data.db.AppDatabase
import com.boardgamenation.tracker.data.db.Migrations
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
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): AppDatabase =
        Room.databaseBuilder(context, AppDatabase::class.java, AppDatabase.NAME)
            // Explicit migrations only. A missing one must fail loudly in development
            // rather than quietly delete somebody's play history on upgrade.
            .addMigrations(*Migrations.ALL)
            .build()

    @Provides fun provideGameDao(db: AppDatabase): GameDao = db.gameDao()
    @Provides fun provideTagDao(db: AppDatabase): TagDao = db.tagDao()
    @Provides fun providePlayerDao(db: AppDatabase): PlayerDao = db.playerDao()
    @Provides fun provideSessionDao(db: AppDatabase): SessionDao = db.sessionDao()
    @Provides fun provideStatsDao(db: AppDatabase): StatsDao = db.statsDao()
    @Provides fun provideRubricDao(db: AppDatabase): RubricDao = db.rubricDao()
    @Provides fun provideAchievementDao(db: AppDatabase): AchievementDao = db.achievementDao()
    @Provides fun provideAchievementStatsDao(db: AppDatabase): AchievementStatsDao =
        db.achievementStatsDao()
    @Provides fun provideTimerDao(db: AppDatabase): TimerDao = db.timerDao()
    @Provides fun provideBggCacheDao(db: AppDatabase): BggCacheDao = db.bggCacheDao()
}

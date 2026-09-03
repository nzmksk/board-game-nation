package com.boardgamenation.tracker.data.repository

import androidx.room.withTransaction
import com.boardgamenation.tracker.data.db.AppDatabase
import com.boardgamenation.tracker.data.db.dao.AchievementDao
import com.boardgamenation.tracker.data.db.dao.BggCacheDao
import com.boardgamenation.tracker.data.db.dao.GameDao
import com.boardgamenation.tracker.data.db.dao.PlayerDao
import com.boardgamenation.tracker.data.db.dao.RubricDao
import com.boardgamenation.tracker.data.db.dao.SessionDao
import com.boardgamenation.tracker.data.db.dao.TagDao
import com.boardgamenation.tracker.data.db.dao.TimerDao
import com.boardgamenation.tracker.data.db.projection.TableCountSummary
import com.boardgamenation.tracker.di.IoDispatcher
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext

/**
 * Destructive whole-database operations, in exactly one place.
 *
 * Both "erase everything" and a replace-mode import need to clear the same set of
 * tables. Having two implementations of that would eventually mean one of them forgets
 * a table and leaves orphans behind, so they share this.
 */
@Singleton
class DataMaintenanceRepository @Inject constructor(
    private val database: AppDatabase,
    private val gameDao: GameDao,
    private val tagDao: TagDao,
    private val playerDao: PlayerDao,
    private val sessionDao: SessionDao,
    private val rubricDao: RubricDao,
    private val achievementDao: AchievementDao,
    private val timerDao: TimerDao,
    private val bggCacheDao: BggCacheDao,
    @param:IoDispatcher private val io: CoroutineDispatcher
) {

    /**
     * Clears every table that holds user data.
     *
     * Achievement *definitions* survive, because they come from a bundled asset rather
     * than from the user; their unlocks do not. The BGG response cache is cleared too,
     * so a fresh start does not silently reuse metadata for games that no longer exist.
     */
    suspend fun wipeUserData() = withContext(io) {
        database.withTransaction {
            timerDao.clearAll()
            sessionDao.deleteAll()
            rubricDao.deleteAllRatings()
            rubricDao.deleteAllRubrics()
            gameDao.deleteAll()
            tagDao.deleteAllLinks()
            tagDao.deleteAll()
            playerDao.deleteAll()
            achievementDao.deleteAllUnlocks()
            bggCacheDao.clear()
        }
    }

    /**
     * Row counts per table. Used by the export/import round-trip check, where "identical
     * row counts across all tables" is the thing being asserted.
     */
    suspend fun tableCounts(): TableCountSummary = withContext(io) {
        TableCountSummary(
            games = gameDao.count(),
            tags = tagDao.count(),
            gameTags = tagDao.countLinks(),
            players = playerDao.count(),
            sessions = sessionDao.count(),
            sessionPlayers = sessionDao.countParticipants(),
            sessionExpansions = sessionDao.countExpansions(),
            rubrics = rubricDao.countRubrics(),
            rubricCriteria = rubricDao.countCriteria(),
            gameRatings = rubricDao.countRatings(),
            gameRatingScores = rubricDao.countScores(),
            achievementUnlocks = achievementDao.countUnlocks()
        )
    }
}

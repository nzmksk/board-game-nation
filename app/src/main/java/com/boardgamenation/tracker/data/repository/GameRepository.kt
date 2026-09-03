package com.boardgamenation.tracker.data.repository

import com.boardgamenation.tracker.core.time.AppClock
import com.boardgamenation.tracker.core.time.DateUtils
import com.boardgamenation.tracker.data.db.dao.GameDao
import com.boardgamenation.tracker.data.db.dao.SessionDao
import com.boardgamenation.tracker.data.db.dao.TagDao
import com.boardgamenation.tracker.data.db.entity.GameEntity
import com.boardgamenation.tracker.data.db.entity.TagEntity
import com.boardgamenation.tracker.data.db.projection.FactionRecord
import com.boardgamenation.tracker.data.db.projection.GameAggregates
import com.boardgamenation.tracker.data.db.projection.GameListItem
import com.boardgamenation.tracker.data.db.query.GameQueryBuilder
import com.boardgamenation.tracker.domain.model.CollectionFilter
import com.boardgamenation.tracker.domain.model.GameStatus
import com.boardgamenation.tracker.domain.model.TagKind
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf

/** What happens when the user deletes a game that has plays recorded against it. */
sealed interface DeleteGameOutcome {
    /** Deleted outright: nothing referenced it. */
    data object Deleted : DeleteGameOutcome

    /**
     * Blocked pending confirmation, because deleting would cascade [sessionCount] plays
     * out of existence. The UI turns this into an explicit prompt rather than guessing.
     */
    data class NeedsConfirmation(val sessionCount: Int, val expansionCount: Int) : DeleteGameOutcome
}

@Singleton
@OptIn(ExperimentalCoroutinesApi::class)
class GameRepository @Inject constructor(
    private val gameDao: GameDao,
    private val tagDao: TagDao,
    private val sessionDao: SessionDao,
    private val clock: AppClock
) {

    fun observeCollection(filter: Flow<CollectionFilter>): Flow<List<GameListItem>> =
        filter.flatMapLatest { gameDao.observeCollection(GameQueryBuilder.build(it)) }

    fun observeCollection(filter: CollectionFilter): Flow<List<GameListItem>> = gameDao.observeCollection(GameQueryBuilder.build(filter))

    fun observeGame(id: Long): Flow<GameEntity?> = gameDao.observeGame(id)

    fun observeAggregates(id: Long): Flow<GameAggregates> = gameDao.observeAggregates(id)

    /** Win rate per faction for this game, across everybody who has played it. */
    fun observeFactionRecords(gameId: Long): Flow<List<FactionRecord>> = gameDao.observeFactionRecords(gameId)

    fun observeTags(gameId: Long): Flow<List<TagEntity>> = tagDao.observeForGame(gameId)

    fun observeAllTags(): Flow<List<TagEntity>> = tagDao.observeAll()

    fun observeTagsInUse(): Flow<List<TagEntity>> = tagDao.observeInUse()

    fun observeExpansions(baseGameId: Long): Flow<List<GameEntity>> = gameDao.observeExpansionsOf(baseGameId)

    fun observeBaseGames(): Flow<List<GameEntity>> = gameDao.observeBaseGames()

    fun observeLentOut(): Flow<List<GameEntity>> = gameDao.observeLentOut()

    /** Expansions of the game being logged, so the session form can offer them. */
    fun observeExpansionsForSession(gameId: Long?): Flow<List<GameEntity>> =
        if (gameId == null) flowOf(emptyList()) else gameDao.observeExpansionsOf(gameId)

    suspend fun getGame(id: Long): GameEntity? = gameDao.getGame(id)

    suspend fun getGameByBggId(bggId: Long): GameEntity? = gameDao.getGameByBggId(bggId)

    suspend fun getAllGames(): List<GameEntity> = gameDao.getAllGames()

    /**
     * Creates a game. [dateAdded] defaults to today and the timestamps are set here so
     * callers never have to remember to, and so an import can override them.
     */
    suspend fun addGame(game: GameEntity, tagIds: List<Long> = emptyList()): Long {
        val now = clock.nowMillis()
        val id = gameDao.insert(
            game.copy(
                dateAdded = game.dateAdded.ifBlank { DateUtils.toIso(clock.today()) },
                createdAt = if (game.createdAt == 0L) now else game.createdAt,
                updatedAt = now
            )
        )
        if (tagIds.isNotEmpty()) gameDao.replaceTags(id, tagIds)
        return id
    }

    suspend fun updateGame(game: GameEntity, tagIds: List<Long>? = null) {
        gameDao.update(game.copy(updatedAt = clock.nowMillis()))
        tagIds?.let { gameDao.replaceTags(game.id, it) }
    }

    /** Resolves free-text tag names to ids, creating any that are new. */
    suspend fun resolveTags(names: List<String>, kind: TagKind): List<Long> = names.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
        .map { tagDao.upsertByName(it, kind) }

    /**
     * Checks before deleting rather than after. A game with plays takes its whole
     * history with it, so the caller is made to say so explicitly.
     */
    suspend fun deleteGame(id: Long, confirmed: Boolean): DeleteGameOutcome {
        val sessions = gameDao.sessionCountFor(id)
        val expansions = gameDao.getExpansionsOf(id).size
        if (!confirmed && (sessions > 0 || expansions > 0)) {
            return DeleteGameOutcome.NeedsConfirmation(sessions, expansions)
        }
        // Sessions cascade; expansions are detached rather than destroyed, since an
        // expansion can outlive the base game in a collection.
        gameDao.getGame(id)?.let { gameDao.delete(it) }
        tagDao.pruneOrphans()
        return DeleteGameOutcome.Deleted
    }

    suspend fun deleteGames(ids: List<Long>) {
        gameDao.deleteByIds(ids)
        tagDao.pruneOrphans()
    }

    suspend fun setStatus(ids: List<Long>, status: GameStatus) {
        gameDao.setStatus(ids, status, clock.nowMillis())
    }

    /** Adds tags to several games at once without disturbing the tags they already have. */
    suspend fun addTagsTo(gameIds: List<Long>, tagIds: List<Long>) {
        gameIds.forEach { gameId ->
            val existing = tagDao.getForGame(gameId).map { it.id }
            gameDao.replaceTags(gameId, (existing + tagIds).distinct())
        }
    }

    suspend fun lendGame(gameId: Long, person: String) {
        gameDao.markLent(gameId, person.trim(), DateUtils.toIso(clock.today()), clock.nowMillis())
    }

    suspend fun returnGame(gameId: Long) {
        gameDao.markReturned(gameId, clock.nowMillis())
    }

    /** Games out longer than [thresholdDays], for the lending reminder. */
    suspend fun overdueLoans(thresholdDays: Int): List<GameEntity> {
        val cutoff = DateUtils.toIso(clock.today().minusDays(thresholdDays.toLong()))
        return gameDao.getLoansOlderThan(cutoff)
    }

    /** Days a game has been out, for the badge on the collection row. */
    fun daysOnLoan(lentDate: String?): Long? = DateUtils.parseIsoOrNull(lentDate)?.let { DateUtils.daysBetween(it, clock.today()) }
}

package com.boardgamenation.tracker.data.repository

import com.boardgamenation.tracker.data.db.dao.PlayerDao
import com.boardgamenation.tracker.data.db.entity.PlayerEntity
import com.boardgamenation.tracker.data.db.projection.PlayerRow
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow

/** Deleting a player who appears in sessions would rewrite history, so it is refused. */
sealed interface DeletePlayerOutcome {
    data object Deleted : DeletePlayerOutcome
    data class HasHistory(val appearances: Int) : DeletePlayerOutcome
}

@Singleton
class PlayerRepository @Inject constructor(private val playerDao: PlayerDao) {

    fun observeActive(): Flow<List<PlayerEntity>> = playerDao.observeActive()

    fun observeAll(): Flow<List<PlayerEntity>> = playerDao.observeAll()

    /** Recently-played-with first: the picker should not make the user hunt. */
    fun observeByRecency(): Flow<List<PlayerEntity>> = playerDao.observeByRecency()

    fun observeWithCounts(): Flow<List<PlayerRow>> = playerDao.observeWithCounts()

    fun observeSelf(): Flow<PlayerEntity?> = playerDao.observeSelf()

    fun observePlayer(id: Long): Flow<PlayerEntity?> = playerDao.observePlayer(id)

    suspend fun getSelf(): PlayerEntity? = playerDao.getSelf()

    suspend fun getPlayer(id: Long): PlayerEntity? = playerDao.getPlayer(id)

    suspend fun getAll(): List<PlayerEntity> = playerDao.getAll()

    suspend fun lastLineupFor(gameId: Long): List<PlayerEntity> = playerDao.lastLineupFor(gameId)

    suspend fun add(name: String, colorHex: String? = null, notes: String? = null): Long =
        playerDao.insert(PlayerEntity(name = name.trim(), colorHex = colorHex, notes = notes))

    /** Used by the inline "add new player" affordance and by every importer. */
    suspend fun findOrCreate(name: String): Long = playerDao.upsertByName(name)

    suspend fun update(player: PlayerEntity) = playerDao.update(player)

    suspend fun setArchived(id: Long, archived: Boolean) = playerDao.setArchived(id, archived)

    /** Moves the "this is me" flag, keeping the invariant that exactly one row holds it. */
    suspend fun setSelf(id: Long) {
        playerDao.clearSelfFlag()
        playerDao.setSelfFlag(id)
    }

    /**
     * Seeds the device owner during onboarding. Idempotent, so re-running it on a
     * relaunch cannot create a second "me".
     */
    suspend fun ensureSelf(name: String): Long {
        playerDao.getSelf()?.let { existing ->
            if (existing.name != name.trim()) {
                playerDao.update(existing.copy(name = name.trim()))
            }
            return existing.id
        }
        val id = playerDao.upsertByName(name)
        setSelf(id)
        return id
    }

    suspend fun delete(id: Long): DeletePlayerOutcome {
        val appearances = playerDao.appearanceCount(id)
        if (appearances > 0) return DeletePlayerOutcome.HasHistory(appearances)
        playerDao.delete(id)
        return DeletePlayerOutcome.Deleted
    }
}

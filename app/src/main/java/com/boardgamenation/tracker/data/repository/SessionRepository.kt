package com.boardgamenation.tracker.data.repository

import com.boardgamenation.tracker.core.time.AppClock
import com.boardgamenation.tracker.core.time.DateUtils
import com.boardgamenation.tracker.data.db.dao.GameDao
import com.boardgamenation.tracker.data.db.dao.PlayerDao
import com.boardgamenation.tracker.data.db.dao.SessionDao
import com.boardgamenation.tracker.data.db.entity.PlayerEntity
import com.boardgamenation.tracker.data.db.entity.SessionEntity
import com.boardgamenation.tracker.data.db.entity.SessionPlayerEntity
import com.boardgamenation.tracker.data.db.projection.SessionListItem
import com.boardgamenation.tracker.data.db.projection.SessionParticipant
import com.boardgamenation.tracker.domain.model.CoopOutcome
import com.boardgamenation.tracker.domain.model.ParticipantForm
import com.boardgamenation.tracker.domain.model.PlacementCalculator
import com.boardgamenation.tracker.domain.model.ScoringMode
import com.boardgamenation.tracker.domain.model.SessionForm
import com.boardgamenation.tracker.domain.model.TurnOrder
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

/** Filters for the session list. Nulls mean "no filter" all the way down to the SQL. */
data class SessionFilter(
    val gameId: Long? = null,
    val playerId: Long? = null,
    val fromDate: String? = null,
    val toDate: String? = null,
)

@Singleton
class SessionRepository @Inject constructor(
    private val sessionDao: SessionDao,
    private val gameDao: GameDao,
    private val playerDao: PlayerDao,
    private val clock: AppClock,
) {

    fun observeSessions(filter: SessionFilter): Flow<List<SessionListItem>> =
        sessionDao.observeSessions(
            gameId = filter.gameId,
            playerId = filter.playerId,
            fromDate = filter.fromDate,
            toDate = filter.toDate,
        )

    fun observeRecent(limit: Int = 5): Flow<List<SessionListItem>> = sessionDao.observeRecent(limit)

    fun observeSession(id: Long): Flow<SessionEntity?> = sessionDao.observeSession(id)

    fun observeParticipants(sessionId: Long): Flow<List<SessionParticipant>> =
        sessionDao.observeParticipants(sessionId)

    fun observeLatestDraft(): Flow<SessionEntity?> = sessionDao.observeLatestDraft()

    /** Sudden-death reasons this game has already been given, newest first. */
    fun observeEndReasonsFor(gameId: Long): Flow<List<String>> =
        sessionDao.observeEndReasonsFor(gameId)

    /** Configurations this game has already been played at, newest first. */
    fun observeModesFor(gameId: Long): Flow<List<String>> = sessionDao.observeModesFor(gameId)

    suspend fun getDrafts(): List<SessionEntity> = sessionDao.getDrafts()

    suspend fun getSession(id: Long): SessionEntity? = sessionDao.getSession(id)

    /**
     * Builds the form for a brand-new play, pre-filled from history: the lineup from the
     * last session of this game and the duration it usually actually takes. Quick log
     * exists to be finished in under twenty seconds, and this is most of how.
     */
    suspend fun newSessionForm(gameId: Long): SessionForm {
        val game = gameDao.getGame(gameId)
        val lineup = playerDao.lastLineupFor(gameId).ifEmpty { listOfNotNull(playerDao.getSelf()) }
        val averageMinutes = sessionDao.averageDurationFor(gameId)?.toInt()
        val fallbackMinutes = game?.let { g ->
            listOfNotNull(g.minPlaytimeMinutes, g.maxPlaytimeMinutes)
                .takeIf { it.isNotEmpty() }?.average()?.toInt()
        }
        return SessionForm(
            gameId = gameId,
            gameTitle = game?.title.orEmpty(),
            playedOn = clock.today(),
            durationMinutes = averageMinutes ?: fallbackMinutes ?: 60,
            scoringMode = game?.scoringMode ?: ScoringMode.RANKED_SCORES,
            highScoreWins = game?.highScoreWins ?: true,
            participants = lineup.map { it.toParticipant() },
        )
    }

    /** Loads an existing session back into an editable form. */
    suspend fun loadForm(sessionId: Long): SessionForm? {
        val session = sessionDao.getSession(sessionId) ?: return null
        val game = gameDao.getGame(session.gameId)
        val participants = sessionDao.getParticipants(sessionId).map { it.toParticipantForm() }
        val expansions = sessionDao.getAllSessionExpansions()
            .filter { it.sessionId == sessionId }
            .map { it.gameId }
        return SessionForm(
            id = session.id,
            gameId = session.gameId,
            gameTitle = game?.title.orEmpty(),
            playedOn = DateUtils.parseIsoOrNull(session.playedOn) ?: clock.today(),
            durationMinutes = session.durationMinutes,
            location = session.location,
            scoringMode = when {
                session.isCooperative -> ScoringMode.COOPERATIVE
                else -> game?.scoringMode ?: ScoringMode.RANKED_SCORES
            },
            highScoreWins = game?.highScoreWins ?: true,
            coopOutcome = session.coopOutcome,
            mode = session.mode,
            endCondition = session.endCondition,
            endReason = session.endReason,
            isIncomplete = session.isIncomplete,
            isTeachingGame = session.isTeachingGame,
            notes = session.notes,
            photoUri = session.photoUri,
            participants = participants,
            expansionIds = expansions,
            startedAt = session.startedAt,
            endedAt = session.endedAt,
            pausedMs = session.pausedMs,
        )
    }

    /**
     * Normalises then persists. Placements are always derived here rather than trusted
     * from the UI, so a session saved from the quick sheet, the full form, or an import
     * all end up ranked by the same rules.
     */
    suspend fun save(form: SessionForm): Long {
        val normalised = normalise(form)
        val now = clock.nowMillis()
        val existing = if (form.id != 0L) sessionDao.getSession(form.id) else null

        val entity = SessionEntity(
            id = form.id,
            gameId = form.gameId,
            playedOn = DateUtils.toIso(form.playedOn),
            startedAt = form.startedAt,
            endedAt = form.endedAt,
            durationMinutes = form.durationMinutes,
            playerCount = normalised.size,
            location = form.location?.takeIf { it.isNotBlank() },
            isCooperative = form.isCooperative,
            coopOutcome = if (form.isCooperative) form.coopOutcome ?: CoopOutcome.NA else null,
            mode = form.mode?.takeIf { it.isNotBlank() },
            endCondition = form.endCondition,
            endReason = form.endReason?.takeIf { it.isNotBlank() && form.isSuddenDeath },
            isIncomplete = form.isIncomplete,
            isTeachingGame = form.isTeachingGame,
            isDraft = false,
            pausedMs = form.pausedMs,
            photoUri = form.photoUri,
            notes = form.notes?.takeIf { it.isNotBlank() },
            createdAt = existing?.createdAt ?: now,
            updatedAt = now,
        )

        val rows = normalised.map { participant ->
            SessionPlayerEntity(
                sessionId = form.id,
                playerId = participant.playerId,
                score = participant.score,
                placement = participant.placement,
                isWinner = participant.isWinner,
                faction = participant.faction?.takeIf { it.isNotBlank() },
                turnOrder = participant.turnOrder,
                isNewPlayer = participant.isNewPlayer,
                turnTimeMs = participant.turnTimeMs,
                bankTimeRemainingMs = participant.bankTimeRemainingMs,
            )
        }

        val id = sessionDao.saveComplete(entity, rows, form.expansionIds)

        // The scoring mode the user actually used is the one worth remembering.
        gameDao.getGame(form.gameId)?.let { game ->
            if (game.scoringMode != form.scoringMode || game.highScoreWins != form.highScoreWins) {
                gameDao.update(
                    game.copy(
                        scoringMode = form.scoringMode,
                        highScoreWins = form.highScoreWins,
                        updatedAt = now,
                    ),
                )
            }
        }
        return id
    }

    /** Applies the scoring mode's ranking rules and flags first-timers. */
    private suspend fun normalise(form: SessionForm): List<ParticipantForm> {
        val ranked = when {
            // The caller already knows who won and there is nothing to infer. Quick log
            // works this way; it must not be expressed by changing the scoring mode,
            // because the mode is written back onto the game further down.
            !form.derivePlacements -> form.participants

            // A sudden-death play ended the instant a condition was met, so there are no
            // final scores to rank by -- 7 Wonders Duel's military and scientific
            // supremacy both stop the game before anyone counts a victory point. The
            // order the user put the players in is the result. Any scores they did enter
            // are kept: a partial score is still worth remembering, it is just not what
            // decides the winner.
            form.isSuddenDeath && !form.isCooperative ->
                PlacementCalculator.fromOrder(form.participants)

            // Kept exhaustive over the enum on purpose: a new ScoringMode should fail
            // to compile here rather than quietly fall through to a default.
            else -> when (form.scoringMode) {
                ScoringMode.RANKED_SCORES ->
                    PlacementCalculator.derive(form.participants, form.highScoreWins)
                ScoringMode.MANUAL_PLACEMENT -> PlacementCalculator.fromOrder(form.participants)
                ScoringMode.COOPERATIVE ->
                    PlacementCalculator.applyCoop(form.participants, form.coopOutcome)
                ScoringMode.NONE -> form.participants.map { it.copy(placement = null) }
            }
        }
        val flagged = ranked.map { participant ->
            if (participant.isNewPlayer) {
                participant
            } else {
                // A player's first appearance with a game is worth recording even when
                // the person entering the session did not think to tick the box.
                val priorPlays = sessionDao.timesPlayerPlayedGame(participant.playerId, form.gameId)
                val priorExcludingThis = if (form.id != 0L) priorPlays - 1 else priorPlays
                participant.copy(isNewPlayer = priorExcludingThis <= 0)
            }
        }

        // Renumbered here for the same reason placements are derived here: the quick
        // sheet, the full form and an import all reach this line, and exactly one of
        // them may leave a play with two first players.
        return TurnOrder.normalise(flagged)
    }

    /**
     * Creates the draft the timer fills in. It exists from the moment the clock starts,
     * so a process death mid-game leaves something to recover rather than nothing.
     */
    suspend fun createDraft(gameId: Long, playerCount: Int): Long {
        val now = clock.nowMillis()
        return sessionDao.insertSession(
            SessionEntity(
                gameId = gameId,
                playedOn = DateUtils.toIso(clock.today()),
                startedAt = now,
                durationMinutes = 0,
                playerCount = playerCount,
                isDraft = true,
                createdAt = now,
                updatedAt = now,
            ),
        )
    }

    suspend fun updateDraft(session: SessionEntity) {
        sessionDao.updateSession(session.copy(updatedAt = clock.nowMillis()))
    }

    suspend fun discardDraft(id: Long) = sessionDao.deleteSession(id)

    suspend fun discardAllDrafts() = sessionDao.deleteDrafts()

    suspend fun delete(id: Long) = sessionDao.deleteSession(id)

    suspend fun averageDurationFor(gameId: Long): Int? =
        sessionDao.averageDurationFor(gameId)?.toInt()
}

private fun PlayerEntity.toParticipant() = ParticipantForm(
    playerId = id,
    playerName = name,
    colorHex = colorHex,
)

private fun SessionParticipant.toParticipantForm() = ParticipantForm(
    playerId = playerId,
    playerName = playerName,
    colorHex = colorHex,
    score = score,
    placement = placement,
    isWinner = isWinner,
    faction = faction,
    turnOrder = turnOrder,
    isNewPlayer = isNewPlayer,
    turnTimeMs = turnTimeMs,
    bankTimeRemainingMs = bankTimeRemainingMs,
)

package com.boardgamenation.tracker.domain.model

import java.time.LocalDate

/** One player's line on the session form. */
data class ParticipantForm(
    val playerId: Long,
    val playerName: String,
    val colorHex: String? = null,
    val score: Double? = null,
    val placement: Int? = null,
    val isWinner: Boolean = false,
    val faction: String? = null,

    /** Seat in the turn order; 1 went first, null means nobody recorded it. */
    val turnOrder: Int? = null,

    /** The chair round the table, numbered from 1. Null means nobody recorded it. */
    val seat: Int? = null,

    /** The side this player was on, when the game is played in teams. */
    val team: String? = null,

    val isNewPlayer: Boolean = false,
    val turnTimeMs: Long? = null,
    val bankTimeRemainingMs: Long? = null
)

/**
 * A session being entered or edited. Kept separate from the Room entity because the form
 * carries the player rows with it and works in [LocalDate] rather than ISO text.
 */
data class SessionForm(
    val id: Long = 0,
    val gameId: Long = 0,
    val gameTitle: String = "",
    val playedOn: LocalDate,
    val durationMinutes: Int = 0,
    val location: String? = null,
    val scoringMode: ScoringMode = ScoringMode.RANKED_SCORES,
    val highScoreWins: Boolean = true,
    val coopOutcome: CoopOutcome? = null,

    /** The configuration played: expansion set, modules, level, scenario. Free text. */
    val mode: String? = null,

    /**
     * The side that won, for a team game. Not stored as a column of its own: the
     * winners are marked on the participants, so the winning side is whichever team
     * those rows belong to and cannot drift away from them.
     */
    val winningTeam: String? = null,

    /** Null means the play ran to final scoring, which is the ordinary case. */
    val endCondition: SessionEndCondition? = null,

    /** Free text naming what triggered a sudden-death ending. */
    val endReason: String? = null,

    val isIncomplete: Boolean = false,
    val isTeachingGame: Boolean = false,
    val notes: String? = null,
    val photoUri: String? = null,
    val participants: List<ParticipantForm> = emptyList(),
    val expansionIds: List<Long> = emptyList(),
    val startedAt: Long? = null,
    val endedAt: Long? = null,
    val pausedMs: Long = 0,

    /**
     * False when the caller has already decided who won and no ranking should be
     * inferred -- quick log works that way, asking for winners directly instead of
     * scores.
     *
     * Deliberately transient and never persisted. Quick log used to express this by
     * forcing [scoringMode] to NONE, but the mode is written back onto the game after a
     * save, so one quick log silently reset the game's remembered scoring.
     */
    val derivePlacements: Boolean = true
) {
    val isCooperative: Boolean get() = scoringMode == ScoringMode.COOPERATIVE

    /** Sides win together, so nobody is marked a winner individually. */
    val isTeamBased: Boolean get() = scoringMode.recordsSides

    /** The sides named on the form so far, in the order they were entered. */
    val teams: List<String>
        get() = participants.mapNotNull { it.team?.trim()?.takeIf(String::isNotEmpty) }
            .distinctBy { it.lowercase() }

    /** A play that ended the moment a condition was met, before any final scoring. */
    val isSuddenDeath: Boolean get() = endCondition == SessionEndCondition.SUDDEN_DEATH

    /** Who took the first turn, when anyone said. */
    val firstPlayer: ParticipantForm? get() = participants.firstOrNull { it.turnOrder == 1 }

    /** The players who have been given a chair, read round the table from seat 1. */
    val seating: List<ParticipantForm>
        get() = participants.filter { it.seat != null }.sortedBy { it.seat }

    /** Who sat either side of whom, once the whole table has been seated. */
    val neighbours: Map<Long, Neighbours> get() = Seating.neighbours(participants)

    /** The form is savable once it names a game and has at least one player. */
    val isValid: Boolean get() = gameId != 0L && participants.isNotEmpty()
}

/**
 * Turns scores into placements.
 *
 * Uses standard competition ranking, so a tie for first produces 1, 1, 3 rather than
 * 1, 1, 2. Tied players are all winners, which is the behaviour the data model was
 * built for: `is_winner` is explicit precisely so more than one row can carry it.
 */
object PlacementCalculator {

    fun derive(participants: List<ParticipantForm>, highScoreWins: Boolean): List<ParticipantForm> {
        val (scored, unscored) = participants.partition { it.score != null }
        if (scored.isEmpty()) {
            return participants.map { it.copy(placement = null, isWinner = false) }
        }

        val ordered = scored.sortedWith(
            if (highScoreWins) {
                compareByDescending { it.score!! }
            } else {
                compareBy { it.score!! }
            }
        )

        val placed = mutableListOf<ParticipantForm>()
        var currentPlacement = 1
        var previousScore: Double? = null
        ordered.forEachIndexed { index, participant ->
            if (previousScore != null && participant.score != previousScore) {
                // Skip the placements consumed by the tie above, so 1,1 is followed by 3.
                currentPlacement = index + 1
            }
            previousScore = participant.score
            placed += participant.copy(
                placement = currentPlacement,
                isWinner = currentPlacement == 1
            )
        }

        // Players with no score entered cannot be ranked, and are certainly not winners.
        placed += unscored.map { it.copy(placement = null, isWinner = false) }

        // Restore the caller's original ordering so the form does not jump around while
        // the user is still typing.
        val byId = placed.associateBy { it.playerId }
        return participants.mapNotNull { byId[it.playerId] }
    }

    /**
     * Applies manual ordering: the list order *is* the ranking, everyone above the first
     * gap is placed sequentially, and only position one wins.
     */
    fun fromOrder(participants: List<ParticipantForm>): List<ParticipantForm> = participants.mapIndexed { index, participant ->
        participant.copy(placement = index + 1, isWinner = index == 0)
    }

    /**
     * Applies a team result: everyone on the winning side wins, everyone else does not.
     *
     * Matched case-insensitively on the trimmed name, because "Liberals" typed once and
     * "liberals" typed again are the same side to everybody except a string comparison.
     * Nobody is placed: a side winning says nothing about the order within it.
     */
    fun applyTeams(participants: List<ParticipantForm>, winningTeam: String?): List<ParticipantForm> {
        val winner = winningTeam?.trim()?.lowercase()
        return participants.map { participant ->
            participant.copy(
                placement = null,
                isWinner = winner != null && participant.team?.trim()?.lowercase() == winner
            )
        }
    }

    /** In a co-op the table shares one result, so every participant gets the same flag. */
    fun applyCoop(participants: List<ParticipantForm>, outcome: CoopOutcome?): List<ParticipantForm> {
        val won = outcome == CoopOutcome.WIN
        return participants.map { it.copy(placement = null, isWinner = won) }
    }
}

/**
 * Keeps the turn order on a form coherent.
 *
 * The order is built by naming players in the order they played, and it has to survive
 * the edits that follow: dropping the second of four players must not leave 1, 3, 4, and
 * no two rows may both claim to have gone first. Recorded seats are therefore renumbered
 * into a run starting at 1 whenever the form is touched or saved.
 *
 * Players nobody named keep a null. A partial answer -- very often just "Aina started" --
 * is real information, and filling in the rest of the table would be inventing it.
 */
object TurnOrder {

    /** Closes gaps and breaks ties, leaving unrecorded players unrecorded. */
    fun normalise(participants: List<ParticipantForm>): List<ParticipantForm> = participants.renumber(
        position = { it.turnOrder },
        reseat = { participant, seat -> participant.copy(turnOrder = seat) }
    )

    /**
     * Adds a player to the end of the order, or takes one out of it.
     *
     * This is the whole interaction behind the picker: naming people in sequence builds
     * the order, and naming one again removes them while everyone behind closes up.
     */
    fun toggle(participants: List<ParticipantForm>, playerId: Long): List<ParticipantForm> {
        val alreadySeated = participants.any { it.playerId == playerId && it.turnOrder != null }
        val nextSeat = (participants.mapNotNull { it.turnOrder }.maxOrNull() ?: 0) + 1
        return normalise(
            participants.map { participant ->
                when {
                    participant.playerId != playerId -> participant
                    alreadySeated -> participant.copy(turnOrder = null)
                    else -> participant.copy(turnOrder = nextSeat)
                }
            }
        )
    }

    /** Forgets the order entirely, for when it was recorded wrongly. */
    fun clear(participants: List<ParticipantForm>): List<ParticipantForm> = participants.map { it.copy(turnOrder = null) }

    /**
     * Records only who went first, which is all the quick sheet asks for. A null player
     * id leaves the table with no first player, which is a perfectly ordinary answer.
     */
    fun firstOnly(participants: List<ParticipantForm>, playerId: Long?): List<ParticipantForm> =
        participants.map { it.copy(turnOrder = if (it.playerId == playerId) 1 else null) }
}

/** The two players a given player sat between, read round the table. */
data class Neighbours(val anticlockwise: ParticipantForm, val clockwise: ParticipantForm)

/**
 * Keeps the seating on a form coherent, and works out who ended up next to whom.
 *
 * Seats are renumbered into a run starting at 1 by the same rule the turn order uses,
 * and for the same reason: a table assembled from parts, or edited after the fact, must
 * not end up with a gap where somebody used to sit or with two people in one chair.
 *
 * Where this parts company with [TurnOrder] is what a partial answer is worth. A partial
 * turn order is real information -- "Aina started" is the common case and stands on its
 * own. A partial seating is not: the question a seating answers is who was *beside*
 * whom, and an unseated player may well have been sitting between two seated ones, so
 * every adjacency in a half-filled ring is a guess. Neighbours are therefore reported
 * only once the whole table has a chair, and withheld rather than approximated until
 * then.
 */
object Seating {

    /** Closes gaps and breaks ties, leaving unseated players unseated. */
    fun normalise(participants: List<ParticipantForm>): List<ParticipantForm> = participants.renumber(
        position = { it.seat },
        reseat = { participant, seat -> participant.copy(seat = seat) }
    )

    /**
     * Seats a player in the next chair round, or takes one out of the ring.
     *
     * The same tap-in-sequence interaction the turn order uses: going round the table
     * naming people builds the arrangement, and naming one again stands them up while
     * everybody after them shuffles along one chair.
     */
    fun toggle(participants: List<ParticipantForm>, playerId: Long): List<ParticipantForm> {
        val alreadySeated = participants.any { it.playerId == playerId && it.seat != null }
        val nextSeat = (participants.mapNotNull { it.seat }.maxOrNull() ?: 0) + 1
        return normalise(
            participants.map { participant ->
                when {
                    participant.playerId != playerId -> participant
                    alreadySeated -> participant.copy(seat = null)
                    else -> participant.copy(seat = nextSeat)
                }
            }
        )
    }

    /** Forgets the arrangement entirely, for when it was recorded wrongly. */
    fun clear(participants: List<ParticipantForm>): List<ParticipantForm> = participants.map { it.copy(seat = null) }

    /**
     * Whether the ring closes: everybody at the table has a chair, and there are at
     * least two of them.
     *
     * A solo play is excluded rather than special-cased later. One player in a ring is
     * their own neighbour on both sides, which is arithmetically true and worth nothing.
     */
    fun isComplete(participants: List<ParticipantForm>): Boolean = participants.size >= 2 &&
        participants.all { it.seat != null }

    /**
     * Who each player sat between, or nothing at all if the ring does not close.
     *
     * The wrap is the point: the player in the last chair is sitting next to the player
     * in the first, which is exactly the adjacency a turn order does not have. At a
     * table of two both sides are the same person, which is not a degenerate case but
     * the truth about a two-player game -- and why 7 Wonders starts at three.
     */
    fun neighbours(participants: List<ParticipantForm>): Map<Long, Neighbours> {
        if (!isComplete(participants)) return emptyMap()
        val ring = participants.sortedBy { it.seat }
        return ring.mapIndexed { index, participant ->
            participant.playerId to Neighbours(
                anticlockwise = ring[(index - 1 + ring.size) % ring.size],
                clockwise = ring[(index + 1) % ring.size]
            )
        }.toMap()
    }
}

/**
 * Renumbers whichever position column is passed in into a run starting at 1, closing
 * gaps and breaking ties, and leaves rows holding null holding null.
 *
 * Shared by the turn order and the seating because it is one rule, not two that happen
 * to look alike: both are positions the user builds by tapping and then edits, and both
 * break in the same way when a player in the middle is removed.
 */
private fun List<ParticipantForm>.renumber(
    position: (ParticipantForm) -> Int?,
    reseat: (ParticipantForm, Int?) -> ParticipantForm
): List<ParticipantForm> {
    val renumbered = filter { position(it) != null }
        // sortedBy is stable, so two rows that somehow claim the same position keep the
        // order the form holds them in rather than swapping about.
        .sortedBy(position)
        .mapIndexed { index, participant -> participant.playerId to index + 1 }
        .toMap()
    return map { reseat(it, renumbered[it.playerId]) }
}

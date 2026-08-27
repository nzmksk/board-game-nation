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
    val isNewPlayer: Boolean = false,
    val turnTimeMs: Long? = null,
    val bankTimeRemainingMs: Long? = null,
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
    val derivePlacements: Boolean = true,
) {
    val isCooperative: Boolean get() = scoringMode == ScoringMode.COOPERATIVE

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

    fun derive(
        participants: List<ParticipantForm>,
        highScoreWins: Boolean,
    ): List<ParticipantForm> {
        val (scored, unscored) = participants.partition { it.score != null }
        if (scored.isEmpty()) {
            return participants.map { it.copy(placement = null, isWinner = false) }
        }

        val ordered = scored.sortedWith(
            if (highScoreWins) {
                compareByDescending { it.score!! }
            } else {
                compareBy { it.score!! }
            },
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
                isWinner = currentPlacement == 1,
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
    fun fromOrder(participants: List<ParticipantForm>): List<ParticipantForm> =
        participants.mapIndexed { index, participant ->
            participant.copy(placement = index + 1, isWinner = index == 0)
        }

    /** In a co-op the table shares one result, so every participant gets the same flag. */
    fun applyCoop(
        participants: List<ParticipantForm>,
        outcome: CoopOutcome?,
    ): List<ParticipantForm> {
        val won = outcome == CoopOutcome.WIN
        return participants.map { it.copy(placement = null, isWinner = won) }
    }
}

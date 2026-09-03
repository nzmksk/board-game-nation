package com.boardgamenation.tracker.domain.share

import com.boardgamenation.tracker.domain.model.CoopOutcome
import com.boardgamenation.tracker.domain.model.ParticipantForm
import com.boardgamenation.tracker.domain.model.Seating
import com.boardgamenation.tracker.domain.model.SessionForm
import java.time.LocalDate
import java.util.Locale

/**
 * What the table's result actually was, which is what decides how the card reads.
 *
 * The scoring mode is not enough on its own: a co-op that nobody said the outcome of and
 * a party game logged with no winner both come out of the form looking the same, and
 * neither has a result to announce. Keeping that case named means the renderer never has
 * to invent one.
 */
enum class ShareResult {
    /** Players ranked against each other. The rows placed first are the winners. */
    RANKED,

    /** Sides, one of which won together. Nobody inside a side outranks anybody else. */
    TEAMS,

    COOP_WIN,
    COOP_LOSS,

    /** Played and logged, but with no outcome recorded. The card shows the lineup. */
    UNRESOLVED
}

/**
 * Where the players were, of the two ways a play can record it.
 *
 * The card has room for one line about the table, so this names which question it
 * answers rather than leaving the renderer to guess from whichever list is non-empty.
 */
enum class ShareArrangement {
    /** Who played when, read from the first turn. */
    TURN_ORDER,

    /** Who sat beside whom, read round the table and wrapping back to the start. */
    SEATING
}

/** One player's line on the card. */
data class ShareStanding(
    /** Their placement, or null on a play that was never ranked. Ties share a number. */
    val rank: Int?,
    val name: String,
    val faction: String?,
    val team: String?,
    val score: Double?,
    val isWinner: Boolean,

    /** Whether this play was their first of this game. */
    val isNewPlayer: Boolean
) {
    /** `12` rather than `12.0`, and `7.5` kept as `7.5`. */
    val scoreText: String? get() = score?.let(::formatScore)
}

/**
 * One play, arranged for a picture rather than for a screen.
 *
 * This is the whole of the decision-making behind a shared result: which players the
 * card lists and in what order, who is highlighted, and what the headline says. It is
 * pure Kotlin and holds no strings the user reads, because every one of those is a
 * resource -- the renderer resolves them. What is here instead are the facts and the
 * ordering, which is the part worth testing without a device.
 */
data class ShareCard(
    val gameTitle: String,
    val playedOn: LocalDate,
    val durationMinutes: Int,
    val result: ShareResult,
    val standings: List<ShareStanding>,

    /** The side that won, when one did. */
    val winningTeam: String?,

    /** The configuration the game was set up with, when it was recorded. */
    val mode: String?,

    /** The rule that stopped a play, when one did. */
    val endReason: String?,

    /** Names in seat order. Empty when nobody wrote the order down, which is common. */
    val turnOrder: List<String>,

    /**
     * Names read round the table from chair 1, each of them once. Empty unless every
     * player at the table had a chair. The wrap back to the first chair is the ring's
     * whole point, but it is a way of saying the list rather than a fact in it, so the
     * renderer is what closes it.
     *
     * Half a ring is left off rather than shown as far as it goes: the arrangement is
     * worth sharing because it says who sat beside whom, and an unseated player may
     * well have been sitting between two seated ones, so every adjacency in a partial
     * seating is a guess. This is the same bar [Seating.neighbours] holds itself to.
     */
    val seating: List<String>,

    val isIncomplete: Boolean,
    val isTeachingGame: Boolean
) {
    val playerCount: Int get() = standings.size

    val winners: List<String> get() = standings.filter { it.isWinner }.map { it.name }

    /** Whether any row has a score to print, which decides if the column appears at all. */
    val hasScores: Boolean get() = standings.any { it.score != null }

    /** Whether the standings are a ranking rather than a lineup. */
    val hasRanks: Boolean get() = standings.any { it.rank != null }

    /**
     * The one arrangement the card shows, or null on a play that recorded neither.
     *
     * The turn order wins where both were recorded. A table that wrote down the whole
     * order almost always sat in it, so printing both would spend a second line of the
     * card restating the first; and where the two really did differ, the order is the
     * one that decided the game.
     */
    val arrangement: ShareArrangement? get() = when {
        turnOrder.isNotEmpty() -> ShareArrangement.TURN_ORDER
        seating.isNotEmpty() -> ShareArrangement.SEATING
        else -> null
    }

    companion object {

        /**
         * Builds the card from a saved play.
         *
         * The form is the right input rather than the raw rows: it is what the session
         * screen already holds, and placements and winners on it have been through the
         * scoring rules, so the card ranks exactly the way the app does.
         */
        fun of(form: SessionForm): ShareCard {
            val result = resultOf(form)
            return ShareCard(
                gameTitle = form.gameTitle,
                playedOn = form.playedOn,
                durationMinutes = form.durationMinutes,
                result = result,
                standings = order(form.participants, result).map { it.toStanding(result) },
                winningTeam = form.winningTeam?.trim()?.takeIf(String::isNotEmpty),
                mode = form.mode?.trim()?.takeIf(String::isNotEmpty),
                endReason = form.endReason?.trim()?.takeIf(String::isNotEmpty),
                turnOrder = form.participants
                    .filter { it.turnOrder != null }
                    .sortedBy { it.turnOrder }
                    .map { it.playerName },
                seating = if (Seating.isComplete(form.participants)) {
                    form.seating.map { it.playerName }
                } else {
                    emptyList()
                },
                isIncomplete = form.isIncomplete,
                isTeachingGame = form.isTeachingGame
            )
        }

        private fun resultOf(form: SessionForm): ShareResult = when {
            form.isCooperative -> when (form.coopOutcome) {
                CoopOutcome.WIN -> ShareResult.COOP_WIN
                CoopOutcome.LOSS -> ShareResult.COOP_LOSS
                else -> ShareResult.UNRESOLVED
            }

            form.isTeamBased -> ShareResult.TEAMS

            // A play with neither a placement nor a winner on it was logged for the
            // record and nothing more. Announcing a result would be making one up.
            form.participants.any { it.placement != null || it.isWinner } -> ShareResult.RANKED

            else -> ShareResult.UNRESOLVED
        }

        /**
         * Puts the winners at the top, because that is the one thing somebody reads a
         * shared result for.
         *
         * A team game groups by side rather than sorting player by player: a side wins
         * together, so splitting one up to interleave it with the other would be
         * describing a competition that did not happen.
         */
        private fun order(participants: List<ParticipantForm>, result: ShareResult): List<ParticipantForm> = when (result) {
            ShareResult.TEAMS -> bySide(participants)

            // Placement first, then winners, and every sort here is stable so anyone the
            // two rules cannot separate keeps the order the form holds them in. The
            // second rule is not redundant: a quick log records winners without ever
            // placing anybody, which leaves every placement null.
            else -> participants.sortedWith(
                compareBy(nullsLast<Int>(), ParticipantForm::placement)
                    .thenByDescending { it.isWinner }
            )
        }

        /** Sides in the order they were entered, with the winning one moved to the top. */
        private fun bySide(participants: List<ParticipantForm>): List<ParticipantForm> {
            val sides = participants.groupBy { it.team?.trim()?.lowercase().orEmpty() }
            return sides.entries
                .sortedWith(
                    // Players nobody put on a side go last: they are the incomplete part
                    // of the record, not a third team.
                    compareByDescending<Map.Entry<String, List<ParticipantForm>>> { (_, side) ->
                        side.any { it.isWinner }
                    }.thenBy { (key, _) -> if (key.isEmpty()) 1 else 0 }
                )
                .flatMap { it.value }
        }

        private fun ParticipantForm.toStanding(result: ShareResult) = ShareStanding(
            // A side winning says nothing about the order within it, and a co-op has no
            // order at all, so neither carries a rank onto the card.
            rank = placement.takeIf { result == ShareResult.RANKED },
            name = playerName,
            faction = faction?.trim()?.takeIf(String::isNotEmpty),
            team = team?.trim()?.takeIf(String::isNotEmpty),
            score = score,
            isWinner = isWinner,
            isNewPlayer = isNewPlayer
        )
    }
}

/**
 * Trims a score down to what somebody would write on a scoresheet: no trailing zeros,
 * and no decimal point when there is nothing after it.
 *
 * `Locale.ROOT` for the same reason the CSV writer uses it -- the separator has to be a
 * `.` whatever the device is set to, or a half-point score reads as a thousands group.
 */
internal fun formatScore(score: Double): String = String.format(Locale.ROOT, "%.2f", score).trimEnd('0').trimEnd('.')

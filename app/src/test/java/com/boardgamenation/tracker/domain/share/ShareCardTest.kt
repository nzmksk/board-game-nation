package com.boardgamenation.tracker.domain.share

import com.boardgamenation.tracker.domain.model.CoopOutcome
import com.boardgamenation.tracker.domain.model.ParticipantForm
import com.boardgamenation.tracker.domain.model.ScoringMode
import com.boardgamenation.tracker.domain.model.SessionForm
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The card is a picture that leaves the app, so the cases that matter are the ones where
 * the play has no clean ranking to show: a table that won together, sides that won
 * together, a quick log that named winners without placing anybody, and a play logged
 * with no outcome at all. Getting any of those wrong publishes a result that did not
 * happen.
 */
class ShareCardTest {

    private fun form(
        scoringMode: ScoringMode = ScoringMode.RANKED_SCORES,
        coopOutcome: CoopOutcome? = null,
        winningTeam: String? = null,
        mode: String? = null,
        endReason: String? = null,
        participants: List<ParticipantForm>
    ) = SessionForm(
        gameId = 1,
        gameTitle = "Wingspan",
        playedOn = LocalDate.of(2026, 9, 2),
        durationMinutes = 95,
        scoringMode = scoringMode,
        coopOutcome = coopOutcome,
        winningTeam = winningTeam,
        mode = mode,
        endReason = endReason,
        participants = participants
    )

    private fun player(
        id: Long,
        name: String,
        score: Double? = null,
        placement: Int? = null,
        isWinner: Boolean = false,
        faction: String? = null,
        team: String? = null,
        turnOrder: Int? = null,
        seat: Int? = null,
        isNewPlayer: Boolean = false
    ) = ParticipantForm(
        playerId = id,
        playerName = name,
        score = score,
        placement = placement,
        isWinner = isWinner,
        faction = faction,
        team = team,
        turnOrder = turnOrder,
        seat = seat,
        isNewPlayer = isNewPlayer
    )

    private fun namesOn(card: ShareCard) = card.standings.map { it.name }

    @Test
    fun `a ranked play lists the winner first whatever order the form held`() {
        val card = ShareCard.of(
            form(
                participants = listOf(
                    player(1, "Hafiz", score = 71.0, placement = 3),
                    player(2, "Aina", score = 94.0, placement = 1, isWinner = true),
                    player(3, "Ben", score = 88.0, placement = 2)
                )
            )
        )

        assertEquals(ShareResult.RANKED, card.result)
        assertEquals(listOf("Aina", "Ben", "Hafiz"), namesOn(card))
        assertEquals(listOf(1, 2, 3), card.standings.map { it.rank })
        assertEquals(listOf("Aina"), card.winners)
    }

    /** Standard competition ranking, so the shared 1 is shown to both and 3 follows. */
    @Test
    fun `tied winners both keep first place and the form order between them`() {
        val card = ShareCard.of(
            form(
                participants = listOf(
                    player(1, "Hafiz", score = 94.0, placement = 1, isWinner = true),
                    player(2, "Aina", score = 94.0, placement = 1, isWinner = true),
                    player(3, "Ben", score = 60.0, placement = 3)
                )
            )
        )

        assertEquals(listOf("Hafiz", "Aina", "Ben"), namesOn(card))
        assertEquals(listOf(1, 1, 3), card.standings.map { it.rank })
        assertEquals(listOf("Hafiz", "Aina"), card.winners)
    }

    /**
     * Quick log asks who won and never places anybody, so every placement is null. The
     * winner still has to reach the top of the card.
     */
    @Test
    fun `a play with winners but no placements still leads with the winners`() {
        val card = ShareCard.of(
            form(
                scoringMode = ScoringMode.NONE,
                participants = listOf(
                    player(1, "Hafiz"),
                    player(2, "Aina"),
                    player(3, "Ben", isWinner = true)
                )
            )
        )

        assertEquals(ShareResult.RANKED, card.result)
        assertEquals(listOf("Ben", "Hafiz", "Aina"), namesOn(card))
        assertTrue(card.standings.all { it.rank == null })
    }

    @Test
    fun `a co-op announces the table's result and ranks nobody`() {
        val card = ShareCard.of(
            form(
                scoringMode = ScoringMode.COOPERATIVE,
                coopOutcome = CoopOutcome.WIN,
                participants = listOf(
                    player(1, "Hafiz", isWinner = true),
                    player(2, "Aina", isWinner = true)
                )
            )
        )

        assertEquals(ShareResult.COOP_WIN, card.result)
        assertEquals(listOf("Hafiz", "Aina"), namesOn(card))
        assertTrue(card.standings.all { it.rank == null })
    }

    @Test
    fun `a co-op nobody recorded the outcome of announces nothing`() {
        val card = ShareCard.of(
            form(
                scoringMode = ScoringMode.COOPERATIVE,
                coopOutcome = CoopOutcome.NA,
                participants = listOf(player(1, "Hafiz"), player(2, "Aina"))
            )
        )

        assertEquals(ShareResult.UNRESOLVED, card.result)
        assertEquals(emptyList<String>(), card.winners)
    }

    /** A side wins together, so the card groups it rather than interleaving the table. */
    @Test
    fun `a team game puts the winning side first and keeps each side whole`() {
        val card = ShareCard.of(
            form(
                scoringMode = ScoringMode.TEAM_BASED,
                winningTeam = "Fascists",
                participants = listOf(
                    player(1, "Hafiz", team = "Liberals"),
                    player(2, "Aina", team = "Fascists", isWinner = true),
                    player(3, "Ben", team = "Liberals"),
                    player(4, "Sara", team = "Fascists", isWinner = true)
                )
            )
        )

        assertEquals(ShareResult.TEAMS, card.result)
        assertEquals(listOf("Aina", "Sara", "Hafiz", "Ben"), namesOn(card))
        assertEquals("Fascists", card.winningTeam)
        assertTrue(card.standings.all { it.rank == null })
    }

    /** Someone left off a side is the gap in the record, not a third team to rank. */
    @Test
    fun `a player on no side comes last`() {
        val card = ShareCard.of(
            form(
                scoringMode = ScoringMode.TEAM_BASED,
                winningTeam = "Liberals",
                participants = listOf(
                    player(1, "Hafiz"),
                    player(2, "Aina", team = "Fascists"),
                    player(3, "Ben", team = "Liberals", isWinner = true)
                )
            )
        )

        assertEquals(listOf("Ben", "Aina", "Hafiz"), namesOn(card))
    }

    @Test
    fun `the turn order comes back in turn order and only for the seats recorded`() {
        val card = ShareCard.of(
            form(
                participants = listOf(
                    player(1, "Hafiz", turnOrder = 2),
                    player(2, "Aina", turnOrder = 1),
                    player(3, "Ben")
                )
            )
        )

        assertEquals(listOf("Aina", "Hafiz"), card.turnOrder)
        assertEquals(ShareArrangement.TURN_ORDER, card.arrangement)
    }

    @Test
    fun `a play with no turn order and no seating recorded carries neither`() {
        val card = ShareCard.of(
            form(participants = listOf(player(1, "Hafiz"), player(2, "Aina")))
        )

        assertEquals(emptyList<String>(), card.turnOrder)
        assertEquals(emptyList<String>(), card.seating)
        assertNull(card.arrangement)
    }

    @Test
    fun `a seated table comes back round the table from chair one`() {
        val card = ShareCard.of(
            form(
                participants = listOf(
                    player(1, "Hafiz", seat = 3),
                    player(2, "Aina", seat = 1),
                    player(3, "Ben", seat = 2)
                )
            )
        )

        assertEquals(listOf("Aina", "Ben", "Hafiz"), card.seating)
        assertEquals(ShareArrangement.SEATING, card.arrangement)
    }

    /**
     * A player without a chair may well have been sitting between two who have one, so
     * the ring that is left after dropping them is a table that did not exist.
     */
    @Test
    fun `a half seated table carries no seating at all`() {
        val card = ShareCard.of(
            form(
                participants = listOf(
                    player(1, "Hafiz", seat = 1),
                    player(2, "Aina", seat = 2),
                    player(3, "Ben")
                )
            )
        )

        assertEquals(emptyList<String>(), card.seating)
        assertNull(card.arrangement)
    }

    /** Both are carried; the card only has room to say one of them. */
    @Test
    fun `the turn order is the arrangement shown when a play recorded both`() {
        val card = ShareCard.of(
            form(
                participants = listOf(
                    player(1, "Hafiz", turnOrder = 1, seat = 2),
                    player(2, "Aina", seat = 1)
                )
            )
        )

        assertEquals(listOf("Hafiz"), card.turnOrder)
        assertEquals(listOf("Aina", "Hafiz"), card.seating)
        assertEquals(ShareArrangement.TURN_ORDER, card.arrangement)
    }

    @Test
    fun `blank free text is dropped rather than printed as an empty line`() {
        val card = ShareCard.of(
            form(
                mode = "   ",
                endReason = "",
                participants = listOf(player(1, "Hafiz", faction = " ", team = ""))
            )
        )

        assertNull(card.mode)
        assertNull(card.endReason)
        assertNull(card.standings.single().faction)
        assertNull(card.standings.single().team)
    }

    /**
     * Who was new to the game is recorded per player and per play, so it has to ride
     * along with the row it belongs to rather than being looked up again later. The
     * ordering rules move rows around freely, so the check is that the flag lands on the
     * right name after the sort, not merely that some row carries it.
     */
    @Test
    fun `a first-timer is marked on their own row and nobody else's`() {
        val card = ShareCard.of(
            form(
                participants = listOf(
                    player(1, "Hafiz", score = 71.0, placement = 3),
                    player(2, "Aina", score = 94.0, placement = 1, isWinner = true),
                    player(3, "Ben", score = 88.0, placement = 2, isNewPlayer = true)
                )
            )
        )

        assertEquals(listOf("Aina", "Ben", "Hafiz"), namesOn(card))
        assertEquals(listOf(false, true, false), card.standings.map { it.isNewPlayer })
    }

    /** Everybody's first play of a game is the ordinary case for a new game night. */
    @Test
    fun `a table where nobody had played before marks every row`() {
        val card = ShareCard.of(
            form(
                scoringMode = ScoringMode.COOPERATIVE,
                coopOutcome = CoopOutcome.WIN,
                participants = listOf(
                    player(1, "Hafiz", isWinner = true, isNewPlayer = true),
                    player(2, "Aina", isWinner = true, isNewPlayer = true)
                )
            )
        )

        assertTrue(card.standings.all { it.isNewPlayer })
    }

    @Test
    fun `the card knows whether there are scores to print`() {
        val scored = ShareCard.of(
            form(participants = listOf(player(1, "Hafiz", score = 12.0)))
        )
        val unscored = ShareCard.of(form(participants = listOf(player(1, "Hafiz"))))

        assertTrue(scored.hasScores)
        assertTrue(!unscored.hasScores)
    }

    /** A whole number is written as one; a half point survives. */
    @Test
    fun `scores print the way somebody would write them on a scoresheet`() {
        assertEquals("12", formatScore(12.0))
        assertEquals("100", formatScore(100.0))
        assertEquals("7.5", formatScore(7.5))
        assertEquals("0", formatScore(0.0))
        assertEquals("-3", formatScore(-3.0))
        assertEquals("6.25", formatScore(6.25))
    }
}

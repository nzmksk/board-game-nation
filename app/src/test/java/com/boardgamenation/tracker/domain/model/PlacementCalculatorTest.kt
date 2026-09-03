package com.boardgamenation.tracker.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlacementCalculatorTest {

    private fun participants(vararg scores: Pair<String, Double?>) = scores.mapIndexed { index, (name, score) ->
        ParticipantForm(playerId = index + 1L, playerName = name, score = score)
    }

    @Test
    fun `highest score takes first place`() {
        val result = PlacementCalculator.derive(
            participants("Aina" to 42.0, "Ben" to 90.0, "Chandra" to 55.0),
            highScoreWins = true
        )
        assertEquals(1, result.first { it.playerName == "Ben" }.placement)
        assertEquals(2, result.first { it.playerName == "Chandra" }.placement)
        assertEquals(3, result.first { it.playerName == "Aina" }.placement)
        assertTrue(result.first { it.playerName == "Ben" }.isWinner)
    }

    @Test
    fun `lowest score wins for golf scoring`() {
        val result = PlacementCalculator.derive(
            participants("Aina" to 42.0, "Ben" to 90.0, "Chandra" to 55.0),
            highScoreWins = false
        )
        assertEquals(1, result.first { it.playerName == "Aina" }.placement)
        assertTrue(result.first { it.playerName == "Aina" }.isWinner)
        assertFalse(result.first { it.playerName == "Ben" }.isWinner)
    }

    /**
     * Standard competition ranking. A shared first place consumes two slots, so the next
     * player is third; ranking them second would imply somebody came second.
     */
    @Test
    fun `a tie shares the placement and skips the next`() {
        val result = PlacementCalculator.derive(
            participants("Aina" to 90.0, "Ben" to 90.0, "Chandra" to 40.0),
            highScoreWins = true
        )
        assertEquals(1, result.first { it.playerName == "Aina" }.placement)
        assertEquals(1, result.first { it.playerName == "Ben" }.placement)
        assertEquals(3, result.first { it.playerName == "Chandra" }.placement)
    }

    @Test
    fun `both halves of a tie for first are winners`() {
        val result = PlacementCalculator.derive(
            participants("Aina" to 90.0, "Ben" to 90.0),
            highScoreWins = true
        )
        assertTrue(result.all { it.isWinner })
    }

    @Test
    fun `a player with no score is ranked nowhere and wins nothing`() {
        val result = PlacementCalculator.derive(
            participants("Aina" to 90.0, "Ben" to null),
            highScoreWins = true
        )
        val ben = result.first { it.playerName == "Ben" }
        assertNull(ben.placement)
        assertFalse(ben.isWinner)
    }

    @Test
    fun `the caller's ordering survives`() {
        val input = participants("Aina" to 10.0, "Ben" to 90.0, "Chandra" to 50.0)
        val result = PlacementCalculator.derive(input, highScoreWins = true)
        // The form must not reshuffle itself while somebody is still typing.
        assertEquals(listOf("Aina", "Ben", "Chandra"), result.map { it.playerName })
    }

    @Test
    fun `no scores at all means no placements`() {
        val result = PlacementCalculator.derive(
            participants("Aina" to null, "Ben" to null),
            highScoreWins = true
        )
        assertTrue(result.all { it.placement == null && !it.isWinner })
    }

    @Test
    fun `manual placement takes the list order literally`() {
        val result = PlacementCalculator.fromOrder(
            participants("Chandra" to null, "Aina" to null, "Ben" to null)
        )
        assertEquals(1, result[0].placement)
        assertEquals(2, result[1].placement)
        assertEquals(3, result[2].placement)
        assertTrue(result[0].isWinner)
        assertFalse(result[1].isWinner)
    }

    @Test
    fun `a co-op win makes everyone a winner`() {
        val result = PlacementCalculator.applyCoop(
            participants("Aina" to null, "Ben" to null),
            CoopOutcome.WIN
        )
        assertTrue(result.all { it.isWinner })
        assertTrue(result.all { it.placement == null })
    }

    @Test
    fun `a co-op loss makes nobody a winner`() {
        val result = PlacementCalculator.applyCoop(
            participants("Aina" to null, "Ben" to null),
            CoopOutcome.LOSS
        )
        assertTrue(result.none { it.isWinner })
    }

    // --- teams ----------------------------------------------------------------------

    private fun sides(vararg members: Pair<String, String?>) = members.mapIndexed { index, (name, team) ->
        ParticipantForm(playerId = index + 1L, playerName = name, team = team)
    }

    @Test
    fun `a team win makes that whole side the winners`() {
        val result = PlacementCalculator.applyTeams(
            sides(
                "Aina" to "Liberals",
                "Ben" to "Fascists",
                "Chandra" to "Liberals",
                "Dee" to "Fascists"
            ),
            winningTeam = "Liberals"
        )

        assertEquals(
            listOf("Aina", "Chandra"),
            result.filter { it.isWinner }.map { it.playerName }
        )
        assertTrue(
            "a side winning says nothing about the order within it",
            result.all { it.placement == null }
        )
    }

    @Test
    fun `team names match regardless of case and stray spacing`() {
        val result = PlacementCalculator.applyTeams(
            sides("Aina" to " liberals ", "Ben" to "Fascists"),
            winningTeam = "Liberals"
        )

        assertTrue(result.first { it.playerName == "Aina" }.isWinner)
        assertFalse(result.first { it.playerName == "Ben" }.isWinner)
    }

    @Test
    fun `no winning side means nobody won`() {
        val result = PlacementCalculator.applyTeams(
            sides("Aina" to "Liberals", "Ben" to "Fascists"),
            winningTeam = null
        )
        assertTrue(result.none { it.isWinner })
    }

    @Test
    fun `a player left off a side cannot win by accident`() {
        val result = PlacementCalculator.applyTeams(
            sides("Aina" to "Liberals", "Ben" to null),
            winningTeam = "Liberals"
        )

        assertTrue(result.first { it.playerName == "Aina" }.isWinner)
        assertFalse(result.first { it.playerName == "Ben" }.isWinner)
    }

    @Test
    fun `the form lists each side once however many players are on it`() {
        val form = SessionForm(
            playedOn = java.time.LocalDate.of(2026, 8, 30),
            scoringMode = ScoringMode.TEAM_BASED,
            participants = sides(
                "Aina" to "Liberals",
                "Ben" to "fascists",
                "Chandra" to "Liberals",
                "Dee" to "  "
            )
        )

        assertEquals(listOf("Liberals", "fascists"), form.teams)
        assertTrue(form.isTeamBased)
    }
}

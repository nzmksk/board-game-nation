package com.boardgamenation.tracker.domain.model

import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A seating is edited the same way a turn order is, so it breaks in the same ways -- a
 * player taken out of the middle, a chair claimed twice -- and those cases are covered
 * here as well as in `TurnOrderTest` because the two share their renumbering rule and a
 * change to it has to keep both honest.
 *
 * What is only tested here is the ring: that it closes, that a half-seated table is
 * refused rather than guessed at, and that a table of two really does put the same
 * person on both sides.
 */
class SeatingTest {

    private fun table(vararg seats: Pair<String, Int?>) = seats.mapIndexed { index, (name, seat) ->
        ParticipantForm(playerId = index + 1L, playerName = name, seat = seat)
    }

    private fun seatsOf(participants: List<ParticipantForm>) = participants.associate { it.playerName to it.seat }

    private fun around(participants: List<ParticipantForm>, name: String): Pair<String, String>? {
        val player = participants.first { it.playerName == name }
        return Seating.neighbours(participants)[player.playerId]
            ?.let { it.anticlockwise.playerName to it.clockwise.playerName }
    }

    @Test
    fun `going round the table naming people numbers the chairs from one`() {
        var table = table("Hafiz" to null, "Aina" to null, "Ben" to null)
        table = Seating.toggle(table, playerId = 2)
        table = Seating.toggle(table, playerId = 3)

        assertEquals(mapOf("Hafiz" to null, "Aina" to 1, "Ben" to 2), seatsOf(table))
    }

    /** Otherwise 1, 3, 4 leaves an empty chair in a ring that is meant to close. */
    @Test
    fun `standing a player up closes the gap they leave`() {
        val table = Seating.toggle(
            table("Hafiz" to 1, "Aina" to 2, "Ben" to 3, "Sara" to 4),
            playerId = 2
        )

        assertEquals(
            mapOf("Hafiz" to 1, "Aina" to null, "Ben" to 2, "Sara" to 3),
            seatsOf(table)
        )
    }

    @Test
    fun `two players cannot hold the same chair`() {
        val table = Seating.normalise(table("Hafiz" to 2, "Aina" to 2, "Ben" to 9))

        assertEquals(mapOf("Hafiz" to 1, "Aina" to 2, "Ben" to 3), seatsOf(table))
    }

    @Test
    fun `clearing the arrangement stands everybody up`() {
        assertEquals(
            mapOf("Hafiz" to null, "Aina" to null),
            seatsOf(Seating.clear(table("Hafiz" to 1, "Aina" to 2)))
        )
    }

    // --- the ring -------------------------------------------------------------------

    /**
     * The wrap is the whole feature. The player in the last chair is beside the player
     * in the first, which is the adjacency a turn order does not have and the reason
     * this column exists at all.
     */
    @Test
    fun `the last chair is next to the first`() {
        val table = table("Hafiz" to 1, "Aina" to 2, "Ben" to 3)

        assertEquals("Ben" to "Aina", around(table, "Hafiz"))
        assertEquals("Aina" to "Hafiz", around(table, "Ben"))
        assertEquals("Hafiz" to "Ben", around(table, "Aina"))
    }

    /** In a two-player game the person on your left is the person on your right. */
    @Test
    fun `a table of two has the same neighbour on both sides`() {
        val table = table("Hafiz" to 1, "Aina" to 2)

        assertEquals("Aina" to "Aina", around(table, "Hafiz"))
        assertEquals("Hafiz" to "Hafiz", around(table, "Aina"))
    }

    /**
     * The difference from a turn order, and the point of the whole exercise: an unseated
     * player may have been sitting between the two this would otherwise call neighbours,
     * so every adjacency in a half-filled ring is a guess.
     */
    @Test
    fun `a half-seated table reports no neighbours rather than wrong ones`() {
        val table = Seating.normalise(table("Hafiz" to 1, "Aina" to 2, "Ben" to null))

        assertFalse(Seating.isComplete(table))
        assertEquals(emptyMap<Long, Neighbours>(), Seating.neighbours(table))
        // The partial arrangement is still kept -- it just does not answer yet.
        assertEquals(mapOf("Hafiz" to 1, "Aina" to 2, "Ben" to null), seatsOf(table))
    }

    /** One player in a ring is their own neighbour, which is true and worth nothing. */
    @Test
    fun `a solo play has no neighbours`() {
        val table = table("Hafiz" to 1)

        assertFalse(Seating.isComplete(table))
        assertEquals(emptyMap<Long, Neighbours>(), Seating.neighbours(table))
    }

    @Test
    fun `the form reads the arrangement round the table from seat one`() {
        val form = SessionForm(
            playedOn = LocalDate.parse("2026-02-01"),
            participants = table("Hafiz" to 3, "Aina" to 1, "Ben" to 2)
        )

        assertEquals(listOf("Aina", "Ben", "Hafiz"), form.seating.map { it.playerName })
        assertTrue(form.neighbours.isNotEmpty())

        val unseated = form.copy(participants = Seating.clear(form.participants))
        assertEquals(emptyList<ParticipantForm>(), unseated.seating)
        assertTrue(unseated.neighbours.isEmpty())
    }

    /** A seating and a turn order are independent: neither is read off the other. */
    @Test
    fun `seating a table leaves the turn order alone`() {
        val started = TurnOrder.firstOnly(
            table("Hafiz" to null, "Aina" to null, "Ben" to null),
            playerId = 3
        )
        val seated = Seating.toggle(Seating.toggle(started, playerId = 1), playerId = 2)

        assertEquals(mapOf("Hafiz" to 1, "Aina" to 2, "Ben" to null), seatsOf(seated))
        assertEquals(1, seated.first { it.playerName == "Ben" }.turnOrder)
        assertNull(seated.first { it.playerName == "Hafiz" }.turnOrder)
    }
}

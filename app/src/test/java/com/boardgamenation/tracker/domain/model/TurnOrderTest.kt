package com.boardgamenation.tracker.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The turn order is entered by tapping names in sequence and then edited freely, so the
 * cases that matter here are all the ways an order stops being a clean 1..n run: a
 * player removed from the middle of it, a seat claimed twice, an order that covers only
 * part of the table.
 */
class TurnOrderTest {

    private fun table(vararg seats: Pair<String, Int?>) = seats.mapIndexed { index, (name, seat) ->
        ParticipantForm(playerId = index + 1L, playerName = name, turnOrder = seat)
    }

    private fun seatsOf(participants: List<ParticipantForm>) =
        participants.associate { it.playerName to it.turnOrder }

    @Test
    fun `naming players in sequence numbers them from one`() {
        var table = table("Hafiz" to null, "Aina" to null, "Ben" to null)
        table = TurnOrder.toggle(table, playerId = 2)
        table = TurnOrder.toggle(table, playerId = 3)

        assertEquals(mapOf("Hafiz" to null, "Aina" to 1, "Ben" to 2), seatsOf(table))
    }

    /** The gap is the whole point: 1, 3, 4 would leave a third player claiming seat 3. */
    @Test
    fun `removing a player from the middle closes the gap behind them`() {
        val table = TurnOrder.toggle(
            table("Hafiz" to 1, "Aina" to 2, "Ben" to 3, "Sara" to 4),
            playerId = 2,
        )

        assertEquals(
            mapOf("Hafiz" to 1, "Aina" to null, "Ben" to 2, "Sara" to 3),
            seatsOf(table),
        )
    }

    @Test
    fun `naming someone already in the order takes them out of it`() {
        val table = TurnOrder.toggle(table("Hafiz" to 1, "Aina" to 2), playerId = 1)

        assertNull(seatsOf(table)["Hafiz"])
        assertEquals(1, seatsOf(table)["Aina"])
    }

    /**
     * Only ever one first player. A form assembled from parts -- a draft edited, an
     * import, a sheet that set the first player and a screen that set the order -- can
     * hand over two rows claiming seat 1, and a first-player win rate would count both.
     */
    @Test
    fun `two players cannot both hold the first seat`() {
        val table = TurnOrder.normalise(table("Hafiz" to 1, "Aina" to 1, "Ben" to 5))

        assertEquals(mapOf("Hafiz" to 1, "Aina" to 2, "Ben" to 3), seatsOf(table))
    }

    @Test
    fun `an order that covers only part of the table is left partial`() {
        val table = TurnOrder.normalise(table("Hafiz" to 4, "Aina" to null, "Ben" to null))

        // Knowing who started is worth recording; the other two seats are not invented.
        assertEquals(mapOf("Hafiz" to 1, "Aina" to null, "Ben" to null), seatsOf(table))
    }

    @Test
    fun `the quick sheet records a first player and nothing else`() {
        val table = TurnOrder.firstOnly(table("Hafiz" to 3, "Aina" to 1, "Ben" to 2), playerId = 1)

        assertEquals(mapOf("Hafiz" to 1, "Aina" to null, "Ben" to null), seatsOf(table))
    }

    @Test
    fun `no first player at all is a legitimate answer`() {
        assertEquals(
            mapOf("Hafiz" to null, "Aina" to null),
            seatsOf(TurnOrder.firstOnly(table("Hafiz" to 1, "Aina" to 2), playerId = null)),
        )
        assertEquals(
            mapOf("Hafiz" to null, "Aina" to null),
            seatsOf(TurnOrder.clear(table("Hafiz" to 1, "Aina" to 2))),
        )
    }

    @Test
    fun `the first player is the head of the order`() {
        val form = SessionForm(
            playedOn = java.time.LocalDate.parse("2026-02-01"),
            participants = table("Hafiz" to 2, "Aina" to 1),
        )

        assertEquals("Aina", form.firstPlayer?.playerName)
        assertNull(form.copy(participants = TurnOrder.clear(form.participants)).firstPlayer)
    }
}

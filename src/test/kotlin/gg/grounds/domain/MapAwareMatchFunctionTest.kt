package gg.grounds.domain

import java.time.Instant
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Map requests partition the queue without changing how a match is drafted: tickets that asked for
 * a map only meet tickets that asked for the same map or for none.
 */
class MapAwareMatchFunctionTest {

    private val now: Instant = Instant.parse("2026-10-19T12:00:00Z")
    private val duel = ModeConfig(modeId = "duel", teamSize = 1, teamCount = 2)
    private val fn = MapAwareMatchFunction(BandedMmrMatchFunction())

    private fun ticket(id: String, map: String? = null, waited: Long = 30, mu: Double = 25.0) =
        Ticket(
            id = id,
            playerId = "p-$id",
            modeId = "duel",
            mu = mu,
            sigma = 8.33,
            enqueuedAt = now.minusSeconds(waited),
            map = map,
        )

    @Test
    fun `tickets asking for different maps never meet`() {
        val proposals =
            fn.propose(
                duel,
                listOf(ticket("a", map = "m/aura"), ticket("b", map = "m/breeze")),
                now,
            )

        assertTrue(proposals.isEmpty())
    }

    @Test
    fun `a map request plays with a ticket that asked for none, on that map`() {
        val proposals =
            fn.propose(duel, listOf(ticket("a", map = "m/aura", waited = 60), ticket("b")), now)

        assertEquals(1, proposals.size)
        assertEquals(setOf("a", "b"), proposals[0].ticketIds.toSet())
        assertEquals("m/aura", proposals[0].map)
    }

    @Test
    fun `each map request takes its own partner from the any pool`() {
        val tickets =
            listOf(
                ticket("aura", map = "m/aura", waited = 90),
                ticket("breeze", map = "m/breeze", waited = 80),
                ticket("any1", waited = 70),
                ticket("any2", waited = 60),
            )

        val proposals = fn.propose(duel, tickets, now)

        assertEquals(2, proposals.size)
        val byMap = proposals.associateBy { it.map }
        assertEquals(setOf("m/aura", "m/breeze"), byMap.keys)
        assertTrue("aura" in byMap.getValue("m/aura").ticketIds)
        assertTrue("breeze" in byMap.getValue("m/breeze").ticketIds)
        assertEquals(4, proposals.flatMap { it.ticketIds }.toSet().size, "nobody twice")
    }

    @Test
    fun `tickets that all asked for none match exactly as before`() {
        val tickets = listOf(ticket("a", mu = 20.0), ticket("b", mu = 21.0), ticket("c", mu = 40.0))

        val withMaps = fn.propose(duel, tickets, now)
        val plain = BandedMmrMatchFunction().propose(duel, tickets, now)

        assertEquals(plain.map { it.teams }, withMaps.map { it.teams })
        assertNull(withMaps.single().map)
    }

    @Test
    fun `a match of tickets that asked for none plays no requested map`() {
        // Two "any" tickets and a lone aura request that cannot be paired within its band: the
        // "any" pair still forms, without a map attached.
        val tickets =
            listOf(
                ticket("aura", map = "m/aura", mu = 50.0, waited = 1),
                ticket("any1", mu = 20.0),
                ticket("any2", mu = 20.5),
            )

        val proposals = fn.propose(duel, tickets, now)

        assertEquals(1, proposals.size)
        assertEquals(setOf("any1", "any2"), proposals[0].ticketIds.toSet())
        assertNull(proposals[0].map)
    }

    @Test
    fun `an under-filled free-for-all keeps to one map`() {
        val ffa =
            ModeConfig(
                modeId = "ffa",
                teamSize = 1,
                teamCount = 8,
                minPlayers = 2,
                fillSeconds = 10,
            )
        val tickets =
            listOf(
                ticket("a1", map = "m/aura", waited = 60),
                ticket("b1", map = "m/breeze", waited = 50),
                ticket("x", waited = 40),
                ticket("a2", map = "m/aura", waited = 30),
            )

        val proposals = fn.propose(ffa, tickets, now)

        val aura = proposals.single { it.map == "m/aura" }
        assertEquals(setOf("a1", "a2", "x"), aura.ticketIds.toSet())
        assertTrue(proposals.none { "b1" in it.ticketIds }, "breeze has nobody to play with")
    }
}

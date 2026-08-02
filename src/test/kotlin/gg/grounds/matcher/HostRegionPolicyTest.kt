package gg.grounds.matcher

import gg.grounds.domain.Ticket
import gg.grounds.domain.TicketState
import java.time.Instant
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class HostRegionPolicyTest {

    @Test
    fun `everyone in one region plays there`() {
        val chosen =
            HostRegionPolicy.choose(listOf(at("nl-ams1"), at("nl-ams1")), fallback = "de-fra1")

        assertEquals("nl-ams1", chosen)
    }

    @Test
    fun `the majority region wins`() {
        val chosen =
            HostRegionPolicy.choose(
                listOf(at("de-fra1"), at("de-fra1"), at("de-fra1"), at("nl-ams1")),
                fallback = "nl-ams1",
            )

        assertEquals("de-fra1", chosen, "three players must not travel to spare one")
    }

    @Test
    fun `an even split never hosts where nobody is`() {
        // Two in Helsinki, two in Amsterdam, and Frankfurt sitting geographically
        // between them. The "fair middle" is not on the table: only regions that
        // players are actually in are candidates, because a player in the host
        // region pays nothing and a middle region makes EVERYONE pay. So this
        // resolves to one of the two ends — symmetric, hence decided by the
        // deterministic tie-break, not by chance.
        val chosen =
            HostRegionPolicy.choose(
                listOf(at("fi-hel1"), at("fi-hel1"), at("nl-ams1"), at("nl-ams1")),
                fallback = "de-fra1",
            )

        assertEquals("fi-hel1", chosen, "the fallback region, where nobody is, must not win")
    }

    @Test
    fun `a tie between equal regions is decided the same way everywhere`() {
        // Two matchers in different regions run this for the same match and must
        // agree, or both would try to allocate it — in different clusters.
        val tickets = listOf(at("nl-ams1"), at("de-fra1"))

        assertEquals(
            HostRegionPolicy.choose(tickets, fallback = "nl-ams1"),
            HostRegionPolicy.choose(tickets.reversed(), fallback = "de-fra1"),
            "the choice must not depend on ticket order or on who asked",
        )
    }

    @Test
    fun `tickets without a location fall back to the asking region`() {
        // Written before locations existed, or by a caller that sent none.
        val chosen = HostRegionPolicy.choose(listOf(at(""), at("")), fallback = "de-fra1")

        assertEquals("de-fra1", chosen)
    }

    @Test
    fun `a region with no measured distance still resolves to one of the players`() {
        // A region added to regions.json but not yet to the RTT table. Both
        // directions score the same unknown penalty, so this is the symmetric
        // case again — what matters is that it lands on a region a player is in
        // and does so deterministically, not that it lands on a particular one.
        val chosen =
            HostRegionPolicy.choose(listOf(at("nl-ams1"), at("xx-nowhere1")), fallback = "de-fra1")

        assertEquals("nl-ams1", chosen)
    }

    private fun at(location: String) =
        Ticket(
            id = location + "-" + counter++,
            playerId = "p$counter",
            modeId = "duel",
            mu = 25.0,
            sigma = 8.333,
            enqueuedAt = Instant.EPOCH,
            location = location,
            state = TicketState.QUEUED,
        )

    private companion object {
        var counter = 0
    }
}

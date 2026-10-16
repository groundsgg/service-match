package gg.grounds.domain

import java.time.Instant
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class BandedMmrMatchFunctionTest {

    private val fn = BandedMmrMatchFunction()
    private val now: Instant = Instant.ofEpochSecond(1_000_000)
    private val duel = ModeConfig(modeId = "duel", teamSize = 1, teamCount = 2)

    private fun ticket(id: String, mu: Double, sigma: Double = 1.0, waitedSeconds: Long = 0) =
        Ticket(
            id = id,
            playerId = "p-$id",
            modeId = "duel",
            mu = mu,
            sigma = sigma,
            enqueuedAt = now.minusSeconds(waitedSeconds),
        )

    @Test
    fun `nothing happens below the player count`() {
        assertTrue(fn.propose(duel, listOf(ticket("a", 25.0)), now).isEmpty())
    }

    @Test
    fun `two players of similar rating match`() {
        val proposals = fn.propose(duel, listOf(ticket("a", 25.0), ticket("b", 25.5)), now)
        assertEquals(1, proposals.size)
        assertEquals(setOf("a", "b"), proposals[0].ticketIds.toSet())
    }

    @Test
    fun `a settled player is not matched against someone far away`() {
        // Both settled (sigma 1.0 -> band = max(2.0, 1.0) = 2.0), 20 points
        // apart, no waiting time. Matching them would be a slaughter.
        val proposals = fn.propose(duel, listOf(ticket("a", 10.0), ticket("b", 30.0)), now)
        assertTrue(proposals.isEmpty(), "should not match a 20-point gap on a 2-point band")
    }

    @Test
    fun `waiting widens the band until the match happens`() {
        // Same 20-point gap, but the anchor has waited 200s. Band widens by
        // w=1.0 every s=10s, so 20 extra points of reach — and past
        // mutualSeconds=150 the anchor no longer needs the candidate's consent.
        val proposals =
            fn.propose(duel, listOf(ticket("a", 10.0, waitedSeconds = 200), ticket("b", 30.0)), now)
        assertEquals(1, proposals.size, "a long wait should eventually bridge the gap")
    }

    @Test
    fun `a fresh player is not dragged into a desperate player's band`() {
        // The anchor has waited 60s: under mercy (90s) and under mutual (150s),
        // so mutuality still applies. Its own band is wide (2.0 + 6.0 = 8.0),
        // but the fresh candidate's band is only 2.0 — and the gap is 7 points.
        // The anchor would accept; the candidate must not be forced to.
        val proposals =
            fn.propose(
                duel,
                listOf(ticket("anchor", 20.0, waitedSeconds = 60), ticket("fresh", 27.0)),
                now,
            )
        assertTrue(proposals.isEmpty(), "mutuality must protect the fresh player")
    }

    @Test
    fun `an uncertain new player casts a wide net immediately`() {
        // sigma 8.33 (a brand-new rating) -> band = max(2.0, 8.33) = 8.33, so a
        // 6-point gap is fine even with no waiting. The model does not know
        // these players yet; matching them is how it finds out.
        val proposals =
            fn.propose(
                duel,
                listOf(ticket("a", 22.0, sigma = 8.33), ticket("b", 28.0, sigma = 8.33)),
                now,
            )
        assertEquals(1, proposals.size)
    }

    @Test
    fun `the longest waiting player is served first`() {
        val proposals =
            fn.propose(
                duel,
                listOf(
                    ticket("fresh", 25.0),
                    ticket("waiting", 25.0, waitedSeconds = 120),
                    ticket("mid", 25.0, waitedSeconds = 30),
                ),
                now,
            )
        assertEquals(1, proposals.size)
        assertTrue(
            "waiting" in proposals[0].ticketIds,
            "the anchor must be the longest-waiting ticket",
        )
    }

    @Test
    fun `teams are snake-drafted, not split down the middle`() {
        // 2v2 with ratings 28/26/24/22 — all within a fresh player's band, so
        // they do match. A naive top-half split gives 28+26=54 against
        // 24+22=46. Snake gives 28+22=50 against 26+24=50.
        val mode = ModeConfig(modeId = "duo", teamSize = 2, teamCount = 2)
        val tickets =
            listOf(
                    ticket("a", 28.0, sigma = 8.33),
                    ticket("b", 26.0, sigma = 8.33),
                    ticket("c", 24.0, sigma = 8.33),
                    ticket("d", 22.0, sigma = 8.33),
                )
                .map { it.copy(modeId = "duo") }

        val proposals = fn.propose(mode, tickets, now)
        assertEquals(1, proposals.size)

        val teams = proposals[0].teams
        assertEquals(2, teams.size)
        val strengths = teams.map { team -> team.sumOf { id -> tickets.first { it.id == id }.mu } }
        assertEquals(strengths[0], strengths[1], 0.001, "snake draft should balance the teams")
    }

    @Test
    fun `a rating gap wider than the band is refused, even with enough players`() {
        // The case the previous test originally got wrong, kept as its own:
        // four players at 40/30/20/10 are numerous enough for a 2v2, but no two
        // of them are within a fresh band of each other. Refusing is correct —
        // waiting will widen the band and they will match later.
        val mode = ModeConfig(modeId = "duo", teamSize = 2, teamCount = 2)
        val tickets =
            listOf(
                    ticket("a", 40.0, sigma = 8.33),
                    ticket("b", 30.0, sigma = 8.33),
                    ticket("c", 20.0, sigma = 8.33),
                    ticket("d", 10.0, sigma = 8.33),
                )
                .map { it.copy(modeId = "duo") }

        assertTrue(fn.propose(mode, tickets, now).isEmpty())
    }

    @Test
    fun `a player is never proposed for two matches at once`() {
        val tickets = (1..4).map { ticket("t$it", 25.0, sigma = 8.33) }
        val proposals = fn.propose(duel, tickets, now)

        val allIds = proposals.flatMap { it.ticketIds }
        assertEquals(allIds.size, allIds.toSet().size, "no ticket may appear in two proposals")
    }

    private val ffa =
        ModeConfig(modeId = "ffa", teamSize = 1, teamCount = 8, minPlayers = 1, fillSeconds = 20)

    @Test
    fun `an under-filled free-for-all waits out the fill time`() {
        val tickets = listOf(ticket("a", 25.0, waitedSeconds = 19), ticket("b", 25.0))
        assertTrue(fn.propose(ffa, tickets, now).isEmpty())
    }

    @Test
    fun `an under-filled free-for-all forms once the oldest has waited the fill time`() {
        val tickets = listOf(ticket("a", 25.0, waitedSeconds = 20), ticket("b", 40.0))
        val proposals = fn.propose(ffa, tickets, now)
        assertEquals(1, proposals.size)
        assertEquals(listOf(listOf("a"), listOf("b")), proposals[0].teams)
    }

    @Test
    fun `a single player is a match when the mode allows one`() {
        val proposals = fn.propose(ffa, listOf(ticket("a", 25.0, waitedSeconds = 30)), now)
        assertEquals(listOf(listOf("a")), proposals.single().teams)
    }

    @Test
    fun `a full free-for-all does not wait for the fill time`() {
        val tickets = (1..8).map { ticket("t$it", 25.0, sigma = 8.33) }
        val proposals = fn.propose(ffa, tickets, now)
        assertEquals(1, proposals.size)
        assertEquals(8, proposals[0].ticketIds.size)
    }

    @Test
    fun `an under-filled match never takes more than a full one holds`() {
        // Twelve far apart so no full match bands together; the partial is capped at eight.
        val tickets = (1..12).map { ticket("t$it", it * 10.0, waitedSeconds = 30L - it) }
        val proposals = fn.propose(ffa.copy(fillSeconds = 0), tickets, now)
        assertEquals(8, proposals.single().ticketIds.size)
        assertEquals("t1", proposals.single().ticketIds.first(), "the oldest goes first")
    }

    @Test
    fun `a mode that has not lowered its minimum never forms a short match`() {
        val tickets = listOf(ticket("a", 25.0, waitedSeconds = 600))
        assertTrue(fn.propose(ModeConfig("ffa", 1, 8, fillSeconds = 0), tickets, now).isEmpty())
    }
}

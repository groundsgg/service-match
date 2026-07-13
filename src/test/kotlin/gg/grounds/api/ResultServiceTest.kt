package gg.grounds.api

import gg.grounds.domain.MatchRecordRepository
import gg.grounds.domain.PlayerPlacement
import gg.grounds.domain.RatingRepository
import gg.grounds.persistence.ValkeyQueueIT
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.junit.QuarkusTest
import jakarta.inject.Inject
import java.util.UUID
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The result path against a real Postgres.
 *
 * What matters here is what it *refuses*: a result for a match nobody formed, and a placement for a
 * player who was not in the match. Both would let a gamemode move ratings at will.
 */
@QuarkusTest
@QuarkusTestResource(ValkeyQueueIT.PostgresResource::class)
@QuarkusTestResource(ValkeyQueueIT.ValkeyResource::class)
class ResultServiceTest {

    @Inject lateinit var results: ResultService

    @Inject lateinit var matches: MatchRecordRepository

    @Inject lateinit var ratings: RatingRepository

    private fun formMatch(ranked: Boolean, vararg players: UUID): UUID {
        val matchId = UUID.randomUUID()
        matches.recordMatch(matchId, "duel", ranked, players.toList())
        return matchId
    }

    @Test
    fun `a ranked 1v1 moves both players' ratings apart`() {
        val winner = UUID.randomUUID()
        val loser = UUID.randomUUID()
        val matchId = formMatch(ranked = true, winner, loser)

        val outcome =
            results.report(
                matchId,
                listOf(PlayerPlacement(winner, 1), PlayerPlacement(loser, 2)),
                "normal_finish",
            )

        assertTrue(outcome.applied)
        assertTrue(outcome.rated)

        val w = ratings.find(winner, "duel")
        val l = ratings.find(loser, "duel")
        assertNotNull(w)
        assertNotNull(l)
        assertTrue(w!!.rating.mu > 25.0, "the winner should gain")
        assertTrue(l!!.rating.mu < 25.0, "the loser should lose")
        // The model is more certain about both of them now.
        assertTrue(w.rating.sigma < 25.0 / 3.0)
        assertEquals(1, w.gamesPlayed)
    }

    @Test
    fun `reporting the same result twice changes nothing the second time`() {
        val a = UUID.randomUUID()
        val b = UUID.randomUUID()
        val matchId = formMatch(ranked = true, a, b)
        val placements = listOf(PlayerPlacement(a, 1), PlayerPlacement(b, 2))

        val first = results.report(matchId, placements, "normal_finish")
        val muAfterFirst = ratings.find(a, "duel")!!.rating.mu

        // The gamemode timed out and retried. If this moved the rating again,
        // a flaky network would inflate the ladder.
        val second = results.report(matchId, placements, "normal_finish")

        assertTrue(first.applied)
        assertFalse(second.applied, "a replay must not apply")
        assertFalse(second.rated)
        assertEquals(muAfterFirst, ratings.find(a, "duel")!!.rating.mu, 1e-9)
        assertEquals(1, ratings.find(a, "duel")!!.gamesPlayed, "one match, one game played")
    }

    @Test
    fun `an unranked match records the placements but moves no ratings`() {
        val a = UUID.randomUUID()
        val b = UUID.randomUUID()
        val matchId = formMatch(ranked = false, a, b)

        val outcome =
            results.report(
                matchId,
                listOf(PlayerPlacement(a, 1), PlayerPlacement(b, 2)),
                "normal_finish",
            )

        assertTrue(outcome.applied)
        assertFalse(outcome.rated, "an unranked match must not touch the ladder")
        assertNull(ratings.find(a, "duel"), "no rating row should exist at all")
    }

    @Test
    fun `a result for a match we never formed is rejected`() {
        // The match record is written when the match is formed, so an unknown id
        // is not a race — it is a fabrication.
        assertThrows(UnknownMatchException::class.java) {
            results.report(
                UUID.randomUUID(),
                listOf(PlayerPlacement(UUID.randomUUID(), 1)),
                "normal_finish",
            )
        }
    }

    @Test
    fun `a player who was not in the match cannot be placed`() {
        val a = UUID.randomUUID()
        val b = UUID.randomUUID()
        val stranger = UUID.randomUUID()
        val matchId = formMatch(ranked = true, a, b)

        // Without this check a gamemode could name any player it liked and move
        // their rating.
        assertThrows(NotInMatchException::class.java) {
            results.report(
                matchId,
                listOf(PlayerPlacement(a, 1), PlayerPlacement(stranger, 2)),
                "normal_finish",
            )
        }

        assertNull(ratings.find(a, "duel"), "a rejected result must not half-apply")
    }

    @Test
    fun `a tie leaves both players level`() {
        val a = UUID.randomUUID()
        val b = UUID.randomUUID()
        val matchId = formMatch(ranked = true, a, b)

        val outcome =
            results.report(matchId, listOf(PlayerPlacement(a, 1), PlayerPlacement(b, 1)), "draw")

        assertTrue(outcome.rated)
        val ra = ratings.find(a, "duel")!!.rating
        val rb = ratings.find(b, "duel")!!.rating
        assertEquals(ra.mu, rb.mu, 1e-9, "a draw between equals should leave them equal")
        assertEquals(25.0, ra.mu, 1e-9, "and unchanged, since they started level")
    }
}

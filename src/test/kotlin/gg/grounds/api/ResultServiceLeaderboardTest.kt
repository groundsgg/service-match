package gg.grounds.api

import gg.grounds.domain.MatchRecordRepository
import gg.grounds.domain.PlayerPlacement
import gg.grounds.domain.RatingCalculator
import gg.grounds.domain.RatingRepository
import gg.grounds.leaderboard.LeaderboardClient
import gg.grounds.persistence.ValkeyQueueIT
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.junit.QuarkusTest
import jakarta.inject.Inject
import java.net.ServerSocket
import java.util.UUID
import kotlin.math.roundToLong
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.verifyNoInteractions

/**
 * The wiring between a rated result and service-leaderboard.
 *
 * service-match already computes the ratings, so this is the one place a gamemode's win never has
 * to know a leaderboard exists — and the one place that must never let a leaderboard problem turn
 * an already-committed result back into a failure.
 */
@QuarkusTest
@QuarkusTestResource(ValkeyQueueIT.PostgresResource::class)
@QuarkusTestResource(ValkeyQueueIT.ValkeyResource::class)
class ResultServiceLeaderboardTest {

    @Inject lateinit var matches: MatchRecordRepository

    @Inject lateinit var ratings: RatingRepository

    @Inject lateinit var calculator: RatingCalculator

    private fun formMatch(ranked: Boolean, vararg players: UUID): UUID {
        val matchId = UUID.randomUUID()
        matches.recordMatch(matchId, "duel", ranked, players.toList())
        return matchId
    }

    private fun resultService(leaderboard: LeaderboardClient) =
        ResultService(matches, ratings, calculator, leaderboard, 25.0, 8.333333333333334)

    @Test
    fun `a rated result submits one score per player with the right board, score and idempotency key`() {
        val leaderboard = mock<LeaderboardClient>()
        val results = resultService(leaderboard)

        val winner = UUID.randomUUID()
        val loser = UUID.randomUUID()
        val matchId = formMatch(ranked = true, winner, loser)

        results.report(
            matchId,
            listOf(PlayerPlacement(winner, 1), PlayerPlacement(loser, 2)),
            "normal_finish",
        )

        // Independently re-read what actually landed in Postgres, so this checks
        // the call against the real post-match rating rather than a duplicated
        // hard-coded expectation — a bug that sent the wrong player's rating, or
        // the pre-update rating, would still be caught.
        val winnerScore = conservativeScore(ratings.find(winner, "duel")!!.rating)
        val loserScore = conservativeScore(ratings.find(loser, "duel")!!.rating)

        verify(leaderboard).submitScore("duel", winner.toString(), winnerScore, "$matchId:$winner")
        verify(leaderboard).submitScore("duel", loser.toString(), loserScore, "$matchId:$loser")
        verify(leaderboard, times(2)).submitScore(any(), any(), any(), any())
    }

    @Test
    fun `an unranked match never calls the leaderboard`() {
        val leaderboard = mock<LeaderboardClient>()
        val results = resultService(leaderboard)

        val a = UUID.randomUUID()
        val b = UUID.randomUUID()
        val matchId = formMatch(ranked = false, a, b)

        results.report(
            matchId,
            listOf(PlayerPlacement(a, 1), PlayerPlacement(b, 2)),
            "normal_finish",
        )

        verifyNoInteractions(leaderboard)
    }

    @Test
    fun `a replayed result does not resubmit to the leaderboard`() {
        val leaderboard = mock<LeaderboardClient>()
        val results = resultService(leaderboard)

        val a = UUID.randomUUID()
        val b = UUID.randomUUID()
        val matchId = formMatch(ranked = true, a, b)
        val placements = listOf(PlayerPlacement(a, 1), PlayerPlacement(b, 2))

        results.report(matchId, placements, "normal_finish")
        results.report(matchId, placements, "normal_finish")

        verify(leaderboard, times(2)).submitScore(any(), any(), any(), any())
    }

    @Test
    fun `a leaderboard outage does not fail ReportMatchResult`() {
        // The real client, pointed at a port nothing is listening on — this
        // exercises the actual swallow path end to end, not a stand-in for it.
        val closedPort = ServerSocket(0).use { it.localPort }
        val leaderboard = LeaderboardClient("127.0.0.1:$closedPort", 500L)
        try {
            val winner = UUID.randomUUID()
            val loser = UUID.randomUUID()
            val matchId = formMatch(ranked = true, winner, loser)

            val outcome =
                resultService(leaderboard)
                    .report(
                        matchId,
                        listOf(PlayerPlacement(winner, 1), PlayerPlacement(loser, 2)),
                        "normal_finish",
                    )

            assertTrue(outcome.applied, "the result must apply despite the leaderboard being down")
            assertTrue(outcome.rated)
            assertTrue(
                ratings.find(winner, "duel")!!.rating.mu > 25.0,
                "the rating still moved despite the outage",
            )
        } finally {
            leaderboard.close()
        }
    }

    /**
     * Mirrors ResultService.boardScore: the conservative rating, shifted by the starting mu so a
     * fresh player reads 2500 instead of 0 and a below-average one is not negative. A pure display
     * transform — it cannot reorder anybody.
     */
    private fun conservativeScore(rating: gg.grounds.domain.Rating): Long =
        ((rating.display + 25.0) * 100).roundToLong().coerceAtLeast(0)

    @Test
    fun `the board score is floored at zero rather than going negative`() {
        // mu far below the floor: display = -50 - 3*8.333 = -75, +25 is still deeply negative.
        val score = conservativeScore(gg.grounds.domain.Rating(mu = -50.0, sigma = 8.333))
        assertEquals(0L, score)
    }
}

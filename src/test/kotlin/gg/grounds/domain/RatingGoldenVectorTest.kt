package gg.grounds.domain

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * Pins our Weng-Lin implementation to the reference one.
 *
 * Every expected number below was produced by openskill.py 6.2.0 (`PlackettLuce()`, stock defaults
 * mu=25, sigma=25/3, beta=25/6, tau=25/300) and is reproduced here to 1e-9. openskill.py is the
 * reference implementation the paper's authors point at, so if this test ever goes red after a
 * dependency bump, the JVM library has drifted from the reference — the correct response is to pin
 * the old version, not to update these numbers. Silently re-baselining them would move every
 * player's rating.
 *
 * Regenerate (only to ADD cases, never to fix a failure):
 *
 * pip install openskill==6.2.0 python -c " from openskill.models import PlackettLuce m =
 * PlackettLuce() out = m.rate([[m.rating()],[m.rating()]], ranks=[1,2]) print([(p.mu, p.sigma) for
 * t in out for p in t])"
 */
class RatingGoldenVectorTest {
    private val calc = RatingCalculator()
    private val eps = 1e-9

    private fun fresh() = Rating(25.0, 25.0 / 3.0)

    private fun assertRating(
        expectedMu: Double,
        expectedSigma: Double,
        actual: Rating,
        who: String,
    ) {
        assertEquals(expectedMu, actual.mu, eps, "$who mu")
        assertEquals(expectedSigma, actual.sigma, eps, "$who sigma")
    }

    @Test
    fun `1v1 between fresh players, team0 wins`() {
        val out =
            calc.rate(
                teams = listOf(TeamResult(listOf("a"), 1), TeamResult(listOf("b"), 2)),
                ratings = mapOf("a" to fresh(), "b" to fresh()),
            )

        assertRating(27.6353894931, 8.0659014135, out.getValue("a"), "winner")
        assertRating(22.3646105069, 8.0659014135, out.getValue("b"), "loser")
    }

    @Test
    fun `4-player free-for-all, distinct placements`() {
        // The Mob Rush shape: one player per team, ranked 1st to 4th.
        val out =
            calc.rate(
                teams = (0..3).map { TeamResult(listOf("p$it"), it + 1) },
                ratings = (0..3).associate { "p$it" to fresh() },
            )

        assertRating(27.7952526725, 8.2635717913, out.getValue("p0"), "1st")
        assertRating(26.5529181514, 8.1796179884, out.getValue("p1"), "2nd")
        assertRating(24.6894163697, 8.0841278802, out.getValue("p2"), "3rd")
        assertRating(20.9624128064, 8.0841278802, out.getValue("p3"), "4th")
    }

    @Test
    fun `2v2 with established ratings, underdog team wins`() {
        // Mixed certainties: x0 is a settled strong player (low sigma),
        // y0 is brand new. Beating a strong team should move the new
        // player a lot and the settled one barely.
        val out =
            calc.rate(
                teams =
                    listOf(TeamResult(listOf("x0", "x1"), 2), TeamResult(listOf("y0", "y1"), 1)),
                ratings =
                    mapOf(
                        "x0" to Rating(30.0, 2.0),
                        "x1" to Rating(20.0, 5.0),
                        "y0" to Rating(25.0, 8.333333333333334),
                        "y1" to Rating(27.5, 3.0),
                    ),
            )

        assertRating(29.8495351935, 1.9985826220, out.getValue("x0"), "x0")
        assertRating(19.0609640064, 4.9513355382, out.getValue("x1"), "x1")
        assertRating(27.6079697228, 7.9511464761, out.getValue("y0"), "y0")
        assertRating(27.8382198511, 2.9836475502, out.getValue("y1"), "y1")
    }

    @Test
    fun `3-way free-for-all with a tie for first`() {
        // Ties are expressed by repeating a placement. Both winners must
        // come out identical, and neither may gain as much as a clean win.
        val out =
            calc.rate(
                teams =
                    listOf(
                        TeamResult(listOf("q0"), 1),
                        TeamResult(listOf("q1"), 1),
                        TeamResult(listOf("q2"), 3),
                    ),
                ratings = (0..2).associate { "q$it" to fresh() },
            )

        assertRating(25.7172621702, 8.2052433774, out.getValue("q0"), "tied 1st")
        assertRating(25.7172621702, 8.2052433774, out.getValue("q1"), "tied 1st")
        assertRating(23.5654756596, 8.2052433774, out.getValue("q2"), "3rd")
    }

    @Test
    fun `display rating is conservative — a fresh player shows zero`() {
        assertEquals(0.0, fresh().display, eps)
    }
}

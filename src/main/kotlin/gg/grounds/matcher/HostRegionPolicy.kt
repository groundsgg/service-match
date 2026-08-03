package gg.grounds.matcher

import gg.grounds.domain.Ticket

/**
 * Where a match gets played, given where its players are.
 *
 * The queue spans regions so that two players in different ones can meet; this is the other half of
 * that decision — having matched them, one region has to host, and somebody is going to travel.
 *
 * **The objective is the worst-off player, not the average.** A 2-player match split across
 * Amsterdam and Frankfurt costs one of them ~10ms either way; a 4-player match with three in one
 * region and one in the other should not send the three across to spare the one. Minimising the
 * mean would do exactly that whenever the far group is larger, which is the wrong trade: a player
 * already near the median barely notices ten more milliseconds, and the player at the tail is the
 * one who feels it.
 *
 * With no measured RTT matrix the honest approximation is "host where most of them already are",
 * because a player in the host region pays zero. That is majority rule, and it is exact for the
 * case that actually occurs today — everyone in one region, or a clean split. [ROUND_TRIP_MS] is
 * where a real measured matrix replaces the tie-break; the shape of this function does not change
 * when it does.
 */
object HostRegionPolicy {

    /**
     * Estimated round-trip between two regions, in milliseconds.
     *
     * Deliberately sparse: only the pairs that exist. An unknown pair falls back to
     * [UNKNOWN_RTT_MS], which is high enough that a region nobody declared never wins a tie by
     * accident.
     */
    private val ROUND_TRIP_MS: Map<Pair<String, String>, Int> =
        mapOf(
            ("nl-ams1" to "de-fra1") to 10,
            ("nl-ams1" to "fi-hel1") to 30,
            ("de-fra1" to "fi-hel1") to 25,
        )

    private const val UNKNOWN_RTT_MS = 200
    private const val SAME_REGION_MS = 0

    internal fun roundTrip(from: String, to: String): Int {
        if (from == to) return SAME_REGION_MS
        return ROUND_TRIP_MS[from to to] ?: ROUND_TRIP_MS[to to from] ?: UNKNOWN_RTT_MS
    }

    /**
     * Pick the host region for a set of matched tickets.
     *
     * @param fallback used when no ticket carries a location — an older ticket written before
     *   locations existed, or a caller that sent no hint. The matcher passes its own region, so the
     *   match stays where it was formed rather than landing somewhere arbitrary.
     */
    fun choose(tickets: List<Ticket>, fallback: String): String {
        val locations = tickets.map { it.location }.filter { it.isNotBlank() }
        if (locations.isEmpty()) return fallback

        val candidates = locations.distinct().sorted()
        if (candidates.size == 1) return candidates.first()

        // Score every candidate by the player it treats worst, and only then by
        // the total. Sorting the candidates first makes ties deterministic —
        // two matchers in different regions must reach the same answer for the
        // same match, or they would disagree about who allocates it.
        return candidates.minBy { candidate ->
            val trips = locations.map { roundTrip(it, candidate) }
            Score(worst = trips.max(), total = trips.sum(), region = candidate)
        }
    }

    private data class Score(val worst: Int, val total: Int, val region: String) :
        Comparable<Score> {
        override fun compareTo(other: Score): Int =
            compareValuesBy(this, other, Score::worst, Score::total, Score::region)
    }
}

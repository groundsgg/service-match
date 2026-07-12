package gg.grounds.domain

import io.github.toveri.openskill.Match
import io.github.toveri.openskill.RateOptions
import io.github.toveri.openskill.Rating as OsRating
import io.github.toveri.openskill.models.PlackettLuce
import jakarta.enterprise.context.ApplicationScoped

/** A player's skill estimate for one mode. */
data class Rating(val mu: Double, val sigma: Double) {
    /**
     * Conservative rating shown to players: the model is 99.7% sure the player is at least this
     * good. A fresh player shows ~0 and climbs as sigma shrinks, so a newcomer cannot appear on top
     * of a ladder.
     */
    val display: Double
        get() = mu - 3 * sigma
}

/** One team's players, in the placement they finished. */
data class TeamResult(val playerIds: List<String>, val placement: Int)

/**
 * Weng-Lin (OpenSkill) rating math, via the Plackett-Luce model.
 *
 * Plackett-Luce, not Bradley-Terry: it handles N teams and free-for-all placements natively, which
 * is the shape Grounds' minigames actually have (Mob Rush is a 60s FFA). Bradley-Terry is pairwise
 * and would need the multi-team case decomposed by hand.
 *
 * The library is pinned to openskill.py's numbers by RatingGoldenVectorTest — see that test before
 * changing the model, the tau/beta defaults, or the library version.
 */
@ApplicationScoped
class RatingCalculator {
    private val model = PlackettLuce()

    /**
     * Apply one match's outcome. Ranks are 1-based placements, lower is better, and ties are
     * expressed by repeating a placement (two teams both placed 1st).
     *
     * Returns the new rating per player, keyed by player id.
     */
    fun rate(teams: List<TeamResult>, ratings: Map<String, Rating>): Map<String, Rating> {
        require(teams.size >= 2) { "a match needs at least two teams, got ${teams.size}" }

        val osTeams =
            teams.map { team ->
                team.playerIds.map { id ->
                    val r = ratings[id] ?: error("no rating supplied for player $id")
                    OsRating(r.mu, r.sigma)
                }
            }
        val ranks = teams.map { it.placement.toDouble() }

        val updated = model.rate(Match(osTeams), RateOptions(ranks)).teams

        return buildMap {
            teams.forEachIndexed { ti, team ->
                team.playerIds.forEachIndexed { pi, id ->
                    val r = updated[ti][pi]
                    put(id, Rating(r.mu, r.sigma))
                }
            }
        }
    }
}

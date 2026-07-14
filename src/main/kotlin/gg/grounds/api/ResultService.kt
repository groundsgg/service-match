package gg.grounds.api

import gg.grounds.domain.MatchRecordRepository
import gg.grounds.domain.PlayerPlacement
import gg.grounds.domain.Rating
import gg.grounds.domain.RatingCalculator
import gg.grounds.domain.RatingRepository
import gg.grounds.domain.RatingTransition
import gg.grounds.domain.ResultOutcome
import gg.grounds.domain.TeamResult
import gg.grounds.leaderboard.LeaderboardClient
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import java.util.UUID
import kotlin.math.roundToLong
import org.eclipse.microprofile.config.inject.ConfigProperty
import org.jboss.logging.Logger

class UnknownMatchException(matchId: String) : RuntimeException("unknown match: $matchId")

class NotInMatchException(playerId: UUID, matchId: UUID) :
    RuntimeException("player $playerId was not in match $matchId")

/**
 * Applies a match result to the ladder.
 *
 * Two things it refuses to do, and both matter:
 *
 * It will not accept a result for a match it never formed. The match record is written at claim
 * time, so an unknown id is not a race — it is a fabrication.
 *
 * It will not place a player who was not in the match. The roster is recorded at claim time too;
 * otherwise a gamemode could name any player it liked and move their rating.
 */
@ApplicationScoped
class ResultService
@Inject
constructor(
    private val matches: MatchRecordRepository,
    private val ratings: RatingRepository,
    private val calculator: RatingCalculator,
    private val leaderboard: LeaderboardClient,
    @param:ConfigProperty(name = "grounds.match.rating.default-mu") private val defaultMu: Double,
    @param:ConfigProperty(name = "grounds.match.rating.default-sigma")
    private val defaultSigma: Double,
) {

    fun report(
        matchId: UUID,
        placements: List<PlayerPlacement>,
        terminationReason: String,
    ): ResultOutcome {
        val match = matches.findMatch(matchId) ?: throw UnknownMatchException(matchId.toString())

        // Cheap rejection before any maths: a replay is the common case on a
        // retry, and there is nothing to compute.
        if (match.ended) {
            return ResultOutcome(applied = false, rated = false)
        }

        for (p in placements) {
            if (p.playerId !in match.playerIds) {
                throw NotInMatchException(p.playerId, matchId)
            }
        }

        // Ranked is decided when the match is formed, never by the reporter —
        // that is what stops a test workspace claiming its matches count.
        val transitions =
            if (match.ranked && placements.size >= 2) {
                computeRatings(match.modeId, placements)
            } else {
                if (!match.ranked) {
                    log.info("Unranked match, recording placements only (match=$matchId)")
                }
                emptyMap()
            }

        val outcome =
            matches.applyResult(
                matchId = matchId,
                modeId = match.modeId,
                placements = placements,
                ratedUpdates = transitions,
                terminationReason = terminationReason,
            )

        // The result is already committed by the time we get here — applyResult
        // has returned. The leaderboard is a nice-to-have on top of a result that
        // is already the source of truth, so this stays outside any transaction:
        // LeaderboardClient never throws, and even if it somehow did, this must
        // not be allowed to undo what Postgres already has.
        if (outcome.applied && outcome.rated) {
            for ((playerId, transition) in transitions) {
                leaderboard.submitScore(
                    boardId = match.modeId,
                    playerId = playerId.toString(),
                    score = boardScore(transition.after),
                    idempotencyKey = "$matchId:$playerId",
                )
            }
        }

        return outcome
    }

    private fun computeRatings(
        modeId: String,
        placements: List<PlayerPlacement>,
    ): Map<UUID, RatingTransition> {
        val before =
            placements.associate { p ->
                p.playerId to
                    (ratings.find(p.playerId, modeId)?.rating ?: Rating(defaultMu, defaultSigma))
            }

        // One player per team: the matchmaker's teams are a matter for the game,
        // but the ladder rates individuals. Players who tied share a placement,
        // and Weng-Lin handles that natively.
        val teams = placements.map { TeamResult(listOf(it.playerId.toString()), it.placement) }
        val ratingsByKey = before.entries.associate { (id, r) -> id.toString() to r }

        val after = calculator.rate(teams, ratingsByKey)

        return placements.associate { p ->
            val key = p.playerId.toString()
            p.playerId to
                RatingTransition(before = before.getValue(p.playerId), after = after.getValue(key))
        }
    }

    companion object {
        private val log: Logger = Logger.getLogger(ResultService::class.java)

        /** OpenSkill's default starting mu. A fresh player lands on 2500. */
        private const val STARTING_MU = 25.0
    }

    /**
     * The number a player sees on the board.
     *
     * The rating itself is [Rating.display] — mu - 3*sigma, the value the model is 99.7% sure the
     * player is at least worth. That is the right quantity to rank by, but it is centred on zero: a
     * fresh player is exactly 0 and anyone below average is negative, which reads like a bug on a
     * leaderboard. Shifting by the starting mu puts a newcomer at 2500 and makes the board look
     * like every ladder anyone has ever seen. It is a pure display transform — strictly increasing,
     * so it cannot change anybody's rank.
     *
     * Floored at zero rather than allowed to go negative: below this a player would have to be
     * worse than mu = 0, which the model treats as barely possible, and a negative score buys
     * nothing.
     */
    private fun boardScore(rating: Rating): Long =
        ((rating.display + STARTING_MU) * 100).roundToLong().coerceAtLeast(0)
}

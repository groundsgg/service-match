package gg.grounds.domain

import java.util.UUID

/** How a player finished. 1-based, lower is better; ties repeat a value. */
data class PlayerPlacement(val playerId: UUID, val placement: Int)

/** The outcome of applying a result. */
data class ResultOutcome(
    /** False when this result had already been recorded — a retry, not an error. */
    val applied: Boolean,
    /** True when the match was ranked and ratings actually moved. */
    val rated: Boolean,
)

/**
 * The durable half: match records and the ratings they move.
 *
 * Everything here has to survive a restart, which is why it is Postgres and not Valkey. Queues are
 * losable; a player's rating is not.
 */
interface MatchRecordRepository {

    /**
     * Written at claim time, before a GameServer even exists. That ordering is what lets the result
     * path reject a result for a match we never formed: there is no legitimate way to report one.
     */
    fun recordMatch(matchId: UUID, modeId: String, ranked: Boolean, playerIds: List<UUID>)

    fun findMatch(matchId: UUID): MatchRecord?

    /**
     * Apply a result: write the rating deltas and move the players' ratings, in one transaction.
     *
     * Idempotent. `rating_update`'s primary key is (match_id, player_id), so a replay inserts
     * nothing and must therefore also move no ratings — which is exactly why the whole thing is one
     * transaction rather than a loop of updates.
     */
    fun applyResult(
        matchId: UUID,
        modeId: String,
        placements: List<PlayerPlacement>,
        ratedUpdates: Map<UUID, RatingTransition>,
        terminationReason: String,
    ): ResultOutcome
}

/** A player's rating before and after a match. */
data class RatingTransition(val before: Rating, val after: Rating)

data class MatchRecord(
    val matchId: UUID,
    val modeId: String,
    val ranked: Boolean,
    val ended: Boolean,
    val playerIds: List<UUID>,
)

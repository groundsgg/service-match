package gg.grounds.api

import gg.grounds.domain.BandConfig
import gg.grounds.domain.Rating
import gg.grounds.domain.ResultOutcome
import gg.grounds.domain.StoredRating
import gg.grounds.domain.Ticket
import org.eclipse.microprofile.openapi.annotations.media.Schema

// ---------------------------------------------------------------
// Tickets
// ---------------------------------------------------------------

@Schema(description = "Ask for a match in a mode.")
data class EnqueueRequestBody(
    @get:Schema(
        description = "Player UUID — the Keycloak identity, stable across sessions.",
        required = true,
    )
    val playerId: String?,
    @get:Schema(description = "Mode within the project.", examples = ["bedwars-squads"])
    val modeId: String?,
)

@Schema(description = "The ticket a player now holds.")
data class EnqueueResponse(
    val ticketId: String,
    @get:Schema(
        description =
            "The player's current mu for this mode, so a client can show a rating without a " +
                "second round trip."
    )
    val mu: Double,
    @get:Schema(description = "How many tickets are queued for the mode, this one included.")
    val queuePosition: Int,
)

@Schema(description = "Whether a ticket was withdrawn.")
data class CancelTicketResponse(
    @get:Schema(
        description =
            "False when the ticket was already past QUEUED — a match had formed — or is not " +
                "this player's. A legitimate answer, not an error."
    )
    val cancelled: Boolean
)

@Schema(description = "Where a ticket stands.")
data class TicketResponse(
    @get:Schema(
        description = "QUEUED, MATCHED, ASSIGNED, CANCELLED or FAILED.",
        examples = ["QUEUED"],
    )
    val state: String,
    @get:Schema(description = "Set exactly when state is ASSIGNED.", nullable = true)
    val assignment: AssignmentResponse?,
    val queueDepth: Int,
) {
    companion object {
        fun from(ticket: Ticket, queueDepth: Long) =
            TicketResponse(
                state = ticket.state.name,
                assignment =
                    ticket.assignment?.let {
                        AssignmentResponse(
                            matchId = ticket.matchId.orEmpty(),
                            gameServerName = it.gameServerName,
                            address = it.address,
                            port = it.port,
                        )
                    },
                queueDepth = queueDepth.toInt(),
            )
    }
}

@Schema(description = "The server a matched player should be routed to.")
data class AssignmentResponse(
    val matchId: String,
    @get:Schema(
        description = "The Agones GameServer name, which is also the Velocity backend name."
    )
    val gameServerName: String,
    @get:Schema(
        description =
            "Fallback route. The routing pipeline resolves the name; the raw endpoint ships " +
                "alongside it for the case where it has not."
    )
    val address: String,
    val port: Int,
)

// ---------------------------------------------------------------
// Ratings and queue stats
// ---------------------------------------------------------------

@Schema(
    description = "A player's Weng-Lin rating for one mode. Ratings are global, not per region."
)
data class RatingResponse(
    val mu: Double,
    val sigma: Double,
    @get:Schema(description = "Conservative display rating, mu - 3*sigma.") val display: Double,
    val gamesPlayed: Int,
) {
    companion object {
        fun from(stored: StoredRating?, fallback: Rating) =
            (stored?.rating ?: fallback).let {
                RatingResponse(
                    mu = it.mu,
                    sigma = it.sigma,
                    display = it.display,
                    gamesPlayed = stored?.gamesPlayed ?: 0,
                )
            }
    }
}

@Schema(description = "Queue depth for a mode, for UI.")
data class QueueStatsResponse(
    val ticketsQueued: Int,
    @get:Schema(description = "False when the mode has no matchmaking config — nobody can queue.")
    val available: Boolean,
)

// ---------------------------------------------------------------
// Mode configuration
// ---------------------------------------------------------------

@Schema(
    description =
        "A mode's matchmaking configuration, as forge pushes it from the `matchmaking:` block " +
            "in grounds.yaml. There is no hot reload: the next queue tick picks it up."
)
data class UpsertQueueRequestBody(
    @get:Schema(description = "Players per team. At least 1.") val teamSize: Int?,
    @get:Schema(description = "Teams per match. At least 2 — a match needs an opponent.")
    val teamCount: Int?,
    @get:Schema(
        description =
            "Whether results move ratings. Decided here, at configuration time, and never by " +
                "the result call — that is what stops an ephemeral test workspace polluting " +
                "the ladder."
    )
    val ranked: Boolean = false,
    @get:Schema(description = "Banding parameters. Omit for the defaults.", nullable = true)
    val band: BandRequestBody? = null,
    @get:Schema(
        description =
            "The Agones Fleet that serves this mode. Omit when the fleet is named after the " +
                "mode, which is the usual case. Set it when one image serves several modes — a " +
                "duel server builds a different arena per mode and runs all of them from one " +
                "pool, and a fleet per mode would idle a set of servers for each.",
        nullable = true,
    )
    val fleetName: String? = null,
)

@Schema(
    description =
        "band(t) = max(b0, k*sigma) + w*floor(waitSeconds/sSeconds). Any field that is absent, " +
            "null or not positive falls back to its default — the same rule the gRPC surface " +
            "applies to a zero, so a caller porting its code keeps the behaviour it had."
)
data class BandRequestBody(
    val b0: Double? = null,
    val k: Double? = null,
    val w: Double? = null,
    val sSeconds: Int? = null,
    @get:Schema(
        description = "After this wait the anchor's band goes unbounded — the whole ladder."
    )
    val mercySeconds: Int? = null,
    @get:Schema(description = "After this wait the anchor stops requiring mutual band overlap.")
    val mutualSeconds: Int? = null,
) {
    fun toDomain(): BandConfig {
        val defaults = BandConfig()
        return BandConfig(
            b0 = b0?.takeIf { it > 0 } ?: defaults.b0,
            k = k?.takeIf { it > 0 } ?: defaults.k,
            w = w?.takeIf { it > 0 } ?: defaults.w,
            stepSeconds = sSeconds?.takeIf { it > 0 } ?: defaults.stepSeconds,
            mercySeconds = mercySeconds?.takeIf { it > 0 } ?: defaults.mercySeconds,
            mutualSeconds = mutualSeconds?.takeIf { it > 0 } ?: defaults.mutualSeconds,
        )
    }
}

@Schema(description = "Whether the upsert created the mode or replaced an existing config.")
data class UpsertQueueResponse(val created: Boolean)

// ---------------------------------------------------------------
// Results
// ---------------------------------------------------------------

@Schema(description = "How a match ended.")
data class ReportResultRequestBody(
    @get:Schema(description = "One entry per player. At least one.")
    val results: List<PlayerResultBody>?,
    @get:Schema(
        description = "Stored for operators, never interpreted. Defaults to `normal_finish`.",
        examples = ["normal_finish", "abandoned", "forfeit", "server_crashed"],
        nullable = true,
    )
    val terminationReason: String? = null,
)

@Schema(description = "Where one player finished.")
data class PlayerResultBody(
    val playerId: String?,
    @get:Schema(
        description =
            "1-based finishing position, lower is better. Ties repeat a placement. Weng-Lin " +
                "needs a ranking rather than a win/loss bit: without one a multi-team result " +
                "degrades to winner-versus-rest, which biases the ladder."
    )
    val placement: Int?,
)

@Schema(description = "What the result did.")
data class ReportResultResponse(
    @get:Schema(
        description =
            "False when this result had already been recorded — the call was a retry, not an " +
                "error."
    )
    val applied: Boolean,
    @get:Schema(description = "True when the match was ranked and ratings actually moved.")
    val rated: Boolean,
) {
    companion object {
        fun from(outcome: ResultOutcome) =
            ReportResultResponse(applied = outcome.applied, rated = outcome.rated)
    }
}

// ---------------------------------------------------------------
// Errors
// ---------------------------------------------------------------

@Schema(description = "RFC 9457 problem details.")
data class ProblemDetails(
    val title: String,
    val status: Int,
    val detail: String?,
    @get:Schema(
        description = "Stable machine-readable code.",
        examples = ["ticket_exists", "unknown_mode", "unknown_match", "not_in_match"],
    )
    val code: String,
)

/** The problem+json media type, in one place because four mappers and a filter all set it. */
const val PROBLEM_JSON: String = "application/problem+json"

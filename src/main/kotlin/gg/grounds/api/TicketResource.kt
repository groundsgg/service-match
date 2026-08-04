package gg.grounds.api

import io.smallrye.common.annotation.Blocking
import jakarta.inject.Inject
import jakarta.ws.rs.Consumes
import jakarta.ws.rs.DELETE
import jakarta.ws.rs.GET
import jakarta.ws.rs.POST
import jakarta.ws.rs.Path
import jakarta.ws.rs.PathParam
import jakarta.ws.rs.Produces
import jakarta.ws.rs.QueryParam
import jakarta.ws.rs.core.MediaType
import java.util.UUID
import org.eclipse.microprofile.openapi.annotations.Operation
import org.eclipse.microprofile.openapi.annotations.media.Content
import org.eclipse.microprofile.openapi.annotations.media.Schema
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse
import org.eclipse.microprofile.openapi.annotations.responses.APIResponses
import org.eclipse.microprofile.openapi.annotations.security.SecurityRequirement
import org.eclipse.microprofile.openapi.annotations.tags.Tag
import org.jboss.resteasy.reactive.ResponseStatus

/**
 * Queueing, withdrawing and reading a ticket — the proxy's side of the matchmaker.
 *
 * A thin translator, like the gRPC surface next to it: [QueueService] owns the invariants, this
 * class owns argument validation and HTTP status codes.
 *
 * Every method is explicitly `@Blocking`. Valkey and Postgres calls block, and this repository has
 * already paid once for finding that out at runtime rather than in a test — see the comment on
 * `MatchGrpcService`.
 */
@Path("/v1/match/tickets")
@Produces(MediaType.APPLICATION_JSON)
@Tag(name = "Tickets", description = "Queue for a match, withdraw, and read where a ticket stands.")
@SecurityRequirement(name = "bearerAuth")
class TicketResource @Inject constructor(private val queue: QueueService) {

    @POST
    @Consumes(MediaType.APPLICATION_JSON)
    @Blocking
    @ResponseStatus(201)
    @Operation(
        summary = "Queue a player for a mode",
        description =
            "Rejected with 409 if the player already holds a live ticket — one live ticket per " +
                "player, worldwide, is what stops a player being committed to two matches.\n\n" +
                "There is deliberately no project or region in the body. service-match runs " +
                "inside the project's own vCluster, so the vCluster is the tenancy boundary: a " +
                "ticket cannot address another project because it cannot reach another " +
                "project's matchmaker. Accepting either field here would hand that boundary to " +
                "the caller.",
    )
    @APIResponses(
        APIResponse(
            responseCode = "201",
            description = "The player is queued.",
            content =
                [
                    Content(
                        mediaType = MediaType.APPLICATION_JSON,
                        schema = Schema(implementation = EnqueueResponse::class),
                    )
                ],
        ),
        APIResponse(
            responseCode = "400",
            description = "playerId or modeId is missing or not a UUID.",
        ),
        APIResponse(responseCode = "401", description = "Authentication is missing or invalid."),
        APIResponse(responseCode = "404", description = "The mode has no matchmaking config."),
        APIResponse(responseCode = "409", description = "The player already holds a live ticket."),
    )
    fun enqueue(body: EnqueueRequestBody?): EnqueueResponse {
        val request = body ?: throw InvalidRequestException("a request body is required")
        val playerId = parsePlayerId(request.playerId)
        val modeId = requireNonBlank(request.modeId, "modeId")

        val ticket = queue.enqueue(playerId, modeId)
        return EnqueueResponse(
            ticketId = ticket.id,
            mu = ticket.mu,
            queuePosition = queue.queueDepth(modeId).toInt(),
        )
    }

    @DELETE
    @Path("/{ticketId}")
    @Blocking
    @Operation(
        summary = "Withdraw a ticket",
        description =
            "Only succeeds while the ticket is still QUEUED — once a match has formed the " +
                "ticket is committed. `cancelled: false` is the answer for that case and for a " +
                "ticket that is not this player's; neither is an error.",
    )
    @APIResponses(
        APIResponse(
            responseCode = "200",
            description = "Whether the ticket was withdrawn.",
            content =
                [
                    Content(
                        mediaType = MediaType.APPLICATION_JSON,
                        schema = Schema(implementation = CancelTicketResponse::class),
                    )
                ],
        ),
        APIResponse(
            responseCode = "400",
            description = "ticketId or playerId is missing or malformed.",
        ),
        APIResponse(responseCode = "401", description = "Authentication is missing or invalid."),
    )
    fun cancel(
        @PathParam("ticketId") ticketId: String?,
        @QueryParam("playerId")
        @Schema(description = "Checked against the ticket's owner.", required = true)
        playerId: String?,
    ): CancelTicketResponse =
        CancelTicketResponse(
            queue.cancel(requireNonBlank(ticketId, "ticketId"), parsePlayerId(playerId))
        )

    @GET
    @Path("/{ticketId}")
    @Blocking
    @Operation(
        summary = "Read a ticket",
        description =
            "The source of truth on reconnect, and the only authority on an assignment: the " +
                "NATS assignment push is a wakeup, this is the answer.",
    )
    @APIResponses(
        APIResponse(
            responseCode = "200",
            description = "The ticket's state, and its assignment once it has one.",
            content =
                [
                    Content(
                        mediaType = MediaType.APPLICATION_JSON,
                        schema = Schema(implementation = TicketResponse::class),
                    )
                ],
        ),
        APIResponse(responseCode = "401", description = "Authentication is missing or invalid."),
        APIResponse(responseCode = "404", description = "No such ticket."),
    )
    fun get(@PathParam("ticketId") ticketId: String?): TicketResponse {
        val id = requireNonBlank(ticketId, "ticketId")
        val ticket = queue.findTicket(id) ?: throw UnknownTicketException(id)
        return TicketResponse.from(ticket, queue.queueDepth(ticket.modeId))
    }
}

/** Raised when a ticket id does not resolve. Rendered as 404 by [UnknownTicketMapper]. */
class UnknownTicketException(ticketId: String) : RuntimeException("no such ticket: $ticketId")

internal fun parsePlayerId(raw: String?): UUID {
    val value = requireNonBlank(raw, "playerId")
    return try {
        UUID.fromString(value)
    } catch (_: IllegalArgumentException) {
        throw InvalidRequestException("playerId is not a UUID: $value")
    }
}

internal fun requireNonBlank(value: String?, field: String): String {
    if (value.isNullOrBlank()) throw InvalidRequestException("$field is required")
    return value
}

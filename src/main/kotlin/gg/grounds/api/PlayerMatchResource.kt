package gg.grounds.api

import io.smallrye.common.annotation.Blocking
import jakarta.inject.Inject
import jakarta.ws.rs.Consumes
import jakarta.ws.rs.DELETE
import jakarta.ws.rs.GET
import jakarta.ws.rs.NotFoundException
import jakarta.ws.rs.PUT
import jakarta.ws.rs.Path
import jakarta.ws.rs.PathParam
import jakarta.ws.rs.Produces
import jakarta.ws.rs.core.MediaType
import org.eclipse.microprofile.openapi.annotations.Operation
import org.eclipse.microprofile.openapi.annotations.media.Content
import org.eclipse.microprofile.openapi.annotations.media.Schema
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse
import org.eclipse.microprofile.openapi.annotations.responses.APIResponses
import org.eclipse.microprofile.openapi.annotations.security.SecurityRequirement
import org.eclipse.microprofile.openapi.annotations.tags.Tag

/**
 * The match a player is playing — what lets a player who dropped off the network be offered it
 * again, through whichever proxy they log back in on.
 *
 * A proxy only remembers the players it holds, and a reconnect is dealt to any proxy. So the
 * assignment itself records where each player went (assign.lua), and this is where a proxy reads it
 * back. The match ending, the player leaving it, and the rejoin grace running out all remove it.
 */
@Path("/v1/match/players/{playerId}/match")
@Produces(MediaType.APPLICATION_JSON)
@Tag(name = "Rejoin", description = "The match a player is playing, for offering a rejoin.")
@SecurityRequirement(name = "bearerAuth")
class PlayerMatchResource @Inject constructor(private val queue: QueueService) {

    @GET
    @Blocking
    @Operation(
        summary = "Read the match a player is playing",
        description =
            "404 is the ordinary answer: the player is in no match, it has ended, they left it, " +
                "or they were away longer than the rejoin grace.",
    )
    @APIResponses(
        APIResponse(
            responseCode = "200",
            description = "The player's running match.",
            content =
                [
                    Content(
                        mediaType = MediaType.APPLICATION_JSON,
                        schema = Schema(implementation = ActiveMatchResponse::class),
                    )
                ],
        ),
        APIResponse(responseCode = "400", description = "playerId is not a UUID."),
        APIResponse(responseCode = "401", description = "Authentication is missing or invalid."),
        APIResponse(responseCode = "404", description = "The player has no running match."),
    )
    fun get(@PathParam("playerId") playerId: String?): ActiveMatchResponse {
        val ticket = queue.activeMatch(parsePlayerId(playerId)) ?: throw NotFoundException()
        return ActiveMatchResponse.from(ticket)
    }

    @DELETE
    @Blocking
    @Operation(
        summary = "Leave the match for good",
        description =
            "The player went back to a lobby, or the match server sent them away. Idempotent.",
    )
    @APIResponses(
        APIResponse(responseCode = "204", description = "No match is offered to the player."),
        APIResponse(responseCode = "400", description = "playerId is not a UUID."),
        APIResponse(responseCode = "401", description = "Authentication is missing or invalid."),
        APIResponse(
            responseCode = "503",
            description = "Valkey is out of connections; retry after the Retry-After seconds.",
        ),
    )
    fun leave(@PathParam("playerId") playerId: String?) {
        queue.leaveMatch(parsePlayerId(playerId))
    }

    @PUT
    @Path("/presence")
    @Consumes(MediaType.APPLICATION_JSON)
    @Blocking
    @Operation(
        summary = "Report the player dropping off, or coming back",
        description =
            "`connected: false` keeps the match for the rejoin grace only; `connected: true` " +
                "gives it the lifetime of the match again.",
    )
    @APIResponses(
        APIResponse(responseCode = "204", description = "Recorded."),
        APIResponse(
            responseCode = "400",
            description = "playerId is not a UUID, or connected is missing.",
        ),
        APIResponse(responseCode = "401", description = "Authentication is missing or invalid."),
        APIResponse(responseCode = "404", description = "The player has no running match."),
    )
    fun presence(@PathParam("playerId") playerId: String?, body: MatchPresenceRequestBody?) {
        val player = parsePlayerId(playerId)
        val connected = body?.connected ?: throw InvalidRequestException("connected is required")
        if (!queue.matchPresence(player, connected)) throw NotFoundException()
    }
}

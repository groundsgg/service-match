package gg.grounds.api

import io.smallrye.common.annotation.Blocking
import jakarta.inject.Inject
import jakarta.ws.rs.Consumes
import jakarta.ws.rs.POST
import jakarta.ws.rs.Path
import jakarta.ws.rs.PathParam
import jakarta.ws.rs.Produces
import jakarta.ws.rs.core.MediaType
import java.util.UUID
import org.eclipse.microprofile.openapi.annotations.Operation
import org.eclipse.microprofile.openapi.annotations.media.Content
import org.eclipse.microprofile.openapi.annotations.media.Schema
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse
import org.eclipse.microprofile.openapi.annotations.responses.APIResponses
import org.eclipse.microprofile.openapi.annotations.security.SecurityRequirement
import org.eclipse.microprofile.openapi.annotations.tags.Tag

/**
 * A game server reports that the round it was preparing is ready — or that it could not be — and
 * learns whether the players are coming.
 *
 * Only a server that answered StartMatch with `signals_ready` calls this; until it does, its
 * match's tickets stay PREPARING and nobody is routed. Workload-authenticated like the result call
 * next to it, by [WorkloadAuthFilter].
 */
@Path("/v1/match/matches/{matchId}/ready")
@Produces(MediaType.APPLICATION_JSON)
@Tag(name = "Results", description = "Report a finished match and move the ladder.")
@SecurityRequirement(name = "bearerAuth")
class MatchReadyResource @Inject constructor(private val queue: QueueService) {

    @POST
    @Consumes(MediaType.APPLICATION_JSON)
    @Blocking
    @Operation(
        summary = "Report a prepared round ready",
        description =
            "`ok: true` on a match that is PREPARING on this server routes its players " +
                "(`routed: true`; a retry once routed answers the same). `ok: false` puts them " +
                "back on the queue with the wait they had earned. A match that is no longer " +
                "waiting for this server — timed out, failed, left by too many players, or " +
                "unknown — answers `routed: false`, and the server should discard the round.",
    )
    @APIResponses(
        APIResponse(
            responseCode = "200",
            description = "Whether the players are being sent.",
            content =
                [
                    Content(
                        mediaType = MediaType.APPLICATION_JSON,
                        schema = Schema(implementation = MatchReadyResponse::class),
                    )
                ],
        ),
        APIResponse(
            responseCode = "400",
            description = "matchId is not a UUID, or gameServerName or ok is missing.",
        ),
        APIResponse(responseCode = "401", description = "Authentication is missing or invalid."),
        APIResponse(responseCode = "409", description = "The match is on another server."),
    )
    fun ready(
        @PathParam("matchId") matchId: String?,
        body: MatchReadyRequestBody?,
    ): MatchReadyResponse {
        val raw = requireNonBlank(matchId, "matchId")
        // Normalised: match ids are stored as UUID.toString() wrote them.
        val id =
            try {
                UUID.fromString(raw).toString()
            } catch (_: IllegalArgumentException) {
                throw InvalidRequestException("matchId is not a UUID: $raw")
            }
        val request = body ?: throw InvalidRequestException("a request body is required")
        val server = requireNonBlank(request.gameServerName, "gameServerName").trim()
        val ok = request.ok ?: throw InvalidRequestException("ok is required")
        return MatchReadyResponse(queue.ready(id, server, ok, request.reason))
    }
}

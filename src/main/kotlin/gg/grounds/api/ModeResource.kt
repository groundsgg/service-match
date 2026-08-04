package gg.grounds.api

import gg.grounds.domain.ModeConfig
import io.smallrye.common.annotation.Blocking
import jakarta.inject.Inject
import jakarta.ws.rs.Consumes
import jakarta.ws.rs.GET
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
import org.jboss.logging.Logger

/**
 * A mode's matchmaking configuration, and how busy its queue is.
 *
 * The upsert is `PUT` rather than `POST` because it is idempotent on the mode id: a gamemode calls
 * it on every boot with the same body, and doing so must not accumulate anything.
 */
@Path("/v1/match/modes/{modeId}")
@Produces(MediaType.APPLICATION_JSON)
@Tag(name = "Modes", description = "Matchmaking configuration and queue depth per mode.")
@SecurityRequirement(name = "bearerAuth")
class ModeResource @Inject constructor(private val queue: QueueService) {

    @PUT
    @Path("/queue")
    @Consumes(MediaType.APPLICATION_JSON)
    @Blocking
    @Operation(
        summary = "Create or replace a mode's matchmaking config",
        description =
            "Called by forge on deploy from the `matchmaking:` block in grounds.yaml — a " +
                "trusted server path, never exposed to game clients. There is no hot reload: " +
                "the next queue tick picks the new config up.",
    )
    @APIResponses(
        APIResponse(
            responseCode = "200",
            description = "The config is stored.",
            content =
                [
                    Content(
                        mediaType = MediaType.APPLICATION_JSON,
                        schema = Schema(implementation = UpsertQueueResponse::class),
                    )
                ],
        ),
        APIResponse(responseCode = "400", description = "teamSize or teamCount is out of range."),
        APIResponse(responseCode = "401", description = "Authentication is missing or invalid."),
    )
    fun upsert(
        @PathParam("modeId") modeId: String?,
        body: UpsertQueueRequestBody?,
    ): UpsertQueueResponse {
        val id = requireNonBlank(modeId, "modeId")
        val request = body ?: throw InvalidRequestException("a request body is required")

        val teamSize = request.teamSize ?: throw InvalidRequestException("teamSize is required")
        val teamCount = request.teamCount ?: throw InvalidRequestException("teamCount is required")
        if (teamSize < 1 || teamCount < 2) {
            throw InvalidRequestException("a match needs teamSize >= 1 and teamCount >= 2")
        }

        val config =
            ModeConfig(
                modeId = id,
                teamSize = teamSize,
                teamCount = teamCount,
                ranked = request.ranked,
                band = (request.band ?: BandRequestBody()).toDomain(),
            )

        val created = queue.upsertMode(config)
        log.info(
            "Upserted mode (id=$id, ${config.teamCount}x${config.teamSize}, " +
                "ranked=${config.ranked}, created=$created)"
        )
        return UpsertQueueResponse(created)
    }

    @GET
    @Path("/queue-stats")
    @Blocking
    @Operation(
        summary = "Queue depth for a mode",
        description =
            "`available` is false when the mode has no matchmaking config at all, in which case " +
                "`ticketsQueued` is zero because nobody can be waiting — reporting a depth for " +
                "a mode nobody can queue for would read as a queue that is simply empty.",
    )
    @APIResponses(
        APIResponse(
            responseCode = "200",
            description = "How busy the queue is.",
            content =
                [
                    Content(
                        mediaType = MediaType.APPLICATION_JSON,
                        schema = Schema(implementation = QueueStatsResponse::class),
                    )
                ],
        ),
        APIResponse(responseCode = "401", description = "Authentication is missing or invalid."),
    )
    fun queueStats(@PathParam("modeId") modeId: String?): QueueStatsResponse {
        val id = requireNonBlank(modeId, "modeId")
        val known = queue.isKnownMode(id)
        return QueueStatsResponse(
            ticketsQueued = if (known) queue.queueDepth(id).toInt() else 0,
            available = known,
        )
    }

    private companion object {
        private val log: Logger = Logger.getLogger(ModeResource::class.java)
    }
}

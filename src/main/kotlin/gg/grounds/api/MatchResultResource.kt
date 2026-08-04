package gg.grounds.api

import gg.grounds.domain.PlayerPlacement
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
 * How a match ended — the call that moves the ratings.
 *
 * Idempotent: the durable store keys rating updates on (matchId, playerId), so a retry after a
 * timeout is a no-op rather than a second rating change. Gamemodes should retry.
 */
@Path("/v1/match/matches/{matchId}/result")
@Produces(MediaType.APPLICATION_JSON)
@Tag(name = "Results", description = "Report a finished match and move the ladder.")
@SecurityRequirement(name = "bearerAuth")
class MatchResultResource @Inject constructor(private val results: ResultService) {

    @POST
    @Consumes(MediaType.APPLICATION_JSON)
    @Blocking
    @Operation(
        summary = "Report a match result",
        description =
            "Whether the result actually rates is decided at match-formation time from the mode " +
                "config, never from this call — so an ephemeral test workspace cannot pollute " +
                "the ladder however it reports.",
    )
    @APIResponses(
        APIResponse(
            responseCode = "200",
            description = "The result was accepted, or had already been recorded.",
            content =
                [
                    Content(
                        mediaType = MediaType.APPLICATION_JSON,
                        schema = Schema(implementation = ReportResultResponse::class),
                    )
                ],
        ),
        APIResponse(
            responseCode = "400",
            description = "Malformed ids, empty results, or a placement below 1.",
        ),
        APIResponse(responseCode = "401", description = "Authentication is missing or invalid."),
        APIResponse(responseCode = "403", description = "A named player was not in this match."),
        APIResponse(responseCode = "404", description = "No match with this id was ever formed."),
    )
    fun report(
        @PathParam("matchId") matchId: String?,
        body: ReportResultRequestBody?,
    ): ReportResultResponse {
        val id = parseMatchId(matchId)
        val request = body ?: throw InvalidRequestException("a request body is required")

        val entries = request.results
        if (entries.isNullOrEmpty()) {
            throw InvalidRequestException("a result needs at least one player")
        }

        val placements =
            entries.map { entry ->
                val placement =
                    entry.placement ?: throw InvalidRequestException("placement is required")
                if (placement < 1) {
                    throw InvalidRequestException("placement is 1-based; got $placement")
                }
                PlayerPlacement(parsePlayerId(entry.playerId), placement)
            }

        return ReportResultResponse.from(
            results.report(
                matchId = id,
                placements = placements,
                terminationReason = request.terminationReason?.ifBlank { null } ?: "normal_finish",
            )
        )
    }

    private fun parseMatchId(raw: String?): UUID {
        val value = requireNonBlank(raw, "matchId")
        return try {
            UUID.fromString(value)
        } catch (_: IllegalArgumentException) {
            throw InvalidRequestException("matchId is not a UUID: $value")
        }
    }
}

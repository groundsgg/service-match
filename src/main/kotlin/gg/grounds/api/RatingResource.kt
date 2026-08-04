package gg.grounds.api

import gg.grounds.domain.Rating
import gg.grounds.domain.RatingRepository
import io.smallrye.common.annotation.Blocking
import jakarta.inject.Inject
import jakarta.ws.rs.GET
import jakarta.ws.rs.Path
import jakarta.ws.rs.PathParam
import jakarta.ws.rs.Produces
import jakarta.ws.rs.core.MediaType
import org.eclipse.microprofile.config.inject.ConfigProperty
import org.eclipse.microprofile.openapi.annotations.Operation
import org.eclipse.microprofile.openapi.annotations.media.Content
import org.eclipse.microprofile.openapi.annotations.media.Schema
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse
import org.eclipse.microprofile.openapi.annotations.responses.APIResponses
import org.eclipse.microprofile.openapi.annotations.security.SecurityRequirement
import org.eclipse.microprofile.openapi.annotations.tags.Tag

/**
 * A player's rating for a mode.
 *
 * Ratings are global and player-scoped — there is no per-region ladder, which is why the region
 * appears nowhere in the path.
 */
@Path("/v1/match/players/{playerId}/ratings/{modeId}")
@Produces(MediaType.APPLICATION_JSON)
@Tag(name = "Ratings", description = "Weng-Lin ratings, global per player and mode.")
@SecurityRequirement(name = "bearerAuth")
class RatingResource
@Inject
constructor(
    private val ratings: RatingRepository,
    @param:ConfigProperty(name = "grounds.match.rating.default-mu") private val defaultMu: Double,
    @param:ConfigProperty(name = "grounds.match.rating.default-sigma")
    private val defaultSigma: Double,
) {

    @GET
    @Blocking
    @Operation(
        summary = "Read a player's rating",
        description =
            "A player who has never played this mode is not an error — they are unrated, and " +
                "the answer is the defaults the matchmaker would seed their first ticket with.",
    )
    @APIResponses(
        APIResponse(
            responseCode = "200",
            description = "The rating, or the seeded defaults for an unrated player.",
            content =
                [
                    Content(
                        mediaType = MediaType.APPLICATION_JSON,
                        schema = Schema(implementation = RatingResponse::class),
                    )
                ],
        ),
        APIResponse(
            responseCode = "400",
            description = "playerId is not a UUID, or modeId is empty.",
        ),
        APIResponse(responseCode = "401", description = "Authentication is missing or invalid."),
    )
    fun get(
        @PathParam("playerId") playerId: String?,
        @PathParam("modeId") modeId: String?,
    ): RatingResponse {
        val player = parsePlayerId(playerId)
        val mode = requireNonBlank(modeId, "modeId")
        return RatingResponse.from(ratings.find(player, mode), Rating(defaultMu, defaultSigma))
    }
}

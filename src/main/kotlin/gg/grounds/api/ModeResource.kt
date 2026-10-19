package gg.grounds.api

import gg.grounds.domain.MapSelectionStrategy
import gg.grounds.domain.ModeConfig
import gg.grounds.domain.ModeLease
import gg.grounds.domain.ModeMaps
import gg.grounds.domain.ModeMeta
import gg.grounds.domain.StatBoard
import gg.grounds.security.WorkloadAuthenticator
import io.smallrye.common.annotation.Blocking
import jakarta.inject.Inject
import jakarta.ws.rs.Consumes
import jakarta.ws.rs.DELETE
import jakarta.ws.rs.GET
import jakarta.ws.rs.PUT
import jakarta.ws.rs.Path
import jakarta.ws.rs.PathParam
import jakarta.ws.rs.Produces
import jakarta.ws.rs.core.Context
import jakarta.ws.rs.core.MediaType
import jakarta.ws.rs.core.SecurityContext
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
 *
 * A game server that registers with a `leaseId` keeps its mode alive by repeating the upsert; the
 * mode disappears when the last such lease expires or is released.
 *
 * One resource rooted at `/v1/match/modes` rather than two: JAX-RS picks a single root resource by
 * its path template before it looks at methods, so a second resource at `/v1/match/modes` would
 * never see a request below `/{modeId}`.
 */
@Path("/v1/match/modes")
@Produces(MediaType.APPLICATION_JSON)
@Tag(name = "Modes", description = "Matchmaking configuration and queue depth per mode.")
@SecurityRequirement(name = "bearerAuth")
class ModeResource
@Inject
constructor(private val queue: QueueService, private val authenticator: WorkloadAuthenticator) {

    @GET
    @Blocking
    @Operation(
        summary = "Every live mode",
        description =
            "Permanent modes, and leased modes with at least one unexpired lease, with their " +
                "lobby metadata and queue depth. Sorted by modeId.",
    )
    @APIResponses(
        APIResponse(
            responseCode = "200",
            description = "The live modes.",
            content =
                [
                    Content(
                        mediaType = MediaType.APPLICATION_JSON,
                        schema = Schema(implementation = ModesResponse::class),
                    )
                ],
        ),
        APIResponse(responseCode = "401", description = "Authentication is missing or invalid."),
    )
    fun list(): ModesResponse = ModesResponse(queue.listModes().map { ModeResponse.from(it) })

    @DELETE
    @Path("/{modeId}")
    @Blocking
    @Operation(
        summary = "Remove a mode",
        description =
            "Admin only. Removes the mode whether permanent or leased, and cancels every ticket " +
                "still waiting in its queue.",
    )
    @APIResponses(
        APIResponse(responseCode = "204", description = "The mode is gone."),
        APIResponse(responseCode = "401", description = "Authentication is missing or invalid."),
        APIResponse(responseCode = "403", description = "The caller is not an admin."),
        APIResponse(responseCode = "404", description = "There is no such mode."),
    )
    fun delete(@PathParam("modeId") modeId: String?, @Context security: SecurityContext) {
        val id = requireNonBlank(modeId, "modeId")
        AdminGuard.requireAdmin(security.userPrincipal?.name, authenticator.isEnabled, "deleteMode")
        if (!queue.deleteMode(id)) throw UnknownModeException(id)
    }

    @DELETE
    @Path("/{modeId}/leases/{leaseId}")
    @Blocking
    @Operation(
        summary = "Release a lease",
        description =
            "Called by a game server on graceful shutdown. Idempotent. When it was the mode's " +
                "last lease, the mode is removed and its waiting tickets are cancelled.",
    )
    @APIResponses(
        APIResponse(responseCode = "204", description = "The lease is gone."),
        APIResponse(responseCode = "401", description = "Authentication is missing or invalid."),
    )
    fun releaseLease(@PathParam("modeId") modeId: String?, @PathParam("leaseId") leaseId: String?) {
        queue.releaseLease(requireNonBlank(modeId, "modeId"), requireNonBlank(leaseId, "leaseId"))
    }

    @PUT
    @Path("/{modeId}/queue")
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
        APIResponse(responseCode = "400", description = "A field is missing or out of range."),
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
        val minPlayers = request.minPlayers ?: (teamSize * teamCount)
        if (minPlayers < 1 || minPlayers > teamSize * teamCount) {
            throw InvalidRequestException("minPlayers must be between 1 and teamSize * teamCount")
        }
        if (minPlayers < teamSize * teamCount && teamSize != 1) {
            // An under-filled team match would need someone to decide who plays short-handed.
            throw InvalidRequestException("only a free-for-all (teamSize 1) may lower minPlayers")
        }
        val fillSeconds = request.fillSeconds ?: 0
        if (fillSeconds < 0) throw InvalidRequestException("fillSeconds must not be negative")
        val lease = lease(request)
        val meta = meta(request)

        val config =
            ModeConfig(
                modeId = id,
                teamSize = teamSize,
                teamCount = teamCount,
                ranked = request.ranked,
                band = (request.band ?: BandRequestBody()).toDomain(),
                fleetName = request.fleetName?.trim()?.takeIf { it.isNotEmpty() },
                minPlayers = minPlayers,
                fillSeconds = fillSeconds,
            )

        val created = queue.upsertMode(config, meta, lease)
        // A leased mode repeats this call every few seconds per server; only a new mode is news.
        val message =
            "Upserted mode (id=$id, ${config.teamCount}x${config.teamSize}, " +
                "min=${config.minPlayers}, fill=${config.fillSeconds}s, ranked=${config.ranked}, " +
                "fleet=${config.fleet}, lease=${lease?.leaseId}, created=$created)"
        if (created) log.info(message) else log.debug(message)
        return UpsertQueueResponse(created)
    }

    private fun lease(request: UpsertQueueRequestBody): ModeLease? {
        val seconds = request.leaseSeconds ?: DEFAULT_LEASE_SECONDS
        if (seconds !in LEASE_SECONDS_RANGE) {
            throw InvalidRequestException("leaseSeconds must be between 15 and 600")
        }
        val leaseId = request.leaseId ?: return null
        if (leaseId.isBlank()) throw InvalidRequestException("leaseId must not be blank")
        if (leaseId.length > MAX_LEASE_ID_LENGTH) {
            throw InvalidRequestException("leaseId must be at most 253 characters")
        }
        return ModeLease(leaseId, seconds)
    }

    private fun meta(request: UpsertQueueRequestBody): ModeMeta {
        val displayName = request.displayName?.trim()?.takeIf { it.isNotEmpty() }
        if (displayName != null && displayName.length > MAX_DISPLAY_NAME_LENGTH) {
            throw InvalidRequestException("displayName must be at most 64 characters")
        }
        val boards =
            request.boards.orEmpty().map { board ->
                val stat = board.stat
                if (stat == null || !STAT_PATTERN.matches(stat)) {
                    throw InvalidRequestException("board stat must match [a-z0-9_-]{1,32}")
                }
                val name = board.displayName?.trim()?.takeIf { it.isNotEmpty() } ?: stat
                if (name.length > MAX_DISPLAY_NAME_LENGTH) {
                    throw InvalidRequestException("board displayName must be at most 64 characters")
                }
                StatBoard(stat, name)
            }
        if (boards.map { it.stat }.toSet().size != boards.size) {
            throw InvalidRequestException("board stats must be unique")
        }
        return ModeMeta(
            displayName = displayName,
            icon = request.icon?.trim()?.takeIf { it.isNotEmpty() },
            boards = boards,
            rejoinEnabled = request.rejoinEnabled,
            maps = request.maps?.let(::maps),
        )
    }

    private fun maps(body: MapsBody): ModeMaps {
        val namespace =
            body.namespace?.trim()?.trim('/')?.takeIf { it.isNotEmpty() }
                ?: throw InvalidRequestException("maps.namespace is required")
        if (namespace.length > MAX_NAMESPACE_LENGTH || namespace.any { it.isWhitespace() }) {
            throw InvalidRequestException(
                "maps.namespace must be at most 128 characters, without whitespace"
            )
        }
        val selection =
            body.selection
                ?.trim()
                ?.takeIf { it.isNotEmpty() }
                ?.uppercase()
                ?.let { name ->
                    MapSelectionStrategy.entries.firstOrNull { it.name == name }
                        ?: throw InvalidRequestException("maps.selection must be RANDOM")
                } ?: MapSelectionStrategy.RANDOM
        if (selection != MapSelectionStrategy.RANDOM) {
            // Reserved for a map vote that does not exist yet; accepting it would silently play
            // random maps under a name that promises something else.
            throw InvalidRequestException("maps.selection $selection is not supported yet")
        }
        return ModeMaps(namespace, selection)
    }

    @GET
    @Path("/{modeId}/maps")
    @Blocking
    @Operation(
        summary = "The maps a mode can be queued for",
        description =
            "Every map pinned for this environment under the mode's map namespace — what " +
                "`map` on POST /v1/match/tickets accepts, and what a proxy completes " +
                "`/queue <mode> <map>` from. Empty for a mode without maps.",
    )
    @APIResponses(
        APIResponse(
            responseCode = "200",
            description = "The mode's map pool.",
            content =
                [
                    Content(
                        mediaType = MediaType.APPLICATION_JSON,
                        schema = Schema(implementation = ModeMapsResponse::class),
                    )
                ],
        ),
        APIResponse(responseCode = "401", description = "Authentication is missing or invalid."),
        APIResponse(responseCode = "404", description = "There is no such mode."),
    )
    fun maps(@PathParam("modeId") modeId: String?): ModeMapsResponse {
        val id = requireNonBlank(modeId, "modeId")
        val pool = queue.modeMaps(id) ?: throw UnknownModeException(id)
        return ModeMapsResponse(maps = pool.maps, selection = pool.selection?.name)
    }

    @GET
    @Path("/{modeId}/queue-stats")
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

        private const val DEFAULT_LEASE_SECONDS = 60
        private val LEASE_SECONDS_RANGE = 15..600

        /** A pod's HOSTNAME is at most a DNS name. */
        private const val MAX_LEASE_ID_LENGTH = 253
        private const val MAX_DISPLAY_NAME_LENGTH = 64
        private const val MAX_NAMESPACE_LENGTH = 128
        private val STAT_PATTERN = Regex("[a-z0-9_-]{1,32}")
    }
}

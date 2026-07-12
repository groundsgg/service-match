package gg.grounds.api

import gg.grounds.domain.BandConfig
import gg.grounds.domain.ModeConfig
import gg.grounds.domain.Rating
import gg.grounds.domain.RatingRepository
import gg.grounds.domain.TicketState as DomainTicketState
import gg.grounds.grpc.match.Assignment
import gg.grounds.grpc.match.CancelTicketReply
import gg.grounds.grpc.match.CancelTicketRequest
import gg.grounds.grpc.match.EnqueueReply
import gg.grounds.grpc.match.EnqueueRequest
import gg.grounds.grpc.match.GetRatingReply
import gg.grounds.grpc.match.GetRatingRequest
import gg.grounds.grpc.match.GetTicketReply
import gg.grounds.grpc.match.GetTicketRequest
import gg.grounds.grpc.match.MatchServiceGrpc
import gg.grounds.grpc.match.QueueStatsReply
import gg.grounds.grpc.match.QueueStatsRequest
import gg.grounds.grpc.match.TicketState
import gg.grounds.grpc.match.UpsertQueueReply
import gg.grounds.grpc.match.UpsertQueueRequest
import gg.grounds.matcher.ModeRegistry
import io.grpc.Status
import io.grpc.StatusRuntimeException
import io.grpc.stub.StreamObserver
import io.quarkus.grpc.GrpcService
import jakarta.inject.Inject
import java.util.UUID
import org.eclipse.microprofile.config.inject.ConfigProperty
import org.jboss.logging.Logger

/**
 * gRPC entry-point. A thin translator: the domain layer owns the maths and the storage semantics.
 *
 * Only the rating reads are live. The queue RPCs need the Valkey spine (phase 2) and answer
 * UNIMPLEMENTED until then — deliberately, rather than returning a plausible-looking empty queue
 * that a caller would mistake for "no players waiting".
 */
@GrpcService
class MatchGrpcService
@Inject
constructor(
    private val ratings: RatingRepository,
    private val queue: QueueService,
    private val modes: ModeRegistry,
    @param:ConfigProperty(name = "grounds.match.rating.default-mu") private val defaultMu: Double,
    @param:ConfigProperty(name = "grounds.match.rating.default-sigma")
    private val defaultSigma: Double,
) : MatchServiceGrpc.MatchServiceImplBase() {

    override fun getRating(
        request: GetRatingRequest,
        responseObserver: StreamObserver<GetRatingReply>,
    ) {
        try {
            val playerId = parsePlayerId(request.playerId)
            val modeId = requireNonEmpty(request.modeId, "mode_id")

            // A player who has never played this mode is not an error —
            // they are simply unrated, and the defaults are what the
            // matchmaker would seed their first ticket with.
            val stored = ratings.find(playerId, modeId)
            val rating = stored?.rating ?: Rating(defaultMu, defaultSigma)

            responseObserver.onNext(
                GetRatingReply.newBuilder()
                    .setMu(rating.mu)
                    .setSigma(rating.sigma)
                    .setDisplay(rating.display)
                    .setGamesPlayed(stored?.gamesPlayed ?: 0)
                    .build()
            )
            responseObserver.onCompleted()
        } catch (e: StatusRuntimeException) {
            responseObserver.onError(e)
        } catch (e: Exception) {
            log.error("GetRating failed (player=${request.playerId}, mode=${request.modeId})", e)
            responseObserver.onError(
                Status.INTERNAL.withDescription("rating lookup failed").asRuntimeException()
            )
        }
    }

    override fun enqueue(request: EnqueueRequest, responseObserver: StreamObserver<EnqueueReply>) {
        try {
            val playerId = parsePlayerId(request.playerId)
            val modeId = requireNonEmpty(request.modeId, "mode_id")

            val ticket = queue.enqueue(playerId, modeId)

            responseObserver.onNext(
                EnqueueReply.newBuilder()
                    .setTicketId(ticket.id)
                    .setMu(ticket.mu)
                    .setQueuePosition(queue.queueDepth(modeId).toInt())
                    .build()
            )
            responseObserver.onCompleted()
        } catch (e: AlreadyQueuedException) {
            // Not an error the caller can fix by retrying — they are already in
            // a queue. One live ticket per player is the invariant that stops a
            // player being committed to two matches.
            responseObserver.onError(
                Status.ALREADY_EXISTS.withDescription(e.message).asRuntimeException()
            )
        } catch (e: UnknownModeException) {
            responseObserver.onError(
                Status.NOT_FOUND.withDescription(e.message).asRuntimeException()
            )
        } catch (e: StatusRuntimeException) {
            responseObserver.onError(e)
        } catch (e: Exception) {
            log.error("Enqueue failed (player=${request.playerId}, mode=${request.modeId})", e)
            responseObserver.onError(
                Status.INTERNAL.withDescription("enqueue failed").asRuntimeException()
            )
        }
    }

    override fun cancelTicket(
        request: CancelTicketRequest,
        responseObserver: StreamObserver<CancelTicketReply>,
    ) {
        try {
            val playerId = parsePlayerId(request.playerId)
            val ticketId = requireNonEmpty(request.ticketId, "ticket_id")

            // False is a legitimate answer, not an error: the match had already
            // formed, or the ticket is not this player's.
            val cancelled = queue.cancel(ticketId, playerId)

            responseObserver.onNext(CancelTicketReply.newBuilder().setCancelled(cancelled).build())
            responseObserver.onCompleted()
        } catch (e: StatusRuntimeException) {
            responseObserver.onError(e)
        } catch (e: Exception) {
            log.error("CancelTicket failed (ticket=${request.ticketId})", e)
            responseObserver.onError(
                Status.INTERNAL.withDescription("cancel failed").asRuntimeException()
            )
        }
    }

    override fun getTicket(
        request: GetTicketRequest,
        responseObserver: StreamObserver<GetTicketReply>,
    ) {
        try {
            val ticketId = requireNonEmpty(request.ticketId, "ticket_id")
            val ticket =
                queue.findTicket(ticketId)
                    ?: throw Status.NOT_FOUND.withDescription("no such ticket: $ticketId")
                        .asRuntimeException()

            val reply =
                GetTicketReply.newBuilder()
                    .setState(ticket.state.toProto())
                    .setQueueDepth(queue.queueDepth(ticket.modeId).toInt())

            ticket.assignment?.let { a ->
                reply.setAssignment(
                    Assignment.newBuilder()
                        .setMatchId(ticket.matchId.orEmpty())
                        .setGameServerName(a.gameServerName)
                        .setAddress(a.address)
                        .setPort(a.port)
                        .build()
                )
            }

            responseObserver.onNext(reply.build())
            responseObserver.onCompleted()
        } catch (e: StatusRuntimeException) {
            responseObserver.onError(e)
        } catch (e: Exception) {
            log.error("GetTicket failed (ticket=${request.ticketId})", e)
            responseObserver.onError(
                Status.INTERNAL.withDescription("ticket lookup failed").asRuntimeException()
            )
        }
    }

    override fun getQueueStats(
        request: QueueStatsRequest,
        responseObserver: StreamObserver<QueueStatsReply>,
    ) {
        try {
            val modeId = requireNonEmpty(request.modeId, "mode_id")
            val known = queue.isKnownMode(modeId)

            responseObserver.onNext(
                QueueStatsReply.newBuilder()
                    .setTicketsQueued(if (known) queue.queueDepth(modeId).toInt() else 0)
                    .setAvailable(known)
                    .build()
            )
            responseObserver.onCompleted()
        } catch (e: StatusRuntimeException) {
            responseObserver.onError(e)
        } catch (e: Exception) {
            log.error("GetQueueStats failed (mode=${request.modeId})", e)
            responseObserver.onError(
                Status.INTERNAL.withDescription("queue stats failed").asRuntimeException()
            )
        }
    }

    override fun upsertQueue(
        request: UpsertQueueRequest,
        responseObserver: StreamObserver<UpsertQueueReply>,
    ) {
        try {
            val modeId = requireNonEmpty(request.modeId, "mode_id")
            if (request.teamSize < 1 || request.teamCount < 2) {
                throw Status.INVALID_ARGUMENT.withDescription(
                        "a match needs team_size >= 1 and team_count >= 2"
                    )
                    .asRuntimeException()
            }

            val band = request.band
            val config =
                ModeConfig(
                    modeId = modeId,
                    teamSize = request.teamSize,
                    teamCount = request.teamCount,
                    ranked = request.ranked,
                    band =
                        BandConfig(
                            // Zero means "not set" on the wire, so fall back to
                            // the defaults rather than a band of nothing.
                            b0 = band.b0.takeIf { it > 0 } ?: BandConfig().b0,
                            k = band.k.takeIf { it > 0 } ?: BandConfig().k,
                            w = band.w.takeIf { it > 0 } ?: BandConfig().w,
                            stepSeconds =
                                band.sSeconds.takeIf { it > 0 } ?: BandConfig().stepSeconds,
                            mercySeconds =
                                band.mercySeconds.takeIf { it > 0 } ?: BandConfig().mercySeconds,
                            mutualSeconds =
                                band.mutualSeconds.takeIf { it > 0 } ?: BandConfig().mutualSeconds,
                        ),
                )

            val created = modes.upsert(config)
            log.info(
                "Upserted mode (id=$modeId, ${config.teamCount}x${config.teamSize}, " +
                    "ranked=${config.ranked}, created=$created)"
            )

            responseObserver.onNext(UpsertQueueReply.newBuilder().setCreated(created).build())
            responseObserver.onCompleted()
        } catch (e: StatusRuntimeException) {
            responseObserver.onError(e)
        } catch (e: Exception) {
            log.error("UpsertQueue failed (mode=${request.modeId})", e)
            responseObserver.onError(
                Status.INTERNAL.withDescription("upsert failed").asRuntimeException()
            )
        }
    }

    private fun DomainTicketState.toProto(): TicketState =
        when (this) {
            DomainTicketState.QUEUED -> TicketState.QUEUED
            DomainTicketState.MATCHED -> TicketState.MATCHED
            DomainTicketState.ASSIGNED -> TicketState.ASSIGNED
            DomainTicketState.CANCELLED -> TicketState.CANCELLED
            DomainTicketState.FAILED -> TicketState.FAILED
        }

    private fun parsePlayerId(raw: String): UUID =
        try {
            UUID.fromString(raw)
        } catch (_: IllegalArgumentException) {
            throw Status.INVALID_ARGUMENT.withDescription("player_id is not a UUID: $raw")
                .asRuntimeException()
        }

    private fun requireNonEmpty(value: String, field: String): String {
        if (value.isBlank()) {
            throw Status.INVALID_ARGUMENT.withDescription("$field is required").asRuntimeException()
        }
        return value
    }

    private companion object {
        private val log: Logger = Logger.getLogger(MatchGrpcService::class.java)
    }
}

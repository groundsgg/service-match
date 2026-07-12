package gg.grounds.api

import gg.grounds.domain.Rating
import gg.grounds.domain.RatingRepository
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
import gg.grounds.grpc.match.UpsertQueueReply
import gg.grounds.grpc.match.UpsertQueueRequest
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

    override fun enqueue(request: EnqueueRequest, responseObserver: StreamObserver<EnqueueReply>) =
        responseObserver.onError(notYetBuilt("Enqueue"))

    override fun cancelTicket(
        request: CancelTicketRequest,
        responseObserver: StreamObserver<CancelTicketReply>,
    ) = responseObserver.onError(notYetBuilt("CancelTicket"))

    override fun getTicket(
        request: GetTicketRequest,
        responseObserver: StreamObserver<GetTicketReply>,
    ) = responseObserver.onError(notYetBuilt("GetTicket"))

    override fun getQueueStats(
        request: QueueStatsRequest,
        responseObserver: StreamObserver<QueueStatsReply>,
    ) = responseObserver.onError(notYetBuilt("GetQueueStats"))

    override fun upsertQueue(
        request: UpsertQueueRequest,
        responseObserver: StreamObserver<UpsertQueueReply>,
    ) = responseObserver.onError(notYetBuilt("UpsertQueue"))

    private fun notYetBuilt(rpc: String) =
        Status.UNIMPLEMENTED.withDescription("$rpc needs the Valkey queue spine (phase 2)")
            .asRuntimeException()

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

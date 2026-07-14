package gg.grounds.leaderboard

import gg.grounds.grpc.leaderboard.LeaderboardServiceGrpc
import gg.grounds.grpc.leaderboard.SubmitMode
import gg.grounds.grpc.leaderboard.SubmitScoreRequest
import io.grpc.CallOptions
import io.grpc.Channel
import io.grpc.ClientCall
import io.grpc.ClientInterceptor
import io.grpc.ForwardingClientCall
import io.grpc.ManagedChannel
import io.grpc.ManagedChannelBuilder
import io.grpc.Metadata
import io.grpc.MethodDescriptor
import jakarta.annotation.PreDestroy
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import org.eclipse.microprofile.config.inject.ConfigProperty
import org.jboss.logging.Logger

/**
 * Writes rated results to service-leaderboard.
 *
 * service-match already computes the ratings, so this is the one place a gamemode's win never has
 * to know a leaderboard exists — see `ResultService.report`, which calls this after a rated result
 * has already been committed to Postgres.
 *
 * By the time this is called the result is the source of truth and is already durable. A
 * leaderboard that is down, slow, or rejects the call must not turn a real result into a failed one
 * — `submitScore` never throws, on purpose; every failure is logged and swallowed here rather than
 * left for the caller to remember to catch.
 */
@ApplicationScoped
class LeaderboardClient
@Inject
constructor(
    @param:ConfigProperty(
        name = "grounds.match.leaderboard.url",
        defaultValue = "service-leaderboard:9000",
    )
    target: String,
    @param:ConfigProperty(name = "grounds.match.leaderboard.deadline-ms", defaultValue = "5000")
    private val deadlineMs: Long,
) {

    // Plaintext, same as MatchHostClient: this is a pod-to-pod call inside the
    // project's own vCluster, which is the tenancy boundary. There is no
    // untrusted network between us and service-leaderboard.
    private val channel: ManagedChannel =
        ManagedChannelBuilder.forTarget(target).usePlaintext().intercept(TokenInterceptor()).build()

    private val stub = LeaderboardServiceGrpc.newBlockingStub(channel)

    /**
     * Submit one player's post-match score. `idempotencyKey` is the caller's job to make stable
     * (`<matchId>:<playerId>`) — a retried ReportMatchResult must not double-count on the board any
     * more than it double-counts the rating.
     *
     * Never throws — see the class doc. A caller that needs to know whether this actually landed
     * has the wrong idea about what this call is for.
     */
    fun submitScore(boardId: String, playerId: String, score: Long, idempotencyKey: String) {
        try {
            stub
                .withDeadlineAfter(deadlineMs, TimeUnit.MILLISECONDS)
                .submitScore(
                    SubmitScoreRequest.newBuilder()
                        .setBoardId(boardId)
                        .setPlayerId(playerId)
                        .setScore(score)
                        .setMode(SubmitMode.SUBMIT_MODE_REPLACE)
                        .setIdempotencyKey(idempotencyKey)
                        .build()
                )
        } catch (e: Exception) {
            log.error(
                "Leaderboard submit failed, result stands regardless " +
                    "(board=$boardId, player=$playerId): ${e.message}",
                e,
            )
        }
    }

    @PreDestroy
    fun close() {
        channel.shutdownNow()
    }

    companion object {
        private val log: Logger = Logger.getLogger(LeaderboardClient::class.java)
    }
}

/**
 * Attaches the pod's projected ServiceAccount JWT (aud=grounds-services) as `authorization: Bearer
 * <token>` on every call. Re-read from disk on every single call, never cached: it is a projected
 * volume the kubelet rotates, and a token cached once at startup would expire under this
 * long-running process while leaderboard writes quietly started failing auth hours later. A missing
 * file (local dev, or a service-leaderboard with auth disabled) just means the call goes out
 * unauthenticated rather than crashing.
 *
 * Mirrors `MatchServiceClient.TokenInterceptor` in plugin-match — same convention, same env var.
 */
internal class TokenInterceptor : ClientInterceptor {
    override fun <ReqT : Any, RespT : Any> interceptCall(
        method: MethodDescriptor<ReqT, RespT>,
        callOptions: CallOptions,
        next: Channel,
    ): ClientCall<ReqT, RespT> {
        val call = next.newCall(method, callOptions)
        return object : ForwardingClientCall.SimpleForwardingClientCall<ReqT, RespT>(call) {
            override fun start(responseListener: Listener<RespT>, headers: Metadata) {
                readToken()?.let { headers.put(AUTHORIZATION, "Bearer $it") }
                super.start(responseListener, headers)
            }
        }
    }

    private fun readToken(): String? {
        val path = Path.of(System.getenv("GROUNDS_TOKEN_FILE") ?: DEFAULT_TOKEN_PATH)
        return try {
            if (Files.exists(path)) Files.readString(path).trim().ifEmpty { null } else null
        } catch (_: Exception) {
            null
        }
    }

    companion object {
        private const val DEFAULT_TOKEN_PATH = "/var/run/secrets/grounds/token"
        private val AUTHORIZATION: Metadata.Key<String> =
            Metadata.Key.of("authorization", Metadata.ASCII_STRING_MARSHALLER)
    }
}

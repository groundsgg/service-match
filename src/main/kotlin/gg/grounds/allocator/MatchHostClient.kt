package gg.grounds.allocator

import com.google.protobuf.InvalidProtocolBufferException
import gg.grounds.domain.ServerAssignment
import gg.grounds.events.NatsConnector
import gg.grounds.grpc.match.MatchHostGrpc
import gg.grounds.grpc.match.MatchTeam
import gg.grounds.grpc.match.StartMatchReply
import gg.grounds.grpc.match.StartMatchRequest
import io.grpc.ManagedChannel
import io.grpc.ManagedChannelBuilder
import jakarta.annotation.PreDestroy
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import org.eclipse.microprofile.config.inject.ConfigProperty
import org.jboss.logging.Logger

/**
 * Tells a game server that a match is coming.
 *
 * This is the one call in the service that dials *out* to a server rather than being dialled, and
 * it is a request, not an event: the reply decides whether the players are routed or put back on
 * the queue, and the push happens before the assign precisely so that a refusal is still
 * recoverable.
 *
 * It goes over NATS request-reply, addressed by the server's own name rather than by where its pod
 * happens to be. The gRPC route it replaces had to know that: Agones runs these fleets with
 * `portPolicy: None`, so there is no Service and no DNS name, and the matchmaker read the PodIP off
 * the GameServer status and dialled it directly on a second container port. Addressing the server
 * by identity removes both.
 *
 * The gRPC route stays as a fallback for one release. The runtimes migrate on their own schedule —
 * game-bedwars and duel do not even depend on the runtime directly, they inherit it — and a
 * matchmaker that could only speak NATS would leave every un-migrated gamemode with matches that
 * silently never start. With the fallback, the two sides can land in either order.
 *
 * A second push is safe in either direction: `StartMatch` is required to be idempotent on the match
 * id, so falling back after a lost reply re-sends a match the server may already be building and
 * gets `accepted` for it.
 */
@ApplicationScoped
class MatchHostClient
@Inject
constructor(
    private val nats: NatsConnector,
    @param:ConfigProperty(name = "grounds.match.gameserver.subject-prefix")
    private val subjectPrefix: String,
    @param:ConfigProperty(name = "grounds.match.gameserver.grpc-port") private val grpcPort: Int,
    @param:ConfigProperty(name = "grounds.match.gameserver.grpc-fallback")
    private val grpcFallback: Boolean,
    @param:ConfigProperty(name = "grounds.match.gameserver.deadline-ms")
    private val deadlineMs: Long,
) {

    // Only the fallback needs these. A fleet is a handful of pods, and rebuilding a channel per
    // match would pay a TCP and HTTP/2 handshake on the path between "match formed" and "players
    // routed".
    private val channels = ConcurrentHashMap<String, ManagedChannel>()

    /**
     * @return true when the server has the match — including when it already had it.
     *
     * A refusal, a timeout and an unreachable server are all the same answer here: *this match is
     * not going to start on this server*. The caller puts the players back on the queue with the
     * wait they had earned rather than leaving them in a match that will never begin.
     */
    fun startMatch(
        server: ServerAssignment,
        matchId: String,
        modeId: String,
        teams: List<List<String>>,
    ): Boolean {
        if (teams.isEmpty() || teams.all { it.isEmpty() }) {
            // Nothing to start. Better caught here than by the server, which would
            // build an arena for nobody.
            log.error("Refusing to push an empty match (id=$matchId)")
            return false
        }

        val request =
            StartMatchRequest.newBuilder()
                .setMatchId(matchId)
                .setModeId(modeId)
                .addAllTeams(teams.map { MatchTeam.newBuilder().addAllPlayerIds(it).build() })
                .build()

        pushOverNats(server, request)?.let {
            return it
        }

        if (!grpcFallback) {
            log.warn(
                "Nobody answered on NATS for ${server.gameServerName} and the gRPC fallback is " +
                    "off (id=$matchId)"
            )
            return false
        }
        return pushOverGrpc(server, request)
    }

    /**
     * @return the server's answer, or null when nobody answered — no connection, no responder on
     *   the subject, or no reply inside the deadline. All three mean "try the other route".
     */
    private fun pushOverNats(server: ServerAssignment, request: StartMatchRequest): Boolean? {
        val connection = nats.connection() ?: return null
        val subject = "$subjectPrefix.${server.gameServerName}.start"

        return try {
            // jnats answers null for both a timeout and a no-responder 503. That is the one
            // distinction we do not need: either way this route produced no answer. It does
            // mean an un-migrated runtime costs nothing — a 503 comes back immediately, so the
            // fallback is not gated on the deadline.
            val reply =
                connection.request(subject, request.toByteArray(), Duration.ofMillis(deadlineMs))
                    ?: return null

            val parsed = StartMatchReply.parseFrom(reply.data)
            if (!parsed.accepted) {
                log.warn(
                    "Server refused the match (id=${request.matchId}, " +
                        "gs=${server.gameServerName}, reason=${parsed.reason})"
                )
            }
            parsed.accepted
        } catch (e: InvalidProtocolBufferException) {
            // Something answered on this subject and it was not a game server. Falling back
            // would push the same match down a second route on the strength of a reply we
            // could not read; refusing costs the players a requeue and nothing more.
            log.error("Unparseable StartMatch reply from $subject (id=${request.matchId})", e)
            false
        } catch (e: Exception) {
            log.warn(
                "NATS push failed for ${server.gameServerName} " +
                    "(id=${request.matchId}, subject=$subject): ${e.message}"
            )
            null
        }
    }

    private fun pushOverGrpc(server: ServerAssignment, request: StartMatchRequest): Boolean =
        try {
            val reply =
                MatchHostGrpc.newBlockingStub(channelTo(server.address))
                    .withDeadlineAfter(deadlineMs, TimeUnit.MILLISECONDS)
                    .startMatch(request)

            if (!reply.accepted) {
                log.warn(
                    "Server refused the match (id=${request.matchId}, " +
                        "gs=${server.gameServerName}, reason=${reply.reason})"
                )
            }
            reply.accepted
        } catch (e: Exception) {
            // We cannot tell a lost call from a lost reply, so the server may in
            // fact be building this arena right now. Treating that as failure
            // costs a leaked slot the gamemode's counter sync reclaims; treating
            // it as success would cost the players their match.
            log.warn(
                "Could not push the match to ${server.gameServerName} " +
                    "(id=${request.matchId}, addr=${server.address}:$grpcPort): ${e.message}"
            )
            false
        }

    private fun channelTo(address: String): ManagedChannel =
        channels.computeIfAbsent(address) {
            // Plaintext: this is a pod-to-pod call inside the project's own
            // vCluster, which is the tenancy boundary. There is no untrusted
            // network between us and the game server.
            ManagedChannelBuilder.forAddress(it, grpcPort).usePlaintext().build()
        }

    @PreDestroy
    fun close() {
        channels.values.forEach { it.shutdownNow() }
    }

    companion object {
        private val log: Logger = Logger.getLogger(MatchHostClient::class.java)
    }
}

package gg.grounds.allocator

import gg.grounds.domain.ServerAssignment
import gg.grounds.grpc.match.MatchHostGrpc
import gg.grounds.grpc.match.MatchTeam
import gg.grounds.grpc.match.StartMatchRequest
import io.grpc.ManagedChannel
import io.grpc.ManagedChannelBuilder
import jakarta.annotation.PreDestroy
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import org.eclipse.microprofile.config.inject.ConfigProperty
import org.jboss.logging.Logger

/**
 * Tells a game server that a match is coming.
 *
 * This is the one call in the service that dials *out* to a server rather than being dialled, and
 * the address is only known at runtime — Agones picks the server, so there is no static gRPC client
 * to configure. Channels are therefore built per server and cached: a fleet is a handful of pods,
 * and rebuilding a channel per match would pay a TCP and HTTP/2 handshake on the critical path
 * between "match formed" and "players routed".
 *
 * The server may already have this match. A retry is not a new match, and the contract requires
 * `StartMatch` to be idempotent on the match id, so re-sending after a lost reply is safe.
 */
@ApplicationScoped
class MatchHostClient
@Inject
constructor(
    @param:ConfigProperty(name = "grounds.match.gameserver.grpc-port") private val grpcPort: Int,
    @param:ConfigProperty(name = "grounds.match.gameserver.deadline-ms")
    private val deadlineMs: Long,
) {

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

        return try {
            val reply =
                MatchHostGrpc.newBlockingStub(channelTo(server.address))
                    .withDeadlineAfter(deadlineMs, TimeUnit.MILLISECONDS)
                    .startMatch(request)

            if (!reply.accepted) {
                log.warn(
                    "Server refused the match (id=$matchId, gs=${server.gameServerName}, " +
                        "reason=${reply.reason})"
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
                    "(id=$matchId, addr=${server.address}:$grpcPort): ${e.message}"
            )
            false
        }
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

package gg.grounds.allocator

import com.google.protobuf.InvalidProtocolBufferException
import gg.grounds.domain.ServerAssignment
import gg.grounds.events.NatsConnector
import gg.grounds.grpc.match.MatchTeam
import gg.grounds.grpc.match.StartMatchReply
import gg.grounds.grpc.match.StartMatchRequest
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import java.time.Duration
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
 * happens to be. The gRPC route it replaced had to know that: Agones runs these fleets with
 * `portPolicy: None`, so there is no Service and no DNS name, and the matchmaker read the PodIP off
 * the GameServer status and dialled it directly on a second container port. Addressing the server
 * by identity removes both.
 */
@ApplicationScoped
class MatchHostClient
@Inject
constructor(
    private val nats: NatsConnector,
    @param:ConfigProperty(name = "grounds.match.gameserver.subject-prefix")
    private val subjectPrefix: String,
    @param:ConfigProperty(name = "grounds.match.gameserver.deadline-ms")
    private val deadlineMs: Long,
) {

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

        return pushOverNats(server, request)
    }

    private fun pushOverNats(server: ServerAssignment, request: StartMatchRequest): Boolean {
        val connection = nats.connection()
        if (connection == null) {
            log.warn(
                "No NATS connection to push the match to ${server.gameServerName} " +
                    "(id=${request.matchId})"
            )
            return false
        }
        val subject = "$subjectPrefix.${server.gameServerName}.start"

        return try {
            // jnats answers null for both a timeout and a no-responder 503. That is the one
            // distinction we do not need: either way nobody took this match, and the players go
            // back on the queue.
            val reply =
                connection.request(subject, request.toByteArray(), Duration.ofMillis(deadlineMs))
            if (reply == null) {
                log.warn(
                    "Nobody answered on $subject for ${server.gameServerName} " +
                        "(id=${request.matchId})"
                )
                return false
            }

            val parsed = StartMatchReply.parseFrom(reply.data)
            if (!parsed.accepted) {
                log.warn(
                    "Server refused the match (id=${request.matchId}, " +
                        "gs=${server.gameServerName}, reason=${parsed.reason})"
                )
            }
            parsed.accepted
        } catch (e: InvalidProtocolBufferException) {
            // Something answered on this subject and it was not a game server.
            log.error("Unparseable StartMatch reply from $subject (id=${request.matchId})", e)
            false
        } catch (e: Exception) {
            // We cannot tell a lost request from a lost reply, so the server may in fact be
            // building this arena right now. Treating that as failure costs a leaked slot the
            // gamemode's counter sync reclaims; treating it as success would cost the players
            // their match.
            log.warn(
                "NATS push failed for ${server.gameServerName} " +
                    "(id=${request.matchId}, subject=$subject): ${e.message}"
            )
            false
        }
    }

    companion object {
        private val log: Logger = Logger.getLogger(MatchHostClient::class.java)
    }
}

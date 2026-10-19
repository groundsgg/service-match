package gg.grounds.allocator

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.google.protobuf.InvalidProtocolBufferException
import gg.grounds.domain.ServerAssignment
import gg.grounds.events.NatsConnector
import gg.grounds.grpc.match.MatchTeam
import gg.grounds.grpc.match.StartMatchReply
import gg.grounds.grpc.match.StartMatchRequest
import io.nats.client.impl.Headers
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import java.time.Duration
import java.time.Instant
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
     * @return whether the server has the match — including when it already had it — and whether it
     *   will report the round ready on its own before its players may be sent.
     *
     * A refusal, a timeout and an unreachable server are all the same answer here: *this match is
     * not going to start on this server*. The caller puts the players back on the queue with the
     * wait they had earned rather than leaving them in a match that will never begin.
     *
     * The match timeline rides along as NATS headers rather than in the proto, so a game server
     * that does not read them is unaffected: [enqueuedAt] (each roster player's ticket enqueue),
     * [formedAt] and the [traceparent] of the match trace. Whatever is missing is left out.
     *
     * [mapAddress] is the map the matchmaker chose, empty for none. [waitForReady] tells the server
     * the players can wait in the queue until it reports the round ready; a server that will do so
     * answers with `signals_ready`, and one that predates it never does.
     */
    fun startMatch(
        server: ServerAssignment,
        matchId: String,
        modeId: String,
        teams: List<List<String>>,
        enqueuedAt: Map<String, Instant> = emptyMap(),
        formedAt: Instant? = null,
        traceparent: String? = null,
        mapAddress: String = "",
        waitForReady: Boolean = true,
    ): StartMatchResult {
        if (teams.isEmpty() || teams.all { it.isEmpty() }) {
            // Nothing to start. Better caught here than by the server, which would
            // build an arena for nobody.
            log.error("Refusing to push an empty match (id=$matchId)")
            return StartMatchResult.REFUSED
        }

        val request =
            StartMatchRequest.newBuilder()
                .setMatchId(matchId)
                .setModeId(modeId)
                .addAllTeams(teams.map { MatchTeam.newBuilder().addAllPlayerIds(it).build() })
                .setMapAddress(mapAddress)
                .setWaitForReady(waitForReady)
                .build()

        val headers = Headers()
        traceparent?.let { headers.add(TRACEPARENT_HEADER, it) }
        if (enqueuedAt.isNotEmpty()) {
            headers.add(
                ENQUEUED_AT_HEADER,
                enqueuedAt.entries.joinToString(",") { "${it.key}=${it.value.toEpochMilli()}" },
            )
        }
        formedAt?.let { headers.add(FORMED_AT_HEADER, it.toEpochMilli().toString()) }

        return pushOverNats(server, request, headers)
    }

    /**
     * Tell [gameServerName] to drop the round it is preparing for [matchId] — its players went back
     * on the queue (the prepare timed out, or too many left). Fire-and-forget over core NATS: a
     * server that misses it discards the round anyway when its ready call answers `routed: false`.
     *
     * Published to `<prefix>.<gameServerName>.cancel` as `{"matchId": ..., "reason": ...}`, the
     * reason one of `timeout`, `cancelled` or `failed`.
     *
     * @return false if it could not be published.
     */
    fun cancelMatch(gameServerName: String, matchId: String, reason: String): Boolean {
        val connection = nats.connection()
        if (connection == null) {
            log.warn("No NATS connection to cancel the match on $gameServerName (id=$matchId)")
            return false
        }
        val subject = "$subjectPrefix.$gameServerName.cancel"
        return try {
            connection.publish(
                subject,
                json.writeValueAsBytes(linkedMapOf("matchId" to matchId, "reason" to reason)),
            )
            true
        } catch (e: Exception) {
            log.warn("Could not publish the cancel on $subject (id=$matchId): ${e.message}")
            false
        }
    }

    private fun pushOverNats(
        server: ServerAssignment,
        request: StartMatchRequest,
        headers: Headers,
    ): StartMatchResult {
        val connection = nats.connection()
        if (connection == null) {
            log.warn(
                "No NATS connection to push the match to ${server.gameServerName} " +
                    "(id=${request.matchId})"
            )
            return StartMatchResult.REFUSED
        }
        val subject = "$subjectPrefix.${server.gameServerName}.start"

        return try {
            // jnats answers null for both a timeout and a no-responder 503. That is the one
            // distinction we do not need: either way nobody took this match, and the players go
            // back on the queue.
            val reply =
                connection.request(
                    subject,
                    headers,
                    request.toByteArray(),
                    Duration.ofMillis(deadlineMs),
                )
            if (reply == null) {
                log.warn(
                    "Nobody answered on $subject for ${server.gameServerName} " +
                        "(id=${request.matchId})"
                )
                return StartMatchResult.REFUSED
            }

            val parsed = StartMatchReply.parseFrom(reply.data)
            if (!parsed.accepted) {
                log.warn(
                    "Server refused the match (id=${request.matchId}, " +
                        "gs=${server.gameServerName}, reason=${parsed.reason})"
                )
            }
            // An older server leaves signals_ready unset: it is ready when it accepts.
            StartMatchResult(parsed.accepted, parsed.accepted && parsed.signalsReady)
        } catch (e: InvalidProtocolBufferException) {
            // Something answered on this subject and it was not a game server.
            log.error("Unparseable StartMatch reply from $subject (id=${request.matchId})", e)
            StartMatchResult.REFUSED
        } catch (e: Exception) {
            // We cannot tell a lost request from a lost reply, so the server may in fact be
            // building this arena right now. Treating that as failure costs a leaked slot the
            // gamemode's counter sync reclaims; treating it as success would cost the players
            // their match.
            log.warn(
                "NATS push failed for ${server.gameServerName} " +
                    "(id=${request.matchId}, subject=$subject): ${e.message}"
            )
            StartMatchResult.REFUSED
        }
    }

    companion object {
        private val log: Logger = Logger.getLogger(MatchHostClient::class.java)
        private val json = jacksonObjectMapper()

        const val TRACEPARENT_HEADER = "traceparent"
        const val ENQUEUED_AT_HEADER = "grounds-enqueued-at"
        const val FORMED_AT_HEADER = "grounds-formed-at"
    }
}

/** A game server's answer to StartMatch. */
data class StartMatchResult(
    /** The server has the match. */
    val accepted: Boolean,
    /** It is preparing the round and will report it ready; until then nobody is routed. */
    val signalsReady: Boolean = false,
) {
    companion object {
        val REFUSED = StartMatchResult(accepted = false)
    }
}

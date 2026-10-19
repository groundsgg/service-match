package gg.grounds.api

import gg.grounds.allocator.MatchHostClient
import gg.grounds.domain.MatchState
import gg.grounds.domain.ModeConfig
import gg.grounds.domain.ModeLease
import gg.grounds.domain.ModeMeta
import gg.grounds.domain.ModeSummary
import gg.grounds.domain.Rating
import gg.grounds.domain.RatingRepository
import gg.grounds.domain.Ticket
import gg.grounds.maps.MapSelection
import gg.grounds.maps.ModeMapPool
import gg.grounds.matcher.ModeRegistry
import gg.grounds.metrics.MatchMetrics
import gg.grounds.persistence.MatchStatus
import gg.grounds.persistence.ValkeyQueue
import gg.grounds.persistence.Withdrawal
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import java.time.Duration
import java.time.Instant
import java.util.UUID
import org.eclipse.microprofile.config.inject.ConfigProperty
import org.jboss.logging.Logger

/** Raised when a player already holds a live ticket. */
class AlreadyQueuedException : RuntimeException("player already holds a live ticket")

/** Raised when the mode has no matchmaking config — forge has not pushed one. */
class UnknownModeException(modeId: String) : RuntimeException("unknown mode: $modeId")

/** Raised when a server reports ready for a match that was given to a different server. */
class WrongServerException(matchId: String, gameServerName: String) :
    RuntimeException("match $matchId is not on $gameServerName")

/**
 * Queue operations, between the gRPC layer and Valkey.
 *
 * Its one real job is seeding the ticket with the player's rating: the matcher reads mu and sigma
 * off the ticket, so the 2-second loop never touches Postgres.
 */
@ApplicationScoped
class QueueService
@Inject
constructor(
    private val queue: ValkeyQueue,
    private val modes: ModeRegistry,
    private val ratings: RatingRepository,
    private val metrics: MatchMetrics,
    private val mapSelection: MapSelection,
    private val matchHost: MatchHostClient,
    @param:ConfigProperty(name = "grounds.match.ticket.ttl-seconds") private val ticketTtl: Long,
    @param:ConfigProperty(name = "grounds.match.rating.default-mu") private val defaultMu: Double,
    @param:ConfigProperty(name = "grounds.match.rating.default-sigma")
    private val defaultSigma: Double,
    @param:ConfigProperty(name = "grounds.match.rejoin.grace-seconds") private val rejoinGrace: Long,
) {

    /**
     * @param map the map the player asked for, `<namespace>/<name>`; null for any. It must be in
     *   the mode's pinned pool, and a mode without maps takes no request at all.
     */
    fun enqueue(playerId: UUID, modeId: String, map: String? = null): Ticket {
        val mode = modes.find(modeId) ?: throw UnknownModeException(modeId)
        if (map != null) requireInPool(mode.modeId, map)

        // Read the rating once, here, and carry it on the ticket. The matcher
        // then works purely from Valkey.
        //
        // A store that will not answer must not stop someone queueing. The
        // codebase already takes that position on the write side — "players
        // would rather play an unrated game than no game" (Matcher) — and the
        // read side had the opposite behaviour: an exception here refused the
        // enqueue outright, so a database blip locked every player out of every
        // queue.
        //
        // Failing open costs something, and the ticket records it. Matching a
        // settled player as mu=25 is fine — the band is wide and the worst case
        // is one lopsided game — but *rating* from that prior would move their
        // real rating on a fiction. So the ticket is marked provisional and the
        // match it forms is recorded unranked.
        //
        // A genuinely unrated player (find returns null) is NOT provisional: the
        // defaults are the right answer for them, not a fallback.
        var provisional = false
        val stored =
            try {
                ratings.find(playerId, mode.modeId)
            } catch (e: Exception) {
                provisional = true
                metrics.ratingLookupDegraded(mode.modeId)
                log.warn(
                    "Rating store unreachable; queueing with seeded defaults and " +
                        "the match will be unranked (player=$playerId, mode=${mode.modeId})",
                    e,
                )
                null
            }
        val rating = stored?.rating ?: Rating(defaultMu, defaultSigma)

        val ticket =
            Ticket(
                id = UUID.randomUUID().toString(),
                playerId = playerId.toString(),
                modeId = mode.modeId,
                mu = rating.mu,
                sigma = rating.sigma,
                enqueuedAt = Instant.now(),
                provisional = provisional,
                map = map,
            )

        if (!queue.enqueue(ticket, ticketTtl)) {
            throw AlreadyQueuedException()
        }
        metrics.ticketEnqueued(mode.modeId)
        return ticket
    }

    private fun requireInPool(modeId: String, map: String) {
        val maps =
            queue.loadMeta(modeId).maps
                ?: throw InvalidRequestException(
                    "mode $modeId has no maps, so none can be requested"
                )
        if (!mapSelection.inPool(maps, map)) {
            throw InvalidRequestException(
                "map $map is not in the pool of mode $modeId (namespace ${maps.namespace})"
            )
        }
    }

    /**
     * Withdraw a ticket — while it is QUEUED, or while its match's server is still preparing the
     * round. A preparing match left with fewer than the mode's minPlayers fails: the rest go back
     * on the queue with the wait they had, and the server is told to drop the round. Otherwise it
     * carries on without the leaver.
     */
    fun cancel(ticketId: String, playerId: UUID): Boolean {
        val ticket = queue.findTicket(ticketId) ?: return false
        // A team mode's minimum is a full match: one leaver ends it, as an unbalanced team would.
        val minPlayers = modes.find(ticket.modeId)?.minPlayers ?: 1
        val result = queue.withdraw(ticketId, playerId.toString(), ticket.modeId, minPlayers)
        val matchId = ticket.matchId
        if (result == Withdrawal.CANCELLED_MATCH_FAILED && matchId != null) {
            val status = queue.matchStatus(matchId)
            status.server?.let { matchHost.cancelMatch(it.gameServerName, matchId, "cancelled") }
            recordReady(matchId, ticket.modeId, status, "cancelled")
            log.info(
                "Too few players left the preparing match, requeued the rest " +
                    "(id=$matchId, mode=${ticket.modeId}, min=$minPlayers)"
            )
        }
        return result != Withdrawal.REFUSED
    }

    /**
     * A server reports whether the round it was preparing for [matchId] is ready.
     *
     * @return whether the players are routed to it: true once assigned (also on a retry after
     *   that), false when the match is no longer waiting for this server — failed, timed out, left
     *   by too many, or unknown — in which case the server discards the round.
     * @throws WrongServerException when the match is on another server.
     */
    fun ready(matchId: String, gameServerName: String, ok: Boolean, reason: String?): Boolean {
        val status = statusAfterPush(matchId)
        val state = status.state ?: return false
        val server = status.server
        if (
            (state == MatchState.PREPARING || state == MatchState.ASSIGNED) &&
                server != null &&
                server.gameServerName != gameServerName
        ) {
            throw WrongServerException(matchId, gameServerName)
        }
        if (state == MatchState.ASSIGNED) return true
        if (state != MatchState.PREPARING || server == null) return false
        val modeId = status.modeId.orEmpty()

        if (!ok) {
            if (queue.failRequeue(matchId, modeId, MatchState.PREPARING) >= 0) {
                recordReady(matchId, modeId, status, "failed")
                log.warn(
                    "Server could not prepare the round, requeued its players " +
                        "(id=$matchId, gs=$gameServerName, reason=${reason.orEmpty()})"
                )
            }
            return false
        }

        if (queue.assign(matchId, server, MatchState.PREPARING)) {
            recordReady(matchId, modeId, status, "ok")
            log.info(
                "Assigned match (id=$matchId, gs=${server.gameServerName}, " +
                    "addr=${server.address}:${server.port})"
            )
            return true
        }
        // Lost to a concurrent ready (routed after all) or to a failure (not routed).
        return queue.matchStatus(matchId).state == MatchState.ASSIGNED
    }

    /**
     * The match's status once the allocation worker has recorded the server's acceptance. A server
     * whose round was ready the moment it accepted (a world prepared in advance) can report ready
     * before the worker has moved the match from FORMED to PREPARING; answering that with `routed:
     * false` would throw the round away, so a FORMED match is re-read for a moment.
     */
    private fun statusAfterPush(matchId: String): MatchStatus {
        var status = queue.matchStatus(matchId)
        val deadline = System.nanoTime() + READY_RACE_WAIT.toNanos()
        while (status.state == MatchState.FORMED && System.nanoTime() < deadline) {
            Thread.sleep(READY_RACE_POLL.toMillis())
            status = queue.matchStatus(matchId)
        }
        return status
    }

    /** How long the server prepared, by how it ended, with the timeline log line. */
    private fun recordReady(matchId: String, modeId: String, status: MatchStatus, result: String) {
        val took = status.preparingAt?.let { Duration.between(it, Instant.now()) }
        took?.let { metrics.timeToReady(modeId, result, it) }
        log.info(
            "Match timeline (match=$matchId, mode=$modeId, stage=ready, result=$result, " +
                "gs=${status.server?.gameServerName}" +
                (took?.let { ", readyMs=${it.toMillis()})" } ?: ")")
        )
    }

    /** The maps a mode can be queued for, or null if there is no such mode. */
    fun modeMaps(modeId: String): ModeMapPool? {
        if (modes.find(modeId) == null) return null
        return mapSelection.pool(queue.loadMeta(modeId).maps)
    }

    fun findTicket(ticketId: String): Ticket? = queue.findTicket(ticketId)

    fun queueDepth(modeId: String): Long = queue.queueDepth(modeId)

    /** The assigned ticket of the match [playerId] is playing, for a proxy offering a rejoin. */
    fun activeMatch(playerId: UUID): Ticket? = queue.activeTicket(playerId.toString())

    /** [playerId] left their match for good: no proxy offers it to them again. */
    fun leaveMatch(playerId: UUID) = queue.clearActive(playerId.toString())

    /**
     * [playerId] dropped off the network ([connected] false) or is back in their match (true).
     *
     * A disconnected player keeps their match for the rejoin grace only — after that a login no
     * longer offers it. Back in the match, the entry lives as long as the match again, so a second
     * drop later in the game gets the full grace too.
     *
     * @return false if the player has no active match.
     */
    fun matchPresence(playerId: UUID, connected: Boolean): Boolean =
        if (connected) queue.keepActive(playerId.toString())
        else queue.expireActive(playerId.toString(), rejoinGrace)

    fun isKnownMode(modeId: String): Boolean = modes.find(modeId) != null

    /**
     * Write the mode config through to Valkey before it ever reaches the in-memory registry — so
     * that if the persist fails, the registry never advertises a mode a restart would lose.
     *
     * With a [lease], this is also the renewal: the game server repeats the same call.
     *
     * @return true if the mode did not exist in Valkey before.
     */
    fun upsertMode(config: ModeConfig, meta: ModeMeta, lease: ModeLease?): Boolean {
        val created = queue.upsertMode(config, meta, lease, Instant.now())
        // Valkey decided whether the mode is leased; the registry learns it on the next resync,
        // and the flag only matters to the expiry sweep, which reads Valkey anyway.
        modes.upsert(config)
        return created
    }

    /** Release one lease. Removes the mode, cancelling its waiting tickets, if it was the last. */
    fun releaseLease(modeId: String, leaseId: String) {
        val cancelled = queue.releaseLease(modeId, leaseId, Instant.now()) ?: return
        modes.remove(modeId)
        log.info("Last lease released, removed mode (id=$modeId, cancelled=$cancelled ticket(s))")
    }

    /**
     * Remove a mode outright, permanent or leased, cancelling its waiting tickets.
     *
     * @return false if there was no such mode.
     */
    fun deleteMode(modeId: String): Boolean {
        val cancelled = queue.deleteMode(modeId, Instant.now()) ?: return false
        modes.remove(modeId)
        log.info("Deleted mode (id=$modeId, cancelled=$cancelled ticket(s))")
        return true
    }

    /**
     * Every live mode, read from Valkey rather than this replica's registry so that every replica
     * answers the same. A leased mode whose leases have all expired but that no sweep has removed
     * yet is already left out.
     */
    fun listModes(): List<ModeSummary> {
        val now = Instant.now()
        return queue
            .loadModes()
            .map { config ->
                ModeSummary(
                    config = config,
                    meta = queue.loadMeta(config.modeId),
                    ticketsQueued = queue.queueDepth(config.modeId),
                    leases = queue.liveLeaseCount(config.modeId, now),
                )
            }
            .filter { !it.config.leased || it.leases > 0 }
            .sortedBy { it.config.modeId }
    }

    companion object {
        private val log: Logger = Logger.getLogger(QueueService::class.java)

        /** Longer than the worker needs from the StartMatch reply to the prepare script. */
        private val READY_RACE_WAIT: Duration = Duration.ofSeconds(3)
        private val READY_RACE_POLL: Duration = Duration.ofMillis(50)
    }
}

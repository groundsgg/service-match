package gg.grounds.api

import gg.grounds.domain.ModeConfig
import gg.grounds.domain.Rating
import gg.grounds.domain.RatingRepository
import gg.grounds.domain.Ticket
import gg.grounds.matcher.ModeRegistry
import gg.grounds.metrics.MatchMetrics
import gg.grounds.persistence.ValkeyQueue
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import java.time.Instant
import java.util.UUID
import org.eclipse.microprofile.config.inject.ConfigProperty
import org.jboss.logging.Logger

/** Raised when a player already holds a live ticket. */
class AlreadyQueuedException : RuntimeException("player already holds a live ticket")

/** Raised when the mode has no matchmaking config — forge has not pushed one. */
class UnknownModeException(modeId: String) : RuntimeException("unknown mode: $modeId")

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
    @param:ConfigProperty(name = "grounds.match.ticket.ttl-seconds") private val ticketTtl: Long,
    @param:ConfigProperty(name = "grounds.match.rating.default-mu") private val defaultMu: Double,
    @param:ConfigProperty(name = "grounds.match.rating.default-sigma")
    private val defaultSigma: Double,
) {

    fun enqueue(playerId: UUID, modeId: String): Ticket {
        val mode = modes.find(modeId) ?: throw UnknownModeException(modeId)

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
            )

        if (!queue.enqueue(ticket, ticketTtl)) {
            throw AlreadyQueuedException()
        }
        metrics.ticketEnqueued(mode.modeId)
        return ticket
    }

    fun cancel(ticketId: String, playerId: UUID): Boolean {
        val ticket = queue.findTicket(ticketId) ?: return false
        return queue.cancel(ticketId, playerId.toString(), ticket.modeId)
    }

    fun findTicket(ticketId: String): Ticket? = queue.findTicket(ticketId)

    fun queueDepth(modeId: String): Long = queue.queueDepth(modeId)

    fun isKnownMode(modeId: String): Boolean = modes.find(modeId) != null

    /**
     * Write the mode config through to Valkey before it ever reaches the in-memory registry — so
     * that if the persist fails, the registry never advertises a mode a restart would lose.
     *
     * @return true if this is a new mode, matching `ModeRegistry.upsert`'s existing contract.
     */
    fun upsertMode(config: ModeConfig): Boolean {
        queue.saveMode(config)
        return modes.upsert(config)
    }

    companion object {
        private val log: Logger = Logger.getLogger(QueueService::class.java)
    }
}

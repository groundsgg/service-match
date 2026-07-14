package gg.grounds.api

import gg.grounds.domain.ModeConfig
import gg.grounds.domain.Rating
import gg.grounds.domain.RatingRepository
import gg.grounds.domain.Ticket
import gg.grounds.matcher.ModeRegistry
import gg.grounds.persistence.ValkeyQueue
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import java.time.Instant
import java.util.UUID
import org.eclipse.microprofile.config.inject.ConfigProperty

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
    @param:ConfigProperty(name = "grounds.match.ticket.ttl-seconds") private val ticketTtl: Long,
    @param:ConfigProperty(name = "grounds.match.rating.default-mu") private val defaultMu: Double,
    @param:ConfigProperty(name = "grounds.match.rating.default-sigma")
    private val defaultSigma: Double,
) {

    fun enqueue(playerId: UUID, modeId: String): Ticket {
        val mode = modes.find(modeId) ?: throw UnknownModeException(modeId)

        // Read the rating once, here, and carry it on the ticket. The matcher
        // then works purely from Valkey.
        val stored = ratings.find(playerId, mode.modeId)
        val rating = stored?.rating ?: Rating(defaultMu, defaultSigma)

        val ticket =
            Ticket(
                id = UUID.randomUUID().toString(),
                playerId = playerId.toString(),
                modeId = mode.modeId,
                mu = rating.mu,
                sigma = rating.sigma,
                enqueuedAt = Instant.now(),
            )

        if (!queue.enqueue(ticket, ticketTtl)) {
            throw AlreadyQueuedException()
        }
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
}

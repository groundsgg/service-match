package gg.grounds.matcher

import gg.grounds.domain.BandedMmrMatchFunction
import gg.grounds.domain.MatchFunction
import gg.grounds.domain.ModeConfig
import gg.grounds.domain.Ticket
import gg.grounds.persistence.ValkeyQueue
import io.quarkus.scheduler.Scheduled
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import java.time.Instant
import org.eclipse.microprofile.config.inject.ConfigProperty
import org.jboss.logging.Logger

/**
 * Turns waiting tickets into matches.
 *
 * Timer-driven, not event-driven, and that is not laziness: the band widens with waiting time, so a
 * queue that had no match a moment ago may have one now without anything having happened. A purely
 * event-driven loop could never widen.
 *
 * The loop is allowed to be wrong. It reads a snapshot, asks the match function for proposals, and
 * offers each to the claim script — which may refuse, because a player cancelled or another matcher
 * got there first. A refusal is not an error; it just means the snapshot aged. That is why this can
 * run on several replicas without a leader: correctness lives in the Lua, not here.
 */
@ApplicationScoped
class Matcher
@Inject
constructor(
    private val queue: ValkeyQueue,
    private val modes: ModeRegistry,
    @param:ConfigProperty(name = "grounds.match.ticket.ttl-seconds") private val ticketTtl: Long,
    @param:ConfigProperty(name = "grounds.match.match.ttl-seconds") private val matchTtl: Long,
) {
    private val matchFunction: MatchFunction = BandedMmrMatchFunction()

    @Scheduled(
        every = "{grounds.match.tick}",
        concurrentExecution = Scheduled.ConcurrentExecution.SKIP,
    )
    fun tick() {
        for (mode in modes.all()) {
            try {
                tickMode(mode)
            } catch (e: Exception) {
                log.error("Queue tick failed (mode=${mode.modeId})", e)
            }
        }
    }

    /** Visible for testing — runs one pass over one mode. */
    fun tickMode(mode: ModeConfig, now: Instant = Instant.now()): Int {
        val needed = mode.playersPerMatch
        // Cheap early out: most ticks on most modes have nobody waiting.
        if (queue.queueDepth(mode.modeId) < needed) return 0

        val tickets = snapshot(mode)
        if (tickets.size < needed) return 0

        val proposals = matchFunction.propose(mode, tickets, now)
        var committed = 0

        for (proposal in proposals) {
            val won =
                queue.claim(
                    matchId = proposal.matchId,
                    modeId = mode.modeId,
                    ticketIds = proposal.ticketIds,
                    teamSize = mode.teamSize,
                    now = now,
                    matchTtlSeconds = matchTtl,
                )
            if (won) {
                committed++
                log.info(
                    "Formed match (id=${proposal.matchId}, mode=${mode.modeId}, " +
                        "players=${proposal.ticketIds.size}, spread=${"%.2f".format(proposal.quality)})"
                )
            } else {
                // Someone else took a ticket, or a player cancelled. The next
                // tick works from a fresh snapshot.
                log.debug("Proposal went stale (id=${proposal.matchId}, mode=${mode.modeId})")
            }
        }
        return committed
    }

    private fun snapshot(mode: ModeConfig): List<Ticket> =
        queue.queuedTicketIdsByWait(mode.modeId, SNAPSHOT_LIMIT).mapNotNull { queue.findTicket(it) }

    companion object {
        private val log: Logger = Logger.getLogger(Matcher::class.java)

        /**
         * How many of the longest-waiting tickets a tick considers. A cap keeps the tick bounded on
         * a hot queue; the oldest tickets are the ones that need serving, so taking them first is
         * also the fair order.
         */
        private const val SNAPSHOT_LIMIT = 200
    }
}

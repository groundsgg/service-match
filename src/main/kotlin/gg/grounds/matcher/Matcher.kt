package gg.grounds.matcher

import gg.grounds.domain.BandedMmrMatchFunction
import gg.grounds.domain.MatchFunction
import gg.grounds.domain.MatchRecordRepository
import gg.grounds.domain.ModeConfig
import gg.grounds.domain.Ticket
import gg.grounds.metrics.MatchMetrics
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
    private val matches: MatchRecordRepository,
    private val metrics: MatchMetrics,
    @param:ConfigProperty(name = "grounds.match.ticket.ttl-seconds") private val ticketTtl: Long,
    @param:ConfigProperty(name = "grounds.match.match.ttl-seconds") private val matchTtl: Long,
    @param:ConfigProperty(name = "grounds.match.region") private val region: String,
) {
    private val matchFunction: MatchFunction = BandedMmrMatchFunction()

    @Scheduled(
        every = "{grounds.match.tick}",
        concurrentExecution = Scheduled.ConcurrentExecution.SKIP,
    )
    fun tick() {
        for (mode in modes.all()) {
            try {
                metrics.timeTick(mode.modeId) { tickMode(mode) }
            } catch (e: Exception) {
                log.error("Queue tick failed (mode=${mode.modeId})", e)
            }
        }
    }

    /** Visible for testing — runs one pass over one mode. */
    fun tickMode(mode: ModeConfig, now: Instant = Instant.now()): Int {
        // Register the depth gauge for this mode the first time it ticks — the
        // matcher sees every known mode every pass, so nothing has to remember
        // to do it on the write path.
        metrics.ensureQueueDepthGauge(mode.modeId) { queue.queueDepth(mode.modeId) }

        val needed = mode.playersPerMatch
        // Cheap early out: most ticks on most modes have nobody waiting.
        if (queue.queueDepth(mode.modeId) < needed) return 0

        val tickets = snapshot(mode)
        if (tickets.size < needed) return 0

        val proposals = matchFunction.propose(mode, tickets, now)
        val byId = tickets.associateBy { it.id }
        var committed = 0

        for (proposal in proposals) {
            // Where it gets played, decided here rather than in the allocator:
            // the claim has to record it atomically with the match, or a retry
            // could pick a different region than the first attempt did.
            val target =
                HostRegionPolicy.choose(
                    proposal.ticketIds.mapNotNull { byId[it] },
                    fallback = region,
                )
            val won =
                queue.claim(
                    matchId = proposal.matchId,
                    modeId = mode.modeId,
                    ticketIds = proposal.ticketIds,
                    teamSize = mode.teamSize,
                    target = target,
                    now = now,
                    matchTtlSeconds = matchTtl,
                )
            if (won) {
                committed++
                metrics.matchFormed(mode.modeId, proposal.ticketIds.size)
                recordDurably(proposal, mode, tickets)
                log.info(
                    "Formed match (id=${proposal.matchId}, mode=${mode.modeId}, " +
                        "players=${proposal.ticketIds.size}, host=$target, " +
                        "spread=${"%.2f".format(proposal.quality)})"
                )
            } else {
                // Someone else took a ticket, or a player cancelled. The next
                // tick works from a fresh snapshot.
                log.debug("Proposal went stale (id=${proposal.matchId}, mode=${mode.modeId})")
            }
        }
        return committed
    }

    /**
     * Write the match to Postgres, with its roster.
     *
     * This is what lets the result path later reject a result for a match we never formed, or a
     * placement for a player who was never in it. It happens after the claim rather than before, so
     * a failure here costs this match its rating — not the match itself. Players would rather play
     * an unrated game than no game.
     */
    private fun recordDurably(
        proposal: gg.grounds.domain.MatchProposal,
        mode: ModeConfig,
        tickets: List<Ticket>,
    ) {
        try {
            val byId = tickets.associateBy { it.id }
            val playerIds =
                proposal.ticketIds.mapNotNull { id ->
                    byId[id]?.playerId?.let { java.util.UUID.fromString(it) }
                }
            // One provisional ticket unranks the whole match. Its mu/sigma are
            // the seeded defaults because the rating store would not answer, not
            // because the player is new — rating the result from that prior would
            // move a real rating on a fiction. Unranking is per match rather than
            // per player because the update is joint: every placement in a
            // Plackett-Luce update is computed against the others.
            val provisional = proposal.ticketIds.any { byId[it]?.provisional == true }
            if (provisional) {
                log.warn(
                    "Recording match unranked — a ticket was seeded with default " +
                        "ratings because the store was unreachable (id=${proposal.matchId})"
                )
            }
            matches.recordMatch(
                matchId = java.util.UUID.fromString(proposal.matchId),
                modeId = mode.modeId,
                ranked = mode.ranked && !provisional,
                playerIds = playerIds,
            )
        } catch (e: Exception) {
            log.error(
                "Failed to record match durably; it will play but not rate (id=${proposal.matchId})",
                e,
            )
        }
    }

    private fun snapshot(mode: ModeConfig): List<Ticket> =
        queue.snapshot(mode.modeId, SNAPSHOT_LIMIT)

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

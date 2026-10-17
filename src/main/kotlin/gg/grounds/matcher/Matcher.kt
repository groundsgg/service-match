package gg.grounds.matcher

import gg.grounds.domain.BandedMmrMatchFunction
import gg.grounds.domain.MatchFunction
import gg.grounds.domain.MatchProposal
import gg.grounds.domain.MatchRecordRepository
import gg.grounds.domain.ModeConfig
import gg.grounds.domain.Ticket
import gg.grounds.metrics.MatchMetrics
import gg.grounds.persistence.ValkeyQueue
import gg.grounds.tracing.MatchTracing
import io.quarkus.scheduler.Scheduled
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import java.time.Duration
import java.time.Instant
import java.util.UUID
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
    private val tracing: MatchTracing,
    @param:ConfigProperty(name = "grounds.match.ticket.ttl-seconds") private val ticketTtl: Long,
    @param:ConfigProperty(name = "grounds.match.match.ttl-seconds") private val matchTtl: Long,
) {
    private val matchFunction: MatchFunction = BandedMmrMatchFunction()

    @Scheduled(
        every = "{grounds.match.tick}",
        concurrentExecution = Scheduled.ConcurrentExecution.SKIP,
    )
    fun tick() {
        val now = Instant.now()
        try {
            // Learn what other replicas registered and removed before deciding what to tick.
            modes.resync()
        } catch (e: Exception) {
            log.error("Mode resync failed; ticking the modes known so far", e)
        }
        for (mode in modes.all().toList()) {
            try {
                if (expired(mode, now)) continue
                metrics.timeTick(mode.modeId) { tickMode(mode) }
            } catch (e: Exception) {
                log.error("Queue tick failed (mode=${mode.modeId})", e)
            }
        }
    }

    /**
     * Remove the mode if its last lease has run out. Every replica sweeps every mode on every tick;
     * the script makes that safe, because the first to find the mode dead removes it and the rest
     * then find nothing to do.
     */
    private fun expired(mode: ModeConfig, now: Instant): Boolean {
        val cancelled = queue.expireMode(mode.modeId, now) ?: return false
        modes.remove(mode.modeId)
        log.info(
            "Last lease expired, removed mode (id=${mode.modeId}, cancelled=$cancelled ticket(s))"
        )
        return true
    }

    /** Visible for testing — runs one pass over one mode. */
    fun tickMode(mode: ModeConfig, now: Instant = Instant.now()): Int {
        // Register the depth gauge for this mode the first time it ticks — the
        // matcher sees every known mode every pass, so nothing has to remember
        // to do it on the write path.
        metrics.ensureQueueDepthGauge(mode.modeId) { queue.queueDepth(mode.modeId) }

        val needed = mode.minPlayers
        // Cheap early out: most ticks on most modes have nobody waiting.
        if (queue.queueDepth(mode.modeId) < needed) return 0

        val tickets = snapshot(mode)
        if (tickets.size < needed) return 0

        val proposals = withUniqueShortIds(matchFunction.propose(mode, tickets, now))
        var committed = 0

        val byId = tickets.associateBy { it.id }
        for (proposal in proposals) {
            // The trace is started before the claim so its traceparent can be stored by the
            // same atomic step that creates the match. A claim that loses leaves the span
            // unended, and an unended span is never exported.
            val span =
                tracing.matchmaking(
                    modeId = mode.modeId,
                    matchId = proposal.matchId,
                    players = proposal.ticketIds.size,
                    oldestEnqueuedAt =
                        proposal.ticketIds.mapNotNull { byId[it]?.enqueuedAt }.minOrNull() ?: now,
                )
            val won =
                queue.claim(
                    matchId = proposal.matchId,
                    modeId = mode.modeId,
                    ticketIds = proposal.ticketIds,
                    teamSize = mode.teamSize,
                    now = now,
                    matchTtlSeconds = matchTtl,
                    traceparent = tracing.traceparent(span),
                )
            if (won) {
                span.end(now)
                committed++
                metrics.matchFormed(mode.modeId, proposal.ticketIds.size)
                recordWaits(proposal, mode, tickets, now)
                recordDurably(proposal, mode, tickets)
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

    /**
     * Re-roll every proposal whose short id a recorded match, or another proposal, already has.
     *
     * The short id is what players read off the scoreboard and what prism and the map page resolve,
     * so it has to name one match. One query per tick that formed proposals; if Postgres does not
     * answer, the ids go out unchecked, because players would rather play than wait on it.
     */
    private fun withUniqueShortIds(proposals: List<MatchProposal>): List<MatchProposal> {
        if (proposals.isEmpty()) return proposals
        var result = proposals
        repeat(MAX_SHORT_ID_ROLLS) {
            val taken =
                try {
                    matches.takenShortIds(result.map { it.shortId })
                } catch (e: Exception) {
                    log.warn("Could not check match short ids; claiming them unchecked", e)
                    return result
                }
            val seen = mutableSetOf<String>()
            var rerolled = false
            result =
                result.map { proposal ->
                    if (proposal.shortId !in taken && seen.add(proposal.shortId)) {
                        proposal
                    } else {
                        rerolled = true
                        proposal.copy(matchId = UUID.randomUUID().toString())
                    }
                }
            if (!rerolled) return result
        }
        log.warn("Match short ids still collide after $MAX_SHORT_ID_ROLLS rolls; claiming anyway")
        return result
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

    /** One wait per matched player, measured to the claim — the moment their queue ended. */
    private fun recordWaits(
        proposal: gg.grounds.domain.MatchProposal,
        mode: ModeConfig,
        tickets: List<Ticket>,
        now: Instant,
    ) {
        val byId = tickets.associateBy { it.id }
        for (id in proposal.ticketIds) {
            val enqueuedAt = byId[id]?.enqueuedAt ?: continue
            val waited = Duration.between(enqueuedAt, now)
            metrics.queueWait(mode.modeId, waited)
            metrics.timeToMatch(mode.modeId, waited)
        }
    }

    private fun snapshot(mode: ModeConfig): List<Ticket> =
        queue.queuedTicketIdsByWait(mode.modeId, SNAPSHOT_LIMIT).mapNotNull { queue.findTicket(it) }

    companion object {
        private val log: Logger = Logger.getLogger(Matcher::class.java)

        /**
         * Even with a million recorded matches a fresh short id collides about once in 4,000 draws,
         * so a third roll is all but never needed.
         */
        private const val MAX_SHORT_ID_ROLLS = 3

        /**
         * How many of the longest-waiting tickets a tick considers. A cap keeps the tick bounded on
         * a hot queue; the oldest tickets are the ones that need serving, so taking them first is
         * also the fair order.
         */
        private const val SNAPSHOT_LIMIT = 200
    }
}

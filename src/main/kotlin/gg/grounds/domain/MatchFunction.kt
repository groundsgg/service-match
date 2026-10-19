package gg.grounds.domain

import java.time.Instant
import java.util.UUID
import kotlin.math.abs

/**
 * Proposes matches from a snapshot of a queue.
 *
 * Pure: no I/O, no side effects. It may propose matches that turn out to be stale — a player may
 * have cancelled since the snapshot — because the claim script is the only thing that decides
 * whether a match actually happens. That split is deliberate: it means a match function can be
 * swapped (or replaced by Open Match) without touching the correctness spine.
 */
interface MatchFunction {
    fun propose(mode: ModeConfig, tickets: List<Ticket>, now: Instant): List<MatchProposal>
}

/**
 * MMR matching on a widening band.
 *
 * The anchor is always the longest-waiting ticket — that is what stops anyone starving. Candidates
 * are taken from around the anchor's rating, and the band widens the longer someone waits, so a
 * queue that cannot find a perfect match eventually settles for a decent one.
 *
 * Two rules keep it honest:
 *
 * **Mutuality.** A candidate must fit the anchor's band *and* the anchor must fit theirs. Otherwise
 * a desperate long-waiting player with a huge band would drag a freshly-queued player into a
 * lopsided match they never agreed to.
 *
 * **Mercy, then unilateral.** After `mercySeconds` the anchor's band is unbounded (it will take
 * anyone), and after `mutualSeconds` it stops asking for consent. Without that second step an
 * outlier — the best or worst player on the ladder — would wait forever while everyone else matched
 * around them.
 */
class BandedMmrMatchFunction : MatchFunction {

    override fun propose(
        mode: ModeConfig,
        tickets: List<Ticket>,
        now: Instant,
    ): List<MatchProposal> {
        val needed = mode.playersPerMatch
        val queued = tickets.filter { it.state == TicketState.QUEUED }
        if (queued.size < mode.minPlayers) return emptyList()

        val proposals = mutableListOf<MatchProposal>()
        val taken = mutableSetOf<String>()

        // Oldest first: whoever has waited longest gets served first.
        val byWait = queued.sortedBy { it.enqueuedAt }

        for (anchor in byWait) {
            if (anchor.id in taken) continue

            val remaining = byWait.filter { it.id !in taken && it.id != anchor.id }
            if (remaining.size < needed - 1) break

            val group = gather(anchor, remaining, mode, now, needed)
            if (group == null) continue

            group.forEach { taken += it.id }
            proposals += toProposal(group, mode)
        }

        partial(mode, byWait.filter { it.id !in taken }, now)?.let { proposals += it }
        return proposals
    }

    /**
     * An under-filled free-for-all from whoever is left, once the longest-waiting of them has
     * waited [ModeConfig.fillSeconds] for a full one. Rating plays no part: a mode that would
     * rather start short than wait has already said who it plays against matters less than playing
     * at all.
     */
    private fun partial(mode: ModeConfig, left: List<Ticket>, now: Instant): MatchProposal? {
        if (!mode.allowsPartial || left.size < mode.minPlayers) return null
        if (waitSeconds(left.first(), now) < mode.fillSeconds) return null
        val group = left.take(mode.playersPerMatch)
        val mus = group.map { it.mu }
        return MatchProposal(
            matchId = UUID.randomUUID().toString(),
            teams = group.map { listOf(it.id) },
            quality = (mus.maxOrNull() ?: 0.0) - (mus.minOrNull() ?: 0.0),
        )
    }

    private fun gather(
        anchor: Ticket,
        candidates: List<Ticket>,
        mode: ModeConfig,
        now: Instant,
        needed: Int,
    ): List<Ticket>? {
        val band = mode.band
        val anchorWait = waitSeconds(anchor, now)
        val anchorBand =
            if (anchorWait >= band.mercySeconds) {
                Double.MAX_VALUE // mercy: the whole ladder is fair game
            } else {
                band.bandFor(anchor.sigma, anchorWait)
            }
        // Past this point the anchor stops asking candidates to accept it back.
        val requireMutual = anchorWait < band.mutualSeconds

        val fits =
            candidates.filter { c ->
                val delta = abs(c.mu - anchor.mu)
                if (delta > anchorBand) return@filter false
                if (!requireMutual) return@filter true
                val candidateBand = band.bandFor(c.sigma, waitSeconds(c, now))
                delta <= candidateBand
            }

        if (fits.size < needed - 1) return null

        // Closest in rating first — among everyone who fits, take the fairest.
        val picked = fits.sortedBy { abs(it.mu - anchor.mu) }.take(needed - 1)
        return listOf(anchor) + picked
    }

    /**
     * Snake draft on rating: 1..k, k..1. Splitting by simple halves would stack the strongest
     * players on one side and make the match a foregone conclusion.
     */
    private fun toProposal(group: List<Ticket>, mode: ModeConfig): MatchProposal {
        val ranked = group.sortedByDescending { it.mu }
        val teams = List(mode.teamCount) { mutableListOf<String>() }

        var index = 0
        var forward = true
        for (ticket in ranked) {
            teams[index] += ticket.id
            if (forward) {
                if (index == mode.teamCount - 1) forward = false else index++
            } else {
                if (index == 0) forward = true else index--
            }
        }

        val mus = group.map { it.mu }
        val spread = (mus.maxOrNull() ?: 0.0) - (mus.minOrNull() ?: 0.0)

        return MatchProposal(
            matchId = UUID.randomUUID().toString(),
            teams = teams.map { it.toList() },
            quality = spread,
        )
    }

    private fun waitSeconds(ticket: Ticket, now: Instant): Long =
        maxOf(0, now.epochSecond - ticket.enqueuedAt.epochSecond)
}

/**
 * Keeps map requests apart: a ticket that asked for a map only plays with tickets that asked for
 * the same map or for none, and the match then plays that map.
 *
 * It does so by partitioning rather than by teaching [delegate] about maps, so the rating and
 * banding rules stay exactly what they are. For each requested map — the one the longest-waiting
 * requester asked for first — [delegate] runs over that map's tickets plus every "any" ticket not
 * yet proposed; only proposals holding at least one of that map's requesters are kept, since a
 * match of "any" tickets alone has no reason to play it. A last pass runs over the "any" tickets
 * left. With no map requested in the snapshot this is [delegate], unchanged.
 */
class MapAwareMatchFunction(private val delegate: MatchFunction) : MatchFunction {
    override fun propose(
        mode: ModeConfig,
        tickets: List<Ticket>,
        now: Instant,
    ): List<MatchProposal> {
        val queued = tickets.filter { it.state == TicketState.QUEUED }
        val maps = queued.filter { it.map != null }.sortedBy { it.enqueuedAt }.mapNotNull { it.map }
        if (maps.isEmpty()) return delegate.propose(mode, tickets, now)

        val byId = queued.associateBy { it.id }
        val taken = mutableSetOf<String>()
        val proposals = mutableListOf<MatchProposal>()
        for (map in maps.distinct()) {
            val pool = queued.filter { it.id !in taken && (it.map == null || it.map == map) }
            for (proposal in delegate.propose(mode, pool, now)) {
                if (proposal.ticketIds.none { byId[it]?.map == map }) continue
                proposals += proposal.copy(map = map)
                taken += proposal.ticketIds
            }
        }
        val any = queued.filter { it.id !in taken && it.map == null }
        proposals += delegate.propose(mode, any, now)
        return proposals
    }
}

/** Ignores ratings entirely — oldest tickets, in order. Useful in tests. */
class FifoMatchFunction : MatchFunction {
    override fun propose(
        mode: ModeConfig,
        tickets: List<Ticket>,
        now: Instant,
    ): List<MatchProposal> {
        val queued = tickets.filter { it.state == TicketState.QUEUED }.sortedBy { it.enqueuedAt }
        val needed = mode.playersPerMatch
        return queued
            .chunked(needed)
            .filter { it.size == needed }
            .map { group ->
                MatchProposal(
                    matchId = UUID.randomUUID().toString(),
                    teams = group.map { it.id }.chunked(mode.teamSize),
                    quality = 0.0,
                )
            }
    }
}

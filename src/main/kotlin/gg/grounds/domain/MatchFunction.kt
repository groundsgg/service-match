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
        if (tickets.size < needed) return emptyList()

        val queued = tickets.filter { it.state == TicketState.QUEUED }
        if (queued.size < needed) return emptyList()

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

        return proposals
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

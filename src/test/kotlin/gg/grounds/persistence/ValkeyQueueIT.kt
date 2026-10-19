package gg.grounds.persistence

import gg.grounds.domain.MatchState
import gg.grounds.domain.MatchTimeline
import gg.grounds.domain.ServerAssignment
import gg.grounds.domain.Ticket
import gg.grounds.domain.TicketState
import io.quarkus.redis.datasource.RedisDataSource
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.common.QuarkusTestResourceLifecycleManager
import io.quarkus.test.junit.QuarkusTest
import jakarta.inject.Inject
import java.time.Instant
import java.util.UUID
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.testcontainers.containers.GenericContainer
import org.testcontainers.postgresql.PostgreSQLContainer
import org.testcontainers.utility.DockerImageName

/**
 * The queue spine against a real Valkey.
 *
 * These run the actual Lua, because the guarantees the design rests on are guarantees *of the
 * scripts* — no double-booking, no double-claim, an assignment only one assigner wins. Mocking
 * Valkey here would test nothing that matters.
 */
@QuarkusTest
@QuarkusTestResource(ValkeyQueueIT.ValkeyResource::class)
@QuarkusTestResource(ValkeyQueueIT.PostgresResource::class)
class ValkeyQueueIT {

    @Inject lateinit var queue: ValkeyQueue

    @Inject lateinit var redis: RedisDataSource

    @BeforeEach
    fun clean() {
        redis.execute("FLUSHALL")
        queue.loadScripts()
    }

    private fun ticket(
        player: String,
        mu: Double = 25.0,
        waitedSeconds: Long = 0,
        mode: String = "duel",
    ) =
        Ticket(
            id = UUID.randomUUID().toString(),
            playerId = player,
            modeId = mode,
            mu = mu,
            sigma = 8.33,
            enqueuedAt = Instant.now().minusSeconds(waitedSeconds),
        )

    @Test
    fun `a player may hold only one live ticket`() {
        val first = ticket("p1")
        assertTrue(queue.enqueue(first, TTL))

        // Same player, second attempt. This is the invariant that stops a player
        // being committed to two matches at once.
        assertFalse(queue.enqueue(ticket("p1"), TTL), "second live ticket must be refused")
        assertEquals(1, queue.queueDepth("duel"))
    }

    @Test
    fun `cancelling frees the player to queue again`() {
        val t = ticket("p1")
        queue.enqueue(t, TTL)

        assertTrue(queue.cancel(t.id, "p1", "duel"))
        assertEquals(TicketState.CANCELLED, queue.findTicket(t.id)?.state)
        assertEquals(0, queue.queueDepth("duel"))

        assertTrue(queue.enqueue(ticket("p1"), TTL), "guard must be released on cancel")
    }

    @Test
    fun `a player cannot cancel someone else's ticket`() {
        val t = ticket("p1")
        queue.enqueue(t, TTL)

        assertFalse(queue.cancel(t.id, "someone-else", "duel"))
        assertEquals(TicketState.QUEUED, queue.findTicket(t.id)?.state)
    }

    @Test
    fun `a claim commits the tickets and drains the queue`() {
        val a = ticket("p1")
        val b = ticket("p2")
        queue.enqueue(a, TTL)
        queue.enqueue(b, TTL)

        val matchId = UUID.randomUUID().toString()
        assertTrue(queue.claim(matchId, "duel", listOf(a.id, b.id), 1, Instant.now(), TTL))

        assertEquals(TicketState.MATCHED, queue.findTicket(a.id)?.state)
        assertEquals(matchId, queue.findTicket(a.id)?.matchId)
        assertEquals(0, queue.queueDepth("duel"), "claimed tickets must leave the queue")
    }

    @Test
    fun `the same tickets cannot be claimed twice`() {
        val a = ticket("p1")
        val b = ticket("p2")
        queue.enqueue(a, TTL)
        queue.enqueue(b, TTL)

        assertTrue(
            queue.claim(
                UUID.randomUUID().toString(),
                "duel",
                listOf(a.id, b.id),
                1,
                Instant.now(),
                TTL,
            )
        )

        // A second matcher, working from a stale snapshot, tries the same pair.
        // This is the race the whole design turns on.
        assertFalse(
            queue.claim(
                UUID.randomUUID().toString(),
                "duel",
                listOf(a.id, b.id),
                1,
                Instant.now(),
                TTL,
            ),
            "a ticket must never be committed to two matches",
        )
    }

    @Test
    fun `a cancelled ticket cannot be claimed`() {
        val a = ticket("p1")
        val b = ticket("p2")
        queue.enqueue(a, TTL)
        queue.enqueue(b, TTL)
        queue.cancel(a.id, "p1", "duel")

        assertFalse(
            queue.claim(
                UUID.randomUUID().toString(),
                "duel",
                listOf(a.id, b.id),
                1,
                Instant.now(),
                TTL,
            ),
            "a player who left must not be dragged into a match",
        )
    }

    @Test
    fun `only the first assigner wins`() {
        val a = ticket("p1")
        val b = ticket("p2")
        queue.enqueue(a, TTL)
        queue.enqueue(b, TTL)
        val matchId = UUID.randomUUID().toString()
        queue.claim(matchId, "duel", listOf(a.id, b.id), 1, Instant.now(), TTL)

        assertTrue(queue.assign(matchId, ServerAssignment("gs-a", "10.0.0.1", 25565)))
        assertFalse(
            queue.assign(matchId, ServerAssignment("gs-b", "10.0.0.2", 25565)),
            "two assigners would split the players across two servers",
        )

        // The players must all be pointed at the winner's server.
        assertEquals("gs-a", queue.findTicket(a.id)?.assignment?.gameServerName)
        assertEquals("gs-a", queue.findTicket(b.id)?.assignment?.gameServerName)
    }

    @Test
    fun `assignment releases the guard so the player can queue again afterwards`() {
        val a = ticket("p1")
        val b = ticket("p2")
        queue.enqueue(a, TTL)
        queue.enqueue(b, TTL)
        val matchId = UUID.randomUUID().toString()
        queue.claim(matchId, "duel", listOf(a.id, b.id), 1, Instant.now(), TTL)
        queue.assign(matchId, ServerAssignment("gs", "10.0.0.1", 25565))

        assertEquals(TicketState.ASSIGNED, queue.findTicket(a.id)?.state)
        assertTrue(
            queue.enqueue(ticket("p1"), TTL),
            "the ticket is terminal, so p1 may queue again",
        )
    }

    @Test
    fun `a failed allocation returns the players with the wait they earned`() {
        // p1 has been waiting 5 minutes. If a failed allocation reset that, the
        // unluckiest player would become the hardest to match — exactly backwards.
        val a = ticket("p1", waitedSeconds = 300)
        val b = ticket("p2", waitedSeconds = 10)
        queue.enqueue(a, TTL)
        queue.enqueue(b, TTL)
        val enqueuedAtBefore = queue.findTicket(a.id)!!.enqueuedAt

        val matchId = UUID.randomUUID().toString()
        queue.claim(matchId, "duel", listOf(a.id, b.id), 1, Instant.now(), TTL)

        assertEquals(2, queue.failRequeue(matchId, "duel"))

        val requeued = queue.findTicket(a.id)!!
        assertEquals(TicketState.QUEUED, requeued.state)
        assertNull(requeued.matchId)
        assertEquals(
            enqueuedAtBefore.toEpochMilli(),
            requeued.enqueuedAt.toEpochMilli(),
            "requeue must keep the original wait, not reset the clock",
        )
        assertEquals(2, queue.queueDepth("duel"))
    }

    @Test
    fun `an assigned match is never unwound`() {
        val a = ticket("p1")
        val b = ticket("p2")
        queue.enqueue(a, TTL)
        queue.enqueue(b, TTL)
        val matchId = UUID.randomUUID().toString()
        queue.claim(matchId, "duel", listOf(a.id, b.id), 1, Instant.now(), TTL)
        queue.assign(matchId, ServerAssignment("gs", "10.0.0.1", 25565))

        // The players may already be on the server. Pulling them back into the
        // queue would be worse than any allocation failure.
        assertEquals(0, queue.failRequeue(matchId, "duel"))
        assertEquals(TicketState.ASSIGNED, queue.findTicket(a.id)?.state)
    }

    @Test
    fun `the match lands on the allocation stream as it is committed`() {
        val a = ticket("p1")
        val b = ticket("p2")
        queue.enqueue(a, TTL)
        queue.enqueue(b, TTL)

        val matchId = UUID.randomUUID().toString()
        queue.claim(matchId, "duel", listOf(a.id, b.id), 1, Instant.now(), TTL)

        // The XADD is inside the claim script on purpose: a match that exists but
        // was never queued for allocation would be a match nobody ever plays.
        val len = redis.execute("XLEN", ValkeyQueue.ALLOC_STREAM).toLong()
        assertEquals(1L, len)
    }

    @Test
    fun `an unknown ticket reads as absent rather than blowing up`() {
        assertNull(queue.findTicket("does-not-exist"))
        assertNotNull(queue.findTicket(ticket("p1").also { queue.enqueue(it, TTL) }.id))
    }

    @Test
    fun `a claim puts the match on the watchdog's worklist`() {
        val a = ticket("p1")
        val b = ticket("p2")
        queue.enqueue(a, TTL)
        queue.enqueue(b, TTL)

        val matchId = UUID.randomUUID().toString()
        queue.claim(matchId, "duel", listOf(a.id, b.id), 1, Instant.now(), TTL)

        // A match exists the moment it is claimed, but no server has it yet. If it
        // were not tracked from here, an allocation lost on the way would leave the
        // players waiting on a game that will never start, and nothing on the happy
        // path would ever notice.
        assertTrue(isLive(matchId), "a claimed match must be watched until a server has it")
    }

    @Test
    fun `assignment takes the match off the worklist`() {
        val a = ticket("p1")
        val b = ticket("p2")
        queue.enqueue(a, TTL)
        queue.enqueue(b, TTL)
        val matchId = UUID.randomUUID().toString()
        queue.claim(matchId, "duel", listOf(a.id, b.id), 1, Instant.now(), TTL)

        queue.assign(matchId, ServerAssignment("gs", "10.0.0.1", 25565))

        // The server owns it from here. Keeping it on the worklist would mean the
        // watchdog eventually pulls players out of a game they are in the middle of.
        assertFalse(isLive(matchId), "an assigned match is the server's, not the watchdog's")
    }

    @Test
    fun `a failed requeue takes the match off the worklist`() {
        val a = ticket("p1")
        val b = ticket("p2")
        queue.enqueue(a, TTL)
        queue.enqueue(b, TTL)
        val matchId = UUID.randomUUID().toString()
        queue.claim(matchId, "duel", listOf(a.id, b.id), 1, Instant.now(), TTL)

        queue.failRequeue(matchId, "duel")

        // The players are back on the queue; the match is over as far as anyone is
        // concerned. Leaving it live would have the watchdog requeue them a second
        // time, out of whatever match they have since been put in.
        assertFalse(isLive(matchId))
    }

    @Test
    fun `the roster reads back as teams of players, in draft order`() {
        val a = ticket("p1")
        val b = ticket("p2")
        val c = ticket("p3")
        val d = ticket("p4")
        listOf(a, b, c, d).forEach { queue.enqueue(it, TTL) }

        val matchId = UUID.randomUUID().toString()
        // teamSize 2 → [a,b] against [c,d].
        queue.claim(matchId, "duel", listOf(a.id, b.id, c.id, d.id), 2, Instant.now(), TTL)

        // The claim script stores TICKETS; the server deals in players, and it must
        // get the teams exactly as they were drafted — a server that re-shuffled
        // them would play a different match than the one that gets rated.
        assertEquals(listOf(listOf("p1", "p2"), listOf("p3", "p4")), queue.matchTeams(matchId))
    }

    @Test
    fun `the claim stores every player's enqueue time and the trace`() {
        val a = ticket("p1", waitedSeconds = 30)
        val b = ticket("p2", waitedSeconds = 5)
        queue.enqueue(a, TTL)
        queue.enqueue(b, TTL)
        val traceparent = "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01"
        val formed = Instant.ofEpochMilli(Instant.now().toEpochMilli())

        val matchId = UUID.randomUUID().toString()
        queue.claim(matchId, "duel", listOf(a.id, b.id), 1, formed, TTL, traceparent)

        val key = ValkeyQueue.matchKey(matchId)
        assertEquals(
            "p1=${a.enqueuedAt.toEpochMilli()},p2=${b.enqueuedAt.toEpochMilli()}",
            redis.execute("HGET", key, "enqueuedAt").toString(),
        )
        assertEquals(traceparent, redis.execute("HGET", key, "traceparent").toString())

        val timeline = queue.matchTimeline(matchId)
        assertEquals(formed, timeline.formedAt)
        assertEquals(
            mapOf(
                "p1" to Instant.ofEpochMilli(a.enqueuedAt.toEpochMilli()),
                "p2" to Instant.ofEpochMilli(b.enqueuedAt.toEpochMilli()),
            ),
            timeline.enqueuedAt,
        )
        assertEquals(listOf("p1", "p2"), timeline.enqueuedAt.keys.toList(), "roster order")
        assertEquals(traceparent, timeline.traceparent)
    }

    @Test
    fun `a claim without a trace stores no traceparent`() {
        val a = ticket("p1")
        val b = ticket("p2")
        queue.enqueue(a, TTL)
        queue.enqueue(b, TTL)

        val matchId = UUID.randomUUID().toString()
        queue.claim(matchId, "duel", listOf(a.id, b.id), 1, Instant.now(), TTL)

        assertNull(redis.execute("HGET", ValkeyQueue.matchKey(matchId), "traceparent"))
        assertNull(queue.matchTimeline(matchId).traceparent)
        assertEquals(2, queue.matchTimeline(matchId).enqueuedAt.size)
    }

    @Test
    fun `a match hash from before the timeline still reads`() {
        // Written by the previous claim script: no enqueuedAt, no traceparent.
        val matchId = UUID.randomUUID().toString()
        redis.execute(
            "HSET",
            ValkeyQueue.matchKey(matchId),
            "matchId",
            matchId,
            "modeId",
            "duel",
            "tickets",
            "t1,t2",
            "teams",
            "t1;t2",
            "state",
            "FORMED",
            "attempts",
            "0",
            "createdAt",
            "1760000000000",
        )

        val timeline = queue.matchTimeline(matchId)
        assertEquals(Instant.ofEpochMilli(1_760_000_000_000), timeline.formedAt)
        assertTrue(timeline.enqueuedAt.isEmpty())
        assertNull(timeline.traceparent)

        assertEquals(MatchTimeline(), queue.matchTimeline("does-not-exist"))
    }

    @Test
    fun `an unknown match has no roster rather than blowing up`() {
        assertEquals(emptyList<List<String>>(), queue.matchTeams("does-not-exist"))
    }

    /** p1 against p2, claimed and assigned to [server]; returns the match id and p1's ticket. */
    private fun assignedDuel(server: String = "gs", p1: String = "p1"): Pair<String, Ticket> {
        val a = ticket(p1)
        val b = ticket("p2-${UUID.randomUUID()}")
        queue.enqueue(a, TTL)
        queue.enqueue(b, TTL)
        val matchId = UUID.randomUUID().toString()
        queue.claim(matchId, "duel", listOf(a.id, b.id), 1, Instant.now(), TTL)
        queue.assign(matchId, ServerAssignment(server, "10.0.0.1", 25565))
        return matchId to a
    }

    private fun activeTtl(player: String): Long =
        redis.execute("TTL", ValkeyQueue.activeKey(player)).toLong()

    @Test
    fun `assignment records the match each player is playing`() {
        val (matchId, ticket) = assignedDuel(server = "duel-abc12")

        val active = queue.activeTicket("p1")
        assertEquals(ticket.id, active?.id)
        assertEquals(matchId, active?.matchId)
        assertEquals("duel-abc12", active?.assignment?.gameServerName)
        assertTrue(activeTtl("p1") in 1..TTL, "lives no longer than the match")
    }

    @Test
    fun `a matched but unassigned ticket is not a running match`() {
        val a = ticket("p1")
        val b = ticket("p2")
        queue.enqueue(a, TTL)
        queue.enqueue(b, TTL)
        val matchId = UUID.randomUUID().toString()
        queue.claim(matchId, "duel", listOf(a.id, b.id), 1, Instant.now(), TTL)

        assertNull(queue.activeTicket("p1"))
    }

    @Test
    fun `the end of a match clears its players but not their next match`() {
        val (first, _) = assignedDuel()
        val (second, next) = assignedDuel(server = "gs-next")

        assertEquals(0L, queue.endActive(first, listOf("p1")), "p1 is already in the next match")
        assertEquals(next.id, queue.activeTicket("p1")?.id)

        assertEquals(1L, queue.endActive(second, listOf("p1")))
        assertNull(queue.activeTicket("p1"))
    }

    @Test
    fun `a drop leaves the grace, and coming back restores the match's lifetime`() {
        assignedDuel()

        assertTrue(queue.expireActive("p1", 60))
        assertTrue(activeTtl("p1") in 1..60)

        assertTrue(queue.keepActive("p1"))
        assertTrue(activeTtl("p1") > 60, "back in the match, a later drop gets the full grace")
    }

    @Test
    fun `presence and leaving are no-ops for a player in no match`() {
        assertFalse(queue.expireActive("nobody", 60))
        assertFalse(queue.keepActive("nobody"))
        queue.clearActive("nobody")
        assertNull(queue.activeTicket("nobody"))
    }

    @Test
    fun `leaving clears the running match`() {
        assignedDuel()

        queue.clearActive("p1")

        assertNull(queue.activeTicket("p1"))
    }

    // -----------------------------------------------------------
    // PREPARING: the server has the match and is preparing the round
    // -----------------------------------------------------------

    private val server = ServerAssignment("sg-abc12", "10.0.0.9", 25565)

    /** [players] queued with the given waits, claimed into one free-for-all and PREPARING. */
    private fun preparing(vararg players: Pair<String, Long>): Pair<String, List<Ticket>> {
        val tickets = players.map { (player, waited) -> ticket(player, waitedSeconds = waited) }
        tickets.forEach { assertTrue(queue.enqueue(it, TTL)) }
        val matchId = UUID.randomUUID().toString()
        assertTrue(queue.claim(matchId, "duel", tickets.map { it.id }, 1, Instant.now(), TTL))
        assertTrue(queue.prepare(matchId, server, Instant.now()))
        return matchId to tickets
    }

    private fun isPreparing(matchId: String): Boolean =
        redis.execute("ZSCORE", ValkeyQueue.PREPARING_MATCHES, matchId) != null

    @Test
    fun `a preparing match routes nobody yet and keeps every guard`() {
        val (matchId, tickets) = preparing("p1" to 0L, "p2" to 0L)

        val t = queue.findTicket(tickets[0].id)!!
        assertEquals(TicketState.PREPARING, t.state)
        assertNull(t.assignment, "a ticket carries a server only once it is ASSIGNED")
        assertNull(queue.activeTicket("p1"))
        assertFalse(queue.enqueue(ticket("p1"), TTL), "still the player's one live ticket")

        val status = queue.matchStatus(matchId)
        assertEquals(MatchState.PREPARING, status.state)
        assertEquals(server, status.server)
        assertNotNull(status.preparingAt)
        assertTrue(isLive(matchId), "still watched until it is assigned")
        assertTrue(isPreparing(matchId))
        assertEquals(1, queue.preparingCount())
    }

    @Test
    fun `only a formed match starts preparing`() {
        val (matchId, _) = preparing("p1" to 0L, "p2" to 0L)

        assertFalse(queue.prepare(matchId, server, Instant.now()), "already PREPARING")
        queue.failRequeue(matchId, "duel")
        assertFalse(queue.prepare(matchId, server, Instant.now()), "failed while in flight")
    }

    @Test
    fun `ready assigns a preparing match`() {
        val (matchId, tickets) = preparing("p1" to 0L, "p2" to 0L)

        assertTrue(queue.assign(matchId, server, MatchState.PREPARING))

        val t = queue.findTicket(tickets[0].id)!!
        assertEquals(TicketState.ASSIGNED, t.state)
        assertEquals(server, t.assignment)
        assertEquals(tickets[0].id, queue.activeTicket("p1")?.id)
        assertEquals(MatchState.ASSIGNED, queue.matchStatus(matchId).state)
        assertFalse(isLive(matchId))
        assertFalse(isPreparing(matchId))
        assertTrue(queue.enqueue(ticket("p1"), TTL), "the guard is released at assignment")
    }

    @Test
    fun `ready on a match that is not preparing assigns nothing`() {
        // Still FORMED: the server never said it would prepare.
        val a = ticket("p1")
        val b = ticket("p2")
        queue.enqueue(a, TTL)
        queue.enqueue(b, TTL)
        val formed = UUID.randomUUID().toString()
        queue.claim(formed, "duel", listOf(a.id, b.id), 1, Instant.now(), TTL)
        assertFalse(queue.assign(formed, server, MatchState.PREPARING))
        assertEquals(TicketState.MATCHED, queue.findTicket(a.id)?.state)

        // Failed while preparing: its players are back in the queue and must stay there.
        val (failed, tickets) = preparing("p3" to 0L, "p4" to 0L)
        queue.failRequeue(failed, "duel", MatchState.PREPARING)
        assertFalse(queue.assign(failed, server, MatchState.PREPARING))
        assertEquals(TicketState.QUEUED, queue.findTicket(tickets[0].id)?.state)
    }

    @Test
    fun `a failed preparation requeues with the original wait, once`() {
        val (matchId, tickets) = preparing("p1" to 300L, "p2" to 10L)
        val enqueuedAt = queue.findTicket(tickets[0].id)!!.enqueuedAt

        assertEquals(2, queue.failRequeue(matchId, "duel", MatchState.PREPARING))

        val requeued = queue.findTicket(tickets[0].id)!!
        assertEquals(TicketState.QUEUED, requeued.state)
        assertNull(requeued.matchId)
        assertEquals(enqueuedAt.toEpochMilli(), requeued.enqueuedAt.toEpochMilli())
        assertEquals(
            enqueuedAt.toEpochMilli().toDouble(),
            redis
                .execute("ZSCORE", ValkeyQueue.waitKey("duel"), tickets[0].id)
                .toString()
                .toDouble(),
        )
        assertEquals(2, queue.queueDepth("duel"))
        assertFalse(isLive(matchId))
        assertFalse(isPreparing(matchId))
        assertEquals(
            -1,
            queue.failRequeue(matchId, "duel", MatchState.PREPARING),
            "a second sweep finds it already failed",
        )
    }

    @Test
    fun `a match whose mode is gone cancels its preparing tickets`() {
        val (matchId, tickets) = preparing("p1" to 0L, "p2" to 0L)

        assertEquals(2, queue.failCancel(matchId))

        assertEquals(TicketState.CANCELLED, queue.findTicket(tickets[0].id)?.state)
        assertFalse(isPreparing(matchId))
        assertTrue(queue.enqueue(ticket("p1"), TTL))
    }

    @Test
    fun `leaving a preparing match that still has enough players lets it go ahead`() {
        val (matchId, tickets) = preparing("p1" to 0L, "p2" to 0L, "p3" to 0L)

        assertEquals(Withdrawal.CANCELLED, queue.withdraw(tickets[0].id, "p1", "duel", 2))

        assertEquals(TicketState.CANCELLED, queue.findTicket(tickets[0].id)?.state)
        assertTrue(queue.enqueue(ticket("p1"), TTL), "the leaver may queue again at once")
        assertEquals(MatchState.PREPARING, queue.matchStatus(matchId).state)

        // Ready routes the two who stayed, and not the one who left.
        assertTrue(queue.assign(matchId, server, MatchState.PREPARING))
        assertEquals(TicketState.CANCELLED, queue.findTicket(tickets[0].id)?.state)
        assertEquals(TicketState.ASSIGNED, queue.findTicket(tickets[1].id)?.state)
        assertEquals(TicketState.ASSIGNED, queue.findTicket(tickets[2].id)?.state)
        assertNull(queue.activeTicket("p1"))
    }

    @Test
    fun `leaving a preparing match below its minimum requeues the rest`() {
        val (matchId, tickets) = preparing("p1" to 0L, "p2" to 120L)
        val enqueuedAt = queue.findTicket(tickets[1].id)!!.enqueuedAt

        assertEquals(
            Withdrawal.CANCELLED_MATCH_FAILED,
            queue.withdraw(tickets[0].id, "p1", "duel", 2),
        )

        assertEquals(TicketState.CANCELLED, queue.findTicket(tickets[0].id)?.state)
        val stayed = queue.findTicket(tickets[1].id)!!
        assertEquals(TicketState.QUEUED, stayed.state)
        assertEquals(enqueuedAt.toEpochMilli(), stayed.enqueuedAt.toEpochMilli())
        assertEquals(1, queue.queueDepth("duel"))
        assertFalse(queue.enqueue(ticket("p2"), TTL), "p2 keeps their one live ticket")
        assertEquals(MatchState.FAILED, queue.matchStatus(matchId).state)
        assertFalse(isLive(matchId))
        assertFalse(isPreparing(matchId))
    }

    @Test
    fun `a matched ticket still cannot be withdrawn`() {
        val a = ticket("p1")
        val b = ticket("p2")
        queue.enqueue(a, TTL)
        queue.enqueue(b, TTL)
        queue.claim(UUID.randomUUID().toString(), "duel", listOf(a.id, b.id), 1, Instant.now(), TTL)

        assertEquals(Withdrawal.REFUSED, queue.withdraw(a.id, "p1", "duel", 1))
        assertEquals(TicketState.MATCHED, queue.findTicket(a.id)?.state)
    }

    @Test
    fun `the prepare worklist lists matches by when they started preparing`() {
        val (old, _) = preparing("p1" to 0L, "p2" to 0L)
        redis.execute("ZADD", ValkeyQueue.PREPARING_MATCHES, "1000", old)
        val (young, _) = preparing("p3" to 0L, "p4" to 0L)

        assertEquals(listOf(old), queue.preparingSince(Instant.ofEpochMilli(5000)))
        assertEquals(listOf(old, young), queue.preparingSince(Instant.now().plusSeconds(1)))
        queue.forgetPreparing(old)
        assertEquals(1, queue.preparingCount())
    }

    // -----------------------------------------------------------
    // Map requests and the chosen map
    // -----------------------------------------------------------

    @Test
    fun `a ticket keeps the map it asked for`() {
        val asked = ticket("p1").copy(map = "survivalgames/aura")
        val any = ticket("p2")
        queue.enqueue(asked, TTL)
        queue.enqueue(any, TTL)

        assertEquals("survivalgames/aura", queue.findTicket(asked.id)?.map)
        assertNull(queue.findTicket(any.id)?.map)

        val matchId = UUID.randomUUID().toString()
        queue.claim(matchId, "duel", listOf(any.id, asked.id), 1, Instant.now(), TTL)
        assertEquals("survivalgames/aura", queue.requestedMap(matchId))
    }

    @Test
    fun `a match nobody asked a map for has no requested map`() {
        val a = ticket("p1")
        val b = ticket("p2")
        queue.enqueue(a, TTL)
        queue.enqueue(b, TTL)
        val matchId = UUID.randomUUID().toString()
        queue.claim(matchId, "duel", listOf(a.id, b.id), 1, Instant.now(), TTL)

        assertNull(queue.requestedMap(matchId))
        assertNull(queue.chosenMap(matchId), "nothing chosen yet")

        queue.storeMap(matchId, "", "NONE")
        assertEquals("", queue.chosenMap(matchId), "chosen: no map")
        queue.storeMap(matchId, "duel/arena", "RANDOM")
        assertEquals("duel/arena", queue.chosenMap(matchId))
    }

    private fun isLive(matchId: String): Boolean =
        redis.execute("SISMEMBER", ValkeyQueue.LIVE_MATCHES, matchId).toLong() == 1L

    companion object {
        private const val TTL = 3600L
    }

    /**
     * The app boots as a whole under @QuarkusTest, so the datasource has to resolve even though
     * these tests never touch it.
     */
    class PostgresResource : QuarkusTestResourceLifecycleManager {
        private lateinit var container: PostgreSQLContainer

        override fun start(): Map<String, String> {
            // An existing database instead of a container, for a machine without Docker.
            System.getenv("TEST_POSTGRES_URL")?.let { url ->
                return mapOf(
                    "quarkus.datasource.jdbc.url" to url,
                    "quarkus.datasource.username" to (System.getenv("TEST_POSTGRES_USER") ?: "app"),
                    "quarkus.datasource.password" to
                        (System.getenv("TEST_POSTGRES_PASSWORD") ?: "app"),
                )
            }
            container = PostgreSQLContainer(DockerImageName.parse("postgres:16-alpine"))
            container.start()
            return mapOf(
                // Flyway creates and migrates the `match` schema; without
                // currentSchema the queries would look in `public` and find
                // nothing.
                "quarkus.datasource.jdbc.url" to
                    // Testcontainers' url already carries query params, so the
                    // separator depends on what is there.
                    container.jdbcUrl +
                        (if ("?" in container.jdbcUrl) "&" else "?") +
                        "currentSchema=match",
                "quarkus.datasource.username" to container.username,
                "quarkus.datasource.password" to container.password,
            )
        }

        override fun stop() {
            if (this::container.isInitialized) container.stop()
        }
    }

    /** A real Valkey. The Lua is the thing under test; a fake would prove nothing. */
    class ValkeyResource : QuarkusTestResourceLifecycleManager {
        private lateinit var container: GenericContainer<*>

        override fun start(): Map<String, String> {
            System.getenv("TEST_VALKEY_URL")?.let {
                return mapOf("quarkus.redis.hosts" to it)
            }
            container =
                GenericContainer(DockerImageName.parse("valkey/valkey:8-alpine"))
                    .withExposedPorts(6379)
            container.start()
            return mapOf(
                "quarkus.redis.hosts" to
                    "redis://${container.host}:${container.getMappedPort(6379)}"
            )
        }

        override fun stop() {
            if (this::container.isInitialized) container.stop()
        }
    }
}

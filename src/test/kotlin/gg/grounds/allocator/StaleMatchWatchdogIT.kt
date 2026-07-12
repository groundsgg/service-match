package gg.grounds.allocator

import gg.grounds.domain.ServerAssignment
import gg.grounds.domain.Ticket
import gg.grounds.domain.TicketState
import gg.grounds.persistence.ValkeyQueue
import gg.grounds.persistence.ValkeyQueueIT
import io.quarkus.redis.datasource.RedisDataSource
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.junit.QuarkusTest
import jakarta.inject.Inject
import java.time.Instant
import java.util.UUID
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * The watchdog against a real Valkey.
 *
 * This is the piece that replaced the GameServer reaper, and it is worth testing on its own,
 * because the failure it covers is invisible from everywhere else: a match is claimed, a slot is
 * allocated, and the push to the server is lost. From Valkey's point of view that match looks
 * perfectly healthy. Only the clock says otherwise.
 *
 * The scheduler is off under test (`quarkus.scheduler.enabled=false`), so the sweep is driven by
 * hand — and staleness is set up by *backdating the claim* rather than by sleeping, since the
 * `createdAt` the watchdog reads is whatever `now` the claim was given.
 */
@QuarkusTest
@QuarkusTestResource(ValkeyQueueIT.ValkeyResource::class)
@QuarkusTestResource(ValkeyQueueIT.PostgresResource::class)
class StaleMatchWatchdogIT {

    @Inject lateinit var watchdog: StaleMatchWatchdog

    @Inject lateinit var queue: ValkeyQueue

    @Inject lateinit var redis: RedisDataSource

    @BeforeEach
    fun clean() {
        redis.execute("FLUSHALL")
        queue.loadScripts()
    }

    @Test
    fun `a match that never started puts its players back on the queue`() {
        val (a, b) = twoQueuedPlayers()
        val matchId = claim(a, b, at = longAgo())

        watchdog.sweep()

        // The match was formed and then nothing ever happened to it. Rather than
        // leave the players staring at a lobby forever, they go back on the queue.
        assertEquals(TicketState.QUEUED, queue.findTicket(a.id)?.state)
        assertEquals(TicketState.QUEUED, queue.findTicket(b.id)?.state)
        assertEquals(2, queue.queueDepth("duel"))
        assertFalse(isLive(matchId), "a rescued match is done being watched")
    }

    @Test
    fun `the rescued players keep the wait they had earned`() {
        // The whole point of requeueing rather than telling them to type /queue
        // again: a lost allocation is not the player's fault, and the wait they
        // already served is what widens their matching band.
        val a = ticket("p1", waitedSeconds = 300)
        val b = ticket("p2", waitedSeconds = 300)
        queue.enqueue(a, TTL)
        queue.enqueue(b, TTL)
        val enqueuedAtBefore = queue.findTicket(a.id)!!.enqueuedAt
        claim(a, b, at = longAgo())

        watchdog.sweep()

        assertEquals(
            enqueuedAtBefore.toEpochMilli(),
            queue.findTicket(a.id)!!.enqueuedAt.toEpochMilli(),
            "the watchdog must not reset the clock on players it rescues",
        )
    }

    @Test
    fun `a match that is merely young is left alone`() {
        val (a, b) = twoQueuedPlayers()
        val matchId = claim(a, b, at = Instant.now())

        watchdog.sweep()

        // Allocation takes time. A match that was formed a moment ago is not
        // stalled, it is simply in flight — requeueing it would fight the
        // allocator for its own players.
        assertEquals(TicketState.MATCHED, queue.findTicket(a.id)?.state)
        assertEquals(0, queue.queueDepth("duel"))
        assertTrue(isLive(matchId), "an in-flight match stays on the worklist")
    }

    @Test
    fun `an assigned match is never requeued, however old it gets`() {
        val (a, b) = twoQueuedPlayers()
        val matchId = claim(a, b, at = longAgo())
        queue.assign(matchId, ServerAssignment("gs", "10.0.0.1", 25565))

        // Assign already drops the match from the worklist, so force it back on to
        // exercise the watchdog's own guard rather than the script's. An ASSIGNED
        // match that is old is not a stalled match — it is a long game, and its
        // players are in the middle of a fight.
        redis.execute("SADD", ValkeyQueue.LIVE_MATCHES, matchId)

        watchdog.sweep()

        assertEquals(TicketState.ASSIGNED, queue.findTicket(a.id)?.state)
        assertEquals(0, queue.queueDepth("duel"), "players in a live game must not be requeued")
        assertFalse(isLive(matchId), "and the watchdog stops carrying it")
    }

    @Test
    fun `a match that has expired out from under the worklist is just dropped`() {
        val matchId = UUID.randomUUID().toString()
        redis.execute("SADD", ValkeyQueue.LIVE_MATCHES, matchId)

        watchdog.sweep()

        // The match hash is gone (TTL), so there is nothing to rescue and nobody to
        // rescue. The worklist entry is the only thing left of it.
        assertFalse(isLive(matchId))
    }

    private fun twoQueuedPlayers(): Pair<Ticket, Ticket> {
        val a = ticket("p1")
        val b = ticket("p2")
        queue.enqueue(a, TTL)
        queue.enqueue(b, TTL)
        return a to b
    }

    private fun claim(a: Ticket, b: Ticket, at: Instant): String {
        val matchId = UUID.randomUUID().toString()
        queue.claim(matchId, "duel", listOf(a.id, b.id), 1, at, TTL)
        return matchId
    }

    /** Older than any `stale-after-ms` a sane deployment would set. */
    private fun longAgo(): Instant = Instant.now().minusSeconds(3600)

    private fun isLive(matchId: String): Boolean =
        redis.execute("SISMEMBER", ValkeyQueue.LIVE_MATCHES, matchId).toLong() == 1L

    private fun ticket(player: String, waitedSeconds: Long = 0) =
        Ticket(
            id = UUID.randomUUID().toString(),
            playerId = player,
            modeId = "duel",
            mu = 25.0,
            sigma = 8.33,
            enqueuedAt = Instant.now().minusSeconds(waitedSeconds),
        )

    companion object {
        private const val TTL = 3600L
    }
}

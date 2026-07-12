package gg.grounds.persistence

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
import org.testcontainers.containers.PostgreSQLContainer
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
        private lateinit var container: PostgreSQLContainer<*>

        override fun start(): Map<String, String> {
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

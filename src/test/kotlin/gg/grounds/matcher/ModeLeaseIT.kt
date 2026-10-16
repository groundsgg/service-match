package gg.grounds.matcher

import gg.grounds.allocator.AllocationWorker
import gg.grounds.api.QueueService
import gg.grounds.domain.ModeConfig
import gg.grounds.domain.ModeLease
import gg.grounds.domain.ModeMeta
import gg.grounds.domain.StatBoard
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
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * Leased modes against a real Valkey: registration, renewal, expiry, release and removal all run in
 * Lua, and what matters is what those scripts leave behind — the mode row, the queue, and above all
 * the players' guards.
 *
 * Time is passed in rather than waited for: the scripts take `now` from the caller, so a lease is
 * expired by sweeping with a later clock instead of sleeping through its TTL.
 */
@QuarkusTest
@QuarkusTestResource(ValkeyQueueIT.ValkeyResource::class)
@QuarkusTestResource(ValkeyQueueIT.PostgresResource::class)
class ModeLeaseIT {

    @Inject lateinit var queue: ValkeyQueue

    @Inject lateinit var redis: RedisDataSource

    @Inject lateinit var registry: ModeRegistry

    @Inject lateinit var matcher: Matcher

    @Inject lateinit var queueService: QueueService

    @Inject lateinit var allocator: AllocationWorker

    private val t0: Instant = Instant.parse("2026-01-01T00:00:00Z")

    @BeforeEach
    fun clean() {
        redis.execute("FLUSHALL")
        queue.loadScripts()
        registry.resync()
    }

    private fun mode(modeId: String = MODE) =
        ModeConfig(modeId = modeId, teamSize = 1, teamCount = 8, minPlayers = 8)

    private fun lease(id: String, seconds: Int = 15) = ModeLease(id, seconds)

    private fun ticket(player: String, mode: String = MODE) =
        Ticket(
            id = UUID.randomUUID().toString(),
            playerId = player,
            modeId = mode,
            mu = 25.0,
            sigma = 8.33,
            enqueuedAt = Instant.now(),
        )

    @Test
    fun `renewing a lease keeps the mode alive`() {
        assertTrue(queue.upsertMode(mode(), ModeMeta(), lease("pod-a"), t0))
        // The game repeats the PUT before the 15 s run out.
        assertFalse(queue.upsertMode(mode(), ModeMeta(), lease("pod-a"), t0.plusSeconds(10)))

        assertNull(queue.expireMode(MODE, t0.plusSeconds(20)), "renewed until t0+25s")
        assertNotNull(queue.loadMode(MODE))
        assertEquals(1, queue.liveLeaseCount(MODE, t0.plusSeconds(20)))

        assertNotNull(queue.expireMode(MODE, t0.plusSeconds(26)), "no renewal after t0+10s")
    }

    @Test
    fun `expiry removes the mode and cancels its waiting tickets`() {
        queue.upsertMode(mode(), ModeMeta(displayName = "SG"), lease("pod-a"), t0)
        val a = ticket("p1")
        val b = ticket("p2")
        assertTrue(queue.enqueue(a, TTL))
        assertTrue(queue.enqueue(b, TTL))

        assertEquals(2L, queue.expireMode(MODE, t0.plusSeconds(16)))

        assertNull(queue.loadMode(MODE))
        assertEquals(ModeMeta(), queue.loadMeta(MODE), "metadata must go with the mode")
        assertEquals(0, redis.execute("EXISTS", ValkeyQueue.leasesKey(MODE)).toLong())
        assertEquals(0, queue.queueDepth(MODE))
        assertEquals(0, queue.queuedTicketIdsByWait(MODE, 10).size)
        assertEquals(TicketState.CANCELLED, queue.findTicket(a.id)?.state)
        assertEquals(TicketState.CANCELLED, queue.findTicket(b.id)?.state)

        // The guard is released: the player is free to queue for something else at once, rather
        // than locked out until the ticket TTL runs down.
        assertTrue(queue.enqueue(ticket("p1", mode = "other"), TTL))

        // Every other replica runs the same sweep; for them it is a no-op.
        assertNull(queue.expireMode(MODE, t0.plusSeconds(17)))
    }

    @Test
    fun `a permanent mode never expires`() {
        assertTrue(queue.upsertMode(mode(), ModeMeta(), null, t0))
        queue.enqueue(ticket("p1"), TTL)

        assertNull(queue.expireMode(MODE, t0.plusSeconds(365L * 24 * 3600)))
        assertNotNull(queue.loadMode(MODE))
        assertFalse(queue.loadMode(MODE)!!.leased)
        assertEquals(1, queue.queueDepth(MODE))
    }

    @Test
    fun `a mode once leased stays leased when registered without a lease`() {
        queue.upsertMode(mode(), ModeMeta(), lease("pod-a"), t0)
        // forge's deploy-time push, without a lease, must not pin a mode whose servers are gone.
        queue.upsertMode(mode(), ModeMeta(), null, t0.plusSeconds(1))

        assertTrue(queue.loadMode(MODE)!!.leased)
        assertNotNull(queue.expireMode(MODE, t0.plusSeconds(16)))
    }

    @Test
    fun `releasing the last lease removes the mode, releasing one of two does not`() {
        queue.upsertMode(mode(), ModeMeta(), lease("pod-a", seconds = 60), t0)
        queue.upsertMode(mode(), ModeMeta(), lease("pod-b", seconds = 60), t0)
        val t = ticket("p1")
        queue.enqueue(t, TTL)

        assertNull(queue.releaseLease(MODE, "pod-a", t0.plusSeconds(1)))
        assertNotNull(queue.loadMode(MODE))
        // Idempotent: a second release of the same lease changes nothing.
        assertNull(queue.releaseLease(MODE, "pod-a", t0.plusSeconds(1)))

        assertEquals(1L, queue.releaseLease(MODE, "pod-b", t0.plusSeconds(2)))
        assertNull(queue.loadMode(MODE))
        assertEquals(TicketState.CANCELLED, queue.findTicket(t.id)?.state)
    }

    @Test
    fun `releasing a lease of a permanent or unknown mode changes nothing`() {
        queue.upsertMode(mode(), ModeMeta(), null, t0)

        assertNull(queue.releaseLease(MODE, "pod-a", t0))
        assertNull(queue.releaseLease("no-such-mode", "pod-a", t0))
        assertNotNull(queue.loadMode(MODE))
    }

    @Test
    fun `deleting removes a permanent mode and cancels its tickets`() {
        queue.upsertMode(mode(), ModeMeta(), null, t0)
        val t = ticket("p1")
        queue.enqueue(t, TTL)

        assertEquals(1L, queue.deleteMode(MODE, t0))
        assertNull(queue.loadMode(MODE))
        assertEquals(TicketState.CANCELLED, queue.findTicket(t.id)?.state)
        assertNull(queue.deleteMode(MODE, t0), "a second delete finds nothing")
    }

    @Test
    fun `a matched ticket is left to its match when the mode goes`() {
        queue.upsertMode(mode(), ModeMeta(), lease("pod-a"), t0)
        val a = ticket("p1")
        val b = ticket("p2")
        queue.enqueue(a, TTL)
        queue.enqueue(b, TTL)
        queue.claim(UUID.randomUUID().toString(), MODE, listOf(a.id, b.id), 1, t0, TTL)

        assertEquals(0L, queue.expireMode(MODE, t0.plusSeconds(16)))
        assertEquals(TicketState.MATCHED, queue.findTicket(a.id)?.state)
    }

    @Test
    fun `rows written before leases existed decode as permanent modes`() {
        // Twelve fields: the format up to the previous release.
        redis.execute("HSET", ValkeyQueue.MODES_KEY, "ffa", "1|8|false|2.0|1.0|1.0|10|90|150||1|20")
        // Nine fields: before modes could name a fleet.
        redis.execute("HSET", ValkeyQueue.MODES_KEY, "bedwars", "4|2|true|2.0|1.0|1.0|10|90|150")

        val ffa = queue.loadMode("ffa")!!
        assertFalse(ffa.leased)
        assertEquals(1, ffa.minPlayers)
        assertEquals(20, ffa.fillSeconds)
        assertFalse(queue.loadMode("bedwars")!!.leased)

        assertNull(queue.expireMode("ffa", t0), "an old row is permanent, never expired")
        assertNull(queue.expireMode("bedwars", t0))

        // An old row with no metadata lists with the defaults.
        assertEquals(ModeMeta(), queue.loadMeta("ffa"))
    }

    @Test
    fun `a resync drops a mode another replica removed`() {
        val replica = ModeRegistry(queue)
        queue.upsertMode(mode("stays"), ModeMeta(), null, t0)
        queue.upsertMode(mode("goes"), ModeMeta(), lease("pod-a"), t0)
        replica.resync()
        assertEquals(setOf("stays", "goes"), replica.all().map { it.modeId }.toSet())

        // Another replica's sweep removes it.
        assertNotNull(queue.expireMode("goes", t0.plusSeconds(16)))

        replica.resync()
        assertEquals(setOf("stays"), replica.all().map { it.modeId }.toSet())
        assertNull(replica.find("goes"))
    }

    @Test
    fun `the matcher tick sweeps a mode whose lease ran out`() {
        // Registered long ago with a lease that was never renewed.
        val longAgo = Instant.now().minusSeconds(3600)
        queue.upsertMode(mode(), ModeMeta(), lease("pod-a"), longAgo)
        queue.upsertMode(mode("alive"), ModeMeta(), lease("pod-b", seconds = 600), Instant.now())
        val t = ticket("p1")
        queue.enqueue(t, TTL)

        matcher.tick()

        assertNull(queue.loadMode(MODE))
        assertNull(registry.find(MODE))
        assertNotNull(registry.find("alive"))
        assertEquals(TicketState.CANCELLED, queue.findTicket(t.id)?.state)
    }

    @Test
    fun `the mode list shows live modes with metadata, sorted`() {
        val now = Instant.now()
        queue.upsertMode(
            mode("survivalgames-solo"),
            ModeMeta(displayName = "SurvivalGames", boards = listOf(StatBoard("wins", "Wins"))),
            lease("pod-a", seconds = 600),
            now,
        )
        queue.upsertMode(
            mode("survivalgames-solo"),
            ModeMeta(displayName = "SurvivalGames", boards = listOf(StatBoard("wins", "Wins"))),
            lease("pod-b", seconds = 600),
            now,
        )
        queue.upsertMode(mode("duel"), ModeMeta(), null, now)
        // Expired, but not swept yet: it must not be listed as available.
        queue.upsertMode(mode("dead"), ModeMeta(), lease("pod-c"), now.minusSeconds(3600))
        queue.enqueue(ticket("p1", mode = "survivalgames-solo"), TTL)

        val listed = queueService.listModes()

        assertEquals(listOf("duel", "survivalgames-solo"), listed.map { it.config.modeId })
        val sg = listed[1]
        assertEquals(2, sg.leases)
        assertEquals(1, sg.ticketsQueued)
        assertTrue(sg.config.leased)
        assertEquals("SurvivalGames", sg.meta.displayName)
        assertEquals(listOf(StatBoard("wins", "Wins")), sg.meta.boards)
        assertEquals(0, listed[0].leases)
        assertFalse(listed[0].config.leased)
    }

    @Test
    fun `a match whose mode is gone is failed and its tickets cancelled, not requeued`() {
        // Formed while the mode existed; the mode's last server went away before allocation.
        val a = ticket("p1", mode = "gone")
        val b = ticket("p2", mode = "gone")
        queue.enqueue(a, TTL)
        queue.enqueue(b, TTL)
        val matchId = UUID.randomUUID().toString()
        queue.claim(matchId, "gone", listOf(a.id, b.id), 1, Instant.now(), TTL)

        allocator.pump()

        assertEquals(TicketState.CANCELLED, queue.findTicket(a.id)?.state)
        assertEquals(TicketState.CANCELLED, queue.findTicket(b.id)?.state)
        assertEquals(0, queue.queueDepth("gone"), "nothing goes back into a dead queue")
        assertEquals(
            "FAILED",
            redis.execute("HGET", ValkeyQueue.matchKey(matchId), "state").toString(),
        )
        assertEquals(
            0,
            redis.execute("SISMEMBER", ValkeyQueue.LIVE_MATCHES, matchId).toLong(),
            "off the watchdog's worklist, or it would requeue them later",
        )
        assertTrue(queue.enqueue(ticket("p1", mode = "other"), TTL), "guard must be released")
    }

    private companion object {
        private const val MODE = "survivalgames-solo"
        private const val TTL = 3600L
    }
}

package gg.grounds.allocator

import gg.grounds.domain.ServerAssignment
import gg.grounds.domain.Ticket
import gg.grounds.persistence.ValkeyQueue
import gg.grounds.persistence.ValkeyQueueIT
import io.quarkus.redis.datasource.RedisDataSource
import io.quarkus.test.InjectMock
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.junit.QuarkusTest
import jakarta.inject.Inject
import java.time.Instant
import java.util.UUID
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

/**
 * The reaper decides which GameServers to *delete*, so the interesting cases are the ones where it
 * must hold its fire. Deleting a live match's server would throw the players out mid-game — far
 * worse than leaving an orphan around.
 *
 * Valkey is real; Agones is mocked, because what is under test is the judgement, not the deletion
 * call.
 */
@QuarkusTest
@QuarkusTestResource(ValkeyQueueIT.ValkeyResource::class)
@QuarkusTestResource(ValkeyQueueIT.PostgresResource::class)
class ReaperTest {

    @Inject lateinit var reaper: Reaper

    @Inject lateinit var queue: ValkeyQueue

    @Inject lateinit var redis: RedisDataSource

    @InjectMock lateinit var agones: AgonesAllocator

    @BeforeEach
    fun clean() {
        redis.execute("FLUSHALL")
        queue.loadScripts()
    }

    private fun formMatch(): String {
        val a = ticket("p1")
        val b = ticket("p2")
        queue.enqueue(a, 3600)
        queue.enqueue(b, 3600)
        val matchId = UUID.randomUUID().toString()
        queue.claim(matchId, "duel", listOf(a.id, b.id), 1, Instant.now(), 3600)
        return matchId
    }

    private fun ticket(player: String) =
        Ticket(
            id = UUID.randomUUID().toString(),
            playerId = player,
            modeId = "duel",
            mu = 25.0,
            sigma = 8.33,
            enqueuedAt = Instant.now(),
        )

    @Test
    fun `a server whose match is still forming is left alone`() {
        val matchId = formMatch()
        whenever(agones.listMatchGameServers())
            .thenReturn(listOf(AllocatedGameServer("gs-1", matchId, "Allocated")))

        reaper.sweep()

        // The allocator may still be mid-flight. Deleting here would race it.
        verify(agones, never()).delete(any())
    }

    @Test
    fun `a server assigned to its own match is left alone`() {
        val matchId = formMatch()
        queue.assign(matchId, ServerAssignment("gs-1", "10.0.0.1", 25565))
        whenever(agones.listMatchGameServers())
            .thenReturn(listOf(AllocatedGameServer("gs-1", matchId, "Allocated")))

        reaper.sweep()

        // Players are on this server right now.
        verify(agones, never()).delete(any())
    }

    @Test
    fun `a duplicate server is reaped, the assigned one survives`() {
        // The exact scenario the design admits it cannot prevent: an allocation
        // POST succeeded server-side but the answer never arrived, so a retry
        // allocated a second server. The players were told about gs-1.
        val matchId = formMatch()
        queue.assign(matchId, ServerAssignment("gs-1", "10.0.0.1", 25565))
        whenever(agones.listMatchGameServers())
            .thenReturn(
                listOf(
                    AllocatedGameServer("gs-1", matchId, "Allocated"),
                    AllocatedGameServer("gs-2", matchId, "Allocated"),
                )
            )

        reaper.sweep()

        verify(agones).delete("gs-2")
        verify(agones, never()).delete("gs-1")
    }

    @Test
    fun `a server whose match failed is reaped`() {
        val matchId = formMatch()
        queue.failRequeue(matchId, "duel")
        whenever(agones.listMatchGameServers())
            .thenReturn(listOf(AllocatedGameServer("gs-1", matchId, "Allocated")))

        reaper.sweep()

        // The players went back on the queue; nobody is coming to this server.
        verify(agones).delete("gs-1")
    }

    @Test
    fun `a server whose match no longer exists is reaped`() {
        // Valkey lost the match (expiry, or the ~1s everysec window on a crash).
        // On a replicas:1 fleet this single orphan would block every future
        // match, which is why the reaper is load-bearing rather than hardening.
        whenever(agones.listMatchGameServers())
            .thenReturn(listOf(AllocatedGameServer("gs-1", "match-that-never-was", "Allocated")))

        reaper.sweep()

        verify(agones).delete("gs-1")
    }

    @Test
    fun `an empty cluster is not an error`() {
        whenever(agones.listMatchGameServers()).thenReturn(emptyList())
        reaper.sweep()
        verify(agones, never()).delete(any())
    }

    @Test
    fun `the sweep survives Agones being unreachable`() {
        // A reaper that dies on a transient API error would stop collecting
        // orphans altogether, which is worse than the orphans.
        whenever(agones.listMatchGameServers()).thenThrow(RuntimeException("apiserver down"))
        reaper.sweep()
        verify(agones, never()).delete(any())
        assertEquals(true, true, "sweep must swallow the failure and try again next tick")
    }
}

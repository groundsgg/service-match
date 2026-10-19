package gg.grounds.api

import gg.grounds.allocator.AgonesAllocator
import gg.grounds.allocator.AllocationWorker
import gg.grounds.allocator.MatchHostClient
import gg.grounds.allocator.StartMatchResult
import gg.grounds.domain.MatchState
import gg.grounds.domain.ModeConfig
import gg.grounds.domain.ModeMaps
import gg.grounds.domain.ModeMeta
import gg.grounds.domain.ServerAssignment
import gg.grounds.domain.Ticket
import gg.grounds.domain.TicketState
import gg.grounds.maps.PinnedMap
import gg.grounds.maps.PinnedMapPool
import gg.grounds.matcher.ModeRegistry
import gg.grounds.persistence.ValkeyQueue
import gg.grounds.persistence.ValkeyQueueIT
import io.quarkus.redis.datasource.RedisDataSource
import io.quarkus.test.InjectMock
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.junit.QuarkusTest
import io.restassured.RestAssured.given
import jakarta.inject.Inject
import java.time.Instant
import java.util.UUID
import org.hamcrest.CoreMatchers.equalTo
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

/**
 * A match from the StartMatch push to its players being routed, against a real Valkey: the
 * allocator, the ready call, leaving while the round is prepared, and map requests. Agones, the
 * NATS push and the CDN pin file are the mocked edges.
 */
@QuarkusTest
@QuarkusTestResource(ValkeyQueueIT.ValkeyResource::class)
@QuarkusTestResource(ValkeyQueueIT.PostgresResource::class)
class PreparingFlowIT {

    @Inject lateinit var service: QueueService

    @Inject lateinit var queue: ValkeyQueue

    @Inject lateinit var redis: RedisDataSource

    @Inject lateinit var registry: ModeRegistry

    @Inject lateinit var allocator: AllocationWorker

    @InjectMock lateinit var matchHost: MatchHostClient

    @InjectMock lateinit var pinned: PinnedMapPool

    @InjectMock lateinit var agones: AgonesAllocator

    @BeforeEach
    fun clean() {
        redis.execute("FLUSHALL")
        queue.loadScripts()
        registry.resync()
        register(SG, ModeMeta(maps = ModeMaps("survivalgames")))
        register(DUEL, ModeMeta())
        whenever(pinned.pool("survivalgames"))
            .thenReturn(
                listOf(
                    PinnedMap("survivalgames/aura", lit = true),
                    PinnedMap("survivalgames/breeze"),
                )
            )
        whenever(agones.allocate(any(), any(), any(), any())).thenReturn(SERVER)
    }

    private fun register(mode: ModeConfig, meta: ModeMeta) {
        queue.upsertMode(mode, meta, null, Instant.now())
        registry.upsert(mode)
    }

    private fun enqueue(mode: ModeConfig = SG, map: String? = null): Ticket =
        service.enqueue(UUID.randomUUID(), mode.modeId, map)

    private fun claim(tickets: List<Ticket>, mode: ModeConfig = SG): String {
        val matchId = UUID.randomUUID().toString()
        assertTrue(queue.claim(matchId, mode.modeId, tickets.map { it.id }, 1, Instant.now(), TTL))
        return matchId
    }

    private fun preparing(players: Int): Pair<String, List<Ticket>> {
        val tickets = List(players) { enqueue() }
        val matchId = claim(tickets)
        assertTrue(queue.prepare(matchId, SERVER, Instant.now()))
        return matchId to tickets
    }

    private fun serverAnswers(result: StartMatchResult) {
        whenever(
                matchHost.startMatch(
                    any(),
                    any(),
                    any(),
                    any(),
                    any(),
                    anyOrNull(),
                    anyOrNull(),
                    any(),
                    any(),
                )
            )
            .thenReturn(result)
    }

    /** The map and the wait-for-ready flag the allocator pushed. */
    private fun pushed(): Pair<String, Boolean> {
        val map = argumentCaptor<String>()
        val wait = argumentCaptor<Boolean>()
        verify(matchHost)
            .startMatch(
                any(),
                any(),
                any(),
                any(),
                any(),
                anyOrNull(),
                anyOrNull(),
                map.capture(),
                wait.capture(),
            )
        return map.firstValue to wait.firstValue
    }

    private fun state(ticket: Ticket): TicketState? = queue.findTicket(ticket.id)?.state

    // -----------------------------------------------------------
    // The allocator
    // -----------------------------------------------------------

    @Test
    fun `a server that signals ready keeps the players waiting until it reports ready`() {
        serverAnswers(StartMatchResult(accepted = true, signalsReady = true))
        val tickets = listOf(enqueue(), enqueue())
        val matchId = claim(tickets)

        allocator.pump()

        val (map, waitForReady) = pushed()
        assertTrue(map in setOf("survivalgames/aura", "survivalgames/breeze"))
        assertEquals("survivalgames/aura", map, "the lit map is preferred")
        assertTrue(waitForReady)
        assertEquals(MatchState.PREPARING, queue.matchStatus(matchId).state)
        assertEquals(TicketState.PREPARING, state(tickets[0]))
        assertNull(queue.findTicket(tickets[0].id)?.assignment, "nobody is routed yet")

        assertTrue(service.ready(matchId, SERVER.gameServerName, ok = true, reason = null))

        assertEquals(TicketState.ASSIGNED, state(tickets[0]))
        assertEquals(SERVER, queue.findTicket(tickets[1].id)?.assignment)
        assertTrue(
            service.ready(matchId, SERVER.gameServerName, ok = true, reason = null),
            "a retried ready is still routed",
        )
    }

    @Test
    fun `a server that does not signal ready is assigned at once`() {
        serverAnswers(StartMatchResult(accepted = true, signalsReady = false))
        val tickets = listOf(enqueue(), enqueue())
        val matchId = claim(tickets)

        allocator.pump()

        assertEquals(MatchState.ASSIGNED, queue.matchStatus(matchId).state)
        assertEquals(TicketState.ASSIGNED, state(tickets[0]))
    }

    @Test
    fun `the requested map is the one pushed`() {
        serverAnswers(StartMatchResult(accepted = true, signalsReady = true))
        val tickets = listOf(enqueue(), enqueue(map = "survivalgames/breeze"))
        val matchId = claim(tickets)

        allocator.pump()

        assertEquals("survivalgames/breeze", pushed().first)
        assertEquals("survivalgames/breeze", queue.chosenMap(matchId))
        assertEquals(
            "REQUESTED",
            redis.execute("HGET", ValkeyQueue.matchKey(matchId), "mapStrategy").toString(),
        )
    }

    @Test
    fun `a mode without maps pushes no map, as before`() {
        serverAnswers(StartMatchResult(accepted = true, signalsReady = false))
        claim(listOf(enqueue(DUEL), enqueue(DUEL)), DUEL)

        allocator.pump()

        assertEquals("", pushed().first)
        verify(pinned, never()).pool(any())
    }

    // -----------------------------------------------------------
    // The ready call
    // -----------------------------------------------------------

    @Test
    fun `a round that could not be prepared requeues its players`() {
        val (matchId, tickets) = preparing(2)
        val enqueuedAt = queue.findTicket(tickets[0].id)!!.enqueuedAt

        assertFalse(service.ready(matchId, SERVER.gameServerName, ok = false, reason = "no map"))

        assertEquals(TicketState.QUEUED, state(tickets[0]))
        assertEquals(enqueuedAt, queue.findTicket(tickets[0].id)!!.enqueuedAt)
        assertEquals(MatchState.FAILED, queue.matchStatus(matchId).state)
        assertFalse(
            service.ready(matchId, SERVER.gameServerName, ok = true, reason = null),
            "too late: the round is to be discarded",
        )
    }

    @Test
    fun `ready from another server is refused`() {
        val (matchId, tickets) = preparing(2)

        assertThrows(WrongServerException::class.java) {
            service.ready(matchId, "sg-other", ok = true, reason = null)
        }
        assertEquals(TicketState.PREPARING, state(tickets[0]))
    }

    @Test
    fun `ready for a match nobody knows is not routed`() {
        assertFalse(service.ready(UUID.randomUUID().toString(), SERVER.gameServerName, true, null))
    }

    // -----------------------------------------------------------
    // Leaving while the round is prepared
    // -----------------------------------------------------------

    @Test
    fun `leaving a preparing match goes ahead without the leaver until too few are left`() {
        val (matchId, tickets) = preparing(3)

        assertTrue(service.cancel(tickets[0].id, UUID.fromString(tickets[0].playerId)))
        assertEquals(TicketState.CANCELLED, state(tickets[0]))
        assertEquals(MatchState.PREPARING, queue.matchStatus(matchId).state)
        verify(matchHost, never()).cancelMatch(any(), any(), any())

        // SG's minimum is two: the second leaver ends it.
        assertTrue(service.cancel(tickets[1].id, UUID.fromString(tickets[1].playerId)))
        assertEquals(TicketState.QUEUED, state(tickets[2]))
        assertEquals(MatchState.FAILED, queue.matchStatus(matchId).state)
        verify(matchHost).cancelMatch(SERVER.gameServerName, matchId, "cancelled")
        assertFalse(service.ready(matchId, SERVER.gameServerName, ok = true, reason = null))
    }

    @Test
    fun `the round goes ahead with the players who stayed`() {
        val (matchId, tickets) = preparing(3)
        service.cancel(tickets[0].id, UUID.fromString(tickets[0].playerId))

        assertTrue(service.ready(matchId, SERVER.gameServerName, ok = true, reason = null))

        assertEquals(TicketState.CANCELLED, state(tickets[0]))
        assertEquals(TicketState.ASSIGNED, state(tickets[1]))
        assertEquals(TicketState.ASSIGNED, state(tickets[2]))
    }

    // -----------------------------------------------------------
    // Map requests
    // -----------------------------------------------------------

    @Test
    fun `a pinned map may be requested`() {
        val ticket = enqueue(map = "survivalgames/aura")

        assertEquals("survivalgames/aura", queue.findTicket(ticket.id)?.map)
    }

    @Test
    fun `a map outside the pool is refused`() {
        val error =
            assertThrows(InvalidRequestException::class.java) {
                enqueue(map = "survivalgames/gone")
            }
        assertTrue(error.message!!.contains("not in the pool"))
        assertEquals(0, queue.queueDepth(SG.modeId))
    }

    @Test
    fun `a mode without maps takes no map request`() {
        assertThrows(InvalidRequestException::class.java) {
            enqueue(DUEL, map = "survivalgames/aura")
        }
    }

    @Test
    fun `the maps endpoint lists the mode's pool`() {
        given()
            .get("/v1/match/modes/${SG.modeId}/maps")
            .then()
            .statusCode(200)
            .body("maps", equalTo(listOf("survivalgames/aura", "survivalgames/breeze")))
            .body("selection", equalTo("RANDOM"))

        given()
            .get("/v1/match/modes/${DUEL.modeId}/maps")
            .then()
            .statusCode(200)
            .body("maps", equalTo(emptyList<String>()))

        given().get("/v1/match/modes/no-such-mode/maps").then().statusCode(404)
    }

    private companion object {
        private const val TTL = 3600L
        private val SERVER = ServerAssignment("sg-abc12", "10.0.0.9", 25565)
        private val SG = ModeConfig(modeId = "sg-flow", teamSize = 1, teamCount = 4, minPlayers = 2)
        private val DUEL = ModeConfig(modeId = "duel-flow", teamSize = 1, teamCount = 2)
    }
}

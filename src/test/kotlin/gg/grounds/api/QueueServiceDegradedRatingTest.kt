package gg.grounds.api

import gg.grounds.domain.BandConfig
import gg.grounds.domain.ModeConfig
import gg.grounds.domain.RatingRepository
import gg.grounds.matcher.ModeRegistry
import gg.grounds.persistence.ValkeyQueue
import gg.grounds.persistence.ValkeyQueueIT
import io.quarkus.test.InjectMock
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.junit.QuarkusTest
import jakarta.inject.Inject
import java.util.UUID
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.doThrow
import org.mockito.kotlin.whenever

/**
 * What happens to a player who wants to queue while the rating store is down.
 *
 * The answer has to be "they play", and the whole reason this is a test rather than a comment is
 * that the honest version of failing open has a second half: the match must not be rated. Matching
 * a settled player as mu=25 costs one lopsided game; *rating* from that prior moves their real
 * rating on a fiction, and nothing later would tell you it happened.
 */
@QuarkusTest
@QuarkusTestResource(ValkeyQueueIT.PostgresResource::class, restrictToAnnotatedClass = true)
@QuarkusTestResource(ValkeyQueueIT.ValkeyResource::class, restrictToAnnotatedClass = true)
class QueueServiceDegradedRatingTest {

    @Inject lateinit var queueService: QueueService

    @Inject lateinit var queue: ValkeyQueue

    @Inject lateinit var modes: ModeRegistry

    @InjectMock lateinit var ratings: RatingRepository

    private val mode =
        ModeConfig(
            modeId = "degraded-test",
            teamSize = 1,
            teamCount = 2,
            ranked = true,
            band = BandConfig(),
        )

    @BeforeEach
    fun registerMode() {
        modes.upsert(mode)
    }

    @Test
    fun `an unreachable rating store still lets the player queue, provisionally`() {
        whenever(ratings.find(any(), any())).doThrow(RuntimeException("postgres is gone"))

        val ticket = queueService.enqueue(UUID.randomUUID(), mode.modeId)

        assertNotNull(queue.findTicket(ticket.id), "the ticket must actually be in Valkey")
        assertTrue(ticket.provisional, "a defaulted rating must be marked as such")
        assertEquals(25.0, ticket.mu, 0.0001, "seeded default mu")
    }

    @Test
    fun `the provisional flag survives the round trip through Valkey`() {
        whenever(ratings.find(any(), any())).doThrow(RuntimeException("postgres is gone"))

        val ticket = queueService.enqueue(UUID.randomUUID(), mode.modeId)

        // The matcher never sees the object returned above — it reads tickets back out of Valkey,
        // so a flag that does not survive the Lua write is a flag that does nothing.
        val readBack = queue.findTicket(ticket.id)
        assertNotNull(readBack)
        assertTrue(readBack!!.provisional)
    }

    @Test
    fun `a genuinely unrated player is not provisional`() {
        whenever(ratings.find(any(), any())).doReturn(null)

        val ticket = queueService.enqueue(UUID.randomUUID(), mode.modeId)

        // Defaults are the right answer for a new player, not a fallback. Marking them provisional
        // would make every first match of every new player unranked — which is precisely the
        // population whose rating most needs to move.
        assertFalse(ticket.provisional)
        assertEquals(25.0, ticket.mu, 0.0001)
        assertFalse(queue.findTicket(ticket.id)!!.provisional)
    }
}

package gg.grounds.persistence

import gg.grounds.domain.MatchRecordRepository
import gg.grounds.domain.shortMatchId
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.junit.QuarkusTest
import jakarta.inject.Inject
import java.util.UUID
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

/**
 * The short id names one match: the matcher asks which are taken, and Postgres refuses a second.
 */
@QuarkusTest
@QuarkusTestResource(ValkeyQueueIT.PostgresResource::class)
@QuarkusTestResource(ValkeyQueueIT.ValkeyResource::class)
class MatchShortIdTest {

    @Inject lateinit var matches: MatchRecordRepository

    @Test
    fun `a recorded match's short id is reported as taken`() {
        val matchId = UUID.randomUUID()
        matches.recordMatch(matchId, "duel", false, emptyList())
        val free = shortMatchId(UUID.randomUUID().toString())

        val taken = matches.takenShortIds(listOf(shortMatchId(matchId.toString()), free))

        assertEquals(setOf(shortMatchId(matchId.toString())), taken)
    }

    @Test
    fun `a second match with the same short id is refused`() {
        val first = UUID.randomUUID()
        matches.recordMatch(first, "duel", false, emptyList())
        val sameShortId =
            UUID.fromString(first.toString().take(8) + UUID.randomUUID().toString().drop(8))

        assertThrows(Exception::class.java) {
            matches.recordMatch(sameShortId, "duel", false, emptyList())
        }
    }
}

package gg.grounds.leaderboard

import java.net.ServerSocket
import java.util.UUID
import org.junit.jupiter.api.Assertions.assertDoesNotThrow
import org.junit.jupiter.api.Test

/**
 * `submitScore` backs `ResultService.report`'s promise that a leaderboard outage never fails a
 * match result. The match result is already committed to Postgres by the time it is called; this is
 * the piece that has to swallow whatever service-leaderboard does to it.
 */
class LeaderboardClientTest {

    @Test
    fun `a leaderboard outage is logged and swallowed, never thrown`() {
        val closedPort = closedTcpPort()
        val client = LeaderboardClient("127.0.0.1:$closedPort", 500L)
        try {
            assertDoesNotThrow {
                client.submitScore(
                    boardId = "duel",
                    playerId = UUID.randomUUID().toString(),
                    score = 1000L,
                    idempotencyKey = "match:${UUID.randomUUID()}",
                )
            }
        } finally {
            client.close()
        }
    }

    /** A port nothing is listening on, so the connection is refused immediately. */
    private fun closedTcpPort(): Int = ServerSocket(0).use { it.localPort }
}

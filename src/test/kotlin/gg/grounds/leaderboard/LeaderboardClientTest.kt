package gg.grounds.leaderboard

import com.fasterxml.jackson.databind.ObjectMapper
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.util.UUID
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import org.junit.jupiter.api.Assertions.assertDoesNotThrow
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Test

/**
 * `submitScore` backs `ResultService.report`'s promise that a leaderboard outage never fails a
 * match result. The match result is already committed to Postgres by the time it is called; this is
 * the piece that has to swallow whatever service-leaderboard does to it.
 *
 * The request shape is pinned too. A leaderboard write is fire-and-forget by design, so a wrong
 * path or a dropped idempotency key produces no error anywhere — only a board that is quietly
 * missing scores, or counting them twice.
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

    @Test
    fun `a rejection is swallowed just like an outage`() {
        withServer(status = 500) { _, client ->
            assertDoesNotThrow {
                client.submitScore("duel", UUID.randomUUID().toString(), 10L, "key")
            }
        }
    }

    @Test
    fun `the score is posted to the board's scores collection`() {
        val player = UUID.randomUUID().toString()
        withServer(status = 200) { received, client ->
            client.submitScore("bedwars.solos", player, 1400L, "m1:$player")

            val request = received.poll(5, TimeUnit.SECONDS)
            assertNotNull(request, "no request reached the server")
            assertEquals("POST", request!!.method)
            assertEquals("/v1/leaderboards/bedwars.solos/scores", request.path)

            val body = ObjectMapper().readTree(request.body)
            assertEquals(player, body["playerId"].asText())
            assertEquals(1400L, body["score"].asLong())
            // The rating is the player's standing, not a running total: a resubmitted result
            // must land on the same number rather than adding to it.
            assertEquals("REPLACE", body["mode"].asText())
            assertEquals("m1:$player", body["idempotencyKey"].asText())
        }
    }

    private fun withServer(
        status: Int,
        block: (ArrayBlockingQueue<RecordedRequest>, LeaderboardClient) -> Unit,
    ) {
        val received = ArrayBlockingQueue<RecordedRequest>(4)
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            received.offer(
                RecordedRequest(
                    method = exchange.requestMethod,
                    path = exchange.requestURI.path,
                    body = exchange.requestBody.readBytes().decodeToString(),
                )
            )
            exchange.sendResponseHeaders(status, -1)
            exchange.close()
        }
        server.start()
        val client = LeaderboardClient("127.0.0.1:${server.address.port}", 2000L)
        try {
            block(received, client)
        } finally {
            client.close()
            server.stop(0)
        }
    }

    /** A port nothing is listening on, so the connection is refused immediately. */
    private fun closedTcpPort(): Int = ServerSocket(0).use { it.localPort }
}

private data class RecordedRequest(val method: String, val path: String, val body: String)

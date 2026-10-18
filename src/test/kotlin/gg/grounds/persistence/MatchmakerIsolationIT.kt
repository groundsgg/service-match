package gg.grounds.persistence

import io.quarkus.redis.client.RedisClientName
import io.quarkus.redis.datasource.RedisDataSource
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.junit.QuarkusTestProfile
import io.quarkus.test.junit.TestProfile
import io.restassured.RestAssured.given
import io.vertx.core.http.ConnectionPoolTooBusyException
import jakarta.inject.Inject
import java.time.Duration
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTimeoutPreemptively
import org.junit.jupiter.api.Test

/**
 * The API running out of Valkey connections must not stop matchmaking.
 *
 * The API's pool is shrunk to one connection and one waiting slot, then filled with blocking
 * commands — what a storm of leaves does to the real pool. The API then answers 503, and the
 * matchmaker's own client still answers at once.
 */
@QuarkusTest
@TestProfile(MatchmakerIsolationIT.TinyApiPool::class)
@QuarkusTestResource(ValkeyQueueIT.PostgresResource::class)
@QuarkusTestResource(ValkeyQueueIT.ValkeyResource::class)
class MatchmakerIsolationIT {

    class TinyApiPool : QuarkusTestProfile {
        override fun getConfigOverrides() =
            mapOf("quarkus.redis.max-pool-size" to "1", "quarkus.redis.max-pool-waiting" to "1")
    }

    @Inject lateinit var apiRedis: RedisDataSource

    @Inject lateinit var apiQueue: ValkeyQueue

    @Inject @field:Matchmaker lateinit var matchmakerQueue: ValkeyQueue

    @Inject @field:RedisClientName(Matchmaker.CLIENT) lateinit var matchmakerRedis: RedisDataSource

    @Test
    fun `an API storm that takes every API connection leaves the matchmaker's client free`() {
        // Create both queues (and load their scripts) while connections are still to be had. This
        // also leaves the API's one connection open and idle.
        apiQueue.queueDepth("duel")
        matchmakerQueue.queueDepth("duel")

        val storm = Executors.newFixedThreadPool(2)
        try {
            // One BLPOP takes the API's only connection, the next waits in its one-slot line. In
            // that order: a request that arrives while a connection is still being opened takes
            // the waiting slot itself, and one that finds a free slot waits instead of failing.
            val held = mutableListOf<Future<*>>()
            repeat(2) {
                held += storm.submit { apiRedis.execute("BLPOP", "pressure:none", "3") }
                Thread.sleep(300)
            }

            assertThrows(ConnectionPoolTooBusyException::class.java) { apiRedis.execute("PING") }
            given().delete("/v1/match/players/${UUID.randomUUID()}/match").then().statusCode(503)

            assertTimeoutPreemptively(Duration.ofMillis(500)) {
                assertEquals("PONG", matchmakerRedis.execute("PING").toString())
                assertEquals(0, matchmakerQueue.queueDepth("duel"))
            }

            held.forEach { it.get(10, TimeUnit.SECONDS) }
        } finally {
            storm.shutdownNow()
        }
    }
}

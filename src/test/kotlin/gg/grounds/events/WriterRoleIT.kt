package gg.grounds.events

import com.fasterxml.jackson.databind.ObjectMapper
import gg.grounds.domain.MatchRecordRepository
import gg.grounds.domain.RatingRepository
import gg.grounds.support.StreamInfra
import io.nats.client.Nats
import io.nats.client.api.StorageType
import io.nats.client.api.StreamConfiguration
import io.quarkus.redis.datasource.RedisDataSource
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.junit.QuarkusTestProfile
import io.quarkus.test.junit.TestProfile
import jakarta.inject.Inject
import java.util.UUID
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/**
 * The core writer as deployed: `MATCH_ROLE=writer`, no Valkey reachable, leaderboard over
 * JetStream. Booting at all is the first assertion (the regional runtime refuses to start without
 * Valkey); applying a result from the stream is the second.
 */
@QuarkusTest
@TestProfile(WriterRoleIT.Writer::class)
@QuarkusTestResource(StreamInfra::class, restrictToAnnotatedClass = true)
class WriterRoleIT {

    class Writer : QuarkusTestProfile {
        override fun getConfigOverrides() =
            mapOf(
                "grounds.match.role" to "writer",
                "grounds.match.result.consumer.enabled" to "true",
                "grounds.match.result.consumer.stream" to STREAM,
                "grounds.match.leaderboard.transport" to "jetstream",
                // Nothing listens here: the writer runs in core, where there is no Valkey.
                "quarkus.redis.hosts" to "redis://127.0.0.1:1",
            )
    }

    @Inject lateinit var consumer: MatchResultConsumer

    @Inject lateinit var matches: MatchRecordRepository

    @Inject lateinit var ratings: RatingRepository

    @Inject lateinit var json: ObjectMapper

    @Inject lateinit var redis: RedisDataSource

    @Test
    fun `the writer boots without Valkey and applies a result from the stream`() {
        Nats.connect(StreamInfra.natsUrl()).use { nats ->
            val jsm = nats.jetStreamManagement()
            runCatching { jsm.deleteStream(STREAM) }
            jsm.addStream(
                StreamConfiguration.builder()
                    .name(STREAM)
                    .subjects(MatchResultSubmitted.SUBJECT)
                    .storageType(StorageType.Memory)
                    .build()
            )
            val players = listOf(UUID.randomUUID(), UUID.randomUUID())
            val matchId = UUID.randomUUID()
            matches.recordMatch(matchId, "duel", ranked = true, players)
            val event =
                MatchResultSubmitted(
                    matchId.toString(),
                    "duel",
                    players.mapIndexed { i, p ->
                        MatchResultSubmitted.Placement(p.toString(), i + 1)
                    },
                    "normal_finish",
                    "2026-10-17T00:00:00Z",
                )
            nats
                .jetStream()
                .publish(
                    MatchResultSubmitted.SUBJECT_PREFIX + "duel",
                    json.writeValueAsBytes(event),
                )

            var handled = 0
            repeat(5) { handled += consumer.drain() }

            assertEquals(1, handled)
            // Proof the profile really took Valkey away, rather than a suite-wide one answering.
            assertThrows<Exception> { redis.execute("PING") }
            assertTrue(matches.findMatch(matchId)!!.ended)
            players.forEach { assertEquals(1, ratings.find(it, "duel")!!.gamesPlayed) }
            jsm.deleteStream(STREAM)
        }
    }

    private companion object {
        const val STREAM = "MATCH_RESULTS_WRITER_TEST"
    }
}

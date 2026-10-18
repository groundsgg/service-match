package gg.grounds.events

import com.fasterxml.jackson.databind.ObjectMapper
import gg.grounds.api.ResultService
import gg.grounds.api.ResultSubmitter
import gg.grounds.api.UnknownMatchException
import gg.grounds.domain.MatchRecordRepository
import gg.grounds.domain.PlayerPlacement
import gg.grounds.domain.RatingRepository
import gg.grounds.support.StreamInfra
import io.micrometer.core.instrument.MeterRegistry
import io.nats.client.Connection
import io.nats.client.Nats
import io.nats.client.api.DiscardPolicy
import io.nats.client.api.StorageType
import io.nats.client.api.StreamConfiguration
import io.nats.client.impl.Headers
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.junit.QuarkusTestProfile
import io.quarkus.test.junit.TestProfile
import jakarta.inject.Inject
import java.time.Duration
import java.util.UUID
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/**
 * The asynchronous result path end to end, against a real JetStream, Postgres and Valkey: the
 * regional runtime accepts and publishes, the writer applies, and every failure mode the plan names
 * either delays a result or applies it exactly once — never loses or doubles it.
 *
 * One broker stands in for both ends: in production the regional buffer (REGION_OUTBOX) and the hub
 * stream (MATCH_RESULTS) are two streams joined by sourcing, which grounds-pulumi's topology test
 * covers. Here one stream captures the subject and the writer consumes it directly.
 */
@QuarkusTest
@TestProfile(AsyncResultFlowIT.Async::class)
@QuarkusTestResource(StreamInfra::class, restrictToAnnotatedClass = true)
class AsyncResultFlowIT {

    class Async : QuarkusTestProfile {
        override fun getConfigOverrides() =
            mapOf(
                "grounds.match.result.async" to "true",
                "grounds.match.result.publish-timeout" to "1s",
                "grounds.match.result.consumer.enabled" to "true",
                "grounds.match.result.consumer.stream" to STREAM,
                "grounds.match.result.consumer.retry-delay" to "200ms",
                "grounds.match.leaderboard.transport" to "jetstream",
            )
    }

    @Inject lateinit var submitter: ResultSubmitter

    @Inject lateinit var results: ResultService

    @Inject lateinit var consumer: MatchResultConsumer

    @Inject lateinit var matches: MatchRecordRepository

    @Inject lateinit var ratings: RatingRepository

    @Inject lateinit var json: ObjectMapper

    @Inject lateinit var registry: MeterRegistry

    private lateinit var nats: Connection

    @BeforeEach
    fun setUp() {
        nats = Nats.connect(StreamInfra.natsUrl())
        val jsm = nats.jetStreamManagement()
        listOf(STREAM, SCORES).forEach { runCatching { jsm.deleteStream(it) } }
        jsm.addStream(stream(STREAM, MatchResultSubmitted.SUBJECT))
        jsm.addStream(stream(SCORES, "leaderboard.score.submitted.>"))
    }

    @AfterEach
    fun tearDown() {
        val jsm = nats.jetStreamManagement()
        listOf(STREAM, SCORES).forEach { runCatching { jsm.deleteStream(it) } }
        nats.close()
    }

    private fun stream(name: String, subject: String, maxBytes: Long = -1) =
        StreamConfiguration.builder()
            .name(name)
            .subjects(subject)
            .storageType(StorageType.Memory)
            .maxBytes(maxBytes)
            .discardPolicy(DiscardPolicy.New)
            .duplicateWindow(Duration.ofMinutes(10))
            .build()

    private fun formRankedMatch(): Pair<UUID, List<UUID>> {
        val players = listOf(UUID.randomUUID(), UUID.randomUUID())
        val matchId = UUID.randomUUID()
        matches.recordMatch(matchId, "duel", ranked = true, players)
        return matchId to players
    }

    private fun placements(players: List<UUID>) =
        players.mapIndexed { i, p -> PlayerPlacement(p, i + 1) }

    private fun messages(stream: String) =
        nats.jetStreamManagement().getStreamInfo(stream).streamState.msgCount

    private fun drainUntilIdle() {
        val deadline = System.nanoTime() + Duration.ofSeconds(15).toNanos()
        var idle = 0
        while (System.nanoTime() < deadline && idle < 3) {
            if (consumer.drain() == 0) idle++ else idle = 0
        }
    }

    private fun consumed(outcome: String) =
        registry.find("match.result.consumer.messages").tag("outcome", outcome).counter()?.count()
            ?: 0.0

    @Test
    fun `a result is queued at the edge and applied once by the writer`() {
        val (matchId, players) = formRankedMatch()

        val submitted = submitter.submit(matchId, placements(players), "normal_finish")

        assertTrue(submitted.queued)
        assertTrue(submitted.outcome.applied)
        assertTrue(submitted.outcome.rated)
        assertFalse(matches.findMatch(matchId)!!.ended, "nothing is applied before the writer runs")
        assertEquals(1, messages(STREAM))

        drainUntilIdle()

        assertTrue(matches.findMatch(matchId)!!.ended)
        players.forEach { assertEquals(1, ratings.find(it, "duel")!!.gamesPlayed) }
        assertEquals(2, messages(SCORES), "one leaderboard score per player")
    }

    @Test
    fun `results buffered while the writer is down are applied when it comes back`() {
        val formed = List(3) { formRankedMatch() }
        formed.forEach { (id, players) ->
            submitter.submit(id, placements(players), "normal_finish")
        }

        // The writer has not polled: the stream holds them, the ladder has not moved.
        assertEquals(3, messages(STREAM))
        formed.forEach { (id, _) -> assertFalse(matches.findMatch(id)!!.ended) }

        drainUntilIdle()

        formed.forEach { (id, players) ->
            assertTrue(matches.findMatch(id)!!.ended)
            players.forEach { assertEquals(1, ratings.find(it, "duel")!!.gamesPlayed) }
        }
    }

    @Test
    fun `a duplicate delivery moves the ladder once`() {
        val (matchId, players) = formRankedMatch()
        val event =
            MatchResultSubmitted(
                matchId.toString(),
                "duel",
                placements(players).map {
                    MatchResultSubmitted.Placement(it.playerId.toString(), it.placement)
                },
                "normal_finish",
                "2026-10-17T00:00:00Z",
            )
        // Two message ids, so the stream keeps both: a redelivery its own dedupe cannot catch.
        listOf("a", "b").forEach { id ->
            nats
                .jetStream()
                .publish(
                    MatchResultSubmitted.SUBJECT_PREFIX + "duel",
                    Headers().add("Nats-Msg-Id", id),
                    json.writeValueAsBytes(event),
                )
        }
        val alreadyBefore = consumed("already_applied")

        drainUntilIdle()

        players.forEach { assertEquals(1, ratings.find(it, "duel")!!.gamesPlayed) }
        assertEquals(alreadyBefore + 1, consumed("already_applied"))
        assertEquals(2, messages(SCORES), "the duplicate submits no second set of scores")
    }

    @Test
    fun `a reporter retry while the result is queued is not queued twice`() {
        val (matchId, players) = formRankedMatch()

        submitter.submit(matchId, placements(players), "normal_finish")
        val retry = submitter.submit(matchId, placements(players), "normal_finish")

        assertTrue(retry.queued)
        assertFalse(retry.outcome.applied)
        assertEquals(1, messages(STREAM))
    }

    @Test
    fun `an unacknowledged publish that landed anyway is a no-op after the synchronous fallback`() {
        val (matchId, players) = formRankedMatch()
        // The publish reached the stream but its ack was lost, so the edge applied synchronously.
        val event =
            MatchResultSubmitted(
                matchId.toString(),
                "duel",
                placements(players).map {
                    MatchResultSubmitted.Placement(it.playerId.toString(), it.placement)
                },
                "normal_finish",
                "2026-10-17T00:00:00Z",
            )
        nats
            .jetStream()
            .publish(MatchResultSubmitted.SUBJECT_PREFIX + "duel", json.writeValueAsBytes(event))
        assertTrue(results.report(matchId, placements(players), "normal_finish").applied)
        val muAfterSync = ratings.find(players[0], "duel")!!.rating

        drainUntilIdle()

        assertEquals(muAfterSync, ratings.find(players[0], "duel")!!.rating)
        players.forEach { assertEquals(1, ratings.find(it, "duel")!!.gamesPlayed) }
        assertEquals(2, messages(SCORES), "scores come from the one application only")
    }

    @Test
    fun `a full buffer refuses the publish and the result is applied synchronously`() {
        val jsm = nats.jetStreamManagement()
        jsm.deleteStream(STREAM)
        jsm.addStream(stream(STREAM, MatchResultSubmitted.SUBJECT, maxBytes = 1))
        val (matchId, players) = formRankedMatch()

        val submitted = submitter.submit(matchId, placements(players), "normal_finish")

        assertFalse(submitted.queued)
        assertTrue(submitted.outcome.applied)
        assertTrue(matches.findMatch(matchId)!!.ended)
        assertEquals(0, messages(STREAM))
    }

    @Test
    fun `no buffer at all (no responders) falls back to the synchronous path`() {
        nats.jetStreamManagement().deleteStream(STREAM)
        val (matchId, players) = formRankedMatch()

        val submitted = submitter.submit(matchId, placements(players), "normal_finish")

        assertFalse(submitted.queued)
        assertTrue(submitted.outcome.applied)
        assertTrue(matches.findMatch(matchId)!!.ended)
        // The writer keeps polling a stream that is gone; it must not fail the service.
        assertEquals(0, consumer.drain())
        nats.jetStreamManagement().addStream(stream(STREAM, MatchResultSubmitted.SUBJECT))
    }

    @Test
    fun `a fabricated match is still refused at the edge and never published`() {
        assertThrows<UnknownMatchException> {
            submitter.submit(
                UUID.randomUUID(),
                listOf(PlayerPlacement(UUID.randomUUID(), 1)),
                "normal_finish",
            )
        }
        assertEquals(0, messages(STREAM))
    }

    @Test
    fun `a result the writer cannot apply is terminated and does not block the next`() {
        val bogus =
            MatchResultSubmitted(
                UUID.randomUUID().toString(),
                "duel",
                listOf(MatchResultSubmitted.Placement(UUID.randomUUID().toString(), 1)),
                "normal_finish",
                "2026-10-17T00:00:00Z",
            )
        nats
            .jetStream()
            .publish(MatchResultSubmitted.SUBJECT_PREFIX + "duel", json.writeValueAsBytes(bogus))
        val (matchId, players) = formRankedMatch()
        submitter.submit(matchId, placements(players), "normal_finish")
        val invalidBefore = consumed("invalid")

        drainUntilIdle()

        assertEquals(invalidBefore + 1, consumed("invalid"))
        assertTrue(matches.findMatch(matchId)!!.ended)
        assertNull(ratings.find(UUID.fromString(bogus.results[0].playerId), "duel"))
        assertNotNull(ratings.find(players[0], "duel"))
    }

    private companion object {
        const val STREAM = "MATCH_RESULTS_TEST"
        const val SCORES = "LEADERBOARD_SCORES_TEST"
    }
}

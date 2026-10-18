package gg.grounds.api

import com.fasterxml.jackson.databind.ObjectMapper
import gg.grounds.domain.PlayerPlacement
import gg.grounds.domain.ResultOutcome
import gg.grounds.events.JetStreamPublisher
import gg.grounds.events.MatchResultSubmitted
import io.micrometer.core.instrument.MeterRegistry
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import java.time.Instant
import java.util.UUID
import org.eclipse.microprofile.config.inject.ConfigProperty
import org.jboss.logging.Logger

/** What `POST …/result` answers: the outcome, and whether it is still on its way to the ladder. */
data class SubmittedResult(val outcome: ResultOutcome, val queued: Boolean)

/**
 * The regional side of a match result.
 *
 * Synchronous (the default), this is [ResultService.report]: ~3N+3 database round trips, plus the
 * leaderboard, while the game server waits. With `grounds.match.result.async` on, the result is
 * checked against the match record (one round trip, so a fabricated match is still a 404 and a
 * stranger in the placements still a 403), the players' active match is cleared, and the result is
 * published to the region's JetStream buffer; the writer in core applies it within seconds.
 *
 * Any publish that is not acknowledged falls back to the synchronous path, so a missing stream, a
 * broker outage or a full buffer costs latency, never the result. If the unacknowledged publish did
 * land after all, the writer finds the match already ended and changes nothing.
 */
@ApplicationScoped
class ResultSubmitter
@Inject
constructor(
    private val results: ResultService,
    private val publisher: JetStreamPublisher,
    private val json: ObjectMapper,
    private val registry: MeterRegistry,
    @param:ConfigProperty(name = "grounds.match.result.async") private val async: Boolean,
) {

    fun submit(
        matchId: UUID,
        placements: List<PlayerPlacement>,
        terminationReason: String,
    ): SubmittedResult {
        if (!async) {
            return synchronous(matchId, placements, terminationReason, path = "sync")
        }

        val match = results.validate(matchId, placements)
        if (match.ended) {
            count("replay")
            return SubmittedResult(ResultOutcome(applied = false, rated = false), queued = false)
        }
        if (!MatchResultSubmitted.isSubjectTail(match.modeId)) {
            log.warn(
                "Mode id is not a subject token, applying synchronously (mode=${match.modeId})"
            )
            return synchronous(matchId, placements, terminationReason, path = "sync_fallback")
        }

        val event =
            MatchResultSubmitted(
                matchId = matchId.toString(),
                modeId = match.modeId,
                results =
                    placements.map {
                        MatchResultSubmitted.Placement(it.playerId.toString(), it.placement)
                    },
                terminationReason = terminationReason,
                submittedAt = Instant.now().toString(),
            )
        val published =
            publisher.publish(
                subject = MatchResultSubmitted.SUBJECT_PREFIX + match.modeId,
                messageId = MatchResultSubmitted.messageId(event.matchId),
                body = json.writeValueAsBytes(event),
            )

        return when (published) {
            JetStreamPublisher.Outcome.STORED -> {
                results.clearActiveMatch(match)
                count("queued")
                // What the writer will do with it: the record is not ended yet, and ranked is
                // fixed at formation time. This is what the reporters log.
                SubmittedResult(
                    ResultOutcome(applied = true, rated = match.ranked && placements.size >= 2),
                    queued = true,
                )
            }
            // A retry of a result that is already on its way: not applied a second time.
            JetStreamPublisher.Outcome.DUPLICATE -> {
                count("duplicate")
                SubmittedResult(ResultOutcome(applied = false, rated = false), queued = true)
            }
            JetStreamPublisher.Outcome.FAILED -> {
                log.warn(
                    "Result not acknowledged by the stream, applying synchronously (match=$matchId)"
                )
                synchronous(matchId, placements, terminationReason, path = "sync_fallback")
            }
        }
    }

    private fun synchronous(
        matchId: UUID,
        placements: List<PlayerPlacement>,
        terminationReason: String,
        path: String,
    ): SubmittedResult {
        val outcome = results.report(matchId, placements, terminationReason)
        count(path)
        return SubmittedResult(outcome, queued = false)
    }

    private fun count(path: String) {
        registry.counter("match.result.submissions", "path", path).increment()
    }

    private companion object {
        val log: Logger = Logger.getLogger(ResultSubmitter::class.java)
    }
}

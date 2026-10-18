package gg.grounds.events

import com.fasterxml.jackson.databind.ObjectMapper
import gg.grounds.api.NotInMatchException
import gg.grounds.api.ResultService
import gg.grounds.api.UnknownMatchException
import gg.grounds.domain.PlayerPlacement
import io.micrometer.core.instrument.MeterRegistry
import io.nats.client.JetStreamSubscription
import io.nats.client.Message
import io.nats.client.PullSubscribeOptions
import io.nats.client.api.AckPolicy
import io.nats.client.api.ConsumerConfiguration
import io.quarkus.scheduler.Scheduled
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import java.time.Duration
import java.util.UUID
import org.eclipse.microprofile.config.inject.ConfigProperty
import org.jboss.logging.Logger

/**
 * The core writer: applies results from the MATCH_RESULTS stream.
 *
 * Regional runtimes publish into their region's in-memory buffer; the hub stream sources every
 * region's buffer, so this one durable sees every region's results. Each goes through
 * [ResultService.applySubmitted], the same idempotent transaction the synchronous path uses: a
 * redelivery, a duplicate publish or a result the regional runtime already applied synchronously
 * finds the match ended and changes nothing.
 *
 * One message in flight (`max_ack_pending = 1`): a player's rating update reads the rating the
 * previous match left, so results are applied in the order the stream holds them. A result that can
 * never apply (unreadable, unknown match, a stranger in the placements) is terminated; anything
 * else that fails (the database) is redelivered after a delay and holds the line until it applies.
 */
@ApplicationScoped
class MatchResultConsumer
@Inject
constructor(
    private val nats: NatsConnector,
    private val results: ResultService,
    private val json: ObjectMapper,
    private val registry: MeterRegistry,
    @param:ConfigProperty(name = "grounds.match.result.consumer.enabled")
    private val enabled: Boolean,
    @param:ConfigProperty(name = "grounds.match.result.consumer.stream") private val stream: String,
    @param:ConfigProperty(name = "grounds.match.result.consumer.durable")
    private val durable: String,
    @param:ConfigProperty(name = "grounds.match.result.consumer.batch-size")
    private val batchSize: Int,
    @param:ConfigProperty(name = "grounds.match.result.consumer.retry-delay")
    private val retryDelay: Duration,
) {

    enum class Outcome {
        APPLIED,
        ALREADY_APPLIED,
        INVALID,
        RETRY,
    }

    @Volatile private var subscription: JetStreamSubscription? = null

    @Scheduled(
        every = "{grounds.match.result.consumer.poll-interval}",
        concurrentExecution = Scheduled.ConcurrentExecution.SKIP,
    )
    fun poll() {
        if (!enabled) return
        drain()
    }

    /** Processes up to one batch; returns how many messages were handled. */
    fun drain(): Int {
        val sub = subscription() ?: return 0
        var handled = 0
        while (handled < batchSize) {
            val message =
                try {
                    sub.fetch(1, FETCH_TIMEOUT).firstOrNull()
                } catch (error: Exception) {
                    LOG.warnf(error, "Fetch failed on %s, resubscribing", stream)
                    subscription = null
                    return handled
                } ?: break
            process(message)
            handled++
        }
        if (handled == 0) verifyConsumer()
        return handled
    }

    /**
     * An idle poll checks the durable still exists. A pull from a consumer that was deleted (the
     * stream recreated, an operator's `nats consumer rm`) just returns nothing, so without this the
     * writer would sit idle forever while results pile up.
     */
    private fun verifyConsumer() {
        try {
            // Asked of the server, not of the subscription, which may answer from its cache.
            checkNotNull(nats.connection()).jetStreamManagement().getConsumerInfo(stream, durable)
        } catch (error: Exception) {
            LOG.warnf(
                "Consumer %s on %s is gone, resubscribing: %s",
                durable,
                stream,
                error.message,
            )
            subscription = null
        }
    }

    private fun process(message: Message) {
        val outcome =
            try {
                handle(message.subject, message.data)
            } catch (error: Exception) {
                LOG.warnf(
                    error,
                    "Applying a match result failed, will retry (subject=%s)",
                    message.subject,
                )
                Outcome.RETRY
            }
        when (outcome) {
            Outcome.APPLIED,
            Outcome.ALREADY_APPLIED -> message.ack()
            Outcome.INVALID -> message.term()
            Outcome.RETRY -> message.nakWithDelay(retryDelay)
        }
        registry
            .counter("match.result.consumer.messages", "outcome", outcome.name.lowercase())
            .increment()
    }

    /**
     * Applies one message. Throws on a transient failure (the caller redelivers); returns
     * [Outcome.INVALID] for anything a redelivery could not fix.
     */
    fun handle(subject: String, data: ByteArray): Outcome {
        val event =
            try {
                json.readValue(data, MatchResultSubmitted::class.java)
            } catch (error: Exception) {
                LOG.errorf(
                    "Discarding an unreadable match result (subject=%s): %s",
                    subject,
                    error.message,
                )
                return Outcome.INVALID
            }
        val parsed =
            try {
                UUID.fromString(event.matchId) to
                    event.results.map {
                        PlayerPlacement(UUID.fromString(it.playerId), it.placement)
                    }
            } catch (error: IllegalArgumentException) {
                LOG.errorf("Discarding a match result with a malformed id (subject=%s)", subject)
                return Outcome.INVALID
            }
        val (matchId, placements) = parsed
        if (placements.isEmpty() || placements.any { it.placement < 1 }) {
            LOG.errorf("Discarding a match result without valid placements (match=%s)", matchId)
            return Outcome.INVALID
        }
        return try {
            val outcome = results.applySubmitted(matchId, placements, event.terminationReason)
            if (outcome.applied) Outcome.APPLIED else Outcome.ALREADY_APPLIED
        } catch (error: UnknownMatchException) {
            LOG.errorf("Discarding a result for a match that was never formed (match=%s)", matchId)
            Outcome.INVALID
        } catch (error: NotInMatchException) {
            LOG.errorf(
                "Discarding a result that places a stranger (match=%s): %s",
                matchId,
                error.message,
            )
            Outcome.INVALID
        }
    }

    private fun subscription(): JetStreamSubscription? {
        subscription?.let {
            return it
        }
        val connection = nats.connection() ?: return null
        return try {
            val options =
                PullSubscribeOptions.builder()
                    .stream(stream)
                    .durable(durable)
                    .configuration(
                        ConsumerConfiguration.builder()
                            .ackPolicy(AckPolicy.Explicit)
                            .ackWait(ACK_WAIT)
                            .maxAckPending(1)
                            .build()
                    )
                    .build()
            connection.jetStream().subscribe(MatchResultSubmitted.SUBJECT, options).also {
                subscription = it
                LOG.infof(
                    "Consuming %s on %s (durable=%s)",
                    MatchResultSubmitted.SUBJECT,
                    stream,
                    durable,
                )
            }
        } catch (error: Exception) {
            // Most likely the stream or this service's NATS user is not provisioned yet. The next
            // poll tries again.
            LOG.warnf(error, "Cannot consume %s on %s", MatchResultSubmitted.SUBJECT, stream)
            null
        }
    }

    private companion object {
        val FETCH_TIMEOUT: Duration = Duration.ofMillis(500)
        val ACK_WAIT: Duration = Duration.ofSeconds(30)
        val LOG: Logger = Logger.getLogger(MatchResultConsumer::class.java)
    }
}

package gg.grounds.events

import io.nats.client.JetStreamApiException
import io.nats.client.JetStreamOptions
import io.nats.client.PublishOptions
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import java.time.Duration
import org.eclipse.microprofile.config.inject.ConfigProperty
import org.jboss.logging.Logger

/**
 * A JetStream publish that reports what happened instead of throwing.
 *
 * Every caller has something better to do on failure than to fail: the result path falls back to
 * applying synchronously, the leaderboard path logs. So the three outcomes are a value:
 * - [Outcome.STORED]: a stream acknowledged it.
 * - [Outcome.DUPLICATE]: a stream already had this message id, from an earlier attempt.
 * - [Outcome.FAILED]: nothing acknowledged it within the deadline: no stream for the subject (no
 *   responders), the broker unreachable, or the stream refused it (a full regional buffer answers
 *   "maximum bytes exceeded"). Whether it was stored is unknown, which is why every publish carries
 *   a message id and every consumer is idempotent.
 */
@ApplicationScoped
class JetStreamPublisher
@Inject
constructor(
    private val nats: NatsConnector,
    @param:ConfigProperty(name = "grounds.match.result.publish-timeout")
    private val timeout: Duration,
) {
    enum class Outcome {
        STORED,
        DUPLICATE,
        FAILED,
    }

    fun publish(subject: String, messageId: String, body: ByteArray): Outcome {
        val connection = nats.connection() ?: return Outcome.FAILED
        return try {
            val ack =
                connection
                    .jetStream(JetStreamOptions.builder().requestTimeout(timeout).build())
                    .publish(
                        subject,
                        null,
                        body,
                        PublishOptions.builder().messageId(messageId).build(),
                    )
            if (ack.isDuplicate) Outcome.DUPLICATE else Outcome.STORED
        } catch (e: JetStreamApiException) {
            LOG.warnf(
                "Stream refused the publish (subject=%s, msgId=%s, code=%d): %s",
                subject,
                messageId,
                e.apiErrorCode,
                e.message,
            )
            Outcome.FAILED
        } catch (e: Exception) {
            LOG.warnf(
                "Publish not acknowledged (subject=%s, msgId=%s): %s",
                subject,
                messageId,
                e.message,
            )
            Outcome.FAILED
        }
    }

    private companion object {
        val LOG: Logger = Logger.getLogger(JetStreamPublisher::class.java)
    }
}

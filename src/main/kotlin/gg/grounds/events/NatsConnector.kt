package gg.grounds.events

import io.nats.client.Connection
import io.nats.client.ConnectionListener
import io.nats.client.Nats
import io.nats.client.Options
import io.quarkus.runtime.ShutdownEvent
import jakarta.enterprise.context.ApplicationScoped
import jakarta.enterprise.event.Observes
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import org.eclipse.microprofile.config.inject.ConfigProperty
import org.jboss.logging.Logger

/**
 * One NATS connection for this service.
 *
 * Connecting lazily and tolerating failure is deliberate, and matches service-coins: the queue, the
 * ladder and the whole HTTP surface work without a broker. What NATS carries here is the match push
 * to a game server, and that path still has its gRPC route while the runtimes migrate — so a broker
 * that is down costs a fallback, not a match, and must never stop the service from starting.
 */
@ApplicationScoped
class NatsConnector(
    @param:ConfigProperty(name = "nats.url") private val natsUrl: String,
    @param:ConfigProperty(name = "nats.max-reconnects") private val maxReconnects: Int,
    @param:ConfigProperty(name = "nats.reconnect-wait-seconds")
    private val reconnectWaitSeconds: Long,
    @param:ConfigProperty(name = "grounds.token-file") private val groundsTokenFile: String,
) {

    @Volatile private var connection: Connection? = null

    @Synchronized
    fun connection(): Connection? {
        connection?.let { existing ->
            if (existing.status != Connection.Status.CLOSED) return existing
        }
        return try {
            val builder =
                Options.Builder()
                    .server(natsUrl)
                    .connectionName("service-match")
                    .maxReconnects(maxReconnects)
                    .reconnectWait(Duration.ofSeconds(reconnectWaitSeconds))
                    .connectionListener(
                        ConnectionListener { _, type ->
                            LOG.infof("NATS connection event (event=%s)", type)
                        }
                    )
            // The projected SA token as the NATS bearer, re-read per (re)connect so kubelet
            // rotation is picked up. An absent file means no token, which is right both for a
            // local run and for the satellite leaf, which asks for no credentials at all.
            val tokenPath = Path.of(groundsTokenFile)
            if (Files.exists(tokenPath)) {
                builder.tokenSupplier { Files.readString(tokenPath).trim().toCharArray() }
            }
            Nats.connect(builder.build()).also {
                connection = it
                LOG.infof("Connected to NATS (url=%s)", natsUrl)
            }
        } catch (error: Exception) {
            LOG.errorf(error, "Failed to connect to NATS (url=%s)", natsUrl)
            connection = null
            null
        }
    }

    fun onStop(@Observes event: ShutdownEvent) {
        try {
            connection?.close()
        } catch (error: Exception) {
            LOG.warnf(error, "Failed to close the NATS connection")
        } finally {
            connection = null
        }
    }

    companion object {
        private val LOG = Logger.getLogger(NatsConnector::class.java)
    }
}

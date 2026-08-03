package gg.grounds.bench

import gg.grounds.persistence.ValkeyQueueIT
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.junit.QuarkusTest
import jakarta.inject.Inject
import java.sql.DriverManager
import javax.sql.DataSource
import org.eclipse.microprofile.config.inject.ConfigProperty
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test

/**
 * What one new database connection costs when the database is not local.
 *
 * Every service in a satellite reaches `app-db` on core through a tunnel, and none of them
 * configure the pool — so Agroal's defaults apply, and two of those defaults are about *when*
 * connections are made rather than how many: `min-size=0` and `initial-size=0` mean the pool starts
 * empty and fills on demand, and idle connections are reaped after a few minutes.
 *
 * Locally that is invisible: opening a connection to a Service in the same namespace is
 * sub-millisecond. Through a tunnel it is a TCP handshake, a TLS handshake and the Postgres startup
 * exchange, each of them a round trip. This measures how many.
 *
 * The number decides whether "tune every service" is warranted or whether one setting is. It is a
 * latency spike after idle, not a capacity limit — a different failure from the connection pool
 * this PR resized, and worth not conflating with it.
 */
@QuarkusTest
@Tag("benchmark")
@QuarkusTestResource(
    QueueLoadBenchmark.ProxiedPostgresResource::class,
    restrictToAnnotatedClass = true,
)
// Not measured here, but the app boots as a whole and the redis client has to
// find something to connect to.
@QuarkusTestResource(ValkeyQueueIT.ValkeyResource::class, restrictToAnnotatedClass = true)
class ConnectionSetupBenchmark {

    @Inject lateinit var dataSource: DataSource

    @ConfigProperty(name = "quarkus.datasource.jdbc.url") lateinit var jdbcUrl: String

    @ConfigProperty(name = "quarkus.datasource.username") lateinit var user: String

    @ConfigProperty(name = "quarkus.datasource.password") lateinit var password: String

    @Test
    fun `a cold connection through a tunnel, versus a warm one`() {
        val results = mutableListOf<Triple<String, Long, Long>>()

        for (rtt in listOf(0, 20)) {
            QueueLoadBenchmark.ProxiedPostgresResource.setRoundTripMillis(rtt)
            // The first one after a toxic change is not representative.
            openAndClose()

            val cold = (1..SAMPLES).map { measure { openAndClose() } }.sorted()
            val warm =
                dataSource.connection
                    .use { connection ->
                        (1..SAMPLES).map {
                            measure {
                                connection.createStatement().use {
                                    it.executeQuery("select 1").close()
                                }
                            }
                        }
                    }
                    .sorted()

            results += Triple("new connection", rtt.toLong(), cold[cold.size / 2])
            results += Triple("query on an open one", rtt.toLong(), warm[warm.size / 2])
        }

        QueueLoadBenchmark.ProxiedPostgresResource.setRoundTripMillis(0)

        println()
        println("Cost of a database connection when the database is on core")
        println()
        println("%-24s %10s %10s %10s".format("", "local p50", "tunnel p50", "round trips"))
        println("-".repeat(58))
        for (label in listOf("new connection", "query on an open one")) {
            val local = results.first { it.first == label && it.second == 0L }.third
            val far = results.first { it.first == label && it.second == 20L }.third
            println(
                "%-24s %8d ms %8d ms %10.1f"
                    .format(label, local / 1000, far / 1000, (far - local) / 20_000.0)
            )
        }
        println()
        println("Agroal opens connections on demand and reaps idle ones, so this is paid")
        println("per connection after every quiet period — not once at startup.")
        println()
    }

    private fun openAndClose() {
        DriverManager.getConnection(jdbcUrl, user, password).use { connection ->
            connection.createStatement().use { it.executeQuery("select 1").close() }
        }
    }

    private inline fun measure(block: () -> Unit): Long {
        val started = System.nanoTime()
        block()
        return (System.nanoTime() - started) / 1_000
    }

    private companion object {
        const val SAMPLES = 15
    }
}

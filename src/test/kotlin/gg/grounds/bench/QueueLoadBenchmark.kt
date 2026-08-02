package gg.grounds.bench

import eu.rekawek.toxiproxy.Proxy
import eu.rekawek.toxiproxy.ToxiproxyClient
import eu.rekawek.toxiproxy.model.ToxicDirection
import gg.grounds.domain.ModeConfig
import gg.grounds.domain.Ticket
import gg.grounds.matcher.Matcher
import gg.grounds.persistence.ValkeyQueue
import io.quarkus.redis.datasource.RedisDataSource
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.common.QuarkusTestResourceLifecycleManager
import io.quarkus.test.junit.QuarkusTest
import jakarta.inject.Inject
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.roundToLong
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.testcontainers.containers.GenericContainer
import org.testcontainers.containers.Network
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.containers.ToxiproxyContainer
import org.testcontainers.utility.DockerImageName

/**
 * What the queue does under load, and what the tunnel costs.
 *
 * The queue used to be a Service in the same namespace; it is now on core, behind a Cloudflare
 * Tunnel. That changes which number matters. Throughput against a local Valkey was never the
 * constraint and still is not — what decides whether this design works is **round trips per tick**,
 * because every one of them now costs a WAN hop instead of a loopback.
 *
 * So this measures against a real Valkey with a real proxy in front of it, and runs the whole thing
 * twice: once with the proxy passing traffic straight through, once with 20 ms of round-trip
 * latency injected. The delta between the two columns *is* the tunnel.
 *
 * 20 ms is deliberately pessimistic for the regions that exist — Frankfurt to Amsterdam is about 10
 * ms, and both satellites reach core through Cloudflare's edge rather than directly. Being wrong in
 * this direction is the safe way to be wrong.
 *
 * Not run by `./gradlew test`: these are measurements, they take minutes, and the absolute numbers
 * belong to whatever machine produced them. What travels between machines is the *shape* — flat
 * versus linear in queue depth.
 */
@QuarkusTest
@Tag("benchmark")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
// restrictToAnnotatedClass, and not optional: a Quarkus test resource is
// global by default, so without it these containers start for the whole test
// run and this benchmark's proxied Valkey silently replaces the one the
// integration tests are pointed at.
@QuarkusTestResource(
    QueueLoadBenchmark.ProxiedValkeyResource::class,
    restrictToAnnotatedClass = true,
)
@QuarkusTestResource(QueueLoadBenchmark.PostgresResource::class, restrictToAnnotatedClass = true)
class QueueLoadBenchmark {

    @Inject lateinit var queue: ValkeyQueue

    @Inject lateinit var matcher: Matcher

    @Inject lateinit var redis: RedisDataSource

    private val mode = ModeConfig(modeId = "duel", teamSize = 1, teamCount = 2)

    @Test
    fun `queue under load, with and without the tunnel`() {
        val rows = mutableListOf<Row>()

        for (hop in listOf(Hop("local", 0), Hop("tunnel", 20))) {
            ProxiedValkeyResource.setRoundTripMillis(hop.rttMs)
            // The first call after a toxic change pays for the new connection.
            warmUp()

            rows += measureEnqueue(hop)
            rows += measureIdleProbe(hop)
            for (depth in DEPTHS) {
                rows += measureSnapshot(hop, depth)
                rows += measureSnapshotTheOldWay(hop, depth)
                rows += measureTick(hop, depth)
            }
            rows += measureContention(hop)
        }

        ProxiedValkeyResource.setRoundTripMillis(0)
        report(rows)
    }

    // ── the measurements ────────────────────────────────────────────────

    /** One round trip per call. The floor for everything else. */
    private fun measureEnqueue(hop: Hop): Row {
        flush()
        val samples =
            (1..ENQUEUE_SAMPLES).map {
                val ticket = ticket("bench-$it")
                timeMicros { queue.enqueue(ticket, TTL) }
            }
        return Row(hop, "enqueue", 1, samples)
    }

    /**
     * What a tick costs when nobody is queuing.
     *
     * The most realistic load there is: most modes are empty most of the time, and `tick()` walks
     * every known mode every pass. Each empty one still costs a ZCARD to find that out — which was
     * free against a local Service and is a WAN hop now. This is the cost that grows as modes are
     * added rather than as players arrive, and modes are the axis that actually grows.
     */
    private fun measureIdleProbe(hop: Hop): Row {
        flush()
        val samples =
            (1..ENQUEUE_SAMPLES).map { timeMicros { queue.queueDepth("mode-that-nobody-is-in") } }
        return Row(hop, "idle probe (per empty mode)", 0, samples)
    }

    /** One round trip regardless of depth — that is the point of snapshot.lua. */
    private fun measureSnapshot(hop: Hop, depth: Int): Row {
        fill(depth)
        val samples =
            (1..SNAPSHOT_SAMPLES).map {
                var size = 0
                val micros = timeMicros { size = queue.snapshot(mode.modeId, 200).size }
                check(size == depth) { "expected $depth tickets, read $size" }
                micros
            }
        return Row(hop, "snapshot", depth, samples)
    }

    /**
     * What the matcher used to do: read the index, then fetch each ticket.
     *
     * Kept as a measurement rather than a memory, because the difference is the entire argument for
     * why a shared queue is deployable at all. Both methods still exist on ValkeyQueue, so this is
     * the real old path, not a model of it.
     */
    private fun measureSnapshotTheOldWay(hop: Hop, depth: Int): Row {
        fill(depth)
        val samples =
            (1..OLD_SNAPSHOT_SAMPLES).map {
                timeMicros {
                    queue.queuedTicketIdsByWait(mode.modeId, 200).mapNotNull {
                        queue.findTicket(it)
                    }
                }
            }
        return Row(hop, "snapshot (pre-0.7.0)", depth, samples)
    }

    /**
     * A whole matcher pass: depth check, snapshot, and then **a claim per match formed**. That last
     * part is why a tick is not one round trip — its cost scales with how many matches it forms,
     * not with how many people wait.
     */
    private fun measureTick(hop: Hop, depth: Int): Row {
        var formed = 0
        val samples =
            (1..TICK_SAMPLES).map {
                fill(depth)
                val micros = timeMicros { formed = matcher.tickMode(mode, Instant.now()) }
                check(formed > 0) { "a tick over $depth tickets formed nothing" }
                micros
            }
        return Row(hop, "tick (formed $formed matches)", depth, samples)
    }

    /**
     * Two matchers on one queue, which is the whole reason the claim is a Lua script. Measures what
     * contention costs and asserts it stays correct: a ticket committed to two matches would be the
     * one failure that matters.
     */
    private fun measureContention(hop: Hop): Row {
        fill(CONTENTION_DEPTH)
        val tickets = queue.snapshot(mode.modeId, 200)
        val won = AtomicInteger()
        val start = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(MATCHERS)
        val samples = java.util.Collections.synchronizedList(mutableListOf<Long>())

        val pairs = tickets.chunked(2).filter { it.size == 2 }
        repeat(MATCHERS) { matcherIndex ->
            pool.submit {
                start.await()
                // Every matcher tries every pair, in the same order. That is the
                // worst case on purpose — real matchers work from snapshots
                // taken at different moments and collide less.
                for (pair in pairs) {
                    val micros = timeMicros {
                        if (
                            queue.claim(
                                UUID.randomUUID().toString(),
                                mode.modeId,
                                pair.map { it.id },
                                1,
                                "nl-ams1",
                                Instant.now(),
                                TTL,
                            )
                        ) {
                            won.incrementAndGet()
                        }
                    }
                    if (matcherIndex == 0) samples += micros
                }
            }
        }
        start.countDown()
        pool.shutdown()
        check(pool.awaitTermination(5, TimeUnit.MINUTES)) { "contention run did not finish" }

        assertEquals(
            pairs.size,
            won.get(),
            "each pair must be claimed exactly once, no matter how many matchers race for it",
        )
        return Row(hop, "claim ($MATCHERS matchers racing)", CONTENTION_DEPTH, samples.toList())
    }

    // ── plumbing ────────────────────────────────────────────────────────

    private fun warmUp() {
        flush()
        repeat(3) { queue.snapshot(mode.modeId, 200) }
    }

    private fun flush() {
        redis.execute("FLUSHALL")
        queue.loadScripts()
    }

    private fun fill(depth: Int) {
        flush()
        repeat(depth) { queue.enqueue(ticket("p$it", waited = (depth - it).toLong()), TTL) }
    }

    private fun ticket(player: String, waited: Long = 0) =
        Ticket(
            id = UUID.randomUUID().toString(),
            playerId = player,
            modeId = mode.modeId,
            // Spread ratings so the band actually has to work rather than
            // matching the first two tickets it sees every time.
            mu = 20.0 + (player.hashCode().toDouble() % 10.0),
            sigma = 8.33,
            enqueuedAt = Instant.now().minusSeconds(waited),
            location = if (player.hashCode() % 2 == 0) "nl-ams1" else "de-fra1",
        )

    private inline fun timeMicros(block: () -> Unit): Long {
        val started = System.nanoTime()
        block()
        return (System.nanoTime() - started) / 1_000
    }

    private data class Hop(val label: String, val rttMs: Int)

    private class Row(val hop: Hop, val operation: String, val depth: Int, samples: List<Long>) {
        private val sorted = samples.sorted()

        val p50: Long
            get() = percentile(50)

        val p99: Long
            get() = percentile(99)

        val perSecond: Long
            get() = if (p50 == 0L) 0 else (1_000_000.0 / p50).roundToLong()

        private fun percentile(p: Int): Long {
            if (sorted.isEmpty()) return 0
            val index = ((p / 100.0) * (sorted.size - 1)).roundToInt()
            return sorted[index]
        }

        private fun Double.roundToInt(): Int = Math.round(this).toInt()
    }

    private fun report(rows: List<Row>) {
        val local = rows.filter { it.hop.rttMs == 0 }
        val tunnel = rows.filter { it.hop.rttMs > 0 }

        println()
        println("Queue under load — real Valkey, real Lua, Toxiproxy in front")
        println("tunnel column = 20 ms round trip injected (pessimistic for eu regions)")
        println()
        println(
            "%-34s %6s | %10s %10s | %10s %10s | %8s"
                .format(
                    "operation",
                    "depth",
                    "local p50",
                    "local p99",
                    "tunnel p50",
                    "tunnel p99",
                    "hops",
                )
        )
        println("-".repeat(100))

        for ((index, row) in local.withIndex()) {
            val far = tunnel.getOrNull(index)
            // Round trips implied by the added latency: how many 20 ms hops fit
            // in the difference. This is the number that predicts every other
            // deployment — it does not depend on this machine at all.
            val hops =
                if (far == null) "?"
                else ((far.p50 - row.p50).toDouble() / 20_000.0).let { "%.1f".format(it) }
            println(
                "%-34s %6d | %8d µs %8d µs | %8d µs %8d µs | %8s"
                    .format(
                        row.operation,
                        row.depth,
                        row.p50,
                        row.p99,
                        far?.p50 ?: 0,
                        far?.p99 ?: 0,
                        hops,
                    )
            )
        }
        println()
        println("A tick runs every 2 s, and walks every known mode.")
        val idle = tunnel.firstOrNull { it.operation.startsWith("idle") }?.p50 ?: 0
        if (idle > 0) {
            for (modes in listOf(5, 20, 50)) {
                println(
                    "  %2d modes, all empty: %5d ms per tick just to establish that"
                        .format(modes, modes * idle / 1000)
                )
            }
        }
        println()
    }

    // ── containers ──────────────────────────────────────────────────────

    /**
     * Valkey with a proxy in front, so latency can be turned on and off without restarting
     * anything. Both containers share a network: Toxiproxy has to reach Valkey by container name,
     * and the test reaches Toxiproxy by mapped port.
     */
    class ProxiedValkeyResource : QuarkusTestResourceLifecycleManager {
        private lateinit var network: Network
        private lateinit var valkey: GenericContainer<*>
        private lateinit var toxiproxy: ToxiproxyContainer

        override fun start(): Map<String, String> {
            network = Network.newNetwork()
            valkey =
                GenericContainer(DockerImageName.parse("valkey/valkey:8-alpine"))
                    .withNetwork(network)
                    .withNetworkAliases("valkey")
                    .withExposedPorts(6379)
            valkey.start()

            toxiproxy = ToxiproxyContainer(TOXIPROXY_IMAGE).withNetwork(network)
            toxiproxy.start()

            val client = ToxiproxyClient(toxiproxy.host, toxiproxy.controlPort)
            proxy = client.createProxy("valkey", "0.0.0.0:8666", "valkey:6379")

            return mapOf(
                "quarkus.redis.hosts" to
                    "redis://${toxiproxy.host}:${toxiproxy.getMappedPort(8666)}"
            )
        }

        override fun stop() {
            if (this::toxiproxy.isInitialized) toxiproxy.stop()
            if (this::valkey.isInitialized) valkey.stop()
            if (this::network.isInitialized) network.close()
        }

        companion object {
            private val TOXIPROXY_IMAGE = DockerImageName.parse("ghcr.io/shopify/toxiproxy:2.12.0")
            private var proxy: Proxy? = null

            /**
             * Split evenly over both directions, because that is what a round trip is: a request
             * that takes half and a reply that takes half. Toxiproxy applies a latency toxic per
             * direction.
             */
            fun setRoundTripMillis(rtt: Int) {
                val p = proxy ?: return
                for (name in listOf(UPSTREAM, DOWNSTREAM)) {
                    runCatching { p.toxics().get(name).remove() }
                }
                if (rtt <= 0) return
                p.toxics().latency(UPSTREAM, ToxicDirection.UPSTREAM, (rtt / 2).toLong())
                p.toxics().latency(DOWNSTREAM, ToxicDirection.DOWNSTREAM, (rtt / 2).toLong())
            }

            private const val UPSTREAM = "rtt-up"
            private const val DOWNSTREAM = "rtt-down"
        }
    }

    /** The app boots as a whole, so the datasource has to resolve. */
    class PostgresResource : QuarkusTestResourceLifecycleManager {
        private lateinit var container: PostgreSQLContainer<*>

        override fun start(): Map<String, String> {
            container = PostgreSQLContainer(DockerImageName.parse("postgres:17-alpine"))
            container.start()
            return mapOf(
                "quarkus.datasource.jdbc.url" to container.jdbcUrl,
                "quarkus.datasource.username" to container.username,
                "quarkus.datasource.password" to container.password,
            )
        }

        override fun stop() {
            if (this::container.isInitialized) container.stop()
        }
    }

    /**
     * Postgres with the same proxy treatment as the queue.
     *
     * Forming a match writes the match record synchronously inside the tick, and in production that
     * database is on core behind its own tunnel — a benchmark with a local Postgres understates
     * what a match costs by about half.
     */
    class ProxiedPostgresResource : QuarkusTestResourceLifecycleManager {
        private lateinit var network: Network
        private lateinit var postgres: PostgreSQLContainer<*>
        private lateinit var toxiproxy: ToxiproxyContainer

        override fun start(): Map<String, String> {
            network = Network.newNetwork()
            postgres =
                PostgreSQLContainer(DockerImageName.parse("postgres:17-alpine"))
                    .withNetwork(network)
                    .withNetworkAliases("postgres")
            postgres.start()

            toxiproxy = ToxiproxyContainer(TOXIPROXY_IMAGE).withNetwork(network)
            toxiproxy.start()

            val client = ToxiproxyClient(toxiproxy.host, toxiproxy.controlPort)
            proxy = client.createProxy("postgres", "0.0.0.0:8666", "postgres:5432")

            return mapOf(
                "quarkus.datasource.jdbc.url" to
                    "jdbc:postgresql://${toxiproxy.host}:${toxiproxy.getMappedPort(8666)}/${postgres.databaseName}",
                "quarkus.datasource.username" to postgres.username,
                "quarkus.datasource.password" to postgres.password,
            )
        }

        override fun stop() {
            if (this::toxiproxy.isInitialized) toxiproxy.stop()
            if (this::postgres.isInitialized) postgres.stop()
            if (this::network.isInitialized) network.close()
        }

        companion object {
            private var proxy: Proxy? = null

            fun setRoundTripMillis(rtt: Int) = applyLatency(proxy, rtt)
        }
    }

    companion object {
        private val TOXIPROXY_IMAGE = DockerImageName.parse("ghcr.io/shopify/toxiproxy:2.12.0")

        private const val UPSTREAM = "rtt-up"
        private const val DOWNSTREAM = "rtt-down"

        /**
         * Split evenly over both directions, because that is what a round trip is: a request that
         * takes half and a reply that takes half. Toxiproxy applies a latency toxic per direction.
         */
        internal fun applyLatency(proxy: Proxy?, rtt: Int) {
            val p = proxy ?: return
            for (name in listOf(UPSTREAM, DOWNSTREAM)) {
                runCatching { p.toxics().get(name).remove() }
            }
            if (rtt <= 0) return
            p.toxics().latency(UPSTREAM, ToxicDirection.UPSTREAM, (rtt / 2).toLong())
            p.toxics().latency(DOWNSTREAM, ToxicDirection.DOWNSTREAM, (rtt / 2).toLong())
        }

        const val TTL = 3600L

        // Queue depths worth knowing: a quiet mode, a busy one, and the cap the
        // matcher reads at.
        val DEPTHS = listOf(20, 100, 200)
        const val DEPTH_MATCHES = 100

        const val ENQUEUE_SAMPLES = 200
        const val SNAPSHOT_SAMPLES = 50
        // Fewer, because at 200 tickets over a tunnel each one is 200 round
        // trips and the point is made long before the sample is large.
        const val OLD_SNAPSHOT_SAMPLES = 5
        const val TICK_SAMPLES = 10

        const val CONTENTION_DEPTH = 100
        const val MATCHERS = 4
    }
}

package gg.grounds.bench

import gg.grounds.domain.ModeConfig
import gg.grounds.domain.Ticket
import gg.grounds.matcher.Matcher
import gg.grounds.persistence.ValkeyQueue
import io.quarkus.redis.datasource.RedisDataSource
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.junit.QuarkusTest
import jakarta.inject.Inject
import java.time.Instant
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.roundToInt
import org.eclipse.microprofile.config.inject.ConfigProperty
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance

/**
 * Does the queue hold up at a given concurrent-player count, and what gives way first?
 *
 * **The capacity model this produced**, validated against every row below:
 * ```
 * matches/s = modes × (snapshotLimit ÷ playersPerMatch) ÷ tickSeconds
 * players   = matches/s × playersPerMatch × (matchSeconds + waitSeconds)
 * ```
 *
 * Note what is absent: the number of matcher replicas, and the network. Every replica reads the
 * same longest-waiting tickets and proposes the same matches, so all but one lose the claim race —
 * measured at 1, 4 and 8 replicas, which differ by less than the run-to-run noise. Replicas buy
 * availability, not throughput. Sharding modes across them would buy throughput; nothing does
 * today.
 *
 * Arithmetic can answer part of this, and the arithmetic is not the interesting part. What it
 * misses: the tick walks every mode whether or not anyone is in it, forming a match costs a
 * **second** remote write to Postgres that also crosses a tunnel, and several matcher replicas
 * contend for the same tickets. So this drives real load through the real code and watches whether
 * the queue drains as fast as it fills.
 *
 * **The divergence test is the whole thing.** Throughput numbers flatter a system that is quietly
 * falling behind; a queue that is not keeping up shows it by growing. Each level runs long enough
 * for depth to settle, and what gets reported is whether depth at the end is where it started.
 *
 * Both remotes are behind the proxy, with the same latency: in production the queue is on core
 * through one tunnel and the application database is on core through another. A benchmark with a
 * local Postgres would understate the cost of forming a match by half.
 *
 * The player model is a cycle: queue, wait, play, queue again. At a steady N players the enqueue
 * rate is N / (wait + match), which is what actually loads the queue — not N itself.
 */
@QuarkusTest
@Tag("benchmark")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@QuarkusTestResource(
    QueueLoadBenchmark.ProxiedValkeyResource::class,
    restrictToAnnotatedClass = true,
)
@QuarkusTestResource(
    QueueLoadBenchmark.ProxiedPostgresResource::class,
    restrictToAnnotatedClass = true,
)
class SustainedLoadBenchmark {

    @Inject lateinit var queue: ValkeyQueue

    @Inject lateinit var matcher: Matcher

    @Inject lateinit var redis: RedisDataSource

    // Read from the running config, not from the system property that may have
    // set it: the value that matters is the one the client actually got, and
    // the two differ as soon as it is set in application.properties.
    @ConfigProperty(name = "quarkus.redis.max-pool-size") lateinit var redisPoolSize: String

    @ConfigProperty(name = "grounds.match.snapshot-limit") lateinit var snapshotLimit: String

    @Test
    fun `sustained load at rising player counts`() {
        QueueLoadBenchmark.ProxiedValkeyResource.setRoundTripMillis(TUNNEL_RTT_MS)
        QueueLoadBenchmark.ProxiedPostgresResource.setRoundTripMillis(TUNNEL_RTT_MS)

        val results = CCU_LEVELS.map { ccu -> run(ccu) }

        QueueLoadBenchmark.ProxiedValkeyResource.setRoundTripMillis(0)
        QueueLoadBenchmark.ProxiedPostgresResource.setRoundTripMillis(0)
        report(results)
    }

    private fun run(ccu: Int): Result {
        redis.execute("FLUSHALL")
        queue.loadScripts()

        // Steady state: a player is either waiting or playing, so the rate at
        // which tickets arrive is the population divided by one full cycle.
        val enqueuesPerSecond = ccu.toDouble() / (MATCH_SECONDS + TARGET_WAIT_SECONDS)
        val requiredMatchesPerSecond = enqueuesPerSecond / MODE.playersPerMatch

        val running = AtomicBoolean(true)
        val enqueued = AtomicLong()
        val formed = AtomicLong()
        val tickMicros = java.util.Collections.synchronizedList(mutableListOf<Long>())
        val depths = java.util.Collections.synchronizedList(mutableListOf<Int>())

        // Enough threads that the driver itself is never the bottleneck: each
        // enqueue blocks for a round trip, so the driver needs at least
        // rate × latency of them in flight.
        //
        // Independent threads, NOT one scheduleAtFixedRate task: a scheduled
        // executor never overlaps runs of the same periodic task, so a task
        // that blocks for 21 ms caps at 47/s no matter how large the pool is.
        // That capped the first version of this benchmark at a rate it then
        // reported as "keeps up".
        val driverThreads =
            (enqueuesPerSecond * (TUNNEL_RTT_MS / 1000.0) * 4).roundToInt().coerceIn(4, 192)
        val drivers = Executors.newFixedThreadPool(driverThreads)
        val matchers = Executors.newScheduledThreadPool(MATCHER_REPLICAS)
        val sampler = Executors.newSingleThreadScheduledExecutor()

        val perThreadPeriodNanos = (1_000_000_000.0 * driverThreads / enqueuesPerSecond).toLong()
        val rejected = AtomicLong()
        repeat(driverThreads) {
            drivers.submit {
                while (running.get()) {
                    val started = System.nanoTime()
                    val n = enqueued.incrementAndGet()
                    val modeId = MODES[(n % MODES.size).toInt()]
                    // A failure here is the finding, not an error to hide: past
                    // the connection pool's depth the client rejects rather than
                    // waits, and a player sees their queue attempt fail.
                    if (runCatching { queue.enqueue(ticket(modeId, n), TTL) }.isFailure) {
                        rejected.incrementAndGet()
                    }
                    val remaining = perThreadPeriodNanos - (System.nanoTime() - started)
                    if (remaining > 0) TimeUnit.NANOSECONDS.sleep(remaining)
                }
            }
        }

        // Each replica walks every mode every tick, exactly as the scheduler
        // does. Concurrent replicas on one queue is the design, not a stress:
        // the claim script is what makes it safe.
        repeat(MATCHER_REPLICAS) {
            matchers.scheduleAtFixedRate(
                {
                    if (!running.get()) return@scheduleAtFixedRate
                    val started = System.nanoTime()
                    for (modeId in MODES) {
                        runCatching {
                            formed.addAndGet(
                                matcher.tickMode(MODE.copy(modeId = modeId), Instant.now()).toLong()
                            )
                        }
                    }
                    tickMicros += (System.nanoTime() - started) / 1_000
                },
                0,
                TICK_MILLIS,
                TimeUnit.MILLISECONDS,
            )
        }

        sampler.scheduleAtFixedRate(
            { depths += MODES.sumOf { queue.queueDepth(it).toInt() } },
            1,
            1,
            TimeUnit.SECONDS,
        )

        Thread.sleep(RUN_SECONDS * 1000L)
        running.set(false)
        drivers.shutdownNow()
        matchers.shutdownNow()
        sampler.shutdownNow()
        drivers.awaitTermination(30, TimeUnit.SECONDS)
        matchers.awaitTermination(30, TimeUnit.SECONDS)

        // First third versus last third: a queue that is keeping up settles,
        // one that is not keeps climbing for as long as you watch it.
        val early = depths.take(depths.size / 3).ifEmpty { listOf(0) }.average()
        val late = depths.takeLast(depths.size / 3).ifEmpty { listOf(0) }.average()

        return Result(
            ccu = ccu,
            requiredMatchesPerSecond = requiredMatchesPerSecond,
            achievedMatchesPerSecond = formed.get().toDouble() / RUN_SECONDS,
            enqueuedPerSecond = enqueued.get().toDouble() / RUN_SECONDS,
            rejectedPerSecond = rejected.get().toDouble() / RUN_SECONDS,
            tickP50 = percentile(tickMicros, 50),
            tickP99 = percentile(tickMicros, 99),
            depthEarly = early,
            depthLate = late,
        )
    }

    private fun ticket(modeId: String, n: Long) =
        Ticket(
            id = UUID.randomUUID().toString(),
            playerId = "player-$n",
            modeId = modeId,
            mu = 20.0 + (n % 10),
            sigma = 8.33,
            enqueuedAt = Instant.now(),
            location = if (n % 2 == 0L) "nl-ams1" else "de-fra1",
        )

    private fun percentile(samples: List<Long>, p: Int): Long {
        val sorted = samples.sorted()
        if (sorted.isEmpty()) return 0
        return sorted[Math.round((p / 100.0) * (sorted.size - 1)).toInt()]
    }

    private class Result(
        val ccu: Int,
        val requiredMatchesPerSecond: Double,
        val achievedMatchesPerSecond: Double,
        val enqueuedPerSecond: Double,
        val rejectedPerSecond: Double,
        val tickP50: Long,
        val tickP99: Long,
        val depthEarly: Double,
        val depthLate: Double,
    ) {
        /** Keeping up means the backlog settles, not that throughput looks big. */
        val keepsUp: Boolean
            get() =
                rejectedPerSecond < 1.0 &&
                    depthLate <= depthEarly * 1.5 + MODES.size * MODE.playersPerMatch
    }

    private fun report(results: List<Result>) {
        println()
        println("Sustained load — ${MODES.size} modes, $MATCHER_REPLICAS matcher replicas,")
        println("redis connection pool: $redisPoolSize, snapshot limit: $snapshotLimit")
        println(
            "a tick can therefore form at most " +
                "${snapshotLimit.toInt() / MODE.playersPerMatch} matches per mode"
        )
        println(
            "${MODE.playersPerMatch} players per match, ${MATCH_SECONDS}s matches, " +
                "${TUNNEL_RTT_MS}ms round trip to BOTH the queue and the database"
        )
        println()
        println(
            "%8s | %9s %9s | %9s %8s | %9s %9s | %7s %7s | %s"
                .format(
                    "ccu",
                    "need m/s",
                    "got m/s",
                    "enq/s",
                    "fail/s",
                    "tick p50",
                    "tick p99",
                    "depth→",
                    "depth←",
                    "keeps up",
                )
        )
        println("-".repeat(104))
        for (r in results) {
            println(
                "%8d | %9.2f %9.2f | %9.1f %8.1f | %7d ms %7d ms | %7.0f %7.0f | %s"
                    .format(
                        r.ccu,
                        r.requiredMatchesPerSecond,
                        r.achievedMatchesPerSecond,
                        r.enqueuedPerSecond,
                        r.rejectedPerSecond,
                        r.tickP50 / 1000,
                        r.tickP99 / 1000,
                        r.depthEarly,
                        r.depthLate,
                        if (r.keepsUp) "yes" else "NO — backlog growing",
                    )
            )
        }
        println()
        println("depth→ is the mean queue depth over the first third of the run, depth← the last.")
        println("A queue that keeps up settles; one that does not keeps climbing.")
        println()
    }

    private companion object {
        const val TTL = 3600L
        const val TUNNEL_RTT_MS = 20
        const val TICK_MILLIS = 2_000L
        const val RUN_SECONDS = 30

        /** Two regions, two replicas each — what stage would run. */
        val MATCHER_REPLICAS = System.getProperty("bench.replicas")?.toInt() ?: 4

        /** 4v4v4v4, the shape bedwars-squads actually has. */
        val MODE = ModeConfig(modeId = "bench", teamSize = 4, teamCount = 4)
        val MODES = (1..5).map { "mode-$it" }

        /** Ten minutes of play, and a wait nobody would complain about. */
        const val MATCH_SECONDS = 600
        const val TARGET_WAIT_SECONDS = 30

        /** Overridable: `./gradlew benchmark -Pccu=5000,200000,600000`. */
        val CCU_LEVELS: List<Int> =
            System.getProperty("bench.ccu")?.split(',')?.map { it.trim().toInt() }
                ?: listOf(5_000, 50_000, 200_000, 1_000_000)
    }
}

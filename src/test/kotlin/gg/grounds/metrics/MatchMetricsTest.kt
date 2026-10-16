package gg.grounds.metrics

import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import java.time.Duration
import java.util.concurrent.TimeUnit
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * The queue-wait timer feeds the public status page's "queue wait" chart as sum/count, so both have
 * to be per player and per mode — a wrong unit or a missing tag shows up there, not in a log.
 */
class MatchMetricsTest {

    @Test
    fun `queue waits are recorded per player and per mode`() {
        val registry = SimpleMeterRegistry()
        val metrics = MatchMetrics(registry)

        metrics.queueWait("duel", Duration.ofSeconds(10))
        metrics.queueWait("duel", Duration.ofSeconds(30))
        metrics.queueWait("bedwars", Duration.ofSeconds(5))

        val duel = registry.get("match.queue.wait").tag("mode", "duel").timer()
        assertEquals(2, duel.count())
        assertEquals(40.0, duel.totalTime(TimeUnit.SECONDS), 0.001)
        assertEquals(20.0, duel.mean(TimeUnit.SECONDS), 0.001)

        val bedwars = registry.get("match.queue.wait").tag("mode", "bedwars").timer()
        assertEquals(1, bedwars.count())
    }

    @Test
    fun `every timeline histogram shares the contract's buckets`() {
        val registry = SimpleMeterRegistry()
        val metrics = MatchMetrics(registry)

        metrics.queueWait("duel", Duration.ofMillis(300))
        metrics.timeToMatch("duel", Duration.ofMillis(300))
        metrics.timeToAllocate("duel", "ok", Duration.ofMillis(300))
        metrics.timeToPush("duel", "failed", Duration.ofMillis(300))

        val timers =
            listOf(
                registry.get("match.queue.wait").tag("mode", "duel").timer(),
                registry.get("grounds.match.time.to.match").tag("mode", "duel").timer(),
                registry
                    .get("grounds.match.time.to.allocate")
                    .tags("mode", "duel", "result", "ok")
                    .timer(),
                registry
                    .get("grounds.match.time.to.push")
                    .tags("mode", "duel", "result", "failed")
                    .timer(),
            )
        for (timer in timers) {
            val buckets = timer.takeSnapshot().histogramCounts()
            assertEquals(BUCKETS, buckets.map { it.bucket(TimeUnit.SECONDS) }, timer.id.name)
            // Cumulative: 0.3 s is above the 0.25 bucket and inside every one from 0.5 up.
            assertEquals(0.0, buckets.first { it.bucket(TimeUnit.SECONDS) == 0.25 }.count())
            assertEquals(1.0, buckets.first { it.bucket(TimeUnit.SECONDS) == 0.5 }.count())
            assertEquals(1.0, buckets.last().count())
            assertEquals(1, timer.count())
        }
    }

    @Test
    fun `time to match is per player and per mode`() {
        val registry = SimpleMeterRegistry()
        val metrics = MatchMetrics(registry)

        metrics.timeToMatch("duel", Duration.ofSeconds(4))
        metrics.timeToMatch("duel", Duration.ofSeconds(6))

        val timer = registry.get("grounds.match.time.to.match").tag("mode", "duel").timer()
        assertEquals(2, timer.count())
        assertEquals(10.0, timer.totalTime(TimeUnit.SECONDS), 0.001)
    }

    private companion object {
        private val BUCKETS =
            listOf(
                0.1,
                0.25,
                0.5,
                1.0,
                2.0,
                3.0,
                5.0,
                7.5,
                10.0,
                15.0,
                20.0,
                30.0,
                45.0,
                60.0,
                90.0,
                120.0,
                180.0,
                300.0,
            )
    }
}

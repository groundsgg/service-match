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
}

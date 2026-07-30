package gg.grounds.metrics

import io.micrometer.core.instrument.Gauge
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Timer
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import java.util.concurrent.ConcurrentHashMap

/**
 * Matchmaking metrics, in one place.
 *
 * The service had tracing and nothing else: a span tells you what happened in a single call, but
 * not whether the queue is backing up, whether the tick keeps up with arrivals, or whether a formed
 * match ever reaches a server. Those are rates and gauges over time, which is what this exposes on
 * `/q/metrics` for the satellite's Alloy to scrape.
 *
 * Every series is tagged by `mode`, because that is the axis an operator actually asks about — "is
 * duel matching" is a different question from "is ranked_2v2 matching", and a single aggregate
 * hides a dead mode behind a busy one. Cardinality stays bounded: modes are a small,
 * forge-controlled set, not anything player-derived.
 *
 * Names follow the Micrometer convention (dot-separated, base unit suffixes); the Prometheus
 * registry renders them as `match_tickets_enqueued_total` and friends. Kept deliberately few — each
 * one answers a question someone asks while a region is live, and nothing is here only because it
 * could be.
 */
@ApplicationScoped
class MatchMetrics @Inject constructor(private val registry: MeterRegistry) {

    /**
     * Live queue-depth suppliers, one per mode, held here on purpose: Micrometer keeps only a weak
     * reference to a gauge's state object, so a supplier that lived solely inside the gauge would
     * be collected and the gauge would read NaN. This map is the strong reference that keeps them
     * alive.
     */
    private val depthSuppliers = ConcurrentHashMap<String, () -> Long>()

    /** A ticket joined the queue. */
    fun ticketEnqueued(mode: String) {
        registry.counter("match.tickets.enqueued", "mode", mode).increment()
    }

    /** A match was claimed: one match, and the players it took. */
    fun matchFormed(mode: String, players: Int) {
        registry.counter("match.formed", "mode", mode).increment()
        registry.counter("match.players.matched", "mode", mode).increment(players.toDouble())
    }

    /**
     * Time one matcher pass over one mode, whatever it returns. A tick that starts creeping toward
     * the 2s interval is the queue outgrowing the loop — visible here before it shows up as players
     * waiting.
     */
    fun <T> timeTick(mode: String, pass: () -> T): T {
        val sample = Timer.start(registry)
        try {
            return pass()
        } finally {
            sample.stop(registry.timer("match.tick.duration", "mode", mode))
        }
    }

    /**
     * Time one allocation attempt and record its outcome. `result` is the branch it took —
     * `assigned`, `no_server`, `server_refused`, `unknown_mode`, `gave_up`, `lost_race`, `error` —
     * so a rise in "formed but never started" points at which failure caused it rather than just
     * that it happened.
     */
    fun timeAllocation(mode: String, attempt: () -> String) {
        val sample = Timer.start(registry)
        var result = "error"
        try {
            result = attempt()
        } finally {
            sample.stop(registry.timer("match.allocation.duration", "mode", mode, "result", result))
            registry.counter("match.allocations", "mode", mode, "result", result).increment()
        }
    }

    /**
     * Publish this mode's queue depth as a gauge, once. Idempotent per mode, so calling it every
     * tick is free after the first. The supplier is read by Micrometer at scrape time — a `ZCARD`,
     * cheap — so the value is always current rather than whatever the last tick happened to see.
     */
    fun ensureQueueDepthGauge(mode: String, depth: () -> Long) {
        if (depthSuppliers.putIfAbsent(mode, depth) == null) {
            Gauge.builder("match.queue.depth", depthSuppliers) {
                    (it[mode]?.invoke() ?: 0L).toDouble()
                }
                .tag("mode", mode)
                .register(registry)
        }
    }
}

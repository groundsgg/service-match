package gg.grounds.metrics

import io.micrometer.core.instrument.Gauge
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Timer
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import java.time.Duration
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

    /**
     * The rating store could not be read, so a ticket was seeded with defaults.
     *
     * Worth alerting on rather than merely counting: every match formed while this is non-zero is
     * unranked, so a long outage quietly stops the ladder moving.
     */
    fun ratingLookupDegraded(mode: String) {
        registry.counter("match.rating.lookup.degraded", "mode", mode).increment()
    }

    /** A match was claimed: one match, and the players it took. */
    fun matchFormed(mode: String, players: Int) {
        registry.counter("match.formed", "mode", mode).increment()
        registry.counter("match.players.matched", "mode", mode).increment(players.toDouble())
    }

    /**
     * How long one player waited, from enqueue to the claim that put them in a match. Recorded per
     * player, not per match, so the average is what a player experiences. Cancelled and expired
     * tickets never reach a claim and are not in here: this is "how long until you got a game", not
     * "how long people put up with the queue".
     */
    fun queueWait(mode: String, waited: Duration) {
        timeline("match.queue.wait", waited, "mode", mode)
    }

    /**
     * The match timeline: where the seconds between "queue" and "playing" go. The three phases this
     * service owns are recorded here, the rest by the game server, all with [TIMELINE_BUCKETS] so a
     * dashboard can stack and compare them per mode. The names are a contract with that dashboard;
     * Prometheus renders them as `grounds_match_time_to_*_seconds`.
     *
     * Time to match is per player — ticket enqueued to the claim that formed the match. It is the
     * same measurement as [queueWait], which keeps its name for the dashboards already reading it.
     */
    fun timeToMatch(mode: String, waited: Duration) {
        timeline("grounds.match.time.to.match", waited, "mode", mode)
    }

    /** Match formed to a GameServer allocated for it (`result=ok`) or none to be had (`failed`). */
    fun timeToAllocate(mode: String, result: String, took: Duration) {
        timeline("grounds.match.time.to.allocate", took, "mode", mode, "result", result)
    }

    /** The StartMatch request: sent to the game server until it accepted (`ok`) or did not. */
    fun timeToPush(mode: String, result: String, took: Duration) {
        timeline("grounds.match.time.to.push", took, "mode", mode, "result", result)
    }

    private fun timeline(name: String, took: Duration, vararg tags: String) {
        Timer.builder(name)
            .tags(*tags)
            .serviceLevelObjectives(*TIMELINE_BUCKETS)
            .register(registry)
            .record(took)
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

    companion object {
        /**
         * The one bucket layout every timeline histogram shares, in seconds — here and on the game
         * server alike, so phases can be compared bucket for bucket. Explicit rather than a
         * percentile histogram: that would be ~70 buckets per series for no better answer.
         */
        val TIMELINE_BUCKETS: Array<Duration> =
            doubleArrayOf(
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
                .map { Duration.ofMillis((it * 1000).toLong()) }
                .toTypedArray()
    }
}

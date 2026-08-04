package gg.grounds.metrics

import io.agroal.api.AgroalDataSource
import io.micrometer.core.instrument.Gauge
import io.micrometer.core.instrument.MeterRegistry
import io.quarkus.runtime.StartupEvent
import jakarta.enterprise.context.ApplicationScoped
import jakarta.enterprise.event.Observes
import javax.sql.DataSource

/**
 * Publishes the Agroal connection-pool gauges on `/q/metrics`.
 *
 * Quarkus has its own Agroal-to-metrics bridge, but it feeds the `MetricsFactory` SPI and produced
 * no series here with Micrometer active — verified by building and running the service against a
 * real Postgres with both spellings of the config key. So the gauges are bound directly.
 *
 * `quarkus.datasource.jdbc.metrics.enabled=true` is still required: without it Agroal hands out a
 * no-op metrics object and every reading below is zero.
 *
 * `agroal_awaiting_count` is the one worth alerting on — threads queued for a connection. Anything
 * above zero means the pool, not the database, is the bottleneck.
 */
@ApplicationScoped
class DataSourcePoolMetrics {

    fun onStart(@Observes event: StartupEvent, registry: MeterRegistry, dataSource: DataSource) {
        val metrics = (dataSource as? AgroalDataSource)?.metrics ?: return

        gauge(registry, "agroal.active.count", "Connections currently handed out") {
            metrics.activeCount().toDouble()
        }
        gauge(registry, "agroal.available.count", "Idle connections ready to be handed out") {
            metrics.availableCount().toDouble()
        }
        gauge(registry, "agroal.max.used.count", "High-water mark of connections in use") {
            metrics.maxUsedCount().toDouble()
        }
        gauge(registry, "agroal.awaiting.count", "Threads blocked waiting for a connection") {
            metrics.awaitingCount().toDouble()
        }
        gauge(registry, "agroal.acquire.count", "Connections acquired since start") {
            metrics.acquireCount().toDouble()
        }
        gauge(registry, "agroal.creation.count", "Physical connections opened since start") {
            metrics.creationCount().toDouble()
        }
        gauge(
            registry,
            "agroal.blocking.time.average.seconds",
            "Mean time a caller waited for a connection",
        ) {
            metrics.blockingTimeAverage().toNanos() / 1_000_000_000.0
        }
        gauge(
            registry,
            "agroal.blocking.time.max.seconds",
            "Longest a caller has waited for a connection",
        ) {
            metrics.blockingTimeMax().toNanos() / 1_000_000_000.0
        }
    }

    private fun gauge(
        registry: MeterRegistry,
        name: String,
        description: String,
        value: () -> Double,
    ) {
        Gauge.builder(name, value)
            .description(description)
            // Micrometer keeps a weak reference by default and the lambda is the
            // only thing holding these alive — without this they are collected
            // and the series silently stop.
            .strongReference(true)
            .register(registry)
    }
}

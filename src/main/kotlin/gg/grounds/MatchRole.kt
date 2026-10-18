package gg.grounds

import io.quarkus.scheduler.Scheduled
import io.quarkus.scheduler.ScheduledExecution
import jakarta.inject.Singleton
import org.eclipse.microprofile.config.inject.ConfigProperty

/**
 * Which half of the service this process is.
 *
 * `all` (the default) is the regional runtime as it has always been: queue, matcher, allocator and
 * the HTTP API, next to the region's Valkey and Agones. `writer` is the same image deployed in
 * core, next to the database and the NATS hub, whose only job is to apply results from the
 * MATCH_RESULTS stream. A writer has no Valkey and no Agones, so everything that needs them stays
 * switched off: the mode registry load at startup and the matcher, allocator and watchdog ticks.
 */
@Singleton
class MatchRole(@param:ConfigProperty(name = "grounds.match.role") role: String) {
    val writer: Boolean =
        when (role.trim().lowercase()) {
            "all" -> false
            "writer" -> true
            else ->
                throw IllegalArgumentException(
                    "grounds.match.role must be all or writer, got '$role'"
                )
        }

    /** Skips a `@Scheduled` regional tick when this process is the core writer. */
    @Singleton
    class RegionalOnly(private val role: MatchRole) : Scheduled.SkipPredicate {
        override fun test(execution: ScheduledExecution): Boolean = role.writer
    }
}

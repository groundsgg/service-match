package gg.grounds.bench

import gg.grounds.domain.Ticket
import gg.grounds.persistence.ValkeyQueue
import gg.grounds.persistence.ValkeyQueueIT
import io.quarkus.redis.datasource.RedisDataSource
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.junit.QuarkusTest
import jakarta.inject.Inject
import java.time.Instant
import java.util.UUID
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test

/**
 * How many people fit in the queue at once.
 *
 * Distinct from how many the matcher can *serve* per second: a ticket that is waiting costs memory
 * whether or not this tick looks at it, and the queue is one Valkey pod with a memory limit set in
 * `matchmaking-queue.ts`. That limit was chosen before anyone measured what a ticket weighs, which
 * is the wrong order — so this measures it.
 *
 * A ticket is a small hash plus its two index entries. What makes the total worth checking rather
 * than assuming is that Valkey's per-key overhead dominates a payload this size, so a
 * back-of-envelope sum of the field lengths is wrong by several times.
 */
@QuarkusTest
@Tag("benchmark")
@QuarkusTestResource(ValkeyQueueIT.ValkeyResource::class, restrictToAnnotatedClass = true)
@QuarkusTestResource(ValkeyQueueIT.PostgresResource::class, restrictToAnnotatedClass = true)
class QueueFootprintBenchmark {

    @Inject lateinit var queue: ValkeyQueue

    @Inject lateinit var redis: RedisDataSource

    @Test
    fun `what a waiting ticket costs, and how many fit`() {
        redis.execute("FLUSHALL")
        queue.loadScripts()

        val before = usedMemoryBytes()
        repeat(TICKETS) { n ->
            queue.enqueue(
                Ticket(
                    id = UUID.randomUUID().toString(),
                    playerId = UUID.randomUUID().toString(),
                    modeId = "mode-${n % 5}",
                    mu = 25.0,
                    sigma = 8.33,
                    enqueuedAt = Instant.now(),
                    location = if (n % 2 == 0) "nl-ams1" else "de-fra1",
                ),
                TTL,
            )
        }
        val after = usedMemoryBytes()

        val perTicket = (after - before).toDouble() / TICKETS
        println()
        println("Footprint of a waiting ticket")
        println()
        println("  measured over $TICKETS tickets: %.0f bytes each".format(perTicket))
        println("  (hash, plus one entry in the rating index and one in the wait index)")
        println()
        for (limitMib in listOf(256, 512, 1024)) {
            // Valkey itself needs room to work; a queue that fills its limit
            // starts evicting or refusing writes, so the usable figure is not
            // the whole limit.
            val usable = limitMib * 1024.0 * 1024.0 * 0.8
            println(
                "  %5d MiB limit → about %,d tickets waiting at once"
                    .format(limitMib, (usable / perTicket).toLong())
            )
        }
        println()
    }

    private fun usedMemoryBytes(): Long =
        redis
            .execute("INFO", "memory")
            .toString()
            .lineSequence()
            .first { it.startsWith("used_memory:") }
            .substringAfter(':')
            .trim()
            .toLong()

    private companion object {
        const val TTL = 3600L
        const val TICKETS = 20_000
    }
}

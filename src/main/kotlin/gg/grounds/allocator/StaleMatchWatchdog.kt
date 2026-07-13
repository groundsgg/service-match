package gg.grounds.allocator

import gg.grounds.persistence.ValkeyQueue
import io.quarkus.redis.datasource.RedisDataSource
import io.quarkus.scheduler.Scheduled
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import java.time.Instant
import org.eclipse.microprofile.config.inject.ConfigProperty
import org.jboss.logging.Logger

/**
 * Rescues players from matches that never started.
 *
 * This replaces the GameServer reaper, which the multi-match runtime made not just unnecessary but
 * *dangerous*: one Minestom server carries many matches, so deleting "the orphan's server" would
 * have thrown every other game on it out mid-fight.
 *
 * What can still go wrong is narrower. A match is claimed, a slot is allocated, and then the push
 * to the server is lost — or the server dies before it builds the arena. The players sit in a match
 * that will never begin, and nothing in the happy path will ever notice, because from Valkey's
 * point of view the match looks fine.
 *
 * So: a match that has been sitting in FORMED or ASSIGNED for longer than a game could plausibly
 * take to start is failed, and its players go back on the queue with the waiting time they had
 * earned.
 *
 * The leaked counter slot on the server is *not* our problem to fix. The gamemode owns its counter
 * and sets it to the number of instances it is actually running, so a slot we paid for and never
 * used comes back on the next sync. Trying to decrement it from here would race the server and get
 * it wrong.
 */
@ApplicationScoped
class StaleMatchWatchdog
@Inject
constructor(
    private val redis: RedisDataSource,
    private val queue: ValkeyQueue,
    @param:ConfigProperty(name = "grounds.match.watchdog.stale-after-ms")
    private val staleAfterMs: Long,
) {

    @Scheduled(
        every = "{grounds.match.watchdog.every}",
        concurrentExecution = Scheduled.ConcurrentExecution.SKIP,
    )
    fun sweep() {
        try {
            val now = Instant.now().toEpochMilli()
            var rescued = 0

            for (matchId in liveMatchIds()) {
                val key = ValkeyQueue.matchKey(matchId)
                val state = redis.execute("HGET", key, "state")?.toString()

                // Gone, or already handed to a server. Once a match is ASSIGNED
                // the server owns it — and an ASSIGNED match that is *old* is
                // simply a long game. Requeueing on age here would drag players
                // out of a fight they are in the middle of.
                if (state == null || state != "FORMED") {
                    redis.execute("SREM", ValkeyQueue.LIVE_MATCHES, matchId)
                    continue
                }

                val createdAt =
                    redis.execute("HGET", key, "createdAt")?.toString()?.toLongOrNull() ?: continue
                if (now - createdAt < staleAfterMs) continue

                val modeId = redis.execute("HGET", key, "modeId")?.toString() ?: continue

                // The players have been waiting on a match that never began. Put
                // them back rather than leave them staring at a lobby.
                val requeued = queue.failRequeue(matchId, modeId)
                log.warn(
                    "Match never started, requeued $requeued player(s) " +
                        "(id=$matchId, state=$state, age=${(now - createdAt) / 1000}s)"
                )
                redis.execute("SREM", ValkeyQueue.LIVE_MATCHES, matchId)
                rescued++
            }

            if (rescued > 0) {
                log.warn("Rescued players from $rescued stalled match(es)")
            }
        } catch (e: Exception) {
            // A watchdog that dies on a transient error stops watching, which is
            // worse than the thing it watches for.
            log.error("Watchdog sweep failed", e)
        }
    }

    private fun liveMatchIds(): List<String> {
        val reply = redis.execute("SMEMBERS", ValkeyQueue.LIVE_MATCHES) ?: return emptyList()
        return (0 until reply.size()).mapNotNull { reply[it]?.toString() }
    }

    companion object {
        private val log: Logger = Logger.getLogger(StaleMatchWatchdog::class.java)
    }
}

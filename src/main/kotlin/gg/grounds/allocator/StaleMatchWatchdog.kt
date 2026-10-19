package gg.grounds.allocator

import gg.grounds.MatchRole
import gg.grounds.domain.MatchState
import gg.grounds.metrics.MatchMetrics
import gg.grounds.persistence.Matchmaker
import gg.grounds.persistence.ValkeyQueue
import io.quarkus.redis.client.RedisClientName
import io.quarkus.redis.datasource.RedisDataSource
import io.quarkus.scheduler.Scheduled
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import java.time.Duration
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
 * A match whose server is preparing the round (PREPARING) has its own, much shorter clock: the
 * server said it would report the round ready, and one that has not within the prepare timeout is
 * not going to. Its players go back on the queue, and the server is told to drop the round.
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
    @param:RedisClientName(Matchmaker.CLIENT) private val redis: RedisDataSource,
    @param:Matchmaker private val queue: ValkeyQueue,
    private val matchHost: MatchHostClient,
    private val metrics: MatchMetrics,
    @param:ConfigProperty(name = "grounds.match.watchdog.stale-after-ms")
    private val staleAfterMs: Long,
    @param:ConfigProperty(name = "grounds.match.prepare.timeout-ms")
    private val prepareTimeoutMs: Long,
) {

    @Scheduled(
        every = "{grounds.match.watchdog.every}",
        concurrentExecution = Scheduled.ConcurrentExecution.SKIP,
        skipExecutionIf = MatchRole.RegionalOnly::class,
    )
    fun sweep() {
        try {
            val now = Instant.now().toEpochMilli()
            var rescued = 0

            for (matchId in liveMatchIds()) {
                val key = ValkeyQueue.matchKey(matchId)
                val state = redis.execute("HGET", key, "state")?.toString()

                // Its server is preparing the round. That clock is sweepPreparing's, and the
                // match stays on this worklist until it is assigned or failed.
                if (state == MatchState.PREPARING.name) continue

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

    /**
     * Fail every PREPARING match whose server has not reported the round ready within the prepare
     * timeout. Runs every few seconds rather than with [sweep], so the timeout holds to within a
     * sweep interval — a player kept waiting thirty seconds past it would rather have been
     * requeued.
     */
    @Scheduled(
        every = "{grounds.match.prepare.sweep-every}",
        concurrentExecution = Scheduled.ConcurrentExecution.SKIP,
        skipExecutionIf = MatchRole.RegionalOnly::class,
    )
    fun sweepPreparing() {
        try {
            metrics.ensurePreparingGauge { queue.preparingCount() }
            val now = Instant.now()
            for (matchId in queue.preparingSince(now.minusMillis(prepareTimeoutMs))) {
                val status = queue.matchStatus(matchId)
                val modeId = status.modeId
                if (status.state != MatchState.PREPARING || modeId == null) {
                    // Assigned, failed or expired by another path that did not clear the entry.
                    queue.forgetPreparing(matchId)
                    continue
                }
                val requeued = queue.failRequeue(matchId, modeId, MatchState.PREPARING)
                // Another replica's sweep, a ready or a leaver got there first.
                if (requeued < 0) continue

                status.server?.let { matchHost.cancelMatch(it.gameServerName, matchId, "timeout") }
                val waited = status.preparingAt?.let { Duration.between(it, now) }
                waited?.let { metrics.timeToReady(modeId, "timeout", it) }
                log.warn(
                    "Server never reported the round ready, requeued $requeued player(s) " +
                        "(id=$matchId, mode=$modeId, gs=${status.server?.gameServerName}, " +
                        "waitedMs=${waited?.toMillis()})"
                )
                log.info(
                    "Match timeline (match=$matchId, mode=$modeId, stage=ready, " +
                        "result=timeout, gs=${status.server?.gameServerName}" +
                        (waited?.let { ", readyMs=${it.toMillis()})" } ?: ")")
                )
            }
        } catch (e: Exception) {
            log.error("Prepare sweep failed", e)
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

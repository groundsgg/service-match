package gg.grounds.allocator

import gg.grounds.persistence.ValkeyQueue
import io.quarkus.redis.datasource.RedisDataSource
import io.quarkus.scheduler.Scheduled
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import org.jboss.logging.Logger

/**
 * Deletes GameServers that belong to no live match.
 *
 * This is not hardening — it is load-bearing. `GameServerAllocation` is a non-idempotent create, so
 * a POST that succeeds server-side but never reaches us leaves a server nobody knows about. The
 * lease and the adopt-by-label GET make that rare; they cannot make it impossible, because a client
 * timeout cannot retract a server-side allocation.
 *
 * On a `replicas: 1` fleet — which a small gamemode will have — a single orphan occupies the entire
 * fleet and no further match can start. So the reaper is what keeps the queue from silently dying.
 *
 * It deletes a server when its `grounds/match-id` points at a match that is gone, has FAILED, or
 * was assigned to a *different* server. Anything else is left alone: an unrecognised server is
 * somebody else's business.
 */
@ApplicationScoped
class Reaper
@Inject
constructor(private val redis: RedisDataSource, private val agones: AgonesAllocator) {

    @Scheduled(
        every = "{grounds.match.reaper.every}",
        concurrentExecution = Scheduled.ConcurrentExecution.SKIP,
    )
    fun sweep() {
        try {
            val servers = agones.listMatchGameServers()
            if (servers.isEmpty()) return

            var reaped = 0
            for (gs in servers) {
                val verdict = judge(gs)
                if (verdict != null) {
                    log.warn("Reaping orphan (gs=${gs.name}, match=${gs.matchId}): $verdict")
                    agones.delete(gs.name)
                    reaped++
                }
            }
            if (reaped > 0) {
                log.warn("Reaped $reaped orphaned GameServer(s) out of ${servers.size}")
            }
        } catch (e: Exception) {
            log.error("Reaper sweep failed", e)
        }
    }

    /** @return why this server should die, or null to leave it be. */
    private fun judge(gs: AllocatedGameServer): String? {
        val matchKey = ValkeyQueue.matchKey(gs.matchId)
        val state = redis.execute("HGET", matchKey, "state")?.toString()

        if (state == null) {
            // The match is gone: it expired, or Valkey lost it. Either way the
            // server has nobody coming.
            return "no such match"
        }

        if (state == "FAILED") {
            return "match failed; players were requeued"
        }

        if (state == "ASSIGNED") {
            val assigned = redis.execute("HGET", matchKey, "gsName")?.toString()
            if (assigned != null && assigned != gs.name) {
                // Two servers, one match — the losing side of an assign race, or
                // a duplicate allocation. The players were told about `assigned`,
                // so this one is the orphan.
                return "match is assigned to $assigned, not this server"
            }
        }

        // FORMED and still within its attempts, or ASSIGNED to exactly this
        // server. Both are legitimate.
        return null
    }

    companion object {
        private val log: Logger = Logger.getLogger(Reaper::class.java)
    }
}

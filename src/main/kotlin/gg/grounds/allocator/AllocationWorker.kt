package gg.grounds.allocator

import gg.grounds.matcher.ModeRegistry
import gg.grounds.persistence.ValkeyQueue
import io.quarkus.redis.datasource.RedisDataSource
import io.quarkus.scheduler.Scheduled
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import org.eclipse.microprofile.config.inject.ConfigProperty
import org.jboss.logging.Logger

/**
 * Turns formed matches into running servers.
 *
 * It consumes the `mm:alloc` stream, which the claim script wrote *in the same atomic step that
 * created the match*. That is what makes recovery ordinary rather than special: a crash between
 * claim and allocation leaves an entry that was delivered but never acknowledged, and the consumer
 * group hands it to whoever asks next. Restart, takeover after a dead replica, and the happy path
 * are all the same code — there is no boot-time reconciler.
 *
 * XAUTOCLAIM is what makes that continuous. Pending entries idle for longer than the claim timeout
 * get picked up by another consumer, so a replica that dies mid-allocation does not strand its
 * matches until someone reboots.
 */
@ApplicationScoped
class AllocationWorker
@Inject
constructor(
    private val redis: RedisDataSource,
    private val queue: ValkeyQueue,
    private val agones: AgonesAllocator,
    private val matchHost: MatchHostClient,
    private val modes: ModeRegistry,
    @param:ConfigProperty(name = "grounds.match.alloc.lease-ms") private val leaseMs: Long,
    @param:ConfigProperty(name = "grounds.match.alloc.max-attempts") private val maxAttempts: Int,
    @param:ConfigProperty(name = "grounds.match.alloc.idle-reclaim-ms")
    private val idleReclaimMs: Long,
) {
    private val consumerName: String = "allocator-${java.util.UUID.randomUUID()}"

    fun ensureGroup() {
        try {
            redis.execute("XGROUP", "CREATE", ValkeyQueue.ALLOC_STREAM, GROUP, "0", "MKSTREAM")
            log.info("Created consumer group $GROUP on ${ValkeyQueue.ALLOC_STREAM}")
        } catch (e: Exception) {
            // BUSYGROUP: someone else got there first. That is the normal case
            // on every replica but the first.
            if (e.message?.contains("BUSYGROUP") != true) throw e
        }
    }

    @Scheduled(
        every = "{grounds.match.alloc.poll}",
        concurrentExecution = Scheduled.ConcurrentExecution.SKIP,
    )
    fun pump() {
        try {
            ensureGroup()
            reclaimStale()
            drainNew()
        } catch (e: Exception) {
            log.error("Allocation pump failed", e)
        }
    }

    /**
     * Pick up work a dead (or wedged) consumer never finished. This is the takeover path, and it
     * runs on every poll rather than only at boot — a replica that dies at 3am should not wait for
     * a deploy.
     */
    private fun reclaimStale() {
        val reply =
            redis.execute(
                "XAUTOCLAIM",
                ValkeyQueue.ALLOC_STREAM,
                GROUP,
                consumerName,
                idleReclaimMs.toString(),
                "0",
                "COUNT",
                BATCH.toString(),
            )
        // [cursor, entries, deleted]
        val entries = reply?.get(1) ?: return
        for (i in 0 until entries.size()) {
            handleEntry(entries[i])
        }
    }

    private fun drainNew() {
        val reply =
            redis.execute(
                "XREADGROUP",
                "GROUP",
                GROUP,
                consumerName,
                "COUNT",
                BATCH.toString(),
                "STREAMS",
                ValkeyQueue.ALLOC_STREAM,
                ">",
            ) ?: return

        for (s in 0 until reply.size()) {
            val entries = reply[s]?.get(1) ?: continue
            for (i in 0 until entries.size()) {
                handleEntry(entries[i])
            }
        }
    }

    private fun handleEntry(entry: io.vertx.mutiny.redis.client.Response?) {
        val entryId = entry?.get(0)?.toString() ?: return
        val fields = entry.get(1) ?: return

        var matchId: String? = null
        var modeId: String? = null
        var i = 0
        while (i + 1 < fields.size()) {
            when (fields[i].toString()) {
                "matchId" -> matchId = fields[i + 1].toString()
                "modeId" -> modeId = fields[i + 1].toString()
            }
            i += 2
        }
        if (matchId == null || modeId == null) {
            ack(entryId)
            return
        }

        try {
            allocateOne(matchId, modeId, entryId)
        } catch (e: Exception) {
            log.error("Allocation failed (match=$matchId)", e)
            // Leave it unacknowledged: XAUTOCLAIM will bring it back. If it keeps
            // failing, giveUp() eventually returns the players to the queue.
        }
    }

    private fun allocateOne(matchId: String, modeId: String, entryId: String) {
        val mode = modes.find(modeId)
        if (mode == null) {
            log.warn("Match references an unknown mode, dropping (match=$matchId, mode=$modeId)")
            queue.failRequeue(matchId, modeId)
            ack(entryId)
            return
        }

        // Fence concurrent attempts. This does not make allocation idempotent —
        // nothing can — it just stops two workers POSTing at the same moment.
        val leaseKey = "mm:alloc:lease:$matchId"
        val gotLease = redis.execute("SET", leaseKey, consumerName, "NX", "PX", leaseMs.toString())
        if (gotLease == null) {
            log.debug("Another worker holds the lease (match=$matchId)")
            return // no ack: whoever holds it will finish, or it expires and we retry
        }

        val attempts =
            redis.execute("HINCRBY", ValkeyQueue.matchKey(matchId), "attempts", "1").toInteger()
        if (attempts > maxAttempts) {
            giveUp(matchId, modeId, entryId, "exceeded $maxAttempts allocation attempts")
            return
        }

        val fleet = fleetNameFor(mode.modeId)
        val server = agones.allocate(matchId, modeId, fleet, mode.playersPerMatch)

        if (server == null) {
            // No Ready server in the fleet. Put the players back rather than let
            // them sit in a match that cannot start; they keep the wait they
            // earned, so they are first in line next tick.
            log.warn("No server available, requeueing players (match=$matchId, fleet=$fleet)")
            queue.failRequeue(matchId, modeId)
            ack(entryId)
            return
        }

        // Tell the server the match is coming, and do it BEFORE the assign.
        //
        // The order is the whole safety property. `assign` is what declares the
        // server owns this match: it releases the player guards and takes the
        // match off the watchdog's worklist. Push after that and a lost push is
        // lost forever — the match reads as healthy, the watchdog has stopped
        // looking, and the players wait for a game that nobody is building.
        //
        // Pushed first, the match stays FORMED and on the worklist until a server
        // has actually accepted it. Every way this can fail — refusal, timeout,
        // unreachable pod — lands the players back on the queue with the wait they
        // had already earned.
        val teams = queue.matchTeams(matchId)
        if (!matchHost.startMatch(server, matchId, modeId, teams)) {
            log.warn(
                "Server would not take the match, requeueing players " +
                    "(id=$matchId, gs=${server.gameServerName})"
            )
            // The slot we claimed on that server leaks. The gamemode's counter
            // sync reclaims it — far cheaper than players stuck in a dead match.
            queue.failRequeue(matchId, modeId)
            ack(entryId)
            return
        }

        val won = queue.assign(matchId, server)
        if (!won) {
            // Someone else assigned this match while we were allocating. In the
            // old one-match-per-server model we would delete our server here; we
            // must NOT do that now, because it is carrying other people's live
            // matches. We simply leaked a counter slot, and the gamemode's own
            // counter sync will reclaim it.
            log.warn(
                "Lost the assign race; leaked a slot on ${server.gameServerName} " +
                    "(match=$matchId) — the server's counter sync will reclaim it"
            )
        } else {
            log.info(
                "Assigned match (id=$matchId, gs=${server.gameServerName}, addr=${server.address}:${server.port})"
            )
        }
        ack(entryId)
    }

    private fun giveUp(matchId: String, modeId: String, entryId: String, reason: String) {
        log.error("Giving up on match (id=$matchId): $reason")
        queue.failRequeue(matchId, modeId)
        ack(entryId)
    }

    private fun ack(entryId: String) {
        redis.execute("XACK", ValkeyQueue.ALLOC_STREAM, GROUP, entryId)
    }

    /**
     * v1: one fleet per mode, named after it. When forge starts pushing the `matchmaking:` block
     * this comes from the mode config instead.
     */
    private fun fleetNameFor(modeId: String): String = modeId

    companion object {
        private val log: Logger = Logger.getLogger(AllocationWorker::class.java)
        private const val GROUP = "allocators"
        private const val BATCH = 10
    }
}

package gg.grounds.allocator

import gg.grounds.MatchRole
import gg.grounds.domain.MatchTimeline
import gg.grounds.domain.ServerAssignment
import gg.grounds.matcher.ModeRegistry
import gg.grounds.metrics.MatchMetrics
import gg.grounds.persistence.Matchmaker
import gg.grounds.persistence.ValkeyQueue
import gg.grounds.tracing.MatchTracing
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
    @param:RedisClientName(Matchmaker.CLIENT) private val redis: RedisDataSource,
    @param:Matchmaker private val queue: ValkeyQueue,
    private val agones: AgonesAllocator,
    private val matchHost: MatchHostClient,
    private val modes: ModeRegistry,
    private val metrics: MatchMetrics,
    private val tracing: MatchTracing,
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
        skipExecutionIf = MatchRole.RegionalOnly::class,
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

        // RESP3 — which the Quarkus client speaks — answers XREADGROUP with a MAP
        // keyed by stream name, not the RESP2 array of [name, entries] pairs.
        // Indexing a map throws ("Multi is a Map"), and it throws on every single
        // pump: the allocator never drained a new entry in its life, it only ever
        // picked matches up later via XAUTOCLAIM's idle-reclaim path. That is why
        // allocation looked merely *slow* rather than broken.
        val entries = reply.get(ValkeyQueue.ALLOC_STREAM) ?: return
        for (i in 0 until entries.size()) {
            handleEntry(entries[i])
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
            // Time the attempt and record which branch it took. The outcome
            // label is the point: "formed but never started" is only useful if
            // it also says why — no server, a refusal, an exhausted retry.
            metrics.timeAllocation(modeId) { allocateOne(matchId, modeId, entryId) }
        } catch (e: Exception) {
            log.error("Allocation failed (match=$matchId)", e)
            // Leave it unacknowledged: XAUTOCLAIM will bring it back. If it keeps
            // failing, giveUp() eventually returns the players to the queue.
        }
    }

    private fun allocateOne(matchId: String, modeId: String, entryId: String): String {
        val mode = modes.find(modeId)
        if (mode == null) {
            // The mode was removed after the match formed — its last server went away. Requeueing
            // would strand the players in a queue nobody matches; cancel so they can queue again.
            val cancelled = queue.failCancel(matchId)
            log.warn(
                "Match references an unknown mode, cancelled $cancelled ticket(s) " +
                    "(match=$matchId, mode=$modeId)"
            )
            ack(entryId)
            return "unknown_mode"
        }

        // Fence concurrent attempts. This does not make allocation idempotent —
        // nothing can — it just stops two workers POSTing at the same moment.
        val leaseKey = "mm:alloc:lease:$matchId"
        val gotLease = redis.execute("SET", leaseKey, consumerName, "NX", "PX", leaseMs.toString())
        if (gotLease == null) {
            log.debug("Another worker holds the lease (match=$matchId)")
            return "lease_held" // no ack: whoever holds it will finish, or it expires and we retry
        }

        // When the players queued and when the match formed, as the claim stored them: the
        // start of the allocate phase, and what the game server is told for its own timeline.
        val timeline = queue.matchTimeline(matchId)

        val attempts =
            redis.execute("HINCRBY", ValkeyQueue.matchKey(matchId), "attempts", "1").toInteger()
        if (attempts > maxAttempts) {
            recordAllocate(modeId, timeline, "failed")
            giveUp(matchId, modeId, entryId, "exceeded $maxAttempts allocation attempts")
            return "gave_up"
        }

        val fleet = mode.fleet
        val allocateSpan =
            tracing.child("allocate", timeline.traceparent, timeline.formedAt ?: Instant.now())
        val server =
            try {
                agones.allocate(matchId, modeId, fleet, mode.playersPerMatch)
            } finally {
                allocateSpan.end()
            }
        val allocatedAt = Instant.now()
        recordAllocate(modeId, timeline, if (server == null) "failed" else "ok")

        if (server == null) {
            // No Ready server in the fleet. Put the players back rather than let
            // them sit in a match that cannot start; they keep the wait they
            // earned, so they are first in line next tick.
            log.warn("No server available, requeueing players (match=$matchId, fleet=$fleet)")
            queue.failRequeue(matchId, modeId)
            ack(entryId)
            return "no_server"
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
        val pushSpan = tracing.child("push", timeline.traceparent, Instant.now())
        val pushStarted = System.nanoTime()
        val pushed =
            try {
                matchHost.startMatch(
                    server,
                    matchId,
                    modeId,
                    teams,
                    enqueuedAt = timeline.enqueuedAt,
                    formedAt = timeline.formedAt,
                    traceparent = tracing.traceparent(pushSpan),
                )
            } finally {
                pushSpan.end()
            }
        val pushTook = Duration.ofNanos(System.nanoTime() - pushStarted)
        metrics.timeToPush(modeId, if (pushed) "ok" else "failed", pushTook)
        log.info(
            "Match timeline (match=$matchId, mode=$modeId, stage=push, " +
                "result=${if (pushed) "ok" else "failed"}, gs=${server.gameServerName}, " +
                "pushMs=${pushTook.toMillis()})"
        )
        if (!pushed) {
            log.warn(
                "Server would not take the match, requeueing players " +
                    "(id=$matchId, gs=${server.gameServerName})"
            )
            // The slot we claimed on that server leaks. The gamemode's counter
            // sync reclaims it — far cheaper than players stuck in a dead match.
            queue.failRequeue(matchId, modeId)
            ack(entryId)
            return "server_refused"
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
            log.info(timelineLine(matchId, modeId, server, timeline, allocatedAt, pushTook))
        }
        ack(entryId)
        return if (won) "assigned" else "lost_race"
    }

    /**
     * Where this match's seconds went before its players were routed, one line per match, greppable
     * by `match=<id>`. A phase that cannot be told — a match formed before the timeline was stored
     * has no enqueue times — is left out rather than guessed.
     */
    private fun timelineLine(
        matchId: String,
        modeId: String,
        server: ServerAssignment,
        timeline: MatchTimeline,
        allocatedAt: Instant,
        pushTook: Duration,
    ): String {
        val formedAt = timeline.formedAt
        val oldest = timeline.enqueuedAt.values.minOrNull()
        val phases = buildList {
            if (formedAt != null && oldest != null) {
                add("matchMs=${Duration.between(oldest, formedAt).toMillis()}")
            }
            if (formedAt != null) {
                add("allocateMs=${Duration.between(formedAt, allocatedAt).toMillis()}")
            }
            add("pushMs=${pushTook.toMillis()}")
            if (oldest != null) add("totalMs=${Duration.between(oldest, Instant.now()).toMillis()}")
        }
        return "Match timeline (match=$matchId, mode=$modeId, stage=assigned, " +
            "gs=${server.gameServerName}, ${phases.joinToString(", ")})"
    }

    /** Formed to allocated (or not): the claim's createdAt is when the match formed. */
    private fun recordAllocate(modeId: String, timeline: MatchTimeline, result: String) {
        val formedAt = timeline.formedAt ?: return
        metrics.timeToAllocate(modeId, result, Duration.between(formedAt, Instant.now()))
    }

    private fun giveUp(matchId: String, modeId: String, entryId: String, reason: String) {
        log.error("Giving up on match (id=$matchId): $reason")
        queue.failRequeue(matchId, modeId)
        ack(entryId)
    }

    private fun ack(entryId: String) {
        redis.execute("XACK", ValkeyQueue.ALLOC_STREAM, GROUP, entryId)
    }

    companion object {
        private val log: Logger = Logger.getLogger(AllocationWorker::class.java)
        private const val GROUP = "allocators"
        private const val BATCH = 10
    }
}

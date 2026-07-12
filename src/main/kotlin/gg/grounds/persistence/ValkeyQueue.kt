package gg.grounds.persistence

import gg.grounds.domain.ServerAssignment
import gg.grounds.domain.Ticket
import gg.grounds.domain.TicketState
import io.quarkus.redis.datasource.RedisDataSource
import io.quarkus.redis.datasource.hash.HashCommands
import io.quarkus.redis.datasource.sortedset.SortedSetCommands
import jakarta.annotation.PostConstruct
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import java.time.Instant
import org.jboss.logging.Logger

/**
 * The queue spine.
 *
 * Every multi-key mutation runs as a Lua script, because Valkey executes those atomically. That is
 * the whole correctness story: two matchers racing on the same tickets cannot both win, so there is
 * no leader election, no distributed lock, and no in-memory state that a restart could lose.
 *
 * The scripts live in `src/main/resources/lua/` — read them before changing anything here. The
 * invariants they enforce (one live ticket per player, a ticket committed to at most one match, an
 * assignment that only the first assigner wins) are not re-checked in Kotlin, on purpose: a second
 * check here would be a race, not a safety net.
 */
@ApplicationScoped
class ValkeyQueue @Inject constructor(private val redis: RedisDataSource) {

    private lateinit var shas: Map<String, String>

    @PostConstruct
    fun loadScripts() {
        shas =
            SCRIPTS.associateWith { name ->
                val body = readScript(name)
                redis.execute("SCRIPT", "LOAD", body).toString()
            }
        log.info("Loaded ${shas.size} Lua scripts into Valkey")
    }

    private fun readScript(name: String): String =
        checkNotNull(javaClass.getResourceAsStream("/lua/$name.lua")) { "missing lua/$name.lua" }
            .bufferedReader()
            .readText()

    private fun eval(script: String, keys: List<String>, args: List<String>): Long {
        val argv = buildList {
            add(shas.getValue(script))
            add(keys.size.toString())
            addAll(keys)
            addAll(args)
        }
        return redis.execute("EVALSHA", *argv.toTypedArray()).toLong()
    }

    /** @return false if the player already holds a live ticket. */
    fun enqueue(ticket: Ticket, ttlSeconds: Long): Boolean =
        eval(
            "enqueue",
            keys =
                listOf(
                    guardKey(ticket.playerId),
                    ticketKey(ticket.id),
                    ratingKey(ticket.modeId),
                    waitKey(ticket.modeId),
                ),
            args =
                listOf(
                    ticket.id,
                    ticket.playerId,
                    ticket.modeId,
                    ticket.mu.toString(),
                    ticket.sigma.toString(),
                    ticket.enqueuedAt.toEpochMilli().toString(),
                    ttlSeconds.toString(),
                ),
        ) == 1L

    /**
     * Commit a proposal. Returns false when the proposal went stale — a ticket was cancelled or
     * another matcher took it. The caller should simply drop the proposal and re-read the queue;
     * that is not an error.
     */
    fun claim(
        matchId: String,
        modeId: String,
        ticketIds: List<String>,
        teamSize: Int,
        now: Instant,
        matchTtlSeconds: Long,
    ): Boolean =
        eval(
            "claim",
            keys = listOf(ratingKey(modeId), waitKey(modeId), matchKey(matchId), ALLOC_STREAM),
            args =
                buildList {
                    add(matchId)
                    add(modeId)
                    add(now.toEpochMilli().toString())
                    add(matchTtlSeconds.toString())
                    add(teamSize.toString())
                    addAll(ticketIds)
                },
        ) == 1L

    /** @return false if another assigner already won this match. */
    fun assign(matchId: String, assignment: ServerAssignment): Boolean =
        eval(
            "assign",
            keys = listOf(matchKey(matchId)),
            args = listOf(assignment.gameServerName, assignment.address, assignment.port.toString()),
        ) == 1L

    /** @return false if the ticket was already past QUEUED, or is not this player's. */
    fun cancel(ticketId: String, playerId: String, modeId: String): Boolean =
        eval(
            "cancel",
            keys =
                listOf(ticketKey(ticketId), ratingKey(modeId), waitKey(modeId), guardKey(playerId)),
            args = listOf(ticketId, playerId),
        ) == 1L

    /** Put a failed match's tickets back, keeping the waiting time they earned. */
    fun failRequeue(matchId: String, modeId: String): Long =
        eval(
            "fail_requeue",
            keys = listOf(matchKey(matchId), ratingKey(modeId), waitKey(modeId)),
            args = listOf(matchId),
        )

    fun findTicket(id: String): Ticket? {
        val h: Map<String, String> = hashes.hgetall(ticketKey(id))
        if (h.isEmpty()) return null
        val address = h["address"]
        val port = h["port"]?.toIntOrNull()
        val gsName = h["gsName"]
        return Ticket(
            id = h.getValue("ticketId"),
            playerId = h.getValue("playerId"),
            modeId = h.getValue("modeId"),
            mu = h.getValue("mu").toDouble(),
            sigma = h.getValue("sigma").toDouble(),
            enqueuedAt = Instant.ofEpochMilli(h.getValue("enqueuedAt").toLong()),
            state = TicketState.valueOf(h.getValue("state")),
            matchId = h["matchId"],
            assignment =
                if (gsName != null && address != null && port != null) {
                    ServerAssignment(gsName, address, port)
                } else {
                    null
                },
        )
    }

    /** Tickets waiting in a mode, oldest first. The oldest is the matcher's anchor. */
    fun queuedTicketIdsByWait(modeId: String, limit: Int): List<String> =
        sortedSets.zrange(waitKey(modeId), 0, (limit - 1).toLong())

    fun queueDepth(modeId: String): Long = sortedSets.zcard(ratingKey(modeId))

    private val hashes: HashCommands<String, String, String> by lazy {
        redis.hash(String::class.java, String::class.java, String::class.java)
    }

    private val sortedSets: SortedSetCommands<String, String> by lazy {
        redis.sortedSet(String::class.java, String::class.java)
    }

    companion object {
        private val log: Logger = Logger.getLogger(ValkeyQueue::class.java)
        private val SCRIPTS = listOf("enqueue", "claim", "assign", "cancel", "fail_requeue")

        const val ALLOC_STREAM = "mm:alloc"

        fun guardKey(playerId: String) = "mm:player:$playerId"

        fun ticketKey(ticketId: String) = "mm:ticket:$ticketId"

        fun matchKey(matchId: String) = "mm:match:$matchId"

        fun ratingKey(modeId: String) = "mm:q:$modeId:rating"

        fun waitKey(modeId: String) = "mm:q:$modeId:wait"
    }
}

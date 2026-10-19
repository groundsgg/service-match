package gg.grounds.persistence

import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import gg.grounds.domain.BandConfig
import gg.grounds.domain.MatchState
import gg.grounds.domain.MatchTimeline
import gg.grounds.domain.ModeConfig
import gg.grounds.domain.ModeLease
import gg.grounds.domain.ModeMeta
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
                    if (ticket.provisional) "1" else "0",
                    ticket.map.orEmpty(),
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
        traceparent: String? = null,
    ): Boolean =
        eval(
            "claim",
            keys =
                listOf(
                    ratingKey(modeId),
                    waitKey(modeId),
                    matchKey(matchId),
                    ALLOC_STREAM,
                    LIVE_MATCHES,
                ),
            args =
                buildList {
                    add(matchId)
                    add(modeId)
                    add(now.toEpochMilli().toString())
                    add(matchTtlSeconds.toString())
                    add(teamSize.toString())
                    add(traceparent.orEmpty())
                    addAll(ticketIds)
                },
        ) == 1L

    /**
     * Route the match's players to [assignment]. With [requiredState], only a match in that state
     * is assigned — the ready call passes PREPARING, so a match failed a moment earlier stays
     * failed.
     *
     * @return false if another assigner already won this match, or it is not in [requiredState].
     */
    fun assign(
        matchId: String,
        assignment: ServerAssignment,
        requiredState: MatchState? = null,
    ): Boolean =
        eval(
            "assign",
            keys = listOf(matchKey(matchId), LIVE_MATCHES, PREPARING_MATCHES),
            args =
                listOf(
                    assignment.gameServerName,
                    assignment.address,
                    assignment.port.toString(),
                    matchId,
                    requiredState?.name.orEmpty(),
                ),
        ) == 1L

    /**
     * A server accepted the match and will report when the round is ready: the match and its
     * tickets become PREPARING, and the server is recorded on the match only — nobody is routed
     * yet. See prepare.lua.
     *
     * @return false if the match is no longer FORMED.
     */
    fun prepare(matchId: String, assignment: ServerAssignment, now: Instant): Boolean =
        eval(
            "prepare",
            keys = listOf(matchKey(matchId), PREPARING_MATCHES),
            args =
                listOf(
                    assignment.gameServerName,
                    assignment.address,
                    assignment.port.toString(),
                    matchId,
                    now.toEpochMilli().toString(),
                ),
        ) == 1L

    /** @return false if the ticket was already past QUEUED, or is not this player's. */
    fun cancel(ticketId: String, playerId: String, modeId: String): Boolean =
        withdraw(ticketId, playerId, modeId) != Withdrawal.REFUSED

    /**
     * Withdraw a ticket that is QUEUED, or PREPARING in a match whose server is still preparing the
     * round. A PREPARING match left with fewer than [minPlayers] players fails, and the rest go
     * back on the queue — all in one script, so two players leaving at once cannot both miss it.
     */
    fun withdraw(
        ticketId: String,
        playerId: String,
        modeId: String,
        minPlayers: Int = 1,
    ): Withdrawal =
        when (
            eval(
                "cancel",
                keys =
                    listOf(
                        ticketKey(ticketId),
                        ratingKey(modeId),
                        waitKey(modeId),
                        guardKey(playerId),
                        LIVE_MATCHES,
                        PREPARING_MATCHES,
                    ),
                args = listOf(ticketId, playerId, minPlayers.toString()),
            )
        ) {
            1L -> Withdrawal.CANCELLED
            2L -> Withdrawal.CANCELLED_MATCH_FAILED
            else -> Withdrawal.REFUSED
        }

    /**
     * Fail a match whose mode is gone and cancel its tickets, releasing the players' guards. Unlike
     * [failRequeue], nothing goes back into a queue nobody matches any more.
     */
    fun failCancel(matchId: String): Long =
        eval(
            "fail_cancel",
            keys = listOf(matchKey(matchId), LIVE_MATCHES, PREPARING_MATCHES),
            args = listOf(matchId),
        )

    /**
     * Put a failed match's tickets back, keeping the waiting time they earned.
     *
     * With [requiredState], only a match in that state is failed, and -1 says it was not: of two
     * callers failing the same PREPARING match, only the first sees a count.
     */
    fun failRequeue(matchId: String, modeId: String, requiredState: MatchState? = null): Long =
        eval(
            "fail_requeue",
            keys =
                listOf(
                    matchKey(matchId),
                    ratingKey(modeId),
                    waitKey(modeId),
                    LIVE_MATCHES,
                    PREPARING_MATCHES,
                ),
            args = listOf(matchId, requiredState?.name.orEmpty()),
        )

    /** Where a match stands; every field null for a match that does not exist (any more). */
    fun matchStatus(matchId: String): MatchStatus {
        val h =
            hashes.hmget(
                matchKey(matchId),
                "state",
                "modeId",
                "gsName",
                "address",
                "port",
                "preparingAt",
            )
        val gsName = h["gsName"]
        val address = h["address"]
        val port = h["port"]?.toIntOrNull()
        return MatchStatus(
            state = h["state"]?.let { name -> MatchState.entries.firstOrNull { it.name == name } },
            modeId = h["modeId"],
            server =
                if (gsName != null && address != null && port != null) {
                    ServerAssignment(gsName, address, port)
                } else {
                    null
                },
            preparingAt = h["preparingAt"]?.toLongOrNull()?.let(Instant::ofEpochMilli),
        )
    }

    /** PREPARING matches whose server started preparing at or before [cutoff], oldest first. */
    fun preparingSince(cutoff: Instant): List<String> {
        val reply =
            redis.execute(
                "ZRANGEBYSCORE",
                PREPARING_MATCHES,
                "-inf",
                cutoff.toEpochMilli().toString(),
            ) ?: return emptyList()
        return (0 until reply.size()).mapNotNull { reply[it]?.toString() }
    }

    /** How many matches are PREPARING right now. */
    fun preparingCount(): Long = sortedSets.zcard(PREPARING_MATCHES)

    /** Take a match that is no longer PREPARING off the prepare-timeout worklist. */
    fun forgetPreparing(matchId: String) {
        sortedSets.zrem(PREPARING_MATCHES, matchId)
    }

    /**
     * The map a ticket of the match asked for, or null when none did. The matcher only groups
     * tickets that asked for the same map or for none, so the first one found is the match's.
     */
    fun requestedMap(matchId: String): String? {
        val tickets = hashes.hget(matchKey(matchId), "tickets") ?: return null
        return tickets
            .split(',')
            .filter { it.isNotEmpty() }
            .firstNotNullOfOrNull { id ->
                hashes.hget(ticketKey(id), "map")?.takeIf { it.isNotEmpty() }
            }
    }

    /**
     * The map an earlier allocation attempt chose for the match, "" when it chose none, or null
     * when nothing has been chosen yet. A retry keeps the first choice.
     */
    fun chosenMap(matchId: String): String? {
        val h = hashes.hmget(matchKey(matchId), "map", "mapStrategy")
        if (h["mapStrategy"] == null) return null
        return h["map"].orEmpty()
    }

    /** Record the match's map and the rule that chose it. */
    fun storeMap(matchId: String, address: String, strategy: String) {
        hashes.hset(matchKey(matchId), mapOf("map" to address, "mapStrategy" to strategy))
    }

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
            // Absent on tickets written before the flag existed; those pre-date the
            // degraded path entirely, so "not provisional" is the correct reading.
            provisional = h["provisional"] == "1",
            state = TicketState.valueOf(h.getValue("state")),
            matchId = h["matchId"],
            map = h["map"]?.takeIf { it.isNotEmpty() },
            assignment =
                if (gsName != null && address != null && port != null) {
                    ServerAssignment(gsName, address, port)
                } else {
                    null
                },
        )
    }

    /**
     * The assigned ticket of the match [playerId] is playing, or null. Written by the assign
     * script, so any proxy can offer a rejoin — not just the one that saw the assignment.
     */
    fun activeTicket(playerId: String): Ticket? {
        val ticketId = redis.execute("GET", activeKey(playerId))?.toString() ?: return null
        return findTicket(ticketId)?.takeIf {
            it.state == TicketState.ASSIGNED && it.assignment != null
        }
    }

    /** Forget the match [playerId] is playing — they left it for good. */
    fun clearActive(playerId: String) {
        redis.execute("DEL", activeKey(playerId))
    }

    /**
     * Expire [playerId]'s active match after [seconds]: the time a disconnected player has to come
     * back. @return false if there is no entry.
     */
    fun expireActive(playerId: String, seconds: Long): Boolean =
        redis.execute("EXPIRE", activeKey(playerId), seconds.toString()).toLong() == 1L

    /**
     * Give [playerId]'s active match back the lifetime of the match itself, once they are back in
     * it. @return false if there is no entry, or its match is already gone.
     */
    fun keepActive(playerId: String): Boolean {
        val matchId = activeTicket(playerId)?.matchId ?: return false
        val ttl = redis.execute("TTL", matchKey(matchId)).toLong()
        if (ttl <= 0) return false
        return expireActive(playerId, ttl)
    }

    /** A match ended: drop the entries of [playerIds] that still point at it. */
    fun endActive(matchId: String, playerIds: List<String>): Long =
        if (playerIds.isEmpty()) 0
        else eval("end_active", keys = playerIds.map(::activeKey), args = listOf(matchId))

    /**
     * The match's roster, in the order it was drafted: teams of player ids.
     *
     * The claim script stores teams as ticket ids (`a,b;c,d`) because tickets are what it has. The
     * server has no idea what a ticket is — it deals in players — so they are resolved here.
     *
     * Draft order is preserved deliberately. The matchmaker balanced these teams, and a server that
     * re-shuffled them would play a different match than the one that gets rated.
     */
    fun matchTeams(matchId: String): List<List<String>> {
        val encoded = hashes.hget(matchKey(matchId), "teams") ?: return emptyList()
        return encoded
            .split(';')
            .filter { it.isNotEmpty() }
            .map { team ->
                team.split(',').filter { it.isNotEmpty() }.mapNotNull { findTicket(it)?.playerId }
            }
    }

    /** What the claim stored about the match's timing and trace; see [MatchTimeline]. */
    fun matchTimeline(matchId: String): MatchTimeline {
        val h = hashes.hmget(matchKey(matchId), "createdAt", "enqueuedAt", "traceparent")
        return MatchTimeline(
            formedAt = h["createdAt"]?.toLongOrNull()?.let(Instant::ofEpochMilli),
            enqueuedAt =
                h["enqueuedAt"]
                    .orEmpty()
                    .split(',')
                    .mapNotNull { pair ->
                        val parts = pair.split('=', limit = 2)
                        val millis = parts.getOrNull(1)?.toLongOrNull()
                        if (millis == null) null else parts[0] to Instant.ofEpochMilli(millis)
                    }
                    .toMap(),
            traceparent = h["traceparent"]?.takeIf { it.isNotEmpty() },
        )
    }

    /** Tickets waiting in a mode, oldest first. The oldest is the matcher's anchor. */
    fun queuedTicketIdsByWait(modeId: String, limit: Int): List<String> =
        sortedSets.zrange(waitKey(modeId), 0, (limit - 1).toLong())

    fun queueDepth(modeId: String): Long = sortedSets.zcard(ratingKey(modeId))

    /**
     * Persist one mode's config so it survives a restart. A single hash, keyed by mode id — every
     * live mode in one place, so the startup load is one HGETALL rather than a SCAN over per-mode
     * keys.
     *
     * Not scripted: this is a single-key write, and Valkey already makes that atomic. The Lua
     * scripts above exist for invariants that span multiple keys, which this is not.
     */
    fun saveMode(config: ModeConfig) {
        hashes.hset(MODES_KEY, config.modeId, encodeMode(config))
    }

    /**
     * Register a mode with its lobby metadata and, when [lease] is set, renew that lease — all in
     * one script, so an expiry sweep on another replica can never see the row without the lease.
     *
     * @return true if the mode did not exist before.
     */
    fun upsertMode(config: ModeConfig, meta: ModeMeta, lease: ModeLease?, now: Instant): Boolean =
        eval(
            "upsert_mode",
            keys = listOf(MODES_KEY, metaKey(config.modeId), leasesKey(config.modeId)),
            args =
                listOf(
                    config.modeId,
                    configFields(config).joinToString("|"),
                    json.writeValueAsString(meta),
                    lease?.leaseId.orEmpty(),
                    lease?.let { now.plusSeconds(it.seconds.toLong()).toEpochMilli() }?.toString()
                        ?: "0",
                ),
        ) == 1L

    /**
     * Drop the mode's expired leases, and remove the mode if it is leased and none are left.
     *
     * @return how many waiting tickets were cancelled, or null if the mode is still alive.
     */
    fun expireMode(modeId: String, now: Instant): Long? = removeMode(modeId, now, "expire", "")

    /** Release one lease, removing the mode if it was the last. Same result as [expireMode]. */
    fun releaseLease(modeId: String, leaseId: String, now: Instant): Long? =
        removeMode(modeId, now, "release", leaseId)

    /** Remove a mode whether permanent or leased. Null if it did not exist. */
    fun deleteMode(modeId: String, now: Instant): Long? = removeMode(modeId, now, "delete", "")

    private fun removeMode(modeId: String, now: Instant, action: String, leaseId: String): Long? =
        eval(
                "expire_mode",
                keys =
                    listOf(
                        MODES_KEY,
                        metaKey(modeId),
                        leasesKey(modeId),
                        ratingKey(modeId),
                        waitKey(modeId),
                    ),
                args = listOf(modeId, now.toEpochMilli().toString(), action, leaseId),
            )
            .takeIf { it >= 0 }

    /** A mode's lobby metadata; the defaults for a mode registered before metadata existed. */
    fun loadMeta(modeId: String): ModeMeta =
        redis.execute("GET", metaKey(modeId))?.toString()?.let { json.readValue<ModeMeta>(it) }
            ?: ModeMeta()

    /** Leases of the mode that have not expired at [now]. */
    fun liveLeaseCount(modeId: String, now: Instant): Long =
        redis.execute("ZCOUNT", leasesKey(modeId), "(${now.toEpochMilli()}", "+inf").toLong()

    /**
     * Persist [config] only if no mode of that id exists — for the dev seed, which must never
     * clobber a real registration.
     */
    fun saveModeIfAbsent(config: ModeConfig): Boolean =
        hashes.hsetnx(MODES_KEY, config.modeId, encodeMode(config))

    /** Every persisted mode, for the registry to reload at startup. */
    fun loadModes(): List<ModeConfig> =
        hashes.hgetall(MODES_KEY).map { (modeId, encoded) -> decodeMode(modeId, encoded) }

    /** One persisted mode, or null — for a replica that missed the registration. */
    fun loadMode(modeId: String): ModeConfig? =
        hashes.hget(MODES_KEY, modeId)?.let { decodeMode(modeId, it) }

    private fun encodeMode(config: ModeConfig): String =
        (configFields(config) + (if (config.leased) "1" else "0")).joinToString("|")

    /** Fields 0..11 of the encoded row; [encodeMode] and upsert_mode.lua append `leased`. */
    private fun configFields(config: ModeConfig): List<Any> {
        val band = config.band
        return listOf(
            config.teamSize,
            config.teamCount,
            config.ranked,
            band.b0,
            band.k,
            band.w,
            band.stepSeconds,
            band.mercySeconds,
            band.mutualSeconds,
            // Appended, never inserted: the fields are positional and rows
            // written before this existed are still out there. See decodeMode.
            config.fleetName.orEmpty(),
            config.minPlayers,
            config.fillSeconds,
        )
    }

    private fun decodeMode(modeId: String, encoded: String): ModeConfig {
        val f = encoded.split("|")
        val teamSize = f[0].toInt()
        val teamCount = f[1].toInt()
        return ModeConfig(
            modeId = modeId,
            teamSize = teamSize,
            teamCount = teamCount,
            ranked = f[2].toBoolean(),
            band =
                BandConfig(
                    b0 = f[3].toDouble(),
                    k = f[4].toDouble(),
                    w = f[5].toDouble(),
                    stepSeconds = f[6].toInt(),
                    mercySeconds = f[7].toInt(),
                    mutualSeconds = f[8].toInt(),
                ),
            // Absent in rows written before modes could name a fleet, and blank
            // for the modes that do not. Both mean "the fleet is named after the
            // mode", which is what ModeConfig.fleet falls back to.
            fleetName = f.getOrNull(9)?.takeIf { it.isNotBlank() },
            // Absent in rows written before modes could start under-filled: those
            // always wait for a full match.
            minPlayers = f.getOrNull(10)?.toInt() ?: (teamSize * teamCount),
            fillSeconds = f.getOrNull(11)?.toInt() ?: 0,
            // Absent in rows written before leases existed: those modes are permanent.
            leased = f.getOrNull(12) == "1",
        )
    }

    private val hashes: HashCommands<String, String, String> by lazy {
        redis.hash(String::class.java, String::class.java, String::class.java)
    }

    private val sortedSets: SortedSetCommands<String, String> by lazy {
        redis.sortedSet(String::class.java, String::class.java)
    }

    // Tolerant of fields it does not know, so a newer replica's metadata does not break an older
    // one mid-rollout.
    private val json =
        jacksonObjectMapper().configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)

    companion object {
        private val log: Logger = Logger.getLogger(ValkeyQueue::class.java)
        private val SCRIPTS =
            listOf(
                "enqueue",
                "claim",
                "prepare",
                "assign",
                "cancel",
                "fail_requeue",
                "fail_cancel",
                "upsert_mode",
                "expire_mode",
                "end_active",
            )

        const val ALLOC_STREAM = "mm:alloc"

        /** Matches that exist but no server has taken yet — the watchdog's worklist. */
        const val LIVE_MATCHES = "mm:matches:live"

        /**
         * Matches whose server is preparing the round, scored by when it started: the prepare
         * timeout's worklist and the PREPARING gauge. Also on [LIVE_MATCHES] until assigned.
         */
        const val PREPARING_MATCHES = "mm:matches:preparing"

        /** Every mode this matchmaker knows about, so a restart does not silently drop a queue. */
        const val MODES_KEY = "mm:modes"

        fun guardKey(playerId: String) = "mm:player:$playerId"

        /** The ticket of the match a player is playing; see assign.lua. */
        fun activeKey(playerId: String) = "mm:active:$playerId"

        fun ticketKey(ticketId: String) = "mm:ticket:$ticketId"

        fun matchKey(matchId: String) = "mm:match:$matchId"

        fun ratingKey(modeId: String) = "mm:q:$modeId:rating"

        fun waitKey(modeId: String) = "mm:q:$modeId:wait"

        /** A mode's lobby metadata, as JSON. */
        fun metaKey(modeId: String) = "mm:mode:$modeId:meta"

        /** A mode's leases: member = lease id, score = expiry in epoch millis. */
        fun leasesKey(modeId: String) = "mm:mode:$modeId:leases"
    }
}

/** What withdrawing a ticket did; see [ValkeyQueue.withdraw]. */
enum class Withdrawal {
    /** Too late (its match is being allocated, or has its players), or not this player's ticket. */
    REFUSED,
    /** Withdrawn. A PREPARING match it left carries on without it. */
    CANCELLED,
    /** Withdrawn, and its PREPARING match fell below its minimum: the rest were requeued. */
    CANCELLED_MATCH_FAILED,
}

/** A match's state and, once one has it, its server; see [ValkeyQueue.matchStatus]. */
data class MatchStatus(
    val state: MatchState?,
    val modeId: String?,
    val server: ServerAssignment?,
    val preparingAt: Instant?,
)

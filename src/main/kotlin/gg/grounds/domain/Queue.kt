package gg.grounds.domain

import java.time.Instant

/**
 * A player waiting for a match.
 *
 * There is no project or region here on purpose. service-match runs inside the project's own
 * vCluster, so the vCluster *is* the tenancy boundary — a ticket cannot address another project
 * because it cannot reach another project's matchmaker.
 */
data class Ticket(
    val id: String,
    val playerId: String,
    val modeId: String,
    val mu: Double,
    val sigma: Double,
    val enqueuedAt: Instant,
    /**
     * True when mu/sigma are the seeded defaults because the rating store could not be read, not
     * because this player is genuinely unrated.
     *
     * The difference matters at rating time, not at matching time: treating a settled player as
     * mu=25 and then updating from that prior would move their rating on a fiction. A match holding
     * any provisional ticket is therefore recorded unranked.
     */
    val provisional: Boolean = false,
    val state: TicketState = TicketState.QUEUED,
    val matchId: String? = null,
    val assignment: ServerAssignment? = null,
    /**
     * The map the player asked for, `<namespace>/<name>`, or null for any map. Such a ticket only
     * matches tickets that asked for the same map or for none, and the match then plays it.
     */
    val map: String? = null,
)

enum class TicketState {
    QUEUED,
    MATCHED,
    /**
     * The match has a server, which is preparing the round; the player is not routed until it
     * reports ready. Still the player's one live ticket, and still withdrawable.
     */
    PREPARING,
    ASSIGNED,
    CANCELLED,
    FAILED,
}

/**
 * Where a match stands, as the `state` field of its `mm:match:{id}` hash. The Lua scripts compare
 * these names, so they are part of the storage format.
 *
 * FORMED → (MAP_SELECTION →) PREPARING → ASSIGNED, or FAILED from anywhere before ASSIGNED. A
 * server that does not signal readiness takes a match straight from FORMED to ASSIGNED, as before.
 */
enum class MatchState {
    /** Claimed; the allocation worker is finding it a server. */
    FORMED,
    /**
     * Reserved for a map vote (pick/ban): the match would wait here, with `mapCandidates` and
     * `mapDeadlineAt` on its hash, until the vote decides. Nothing enters it yet — see
     * [gg.grounds.maps.MapDecision.Deferred].
     */
    MAP_SELECTION,
    /** A server accepted it and is preparing the round; `gsName` and `preparingAt` are set. */
    PREPARING,
    /** The players are routed. The server owns the match from here. */
    ASSIGNED,
    /** Given up on; its players were requeued or cancelled. */
    FAILED,
}

data class ServerAssignment(val gameServerName: String, val address: String, val port: Int)

/**
 * When a match's players queued and when it formed, with its trace — what the claim stored so the
 * allocation worker can time its phases and hand them to the game server without reading a ticket.
 *
 * Matches formed before these were stored have only [formedAt]; the rest is empty, not an error.
 */
data class MatchTimeline(
    val formedAt: Instant? = null,
    /** Player id to that player's ticket enqueuedAt, in roster order. */
    val enqueuedAt: Map<String, Instant> = emptyMap(),
    val traceparent: String? = null,
)

/** What the matcher proposes; only the claim script decides whether it happens. */
data class MatchProposal(
    val matchId: String,
    val teams: List<List<String>>,
    val quality: Double,
    /** The map a ticket of this match asked for, or null when none did. */
    val map: String? = null,
) {
    val ticketIds: List<String>
        get() = teams.flatten()

    val shortId: String
        get() = shortMatchId(matchId)
}

/**
 * The first 8 hex characters of a match id. Games show it on the scoreboard and prism resolves it,
 * so the matcher keeps it unique among recorded matches.
 */
fun shortMatchId(matchId: String): String = matchId.take(8)

/** Per-mode matchmaking configuration, pushed by forge from `grounds.yaml`. */
data class ModeConfig(
    val modeId: String,
    val teamSize: Int,
    val teamCount: Int,
    val ranked: Boolean = false,
    val band: BandConfig = BandConfig(),
    /**
     * The Agones Fleet that serves this mode, when it is not the mode itself.
     *
     * Modes and fleets are not one-to-one. A gamemode image that decides its rules from the mode id
     * it is handed — duel builds a different arena and hands out a different kit per mode — serves
     * many modes from one pool of servers, and splitting that pool per mode would idle nine sets of
     * servers running the same image.
     *
     * Null means the fleet is named after the mode, which stays true for a gamemode that really
     * does have one fleet of its own.
     */
    val fleetName: String? = null,
    /**
     * The fewest players a match may start with, for a free-for-all that is still worth playing
     * under-filled. Equal to [playersPerMatch] — always a full match — unless the mode lowers it.
     */
    val minPlayers: Int = teamSize * teamCount,
    /**
     * How long the longest-waiting ticket waits for a full match before an under-filled one of at
     * least [minPlayers] forms. Ignored while [minPlayers] is [playersPerMatch].
     */
    val fillSeconds: Int = 0,
    /**
     * Whether the mode lives on leases rather than forever. A mode becomes leased the first time a
     * game server registers it with a lease id, and from then on exists only while at least one of
     * those leases is unexpired. False for a mode only ever registered without one — forge's
     * deploy-time push, and every row written before leases existed.
     */
    val leased: Boolean = false,
) {
    /** Players in a full match, and the most a match can hold. */
    val playersPerMatch: Int
        get() = teamSize * teamCount

    /** Whether a match may form with fewer than [playersPerMatch] players. */
    val allowsPartial: Boolean
        get() = minPlayers < playersPerMatch

    /** The fleet to allocate from. */
    val fleet: String
        get() = fleetName?.takeIf { it.isNotBlank() } ?: modeId
}

/**
 * What the lobby shows for a mode, separate from how it is matched. Last writer wins: every
 * registration replaces it whole.
 */
data class ModeMeta(
    /** Null means the mode id is shown. */
    val displayName: String? = null,
    /** A Minecraft item key, e.g. `minecraft:iron_sword`. */
    val icon: String? = null,
    /** The stat boards the game writes as `<modeId>.<stat>`. The first is the default. */
    val boards: List<StatBoard> = emptyList(),
    /** Whether a disconnected player may rejoin a running match. */
    val rejoinEnabled: Boolean = false,
    /** Where the mode's maps come from and how one is chosen; null for a mode without maps. */
    val maps: ModeMaps? = null,
)

/**
 * A mode's map pool: every map pinned for this environment under [namespace], e.g. `survivalgames`
 * → `survivalgames/aura`, `survivalgames/breeze`.
 */
data class ModeMaps(
    val namespace: String,
    val selection: MapSelectionStrategy = MapSelectionStrategy.RANDOM,
)

/** How a match's map is chosen when none of its players asked for one. */
enum class MapSelectionStrategy {
    /** Uniformly at random from the pool, preferring maps that ship a pre-lit world. */
    RANDOM,
    /**
     * Reserved: the players pick and ban from a few candidates before the match starts. Not
     * implemented; a lease asking for it is refused.
     */
    PICK_BAN,
}

data class StatBoard(val stat: String, val displayName: String)

/** One game server's claim that a mode is still being served, until [seconds] from now. */
data class ModeLease(val leaseId: String, val seconds: Int)

/** A live mode as listed to the lobby: its config, what to show, and how alive it is. */
data class ModeSummary(
    val config: ModeConfig,
    val meta: ModeMeta,
    val ticketsQueued: Long,
    val leases: Long,
)

/**
 * How wide a net a ticket casts, and how that widens as it waits.
 *
 * band(t) = max(b0, k * sigma) + w * floor(waitSeconds / s)
 *
 * A new player (sigma ≈ 8.3) starts wide because the model does not know them yet; a settled player
 * starts narrow. Everyone widens with time, so nobody waits forever for a perfect opponent.
 */
data class BandConfig(
    val b0: Double = 2.0,
    val k: Double = 1.0,
    val w: Double = 1.0,
    val stepSeconds: Int = 10,
    /** After this long, the anchor's band goes unbounded — the whole ladder. */
    val mercySeconds: Int = 90,
    /**
     * After this long, the anchor stops requiring the *candidate* to accept it back. Without this
     * an outlier starves forever against a population that keeps matching itself.
     */
    val mutualSeconds: Int = 150,
) {
    fun bandFor(sigma: Double, waitSeconds: Long): Double {
        val base = maxOf(b0, k * sigma)
        val widening = w * (waitSeconds / stepSeconds)
        return base + widening
    }
}

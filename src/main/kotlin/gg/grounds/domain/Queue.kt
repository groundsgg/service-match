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
    /**
     * The region the player is connected to.
     *
     * A QoS hint, never a queue dimension. The queue deliberately spans every region so two players
     * in different ones can meet at all; this only says where each of them would prefer to play,
     * and the matcher weighs them against each other when it picks the host region.
     */
    val location: String = "",
    val state: TicketState = TicketState.QUEUED,
    val matchId: String? = null,
    val assignment: ServerAssignment? = null,
)

enum class TicketState {
    QUEUED,
    MATCHED,
    ASSIGNED,
    CANCELLED,
    FAILED,
}

data class ServerAssignment(val gameServerName: String, val address: String, val port: Int)

/** What the matcher proposes; only the claim script decides whether it happens. */
data class MatchProposal(val matchId: String, val teams: List<List<String>>, val quality: Double) {
    val ticketIds: List<String>
        get() = teams.flatten()
}

/** Per-mode matchmaking configuration, pushed by forge from `grounds.yaml`. */
data class ModeConfig(
    val modeId: String,
    val teamSize: Int,
    val teamCount: Int,
    val ranked: Boolean = false,
    val band: BandConfig = BandConfig(),
) {
    /** Players needed to form a match. */
    val playersPerMatch: Int
        get() = teamSize * teamCount
}

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

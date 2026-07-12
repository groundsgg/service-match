package gg.grounds.domain

import java.util.UUID

/**
 * Domain port for the durable half of the matchmaker. The Postgres implementation lives in
 * `persistence`; tests can swap a fake.
 *
 * Only ratings live here. Tickets, queues and in-flight matches are Valkey's job (phase 2) — they
 * are losable, ratings are not.
 */
interface RatingRepository {

    /**
     * A player's rating for a mode, or null if they have never played it. The caller decides what a
     * missing rating means; the gRPC layer answers with the configured defaults so a new player
     * sees a sane mu/sigma instead of an error.
     */
    fun find(playerId: UUID, modeId: String): StoredRating?
}

data class StoredRating(val rating: Rating, val gamesPlayed: Int)

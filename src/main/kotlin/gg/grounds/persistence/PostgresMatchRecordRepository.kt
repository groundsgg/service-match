package gg.grounds.persistence

import gg.grounds.domain.MatchRecord
import gg.grounds.domain.MatchRecordRepository
import gg.grounds.domain.PlayerPlacement
import gg.grounds.domain.Rating
import gg.grounds.domain.RatingTransition
import gg.grounds.domain.ResultOutcome
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import jakarta.transaction.Transactional
import java.sql.Connection
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID
import javax.sql.DataSource
import org.jboss.logging.Logger

@ApplicationScoped
class PostgresMatchRecordRepository @Inject constructor(private val dataSource: DataSource) :
    MatchRecordRepository {

    override fun recordMatch(
        matchId: UUID,
        modeId: String,
        ranked: Boolean,
        playerIds: List<UUID>,
    ) {
        dataSource.connection.use { c ->
            c.prepareStatement(
                    "INSERT INTO match_record (match_id, mode_id, ranked, created_at, player_ids) " +
                        "VALUES (?, ?, ?, ?, ?) ON CONFLICT (match_id) DO NOTHING"
                )
                .use { ps ->
                    ps.setObject(1, matchId)
                    ps.setString(2, modeId)
                    ps.setBoolean(3, ranked)
                    ps.setTimestamp(4, Timestamp.from(Instant.now()))
                    ps.setArray(5, c.createArrayOf("uuid", playerIds.toTypedArray()))
                    ps.executeUpdate()
                }
        }
    }

    override fun findMatch(matchId: UUID): MatchRecord? =
        dataSource.connection.use { c ->
            c.prepareStatement(
                    "SELECT mode_id, ranked, ended_at, player_ids FROM match_record WHERE match_id = ?"
                )
                .use { ps ->
                    ps.setObject(1, matchId)
                    ps.executeQuery().use { rs ->
                        if (!rs.next()) return null
                        @Suppress("UNCHECKED_CAST")
                        val players =
                            (rs.getArray("player_ids")?.array as? Array<Any?>)?.mapNotNull {
                                it as? UUID
                            } ?: emptyList()
                        MatchRecord(
                            matchId = matchId,
                            modeId = rs.getString("mode_id"),
                            ranked = rs.getBoolean("ranked"),
                            ended = rs.getTimestamp("ended_at") != null,
                            playerIds = players,
                        )
                    }
                }
        }

    /**
     * One transaction, deliberately.
     *
     * `rating_update`'s primary key is (match_id, player_id), so a replayed result inserts nothing.
     * If the rating UPDATEs lived outside that transaction, a replay would insert nothing *and
     * still move the ratings* — the idempotency latch would be decorative. Rolling both together is
     * what makes a retry genuinely free.
     */
    @Transactional
    override fun applyResult(
        matchId: UUID,
        modeId: String,
        placements: List<PlayerPlacement>,
        ratedUpdates: Map<UUID, RatingTransition>,
        terminationReason: String,
    ): ResultOutcome {
        // JTA owns the transaction (@Transactional); touching autoCommit or
        // committing by hand here throws. It also means a thrown exception rolls
        // the whole thing back, which is the behaviour we want anyway.
        dataSource.connection.use { c ->
            // Closing the match IS the idempotency latch: the UPDATE only matches
            // while ended_at is null, so a replay touches no rows and we stop
            // before moving any rating. Doing this first, in the same
            // transaction as the rating writes, is what makes a retry free —
            // outside it, a replay would skip the insert and still bump the mu.
            val closed =
                c.prepareStatement(
                        "UPDATE match_record SET ended_at = ?, termination_reason = ? " +
                            "WHERE match_id = ? AND ended_at IS NULL"
                    )
                    .use { ps ->
                        ps.setTimestamp(1, Timestamp.from(Instant.now()))
                        ps.setString(2, terminationReason)
                        ps.setObject(3, matchId)
                        ps.executeUpdate()
                    }

            if (closed == 0) {
                log.info("Result already recorded, ignoring replay (match=$matchId)")
                return ResultOutcome(applied = false, rated = false)
            }

            for (p in placements) {
                val transition = ratedUpdates[p.playerId]
                insertRatingUpdate(c, matchId, p, transition)
                if (transition != null) {
                    upsertRating(c, p.playerId, modeId, transition.after)
                }
            }

            return ResultOutcome(applied = true, rated = ratedUpdates.isNotEmpty())
        }
    }

    private fun insertRatingUpdate(
        c: Connection,
        matchId: UUID,
        placement: PlayerPlacement,
        transition: RatingTransition?,
    ) {
        c.prepareStatement(
                "INSERT INTO rating_update " +
                    "(match_id, player_id, placement, mu_before, sigma_before, mu_after, sigma_after) " +
                    "VALUES (?, ?, ?, ?, ?, ?, ?) ON CONFLICT (match_id, player_id) DO NOTHING"
            )
            .use { ps ->
                ps.setObject(1, matchId)
                ps.setObject(2, placement.playerId)
                ps.setInt(3, placement.placement)
                if (transition != null) {
                    ps.setDouble(4, transition.before.mu)
                    ps.setDouble(5, transition.before.sigma)
                    ps.setDouble(6, transition.after.mu)
                    ps.setDouble(7, transition.after.sigma)
                } else {
                    // Unranked: we still record that the player was here and
                    // where they finished, we just do not move anything.
                    ps.setNull(4, java.sql.Types.DOUBLE)
                    ps.setNull(5, java.sql.Types.DOUBLE)
                    ps.setNull(6, java.sql.Types.DOUBLE)
                    ps.setNull(7, java.sql.Types.DOUBLE)
                }
                ps.executeUpdate()
            }
    }

    private fun upsertRating(c: Connection, playerId: UUID, modeId: String, rating: Rating) {
        c.prepareStatement(
                "INSERT INTO player_rating (player_id, mode_id, mu, sigma, games_played, updated_at) " +
                    "VALUES (?, ?, ?, ?, 1, ?) " +
                    "ON CONFLICT (player_id, mode_id) DO UPDATE SET " +
                    "mu = EXCLUDED.mu, sigma = EXCLUDED.sigma, " +
                    "games_played = player_rating.games_played + 1, updated_at = EXCLUDED.updated_at"
            )
            .use { ps ->
                ps.setObject(1, playerId)
                ps.setString(2, modeId)
                ps.setDouble(3, rating.mu)
                ps.setDouble(4, rating.sigma)
                ps.setTimestamp(5, Timestamp.from(Instant.now()))
                ps.executeUpdate()
            }
    }

    companion object {
        private val log: Logger = Logger.getLogger(PostgresMatchRecordRepository::class.java)
    }
}

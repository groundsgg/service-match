package gg.grounds.persistence

import gg.grounds.domain.Rating
import gg.grounds.domain.RatingRepository
import gg.grounds.domain.StoredRating
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import java.util.UUID
import javax.sql.DataSource

@ApplicationScoped
class PostgresRatingRepository @Inject constructor(private val dataSource: DataSource) :
    RatingRepository {

    override fun find(playerId: UUID, modeId: String): StoredRating? =
        dataSource.connection.use { c ->
            c.prepareStatement(
                    "SELECT mu, sigma, games_played FROM player_rating " +
                        "WHERE player_id = ? AND mode_id = ?"
                )
                .use { ps ->
                    ps.setObject(1, playerId)
                    ps.setString(2, modeId)
                    ps.executeQuery().use { rs ->
                        if (rs.next()) {
                            StoredRating(
                                rating = Rating(rs.getDouble(1), rs.getDouble(2)),
                                gamesPlayed = rs.getInt(3),
                            )
                        } else {
                            null
                        }
                    }
                }
        }
}

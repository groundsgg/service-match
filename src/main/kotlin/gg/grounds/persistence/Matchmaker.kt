package gg.grounds.persistence

import io.quarkus.redis.client.RedisClientName
import io.quarkus.redis.datasource.RedisDataSource
import jakarta.enterprise.context.ApplicationScoped
import jakarta.enterprise.inject.Produces
import jakarta.inject.Qualifier

/**
 * The matchmaker's own Valkey connections: the matcher tick, the allocation pump, the stale-match
 * watchdog and the mode registry they read.
 *
 * The HTTP API answers every proxy on the network. A burst of it — a few hundred leaves a second
 * when lobbies drop their players — once took every connection of the shared pool and failed the
 * matcher and the allocator along with the API. On a client of their own, the API running out of
 * connections only fails API calls; matches keep forming and getting servers.
 */
@Qualifier
@Retention(AnnotationRetention.RUNTIME)
@Target(AnnotationTarget.FIELD, AnnotationTarget.VALUE_PARAMETER, AnnotationTarget.FUNCTION)
annotation class Matchmaker {
    companion object {
        /** The named Redis client, configured under `quarkus.redis.matchmaker.*`. */
        const val CLIENT = "matchmaker"
    }
}

@ApplicationScoped
class MatchmakerValkey {

    /** The same queue spine as the API's [ValkeyQueue], over the matchmaker's own connections. */
    @Produces
    @ApplicationScoped
    @Matchmaker
    fun queue(@RedisClientName(Matchmaker.CLIENT) redis: RedisDataSource): ValkeyQueue =
        ValkeyQueue(redis).also { it.loadScripts() }
}

package gg.grounds.maps

import com.fasterxml.jackson.databind.ObjectMapper
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Clock
import java.time.Duration
import java.time.Instant
import org.eclipse.microprofile.config.inject.ConfigProperty
import org.jboss.logging.Logger

/**
 * The maps pinned for this environment, read from the CDN pin file
 * `<cdn-base>/pins/<environment>.json` — the same file every game server loads its maps by, so a
 * map chosen here is one the server can actually load.
 *
 * Fetched lazily on first use and cached; a fetch that fails keeps the last copy that did not, so a
 * CDN blip changes nothing. Nothing reads it for a mode without maps.
 */
@ApplicationScoped
class PinnedMapPool
@Inject
constructor(
    @param:ConfigProperty(name = "grounds.match.maps.cdn-base") private val cdnBase: String,
    @param:ConfigProperty(name = "grounds.match.maps.environment") private val environment: String,
    @param:ConfigProperty(name = "grounds.match.maps.cache-seconds") private val cacheSeconds: Long,
    @param:ConfigProperty(name = "grounds.match.maps.fetch-timeout-ms")
    private val fetchTimeoutMs: Long,
) {
    /** Replaced in tests to move time without sleeping. */
    internal var clock: Clock = Clock.systemUTC()

    private val http: HttpClient by lazy {
        HttpClient.newBuilder().connectTimeout(Duration.ofMillis(fetchTimeoutMs)).build()
    }

    private var cached: List<PinnedMap>? = null
    private var nextFetchAt: Instant = Instant.MIN

    /** Every map pinned under [namespace], i.e. whose address starts with `<namespace>/`. */
    fun pool(namespace: String): List<PinnedMap> {
        val prefix = namespace.trimEnd('/') + "/"
        return all().filter { it.address.startsWith(prefix) }
    }

    /** Every pinned map. Empty only if the pin file has never been read. */
    @Synchronized
    fun all(): List<PinnedMap> {
        val now = clock.instant()
        if (now.isBefore(nextFetchAt)) return cached.orEmpty()
        try {
            cached = fetch()
            nextFetchAt = now.plusSeconds(cacheSeconds)
        } catch (e: Exception) {
            // Retried sooner than a good copy expires, but not on every call: a CDN that is down
            // must not turn every allocation into a timeout.
            nextFetchAt = now.plusSeconds(minOf(cacheSeconds, FAILURE_RETRY_SECONDS))
            log.warn(
                "Could not read the map pin file, keeping ${cached?.size ?: 0} known map(s) " +
                    "(url=${url()}): ${e.message}"
            )
        }
        return cached.orEmpty()
    }

    private fun url(): String = "${cdnBase.trimEnd('/')}/pins/$environment.json"

    private fun fetch(): List<PinnedMap> {
        val request =
            HttpRequest.newBuilder(URI.create(url()))
                .timeout(Duration.ofMillis(fetchTimeoutMs))
                .GET()
                .build()
        val response = http.send(request, HttpResponse.BodyHandlers.ofString())
        check(response.statusCode() == 200) { "HTTP ${response.statusCode()}" }
        return parse(response.body())
    }

    companion object {
        private val log: Logger = Logger.getLogger(PinnedMapPool::class.java)
        private const val FAILURE_RETRY_SECONDS = 5L
        private val json = ObjectMapper()

        /**
         * `{"environment": ..., "maps": {"<address>": {"version": N, ..., "lit": {...}?}}}`. Read
         * as a tree, so fields this service does not know are simply not looked at.
         */
        internal fun parse(body: String): List<PinnedMap> {
            val maps = json.readTree(body).path("maps")
            require(maps.isObject) { "the pin file has no maps object" }
            return maps
                .properties()
                .map { (address, pin) -> PinnedMap(address, lit = pin.path("lit").isObject) }
                .sortedBy { it.address }
        }
    }
}

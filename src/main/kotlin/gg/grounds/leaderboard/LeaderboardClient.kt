package gg.grounds.leaderboard

import com.fasterxml.jackson.databind.ObjectMapper
import jakarta.annotation.PreDestroy
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import org.eclipse.microprofile.config.inject.ConfigProperty
import org.jboss.logging.Logger

/**
 * Writes rated results to service-leaderboard.
 *
 * service-match already computes the ratings, so this is the one place a gamemode's win never has
 * to know a leaderboard exists — see `ResultService.report`, which calls this after a rated result
 * has already been committed to Postgres.
 *
 * By the time this is called the result is the source of truth and is already durable. A
 * leaderboard that is down, slow, or rejects the call must not turn a real result into a failed one
 * — `submitScore` never throws, on purpose; every failure is logged and swallowed here rather than
 * left for the caller to remember to catch.
 */
@ApplicationScoped
class LeaderboardClient
@Inject
constructor(
    @param:ConfigProperty(
        name = "grounds.match.leaderboard.url",
        defaultValue = "service-leaderboard:9000",
    )
    target: String,
    @param:ConfigProperty(name = "grounds.match.leaderboard.deadline-ms", defaultValue = "5000")
    private val deadlineMs: Long,
) {

    // LEADERBOARD_SERVICE_URL arrives as a bare host:port (the chart injects it
    // with no scheme) — java.net.http throws parsing that directly, so default
    // to http. Plaintext is fine: this is a pod-to-pod call inside the project's
    // own vCluster, which is the tenancy boundary.
    private val baseUri: URI = URI.create(if (target.contains("://")) target else "http://$target")

    private val http: HttpClient = HttpClient.newHttpClient()
    private val json = ObjectMapper()

    /**
     * Submit one player's post-match score. `idempotencyKey` is the caller's job to make stable
     * (`<matchId>:<playerId>`) — a retried ReportMatchResult must not double-count on the board any
     * more than it double-counts the rating.
     *
     * Never throws — see the class doc. A caller that needs to know whether this actually landed
     * has the wrong idea about what this call is for.
     */
    fun submitScore(boardId: String, playerId: String, score: Long, idempotencyKey: String) {
        try {
            val body =
                json.writeValueAsString(
                    mapOf(
                        "playerId" to playerId,
                        "score" to score,
                        "mode" to "REPLACE",
                        "idempotencyKey" to idempotencyKey,
                    )
                )

            val request =
                HttpRequest.newBuilder(baseUri.resolve("/v1/leaderboards/$boardId/scores"))
                    .timeout(Duration.ofMillis(deadlineMs))
                    .header("Content-Type", "application/json")
                    .apply { token()?.let { header("Authorization", "Bearer $it") } }
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build()

            val response = http.send(request, HttpResponse.BodyHandlers.ofString())
            if (response.statusCode() != 200) {
                log.error(
                    "Leaderboard submit rejected, result stands regardless " +
                        "(board=$boardId, player=$playerId, status=${response.statusCode()})"
                )
            }
        } catch (e: Exception) {
            log.error(
                "Leaderboard submit failed, result stands regardless " +
                    "(board=$boardId, player=$playerId): ${e.message}",
                e,
            )
        }
    }

    @PreDestroy
    fun close() {
        http.close()
    }

    /**
     * Read fresh on every call rather than cached: it is a projected volume the kubelet rotates,
     * and a token read once at startup would expire under this long-running process while
     * leaderboard writes quietly started failing auth hours later. A missing file (local dev, or a
     * service-leaderboard with auth disabled) just means the call goes out unauthenticated.
     */
    private fun token(): String? {
        val path = Path.of(System.getenv("GROUNDS_TOKEN_FILE") ?: DEFAULT_TOKEN_PATH)
        return try {
            if (Files.exists(path)) Files.readString(path).trim().ifEmpty { null } else null
        } catch (_: Exception) {
            null
        }
    }

    companion object {
        private const val DEFAULT_TOKEN_PATH = "/var/run/secrets/grounds/token"
        private val log: Logger = Logger.getLogger(LeaderboardClient::class.java)
    }
}

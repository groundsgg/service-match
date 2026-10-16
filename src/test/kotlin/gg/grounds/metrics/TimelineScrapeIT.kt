package gg.grounds.metrics

import gg.grounds.persistence.ValkeyQueueIT
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.junit.QuarkusTest
import io.restassured.RestAssured.given
import jakarta.inject.Inject
import java.time.Duration
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The timeline histograms as the satellite's Alloy scrapes them. The names and the `le` values are
 * a contract with the Match timeline dashboard, and they are produced by the Prometheus registry's
 * naming rather than written here — so this reads them back off `/q/metrics` instead of trusting
 * the Micrometer names.
 */
@QuarkusTest
@QuarkusTestResource(ValkeyQueueIT.PostgresResource::class)
@QuarkusTestResource(ValkeyQueueIT.ValkeyResource::class)
class TimelineScrapeIT {

    @Inject lateinit var metrics: MatchMetrics

    @Test
    fun `the scrape exports the contract's names and buckets`() {
        metrics.queueWait("scrape", Duration.ofSeconds(2))
        metrics.timeToMatch("scrape", Duration.ofSeconds(2))
        metrics.timeToAllocate("scrape", "ok", Duration.ofMillis(800))
        metrics.timeToPush("scrape", "failed", Duration.ofMillis(40))

        val body = given().get("/q/metrics").then().statusCode(200).extract().asString()

        for (name in
            listOf(
                "grounds_match_time_to_match_seconds",
                "grounds_match_time_to_allocate_seconds",
                "grounds_match_time_to_push_seconds",
                "match_queue_wait_seconds",
            )) {
            val buckets =
                body
                    .lines()
                    .filter { it.startsWith("${name}_bucket{") && "mode=\"scrape\"" in it }
                    .map { Regex("le=\"([^\"]+)\"").find(it)!!.groupValues[1] }
            assertEquals(
                BUCKETS.map { it.toDouble() } + Double.POSITIVE_INFINITY,
                buckets.map { if (it == "+Inf") Double.POSITIVE_INFINITY else it.toDouble() },
                "$name buckets in:\n${body.lines().filter { name in it }.joinToString("\n")}",
            )
            assertTrue(
                body.lines().any { it.startsWith("${name}_count{") && "mode=\"scrape\"" in it },
                "$name count",
            )
        }

        assertTrue(
            body.lines().any {
                it.startsWith("grounds_match_time_to_allocate_seconds_count{") &&
                    "result=\"ok\"" in it
            }
        )
        assertTrue(
            body.lines().any {
                it.startsWith("grounds_match_time_to_push_seconds_count{") &&
                    "result=\"failed\"" in it
            }
        )
    }

    private companion object {
        private val BUCKETS =
            listOf(
                "0.1",
                "0.25",
                "0.5",
                "1",
                "2",
                "3",
                "5",
                "7.5",
                "10",
                "15",
                "20",
                "30",
                "45",
                "60",
                "90",
                "120",
                "180",
                "300",
            )
    }
}

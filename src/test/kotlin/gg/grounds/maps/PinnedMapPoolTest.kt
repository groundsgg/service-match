package gg.grounds.maps

import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.concurrent.atomic.AtomicInteger
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** The pin file read over real HTTP, from a server on a local port. */
class PinnedMapPoolTest {

    private val requests = AtomicInteger()

    @Volatile private var status = 200

    @Volatile private var body = PIN_FILE

    private val server: HttpServer =
        HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/pins/stage.json") { exchange ->
                requests.incrementAndGet()
                val bytes = body.toByteArray()
                exchange.sendResponseHeaders(status, bytes.size.toLong())
                exchange.responseBody.use { it.write(bytes) }
            }
            start()
        }

    private var now: Instant = Instant.parse("2026-10-19T12:00:00Z")

    private fun pool(): PinnedMapPool =
        PinnedMapPool(
                cdnBase = "http://127.0.0.1:${server.address.port}/",
                environment = "stage",
                cacheSeconds = 60,
                fetchTimeoutMs = 2000,
            )
            .also { pool ->
                pool.clock =
                    object : Clock() {
                        override fun getZone() = ZoneOffset.UTC

                        override fun withZone(zone: java.time.ZoneId?) = this

                        override fun instant() = now
                    }
            }

    @AfterEach
    fun stop() {
        server.stop(0)
    }

    @Test
    fun `a namespace's pool is every map pinned under it, lit or not`() {
        val pool = pool()

        assertEquals(
            listOf(
                PinnedMap("survivalgames/aura", lit = true),
                PinnedMap("survivalgames/breeze", lit = false),
            ),
            pool.pool("survivalgames"),
        )
        assertEquals(listOf(PinnedMap("bedwars/2-1-antika", lit = true)), pool.pool("bedwars/"))
        // A namespace is matched whole, not as a prefix of another one.
        assertTrue(pool.pool("survival").isEmpty())
    }

    @Test
    fun `the pin file is read once a minute, not per call`() {
        val pool = pool()

        pool.pool("survivalgames")
        now = now.plusSeconds(59)
        pool.pool("bedwars")
        assertEquals(1, requests.get())

        now = now.plusSeconds(2)
        pool.pool("survivalgames")
        assertEquals(2, requests.get())
    }

    @Test
    fun `a failed read keeps the last good copy`() {
        val pool = pool()
        assertEquals(2, pool.pool("survivalgames").size)

        status = 500
        now = now.plusSeconds(61)
        assertEquals(2, pool.pool("survivalgames").size, "a CDN blip changes nothing")

        body = "not json"
        status = 200
        now = now.plusSeconds(61)
        assertEquals(2, pool.pool("survivalgames").size, "nor does a broken file")
        assertEquals(3, requests.get())
    }

    @Test
    fun `a pool that never loaded is empty, and retried soon`() {
        status = 503
        val pool = pool()

        assertTrue(pool.all().isEmpty())
        now = now.plusSeconds(6)
        status = 200
        assertEquals(3, pool.all().size, "retried well before the minute is up")
    }

    @Test
    fun `fields this service does not know are ignored`() {
        val parsed =
            PinnedMapPool.parse(
                """{"environment":"stage","generatedAt":"x","maps":{"a/b":{"version":1,""" +
                    """"future":{"x":1},"lit":null},"a/c":{"lit":{"format":2}}}}"""
            )

        assertEquals(listOf(PinnedMap("a/b", lit = false), PinnedMap("a/c", lit = true)), parsed)
    }

    private companion object {
        private val PIN_FILE =
            """
            {"environment":"stage","maps":{
              "bedwars/2-1-antika":{"version":1,"bundleSha256":"e4","bundleUrl":"https://x/e4",
                "sizeBytes":331618,"estLoadedMib":2,
                "lit":{"format":1,"bundleSha256":"0e","bundleUrl":"https://x/0e"}},
              "survivalgames/aura":{"version":3,"bundleSha256":"aa","bundleUrl":"https://x/aa",
                "lit":{"format":1,"bundleSha256":"ab","bundleUrl":"https://x/ab"}},
              "survivalgames/breeze":{"version":1,"bundleSha256":"bb","bundleUrl":"https://x/bb"}
            }}
            """
                .trimIndent()
    }
}

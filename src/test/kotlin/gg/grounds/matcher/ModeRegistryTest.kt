package gg.grounds.matcher

import gg.grounds.domain.BandConfig
import gg.grounds.domain.ModeConfig
import gg.grounds.persistence.ValkeyQueue
import gg.grounds.persistence.ValkeyQueueIT
import io.quarkus.runtime.StartupEvent
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.junit.QuarkusTest
import jakarta.inject.Inject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

/**
 * The persisted half of mode config.
 *
 * `ModeRegistry` used to be a plain in-memory `ConcurrentHashMap`, filled only by `UpsertQueue`.
 * Any restart silently deleted every queue: `/queue duel` would then fail with an unknown mode and
 * nobody found out until a player hit it. These tests are against exactly that regression — a fresh
 * registry must reload from Valkey before it is trusted with anything.
 */
@QuarkusTest
@QuarkusTestResource(ValkeyQueueIT.ValkeyResource::class)
@QuarkusTestResource(ValkeyQueueIT.PostgresResource::class)
class ModeRegistryTest {

    @Inject lateinit var queue: ValkeyQueue

    private fun mode(
        modeId: String,
        teamSize: Int = 2,
        teamCount: Int = 2,
        ranked: Boolean = true,
    ) =
        ModeConfig(
            modeId = modeId,
            teamSize = teamSize,
            teamCount = teamCount,
            ranked = ranked,
            band =
                BandConfig(
                    b0 = 3.0,
                    k = 1.5,
                    w = 2.0,
                    stepSeconds = 15,
                    mercySeconds = 60,
                    mutualSeconds = 200,
                ),
        )

    @Test
    fun `a persisted mode comes back after a fresh registry reloads it`() {
        val config = mode("bedwars", teamSize = 4, teamCount = 2, ranked = true)
        queue.saveMode(config)

        // A brand new registry, exactly what boots after a restart: it must not
        // depend on anything the previous process held only in memory.
        val fresh = ModeRegistry(queue)
        fresh.loadPersisted(StartupEvent())

        assertEquals(config, fresh.find("bedwars"))
    }

    @Test
    fun `every persisted mode comes back, not just one`() {
        queue.saveMode(mode("duel", teamSize = 1, teamCount = 2))
        queue.saveMode(mode("bedwars", teamSize = 4, teamCount = 4))
        queue.saveMode(mode("ffa", teamSize = 1, teamCount = 8, ranked = false))

        val fresh = ModeRegistry(queue)
        fresh.loadPersisted(StartupEvent())

        assertEquals(setOf("duel", "bedwars", "ffa"), fresh.all().map { it.modeId }.toSet())
    }

    @Test
    fun `the dev duel seed does not clobber a persisted duel mode`() {
        // A real "duel" pushed by forge, shaped nothing like the dev placeholder
        // (teamSize 1x2, ranked=false).
        val real = mode("duel", teamSize = 4, teamCount = 3, ranked = true)
        queue.saveMode(real)

        val fresh = ModeRegistry(queue)
        fresh.loadPersisted(StartupEvent())

        assertEquals(real, fresh.find("duel"), "the persisted mode must win over the dev seed")
    }

    @Test
    fun `the dev duel seed fills in when nothing was persisted`() {
        // Nothing saved to Valkey — a genuinely empty dev environment.
        val fresh = ModeRegistry(queue)
        fresh.loadPersisted(StartupEvent())

        assertEquals("duel", fresh.find("duel")?.modeId)
    }

    @Test
    fun `an unreachable valkey fails startup loudly instead of booting with an empty registry`() {
        val broken = mock<ValkeyQueue>()
        whenever(broken.loadModes()).thenThrow(RuntimeException("connection refused"))
        val registry = ModeRegistry(broken)

        // A silently empty registry is the exact bug this whole mechanism exists
        // to prevent — an unreachable Valkey at boot must fail the boot, not hand
        // back a matchmaker that looks up and answers "unknown mode" to everyone.
        assertThrows(RuntimeException::class.java) { registry.loadPersisted(StartupEvent()) }
    }
}

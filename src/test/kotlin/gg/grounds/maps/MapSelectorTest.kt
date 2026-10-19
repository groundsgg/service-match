package gg.grounds.maps

import gg.grounds.domain.ModeMaps
import kotlin.random.Random
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class MapSelectorTest {

    private val maps = ModeMaps("survivalgames")

    private fun context(
        pool: List<PinnedMap>,
        requested: String? = null,
        maps: ModeMaps? = this.maps,
    ) = MapSelectionContext("survivalgames-solo", maps, requested, pool)

    @Test
    fun `random picks only lit maps while any exist`() {
        val pool =
            listOf(
                PinnedMap("survivalgames/aura", lit = true),
                PinnedMap("survivalgames/breeze"),
                PinnedMap("survivalgames/cinder", lit = true),
                PinnedMap("survivalgames/dune"),
            )
        val picked =
            (0 until 200)
                .map { seed -> RandomMapSelector(Random(seed)).select(context(pool)) }
                .map { (it as MapDecision.Decided).address }
                .toSet()

        assertEquals(setOf("survivalgames/aura", "survivalgames/cinder"), picked)
    }

    @Test
    fun `random spreads over the whole pool when nothing is lit`() {
        val pool = listOf(PinnedMap("survivalgames/aura"), PinnedMap("survivalgames/breeze"))
        val picked =
            (0 until 200)
                .map { seed -> RandomMapSelector(Random(seed)).select(context(pool)) }
                .map { (it as MapDecision.Decided).address }
                .toSet()

        assertEquals(setOf("survivalgames/aura", "survivalgames/breeze"), picked)
    }

    @Test
    fun `a mode without maps gets none`() {
        val pool = listOf(PinnedMap("survivalgames/aura", lit = true))

        assertEquals(MapDecision.None, RandomMapSelector().select(context(pool, maps = null)))
        assertEquals(
            MapDecision.None,
            RequestedMapSelector(RandomMapSelector())
                .select(context(pool, requested = "survivalgames/aura", maps = null)),
        )
    }

    @Test
    fun `an empty pool gets none`() {
        assertEquals(MapDecision.None, RandomMapSelector().select(context(emptyList())))
    }

    @Test
    fun `the requested map wins over random`() {
        val pool =
            listOf(PinnedMap("survivalgames/aura", lit = true), PinnedMap("survivalgames/breeze"))
        val selector = RequestedMapSelector(RandomMapSelector())

        repeat(20) {
            assertEquals(
                MapDecision.Decided("survivalgames/breeze"),
                selector.select(context(pool, requested = "survivalgames/breeze")),
            )
        }
    }

    @Test
    fun `a requested map that was unpinned since falls back to random`() {
        val pool = listOf(PinnedMap("survivalgames/aura"))

        assertEquals(
            MapDecision.Decided("survivalgames/aura"),
            RequestedMapSelector(RandomMapSelector())
                .select(context(pool, requested = "survivalgames/gone")),
        )
    }

    @Test
    fun `a request is honoured when the pin file could not be read`() {
        val decision =
            RequestedMapSelector(RandomMapSelector())
                .select(context(emptyList(), requested = "survivalgames/aura"))

        assertEquals(MapDecision.Decided("survivalgames/aura"), decision)
    }

    @Test
    fun `no request falls through to the fallback`() {
        val decision =
            RequestedMapSelector { MapDecision.Decided("from-fallback") }
                .select(context(listOf(PinnedMap("survivalgames/aura"))))

        assertTrue(decision is MapDecision.Decided)
        assertEquals("from-fallback", (decision as MapDecision.Decided).address)
    }
}

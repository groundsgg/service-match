package gg.grounds.maps

import gg.grounds.domain.MapSelectionStrategy
import gg.grounds.domain.ModeMaps
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import org.jboss.logging.Logger

/** The map a match plays — empty for none — and which rule chose it, for the match hash. */
data class MapChoice(val address: String, val strategy: String)

/** A mode's pool as the proxy completes `/queue <mode> <map>` from it. */
data class ModeMapPool(val maps: List<String>, val selection: MapSelectionStrategy?)

/**
 * Chooses a match's map at allocation time, before the StartMatch push.
 *
 * The one bean for it: the strategies themselves are plain classes, so adding pick/ban later is a
 * new [MapSelector] chosen here by the mode's [MapSelectionStrategy], not a second bean that every
 * injection point would have to tell apart.
 */
@ApplicationScoped
class MapSelection @Inject constructor(private val pinned: PinnedMapPool) {

    private val requestedOrRandom = RequestedMapSelector(RandomMapSelector())

    fun choose(modeId: String, maps: ModeMaps?, requestedMap: String?): MapChoice {
        if (maps == null) return NONE
        val context =
            MapSelectionContext(
                modeId = modeId,
                maps = maps,
                requestedMap = requestedMap,
                pool = pinned.pool(maps.namespace),
            )
        return when (val decision = selector(maps.selection).select(context)) {
            is MapDecision.Decided ->
                MapChoice(
                    decision.address,
                    if (decision.address == requestedMap) REQUESTED else maps.selection.name,
                )
            is MapDecision.Deferred -> {
                // No vote exists to defer to yet; a mode cannot register one either. Should a
                // selector ever answer this before the vote is built, play rather than stall.
                log.warn("Map selection deferred but no vote is implemented (mode=$modeId)")
                decision.candidates.randomOrNull()?.let { MapChoice(it, maps.selection.name) }
                    ?: NONE
            }
            MapDecision.None -> {
                log.warn(
                    "No pinned map for mode $modeId under ${maps.namespace}/; " +
                        "pushing the match without one"
                )
                NONE
            }
        }
    }

    /** The mode's pinned pool, sorted; empty for a mode without maps. */
    fun pool(maps: ModeMaps?): ModeMapPool =
        if (maps == null) ModeMapPool(emptyList(), null)
        else ModeMapPool(pinned.pool(maps.namespace).map { it.address }, maps.selection)

    /** Whether [address] may be asked for in a mode with [maps]. */
    fun inPool(maps: ModeMaps, address: String): Boolean =
        pinned.pool(maps.namespace).any { it.address == address }

    private fun selector(strategy: MapSelectionStrategy): MapSelector =
        when (strategy) {
            MapSelectionStrategy.RANDOM -> requestedOrRandom
            // Refused at registration; a row written by a newer replica falls back to random.
            MapSelectionStrategy.PICK_BAN -> requestedOrRandom
        }

    companion object {
        private val log: Logger = Logger.getLogger(MapSelection::class.java)

        const val REQUESTED = "REQUESTED"
        val NONE = MapChoice("", "NONE")
    }
}

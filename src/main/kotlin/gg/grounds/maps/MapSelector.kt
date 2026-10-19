package gg.grounds.maps

import gg.grounds.domain.ModeMaps
import java.time.Instant
import kotlin.random.Random

/** One map pinned for this environment, as the CDN pin file lists it. */
data class PinnedMap(
    /** `<namespace>/<name>`, exactly as service-maps addresses it. */
    val address: String,
    /** Whether the pin ships a pre-lit world, which a server loads without lighting it first. */
    val lit: Boolean = false,
)

/** What a match's map is chosen from. */
data class MapSelectionContext(
    val modeId: String,
    /** The mode's map pool settings; null for a mode without maps. */
    val maps: ModeMaps?,
    /** The map a ticket of the match asked for, or null when none did. */
    val requestedMap: String?,
    /** Every map pinned under the mode's namespace. Empty if the pin file could not be read. */
    val pool: List<PinnedMap>,
)

/**
 * The outcome of choosing a match's map.
 *
 * [Deferred] is the extension point for a map vote (pick/ban): instead of a map, the selector hands
 * back a few candidates and a deadline, the match would wait in `MAP_SELECTION` with
 * `mapCandidates` and `mapDeadlineAt` on its hash, and the players' picks would settle it before
 * the StartMatch push. Nothing produces it yet.
 */
sealed interface MapDecision {
    data class Decided(val address: String) : MapDecision

    data class Deferred(val candidates: List<String>, val deadline: Instant) : MapDecision

    /** No map: the mode has none, or none could be found. The push carries an empty address. */
    object None : MapDecision
}

/** Chooses the map a match plays. Pure: the pool is read before it is called. */
fun interface MapSelector {
    fun select(context: MapSelectionContext): MapDecision
}

/**
 * Uniformly at random from the pool, preferring maps whose pin ships a pre-lit world: a server
 * loads those without lighting them first, so the round is ready sooner.
 */
class RandomMapSelector(private val random: Random = Random.Default) : MapSelector {
    override fun select(context: MapSelectionContext): MapDecision {
        if (context.maps == null || context.pool.isEmpty()) return MapDecision.None
        val candidates = context.pool.filter { it.lit }.ifEmpty { context.pool }
        return MapDecision.Decided(candidates.random(random).address)
    }
}

/**
 * The map a player asked for wins; otherwise [fallback] decides.
 *
 * The request was checked against the pool when the ticket was enqueued. A map that has since been
 * unpinned is not played — the server could no longer load it — and the fallback picks instead. An
 * empty pool means the pin file could not be read at all, which says nothing about the map, so the
 * request is honoured.
 */
class RequestedMapSelector(private val fallback: MapSelector) : MapSelector {
    override fun select(context: MapSelectionContext): MapDecision {
        val requested = context.requestedMap
        if (
            context.maps != null &&
                requested != null &&
                (context.pool.isEmpty() || context.pool.any { it.address == requested })
        ) {
            return MapDecision.Decided(requested)
        }
        return fallback.select(context)
    }
}

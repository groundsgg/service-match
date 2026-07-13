package gg.grounds.allocator

import gg.grounds.domain.ServerAssignment
import io.fabric8.kubernetes.api.model.GenericKubernetesResource
import io.fabric8.kubernetes.client.KubernetesClient
import io.fabric8.kubernetes.client.dsl.base.ResourceDefinitionContext
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import org.eclipse.microprofile.config.inject.ConfigProperty
import org.jboss.logging.Logger

/**
 * Finds a server with room for one more match.
 *
 * A Minestom runtime hosts **many matches at once**, each its own instance. So the usual Agones
 * model — one GameServer per match, `Allocated` meaning "taken" — does not apply here. A server is
 * never taken; it just has fewer free slots. Agones models that with **Counters**: the Fleet
 * declares `matches: {count: 0, capacity: N}`, and an allocation asks for a server with room and
 * increments the counter atomically.
 *
 * That atomic increment is what stops two matches being packed onto a server that can hold one
 * more, and it is why we let Agones do the counting rather than tracking capacity ourselves.
 *
 * **Allocation is still not idempotent.** A POST that succeeds server-side but never reaches us
 * leaves the counter one too high — a *leaked slot*, not a stranded server. That is a far milder
 * failure than the old model's orphaned GameServer, and it self-heals: the gamemode owns its
 * counter (Agones SDK) and sets it to the number of instances it is actually running, so a drifted
 * count is corrected the next time the server syncs. The lease in [AllocationWorker] keeps such
 * leaks rare in the first place.
 *
 * Decrementing is deliberately not done here. The server knows when one of its matches ends; we do
 * not.
 */
@ApplicationScoped
class AgonesAllocator
@Inject
constructor(
    private val kubernetes: KubernetesClient,
    @param:ConfigProperty(name = "grounds.match.agones.namespace") private val namespace: String,
    @param:ConfigProperty(name = "grounds.match.agones.address-type")
    private val addressType: String,
    @param:ConfigProperty(name = "grounds.match.agones.port") private val port: Int,
) {

    /**
     * Claim a slot on a server for this match.
     *
     * @return the server, or null when every server in the fleet is full.
     */
    fun allocate(
        matchId: String,
        modeId: String,
        fleetName: String,
        expectedPlayers: Int,
    ): ServerAssignment? {
        val body = allocationBody(matchId, modeId, fleetName, expectedPlayers)
        val result =
            kubernetes
                .genericKubernetesResources(ALLOCATION_CONTEXT)
                .inNamespace(namespace)
                .resource(body)
                .create()

        @Suppress("UNCHECKED_CAST")
        val status = result.additionalProperties["status"] as? Map<String, Any?> ?: return null

        // "UnAllocated" means every server is at capacity — a real answer, not an
        // error. The players go back on the queue and try again next tick.
        if (status["state"] != "Allocated") {
            log.warn(
                "No server with a free slot (match=$matchId, fleet=$fleetName, state=${status["state"]})"
            )
            return null
        }

        return assignmentFrom(status)
    }

    /** Look up a server by name — used to re-derive an address after a crash. */
    fun find(gameServerName: String): ServerAssignment? {
        val gs =
            kubernetes
                .genericKubernetesResources(GAMESERVER_CONTEXT)
                .inNamespace(namespace)
                .withName(gameServerName)
                .get() ?: return null

        @Suppress("UNCHECKED_CAST")
        val status = gs.additionalProperties["status"] as? Map<String, Any?> ?: return null
        return assignmentFrom(status, gs.metadata?.name)
    }

    /**
     * Our fleets run `portPolicy: None` — Agones does no port management for them, because the
     * proxy reaches a server on its pod IP and a host port only ever caused collisions.
     *
     * Two consequences, and both of them bite here:
     *
     * `status.ports` is **empty**. Reading a port out of it does not merely give the wrong number,
     * it gives nothing at all — and an allocator that treats that as failure reports "no server
     * available" for a fleet that is standing there idle, forever. The port is fixed instead.
     *
     * `status.address` is the **node's** address, not the pod's. Dialling it would reach the node,
     * which is not where the match is. The pod's address is in `status.addresses` under `PodIP` —
     * the same entry, by the same rule, that plugin-agones registers proxy backends by.
     */
    internal fun assignmentFrom(
        status: Map<String, Any?>,
        name: String? = null,
    ): ServerAssignment? {
        val gsName = name ?: status["gameServerName"]?.toString() ?: return null

        @Suppress("UNCHECKED_CAST") val addresses = status["addresses"] as? List<Map<String, Any?>>
        val address =
            addresses
                ?.firstOrNull { it["type"]?.toString() == addressType }
                ?.get("address")
                ?.toString()
        // Agones does not always publish the PodIP in status.addresses — our own
        // fleets came up with only Hostname and InternalIP, while the bundle's
        // lobby fleet had all three. Rather than depend on that, fall back to the
        // pod itself: Agones names the pod after the GameServer, so it is a direct
        // lookup. Reading the node's InternalIP instead would be worse than
        // failing — the players would be sent to a machine, not to their match.
        val resolved = address ?: podIp(gsName)
        if (resolved == null) {
            log.error(
                "GameServer has no $addressType address and its pod has no IP, " +
                    "cannot route players to it (gs=$gsName)"
            )
            return null
        }

        return ServerAssignment(gsName, resolved, port)
    }

    /** The GameServer's pod carries the same name, so this is a direct lookup. */
    private fun podIp(gsName: String): String? =
        try {
            kubernetes.pods().inNamespace(namespace).withName(gsName).get()?.status?.podIP
        } catch (e: Exception) {
            log.warn("Could not read the pod for $gsName: ${e.message}")
            null
        }

    /**
     * A Minestom runtime hosts *many* matches at once, so "allocate a server" is the wrong
     * operation — a server is never taken, it just has fewer free slots. Agones models exactly this
     * with Counters: the Fleet template declares `matches: {count: 0, capacity: N}`, and an
     * allocation asks for a server with room and increments the counter atomically.
     *
     * The two selectors are ordered on purpose: prefer a server that is *already running matches*
     * and still has room, and only take a fresh one when none has. That packs matches onto warm
     * servers instead of spreading one match per server and idling the rest.
     *
     * `Allocated` therefore does not mean "occupied" here. It means "carrying at least one match" —
     * which is why the reaper must never delete a server simply because one match disappeared, and
     * why the gamemode must never call `ready()` when it happens to be empty for a moment.
     *
     * Agones can only *increment* through allocation. Decrementing is the server's job (Agones SDK)
     * when one of its matches ends — it knows when that is; we do not.
     */
    private fun allocationBody(
        matchId: String,
        modeId: String,
        fleetName: String,
        expectedPlayers: Int,
    ): GenericKubernetesResource =
        GenericKubernetesResource().apply {
            apiVersion = "allocation.agones.dev/v1"
            kind = "GameServerAllocation"
            additionalProperties["spec"] =
                mapOf(
                    "selectors" to
                        listOf(
                            // Pack onto a server that is already running matches.
                            mapOf(
                                "matchLabels" to mapOf("agones.dev/fleet" to fleetName),
                                "gameServerState" to "Allocated",
                                "counters" to mapOf(MATCH_COUNTER to mapOf("minAvailable" to 1)),
                            ),
                            // Nothing warm has room — start using a fresh server.
                            mapOf(
                                "matchLabels" to mapOf("agones.dev/fleet" to fleetName),
                                "gameServerState" to "Ready",
                                "counters" to mapOf(MATCH_COUNTER to mapOf("minAvailable" to 1)),
                            ),
                        ),
                    "counters" to
                        mapOf(MATCH_COUNTER to mapOf("action" to "Increment", "amount" to 1)),
                    // A server now carries several matches, so a single match-id
                    // label cannot identify it. These are annotations for
                    // operators looking at a server, not the dedup key they were
                    // in the one-match-per-server design.
                    "metadata" to
                        mapOf(
                            "labels" to mapOf(GAME_MODE_LABEL to modeId),
                            "annotations" to
                                mapOf(
                                    LAST_MATCH_ID to matchId,
                                    EXPECTED_PLAYERS to expectedPlayers.toString(),
                                ),
                        ),
                )
        }

    companion object {
        private val log: Logger = Logger.getLogger(AgonesAllocator::class.java)

        /**
         * The Fleet template must declare this counter: `counters: {matches: {count: 0, capacity:
         * N}}`. It is how Agones knows a server still has room, and it is the only thing that keeps
         * two matches from being packed onto a server that can hold one.
         */
        const val MATCH_COUNTER = "matches"

        const val GAME_MODE_LABEL = "grounds/game-mode"

        /**
         * Informational. A server carries many matches now, so this is "the last match placed
         * here", not a dedup key — the match's server is recorded in Valkey instead, which is what
         * a retry consults.
         */
        const val LAST_MATCH_ID = "grounds/last-match-id"
        const val EXPECTED_PLAYERS = "grounds/expected-players"

        private val ALLOCATION_CONTEXT: ResourceDefinitionContext =
            ResourceDefinitionContext.Builder()
                .withGroup("allocation.agones.dev")
                .withVersion("v1")
                .withPlural("gameserverallocations")
                .withNamespaced(true)
                .build()

        private val GAMESERVER_CONTEXT: ResourceDefinitionContext =
            ResourceDefinitionContext.Builder()
                .withGroup("agones.dev")
                .withVersion("v1")
                .withPlural("gameservers")
                .withNamespaced(true)
                .build()
    }
}

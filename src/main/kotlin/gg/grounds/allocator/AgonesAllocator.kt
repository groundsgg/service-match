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
 * Gets a GameServer for a match.
 *
 * The hard part is that `GameServerAllocation` is **not idempotent**. It is a create with no
 * idempotency key: every POST hands back a *different* server. A client timeout is therefore
 * ambiguous — the allocation may well have succeeded server-side, and simply retrying would strand
 * the first server and hand the players a second one.
 *
 * So before allocating, we look for a server already carrying this match's id (`adopt`). Agones'
 * MetadataPatch stamps that label at allocation time, which makes a successful-but-unacknowledged
 * POST *findable*. Combined with the lease in [AllocationWorker], that narrows the duplicate window
 * to the sliver where a POST lands server-side after our GET and before our lease expires.
 *
 * It does not close it. A client cannot un-ring that bell — hence the reaper. The design says
 * at-least-once with adopt-dedup, and means it.
 */
@ApplicationScoped
class AgonesAllocator
@Inject
constructor(
    private val kubernetes: KubernetesClient,
    @param:ConfigProperty(name = "grounds.match.agones.namespace") private val namespace: String,
) {

    /**
     * @return the server for this match, whether it was adopted or freshly allocated, or null if
     *   the fleet had nothing ready.
     */
    fun allocate(
        matchId: String,
        modeId: String,
        fleetName: String,
        expectedPlayers: Int,
    ): ServerAssignment? {
        adopt(matchId)?.let {
            // A previous attempt already succeeded — we just never got to hear
            // about it. Taking that server is the whole point of the label.
            log.info("Adopted existing GameServer (match=$matchId, gs=${it.gameServerName})")
            return it
        }

        val body = allocationBody(matchId, modeId, fleetName, expectedPlayers)
        val result =
            kubernetes
                .genericKubernetesResources(ALLOCATION_CONTEXT)
                .inNamespace(namespace)
                .resource(body)
                .create()

        @Suppress("UNCHECKED_CAST")
        val status = result.additionalProperties["status"] as? Map<String, Any?> ?: return null

        // "UnAllocated" means the fleet had no Ready server — a real answer, not
        // an error. The match goes back on the queue and tries again.
        if (status["state"] != "Allocated") {
            log.warn(
                "Fleet had nothing ready (match=$matchId, fleet=$fleetName, state=${status["state"]})"
            )
            return null
        }

        return status.toAssignment()
    }

    /** A GameServer already labelled with this match id, if any. */
    fun adopt(matchId: String): ServerAssignment? {
        val existing =
            kubernetes
                .genericKubernetesResources(GAMESERVER_CONTEXT)
                .inNamespace(namespace)
                .withLabel(MATCH_ID_LABEL, matchId)
                .list()
                .items
                .firstOrNull() ?: return null

        @Suppress("UNCHECKED_CAST")
        val status = existing.additionalProperties["status"] as? Map<String, Any?> ?: return null
        return status.toAssignment(name = existing.metadata?.name)
    }

    /** Every GameServer stamped with a match id — the reaper's input. */
    fun listMatchGameServers(): List<AllocatedGameServer> =
        kubernetes
            .genericKubernetesResources(GAMESERVER_CONTEXT)
            .inNamespace(namespace)
            .withLabel(MATCH_ID_LABEL)
            .list()
            .items
            .mapNotNull { gs ->
                val matchId = gs.metadata?.labels?.get(MATCH_ID_LABEL) ?: return@mapNotNull null
                val name = gs.metadata?.name ?: return@mapNotNull null

                @Suppress("UNCHECKED_CAST")
                val status = gs.additionalProperties["status"] as? Map<String, Any?>
                AllocatedGameServer(
                    name = name,
                    matchId = matchId,
                    state = status?.get("state")?.toString() ?: "Unknown",
                )
            }

    fun delete(gameServerName: String) {
        kubernetes
            .genericKubernetesResources(GAMESERVER_CONTEXT)
            .inNamespace(namespace)
            .withName(gameServerName)
            .delete()
        log.info("Deleted GameServer $gameServerName")
    }

    private fun Map<String, Any?>.toAssignment(name: String? = null): ServerAssignment? {
        val gsName = name ?: this["gameServerName"]?.toString() ?: return null
        val address = this["address"]?.toString() ?: return null

        @Suppress("UNCHECKED_CAST") val ports = this["ports"] as? List<Map<String, Any?>>
        val port = ports?.firstOrNull()?.get("port")?.toString()?.toIntOrNull() ?: return null

        return ServerAssignment(gsName, address, port)
    }

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
                            mapOf(
                                "matchLabels" to mapOf("agones.dev/fleet" to fleetName),
                                "gameServerState" to "Ready",
                            )
                        ),
                    // The label is what makes a lost-but-successful allocation
                    // findable again. Without it, a timeout is unrecoverable.
                    "metadata" to
                        mapOf(
                            "labels" to mapOf(MATCH_ID_LABEL to matchId, GAME_MODE_LABEL to modeId),
                            "annotations" to mapOf(EXPECTED_PLAYERS to expectedPlayers.toString()),
                        ),
                )
        }

    companion object {
        private val log: Logger = Logger.getLogger(AgonesAllocator::class.java)

        const val MATCH_ID_LABEL = "grounds/match-id"
        const val GAME_MODE_LABEL = "grounds/game-mode"
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

data class AllocatedGameServer(val name: String, val matchId: String, val state: String)

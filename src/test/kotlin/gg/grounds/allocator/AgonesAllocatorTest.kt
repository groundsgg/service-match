package gg.grounds.allocator

import io.fabric8.kubernetes.client.KubernetesClient
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.mockito.kotlin.mock

/**
 * How an allocated GameServer becomes an address we can actually reach.
 *
 * This looks like plumbing and is not. Our fleets run `portPolicy: None`, so Agones does no port
 * management for them: `status.ports` comes back **empty** and `status.address` is the *node's*
 * address rather than the pod's. An allocator that reads a port out of `status.ports` therefore
 * gets nothing, treats it as failure, and reports "no server available" — for a fleet that is
 * sitting there idle. Every match would requeue forever against a perfectly healthy fleet, and the
 * logs would blame the fleet.
 *
 * So the shapes below are the real ones, taken from what Agones actually returns for our Fleets.
 */
class AgonesAllocatorTest {

    private val allocator =
        AgonesAllocator(
            kubernetes = mock<KubernetesClient>(),
            namespace = "default",
            addressType = "PodIP",
            port = 25565,
        )

    /** What Agones returns for a `portPolicy: None` fleet: addresses, and no ports at all. */
    private fun status(
        gsName: String = "duel-abcde-xyz",
        addresses: List<Map<String, Any?>> =
            listOf(
                mapOf("type" to "InternalIP", "address" to "10.0.0.7"),
                mapOf("type" to "PodIP", "address" to "10.244.3.19"),
                mapOf("type" to "Hostname", "address" to "node-3"),
            ),
    ): Map<String, Any?> =
        mapOf(
            "state" to "Allocated",
            "gameServerName" to gsName,
            // The NODE's address. Dialling this would reach the node, not the match.
            "address" to "10.0.0.7",
            "addresses" to addresses,
            // Empty, because Agones manages no port for us. This is the field the
            // allocator used to require, and the reason it could never allocate.
            "ports" to emptyList<Map<String, Any?>>(),
        )

    @Test
    fun `an allocated server resolves to its pod, not its node`() {
        val assignment = allocator.assignmentFrom(status())

        assertEquals("duel-abcde-xyz", assignment?.gameServerName)
        assertEquals(
            "10.244.3.19",
            assignment?.address,
            "must be the PodIP, not the node's address",
        )
        assertEquals(25565, assignment?.port)
    }

    @Test
    fun `an empty ports list is not a failure`() {
        // The whole bug in one line: portPolicy None means there is no allocated
        // port to read, and requiring one turns every healthy server into "no
        // server available".
        val assignment = allocator.assignmentFrom(status())

        assertEquals(25565, assignment?.port, "the port is fixed, never read from status.ports")
    }

    @Test
    fun `a server with no pod address is refused rather than half-assigned`() {
        // Nothing sane to hand the players here. Better to fail the allocation and
        // put them back on the queue than to assign them a match at an address
        // nobody can connect to.
        val assignment =
            allocator.assignmentFrom(
                status(addresses = listOf(mapOf("type" to "InternalIP", "address" to "10.0.0.7")))
            )

        assertNull(assignment)
    }

    @Test
    fun `the name may come from the resource when the status does not carry one`() {
        // GameServerAllocation's status names the server it picked; a plain
        // GameServer GET (the crash-recovery path) does not, so the name comes
        // from the object's metadata instead.
        val fromGet = status().minus("gameServerName")

        assertEquals(
            "duel-from-metadata",
            allocator.assignmentFrom(fromGet, "duel-from-metadata")?.gameServerName,
        )
    }
}

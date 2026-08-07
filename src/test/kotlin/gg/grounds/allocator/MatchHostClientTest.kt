package gg.grounds.allocator

import gg.grounds.domain.ServerAssignment
import gg.grounds.events.NatsConnector
import gg.grounds.grpc.match.StartMatchReply
import gg.grounds.grpc.match.StartMatchRequest
import io.nats.client.Connection
import io.nats.client.Message
import io.nats.client.impl.NatsMessage
import java.time.Duration
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

/**
 * The match push over NATS.
 *
 * Most of these are about the one distinction that matters: "the server said no", "nobody answered"
 * and "the broker is gone" all end the same way — the players go back on the queue — but only an
 * accepted reply may route them.
 */
class MatchHostClientTest {

    private val connector: NatsConnector = mock()
    private val connection: Connection = mock()

    @Test
    fun `a server that accepts takes the match`() {
        givenConnected()
        whenever(connection.request(any<String>(), any<ByteArray>(), any<Duration>()))
            .thenReturn(reply(accepted = true))

        assertTrue(client().startMatch(SERVER, MATCH, MODE, TEAMS))
    }

    @Test
    fun `the server is addressed by name, not by pod IP`() {
        givenConnected()
        whenever(connection.request(any<String>(), any<ByteArray>(), any<Duration>()))
            .thenReturn(reply(accepted = true))

        client().startMatch(SERVER, MATCH, MODE, TEAMS)

        val subject = argumentCaptor<String>()
        val payload = argumentCaptor<ByteArray>()
        verify(connection).request(subject.capture(), payload.capture(), any<Duration>())
        assertEquals("match.host.bedwars-abc12.start", subject.firstValue)

        val sent = StartMatchRequest.parseFrom(payload.firstValue)
        assertEquals(MATCH, sent.matchId)
        assertEquals(MODE, sent.modeId)
        assertEquals(2, sent.teamsCount)
        assertEquals(listOf("p1", "p2"), sent.getTeams(0).playerIdsList)
    }

    @Test
    fun `a refusal is final`() {
        givenConnected()
        whenever(connection.request(any<String>(), any<ByteArray>(), any<Duration>()))
            .thenReturn(reply(accepted = false, reason = "shutting down"))

        assertFalse(client().startMatch(SERVER, MATCH, MODE, TEAMS))
    }

    @Test
    fun `no responder means no match`() {
        // jnats answers null for both a 503 no-responder and a deadline that ran
        // out. Either way nobody took this match.
        givenConnected()
        whenever(connection.request(any<String>(), any<ByteArray>(), any<Duration>()))
            .thenReturn(null)

        assertFalse(client().startMatch(SERVER, MATCH, MODE, TEAMS))
        verify(connection).request(any<String>(), any<ByteArray>(), any<Duration>())
    }

    @Test
    fun `a broker that is down means no match`() {
        whenever(connector.connection()).thenReturn(null)

        assertFalse(client().startMatch(SERVER, MATCH, MODE, TEAMS))
    }

    @Test
    fun `an empty match is refused before anything is sent`() {
        assertFalse(client().startMatch(SERVER, MATCH, MODE, emptyList()))
        assertFalse(client().startMatch(SERVER, MATCH, MODE, listOf(emptyList(), emptyList())))

        verify(connector, never()).connection()
    }

    @Test
    fun `a reply that is not a StartMatchReply is refused`() {
        // Something else is answering on this subject. A reply we cannot read is
        // not an acceptance; the players get requeued.
        givenConnected()
        // A truncated varint: there is no field number to be read here, so this
        // cannot be mistaken for a valid message with unknown fields.
        val garbage = message(byteArrayOf(0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte()))
        whenever(connection.request(any<String>(), any<ByteArray>(), any<Duration>()))
            .thenReturn(garbage)

        assertFalse(client().startMatch(SERVER, MATCH, MODE, TEAMS))
    }

    private fun givenConnected() {
        whenever(connector.connection()).thenReturn(connection)
    }

    private fun client() =
        MatchHostClient(nats = connector, subjectPrefix = "match.host", deadlineMs = 200)

    /**
     * A real [NatsMessage] rather than a mock: a mock built inside the `thenReturn(...)` of another
     * stubbing is exactly the nesting Mockito refuses, and the message carries nothing but bytes
     * anyway.
     */
    private fun reply(accepted: Boolean, reason: String = ""): Message =
        message(
            StartMatchReply.newBuilder()
                .setAccepted(accepted)
                .setReason(reason)
                .build()
                .toByteArray()
        )

    private fun message(data: ByteArray): Message =
        NatsMessage.builder().subject("reply").data(data).build()

    private companion object {
        private val SERVER = ServerAssignment("bedwars-abc12", "127.0.0.1", 25565)
        private const val MATCH = "6f1d2b3c-0000-4000-8000-000000000001"
        private const val MODE = "bedwars-squads"
        private val TEAMS = listOf(listOf("p1", "p2"), listOf("p3", "p4"))
    }
}

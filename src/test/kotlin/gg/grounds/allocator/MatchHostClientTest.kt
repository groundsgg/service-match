package gg.grounds.allocator

import gg.grounds.domain.ServerAssignment
import gg.grounds.events.NatsConnector
import gg.grounds.grpc.match.StartMatchReply
import gg.grounds.grpc.match.StartMatchRequest
import io.nats.client.Connection
import io.nats.client.Message
import io.nats.client.impl.Headers
import io.nats.client.impl.NatsMessage
import java.time.Duration
import java.time.Instant
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
        whenever(
                connection.request(any<String>(), any<Headers>(), any<ByteArray>(), any<Duration>())
            )
            .thenReturn(reply(accepted = true))

        assertTrue(client().startMatch(SERVER, MATCH, MODE, TEAMS).accepted)
    }

    @Test
    fun `the server is addressed by name, not by pod IP`() {
        givenConnected()
        whenever(
                connection.request(any<String>(), any<Headers>(), any<ByteArray>(), any<Duration>())
            )
            .thenReturn(reply(accepted = true))

        client().startMatch(SERVER, MATCH, MODE, TEAMS)

        val subject = argumentCaptor<String>()
        val payload = argumentCaptor<ByteArray>()
        verify(connection)
            .request(subject.capture(), any<Headers>(), payload.capture(), any<Duration>())
        assertEquals("match.host.bedwars-abc12.start", subject.firstValue)

        val sent = StartMatchRequest.parseFrom(payload.firstValue)
        assertEquals(MATCH, sent.matchId)
        assertEquals(MODE, sent.modeId)
        assertEquals(2, sent.teamsCount)
        assertEquals(listOf("p1", "p2"), sent.getTeams(0).playerIdsList)
    }

    @Test
    fun `the push carries the match timeline as headers`() {
        givenConnected()
        whenever(
                connection.request(any<String>(), any<Headers>(), any<ByteArray>(), any<Duration>())
            )
            .thenReturn(reply(accepted = true))

        client()
            .startMatch(
                SERVER,
                MATCH,
                MODE,
                TEAMS,
                enqueuedAt =
                    linkedMapOf(
                        P1 to Instant.ofEpochMilli(1_760_000_000_000),
                        P2 to Instant.ofEpochMilli(1_760_000_004_500),
                    ),
                formedAt = Instant.ofEpochMilli(1_760_000_010_000),
                traceparent = TRACEPARENT,
            )

        val headers = sentHeaders()
        assertEquals(listOf(TRACEPARENT), headers.get("traceparent"))
        assertEquals(
            listOf("$P1=1760000000000,$P2=1760000004500"),
            headers.get("grounds-enqueued-at"),
        )
        assertEquals(listOf("1760000010000"), headers.get("grounds-formed-at"))
    }

    @Test
    fun `without a trace the push still carries the enqueue and formed times`() {
        // OTel off, or a match formed before traces were stored: no traceparent, but the
        // game server's timeline still gets every player's queue time.
        givenConnected()
        whenever(
                connection.request(any<String>(), any<Headers>(), any<ByteArray>(), any<Duration>())
            )
            .thenReturn(reply(accepted = true))

        client()
            .startMatch(
                SERVER,
                MATCH,
                MODE,
                TEAMS,
                enqueuedAt = mapOf(P1 to Instant.ofEpochMilli(1_000)),
                formedAt = Instant.ofEpochMilli(2_000),
            )

        val headers = sentHeaders()
        assertFalse(headers.containsKey("traceparent"))
        assertEquals(listOf("$P1=1000"), headers.get("grounds-enqueued-at"))
        assertEquals(listOf("2000"), headers.get("grounds-formed-at"))
    }

    @Test
    fun `a match with no timeline stored sends no timeline headers`() {
        givenConnected()
        whenever(
                connection.request(any<String>(), any<Headers>(), any<ByteArray>(), any<Duration>())
            )
            .thenReturn(reply(accepted = true))

        assertTrue(client().startMatch(SERVER, MATCH, MODE, TEAMS).accepted)

        assertTrue(sentHeaders().isEmpty)
    }

    @Test
    fun `a refusal is final`() {
        givenConnected()
        whenever(
                connection.request(any<String>(), any<Headers>(), any<ByteArray>(), any<Duration>())
            )
            .thenReturn(reply(accepted = false, reason = "shutting down"))

        assertFalse(client().startMatch(SERVER, MATCH, MODE, TEAMS).accepted)
    }

    @Test
    fun `no responder means no match`() {
        // jnats answers null for both a 503 no-responder and a deadline that ran
        // out. Either way nobody took this match.
        givenConnected()
        whenever(
                connection.request(any<String>(), any<Headers>(), any<ByteArray>(), any<Duration>())
            )
            .thenReturn(null)

        assertFalse(client().startMatch(SERVER, MATCH, MODE, TEAMS).accepted)
        verify(connection).request(any<String>(), any<Headers>(), any<ByteArray>(), any<Duration>())
    }

    @Test
    fun `a broker that is down means no match`() {
        whenever(connector.connection()).thenReturn(null)

        assertFalse(client().startMatch(SERVER, MATCH, MODE, TEAMS).accepted)
    }

    @Test
    fun `an empty match is refused before anything is sent`() {
        assertFalse(client().startMatch(SERVER, MATCH, MODE, emptyList()).accepted)
        assertFalse(
            client().startMatch(SERVER, MATCH, MODE, listOf(emptyList(), emptyList())).accepted
        )

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
        whenever(
                connection.request(any<String>(), any<Headers>(), any<ByteArray>(), any<Duration>())
            )
            .thenReturn(garbage)

        assertFalse(client().startMatch(SERVER, MATCH, MODE, TEAMS).accepted)
    }

    @Test
    fun `the push carries the chosen map and asks the server to report ready`() {
        givenConnected()
        whenever(
                connection.request(any<String>(), any<Headers>(), any<ByteArray>(), any<Duration>())
            )
            .thenReturn(reply(accepted = true))

        client().startMatch(SERVER, MATCH, MODE, TEAMS, mapAddress = "survivalgames/aura")

        val sent = sentRequest()
        assertEquals("survivalgames/aura", sent.mapAddress)
        assertTrue(sent.waitForReady)
    }

    @Test
    fun `a mode without maps pushes an empty map address`() {
        givenConnected()
        whenever(
                connection.request(any<String>(), any<Headers>(), any<ByteArray>(), any<Duration>())
            )
            .thenReturn(reply(accepted = true))

        client().startMatch(SERVER, MATCH, MODE, TEAMS)

        assertEquals("", sentRequest().mapAddress)
    }

    @Test
    fun `a server that will report ready says so`() {
        givenConnected()
        whenever(
                connection.request(any<String>(), any<Headers>(), any<ByteArray>(), any<Duration>())
            )
            .thenReturn(reply(accepted = true, signalsReady = true))

        assertEquals(
            StartMatchResult(accepted = true, signalsReady = true),
            client().startMatch(SERVER, MATCH, MODE, TEAMS),
        )
    }

    @Test
    fun `an older server that never signals is ready when it accepts`() {
        // Its reply has no signals_ready at all: the players are routed at once, as before.
        givenConnected()
        whenever(
                connection.request(any<String>(), any<Headers>(), any<ByteArray>(), any<Duration>())
            )
            .thenReturn(reply(accepted = true))

        assertEquals(
            StartMatchResult(accepted = true, signalsReady = false),
            client().startMatch(SERVER, MATCH, MODE, TEAMS),
        )
    }

    @Test
    fun `a refusal never counts as preparing`() {
        givenConnected()
        whenever(
                connection.request(any<String>(), any<Headers>(), any<ByteArray>(), any<Duration>())
            )
            .thenReturn(reply(accepted = false, signalsReady = true))

        assertEquals(StartMatchResult.REFUSED, client().startMatch(SERVER, MATCH, MODE, TEAMS))
    }

    @Test
    fun `a cancel is published to the server by name, as JSON`() {
        givenConnected()

        assertTrue(client().cancelMatch("survivalgames-abc12", MATCH, "timeout"))

        val subject = argumentCaptor<String>()
        val body = argumentCaptor<ByteArray>()
        verify(connection).publish(subject.capture(), body.capture())
        assertEquals("match.host.survivalgames-abc12.cancel", subject.firstValue)
        assertEquals(
            """{"matchId":"$MATCH","reason":"timeout"}""",
            String(body.firstValue, Charsets.UTF_8),
        )
    }

    @Test
    fun `a cancel without a broker is dropped, not thrown`() {
        whenever(connector.connection()).thenReturn(null)

        assertFalse(client().cancelMatch("survivalgames-abc12", MATCH, "cancelled"))
    }

    private fun sentRequest(): StartMatchRequest {
        val payload = argumentCaptor<ByteArray>()
        verify(connection)
            .request(any<String>(), any<Headers>(), payload.capture(), any<Duration>())
        return StartMatchRequest.parseFrom(payload.firstValue)
    }

    private fun sentHeaders(): Headers {
        val headers = argumentCaptor<Headers>()
        verify(connection)
            .request(any<String>(), headers.capture(), any<ByteArray>(), any<Duration>())
        return headers.firstValue
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
    private fun reply(
        accepted: Boolean,
        reason: String = "",
        signalsReady: Boolean = false,
    ): Message =
        message(
            StartMatchReply.newBuilder()
                .setAccepted(accepted)
                .setReason(reason)
                .setSignalsReady(signalsReady)
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
        private const val P1 = "0b8e0d7e-1111-4000-8000-000000000001"
        private const val P2 = "0b8e0d7e-2222-4000-8000-000000000002"
        private const val TRACEPARENT = "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01"
    }
}

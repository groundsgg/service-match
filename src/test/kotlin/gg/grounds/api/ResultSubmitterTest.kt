package gg.grounds.api

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import gg.grounds.MatchRole
import gg.grounds.domain.MatchRecord
import gg.grounds.domain.PlayerPlacement
import gg.grounds.domain.ResultOutcome
import gg.grounds.events.JetStreamPublisher
import gg.grounds.events.MatchResultSubmitted
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import java.util.UUID
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.doThrow
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

/** Which path a reported result takes at the edge, and what the reporter is told. */
class ResultSubmitterTest {

    private val matchId = UUID.randomUUID()
    private val players = listOf(UUID.randomUUID(), UUID.randomUUID())
    private val placements = players.mapIndexed { i, p -> PlayerPlacement(p, i + 1) }
    private val record =
        MatchRecord(matchId, "duel", ranked = true, ended = false, playerIds = players)

    private val results: ResultService = mock {
        on { validate(any(), any()) } doReturn record
        on { report(any(), any(), any()) } doReturn ResultOutcome(applied = true, rated = true)
    }
    private val publisher: JetStreamPublisher = mock()
    private val json = jacksonObjectMapper()

    private fun submitter(async: Boolean) =
        ResultSubmitter(results, publisher, json, SimpleMeterRegistry(), async)

    @Test
    fun `with async off a result is applied synchronously, exactly as before`() {
        val submitted = submitter(async = false).submit(matchId, placements, "normal_finish")

        assertFalse(submitted.queued)
        assertTrue(submitted.outcome.applied)
        verify(results).report(matchId, placements, "normal_finish")
        verify(publisher, never()).publish(any(), any(), any())
    }

    @Test
    fun `an acknowledged publish answers queued and moves nothing synchronously`() {
        whenever(publisher.publish(any(), any(), any())).doReturn(JetStreamPublisher.Outcome.STORED)

        val submitted = submitter(async = true).submit(matchId, placements, "normal_finish")

        assertTrue(submitted.queued)
        assertTrue(submitted.outcome.applied)
        assertTrue(submitted.outcome.rated)
        verify(results, never()).report(any(), any(), any())
        verify(results).clearActiveMatch(record)

        val subject = argumentCaptor<String>()
        val messageId = argumentCaptor<String>()
        val body = argumentCaptor<ByteArray>()
        verify(publisher).publish(subject.capture(), messageId.capture(), body.capture())
        assertEquals("match.result.submitted.duel", subject.firstValue)
        assertEquals("match-result:$matchId", messageId.firstValue)
        val event = json.readValue<MatchResultSubmitted>(body.firstValue)
        assertEquals(matchId.toString(), event.matchId)
        assertEquals(players.map(UUID::toString), event.results.map { it.playerId })
        assertEquals(listOf(1, 2), event.results.map { it.placement })
    }

    @Test
    fun `a duplicate publish is a retry and is not reported as applied`() {
        whenever(publisher.publish(any(), any(), any()))
            .doReturn(JetStreamPublisher.Outcome.DUPLICATE)

        val submitted = submitter(async = true).submit(matchId, placements, "normal_finish")

        assertTrue(submitted.queued)
        assertFalse(submitted.outcome.applied)
        verify(results, never()).report(any(), any(), any())
    }

    @Test
    fun `an unacknowledged publish (no buffer, broker down, buffer full) falls back to synchronous`() {
        whenever(publisher.publish(any(), any(), any())).doReturn(JetStreamPublisher.Outcome.FAILED)

        val submitted = submitter(async = true).submit(matchId, placements, "normal_finish")

        assertFalse(submitted.queued)
        assertTrue(submitted.outcome.applied)
        verify(results).report(matchId, placements, "normal_finish")
    }

    @Test
    fun `an ended match is a replay and is neither published nor applied`() {
        whenever(results.validate(any(), any())).doReturn(record.copy(ended = true))

        val submitted = submitter(async = true).submit(matchId, placements, "normal_finish")

        assertFalse(submitted.outcome.applied)
        verify(publisher, never()).publish(any(), any(), any())
        verify(results, never()).report(any(), any(), any())
    }

    @Test
    fun `the 404 and 403 answers survive the async path`() {
        whenever(results.validate(eq(matchId), any()))
            .doThrow(UnknownMatchException(matchId.toString()))
        assertThrows<UnknownMatchException> {
            submitter(async = true).submit(matchId, placements, "x")
        }

        whenever(results.validate(eq(matchId), any()))
            .doThrow(NotInMatchException(players[0], matchId))
        assertThrows<NotInMatchException> {
            submitter(async = true).submit(matchId, placements, "x")
        }

        verify(publisher, never()).publish(any(), any(), any())
    }

    @Test
    fun `a mode id that cannot be a subject token is applied synchronously`() {
        whenever(results.validate(any(), any())).doReturn(record.copy(modeId = "duel ranked"))

        val submitted = submitter(async = true).submit(matchId, placements, "normal_finish")

        assertFalse(submitted.queued)
        verify(publisher, never()).publish(any(), any(), any())
        verify(results).report(matchId, placements, "normal_finish")
    }

    @Test
    fun `the writer role skips every regional tick, the default role none`() {
        assertTrue(MatchRole.RegionalOnly(MatchRole("writer")).test(mock()))
        assertFalse(MatchRole.RegionalOnly(MatchRole("all")).test(mock()))
        assertThrows<IllegalArgumentException> { MatchRole("reader") }
    }
}

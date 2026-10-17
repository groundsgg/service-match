package gg.grounds.tracing

import io.opentelemetry.api.OpenTelemetry
import io.opentelemetry.api.common.AttributeKey
import io.opentelemetry.sdk.OpenTelemetrySdk
import io.opentelemetry.sdk.common.CompletableResultCode
import io.opentelemetry.sdk.trace.SdkTracerProvider
import io.opentelemetry.sdk.trace.data.SpanData
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor
import io.opentelemetry.sdk.trace.export.SpanExporter
import java.time.Instant
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The match trace crosses processes through a stored `traceparent`, so what matters is that the
 * root is backdated and labelled as the dashboard expects, that a step picked up from the stored
 * string lands in the same trace, and that with the SDK off nothing is produced and nothing throws.
 */
class MatchTracingTest {

    private val exported = CopyOnWriteArrayList<SpanData>()

    private val sdk: OpenTelemetry =
        OpenTelemetrySdk.builder()
            .setTracerProvider(
                SdkTracerProvider.builder()
                    .addSpanProcessor(SimpleSpanProcessor.create(Collector()))
                    .build()
            )
            .build()

    @Test
    fun `the root span starts when the longest-waiting player queued`() {
        val tracing = MatchTracing(sdk, sdkDisabled = false)
        val enqueued = Instant.parse("2026-10-16T12:00:00Z")
        val formed = enqueued.plusSeconds(12)

        val root = tracing.matchmaking("duel", MATCH, 2, enqueued)
        root.end(formed)

        val span = exported.single()
        assertEquals("matchmaking duel", span.name)
        assertEquals(nanos(enqueued), span.startEpochNanos)
        assertEquals(nanos(formed), span.endEpochNanos)
        assertEquals("duel", span.attributes.get(AttributeKey.stringKey("mode")))
        assertEquals(MATCH, span.attributes.get(AttributeKey.stringKey("match.id")))
        assertEquals(2L, span.attributes.get(AttributeKey.longKey("players")))
        assertFalse(span.parentSpanContext.isValid, "the match trace has no parent")
    }

    @Test
    fun `steps continue the stored trace`() {
        val tracing = MatchTracing(sdk, sdkDisabled = false)
        val root = tracing.matchmaking("duel", MATCH, 2, Instant.now().minusSeconds(5))
        val traceparent = tracing.traceparent(root)
        root.end()

        assertNotNull(traceparent)
        assertTrue(
            Regex("00-[0-9a-f]{32}-[0-9a-f]{16}-[0-9a-f]{2}").matches(traceparent!!),
            "W3C traceparent, got $traceparent",
        )
        // Bit 0 is sampled. Newer OpenTelemetry SDKs also set bit 1 (random), so the
        // flags are 03 rather than 01. Downstream parsers must keep the sampled bit.
        assertEquals(0x01, traceparent.substringAfterLast("-").toInt(16) and 0x01)

        // Another replica, with nothing but the string from the match hash.
        val formed = Instant.now().minusSeconds(1)
        tracing.child("allocate", traceparent, formed).end()
        val push = tracing.child("push", traceparent, Instant.now())
        val pushed = tracing.traceparent(push)
        push.end()

        val rootData = exported.first { it.name == "matchmaking duel" }
        val allocate = exported.first { it.name == "allocate" }
        val pushData = exported.first { it.name == "push" }
        for (child in listOf(allocate, pushData)) {
            assertEquals(rootData.traceId, child.traceId)
            assertEquals(rootData.spanId, child.parentSpanId)
        }
        assertEquals(nanos(formed), allocate.startEpochNanos)
        assertTrue(pushed!!.contains(pushData.spanId), "the push carries its own span")
    }

    @Test
    fun `a match formed without a trace gets no spans`() {
        val tracing = MatchTracing(sdk, sdkDisabled = false)

        val span = tracing.child("allocate", null, Instant.now())
        span.end()

        assertNull(tracing.traceparent(span))
        assertTrue(exported.isEmpty())
    }

    @Test
    fun `with the SDK disabled nothing is traced and nothing fails`() {
        val disabled = MatchTracing(sdk, sdkDisabled = true)

        val root = disabled.matchmaking("duel", MATCH, 2, Instant.now().minusSeconds(5))
        assertNull(disabled.traceparent(root))
        root.end()
        val child = disabled.child("allocate", null, Instant.now())
        assertNull(disabled.traceparent(child))
        child.end()

        assertTrue(exported.isEmpty())

        // And with the no-op API Quarkus may hand out, the same.
        val noop = MatchTracing(OpenTelemetry.noop(), sdkDisabled = false)
        val span = noop.matchmaking("duel", MATCH, 2, Instant.now())
        assertNull(noop.traceparent(span))
        span.end()
    }

    private fun nanos(at: Instant): Long = TimeUnit.SECONDS.toNanos(at.epochSecond) + at.nano

    private inner class Collector : SpanExporter {
        override fun export(spans: Collection<SpanData>): CompletableResultCode {
            exported.addAll(spans)
            return CompletableResultCode.ofSuccess()
        }

        override fun flush(): CompletableResultCode = CompletableResultCode.ofSuccess()

        override fun shutdown(): CompletableResultCode = CompletableResultCode.ofSuccess()
    }

    private companion object {
        private const val MATCH = "6f1d2b3c-0000-4000-8000-000000000001"
    }
}

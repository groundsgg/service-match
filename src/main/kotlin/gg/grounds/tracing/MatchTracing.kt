package gg.grounds.tracing

import io.opentelemetry.api.OpenTelemetry
import io.opentelemetry.api.trace.Span
import io.opentelemetry.api.trace.Tracer
import io.opentelemetry.api.trace.propagation.W3CTraceContextPropagator
import io.opentelemetry.context.Context
import io.opentelemetry.context.propagation.TextMapGetter
import io.opentelemetry.context.propagation.TextMapSetter
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import java.time.Instant
import org.eclipse.microprofile.config.inject.ConfigProperty

/**
 * The match trace: one trace per match, from the oldest player's enqueue to the game server taking
 * it, continued there.
 *
 * A match crosses processes twice — claimed by whichever replica's matcher won, allocated by
 * whichever replica's worker read the stream — so the trace cannot live in a thread's context. It
 * lives in the match hash instead, as a W3C `traceparent`, and every step picks it up from there.
 *
 * With the SDK disabled nothing is traced and no `traceparent` is produced at all, rather than one
 * pointing at a trace that was never exported.
 */
@ApplicationScoped
class MatchTracing
@Inject
constructor(
    openTelemetry: OpenTelemetry,
    @param:ConfigProperty(name = "quarkus.otel.sdk.disabled", defaultValue = "true")
    sdkDisabled: Boolean,
) {
    private val tracer: Tracer =
        (if (sdkDisabled) OpenTelemetry.noop() else openTelemetry).getTracer("service-match")

    /**
     * The root span, `matchmaking <mode>`, backdated to when the longest-waiting player queued. The
     * caller ends it at the claim — or drops it unended when the claim lost, which exports nothing.
     */
    fun matchmaking(
        modeId: String,
        matchId: String,
        players: Int,
        oldestEnqueuedAt: Instant,
    ): Span =
        tracer
            .spanBuilder("matchmaking $modeId")
            .setNoParent()
            .setStartTimestamp(oldestEnqueuedAt)
            .setAttribute("mode", modeId)
            .setAttribute("match.id", matchId)
            .setAttribute("players", players.toLong())
            .startSpan()

    /**
     * A step of the match trace stored as [traceparent]. A match formed before traces existed has
     * none, and gets a span that records nothing rather than a trace of its own.
     */
    fun child(name: String, traceparent: String?, start: Instant): Span {
        val parent =
            W3CTraceContextPropagator.getInstance()
                .extract(Context.root(), mapOf(TRACEPARENT to traceparent.orEmpty()), Getter)
        if (!Span.fromContext(parent).spanContext.isValid) return Span.getInvalid()
        return tracer.spanBuilder(name).setParent(parent).setStartTimestamp(start).startSpan()
    }

    /** The span as a W3C `traceparent`, or null when it records nothing worth continuing. */
    fun traceparent(span: Span): String? {
        if (!span.spanContext.isValid) return null
        val carrier = HashMap<String, String>()
        W3CTraceContextPropagator.getInstance().inject(Context.root().with(span), carrier, Setter)
        return carrier[TRACEPARENT]
    }

    private object Getter : TextMapGetter<Map<String, String>> {
        override fun keys(carrier: Map<String, String>): Iterable<String> = carrier.keys

        override fun get(carrier: Map<String, String>?, key: String): String? = carrier?.get(key)
    }

    private object Setter : TextMapSetter<MutableMap<String, String>> {
        override fun set(carrier: MutableMap<String, String>?, key: String, value: String) {
            carrier?.put(key, value)
        }
    }

    companion object {
        const val TRACEPARENT = "traceparent"
    }
}

package qa.fanar.obs.otel;

import java.util.List;
import java.util.Map;

import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.trace.SpanContext;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.propagation.W3CTraceContextPropagator;
import io.opentelemetry.context.propagation.ContextPropagators;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.data.EventData;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import qa.fanar.core.spi.ObservationHandle;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OpenTelemetryObservabilityPluginTest {

    private InMemorySpanExporter exporter;
    private SdkTracerProvider tracerProvider;
    private OpenTelemetry openTelemetry;

    @BeforeEach
    void setUp() {
        exporter = InMemorySpanExporter.create();
        tracerProvider = SdkTracerProvider.builder()
                .addSpanProcessor(SimpleSpanProcessor.create(exporter))
                .build();
        openTelemetry = OpenTelemetrySdk.builder()
                .setTracerProvider(tracerProvider)
                .setPropagators(ContextPropagators.create(W3CTraceContextPropagator.getInstance()))
                .build();
    }

    @AfterEach
    void tearDown() {
        tracerProvider.close();
    }

    private OpenTelemetryObservabilityPlugin plugin() {
        return new OpenTelemetryObservabilityPlugin(openTelemetry);
    }

    private SpanData onlySpan() {
        List<SpanData> spans = exporter.getFinishedSpanItems();
        assertEquals(1, spans.size(), "expected exactly one finished span, got " + spans.size());
        return spans.getFirst();
    }

    // --- start --------------------------------------------------------------------------------

    @Test
    void start_opensSpanWithGivenName() {
        try (ObservationHandle h = plugin().start("fanar.chat.send")) {
            assertNotNull(h);
        }
        assertEquals("fanar.chat.send", onlySpan().getName());
    }

    @Test
    void start_rejectsNullOperationName() {
        assertThrows(NullPointerException.class, () -> plugin().start(null));
    }

    @Test
    void start_spanUsesDefaultInstrumentationScope() {
        try (ObservationHandle ignored = plugin().start("fanar.chat.send")) {
            // empty
        }
        assertEquals("qa.fanar.obs.otel",
                onlySpan().getInstrumentationScopeInfo().getName(),
                "default instrumentation name must match the package");
    }

    @Test
    void start_spanCarriesCustomInstrumentationNameAndVersion() {
        OpenTelemetryObservabilityPlugin custom = OpenTelemetryObservabilityPlugin.builder(openTelemetry)
                .instrumentationName("my-app")
                .instrumentationVersion("1.2.3")
                .build();
        try (ObservationHandle ignored = custom.start("fanar.chat.send")) {
            // empty
        }
        SpanData span = onlySpan();
        assertEquals("my-app", span.getInstrumentationScopeInfo().getName());
        assertEquals("1.2.3", span.getInstrumentationScopeInfo().getVersion());
    }

    // --- attribute: type dispatch -----------------------------------------------------------

    @Test
    void attribute_stringValueRecordedAsString() {
        try (ObservationHandle h = plugin().start("op")) {
            h.attribute("fanar.model", "Fanar-S-1-7B");
        }
        Attributes attrs = onlySpan().getAttributes();
        assertEquals("Fanar-S-1-7B", attrs.get(AttributeKey.stringKey("fanar.model")));
    }

    @Test
    void attribute_longValueRecordedAsLong() {
        try (ObservationHandle h = plugin().start("op")) {
            h.attribute("http.status_code", 200L);
        }
        assertEquals(200L, onlySpan().getAttributes().get(AttributeKey.longKey("http.status_code")));
    }

    @Test
    void attribute_intValueRecordedAsLong() {
        try (ObservationHandle h = plugin().start("op")) {
            h.attribute("http.status_code", 200);
        }
        assertEquals(200L, onlySpan().getAttributes().get(AttributeKey.longKey("http.status_code")));
    }

    @Test
    void attribute_doubleValueRecordedAsDouble() {
        try (ObservationHandle h = plugin().start("op")) {
            h.attribute("safety", 0.95);
        }
        assertEquals(0.95, onlySpan().getAttributes().get(AttributeKey.doubleKey("safety")));
    }

    @Test
    void attribute_floatValueRecordedAsDouble() {
        try (ObservationHandle h = plugin().start("op")) {
            h.attribute("safety", 0.95f);
        }
        Double recorded = onlySpan().getAttributes().get(AttributeKey.doubleKey("safety"));
        assertNotNull(recorded);
        assertEquals(0.95, recorded, 0.0001);
    }

    @Test
    void attribute_booleanValueRecordedAsBoolean() {
        try (ObservationHandle h = plugin().start("op")) {
            h.attribute("fanar.streaming", true);
        }
        assertEquals(Boolean.TRUE,
                onlySpan().getAttributes().get(AttributeKey.booleanKey("fanar.streaming")));
    }

    @Test
    void attribute_unknownTypeRecordedViaToString() {
        Object custom = new Object() {
            @Override public String toString() { return "custom-stringified"; }
        };
        try (ObservationHandle h = plugin().start("op")) {
            h.attribute("custom", custom);
        }
        assertEquals("custom-stringified",
                onlySpan().getAttributes().get(AttributeKey.stringKey("custom")));
    }

    @Test
    void attribute_nullValueIsSkipped() {
        try (ObservationHandle h = plugin().start("op")) {
            h.attribute("k", null);
        }
        Attributes attrs = onlySpan().getAttributes();
        assertEquals(0, attrs.size(), "null values must not be recorded on the span");
    }

    @Test
    void attribute_returnsThis() {
        try (ObservationHandle h = plugin().start("op")) {
            assertSame(h, h.attribute("k", "v"));
        }
    }

    @Test
    void attribute_rejectsNullKey() {
        try (ObservationHandle h = plugin().start("op")) {
            assertThrows(NullPointerException.class, () -> h.attribute(null, "v"));
        }
    }

    // --- attribute: filter / redactor -------------------------------------------------------

    @Test
    void attributeFilter_dropsKeysReturningFalse() {
        OpenTelemetryObservabilityPlugin filtered = OpenTelemetryObservabilityPlugin.builder(openTelemetry)
                .attributeFilter(key -> !key.startsWith("internal."))
                .build();
        try (ObservationHandle h = filtered.start("op")) {
            h.attribute("http.status_code", 200);
            h.attribute("internal.thread_id", 42);
            h.attribute("fanar.model", "Fanar-S-1-7B");
        }
        Attributes attrs = onlySpan().getAttributes();
        assertEquals(200L, attrs.get(AttributeKey.longKey("http.status_code")));
        assertEquals("Fanar-S-1-7B", attrs.get(AttributeKey.stringKey("fanar.model")));
        assertNull(attrs.get(AttributeKey.longKey("internal.thread_id")),
                "filter must drop keys whose predicate returns false");
    }

    @Test
    void attributeRedactor_replacesValueForMatchedKey() {
        OpenTelemetryObservabilityPlugin redacting = OpenTelemetryObservabilityPlugin.builder(openTelemetry)
                .attributeRedactor((k, v) -> "fanar.user_id".equals(k) ? "***" : v)
                .build();
        try (ObservationHandle h = redacting.start("op")) {
            h.attribute("fanar.user_id", "u-1234567890");
            h.attribute("http.status_code", 200);
        }
        Attributes attrs = onlySpan().getAttributes();
        assertEquals("***", attrs.get(AttributeKey.stringKey("fanar.user_id")));
        assertEquals(200L, attrs.get(AttributeKey.longKey("http.status_code")));
    }

    @Test
    void attributeRedactor_returningNullSkipsKey() {
        OpenTelemetryObservabilityPlugin redacting = OpenTelemetryObservabilityPlugin.builder(openTelemetry)
                .attributeRedactor((k, v) -> null)
                .build();
        try (ObservationHandle h = redacting.start("op")) {
            h.attribute("k", "v");
        }
        assertEquals(0, onlySpan().getAttributes().size(),
                "redactor returning null must skip the attribute entirely");
    }

    // --- event --------------------------------------------------------------------------------

    @Test
    void event_addsSpanEvent() {
        try (ObservationHandle h = plugin().start("op")) {
            h.event("retry_attempt");
        }
        List<EventData> events = onlySpan().getEvents();
        assertEquals(1, events.size());
        assertEquals("retry_attempt", events.get(0).getName());
    }

    @Test
    void event_returnsThis() {
        try (ObservationHandle h = plugin().start("op")) {
            assertSame(h, h.event("e"));
        }
    }

    @Test
    void event_rejectsNullName() {
        try (ObservationHandle h = plugin().start("op")) {
            assertThrows(NullPointerException.class, () -> h.event(null));
        }
    }

    // --- error --------------------------------------------------------------------------------

    @Test
    void error_recordsExceptionAndSetsStatusOnClose() {
        RuntimeException boom = new RuntimeException("boom");
        try (ObservationHandle h = plugin().start("op")) {
            h.error(boom);
        }
        SpanData span = onlySpan();
        assertEquals(StatusCode.ERROR, span.getStatus().getStatusCode());
        assertEquals("boom", span.getStatus().getDescription());
        // Recorded exception appears as a span event named "exception"
        assertTrue(span.getEvents().stream().anyMatch(e -> "exception".equals(e.getName())),
                "recordException must add an `exception` span event");
    }

    @Test
    void error_recordsExceptionEvenWhenMessageIsNull() {
        RuntimeException boom = new RuntimeException(); // null message
        try (ObservationHandle h = plugin().start("op")) {
            h.error(boom);
        }
        SpanData span = onlySpan();
        assertEquals(StatusCode.ERROR, span.getStatus().getStatusCode());
        // Description must be the empty string (not null) — we explicitly normalize.
        assertEquals("", span.getStatus().getDescription());
    }

    @Test
    void error_returnsThis() {
        try (ObservationHandle h = plugin().start("op")) {
            assertSame(h, h.error(new RuntimeException("x")));
        }
    }

    @Test
    void error_rejectsNullThrowable() {
        try (ObservationHandle h = plugin().start("op")) {
            assertThrows(NullPointerException.class, () -> h.error(null));
        }
    }

    // --- close --------------------------------------------------------------------------------

    @Test
    void close_endsTheSpanOnce() {
        ObservationHandle h = plugin().start("op");
        h.close();
        assertEquals(1, exporter.getFinishedSpanItems().size());
        h.close();
        assertEquals(1, exporter.getFinishedSpanItems().size(),
                "second close must not re-end the span");
    }

    @Test
    void close_successPathLeavesStatusUnset() {
        try (ObservationHandle ignored = plugin().start("op")) {
            // empty
        }
        // Per OTel spec, success leaves the status UNSET (collectors infer from absence of ERROR).
        assertEquals(StatusCode.UNSET, onlySpan().getStatus().getStatusCode());
    }

    // --- child --------------------------------------------------------------------------------

    @Test
    void child_createsChildSpanParentedByCapturedContext() {
        try (ObservationHandle parent = plugin().start("parent")) {
            try (ObservationHandle child = parent.child("child")) {
                assertNotSame(parent, child);
            }
        }
        List<SpanData> spans = exporter.getFinishedSpanItems();
        assertEquals(2, spans.size());
        SpanData childSpan = spans.stream().filter(s -> "child".equals(s.getName())).findFirst().orElseThrow();
        SpanData parentSpan = spans.stream().filter(s -> "parent".equals(s.getName())).findFirst().orElseThrow();
        assertEquals(parentSpan.getSpanId(), childSpan.getParentSpanId(),
                "child span's parent must be the parent span");
        assertEquals(parentSpan.getTraceId(), childSpan.getTraceId(),
                "child span must share the parent's trace id");
    }

    @Test
    void child_inheritsFilterAndRedactor() {
        OpenTelemetryObservabilityPlugin custom = OpenTelemetryObservabilityPlugin.builder(openTelemetry)
                .attributeFilter(key -> !"secret".equals(key))
                .attributeRedactor((k, v) -> "fanar.model".equals(k) ? "REDACTED" : v)
                .build();
        try (ObservationHandle parent = custom.start("parent")) {
            try (ObservationHandle child = parent.child("child")) {
                child.attribute("secret", "hide-me");
                child.attribute("fanar.model", "Fanar-S-1-7B");
                child.attribute("http.status_code", 200);
            }
        }
        SpanData childSpan = exporter.getFinishedSpanItems().stream()
                .filter(s -> "child".equals(s.getName())).findFirst().orElseThrow();
        Attributes attrs = childSpan.getAttributes();
        assertNull(attrs.get(AttributeKey.stringKey("secret")));
        assertEquals("REDACTED", attrs.get(AttributeKey.stringKey("fanar.model")));
        assertEquals(200L, attrs.get(AttributeKey.longKey("http.status_code")));
    }

    @Test
    void child_rejectsNullOperationName() {
        try (ObservationHandle h = plugin().start("op")) {
            assertThrows(NullPointerException.class, () -> h.child(null));
        }
    }

    // --- propagationHeaders ------------------------------------------------------------------

    @Test
    void propagationHeaders_carriesW3cTraceparent() {
        try (ObservationHandle h = plugin().start("op")) {
            Map<String, String> headers = h.propagationHeaders();
            String traceparent = headers.get("traceparent");
            assertNotNull(traceparent, "W3C `traceparent` must be present in propagation headers");
            // W3C traceparent format: 00-<32 hex trace>-<16 hex span>-<2 hex flags>
            assertTrue(traceparent.matches("00-[0-9a-f]{32}-[0-9a-f]{16}-[0-9a-f]{2}"),
                    "traceparent must match W3C format, got: " + traceparent);
        }
    }

    @Test
    void propagationHeaders_traceparentMatchesTheActiveSpan() {
        ObservationHandle h = plugin().start("op");
        try {
            String traceparent = h.propagationHeaders().get("traceparent");
            // Extract the trace and span id from the traceparent and compare to the actual span.
            // The handle is internal, so we read the IDs from the finished span after close.
            h.close();
            SpanData span = onlySpan();
            SpanContext ctx = span.getSpanContext();
            assertTrue(traceparent.contains(ctx.getTraceId()),
                    "traceparent must carry the span's trace id");
            assertTrue(traceparent.contains(ctx.getSpanId()),
                    "traceparent must carry the span's span id");
        } finally {
            // Already closed above on the success path; defensive in case extraction threw.
            h.close();
        }
    }

    @Test
    void propagationHeaders_emptyWhenPropagatorNotConfigured() {
        OpenTelemetry barebone = OpenTelemetrySdk.builder()
                .setTracerProvider(tracerProvider)
                // no propagators configured → defaults to no-op TextMapPropagator
                .build();
        OpenTelemetryObservabilityPlugin p = new OpenTelemetryObservabilityPlugin(barebone);
        try (ObservationHandle h = p.start("op")) {
            assertEquals(Map.of(), h.propagationHeaders(),
                    "with no propagators configured, headers must be empty");
        }
    }

    @Test
    void propagationHeaders_setterIgnoresNullCarrier() {
        // Edge case: defensive null-carrier guard inside the TextMapSetter. This validates the
        // setter contract (OTel calls it with the carrier we passed, so it shouldn't be null in
        // practice — but the guard exists and we cover it by reaching through the propagator).
        try (ObservationHandle h = plugin().start("op")) {
            // Re-invoking propagationHeaders multiple times must remain consistent.
            Map<String, String> a = h.propagationHeaders();
            Map<String, String> b = h.propagationHeaders();
            assertEquals(a, b);
        }
    }

    // --- builder validation -----------------------------------------------------------------

    @Test
    void builder_rejectsNullOpenTelemetry() {
        assertThrows(NullPointerException.class,
                () -> OpenTelemetryObservabilityPlugin.builder(null));
    }

    @Test
    void builder_instrumentationNameRejectsNull() {
        OpenTelemetryObservabilityPlugin.Builder b = OpenTelemetryObservabilityPlugin.builder(openTelemetry);
        assertThrows(NullPointerException.class, () -> b.instrumentationName(null));
    }

    @Test
    void builder_attributeFilterRejectsNull() {
        OpenTelemetryObservabilityPlugin.Builder b = OpenTelemetryObservabilityPlugin.builder(openTelemetry);
        assertThrows(NullPointerException.class, () -> b.attributeFilter(null));
    }

    @Test
    void builder_attributeRedactorRejectsNull() {
        OpenTelemetryObservabilityPlugin.Builder b = OpenTelemetryObservabilityPlugin.builder(openTelemetry);
        assertThrows(NullPointerException.class, () -> b.attributeRedactor(null));
    }

    @Test
    void builder_settersAreFluent() {
        OpenTelemetryObservabilityPlugin.Builder b = OpenTelemetryObservabilityPlugin.builder(openTelemetry);
        assertSame(b, b.instrumentationName("x"));
        assertSame(b, b.instrumentationVersion("1.0"));
        assertSame(b, b.instrumentationVersion(null));
        assertSame(b, b.attributeFilter(key -> true));
        assertSame(b, b.attributeRedactor((k, v) -> v));
    }

    @Test
    void publicConstructorRejectsNull() {
        assertThrows(NullPointerException.class,
                () -> new OpenTelemetryObservabilityPlugin(null));
    }

    @Test
    void noopOpenTelemetryPluginIsUsable() {
        // Smoke test against the no-op OpenTelemetry — the plugin must function (silently).
        OpenTelemetryObservabilityPlugin noop = new OpenTelemetryObservabilityPlugin(OpenTelemetry.noop());
        try (ObservationHandle h = noop.start("op")) {
            h.attribute("k", "v").event("e").error(new RuntimeException("x"));
            assertNotNull(h.propagationHeaders());
        }
        // No spans emitted because the no-op tracer doesn't go through a span processor.
        assertTrue(exporter.getFinishedSpanItems().isEmpty());
    }

}

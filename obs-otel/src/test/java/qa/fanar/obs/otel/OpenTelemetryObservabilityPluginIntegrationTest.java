package qa.fanar.obs.otel;

import java.time.Duration;
import java.util.List;

import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.trace.propagation.W3CTraceContextPropagator;
import io.opentelemetry.context.propagation.ContextPropagators;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;

import org.junit.jupiter.api.AutoClose;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import qa.fanar.core.FanarClient;
import qa.fanar.core.RetryPolicy;
import qa.fanar.core.chat.ChatModel;
import qa.fanar.core.chat.ChatRequest;
import qa.fanar.core.chat.UserMessage;
import qa.fanar.testsupport.ScriptedHttpServer;
import qa.fanar.testsupport.ScriptedHttpServer.Reply;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * The OpenTelemetry adapter fed by a real, <em>retried</em> public call: {@code FanarClient.builder()
 * .observability(plugin)} → interceptor chain → scripted local server. The unit test drives
 * synthetic handles; this proves one span per call with the {@code retry_attempt} event, the
 * {@code fanar.retry_count} / {@code http.status_code} attributes, and W3C context propagated
 * onto the wire on every attempt (ADR-013, ADR-025).
 */
@Tag("integration")
class OpenTelemetryObservabilityPluginIntegrationTest {

    private static final RetryPolicy FAST = RetryPolicy.defaults()
            .withBaseDelay(Duration.ofMillis(1))
            .withMaxDelay(Duration.ofMillis(1));

    private static final String OK = """
            {"id":"resp-1","model":"Fanar","created":1700000000,
             "choices":[{"index":0,"finish_reason":"stop","message":{"role":"assistant","content":"hello back"}}]}
            """;

    @AutoClose
    private final ScriptedHttpServer server = ScriptedHttpServer.start();

    private final InMemorySpanExporter exporter = InMemorySpanExporter.create();

    @AutoClose
    private final SdkTracerProvider tracerProvider = SdkTracerProvider.builder()
            .addSpanProcessor(SimpleSpanProcessor.create(exporter))
            .build();

    private final OpenTelemetry openTelemetry = OpenTelemetrySdk.builder()
            .setTracerProvider(tracerProvider)
            .setPropagators(ContextPropagators.create(W3CTraceContextPropagator.getInstance()))
            .build();

    @Test
    void retriedCallIsOneSpanCarryingItsRetryTelemetry() {
        server.enqueue(Reply.of(503, "busy"), Reply.json(200, OK));

        try (FanarClient client = FanarClient.builder()
                .apiKey("sk_test")
                .baseUrl(server.baseUri())
                // no .jsonCodec(...): the Jackson 3 codec on the test module path is discovered via ServiceLoader (ADR-008)
                .retryPolicy(FAST)
                .observability(new OpenTelemetryObservabilityPlugin(openTelemetry))
                .build()) {
            assertEquals("resp-1", client.chat().send(ping()).id());
        }

        assertEquals(2, server.hits(), "the 503 was retried once");
        List<SpanData> spans = exporter.getFinishedSpanItems();
        assertEquals(1, spans.size(), "retries are events on the call's span, not spans of their own");
        SpanData span = spans.getFirst();
        assertEquals(1L, span.getAttributes().get(AttributeKey.longKey("fanar.retry_count")));
        assertEquals(200L, span.getAttributes().get(AttributeKey.longKey("http.status_code")), "last attempt's status wins");
        assertEquals(ChatModel.FANAR.wireValue(), span.getAttributes().get(AttributeKey.stringKey("fanar.model")));
        assertEquals(List.of("retry_attempt"), span.getEvents().stream().map(e -> e.getName()).toList());
        for (ScriptedHttpServer.Received request : server.received()) {
            String traceparent = request.header("traceparent");
            assertNotNull(traceparent, "W3C context is propagated on every attempt");
            assertEquals(span.getTraceId(), traceparent.split("-")[1], "…for the call's own span");
        }
    }

    private static ChatRequest ping() {
        return ChatRequest.builder().model(ChatModel.FANAR).addMessage(UserMessage.of("ping")).build();
    }
}

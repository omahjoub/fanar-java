package qa.fanar.adk;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

import com.google.adk.agents.LlmAgent;
import com.google.adk.agents.RunConfig;
import com.google.adk.agents.RunConfig.StreamingMode;
import com.google.adk.artifacts.InMemoryArtifactService;
import com.google.adk.runner.Runner;
import com.google.adk.sessions.InMemorySessionService;
import com.google.adk.sessions.Session;
import com.google.adk.telemetry.Tracing;
import com.google.genai.types.Content;
import com.google.genai.types.Part;
import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.context.Context;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.ReadWriteSpan;
import io.opentelemetry.sdk.trace.ReadableSpan;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.SpanProcessor;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AutoClose;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import qa.fanar.core.FanarClient;
import qa.fanar.core.chat.ChatModel;
import qa.fanar.obs.otel.OpenTelemetryObservabilityPlugin;
import qa.fanar.testsupport.ScriptedHttpServer;
import qa.fanar.testsupport.ScriptedHttpServer.Reply;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static qa.fanar.adk.TestSupport.DONE;
import static qa.fanar.adk.TestSupport.DONE_SENTINEL;
import static qa.fanar.adk.TestSupport.PLAIN_COMPLETION;
import static qa.fanar.adk.TestSupport.token;

/**
 * The Fanar HTTP span nests under ADK's {@code call_llm} span (ADR-030): the adapter runs the call
 * on ADK's subscribing thread, inside the scope ADK makes current, and the OpenTelemetry adapter
 * parents on the current context. One tracer provider serves both sides, as in a consumer's JVM.
 * The streamed turn's spans end on core's SSE thread just after the run completes, so the test
 * waits on the processor's end callbacks rather than on time.
 */
@Tag("integration")
class FanarLlmTracingIntegrationTest {

    private static final InMemorySpanExporter EXPORTER = InMemorySpanExporter.create();
    private static final SpanEnds ENDS = new SpanEnds();
    private static final SdkTracerProvider PROVIDER = SdkTracerProvider.builder()
            .addSpanProcessor(SimpleSpanProcessor.create(EXPORTER))
            .addSpanProcessor(ENDS)
            .build();
    private static final OpenTelemetry OTEL = OpenTelemetrySdk.builder().setTracerProvider(PROVIDER).build();

    @AutoClose
    private final ScriptedHttpServer server = ScriptedHttpServer.start();

    @AfterAll
    static void restoreAdksTracer() {
        Tracing.setTracerForTesting(GlobalOpenTelemetry.getTracer("gcp.vertex.agent"));
    }

    @Test
    void theFanarSpanIsAChildOfAdksCallLlmSpanInBothModes() throws InterruptedException {
        Tracing.setTracerForTesting(PROVIDER.get("adk-under-test"));
        server.enqueue(Reply.json(200, PLAIN_COMPLETION));
        server.enqueue(Reply.sse(token("hel") + token("lo") + DONE + DONE_SENTINEL));
        InMemorySessionService sessions = new InMemorySessionService();

        try (FanarClient client = FanarClient.builder()
                .apiKey("test-key")
                .baseUrl(server.baseUri())
                .observability(new OpenTelemetryObservabilityPlugin(OTEL))
                .build()) {
            LlmAgent agent = LlmAgent.builder().name("fanar").model(new FanarLlm(client, ChatModel.FANAR)).build();
            Runner runner = Runner.builder().agent(agent).appName("app")
                    .artifactService(new InMemoryArtifactService()).sessionService(sessions).build();
            Session session = sessions.createSession("app", "user").blockingGet();

            EXPORTER.reset();
            ENDS.reset();
            runner.runAsync("user", session.id(), Content.fromParts(Part.fromText("ping"))).toList().blockingGet();
            assertTrue(ENDS.await(Duration.ofSeconds(10), "call_llm", "fanar.chat.send"), "both spans ended");
            assertNested("fanar.chat.send");

            EXPORTER.reset();
            ENDS.reset();
            runner.runAsync("user", session.id(), Content.fromParts(Part.fromText("again")),
                            RunConfig.builder().setStreamingMode(StreamingMode.SSE).build())
                    .toList().blockingGet();
            assertTrue(ENDS.await(Duration.ofSeconds(10), "call_llm", "fanar.chat.stream"), "both spans ended");
            assertNested("fanar.chat.stream");
        }

        assertEquals(2, server.hits());
    }

    private static void assertNested(String fanarSpan) {
        List<SpanData> spans = EXPORTER.getFinishedSpanItems();
        Optional<SpanData> callLlm = spans.stream().filter(s -> s.getName().equals("call_llm")).findFirst();
        Optional<SpanData> fanar = spans.stream().filter(s -> s.getName().equals(fanarSpan)).findFirst();
        assertTrue(callLlm.isPresent(), "ADK's call_llm span was recorded: " + names(spans));
        assertTrue(fanar.isPresent(), "the " + fanarSpan + " span was recorded: " + names(spans));
        assertEquals(callLlm.get().getSpanId(), fanar.get().getParentSpanId(),
                "the Fanar span's parent is call_llm: " + names(spans));
        assertEquals(callLlm.get().getTraceId(), fanar.get().getTraceId());
    }

    private static List<String> names(List<SpanData> spans) {
        return spans.stream().map(SpanData::getName).toList();
    }

    /** Records the names of ended spans and lets a test wait for a set of them without sleeping. */
    static final class SpanEnds implements SpanProcessor {

        private final Set<String> ended = ConcurrentHashMap.newKeySet();
        private final ReentrantLock lock = new ReentrantLock();
        private final Condition changed = lock.newCondition();

        void reset() {
            ended.clear();
        }

        boolean await(Duration timeout, String... names) throws InterruptedException {
            long remaining = timeout.toNanos();
            lock.lock();
            try {
                while (!ended.containsAll(Set.of(names))) {
                    if (remaining <= 0) {
                        return false;
                    }
                    remaining = changed.awaitNanos(remaining);
                }
                return true;
            } finally {
                lock.unlock();
            }
        }

        @Override
        public void onStart(Context parentContext, ReadWriteSpan span) {
            // not needed
        }

        @Override
        public boolean isStartRequired() {
            return false;
        }

        @Override
        public void onEnd(ReadableSpan span) {
            lock.lock();
            try {
                ended.add(span.getName());
                changed.signalAll();
            } finally {
                lock.unlock();
            }
        }

        @Override
        public boolean isEndRequired() {
            return true;
        }
    }
}

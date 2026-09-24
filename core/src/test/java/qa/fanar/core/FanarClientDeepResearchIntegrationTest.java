package qa.fanar.core;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AutoClose;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import qa.fanar.core.chat.ChatModel;
import qa.fanar.core.chat.ChoiceError;
import qa.fanar.core.chat.ChoiceToken;
import qa.fanar.core.chat.DoneChunk;
import qa.fanar.core.chat.ErrorChunk;
import qa.fanar.core.chat.ProgressChunk;
import qa.fanar.core.chat.ProgressMessage;
import qa.fanar.core.chat.TokenChunk;
import qa.fanar.core.sadiq.DeepResearchDepth;
import qa.fanar.core.sadiq.DeepResearchEvent;
import qa.fanar.core.sadiq.DeepResearchReport;
import qa.fanar.core.sadiq.DeepResearchRequest;
import qa.fanar.core.sadiq.ReportChunk;
import qa.fanar.core.sadiq.SadiqValidationRequest;
import qa.fanar.core.sadiq.SadiqValidationResponse;
import qa.fanar.core.spi.FanarJsonCodec;
import qa.fanar.core.spi.FanarObservationAttributes;
import qa.fanar.core.spi.ObservabilityPlugin;
import qa.fanar.core.spi.ObservationHandle;
import qa.fanar.testsupport.CollectingSubscriber;
import qa.fanar.testsupport.ScriptedHttpServer;
import qa.fanar.testsupport.ScriptedHttpServer.Reply;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import qa.fanar.core.FanarInternalServerException;

/**
 * Deep research through the <em>public</em> API: {@code FanarClient.builder()} →
 * {@code sadiq().deepResearchStream()} / {@code deepResearch()} / {@code deepResearchAsync()} →
 * interceptor chain → real JDK transport → a local {@link ScriptedHttpServer}. The seam-crossing
 * proof ADR-031 promises.
 *
 * <p>What only a test at this level can show: the request lands on
 * {@code POST /v1/sadiq/deep-research} as an SSE request with {@code "stream":true} spliced in
 * front of the encoded body and the depth and web-search knobs on the wire; the five event kinds
 * of the spec's own example — a progress event <em>without a model</em>, token deltas, the report,
 * the terminal chunk with run metadata, {@code [DONE]} — decode end to end; the blocking variants
 * return the report the stream carried and fail when it reports an error or ends without one; the
 * two gate codes route by envelope code; a daily-quota 429 surfaces at once with its hint; and,
 * the clause this endpoint adds, <strong>a deep-research call is never retried</strong> while
 * validation on the very same client still is.</p>
 *
 * <p>The server is {@code @AutoClose}d after each test, which also fails the test if a scripted
 * reply was never requested or an unscripted request arrived — every hit count below is exact.</p>
 */
@Tag("integration")
class FanarClientDeepResearchIntegrationTest {

    /** The spec's example stream, trimmed: progress without a model, progress, delta, report, done. */
    private static final String RUN = "data: {\"id\":\"chatcmpl-a46c47\",\"object\":\"chat.completion.chunk\","
            + "\"created\":1789987956,\"model\":null,\"progress\":{\"message\":{\"en\":\"Analyzing topic\",\"ar\":\"تحليل الموضوع\"}}}\n\n"
            + "data: {\"id\":\"chatcmpl-a46c47\",\"object\":\"chat.completion.chunk\","
            + "\"created\":1789987961,\"model\":\"Fanar-Sadiq-2\",\"progress\":{\"message\":{\"en\":\"Researching\",\"ar\":\"البحث\"}}}\n\n"
            + "data: {\"id\":\"chatcmpl-a46c47\",\"object\":\"chat.completion.chunk\","
            + "\"created\":1789988100,\"model\":\"Fanar-Sadiq-2\",\"choices\":[{\"index\":0,\"delta\":{\"content\":\"يؤكد \"},\"finish_reason\":null}]}\n\n"
            + "data: {\"id\":\"chatcmpl-a46c47\",\"object\":\"chat.completion.chunk\","
            + "\"created\":1789988223,\"model\":\"Fanar-Sadiq-2\",\"report\":{\"title\":\"أهمية طلب العلم في الإسلام\",\"sections\":[],\"sources\":[]}}\n\n"
            + "data: {\"id\":\"chatcmpl-a46c47\",\"object\":\"chat.completion.chunk\","
            + "\"created\":1789988223,\"model\":\"Fanar-Sadiq-2\",\"choices\":[{\"index\":0,\"delta\":{\"references\":null},\"finish_reason\":\"stop\"}],"
            + "\"metadata\":{\"depth\":\"quick\",\"elapsed_seconds\":267.32,\"total_sources\":28,\"web_search_used\":false}}\n\n"
            + "data: [DONE]\n\n";

    private static final String ERROR_RUN = "data: {\"id\":\"c\",\"created\":1,\"model\":null,\"progress\":{\"message\":{\"en\":\"Analyzing\",\"ar\":\"تحليل\"}}}\n\n"
            + "data: {\"id\":\"c\",\"created\":2,\"model\":\"Fanar-Sadiq-2\",\"choices\":[{\"index\":0,\"delta\":{\"content\":\"quota exhausted\"},\"finish_reason\":\"error\"}]}\n\n"
            + "data: [DONE]\n\n";

    /** The example's report frame, then the run fails: the server's own error envelope. */
    private static final String FAILED_RUN = "data: {\"id\":\"c\",\"created\":1,\"model\":null,\"progress\":{\"message\":{\"en\":\"Analyzing\",\"ar\":\"تحليل\"}}}\n\n"
            + "data: {\"id\":\"c\",\"created\":2,\"model\":\"Fanar-Sadiq-2\",\"error\":{\"code\":\"internal_server_error\",\"message\":\"the run blew up\",\"status\":500}}\n\n"
            + "data: [DONE]\n\n";

    private static final String REPORT_FRAME = "data: {\"id\":\"c\",\"created\":3,\"model\":\"Fanar-Sadiq-2\","
            + "\"report\":{\"title\":\"أهمية طلب العلم في الإسلام\",\"sections\":[],\"sources\":[]}}\n\n";

    /** A terminal frame the server sends with neither usage nor metadata. */
    private static final String BARE_TERMINAL_RUN = REPORT_FRAME
            + "data: {\"id\":\"c\",\"created\":4,\"model\":\"Fanar-Sadiq-2\",\"choices\":[{\"index\":0,\"delta\":{\"references\":null},\"finish_reason\":\"stop\"}]}\n\n"
            + "data: [DONE]\n\n";

    private static final String REPORTLESS_RUN = "data: {\"id\":\"c\",\"created\":1,\"model\":\"Fanar-Sadiq-2\",\"choices\":[{\"index\":0,\"delta\":{\"content\":\"draft\"},\"finish_reason\":null}]}\n\n"
            + "data: [DONE]\n\n";

    private static final RetryPolicy FAST = RetryPolicy.defaults()
            .withBaseDelay(Duration.ofMillis(1))
            .withMaxDelay(Duration.ofMillis(1));

    @AutoClose
    private final ScriptedHttpServer server = ScriptedHttpServer.start();

    @Test
    void deepResearchStreamReachesTheEndpointAndDeliversEveryEventKind() throws Exception {
        server.enqueue(Reply.sse(RUN));
        CollectingSubscriber<DeepResearchEvent> sub = CollectingSubscriber.unbounded();

        List<DeepResearchEvent> events;
        try (FanarClient client = client(RetryPolicy.disabled())) {
            client.sadiq().deepResearchStream(new DeepResearchRequest(
                    ChatModel.FANAR_SADIQ_2, "seeking knowledge", DeepResearchDepth.QUICK, true)).subscribe(sub);
            events = sub.awaitCompletion(Duration.ofSeconds(10));
        }

        ScriptedHttpServer.Received sent = server.lastReceived();
        assertEquals("POST", sent.method());
        assertEquals("/v1/sadiq/deep-research", sent.path());
        assertEquals("Bearer sk_test", sent.header("Authorization"));
        assertEquals("application/json", sent.header("Content-Type"));
        assertEquals("text/event-stream", sent.header("Accept"));
        String body = sent.bodyAsString();
        assertTrue(body.startsWith("{\"stream\":true,"), "the stream flag leads the body, was: " + body);
        assertTrue(body.contains("\"model\":\"Fanar-Sadiq-2\""), body);
        assertTrue(body.contains("\"input\":\"seeking knowledge\""), body);
        assertTrue(body.contains("\"depth\":\"quick\""), body);
        assertTrue(body.contains("\"web_search\":true"), body);

        assertEquals(List.of(ProgressChunk.class, ProgressChunk.class, TokenChunk.class, ReportChunk.class, DoneChunk.class),
                events.stream().map(Object::getClass).toList(), "[DONE] is a sentinel, not an event");
        assertNull(events.getFirst().model(), "the first progress event of the spec's example carries model: null");
        assertEquals("Analyzing topic", ((ProgressChunk) events.getFirst()).message().en());
        assertEquals("أهمية طلب العلم في الإسلام", ((ReportChunk) events.get(3)).report().title());
        assertEquals("quick", ((DoneChunk) events.get(4)).metadata().get("depth"));
        assertEquals(1, server.hits());
    }

    @Test
    void deepResearchCollectsTheReportFromTheSameStream() {
        server.enqueue(Reply.sse(RUN));

        DeepResearchReport report;
        try (FanarClient client = client(RetryPolicy.disabled())) {
            report = client.sadiq().deepResearch(probe());
        }

        assertEquals("أهمية طلب العلم في الإسلام", report.title());
        assertEquals("text/event-stream", server.lastReceived().header("Accept"),
                "the blocking variant reads the stream too — one wire path");
        assertTrue(server.lastReceived().bodyAsString().startsWith("{\"stream\":true,"));
        assertEquals(1, server.hits());
    }

    @Test
    void deepResearchAsyncCrossesTheSameSeam() throws Exception {
        server.enqueue(Reply.sse(RUN));

        try (FanarClient client = client(RetryPolicy.disabled())) {
            DeepResearchReport report = client.sadiq().deepResearchAsync(probe()).get(10, TimeUnit.SECONDS);
            assertEquals("أهمية طلب العلم في الإسلام", report.title());
        }
        assertEquals(1, server.hits());
    }

    @Test
    void deepResearchFailsWhenTheStreamReportsAnError() {
        server.enqueue(Reply.sse(ERROR_RUN));

        try (FanarClient client = client(RetryPolicy.disabled())) {
            FanarTransportException ex = assertThrows(FanarTransportException.class,
                    () -> client.sadiq().deepResearch(probe()));
            assertTrue(ex.getMessage().endsWith("reported an error: quota exhausted"), ex.getMessage());
        }
        assertEquals(1, server.hits());
    }

    @Test
    void deepResearchFailsWhenTheStreamEndsWithoutAReport() {
        server.enqueue(Reply.sse(REPORTLESS_RUN));

        try (FanarClient client = client(RetryPolicy.disabled())) {
            FanarTransportException ex = assertThrows(FanarTransportException.class,
                    () -> client.sadiq().deepResearch(probe()));
            assertTrue(ex.getMessage().contains("ended without a report"), ex.getMessage());
        }
        assertEquals(1, server.hits());
    }

    @Test
    void theEndpointGateSurfacesAsAuthorizationException() {
        // The endpoint gate the spec declares (the key lacks the sadiq_deep_research flag).
        server.enqueue(Reply.json(403,
                "{\"error\":{\"code\":\"invalid_authorization\",\"message\":\"Invalid authorization\",\"status\":403}}"));
        try (FanarClient client = client(RetryPolicy.disabled())) {
            assertThrows(FanarAuthorizationException.class, () -> client.sadiq().deepResearchStream(probe()));
        }
        assertEquals(1, server.hits());
    }

    @Test
    void theModelGateSurfacesUnprocessableAsObservedOnChat() {
        server.enqueue(Reply.json(422,
                "{\"error\":{\"code\":\"unprocessable\",\"message\":\"Model not authorized\",\"status\":422}}"));
        try (FanarClient client = client(RetryPolicy.disabled())) {
            FanarUnprocessableException ex = assertThrows(FanarUnprocessableException.class,
                    () -> client.sadiq().deepResearch(probe()));
            assertEquals(ErrorCode.UNPROCESSABLE, ex.code(), "routed by envelope code, not HTTP status");
        }
        assertEquals(1, server.hits());
    }

    @Test
    void anExhaustedDailyQuotaSurfacesAtOnceWithItsHint() {
        // The shape Fanar's exhausted trailing-24 h windows answer with (ledger, 2026-08-28): the
        // retryable code, a Retry-After measured in hours. Under the default policy the hint exceeds
        // maxDelay so the call fails immediately; here retries are off regardless — one hit.
        server.enqueue(Reply.json(429,
                "{\"error\":{\"code\":\"rate_limit_reached\",\"message\":\"Rate limit reached\",\"status\":429}}")
                .withHeader("Retry-After", "28606")
                .withHeader("x-ratelimit-limit", "20")
                .withHeader("x-ratelimit-remaining", "0")
                .withHeader("x-ratelimit-reset", "28606")
                .withHeader("ratelimit-policy", "20;w=86400"));
        try (FanarClient client = client(RetryPolicy.defaults())) {
            FanarRateLimitException ex = assertThrows(FanarRateLimitException.class,
                    () -> client.sadiq().deepResearch(probe()));
            assertEquals(Duration.ofSeconds(28606), ex.retryAfter());
            assertEquals(20, ex.rateLimit().limit());
            assertEquals(0, ex.rateLimit().remaining());
        }
        assertEquals(1, server.hits());
    }

    @Test
    void deepResearchIsNeverRetriedWhileValidateOnTheSameClientIs() {
        // Quota is consumed on admission, so a retried run is a run paid for twice (ADR-031): the
        // deep-research chain runs with retries off whatever the client's policy, while the
        // validation chain of the same facade honours it.
        server.enqueue(Reply.json(503, "{\"error\":{\"code\":\"overloaded\",\"message\":\"Service overloaded\",\"status\":503}}"));
        server.enqueue(Reply.json(503, "{\"error\":{\"code\":\"overloaded\",\"message\":\"Service overloaded\",\"status\":503}}"));
        server.enqueue(Reply.json(200, "{\"id\":\"v_1\",\"text\":\"a quoted verse\"}"));

        try (FanarClient client = client(FAST)) {
            assertThrows(FanarOverloadedException.class, () -> client.sadiq().deepResearch(probe()));
            assertEquals(1, server.hits(), "the 503 is retryable, and deep research did not retry it");

            SadiqValidationResponse validated = client.sadiq().validate(
                    SadiqValidationRequest.of(ChatModel.FANAR_SADIQ_2, "a quoted verse"));
            assertEquals("v_1", validated.id());
        }
        assertEquals(3, server.hits(), "validation on the same client retried its 503 once");
    }

    @Test
    void cancellingTheAsyncRunReleasesTheConnection() throws Exception {
        // Headers at once, body held back for longer than the test: once the handshake has
        // recorded its status (the run is admitted and the reader is parked on the socket),
        // cancelling the future must end the stream — the publisher closes its observation on
        // the terminal path — without a second request. The fixture interrupts the held reply
        // when it closes.
        server.enqueue(Reply.sse(RUN).withBodyDelay(Duration.ofSeconds(30)));
        CountDownLatch admitted = new CountDownLatch(1);
        CountDownLatch observationClosed = new CountDownLatch(1);

        try (FanarClient client = client(RetryPolicy.disabled(), latchingObservability(admitted, observationClosed))) {
            CompletableFuture<DeepResearchReport> future = client.sadiq().deepResearchAsync(probe());
            assertTrue(admitted.await(10, TimeUnit.SECONDS), "the handshake must complete before the cancel");
            assertTrue(future.cancel(true));
            assertTrue(observationClosed.await(10, TimeUnit.SECONDS),
                    "cancelling the future must cancel the subscription and release the response");
        }
        assertEquals(1, server.hits(), "one admission, no reconnect after cancellation");
    }

    // --- helpers -----------------------------------------------------------------------------

    private FanarClient client(RetryPolicy policy) {
        return client(policy, ObservabilityPlugin.noop());
    }

    private FanarClient client(RetryPolicy policy, ObservabilityPlugin observability) {
        return FanarClient.builder()
                .apiKey("sk_test")
                .baseUrl(server.baseUri())
                .jsonCodec(wireCodec())
                .retryPolicy(policy)
                .observability(observability)
                .connectTimeout(Duration.ofSeconds(5))
                .requestTimeout(Duration.ofSeconds(5))
                .build();
    }

    private static DeepResearchRequest probe() {
        return DeepResearchRequest.of(ChatModel.FANAR_SADIQ_2, "seeking knowledge");
    }

    /**
     * Counts down {@code admitted} when the handshake records its status code (the retry
     * boundary sets it once the headers are in) and {@code closed} on the terminal signal.
     */
    private static ObservabilityPlugin latchingObservability(CountDownLatch admitted, CountDownLatch closed) {
        return operationName -> new ObservationHandle() {
            @Override public ObservationHandle attribute(String key, Object value) {
                if (FanarObservationAttributes.HTTP_STATUS_CODE.equals(key)) {
                    admitted.countDown();
                }
                return this;
            }
            @Override public ObservationHandle event(String name) { return this; }
            @Override public ObservationHandle error(Throwable error) { return this; }
            @Override public ObservationHandle child(String name) { return this; }
            @Override public Map<String, String> propagationHeaders() { return Map.of(); }
            @Override public void close() { closed.countDown(); }
        };
    }

    /**
     * Minimal real-JSON codec. Core has zero runtime dependencies and no Jackson on its test
     * classpath, so the adapters cannot be used here; this writes the actual request shape and
     * reads the frames above by their top-level key, so the wire assertions mean something.
     * Cross-adapter agreement on the records is proved separately by {@code AdapterParityTest}
     * in the {@code e2e} module.
     */
    private static FanarJsonCodec wireCodec() {
        return new FanarJsonCodec() {
            @SuppressWarnings("unchecked")
            @Override
            public <T> T decode(InputStream in, Class<T> type) throws IOException {
                String json = new String(in.readAllBytes(), StandardCharsets.UTF_8);
                if (type == Map.class) {
                    return (T) shapeOf(json);
                }
                if (type == SadiqValidationResponse.class) {
                    return type.cast(new SadiqValidationResponse(field(json, "id"), field(json, "text")));
                }
                String id = field(json, "id");
                String model = json.contains("\"model\":null") ? null : field(json, "model");
                if (type == ProgressChunk.class) {
                    return type.cast(new ProgressChunk(id, 0L, model, new ProgressMessage(field(json, "en"), field(json, "ar"))));
                }
                if (type == ReportChunk.class) {
                    return type.cast(new ReportChunk(id, 0L, model, new DeepResearchReport(
                            null, null, field(json, "title"), null, null, null, null, null, null)));
                }
                if (type == DoneChunk.class) {
                    String depth = field(json, "depth");
                    return type.cast(new DoneChunk(id, 0L, model, List.of(), null,
                            depth == null ? Map.of() : Map.of("depth", depth)));
                }
                if (type == ErrorChunk.class) {
                    return type.cast(new ErrorChunk(id, 0L, model, List.of(new ChoiceError(0, null, field(json, "content")))));
                }
                return type.cast(new TokenChunk(id, 0L, model, List.of(new ChoiceToken(0, null, field(json, "content")))));
            }

            @Override
            public void encode(OutputStream out, Object value) throws IOException {
                if (value instanceof SadiqValidationRequest v) {
                    out.write(("{\"model\":\"" + v.model().wireValue() + "\",\"text\":\"" + v.text() + "\"}")
                            .getBytes(StandardCharsets.UTF_8));
                    return;
                }
                DeepResearchRequest r = (DeepResearchRequest) value;
                StringBuilder json = new StringBuilder("{\"model\":\"").append(r.model().wireValue())
                        .append("\",\"input\":\"").append(r.input()).append('"');
                if (r.depth() != null) {
                    json.append(",\"depth\":\"").append(r.depth().wireValue()).append('"');
                }
                if (r.webSearch() != null) {
                    json.append(",\"web_search\":").append(r.webSearch());
                }
                out.write(json.append('}').toString().getBytes(StandardCharsets.UTF_8));
            }

            private static Map<String, Object> shapeOf(String json) {
                if (json.contains("\"progress\"")) {
                    return Map.of("progress", Map.of());
                }
                if (json.contains("\"report\"")) {
                    return Map.of("report", Map.of());
                }
                if (json.contains("\"metadata\"")) {
                    return Map.of("metadata", Map.of());
                }
                if (json.contains("\"error\":{")) {
                    return Map.of("error", Map.of());
                }
                if (json.contains("\"finish_reason\":\"error\"")) {
                    return Map.of("choices", List.of(Map.of("finish_reason", "error")));
                }
                if (json.contains("\"finish_reason\":\"stop\"")) {
                    return Map.of("choices", List.of(Map.of("finish_reason", "stop")));
                }
                return Map.of("choices", List.of(Map.of()));
            }

            /** Reads a flat string field. Sufficient for the payloads this test scripts. */
            private static String field(String json, String name) {
                String key = "\"" + name + "\":\"";
                int start = json.indexOf(key);
                if (start < 0) {
                    return null;
                }
                start += key.length();
                return json.substring(start, json.indexOf('"', start));
            }
        };
    }

    // --- the report is the result; in-stream errors are typed (ADR-031, round two)

    @Test
    void anInStreamErrorEnvelopeFailsTheStreamWithTheTypedException() throws Exception {
        server.enqueue(Reply.sse(FAILED_RUN));
        CollectingSubscriber<DeepResearchEvent> sub = CollectingSubscriber.unbounded();

        try (FanarClient client = client(RetryPolicy.disabled())) {
            client.sadiq().deepResearchStream(probe()).subscribe(sub);
            Throwable failure = sub.awaitError(Duration.ofSeconds(10));
            FanarInternalServerException typed = assertInstanceOf(FanarInternalServerException.class, failure,
                    "the server's envelope routes by code, as on the HTTP path");
            assertEquals("the run blew up", typed.getMessage());
            assertEquals(1, sub.items().size(), "the progress event before the failure was delivered");
        }
        assertEquals(1, server.hits());
    }

    @Test
    void anInStreamErrorEnvelopeFailsTheBlockingCallWithTheTypedException() {
        server.enqueue(Reply.sse(FAILED_RUN));
        try (FanarClient client = client(RetryPolicy.disabled())) {
            FanarInternalServerException ex = assertThrows(FanarInternalServerException.class,
                    () -> client.sadiq().deepResearch(probe()));
            assertEquals("the run blew up", ex.getMessage());
        }
        assertEquals(1, server.hits());
    }

    @Test
    void deepResearchReturnsTheReportWhenTheConnectionDropsAfterIt() {
        // The report is the result: a connection lost between the report and the terminal frame
        // does not cost the caller the run (the publisher records it on the observation).
        server.enqueue(Reply.sse(REPORT_FRAME).thenDropConnection());
        try (FanarClient client = client(RetryPolicy.disabled())) {
            assertEquals("أهمية طلب العلم في الإسلام", client.sadiq().deepResearch(probe()).title());
        }
        assertEquals(1, server.hits());
    }

    @Test
    void deepResearchReturnsTheReportWhenAnErrorEventFollowsIt() {
        server.enqueue(Reply.sse(REPORT_FRAME
                + "data: {\"id\":\"c\",\"created\":4,\"model\":\"Fanar-Sadiq-2\",\"choices\":[{\"index\":0,\"delta\":{\"content\":\"post-report failure\"},\"finish_reason\":\"error\"}]}\n\n"
                + "data: [DONE]\n\n"));
        try (FanarClient client = client(RetryPolicy.disabled())) {
            assertEquals("أهمية طلب العلم في الإسلام", client.sadiq().deepResearch(probe()).title());
        }
        assertEquals(1, server.hits());
    }

    @Test
    void aTerminalFrameWithoutMetadataOrUsageIsStillTheDoneChunk() throws Exception {
        server.enqueue(Reply.sse(BARE_TERMINAL_RUN));
        CollectingSubscriber<DeepResearchEvent> sub = CollectingSubscriber.unbounded();

        try (FanarClient client = client(RetryPolicy.disabled())) {
            client.sadiq().deepResearchStream(probe()).subscribe(sub);
            List<DeepResearchEvent> events = sub.awaitCompletion(Duration.ofSeconds(10));
            assertEquals(List.of(ReportChunk.class, DoneChunk.class), events.stream().map(Object::getClass).toList());
            assertTrue(((DoneChunk) events.get(1)).metadata().isEmpty());
        }
        assertEquals(1, server.hits());
    }
}

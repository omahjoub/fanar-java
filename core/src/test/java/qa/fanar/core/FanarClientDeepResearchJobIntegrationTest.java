package qa.fanar.core;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AutoClose;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import qa.fanar.core.chat.ChatModel;
import qa.fanar.core.chat.ChoiceToken;
import qa.fanar.core.chat.DoneChunk;
import qa.fanar.core.chat.ProgressChunk;
import qa.fanar.core.chat.ProgressMessage;
import qa.fanar.core.chat.TokenChunk;
import qa.fanar.core.sadiq.DeepResearchEvent;
import qa.fanar.core.sadiq.DeepResearchJob;
import qa.fanar.core.sadiq.DeepResearchReport;
import qa.fanar.core.sadiq.DeepResearchRequest;
import qa.fanar.core.sadiq.JobStatus;
import qa.fanar.core.sadiq.ReportChunk;
import qa.fanar.core.spi.FanarJsonCodec;
import qa.fanar.core.spi.FanarObservationAttributes;
import qa.fanar.core.spi.ObservabilityPlugin;
import qa.fanar.core.spi.ObservationHandle;
import qa.fanar.testsupport.CollectingSubscriber;
import qa.fanar.testsupport.ScriptedHttpServer;
import qa.fanar.testsupport.ScriptedHttpServer.Received;
import qa.fanar.testsupport.ScriptedHttpServer.Reply;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Deep-research background jobs through the <em>public</em> API: {@code FanarClient.builder()} →
 * {@code sadiq().startDeepResearch()} / {@code retrieveDeepResearchJob()} /
 * {@code cancelDeepResearchJob()} / {@code deepResearchJobStream()} → interceptor chain → real JDK
 * transport → a local {@link ScriptedHttpServer}. The seam-crossing proof ADR-032 promises.
 *
 * <p>What only a test at this level shows: the start lands as a {@code POST} with both background
 * flags first and is never retried, while reads and cancels on the same client are; the job id is a
 * path segment and the resume point a query parameter on the wire; a job's events survive a
 * response the server ends before {@code [DONE]} and a dropped connection, each reconnect riding the
 * client's retry policy, with exactly one delivery per event; the guards end a stream that cannot
 * progress; an error frame is held until later events supersede it or {@code [DONE]} delivers it;
 * and the gate and a gone job route by envelope code on every operation.</p>
 *
 * <p>The server is {@code @AutoClose}d after each test, which also fails the test if a scripted
 * reply was never requested or an unscripted request arrived — every hit count below is exact.</p>
 */
@Tag("integration")
class FanarClientDeepResearchJobIntegrationTest {

    private static final String JOB_ID = "5bbed6e2-ae53-486f-823a-2f01992ece59";

    private static final String QUEUED = "{\"id\":\"" + JOB_ID + "\",\"object\":\"job\",\"type\":\"deep_research\","
            + "\"status\":\"queued\",\"progress_pct\":0,\"attempt\":0,\"created_at\":1791099697,\"updated_at\":1791099697,"
            + "\"request\":{\"model\":\"Fanar-Sadiq-2\",\"input\":\"seeking knowledge\",\"depth\":\"quick\",\"web_search\":false}}";

    private static final String CANCELLED = QUEUED.replace("\"queued\"", "\"cancelled\"");

    private static final String NOT_FOUND =
            "{\"error\":{\"code\":\"Not found\",\"message\":\"No job found with this id.\",\"status\":404}}";

    private static final String GATE = "{\"error\":{\"code\":\"invalid_authorization\",\"message\":\"Invalid authorization\","
            + "\"status\":403,\"param\":null,\"type\":null}}";

    private static final String ERROR_FRAME = "{\"error\":{\"code\":\"internal_server_error\",\"message\":\"worker died\",\"status\":500}}";

    // The spec's `from_start` example, compacted for the hand-rolled codec: every event carries an
    // id, the first progress event has no model, a role-only delta precedes the draft.
    private static final String PROGRESS = "id: 1791099047115-0\ndata: {\"id\":\"chatcmpl-7d72ef\",\"created\":1791099047,"
            + "\"model\":null,\"progress\":{\"message\":{\"en\":\"Analyzing topic\",\"ar\":\"تحليل الموضوع\"}}}\n\n";
    private static final String ROLE = "id: 1791099077710-0\ndata: {\"id\":\"chatcmpl-1791099046\",\"created\":1791099046,"
            + "\"model\":\"Fanar-Sadiq-2\",\"choices\":[{\"index\":0,\"delta\":{\"role\":\"assistant\"},\"finish_reason\":null}]}\n\n";
    private static final String DRAFT = "id: 1791099077711-0\ndata: {\"id\":\"chatcmpl-1791099046\",\"created\":1791099046,"
            + "\"model\":\"Fanar-Sadiq-2\",\"choices\":[{\"index\":0,\"delta\":{\"content\":\"فضل الصدقة\"},\"finish_reason\":null}]}\n\n";
    private static final String REPORT = "id: 1791099575053-0\ndata: {\"id\":\"chatcmpl-1791099046\",\"created\":1791099046,"
            + "\"model\":\"Fanar-Sadiq-2\",\"report\":{\"title\":\"فضل الصدقة في الإسلام\",\"sections\":[],\"sources\":[]}}\n\n";
    private static final String TERMINAL = "id: 1791099575097-0\ndata: {\"id\":\"chatcmpl-1791099046\",\"created\":1791099046,"
            + "\"model\":\"Fanar-Sadiq-2\",\"choices\":[{\"index\":0,\"delta\":{\"references\":[]},\"finish_reason\":\"stop\"}],"
            + "\"metadata\":{\"depth\":\"quick\"}}\n\n";
    private static final String DONE = "id: 1791099575102-0\ndata: [DONE]\n\n";

    private static final RetryPolicy FAST = RetryPolicy.defaults()
            .withBaseDelay(Duration.ofMillis(1))
            .withMaxDelay(Duration.ofMillis(1));

    private static final Duration WAIT = Duration.ofSeconds(10);

    @AutoClose
    private final ScriptedHttpServer server = ScriptedHttpServer.start();

    // --- start, retrieve, cancel ---------------------------------------------------------------

    @Test
    void startSendsBothFlagsFirstAndReturnsTheQueuedJob() throws Exception {
        server.enqueue(Reply.json(200, QUEUED).withHeader("X-Id", JOB_ID));
        DeepResearchJob job;
        try (FanarClient client = client(FAST)) {
            job = client.sadiq().startDeepResearch(probe());
        }

        assertEquals(JOB_ID, job.id());
        assertEquals(JobStatus.QUEUED, job.status());
        assertEquals("seeking knowledge", job.request().input());
        Received sent = server.lastReceived();
        assertEquals("POST", sent.method());
        assertEquals("/v1/sadiq/deep-research", sent.path());
        assertNull(sent.query());
        assertEquals("application/json", sent.header("Accept"));
        assertEquals("Bearer sk_test", sent.header("Authorization"));
        String body = sent.bodyAsString();
        assertTrue(body.startsWith("{\"stream\":false,\"background\":true,\"model\":\"Fanar-Sadiq-2\""),
                "both flags lead the body (ADR-032), was: " + body);
        assertEquals(1, server.hits());
    }

    @Test
    void startIsNeverRetriedWhileRetrieveAndCancelAre() throws Exception {
        server.enqueue(Reply.json(503, "{\"error\":{\"code\":\"overloaded\",\"message\":\"busy\"}}"),
                Reply.json(503, "{\"error\":{\"code\":\"overloaded\",\"message\":\"busy\"}}"), Reply.json(200, QUEUED),
                Reply.json(503, "{\"error\":{\"code\":\"overloaded\",\"message\":\"busy\"}}"), Reply.json(200, CANCELLED));
        try (FanarClient client = client(FAST)) {
            assertThrows(FanarOverloadedException.class, () -> client.sadiq().startDeepResearch(probe()),
                    "a retried start would admit a second job");
            assertEquals(1, server.hits());
            assertEquals(JobStatus.QUEUED, client.sadiq().retrieveDeepResearchJob(JOB_ID).status());
            assertEquals(3, server.hits(), "reading a job spends no quota: the client's policy retried it");
            assertEquals(JobStatus.CANCELLED, client.sadiq().cancelDeepResearchJob(JOB_ID).status());
        }
        assertEquals(5, server.hits(), "1 + 2 + 2: the same client retries the cancel too");
    }

    @Test
    void retrieveGetsTheJobById() throws Exception {
        server.enqueue(Reply.json(200, QUEUED));
        try (FanarClient client = client(FAST)) {
            assertEquals(JOB_ID, client.sadiq().retrieveDeepResearchJob(JOB_ID).id());
        }
        Received sent = server.lastReceived();
        assertEquals("GET", sent.method());
        assertEquals("/v1/sadiq/deep-research/" + JOB_ID, sent.path());
        assertNull(sent.query(), "the job object, not its events");
        assertEquals("application/json", sent.header("Accept"));
        assertEquals(0, sent.body().length);
        assertEquals(1, server.hits());
    }

    @Test
    void cancelPostsWithoutABody() throws Exception {
        server.enqueue(Reply.json(200, CANCELLED));
        DeepResearchJob job;
        try (FanarClient client = client(FAST)) {
            job = client.sadiq().cancelDeepResearchJob(JOB_ID);
        }
        assertEquals(JobStatus.CANCELLED, job.status());
        Received sent = server.lastReceived();
        assertEquals("POST", sent.method());
        assertEquals("/v1/sadiq/deep-research/" + JOB_ID + "/cancel", sent.path());
        assertEquals(0, sent.body().length);
        assertNull(sent.header("Content-Type"), "no body, so nothing to describe");
        assertEquals(1, server.hits());
    }

    @Test
    void theAsyncTwinsCrossTheSameSeam() throws Exception {
        server.enqueue(Reply.json(200, QUEUED), Reply.json(200, QUEUED), Reply.json(200, CANCELLED));
        try (FanarClient client = client(FAST)) {
            assertEquals(JobStatus.QUEUED,
                    client.sadiq().startDeepResearchAsync(probe()).get(10, TimeUnit.SECONDS).status());
            assertEquals(JobStatus.QUEUED,
                    client.sadiq().retrieveDeepResearchJobAsync(JOB_ID).get(10, TimeUnit.SECONDS).status());
            assertEquals(JobStatus.CANCELLED,
                    client.sadiq().cancelDeepResearchJobAsync(JOB_ID).get(10, TimeUnit.SECONDS).status());
        }
        assertEquals(List.of("POST", "GET", "POST"), server.received().stream().map(Received::method).toList());
        assertEquals(3, server.hits());
    }

    @Test
    void anUnknownJobIsNotFoundOnEveryOperation() throws Exception {
        server.enqueue(Reply.json(404, NOT_FOUND), Reply.json(404, NOT_FOUND), Reply.json(404, NOT_FOUND));
        try (FanarClient client = client(FAST)) {
            assertEquals("No job found with this id.", assertThrows(FanarNotFoundException.class,
                    () -> client.sadiq().retrieveDeepResearchJob("gone")).getMessage());
            assertThrows(FanarNotFoundException.class, () -> client.sadiq().cancelDeepResearchJob("gone"));
            assertThrows(FanarNotFoundException.class, () -> client.sadiq().deepResearchJobStream("gone"));
        }
        assertEquals(3, server.hits(), "a 404 is the caller's to handle: routed by its code, never retried");
    }

    @Test
    void theEndpointGateSurfacesAsAuthorizationExceptionOnEveryOperation() throws Exception {
        server.enqueue(Reply.json(403, GATE), Reply.json(403, GATE), Reply.json(403, GATE), Reply.json(403, GATE));
        try (FanarClient client = client(FAST)) {
            assertThrows(FanarAuthorizationException.class, () -> client.sadiq().startDeepResearch(probe()));
            assertThrows(FanarAuthorizationException.class, () -> client.sadiq().retrieveDeepResearchJob(JOB_ID));
            assertThrows(FanarAuthorizationException.class, () -> client.sadiq().cancelDeepResearchJob(JOB_ID));
            assertThrows(FanarAuthorizationException.class, () -> client.sadiq().deepResearchJobStream(JOB_ID));
        }
        assertEquals(4, server.hits());
    }

    @Test
    void aStartWhoseBodyCannotBeDecodedNamesTheJob() throws Exception {
        server.enqueue(Reply.of(200, "<html>gateway</html>", Map.of("X-Id", JOB_ID)));
        try (FanarClient client = client(FAST)) {
            FanarTransportException e = assertThrows(FanarTransportException.class,
                    () -> client.sadiq().startDeepResearch(probe()));
            assertTrue(e.getMessage().contains("X-Id " + JOB_ID),
                    "the job exists and is paid for; its id must survive the failure: " + e.getMessage());
        }
        assertEquals(1, server.hits());
    }

    // --- the job stream ------------------------------------------------------------------------

    @Test
    void jobStreamDeliversTheEventsAndCompletesOnDone() throws Exception {
        server.enqueue(Reply.sse(PROGRESS + ROLE + DRAFT + REPORT + TERMINAL + DONE));
        List<DeepResearchEvent> events = follow(client(FAST));

        assertEquals(5, events.size());
        ProgressChunk progress = assertInstanceOf(ProgressChunk.class, events.get(0));
        assertNull(progress.model(), "the example's first event has no model");
        assertEquals("", assertInstanceOf(TokenChunk.class, events.get(1)).choices().getFirst().content(),
                "a role-only delta is a token without content");
        assertEquals("فضل الصدقة", assertInstanceOf(TokenChunk.class, events.get(2)).choices().getFirst().content());
        assertEquals("فضل الصدقة في الإسلام", assertInstanceOf(ReportChunk.class, events.get(3)).report().title());
        assertInstanceOf(DoneChunk.class, events.get(4));
        Received sent = server.lastReceived();
        assertEquals("GET", sent.method());
        assertEquals("/v1/sadiq/deep-research/" + JOB_ID, sent.path());
        assertEquals("stream=true", sent.query());
        assertEquals("text/event-stream", sent.header("Accept"));
        assertEquals(1, server.hits());
    }

    @Test
    void jobStreamResumesAfterTheLastEventIdWhenAResponseEndsWithoutDone() throws Exception {
        // The server ends the first response "on purpose" after the draft; the second carries on.
        server.enqueue(Reply.sse(PROGRESS + ROLE + DRAFT), Reply.sse(REPORT + TERMINAL + DONE));
        List<DeepResearchEvent> events = follow(client(FAST));

        assertEquals(5, events.size(), "every event exactly once across both responses");
        assertInstanceOf(ReportChunk.class, events.get(3));
        assertEquals("stream=true", server.received().get(0).query());
        assertEquals("stream=true&starting_after=1791099077711-0", server.received().get(1).query());
        assertEquals(2, server.hits());
    }

    @Test
    void jobStreamResumesAfterADroppedConnection() throws Exception {
        // The connection dies in the middle of the report: it was never committed, so it comes again.
        server.enqueue(Reply.sse(PROGRESS + DRAFT + "id: 1791099575053-0\ndata: {\"id\":\"c\",\"rep").thenDropConnection(),
                Reply.sse(REPORT + DONE));
        List<DeepResearchEvent> events = follow(client(FAST));

        assertEquals(3, events.size());
        assertInstanceOf(ReportChunk.class, events.get(2));
        assertEquals("stream=true&starting_after=1791099077711-0", server.received().get(1).query());
        assertEquals(2, server.hits());
    }

    @Test
    void jobStreamReconnectsUnderTheClientRetryPolicy() throws Exception {
        server.enqueue(Reply.sse(PROGRESS),
                Reply.json(503, "{\"error\":{\"code\":\"overloaded\",\"message\":\"busy\"}}"),
                Reply.sse(REPORT + DONE));
        List<DeepResearchEvent> events = follow(client(FAST));

        assertEquals(2, events.size());
        assertEquals("stream=true&starting_after=1791099047115-0", server.received().get(2).query(),
                "the retried reconnect asks for the same resume point");
        assertEquals(3, server.hits());
    }

    @Test
    void jobStreamEndsWithNotFoundWhenTheJobIsGoneOnReconnect() throws Exception {
        server.enqueue(Reply.sse(PROGRESS), Reply.json(404, NOT_FOUND));
        CollectingSubscriber<DeepResearchEvent> sub = CollectingSubscriber.unbounded();
        try (FanarClient client = client(FAST)) {
            client.sadiq().deepResearchJobStream(JOB_ID).subscribe(sub);
            assertInstanceOf(FanarNotFoundException.class, sub.awaitError(WAIT));
        }
        assertEquals(1, sub.items().size());
        assertEquals(2, server.hits());
    }

    @Test
    void jobStreamFailsAfterThreeEmptyResponses() throws Exception {
        server.enqueue(Reply.sse(PROGRESS), Reply.sse(""), Reply.sse(""), Reply.sse(""));
        CollectingSubscriber<DeepResearchEvent> sub = CollectingSubscriber.unbounded();
        try (FanarClient client = client(FAST)) {
            client.sadiq().deepResearchJobStream(JOB_ID).subscribe(sub);
            Throwable error = sub.awaitError(WAIT);
            assertInstanceOf(FanarTransportException.class, error);
            assertTrue(error.getMessage().contains("3 consecutive responses"), error.getMessage());
        }
        assertEquals(4, server.hits(), "the first response, then three that complete nothing");
    }

    @Test
    void keepaliveOnlyResponsesKeepTheJobStreamAlive() throws Exception {
        server.enqueue(Reply.sse(PROGRESS), Reply.sse(""), Reply.sse(""), Reply.sse(": keepalive\n\n"),
                Reply.sse(""), Reply.sse(""), Reply.sse(REPORT + DONE));
        List<DeepResearchEvent> events = follow(client(FAST));

        assertEquals(2, events.size());
        assertEquals(7, server.hits(), "a keepalive shows the job is alive, so the stall count restarts");
    }

    @Test
    void jobStreamWithoutEventIdsFailsRatherThanReplay() throws Exception {
        server.enqueue(Reply.sse("data: {\"id\":\"c\",\"created\":1,\"model\":null,"
                + "\"progress\":{\"message\":{\"en\":\"Analyzing\",\"ar\":\"تحليل\"}}}\n\n"));
        CollectingSubscriber<DeepResearchEvent> sub = CollectingSubscriber.unbounded();
        try (FanarClient client = client(FAST)) {
            client.sadiq().deepResearchJobStream(JOB_ID).subscribe(sub);
            Throwable error = sub.awaitError(WAIT);
            assertInstanceOf(FanarTransportException.class, error);
            assertTrue(error.getMessage().contains("without an event id"), error.getMessage());
        }
        assertEquals(1, server.hits(), "no reconnect that would replay the delivered event");
    }

    @Test
    void anErrorFrameFollowedByEventsDoesNotEndTheJobStream() throws Exception {
        // A worker fails, the job restarts, and its events replay from the start (ADR-032).
        server.enqueue(Reply.sse(PROGRESS + "id: 1791099050000-0\ndata: " + ERROR_FRAME + "\n\n"
                + PROGRESS.replace("1791099047115-0", "1791099060000-0") + REPORT + DONE));
        RecordingObservability obs = new RecordingObservability();
        List<DeepResearchEvent> events = follow(client(FAST, obs));

        assertEquals(3, events.size(), "the replayed progress event is delivered again");
        assertInstanceOf(ReportChunk.class, events.get(2));
        assertTrue(obs.events.contains("stream_error_superseded"), obs.events.toString());
        assertEquals(1, server.hits());
    }

    @Test
    void anErrorFrameFollowedByDoneFailsTheJobStreamTyped() throws Exception {
        server.enqueue(Reply.sse(PROGRESS + "id: 1791099050000-0\ndata: " + ERROR_FRAME + "\n\n" + DONE));
        CollectingSubscriber<DeepResearchEvent> sub = CollectingSubscriber.unbounded();
        try (FanarClient client = client(FAST)) {
            client.sadiq().deepResearchJobStream(JOB_ID).subscribe(sub);
            FanarInternalServerException error = assertInstanceOf(FanarInternalServerException.class, sub.awaitError(WAIT));
            assertEquals("worker died", error.getMessage());
        }
        assertEquals(1, sub.items().size());
        assertEquals(1, server.hits());
    }

    @Test
    void cancellingTheJobStreamReleasesTheConnection() throws Exception {
        // Headers at once, then a body the server holds back: the subscriber cancels while waiting.
        server.enqueue(Reply.sse(PROGRESS + DONE).withBodyDelay(Duration.ofSeconds(30)));
        CountDownLatch admitted = new CountDownLatch(1);
        CountDownLatch closed = new CountDownLatch(1);
        CollectingSubscriber<DeepResearchEvent> sub = CollectingSubscriber.unbounded();
        try (FanarClient client = client(FAST, latchingObservability(admitted, closed))) {
            client.sadiq().deepResearchJobStream(JOB_ID).subscribe(sub);
            assertTrue(admitted.await(10, TimeUnit.SECONDS), "the response headers arrived");
            sub.cancel();
            assertTrue(closed.await(10, TimeUnit.SECONDS), "cancelling ends the stream and closes its observation");
        }
        assertEquals(1, server.hits(), "no reconnect after the cancel — and no cancel request for the job");
    }

    @Test
    void oneObservationSpansEveryResponseOfAJobStream() throws Exception {
        server.enqueue(Reply.sse(PROGRESS + DRAFT), Reply.sse(REPORT + DONE));
        RecordingObservability obs = new RecordingObservability();
        follow(client(FAST, obs));

        assertTrue(obs.closed.await(10, TimeUnit.SECONDS));
        assertEquals(List.of("fanar.sadiq.deep_research.retrieve.stream"), obs.opened, "one observation, two responses");
        assertEquals(List.of("stream_resumed"), obs.events);
        assertEquals(3L, obs.attributes.get(FanarObservationAttributes.FANAR_STREAM_CHUNKS));
        assertTrue(String.valueOf(obs.attributes.get(FanarObservationAttributes.HTTP_URL))
                        .endsWith("/v1/sadiq/deep-research/" + JOB_ID + "?stream=true&starting_after=1791099077711-0"),
                "the http.* attributes describe the last request: " + obs.attributes.get(FanarObservationAttributes.HTTP_URL));
        assertNull(obs.attributes.get(FanarObservationAttributes.FANAR_MODEL), "a job read names no model");
        assertEquals(2, server.hits());
    }

    // --- helpers -------------------------------------------------------------------------------

    /** Follows the job's events to the end on a fresh client, which it closes. */
    private List<DeepResearchEvent> follow(FanarClient client) throws InterruptedException {
        CollectingSubscriber<DeepResearchEvent> sub = CollectingSubscriber.unbounded();
        try (client) {
            client.sadiq().deepResearchJobStream(JOB_ID).subscribe(sub);
            return sub.awaitCompletion(WAIT);
        }
    }

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

    /** Records every observation's name, and the events and attributes of the last one. */
    private static final class RecordingObservability implements ObservabilityPlugin {
        final List<String> opened = new CopyOnWriteArrayList<>();
        final List<String> events = new CopyOnWriteArrayList<>();
        final Map<String, Object> attributes = new ConcurrentHashMap<>();
        final CountDownLatch closed = new CountDownLatch(1);

        @Override
        public ObservationHandle start(String operationName) {
            opened.add(operationName);
            return new ObservationHandle() {
                @Override public ObservationHandle attribute(String key, Object value) {
                    attributes.put(key, value);
                    return this;
                }
                @Override public ObservationHandle event(String name) { events.add(name); return this; }
                @Override public ObservationHandle error(Throwable error) { return this; }
                @Override public ObservationHandle child(String name) { return this; }
                @Override public Map<String, String> propagationHeaders() { return Map.of(); }
                @Override public void close() { closed.countDown(); }
            };
        }
    }

    /** Counts down {@code admitted} once the retry boundary records the status code, {@code closed} on close. */
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
     * Minimal real-JSON codec — core has no Jackson on its test classpath. It writes the actual
     * request shape and reads the compact frames and job bodies above by their keys; a body that is
     * not a JSON object fails the way a real codec does, with an {@link IOException}. Both shipped
     * Jackson adapters decode the spec's own examples in {@code DeepResearchJobWireIntegrationTest}.
     */
    private static FanarJsonCodec wireCodec() {
        return new FanarJsonCodec() {
            @SuppressWarnings("unchecked")
            @Override
            public <T> T decode(InputStream in, Class<T> type) throws IOException {
                String json = new String(in.readAllBytes(), StandardCharsets.UTF_8);
                if (!json.startsWith("{")) {
                    throw new IOException("not a JSON object");
                }
                if (type == Map.class) {
                    return (T) shapeOf(json);
                }
                if (type == DeepResearchJob.class) {
                    return type.cast(new DeepResearchJob(field(json, "id"), JobStatus.of(field(json, "status")), null,
                            null, 0, 0, 0L, null, null, 0L, null,
                            new DeepResearchRequest(ChatModel.of(field(json, "model")), field(json, "input"), null, null),
                            null));
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
                    return type.cast(new DoneChunk(id, 0L, model, List.of(), null, Map.of("depth", "quick")));
                }
                String content = field(json, "content");
                return type.cast(new TokenChunk(id, 0L, model, List.of(new ChoiceToken(0, null, content == null ? "" : content))));
            }

            @Override
            public void encode(OutputStream out, Object value) throws IOException {
                DeepResearchRequest r = (DeepResearchRequest) value;
                out.write(("{\"model\":\"" + r.model().wireValue() + "\",\"input\":\"" + r.input() + "\"}")
                        .getBytes(StandardCharsets.UTF_8));
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
}

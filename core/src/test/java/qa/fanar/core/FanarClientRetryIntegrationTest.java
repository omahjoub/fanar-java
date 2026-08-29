package qa.fanar.core;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.AutoClose;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import qa.fanar.core.audio.TextToSpeechRequest;
import qa.fanar.core.audio.TtsModel;
import qa.fanar.core.audio.Voice;
import qa.fanar.core.chat.ChatChoice;
import qa.fanar.core.chat.ChatMessage;
import qa.fanar.core.chat.ChatModel;
import qa.fanar.core.chat.ChatRequest;
import qa.fanar.core.chat.ChatResponse;
import qa.fanar.core.chat.FinishReason;
import qa.fanar.core.chat.StreamEvent;
import qa.fanar.core.chat.UserMessage;
import qa.fanar.core.spi.FanarJsonCodec;
import qa.fanar.core.spi.FanarObservationAttributes;
import qa.fanar.core.spi.Interceptor;
import qa.fanar.core.spi.ObservabilityPlugin;
import qa.fanar.core.spi.ObservationHandle;
import qa.fanar.testsupport.CollectingSubscriber;
import qa.fanar.testsupport.ScriptedHttpServer;
import qa.fanar.testsupport.ScriptedHttpServer.Reply;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Retry through the <em>public</em> API: {@code FanarClient.builder()} → {@code chat().send()} →
 * interceptor chain → real JDK transport → a local {@link ScriptedHttpServer} scripting the responses.
 *
 * <p>This is the seam the unit tests cannot see. {@code RetryInterceptorTest} proves the loop on
 * outcomes it is handed and the facade tests prove mapping with retries disabled; only a test
 * that crosses facade → chain → transport can prove that an error response actually reaches the
 * retry decision — which through 0.2.0 it never did (ADR-012 amendment, 2026-08-28). Every ADR-014
 * / ADR-025 promise a consumer can observe is asserted here: retry on 5xx, {@code Retry-After}
 * honoured up to {@code maxDelay}, abort above it with the hint preserved, quota not retried but
 * hinted, non-retryable errors not retried, attempts exhausted → last error, and user interceptors
 * still seeing the raw error responses. The streaming and async surfaces cross the same seam:
 * {@code chat().stream()} / {@code audio().speechStream()} retry the handshake only — a connection
 * that dies mid-stream reaches the subscriber as {@code onError} and is never re-requested (ADR-014)
 * — and {@code sendAsync()} runs the whole chain, retries included, on its virtual thread (ADR-004).</p>
 *
 * <p>The server is {@code @AutoClose}d after each test, which also fails the test if a scripted
 * reply was never requested or an unscripted request arrived — every hit count below is exact.</p>
 */
@Tag("integration")
class FanarClientRetryIntegrationTest {

    private static final RetryPolicy FAST = RetryPolicy.defaults()
            .withBaseDelay(Duration.ofMillis(1))
            .withMaxDelay(Duration.ofMillis(1));
    private static final Duration WAIT = Duration.ofSeconds(10);

    @AutoClose
    private final ScriptedHttpServer server = ScriptedHttpServer.start();

    @Test
    void retryableErrorResponseIsRetriedThroughThePublicApi() {
        server.enqueue(
                Reply.json(503, "{\"error\":{\"code\":\"overloaded\",\"message\":\"busy\",\"status\":503}}"),
                ok());
        RecordingObservability obs = new RecordingObservability();

        try (FanarClient client = client(FAST, obs)) {
            ChatResponse response = client.chat().send(ping());
            assertEquals("c_1", response.id());
        }

        assertEquals(2, server.hits(), "the 503 must be retried once");
        assertEquals(List.of("retry_attempt"), obs.events);
        assertEquals(1, obs.attributes.get(FanarObservationAttributes.FANAR_RETRY_COUNT));
        assertEquals(200, obs.attributes.get(FanarObservationAttributes.HTTP_STATUS_CODE), "last attempt's status wins");
        assertTrue(obs.errors.isEmpty());
    }

    @Test
    void retryAfterWithinTheCeilingIsHonoured() {
        server.enqueue(Reply.of(429, "slow down", Map.of("Retry-After", "1")), ok());

        long started = System.nanoTime();
        try (FanarClient client = client(RetryPolicy.defaults(), ObservabilityPlugin.noop())) {
            assertEquals("c_1", client.chat().send(ping()).id());
        }
        long elapsedMs = (System.nanoTime() - started) / 1_000_000L;

        assertEquals(2, server.hits());
        assertTrue(elapsedMs >= 950, "the 1 s hint must be slept, elapsed " + elapsedMs + " ms");
    }

    @Test
    void retryAfterAboveTheCeilingSurfacesImmediatelyWithTheHint() {
        server.enqueue(Reply.of(429, "come back later", Map.of("Retry-After", "7200")));
        RecordingObservability obs = new RecordingObservability();

        long started = System.nanoTime();
        FanarRateLimitException ex;
        try (FanarClient client = client(RetryPolicy.defaults(), obs)) {
            ex = assertThrows(FanarRateLimitException.class, () -> client.chat().send(ping()));
        }
        long elapsedMs = (System.nanoTime() - started) / 1_000_000L;

        assertEquals(Duration.ofHours(2), ex.retryAfter(), "hint preserved for caller-side scheduling");
        assertEquals(1, server.hits(), "no retry may be attempted");
        assertTrue(elapsedMs < 5_000, "must not sleep, elapsed " + elapsedMs + " ms");
        assertTrue(obs.events.isEmpty(), "no retry_attempt event");
        assertEquals(0, obs.attributes.get(FanarObservationAttributes.FANAR_RETRY_COUNT), "abort is observable");
        assertEquals(429, obs.attributes.get(FanarObservationAttributes.HTTP_STATUS_CODE));
        assertSame(ex, obs.errors.getFirst());
    }

    @Test
    void exceededQuotaIsNotRetriedButCarriesTheCountdown() {
        server.enqueue(Reply.json(429,
                "{\"error\":{\"code\":\"exceeded_quota\",\"message\":\"quota exhausted\",\"status\":429}}")
                .withHeader("Retry-After", "86400"));

        try (FanarClient client = client(FAST, ObservabilityPlugin.noop())) {
            FanarQuotaExceededException ex =
                    assertThrows(FanarQuotaExceededException.class, () -> client.chat().send(ping()));
            assertEquals(Duration.ofHours(24), ex.retryAfter());
        }
        assertEquals(1, server.hits(), "quota exhaustion is not retried by default");
    }

    @Test
    void nonRetryableErrorIsNotRetried() {
        server.enqueue(Reply.json(401,
                "{\"error\":{\"code\":\"invalid_authentication\",\"message\":\"bad key\",\"status\":401}}"));

        try (FanarClient client = client(FAST, ObservabilityPlugin.noop())) {
            FanarAuthenticationException ex =
                    assertThrows(FanarAuthenticationException.class, () -> client.chat().send(ping()));
            assertEquals("bad key", ex.getMessage());
        }
        assertEquals(1, server.hits());
    }

    @Test
    void exhaustedAttemptsSurfaceTheLastError() {
        server.enqueue(Reply.of(503, "busy 1"), Reply.of(503, "busy 2"), Reply.of(503, "busy 3"));
        RecordingObservability obs = new RecordingObservability();

        try (FanarClient client = client(FAST.withMaxAttempts(3), obs)) {
            FanarOverloadedException ex =
                    assertThrows(FanarOverloadedException.class, () -> client.chat().send(ping()));
            assertEquals("busy 3", ex.getMessage(), "the last attempt's error surfaces");
        }
        assertEquals(3, server.hits());
        assertEquals(2, obs.attributes.get(FanarObservationAttributes.FANAR_RETRY_COUNT));
        assertEquals(List.of("retry_attempt", "retry_attempt"), obs.events);
    }

    @Test
    void userInterceptorsSeeRawErrorResponses() {
        // ADR-012 amendment: mapping happens at the retry boundary, outside user interceptors,
        // so logging / capture interceptors keep observing 4xx/5xx as responses.
        server.enqueue(Reply.of(503, "busy"), ok());
        List<Integer> seen = new CopyOnWriteArrayList<>();
        Interceptor capture = (request, chain) -> {
            var response = chain.proceed(request);
            seen.add(response.statusCode());
            return response;
        };

        try (FanarClient client = FanarClient.builder()
                .apiKey("sk_test")
                .baseUrl(server.baseUri())
                .jsonCodec(cannedCodec())
                .retryPolicy(FAST)
                .addInterceptor(capture)
                .build()) {
            assertEquals("c_1", client.chat().send(ping()).id());
        }
        assertEquals(List.of(503, 200), seen);
    }

    @Test
    void disabledPolicyStillMapsErrorsButNeverRetries() {
        server.enqueue(Reply.of(503, "busy"));

        try (FanarClient client = client(RetryPolicy.disabled(), ObservabilityPlugin.noop())) {
            assertInstanceOf(FanarOverloadedException.class,
                    assertThrows(FanarException.class, () -> client.chat().send(ping())));
        }
        assertEquals(1, server.hits());
    }

    // --- rate-limit visibility (ADR-026) ------------------------------------------------------

    @Test
    void rateLimitHeadersOnA429SurfaceOnTheExceptionAndAsAttributes() {
        // The exhausted per-day window observed live on 2026-08-28: retry-after equals x-ratelimit-reset,
        // both far above the ceiling, so the call surfaces immediately carrying the window.
        server.enqueue(Reply.json(429,
                "{\"error\":{\"code\":\"rate_limit_reached\",\"message\":\"Rate limit reached\",\"status\":429}}")
                .withHeader("Retry-After", "28606")
                .withHeader("x-ratelimit-limit", "20")
                .withHeader("x-ratelimit-remaining", "0")
                .withHeader("x-ratelimit-reset", "28606")
                .withHeader("ratelimit-policy", "20;w=86400"));
        RecordingObservability obs = new RecordingObservability();

        FanarRateLimitException ex;
        try (FanarClient client = client(RetryPolicy.defaults(), obs)) {
            ex = assertThrows(FanarRateLimitException.class, () -> client.chat().send(ping()));
        }

        assertEquals(1, server.hits(), "the hint is above the ceiling: no retry");
        RateLimitInfo window = ex.rateLimit();
        assertEquals(new RateLimitInfo(20, 0, Duration.ofSeconds(28606), "20;w=86400"), window);
        assertEquals(Duration.ofDays(1), window.window());
        assertEquals(ex.retryAfter(), window.reset(), "on Fanar the 429's Retry-After is the same countdown");
        assertEquals(20L, obs.attributes.get(FanarObservationAttributes.FANAR_RATELIMIT_LIMIT));
        assertEquals(0L, obs.attributes.get(FanarObservationAttributes.FANAR_RATELIMIT_REMAINING));
        assertEquals(28606L, obs.attributes.get(FanarObservationAttributes.FANAR_RATELIMIT_RESET));
        assertEquals("20;w=86400", obs.attributes.get(FanarObservationAttributes.FANAR_RATELIMIT_POLICY));
    }

    @Test
    void rateLimitHeadersOnSuccessAreRecordedAndTheLastAttemptWins() {
        server.enqueue(
                Reply.json(429, "{\"error\":{\"code\":\"rate_limit_reached\",\"message\":\"slow down\",\"status\":429}}")
                        .withHeader("x-ratelimit-limit", "50")
                        .withHeader("x-ratelimit-remaining", "0")
                        .withHeader("x-ratelimit-reset", "1")
                        .withHeader("ratelimit-policy", "50;w=60"),
                ok().withHeader("x-ratelimit-limit", "50")
                        .withHeader("x-ratelimit-remaining", "49")
                        .withHeader("x-ratelimit-reset", "60")
                        .withHeader("ratelimit-policy", "50;w=60"));
        RecordingObservability obs = new RecordingObservability();

        try (FanarClient client = client(FAST, obs)) {
            assertEquals("c_1", client.chat().send(ping()).id());
        }

        assertEquals(2, server.hits(), "a 429 without Retry-After is retried after the computed back-off");
        assertEquals(1, obs.attributes.get(FanarObservationAttributes.FANAR_RETRY_COUNT));
        assertEquals(49L, obs.attributes.get(FanarObservationAttributes.FANAR_RATELIMIT_REMAINING), "last attempt wins");
        assertEquals(60L, obs.attributes.get(FanarObservationAttributes.FANAR_RATELIMIT_RESET));
        assertTrue(obs.errors.isEmpty());
    }

    @Test
    void responsesWithoutRateLimitHeadersRecordNoRateLimitAttributes() {
        server.enqueue(ok());
        RecordingObservability obs = new RecordingObservability();

        try (FanarClient client = client(FAST, obs)) {
            assertEquals("c_1", client.chat().send(ping()).id());
        }

        assertFalse(obs.attributes.containsKey(FanarObservationAttributes.FANAR_RATELIMIT_LIMIT),
                "non-model calls and unlimited-quota keys carry no headers — and no attributes");
    }

    // --- streaming: retries apply to the handshake only (ADR-014) -----------------------------

    @Test
    void streamingHandshakeIsRetriedThroughThePublicApi() throws Exception {
        // The [DONE] sentinel is handled without the codec (StreamEventDecoder), so the canned
        // codec suffices: a retried handshake followed by an empty stream completes with no events.
        server.enqueue(Reply.of(503, "busy"), Reply.sse("data: [DONE]\n\n"));
        RecordingObservability obs = new RecordingObservability();
        CollectingSubscriber<StreamEvent> subscriber = CollectingSubscriber.unbounded();

        try (FanarClient client = client(FAST, obs)) {
            client.chat().stream(ping()).subscribe(subscriber);
            assertEquals(List.of(), subscriber.awaitCompletion(WAIT), "[DONE] is a sentinel, not an event");
        }

        assertEquals(2, server.hits(), "the 503 handshake must be retried once");
        assertEquals("text/event-stream", server.lastReceived().header("Accept"));
        assertEquals(List.of("retry_attempt"), obs.events);
        assertEquals(1, obs.attributes.get(FanarObservationAttributes.FANAR_RETRY_COUNT));
        assertEquals(200, obs.attributes.get(FanarObservationAttributes.HTTP_STATUS_CODE));
    }

    @Test
    void speechStreamHandshakeIsRetriedThroughThePublicApi() throws Exception {
        byte[] audio = "ID3\u0003\u0000fake-mp3-bytes".getBytes(StandardCharsets.UTF_8);
        server.enqueue(Reply.of(503, "busy"), Reply.of(200, audio, Map.of("Content-Type", "audio/mpeg")));
        RecordingObservability obs = new RecordingObservability();
        CollectingSubscriber<byte[]> subscriber = CollectingSubscriber.unbounded();

        try (FanarClient client = client(FAST, obs)) {
            client.audio().speechStream(speech()).subscribe(subscriber);
            assertArrayEquals(audio, concat(subscriber.awaitCompletion(WAIT)), "the retried body streams through intact");
        }

        assertEquals(2, server.hits(), "the 503 handshake must be retried once");
        assertEquals("audio/*", server.lastReceived().header("Accept"));
        assertEquals(List.of("retry_attempt"), obs.events);
        assertEquals(1, obs.attributes.get(FanarObservationAttributes.FANAR_RETRY_COUNT));
    }

    @Test
    void connectionDropMidStreamIsNotRetried() throws Exception {
        // A handshake that succeeded and then died: the failure reaches the subscriber, and the
        // server is never asked again — there is no mid-stream retry (ADR-014).
        server.enqueue(Reply.sse(": keep-alive\n\n").thenDropConnection());
        RecordingObservability obs = new RecordingObservability();
        CollectingSubscriber<StreamEvent> subscriber = CollectingSubscriber.unbounded();

        try (FanarClient client = client(FAST, obs)) {
            client.chat().stream(ping()).subscribe(subscriber);
            assertInstanceOf(IOException.class, subscriber.awaitError(WAIT),
                    "the transport failure surfaces on the subscriber, unwrapped");
        }

        assertEquals(1, server.hits(), "no second handshake");
        assertTrue(subscriber.items().isEmpty());
        assertTrue(obs.events.isEmpty(), "no retry_attempt event");
        assertEquals(0, obs.attributes.get(FanarObservationAttributes.FANAR_RETRY_COUNT));
        assertTrue(obs.errors.isEmpty(), "the handshake observation had already closed successfully");
    }

    @Test
    void speechStreamConnectionDropIsNotRetried() throws Exception {
        server.enqueue(Reply.of(200, new byte[] {'I', 'D', '3'}, Map.of("Content-Type", "audio/mpeg"))
                .thenDropConnection());
        CollectingSubscriber<byte[]> subscriber = CollectingSubscriber.unbounded();

        try (FanarClient client = client(FAST, ObservabilityPlugin.noop())) {
            client.audio().speechStream(speech()).subscribe(subscriber);
            assertInstanceOf(IOException.class, subscriber.awaitError(WAIT));
        }
        assertEquals(1, server.hits(), "no second handshake");
    }

    // --- async sugar runs the whole chain, retries included, off the caller's thread (ADR-004) ---

    @Test
    void sendAsyncRetriesThroughTheChainOnAVirtualThread() throws Exception {
        server.enqueue(Reply.of(503, "busy"), ok());
        RecordingObservability obs = new RecordingObservability();
        AtomicBoolean chainRanOnVirtualThread = new AtomicBoolean();
        Interceptor probe = (request, chain) -> {
            chainRanOnVirtualThread.set(Thread.currentThread().isVirtual());
            return chain.proceed(request);
        };

        try (FanarClient client = FanarClient.builder()
                .apiKey("sk_test")
                .baseUrl(server.baseUri())
                .jsonCodec(cannedCodec())
                .retryPolicy(FAST)
                .observability(obs)
                .addInterceptor(probe)
                .build()) {
            CompletableFuture<ChatResponse> future = client.chat().sendAsync(ping());
            assertEquals("c_1", future.get(WAIT.toSeconds(), TimeUnit.SECONDS).id());
        }

        assertEquals(2, server.hits(), "the 503 must be retried once");
        assertTrue(chainRanOnVirtualThread.get(), "ADR-004: the async variant runs the chain on a virtual thread");
        assertEquals(List.of("retry_attempt"), obs.events);
        assertEquals(1, obs.attributes.get(FanarObservationAttributes.FANAR_RETRY_COUNT));
    }

    @Test
    void sendAsyncSurfacesTheRetryAfterHintAboveTheCeiling() throws Exception {
        server.enqueue(Reply.of(429, "come back later", Map.of("Retry-After", "7200")));

        try (FanarClient client = client(RetryPolicy.defaults(), ObservabilityPlugin.noop())) {
            CompletableFuture<ChatResponse> future = client.chat().sendAsync(ping());
            ExecutionException failure = assertThrows(ExecutionException.class,
                    () -> future.get(WAIT.toSeconds(), TimeUnit.SECONDS));
            FanarRateLimitException ex = assertInstanceOf(FanarRateLimitException.class, failure.getCause());
            assertEquals(Duration.ofHours(2), ex.retryAfter(), "hint preserved through completeExceptionally");
        }
        assertEquals(1, server.hits(), "no retry may be attempted");
    }

    // --- helpers -----------------------------------------------------------------------------

    private FanarClient client(RetryPolicy policy, ObservabilityPlugin observability) {
        return FanarClient.builder()
                .apiKey("sk_test")
                .baseUrl(server.baseUri())
                .jsonCodec(cannedCodec())
                .retryPolicy(policy)
                .observability(observability)
                .connectTimeout(Duration.ofSeconds(5))
                .requestTimeout(Duration.ofSeconds(5))
                .build();
    }

    private static ChatRequest ping() {
        return ChatRequest.builder().model(ChatModel.FANAR).addMessage(UserMessage.of("ping")).build();
    }

    private static Reply ok() {
        return Reply.json(200, "{}");
    }

    private static TextToSpeechRequest speech() {
        return TextToSpeechRequest.of(TtsModel.FANAR_AURA_TTS_2, "ping", Voice.EMILY);
    }

    private static byte[] concat(List<byte[]> chunks) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        chunks.forEach(out::writeBytes);
        return out.toByteArray();
    }

    /** Decodes every 2xx body into the same canned response — this test is about retry, not JSON. */
    private static FanarJsonCodec cannedCodec() {
        ChatChoice choice = new ChatChoice(FinishReason.STOP, 0, new ChatMessage(null, null, null), null, null);
        ChatResponse canned = new ChatResponse("c_1", List.of(choice), 0L, "Fanar", null, null);
        return new FanarJsonCodec() {
            public <T> T decode(InputStream in, Class<T> type) throws IOException {
                in.readAllBytes();
                return type.cast(canned);
            }
            public void encode(OutputStream out, Object value) throws IOException {
                out.write("{}".getBytes(StandardCharsets.UTF_8));
            }
        };
    }

    /** One observation per test: attributes (last write wins), events in order, reported errors. */
    private static final class RecordingObservability implements ObservabilityPlugin, ObservationHandle {
        final Map<String, Object> attributes = new LinkedHashMap<>();
        final List<String> events = new ArrayList<>();
        final List<Throwable> errors = new ArrayList<>();

        @Override public ObservationHandle start(String operationName) { return this; }
        @Override public ObservationHandle attribute(String key, Object value) { attributes.put(key, value); return this; }
        @Override public ObservationHandle event(String name) { events.add(name); return this; }
        @Override public ObservationHandle error(Throwable error) { errors.add(error); return this; }
        @Override public ObservationHandle child(String operationName) { return this; }
        @Override public Map<String, String> propagationHeaders() { return Map.of(); }
        @Override public void close() { }
    }
}

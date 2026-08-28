package qa.fanar.core;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import com.sun.net.httpserver.HttpServer;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import qa.fanar.core.chat.ChatChoice;
import qa.fanar.core.chat.ChatMessage;
import qa.fanar.core.chat.ChatModel;
import qa.fanar.core.chat.ChatRequest;
import qa.fanar.core.chat.ChatResponse;
import qa.fanar.core.chat.FinishReason;
import qa.fanar.core.chat.UserMessage;
import qa.fanar.core.spi.FanarJsonCodec;
import qa.fanar.core.spi.FanarObservationAttributes;
import qa.fanar.core.spi.Interceptor;
import qa.fanar.core.spi.ObservabilityPlugin;
import qa.fanar.core.spi.ObservationHandle;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Retry through the <em>public</em> API: {@code FanarClient.builder()} → {@code chat().send()} →
 * interceptor chain → real JDK transport → a local {@link HttpServer} scripting the responses.
 *
 * <p>This is the seam the unit tests cannot see. {@code RetryInterceptorTest} proves the loop on
 * outcomes it is handed and the facade tests prove mapping with retries disabled; only a test
 * that crosses facade → chain → transport can prove that an error response actually reaches the
 * retry decision — which through 0.2.0 it never did (ADR-012 amendment, 2026-08-28). Every ADR-014
 * / ADR-025 promise a consumer can observe is asserted here: retry on 5xx, {@code Retry-After}
 * honoured up to {@code maxDelay}, abort above it with the hint preserved, quota not retried but
 * hinted, non-retryable errors not retried, attempts exhausted → last error, and user interceptors
 * still seeing the raw error responses.</p>
 */
class FanarClientRetryIntegrationTest {

    private static final RetryPolicy FAST = RetryPolicy.defaults()
            .withBaseDelay(Duration.ofMillis(1))
            .withMaxDelay(Duration.ofMillis(1));

    private HttpServer server;
    private final ConcurrentLinkedQueue<Scripted> script = new ConcurrentLinkedQueue<>();
    private final AtomicInteger hits = new AtomicInteger();

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/v1/chat/completions", exchange -> {
            try (InputStream in = exchange.getRequestBody()) {
                in.readAllBytes();
            }
            hits.incrementAndGet();
            Scripted next = script.poll();
            if (next == null) {
                throw new IllegalStateException("script exhausted — the client sent more requests than expected");
            }
            byte[] body = next.body().getBytes(StandardCharsets.UTF_8);
            next.headers().forEach((k, v) -> exchange.getResponseHeaders().add(k, v));
            exchange.sendResponseHeaders(next.status(), body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        });
        server.start();
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    @Test
    void retryableErrorResponseIsRetriedThroughThePublicApi() {
        script.add(status(503, "{\"error\":{\"code\":\"overloaded\",\"message\":\"busy\",\"status\":503}}"));
        script.add(ok());
        RecordingObservability obs = new RecordingObservability();

        try (FanarClient client = client(FAST, obs)) {
            ChatResponse response = client.chat().send(ping());
            assertEquals("c_1", response.id());
        }

        assertEquals(2, hits.get(), "the 503 must be retried once");
        assertEquals(List.of("retry_attempt"), obs.events);
        assertEquals(1, obs.attributes.get(FanarObservationAttributes.FANAR_RETRY_COUNT));
        assertEquals(200, obs.attributes.get(FanarObservationAttributes.HTTP_STATUS_CODE), "last attempt's status wins");
        assertTrue(obs.errors.isEmpty());
    }

    @Test
    void retryAfterWithinTheCeilingIsHonoured() {
        script.add(status(429, "slow down", Map.of("Retry-After", "1")));
        script.add(ok());

        long started = System.nanoTime();
        try (FanarClient client = client(RetryPolicy.defaults(), ObservabilityPlugin.noop())) {
            assertEquals("c_1", client.chat().send(ping()).id());
        }
        long elapsedMs = (System.nanoTime() - started) / 1_000_000L;

        assertEquals(2, hits.get());
        assertTrue(elapsedMs >= 950, "the 1 s hint must be slept, elapsed " + elapsedMs + " ms");
    }

    @Test
    void retryAfterAboveTheCeilingSurfacesImmediatelyWithTheHint() {
        script.add(status(429, "come back later", Map.of("Retry-After", "7200")));
        RecordingObservability obs = new RecordingObservability();

        long started = System.nanoTime();
        FanarRateLimitException ex;
        try (FanarClient client = client(RetryPolicy.defaults(), obs)) {
            ex = assertThrows(FanarRateLimitException.class, () -> client.chat().send(ping()));
        }
        long elapsedMs = (System.nanoTime() - started) / 1_000_000L;

        assertEquals(Duration.ofHours(2), ex.retryAfter(), "hint preserved for caller-side scheduling");
        assertEquals(1, hits.get(), "no retry may be attempted");
        assertTrue(elapsedMs < 5_000, "must not sleep, elapsed " + elapsedMs + " ms");
        assertTrue(obs.events.isEmpty(), "no retry_attempt event");
        assertEquals(0, obs.attributes.get(FanarObservationAttributes.FANAR_RETRY_COUNT), "abort is observable");
        assertEquals(429, obs.attributes.get(FanarObservationAttributes.HTTP_STATUS_CODE));
        assertSame(ex, obs.errors.getFirst());
    }

    @Test
    void exceededQuotaIsNotRetriedButCarriesTheCountdown() {
        script.add(status(429,
                "{\"error\":{\"code\":\"exceeded_quota\",\"message\":\"quota exhausted\",\"status\":429}}",
                Map.of("Retry-After", "86400")));

        try (FanarClient client = client(FAST, ObservabilityPlugin.noop())) {
            FanarQuotaExceededException ex =
                    assertThrows(FanarQuotaExceededException.class, () -> client.chat().send(ping()));
            assertEquals(Duration.ofHours(24), ex.retryAfter());
        }
        assertEquals(1, hits.get(), "quota exhaustion is not retried by default");
    }

    @Test
    void nonRetryableErrorIsNotRetried() {
        script.add(status(401,
                "{\"error\":{\"code\":\"invalid_authentication\",\"message\":\"bad key\",\"status\":401}}"));

        try (FanarClient client = client(FAST, ObservabilityPlugin.noop())) {
            FanarAuthenticationException ex =
                    assertThrows(FanarAuthenticationException.class, () -> client.chat().send(ping()));
            assertEquals("bad key", ex.getMessage());
        }
        assertEquals(1, hits.get());
    }

    @Test
    void exhaustedAttemptsSurfaceTheLastError() {
        script.add(status(503, "busy 1"));
        script.add(status(503, "busy 2"));
        script.add(status(503, "busy 3"));
        RecordingObservability obs = new RecordingObservability();

        try (FanarClient client = client(FAST.withMaxAttempts(3), obs)) {
            FanarOverloadedException ex =
                    assertThrows(FanarOverloadedException.class, () -> client.chat().send(ping()));
            assertEquals("busy 3", ex.getMessage(), "the last attempt's error surfaces");
        }
        assertEquals(3, hits.get());
        assertEquals(2, obs.attributes.get(FanarObservationAttributes.FANAR_RETRY_COUNT));
        assertEquals(List.of("retry_attempt", "retry_attempt"), obs.events);
    }

    @Test
    void userInterceptorsSeeRawErrorResponses() {
        // ADR-012 amendment: mapping happens at the retry boundary, outside user interceptors,
        // so logging / capture interceptors keep observing 4xx/5xx as responses.
        script.add(status(503, "busy"));
        script.add(ok());
        List<Integer> seen = new CopyOnWriteArrayList<>();
        Interceptor capture = (request, chain) -> {
            var response = chain.proceed(request);
            seen.add(response.statusCode());
            return response;
        };

        try (FanarClient client = FanarClient.builder()
                .apiKey("sk_test")
                .baseUrl(baseUrl())
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
        script.add(status(503, "busy"));

        try (FanarClient client = client(RetryPolicy.disabled(), ObservabilityPlugin.noop())) {
            assertInstanceOf(FanarOverloadedException.class,
                    assertThrows(FanarException.class, () -> client.chat().send(ping())));
        }
        assertEquals(1, hits.get());
        assertNull(script.poll(), "nothing left unconsumed");
    }

    // --- helpers -----------------------------------------------------------------------------

    private FanarClient client(RetryPolicy policy, ObservabilityPlugin observability) {
        return FanarClient.builder()
                .apiKey("sk_test")
                .baseUrl(baseUrl())
                .jsonCodec(cannedCodec())
                .retryPolicy(policy)
                .observability(observability)
                .connectTimeout(Duration.ofSeconds(5))
                .requestTimeout(Duration.ofSeconds(5))
                .build();
    }

    private URI baseUrl() {
        return URI.create("http://" + server.getAddress().getHostString() + ":" + server.getAddress().getPort());
    }

    private static ChatRequest ping() {
        return ChatRequest.builder().model(ChatModel.FANAR).addMessage(UserMessage.of("ping")).build();
    }

    private static Scripted ok() {
        return status(200, "{}");
    }

    private static Scripted status(int status, String body) {
        return status(status, body, Map.of());
    }

    private static Scripted status(int status, String body, Map<String, String> headers) {
        return new Scripted(status, headers, body);
    }

    private record Scripted(int status, Map<String, String> headers, String body) { }

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

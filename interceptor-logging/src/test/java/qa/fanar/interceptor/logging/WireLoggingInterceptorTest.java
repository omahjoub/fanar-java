package qa.fanar.interceptor.logging;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.Flow;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Function;

import javax.net.ssl.SSLSession;

import org.junit.jupiter.api.Test;

import qa.fanar.core.spi.Interceptor;
import qa.fanar.core.spi.ObservabilityPlugin;
import qa.fanar.core.spi.ObservationHandle;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WireLoggingInterceptorTest {

    /**
     * Each entry is one full direction of an exchange (request or response), so {@code blocks.get(0)}
     * is the request block and {@code blocks.get(1)} is the response block. Lines inside a block
     * are separated by {@code \n}.
     */
    private final List<String> blocks = new ArrayList<>();
    private final Consumer<String> sink = blocks::add;
    private String allText() { return String.join("\n", blocks); }

    // --- level: NONE -----------------------------------------------------------------------

    @Test
    void none_emitsNothingAndPassesThrough() {
        WireLoggingInterceptor i = WireLoggingInterceptor.builder()
                .level(WireLoggingInterceptor.Level.NONE)
                .sink(sink)
                .build();

        HttpResponse<InputStream> resp = response(200, Map.of(), "{}");
        HttpResponse<InputStream> out = i.intercept(get("/v1/models"), chain(req -> resp));

        assertSame(resp, out);
        assertTrue(blocks.isEmpty(), "NONE level must emit nothing");
    }

    // --- level: BASIC ----------------------------------------------------------------------

    @Test
    void basic_emitsOneBlockPerDirection() {
        WireLoggingInterceptor i = build(WireLoggingInterceptor.Level.BASIC);
        i.intercept(get("/v1/models"), chain(req -> response(200, Map.of(), "{}")));

        assertEquals(2, blocks.size(), "BASIC must emit one block per direction (request + response)");
        assertTrue(blocks.get(0).startsWith("--> GET"), "first block is the request");
        assertTrue(blocks.get(1).startsWith("<-- 200"), "second block is the response");
        assertFalse(allText().contains("Authorization"), "BASIC must not include headers");
    }

    @Test
    void basic_responseBlockCarriesDurationMs() {
        WireLoggingInterceptor i = build(WireLoggingInterceptor.Level.BASIC);
        i.intercept(get("/v1/models"), chain(req -> response(200, Map.of(), "{}")));

        String responseBlock = blocks.get(1);
        assertTrue(responseBlock.matches("(?s).*\\(\\d+ms\\)$"),
                "response block must end with `(Nms)`, got: " + responseBlock);
    }

    // --- level: HEADERS --------------------------------------------------------------------

    @Test
    void headers_emitsRequestAndResponseHeaders() {
        WireLoggingInterceptor i = build(WireLoggingInterceptor.Level.HEADERS);
        i.intercept(
                get("/v1/models", "Authorization", "Bearer abc123"),
                chain(req -> response(200, Map.of("Content-Type", List.of("application/json")), "{}")));

        assertTrue(blocks.get(0).contains("\n    Authorization: Bearer [redacted]"),
                "request Authorization must be partially redacted (scheme preserved)");
        assertTrue(blocks.get(1).contains("\n    Content-Type: application/json"),
                "response headers must appear at HEADERS level");
        assertFalse(allText().contains("(no body)"), "HEADERS must not include request body");
    }

    @Test
    void headers_authorizationWithoutSchemeFullyRedacted() {
        WireLoggingInterceptor i = build(WireLoggingInterceptor.Level.HEADERS);
        i.intercept(
                get("/v1/models", "Authorization", "raw-token-no-scheme"),
                chain(req -> response(200, Map.of(), "{}")));

        assertTrue(blocks.getFirst().contains("\n    Authorization: [redacted]"),
                "value with no scheme prefix must be fully redacted");
    }

    @Test
    void headers_customRedactedHeaderHasFullValueReplaced() {
        WireLoggingInterceptor i = WireLoggingInterceptor.builder()
                .level(WireLoggingInterceptor.Level.HEADERS)
                .sink(sink)
                .addRedactedHeader("X-API-Key")
                .build();
        i.intercept(
                get("/v1/models", "X-API-Key", "secret-123"),
                chain(req -> response(200, Map.of(), "{}")));

        assertTrue(blocks.getFirst().contains("\n    X-API-Key: [redacted]"),
                "non-Authorization redacted headers must have entire value replaced");
    }

    @Test
    void headers_redactionIsCaseInsensitive() {
        WireLoggingInterceptor i = WireLoggingInterceptor.builder()
                .level(WireLoggingInterceptor.Level.HEADERS)
                .sink(sink)
                .addRedactedHeader("X-Api-Key")
                .build();
        i.intercept(
                get("/v1/models", "x-api-key", "secret-123"),
                chain(req -> response(200, Map.of(), "{}")));

        assertTrue(allText().contains("[redacted]"),
                "redaction must match regardless of header-name case");
    }

    @Test
    void headers_responseAuthorizationRedactedFully() {
        WireLoggingInterceptor i = build(WireLoggingInterceptor.Level.HEADERS);
        i.intercept(get("/v1/models"),
                chain(req -> response(200, Map.of("Authorization", List.of("Bearer set-cookie-style")), "{}")));

        // Response-side Authorization (rare but possible) — entire value redacted, not just credential.
        assertTrue(blocks.get(1).contains("\n    Authorization: [redacted]"),
                "response-side redacted headers must drop the entire value");
    }

    @Test
    void headers_unredactedHeadersPassThrough() {
        WireLoggingInterceptor i = build(WireLoggingInterceptor.Level.HEADERS);
        i.intercept(
                get("/v1/models", "X-Trace-Id", "abc-123"),
                chain(req -> response(200, Map.of(), "{}")));

        assertTrue(blocks.getFirst().contains("\n    X-Trace-Id: abc-123"),
                "non-redacted headers must pass through verbatim");
    }

    // --- level: BODY -----------------------------------------------------------------------

    @Test
    void body_emitsRequestAndResponseBodies() {
        WireLoggingInterceptor i = build(WireLoggingInterceptor.Level.BODY);
        String requestBody = "{\"input\":\"hi\"}";
        String responseBody = "{\"output\":\"hello\"}";
        i.intercept(
                postJson("/v1/chat", requestBody),
                chain(req -> response(200, Map.of("Content-Type", List.of("application/json")), responseBody)));

        assertTrue(blocks.get(0).contains(requestBody),
                "request block must contain the request body verbatim");
        assertTrue(blocks.get(1).contains(responseBody),
                "response block must contain the response body verbatim");
    }

    @Test
    void body_emptyRequestBodyShowsNoBodyMarker() {
        WireLoggingInterceptor i = build(WireLoggingInterceptor.Level.BODY);
        i.intercept(get("/v1/models"),
                chain(req -> response(200, Map.of(), "{}")));

        assertTrue(blocks.getFirst().contains("(no body)"),
                "GET with no body must emit `(no body)` placeholder");
    }

    @Test
    void body_emptyResponseBodyShowsEmptyMarker() {
        WireLoggingInterceptor i = build(WireLoggingInterceptor.Level.BODY);
        i.intercept(get("/v1/models"),
                chain(req -> response(200, Map.of(), "")));

        assertTrue(blocks.get(1).contains("(empty body)"),
                "empty response body must emit `(empty body)` placeholder");
    }

    @Test
    void body_truncatesBeyondCapAndAppendsMarker() {
        WireLoggingInterceptor i = WireLoggingInterceptor.builder()
                .level(WireLoggingInterceptor.Level.BODY)
                .sink(sink)
                .bodyByteCap(8)
                .build();
        String responseBody = "0123456789ABCDEF"; // 16 bytes, cap 8
        i.intercept(get("/v1/models"),
                chain(req -> response(200, Map.of(), responseBody)));

        String responseBlock = blocks.get(1);
        assertTrue(responseBlock.contains("01234567"), "first 8 bytes must be present");
        assertTrue(responseBlock.contains("(8 more bytes elided)"),
                "marker must report exact byte count");
    }

    @Test
    void body_streamingResponseSkipsBodyAndDoesNotConsume() {
        AtomicReference<Boolean> bodyConsumed = new AtomicReference<>(false);
        InputStream sentinel = new ByteArrayInputStream("data: ping\n\n".getBytes(StandardCharsets.UTF_8)) {
            @Override public int read() { bodyConsumed.set(true); return super.read(); }
            @Override public int read(byte[] b, int off, int len) { bodyConsumed.set(true); return super.read(b, off, len); }
            @Override public byte[] readAllBytes() { bodyConsumed.set(true); return super.readAllBytes(); }
        };
        HttpResponse<InputStream> streamingResponse = wrap(
                200, Map.of("Content-Type", List.of("text/event-stream")), sentinel);

        WireLoggingInterceptor i = build(WireLoggingInterceptor.Level.BODY);
        HttpResponse<InputStream> out = i.intercept(get("/v1/chat/stream"), chain(req -> streamingResponse));

        assertSame(streamingResponse, out, "streaming response must be passed through unchanged");
        assertFalse(bodyConsumed.get(), "interceptor must not drain a streaming body");
        assertTrue(blocks.get(1).contains("(streaming response — body not captured)"),
                "must announce the skip so log readers know why no body shows up");
    }

    @Test
    void body_replaysResponseBodyDownstream() throws IOException {
        WireLoggingInterceptor i = build(WireLoggingInterceptor.Level.BODY);
        byte[] payload = "0123456789".getBytes(StandardCharsets.UTF_8);
        HttpResponse<InputStream> wrapped = i.intercept(get("/v1/models"),
                chain(req -> response(200, Map.of("Content-Type", List.of("application/json")),
                        new String(payload, StandardCharsets.UTF_8))));
        try (InputStream in = wrapped.body()) {
            assertArrayEquals(payload, in.readAllBytes(),
                    "replayed body must equal the original bytes downstream");
        }
    }

    @Test
    void body_responseBodyReadFailureReportedInLine() {
        WireLoggingInterceptor i = build(WireLoggingInterceptor.Level.BODY);
        InputStream blowup = new InputStream() {
            public int read() throws IOException { throw new IOException("body boom"); }
            public byte[] readAllBytes() throws IOException { throw new IOException("body boom"); }
        };
        HttpResponse<InputStream> resp = wrap(200, Map.of(), blowup);
        i.intercept(get("/v1/models"), chain(req -> resp));

        assertTrue(blocks.get(1).contains("body read failed"),
                "body-read failures must be surfaced in the response block, not thrown");
    }

    @Test
    void body_responseReplayPreservesOriginalAccessors() throws IOException {
        WireLoggingInterceptor i = build(WireLoggingInterceptor.Level.BODY);
        URI originalUri = URI.create("https://api.example.com/v1/models");

        HttpResponse<InputStream> original = wrap(
                200, Map.of("Content-Type", List.of("application/json")),
                new ByteArrayInputStream("{}".getBytes(StandardCharsets.UTF_8)),
                originalUri);

        HttpResponse<InputStream> wrapped = i.intercept(get("/v1/models"), chain(req -> original));
        assertEquals(200, wrapped.statusCode());
        assertEquals(original.request(), wrapped.request());
        assertEquals(originalUri, wrapped.uri());
        assertEquals(HttpClient.Version.HTTP_1_1, wrapped.version());
        assertEquals(Optional.empty(), wrapped.previousResponse());
        assertEquals(Optional.empty(), wrapped.sslSession());
        assertEquals(original.headers().map(), wrapped.headers().map());
    }

    @Test
    void body_requestBodyPublisherErrorReportedInLine() {
        HttpRequest request = HttpRequest.newBuilder(URI.create("https://api.example.com/v1/x"))
                .POST(failingBodyPublisher())
                .build();
        WireLoggingInterceptor i = build(WireLoggingInterceptor.Level.BODY);
        i.intercept(request, chain(req -> response(200, Map.of(), "{}")));
        assertTrue(blocks.getFirst().contains("body read failed"),
                "request-body publisher errors must be surfaced inside the request block, not thrown");
    }

    private static HttpRequest.BodyPublisher failingBodyPublisher() {
        return new HttpRequest.BodyPublisher() {
            public long contentLength() { return -1; }
            public void subscribe(Flow.Subscriber<? super ByteBuffer> sub) {
                sub.onSubscribe(new Flow.Subscription() {
                    public void request(long n) { /* no demand needed */ }
                    public void cancel() { /* noop */ }
                });
                sub.onError(new RuntimeException("publisher boom"));
            }
        };
    }

    // --- builder validation ---------------------------------------------------------------

    @Test
    void builder_defaultLevelIsBasic() {
        WireLoggingInterceptor i = WireLoggingInterceptor.builder().sink(sink).build();
        i.intercept(get("/v1/models"), chain(req -> response(200, Map.of(), "{}")));
        // BASIC = first line + status line per direction; no header / body lines
        assertFalse(allText().contains("\n    "),
                "default BASIC level must not emit header lines");
    }

    @Test
    void builder_defaultRedactedHeadersContainsAuthorization() {
        WireLoggingInterceptor i = WireLoggingInterceptor.builder().sink(sink).build();
        Set<String> redacted = i.redactedHeadersForTest();
        assertTrue(redacted.contains("authorization"),
                "default redaction set must contain `authorization` (lowercased)");
    }

    @Test
    void builder_redactedHeadersReplacesDefault() {
        WireLoggingInterceptor i = WireLoggingInterceptor.builder()
                .sink(sink)
                .redactedHeaders(Set.of("X-Custom-Auth"))
                .build();
        Set<String> redacted = i.redactedHeadersForTest();
        assertEquals(Set.of("x-custom-auth"), redacted,
                "redactedHeaders(...) must replace, not augment, the default set");
    }

    @Test
    void builder_levelRejectsNull() {
        assertThrows(NullPointerException.class,
                () -> WireLoggingInterceptor.builder().level(null));
    }

    @Test
    void builder_sinkRejectsNull() {
        assertThrows(NullPointerException.class,
                () -> WireLoggingInterceptor.builder().sink(null));
    }

    @Test
    void builder_redactedHeadersRejectsNullSet() {
        assertThrows(NullPointerException.class,
                () -> WireLoggingInterceptor.builder().redactedHeaders(null));
    }

    @Test
    void builder_addRedactedHeaderRejectsNull() {
        assertThrows(NullPointerException.class,
                () -> WireLoggingInterceptor.builder().addRedactedHeader(null));
    }

    @Test
    void builder_bodyByteCapRejectsNegative() {
        assertThrows(IllegalArgumentException.class,
                () -> WireLoggingInterceptor.builder().bodyByteCap(-1));
    }

    @Test
    void builder_bodyByteCapAllowsZero() {
        WireLoggingInterceptor i = WireLoggingInterceptor.builder()
                .level(WireLoggingInterceptor.Level.BODY)
                .sink(sink)
                .bodyByteCap(0)
                .build();
        i.intercept(get("/v1/models"),
                chain(req -> response(200, Map.of(), "abc")));
        assertTrue(blocks.get(1).contains("(3 more bytes elided)"),
                "bodyByteCap=0 must elide the entire body");
    }

    @Test
    void builder_settersAreFluent() {
        WireLoggingInterceptor.Builder b = WireLoggingInterceptor.builder();
        assertSame(b, b.level(WireLoggingInterceptor.Level.NONE));
        assertSame(b, b.sink(sink));
        assertSame(b, b.redactedHeaders(Set.of("X")));
        assertSame(b, b.addRedactedHeader("Y"));
        assertSame(b, b.bodyByteCap(123));
    }

    @Test
    void publicConstruction_defaultSinkRoutesToSlf4jWithoutThrowing() {
        // No sink override — uses the default SLF4J wiring (slf4j-nop test dep absorbs it).
        WireLoggingInterceptor i = WireLoggingInterceptor.builder()
                .level(WireLoggingInterceptor.Level.BODY)
                .build();
        assertDoesNotThrow(() -> i.intercept(
                get("/v1/models"),
                chain(req -> response(200, Map.of(), "{}"))));
    }

    // --- helpers ---------------------------------------------------------------------------

    private WireLoggingInterceptor build(WireLoggingInterceptor.Level level) {
        return WireLoggingInterceptor.builder().level(level).sink(sink).build();
    }

    private static HttpRequest get(String path, String... headerKv) {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("https://api.example.com" + path)).GET();
        for (int i = 0; i < headerKv.length; i += 2) {
            b.header(headerKv[i], headerKv[i + 1]);
        }
        return b.build();
    }

    private static HttpRequest postJson(String path, String body) {
        return HttpRequest.newBuilder(URI.create("https://api.example.com" + path))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                .build();
    }

    private static HttpResponse<InputStream> response(int status, Map<String, List<String>> headers, String body) {
        return wrap(status, headers,
                new ByteArrayInputStream(body.getBytes(StandardCharsets.UTF_8)));
    }

    private static HttpResponse<InputStream> wrap(int status, Map<String, List<String>> headers, InputStream body) {
        return wrap(status, headers, body, URI.create("https://api.example.com/"));
    }

    private static HttpResponse<InputStream> wrap(int status, Map<String, List<String>> headers,
                                                  InputStream body, URI uri) {
        return new HttpResponse<>() {
            public int statusCode() { return status; }
            public HttpRequest request() { return null; }
            public Optional<HttpResponse<InputStream>> previousResponse() { return Optional.empty(); }
            public HttpHeaders headers() { return HttpHeaders.of(headers, (k, v) -> true); }
            public InputStream body() { return body; }
            public Optional<SSLSession> sslSession() { return Optional.empty(); }
            public URI uri() { return uri; }
            public HttpClient.Version version() { return HttpClient.Version.HTTP_1_1; }
        };
    }

    /** Single-step Chain: invokes the supplied function on the request and returns. */
    private static Interceptor.Chain chain(Function<HttpRequest, HttpResponse<InputStream>> fn) {
        return new Interceptor.Chain() {
            public HttpResponse<InputStream> proceed(HttpRequest req) { return fn.apply(req); }
            public ObservationHandle observation() { return ObservabilityPlugin.noop().start("test"); }
        };
    }
}

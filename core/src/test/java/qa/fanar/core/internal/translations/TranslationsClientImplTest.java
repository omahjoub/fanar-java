package qa.fanar.core.internal.translations;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import javax.net.ssl.SSLSession;

import org.junit.jupiter.api.Test;

import qa.fanar.core.FanarAuthenticationException;
import qa.fanar.core.FanarTransportException;
import qa.fanar.core.RetryPolicy;
import qa.fanar.core.internal.transport.HttpTransport;
import qa.fanar.core.spi.FanarJsonCodec;
import qa.fanar.core.spi.Interceptor;
import qa.fanar.core.spi.ObservabilityPlugin;
import qa.fanar.core.spi.ObservationHandle;
import qa.fanar.core.translations.LanguagePair;
import qa.fanar.core.translations.TranslationModel;
import qa.fanar.core.translations.TranslationRequest;
import qa.fanar.core.translations.TranslationResponse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TranslationsClientImplTest {

    private static final URI BASE = URI.create("https://api.example.com");

    @Test
    void translateHappyPathReturnsDecodedResponse() {
        TranslationResponse canned = response();
        HttpTransport transport = req -> httpResponse(200, "{}", Map.of());
        TranslationsClientImpl client = build(transport, cannedCodec(canned), List.of());
        assertSame(canned, client.translate(request()));
    }

    @Test
    void translatePostsToTranslationsEndpointWithJsonHeaders() {
        AtomicReference<HttpRequest> captured = new AtomicReference<>();
        HttpTransport transport = req -> { captured.set(req); return httpResponse(200, "{}", Map.of()); };
        build(transport, cannedCodec(response()), List.of()).translate(request());

        HttpRequest sent = captured.get();
        assertEquals("POST", sent.method());
        assertEquals("/v1/translations", sent.uri().getPath());
        assertEquals(Optional.of("application/json"), sent.headers().firstValue("Content-Type"));
        assertEquals(Optional.of("application/json"), sent.headers().firstValue("Accept"));
    }

    @Test
    void translateWritesEncodedRequestAsBody() throws Exception {
        AtomicReference<HttpRequest> captured = new AtomicReference<>();
        HttpTransport transport = req -> { captured.set(req); return httpResponse(200, "{}", Map.of()); };
        FanarJsonCodec markerCodec = new FanarJsonCodec() {
            public <T> T decode(InputStream s, Class<T> t) throws IOException {
                s.readAllBytes();
                return t.cast(response());
            }
            public void encode(OutputStream s, Object v) throws IOException {
                s.write("{\"marker\":true}".getBytes(StandardCharsets.UTF_8));
            }
        };
        build(transport, markerCodec, List.of()).translate(request());
        assertEquals("{\"marker\":true}", bodyOf(captured.get()));
    }

    @Test
    void translateInjectsBearerToken() {
        AtomicReference<HttpRequest> captured = new AtomicReference<>();
        HttpTransport transport = req -> { captured.set(req); return httpResponse(200, "{}", Map.of()); };
        build(transport, cannedCodec(response()), List.of(), "my-token").translate(request());
        assertEquals(Optional.of("Bearer my-token"),
                captured.get().headers().firstValue("Authorization"));
    }

    @Test
    void translateInvokesUserInterceptorsInOrder() {
        AtomicInteger counter = new AtomicInteger();
        AtomicInteger firstSeenAt = new AtomicInteger(-1);
        AtomicInteger secondSeenAt = new AtomicInteger(-1);
        Interceptor first = (req, ch) -> { firstSeenAt.set(counter.incrementAndGet()); return ch.proceed(req); };
        Interceptor second = (req, ch) -> { secondSeenAt.set(counter.incrementAndGet()); return ch.proceed(req); };
        HttpTransport transport = req -> httpResponse(200, "{}", Map.of());

        build(transport, cannedCodec(response()), List.of(first, second)).translate(request());

        assertEquals(1, firstSeenAt.get());
        assertEquals(2, secondSeenAt.get());
    }

    @Test
    void translateAppliesDefaultHeadersAndUserAgent() {
        AtomicReference<HttpRequest> captured = new AtomicReference<>();
        HttpTransport transport = req -> { captured.set(req); return httpResponse(200, "{}", Map.of()); };
        TranslationsClientImpl client = new TranslationsClientImpl(
                BASE, cannedCodec(response()), () -> "t", List.of(), transport,
                ObservabilityPlugin.noop(), RetryPolicy.disabled(),
                Map.of("X-Test", "true"), "Fanar-Java/0.1");
        client.translate(request());

        assertEquals(Optional.of("true"), captured.get().headers().firstValue("X-Test"));
        assertEquals(Optional.of("Fanar-Java/0.1"), captured.get().headers().firstValue("User-Agent"));
    }

    @Test
    void translateOmitsUserAgentWhenNull() {
        AtomicReference<HttpRequest> captured = new AtomicReference<>();
        HttpTransport transport = req -> { captured.set(req); return httpResponse(200, "{}", Map.of()); };
        build(transport, cannedCodec(response()), List.of()).translate(request());
        assertTrue(captured.get().headers().firstValue("User-Agent").isEmpty());
    }

    @Test
    void translateMaps401ToAuthenticationException() {
        HttpTransport transport = req -> httpResponse(401, "{\"error\":{}}", Map.of());
        TranslationsClientImpl client = build(transport, cannedCodec(response()), List.of());
        assertThrows(FanarAuthenticationException.class, () -> client.translate(request()));
    }

    @Test
    void translateWrapsCodecEncodeFailureAsTransportException() {
        FanarJsonCodec failingEncode = new FanarJsonCodec() {
            public <T> T decode(InputStream s, Class<T> t) { return null; }
            public void encode(OutputStream s, Object v) throws IOException { throw new IOException("encode"); }
        };
        HttpTransport transport = req -> { throw new AssertionError("should not reach transport"); };
        TranslationsClientImpl client = build(transport, failingEncode, List.of());
        FanarTransportException ex = assertThrows(FanarTransportException.class, () -> client.translate(request()));
        assertTrue(ex.getMessage().contains("Failed to encode TranslationRequest"));
    }

    @Test
    void translateWrapsCodecDecodeFailureAsTransportException() {
        FanarJsonCodec failingDecode = new FanarJsonCodec() {
            public <T> T decode(InputStream s, Class<T> t) throws IOException { throw new IOException("decode"); }
            public void encode(OutputStream s, Object v) throws IOException { s.write("{}".getBytes(StandardCharsets.UTF_8)); }
        };
        HttpTransport transport = req -> httpResponse(200, "{}", Map.of());
        TranslationsClientImpl client = build(transport, failingDecode, List.of());
        FanarTransportException ex = assertThrows(FanarTransportException.class, () -> client.translate(request()));
        assertTrue(ex.getMessage().contains("Failed to decode TranslationResponse"));
    }

    @Test
    void translateAsyncCompletesSuccessfullyOnVirtualThread() throws Exception {
        TranslationResponse canned = response();
        HttpTransport transport = req -> httpResponse(200, "{}", Map.of());
        TranslationsClientImpl client = build(transport, cannedCodec(canned), List.of());
        CompletableFuture<TranslationResponse> f = client.translateAsync(request());
        assertSame(canned, f.get());
    }

    @Test
    void translateAsyncCompletesExceptionallyOnFailure() {
        HttpTransport transport = req -> httpResponse(401, "{\"error\":{}}", Map.of());
        TranslationsClientImpl client = build(transport, cannedCodec(response()), List.of());
        CompletableFuture<TranslationResponse> f = client.translateAsync(request());
        ExecutionException ex = assertThrows(ExecutionException.class, f::get);
        assertInstanceOf(FanarAuthenticationException.class, ex.getCause());
    }

    @Test
    void observationIsOpenedAndAttributesAreSet() {
        AtomicReference<String> opened = new AtomicReference<>();
        AtomicInteger attributes = new AtomicInteger();
        ObservabilityPlugin plugin = name -> {
            opened.set(name);
            return new ObservationHandle() {
                public ObservationHandle attribute(String k, Object v) { attributes.incrementAndGet(); return this; }
                public ObservationHandle event(String n) { return this; }
                public ObservationHandle error(Throwable t) { return this; }
                public ObservationHandle child(String c) { return this; }
                public Map<String, String> propagationHeaders() { return Map.of(); }
                public void close() { }
            };
        };
        HttpTransport transport = req -> httpResponse(200, "{}", Map.of());
        TranslationsClientImpl client = new TranslationsClientImpl(
                BASE, cannedCodec(response()), () -> "t", List.of(), transport,
                plugin, RetryPolicy.disabled(), Map.of(), null);
        client.translate(request());

        assertEquals("fanar.translations.translate", opened.get());
        assertTrue(attributes.get() >= 4);
    }

    @Test
    void observationPropagationHeadersAreMergedIntoRequest() {
        ObservabilityPlugin plugin = name -> new ObservationHandle() {
            public ObservationHandle attribute(String k, Object v) { return this; }
            public ObservationHandle event(String n) { return this; }
            public ObservationHandle error(Throwable t) { return this; }
            public ObservationHandle child(String c) { return this; }
            public Map<String, String> propagationHeaders() { return Map.of("traceparent", "00-tr"); }
            public void close() { }
        };
        AtomicReference<HttpRequest> captured = new AtomicReference<>();
        HttpTransport transport = req -> { captured.set(req); return httpResponse(200, "{}", Map.of()); };
        TranslationsClientImpl client = new TranslationsClientImpl(
                BASE, cannedCodec(response()), () -> "t", List.of(), transport,
                plugin, RetryPolicy.disabled(), Map.of(), null);
        client.translate(request());
        assertEquals(Optional.of("00-tr"), captured.get().headers().firstValue("traceparent"));
    }

    @Test
    void translateReportsErrorOnObservationWhenInterceptorThrows() {
        AtomicInteger errored = new AtomicInteger();
        ObservabilityPlugin plugin = name -> new ObservationHandle() {
            public ObservationHandle attribute(String k, Object v) { return this; }
            public ObservationHandle event(String n) { return this; }
            public ObservationHandle error(Throwable t) { errored.incrementAndGet(); return this; }
            public ObservationHandle child(String c) { return this; }
            public Map<String, String> propagationHeaders() { return Map.of(); }
            public void close() { }
        };
        Interceptor blowingUp = (req, ch) -> { throw new RuntimeException("boom"); };
        HttpTransport transport = req -> httpResponse(200, "{}", Map.of());
        TranslationsClientImpl client = new TranslationsClientImpl(
                BASE, cannedCodec(response()), () -> "t", List.of(blowingUp), transport,
                plugin, RetryPolicy.disabled(), Map.of(), null);
        assertThrows(RuntimeException.class, () -> client.translate(request()));
        assertEquals(1, errored.get());
    }

    @Test
    void bothMethodsRejectNullRequest() {
        HttpTransport transport = req -> httpResponse(200, "{}", Map.of());
        TranslationsClientImpl client = build(transport, cannedCodec(response()), List.of());
        assertThrows(NullPointerException.class, () -> client.translate(null));
        assertThrows(NullPointerException.class, () -> client.translateAsync(null));
    }

    @Test
    void constructorRejectsNulls() {
        FanarJsonCodec codec = cannedCodec(response());
        HttpTransport transport = req -> httpResponse(200, "{}", Map.of());
        ObservabilityPlugin obs = ObservabilityPlugin.noop();
        RetryPolicy rp = RetryPolicy.disabled();
        assertThrows(NullPointerException.class, () ->
                new TranslationsClientImpl(null, codec, () -> "t", List.of(), transport, obs, rp, Map.of(), null));
        assertThrows(NullPointerException.class, () ->
                new TranslationsClientImpl(BASE, null, () -> "t", List.of(), transport, obs, rp, Map.of(), null));
        assertThrows(NullPointerException.class, () ->
                new TranslationsClientImpl(BASE, codec, null, List.of(), transport, obs, rp, Map.of(), null));
        assertThrows(NullPointerException.class, () ->
                new TranslationsClientImpl(BASE, codec, () -> "t", null, transport, obs, rp, Map.of(), null));
        assertThrows(NullPointerException.class, () ->
                new TranslationsClientImpl(BASE, codec, () -> "t", List.of(), null, obs, rp, Map.of(), null));
        assertThrows(NullPointerException.class, () ->
                new TranslationsClientImpl(BASE, codec, () -> "t", List.of(), transport, null, rp, Map.of(), null));
        assertThrows(NullPointerException.class, () ->
                new TranslationsClientImpl(BASE, codec, () -> "t", List.of(), transport, obs, null, Map.of(), null));
        assertThrows(NullPointerException.class, () ->
                new TranslationsClientImpl(BASE, codec, () -> "t", List.of(), transport, obs, rp, null, null));
    }

    // --- helpers

    private static TranslationsClientImpl build(HttpTransport transport, FanarJsonCodec codec, List<Interceptor> interceptors) {
        return build(transport, codec, interceptors, "stub-token");
    }

    private static TranslationsClientImpl build(HttpTransport transport, FanarJsonCodec codec,
                                                List<Interceptor> interceptors, String token) {
        return new TranslationsClientImpl(
                BASE, codec, () -> token, interceptors,
                transport, ObservabilityPlugin.noop(), RetryPolicy.disabled(), Map.of(), null);
    }

    private static TranslationRequest request() {
        return TranslationRequest.of(TranslationModel.FANAR_SHAHEEN_MT_1, "hello", LanguagePair.EN_AR);
    }

    private static TranslationResponse response() {
        return new TranslationResponse("req_1", "مرحبا");
    }

    private static FanarJsonCodec cannedCodec(TranslationResponse canned) {
        return new FanarJsonCodec() {
            public <T> T decode(InputStream s, Class<T> t) throws IOException {
                s.readAllBytes();
                return t.cast(canned);
            }
            public void encode(OutputStream s, Object v) throws IOException {
                s.write("{}".getBytes(StandardCharsets.UTF_8));
            }
        };
    }

    private static HttpResponse<InputStream> httpResponse(int status, String body, Map<String, List<String>> headers) {
        return new HttpResponse<>() {
            public int statusCode() { return status; }
            public HttpRequest request() { return null; }
            public Optional<HttpResponse<InputStream>> previousResponse() { return Optional.empty(); }
            public HttpHeaders headers() { return HttpHeaders.of(headers, (a, b) -> true); }
            public InputStream body() { return new ByteArrayInputStream(body.getBytes(StandardCharsets.UTF_8)); }
            public Optional<SSLSession> sslSession() { return Optional.empty(); }
            public URI uri() { return URI.create("http://t"); }
            public HttpClient.Version version() { return HttpClient.Version.HTTP_1_1; }
        };
    }

    private static String bodyOf(HttpRequest request) throws Exception {
        HttpRequest.BodyPublisher bp = request.bodyPublisher().orElseThrow();
        AtomicReference<byte[]> buf = new AtomicReference<>(new byte[0]);
        CountDownLatch done = new CountDownLatch(1);
        bp.subscribe(new Flow.Subscriber<>() {
            public void onSubscribe(Flow.Subscription s) { s.request(Long.MAX_VALUE); }
            public void onNext(ByteBuffer b) {
                byte[] curr = buf.get();
                byte[] next = new byte[curr.length + b.remaining()];
                System.arraycopy(curr, 0, next, 0, curr.length);
                b.get(next, curr.length, b.remaining());
                buf.set(next);
            }
            public void onError(Throwable t) { done.countDown(); }
            public void onComplete() { done.countDown(); }
        });
        done.await(1, TimeUnit.SECONDS);
        return new String(buf.get(), StandardCharsets.UTF_8);
    }
}

package qa.fanar.core.internal.models;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import javax.net.ssl.SSLSession;

import org.junit.jupiter.api.Test;

import qa.fanar.core.FanarAuthenticationException;
import qa.fanar.core.FanarTransportException;
import qa.fanar.core.RetryPolicy;
import qa.fanar.core.internal.transport.HttpTransport;
import qa.fanar.core.models.AvailableModel;
import qa.fanar.core.models.ModelsResponse;
import qa.fanar.core.spi.FanarJsonCodec;
import qa.fanar.core.spi.Interceptor;
import qa.fanar.core.spi.ObservabilityPlugin;
import qa.fanar.core.spi.ObservationHandle;

import static org.junit.jupiter.api.Assertions.*;

class ModelsClientImplTest {

    private static final URI BASE = URI.create("https://api.example.com");

    @Test
    void listHappyPathReturnsDecodedResponse() {
        ModelsResponse canned = response();
        HttpTransport transport = req -> httpResponse(200, "{}", Map.of());
        ModelsClientImpl client = build(transport, cannedCodec(canned), List.of());
        assertSame(canned, client.list());
    }

    @Test
    void listSendsGetToModelsEndpointWithJsonAccept() {
        AtomicReference<HttpRequest> captured = new AtomicReference<>();
        HttpTransport transport = req -> { captured.set(req); return httpResponse(200, "{}", Map.of()); };
        build(transport, cannedCodec(response()), List.of()).list();

        HttpRequest sent = captured.get();
        assertEquals("GET", sent.method());
        assertEquals("/v1/models", sent.uri().getPath());
        assertEquals(Optional.of("application/json"), sent.headers().firstValue("Accept"));
    }

    @Test
    void listInjectsBearerToken() {
        AtomicReference<HttpRequest> captured = new AtomicReference<>();
        HttpTransport transport = req -> { captured.set(req); return httpResponse(200, "{}", Map.of()); };
        build(transport, cannedCodec(response()), List.of(), "my-token").list();
        assertEquals(Optional.of("Bearer my-token"),
                captured.get().headers().firstValue("Authorization"));
    }

    @Test
    void listInvokesUserInterceptorsInOrder() {
        AtomicInteger counter = new AtomicInteger();
        AtomicInteger firstSeenAt = new AtomicInteger(-1);
        AtomicInteger secondSeenAt = new AtomicInteger(-1);
        Interceptor first = (req, ch) -> { firstSeenAt.set(counter.incrementAndGet()); return ch.proceed(req); };
        Interceptor second = (req, ch) -> { secondSeenAt.set(counter.incrementAndGet()); return ch.proceed(req); };
        HttpTransport transport = req -> httpResponse(200, "{}", Map.of());

        build(transport, cannedCodec(response()), List.of(first, second)).list();

        assertEquals(1, firstSeenAt.get());
        assertEquals(2, secondSeenAt.get());
    }

    @Test
    void listAppliesDefaultHeadersAndUserAgent() {
        AtomicReference<HttpRequest> captured = new AtomicReference<>();
        HttpTransport transport = req -> { captured.set(req); return httpResponse(200, "{}", Map.of()); };
        ModelsClientImpl client = new ModelsClientImpl(
                BASE, cannedCodec(response()), () -> "t", List.of(), transport,
                ObservabilityPlugin.noop(), RetryPolicy.disabled(),
                Map.of("X-Test", "true"), "Fanar-Java/0.1");
        client.list();

        assertEquals(Optional.of("true"), captured.get().headers().firstValue("X-Test"));
        assertEquals(Optional.of("Fanar-Java/0.1"), captured.get().headers().firstValue("User-Agent"));
    }

    @Test
    void listOmitsUserAgentWhenNull() {
        AtomicReference<HttpRequest> captured = new AtomicReference<>();
        HttpTransport transport = req -> { captured.set(req); return httpResponse(200, "{}", Map.of()); };
        build(transport, cannedCodec(response()), List.of()).list();
        assertTrue(captured.get().headers().firstValue("User-Agent").isEmpty());
    }

    @Test
    void listMaps401ToAuthenticationException() {
        HttpTransport transport = req -> httpResponse(401, "{\"error\":{}}", Map.of());
        ModelsClientImpl client = build(transport, cannedCodec(response()), List.of());
        assertThrows(FanarAuthenticationException.class, client::list);
    }

    @Test
    void listWrapsCodecDecodeFailureAsTransportException() {
        FanarJsonCodec failingDecode = new FanarJsonCodec() {
            public <T> T decode(InputStream s, Class<T> t) throws IOException { throw new IOException("decode"); }
            public void encode(OutputStream s, Object v) { /* no body to encode for GET */ }
        };
        HttpTransport transport = req -> httpResponse(200, "{}", Map.of());
        ModelsClientImpl client = build(transport, failingDecode, List.of());
        FanarTransportException ex = assertThrows(FanarTransportException.class, client::list);
        assertTrue(ex.getMessage().contains("Failed to decode ModelsResponse"));
    }

    @Test
    void listAsyncCompletesSuccessfullyOnVirtualThread() throws Exception {
        ModelsResponse canned = response();
        HttpTransport transport = req -> httpResponse(200, "{}", Map.of());
        ModelsClientImpl client = build(transport, cannedCodec(canned), List.of());
        CompletableFuture<ModelsResponse> f = client.listAsync();
        assertSame(canned, f.get());
    }

    @Test
    void listAsyncCompletesExceptionallyOnFailure() {
        HttpTransport transport = req -> httpResponse(401, "{\"error\":{}}", Map.of());
        ModelsClientImpl client = build(transport, cannedCodec(response()), List.of());
        CompletableFuture<ModelsResponse> f = client.listAsync();
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
        ModelsClientImpl client = new ModelsClientImpl(
                BASE, cannedCodec(response()), () -> "t", List.of(), transport,
                plugin, RetryPolicy.disabled(), Map.of(), null);
        client.list();

        assertEquals("fanar.models.list", opened.get());
        assertTrue(attributes.get() >= 3, "expected at least method/url/status attributes");
    }

    @Test
    void observationPropagationHeadersAreMergedIntoRequest() {
        ObservabilityPlugin plugin = name -> new ObservationHandle() {
            public ObservationHandle attribute(String k, Object v) { return this; }
            public ObservationHandle event(String n) { return this; }
            public ObservationHandle error(Throwable t) { return this; }
            public ObservationHandle child(String c) { return this; }
            public Map<String, String> propagationHeaders() { return Map.of("traceparent", "00-zzz"); }
            public void close() { }
        };
        AtomicReference<HttpRequest> captured = new AtomicReference<>();
        HttpTransport transport = req -> { captured.set(req); return httpResponse(200, "{}", Map.of()); };
        ModelsClientImpl client = new ModelsClientImpl(
                BASE, cannedCodec(response()), () -> "t", List.of(), transport,
                plugin, RetryPolicy.disabled(), Map.of(), null);
        client.list();
        assertEquals(Optional.of("00-zzz"), captured.get().headers().firstValue("traceparent"));
    }

    @Test
    void listReportsErrorOnObservationWhenInterceptorThrows() {
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
        ModelsClientImpl client = new ModelsClientImpl(
                BASE, cannedCodec(response()), () -> "t", List.of(blowingUp), transport,
                plugin, RetryPolicy.disabled(), Map.of(), null);
        assertThrows(RuntimeException.class, client::list);
        assertEquals(1, errored.get());
    }

    @Test
    void constructorRejectsNulls() {
        FanarJsonCodec codec = cannedCodec(response());
        HttpTransport transport = req -> httpResponse(200, "{}", Map.of());
        ObservabilityPlugin obs = ObservabilityPlugin.noop();
        RetryPolicy rp = RetryPolicy.disabled();
        assertThrows(NullPointerException.class, () ->
                new ModelsClientImpl(null, codec, () -> "t", List.of(), transport, obs, rp, Map.of(), null));
        assertThrows(NullPointerException.class, () ->
                new ModelsClientImpl(BASE, null, () -> "t", List.of(), transport, obs, rp, Map.of(), null));
        assertThrows(NullPointerException.class, () ->
                new ModelsClientImpl(BASE, codec, null, List.of(), transport, obs, rp, Map.of(), null));
        assertThrows(NullPointerException.class, () ->
                new ModelsClientImpl(BASE, codec, () -> "t", null, transport, obs, rp, Map.of(), null));
        assertThrows(NullPointerException.class, () ->
                new ModelsClientImpl(BASE, codec, () -> "t", List.of(), null, obs, rp, Map.of(), null));
        assertThrows(NullPointerException.class, () ->
                new ModelsClientImpl(BASE, codec, () -> "t", List.of(), transport, null, rp, Map.of(), null));
        assertThrows(NullPointerException.class, () ->
                new ModelsClientImpl(BASE, codec, () -> "t", List.of(), transport, obs, null, Map.of(), null));
        assertThrows(NullPointerException.class, () ->
                new ModelsClientImpl(BASE, codec, () -> "t", List.of(), transport, obs, rp, null, null));
    }

    // --- helpers

    private static ModelsClientImpl build(HttpTransport transport, FanarJsonCodec codec, List<Interceptor> interceptors) {
        return build(transport, codec, interceptors, "stub-token");
    }

    private static ModelsClientImpl build(HttpTransport transport, FanarJsonCodec codec,
                                          List<Interceptor> interceptors, String token) {
        return new ModelsClientImpl(
                BASE, codec, () -> token, interceptors,
                transport, ObservabilityPlugin.noop(), RetryPolicy.disabled(), Map.of(), null);
    }

    private static ModelsResponse response() {
        return new ModelsResponse("req_1",
                List.of(new AvailableModel("Fanar", "model", 0L, "fanar")));
    }

    private static FanarJsonCodec cannedCodec(ModelsResponse canned) {
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
}

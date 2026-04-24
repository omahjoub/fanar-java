package qa.fanar.core.internal.chat;

import org.junit.jupiter.api.Test;
import qa.fanar.core.FanarAuthenticationException;
import qa.fanar.core.FanarRateLimitException;
import qa.fanar.core.FanarTransportException;
import qa.fanar.core.chat.*;
import qa.fanar.core.internal.transport.HttpTransport;
import qa.fanar.core.spi.FanarJsonCodec;
import qa.fanar.core.spi.Interceptor;
import qa.fanar.core.spi.ObservabilityPlugin;

import javax.net.ssl.SSLSession;
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
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class ChatClientImplTest {

    private static final URI BASE = URI.create("https://api.example.com");

    @Test
    void sendHappyPathReturnsDecodedResponse() {
        ChatResponse canned = chatResponse();
        HttpTransport transport = req -> response(200, "{}", Map.of());

        ChatClientImpl client = build(transport, cannedCodec(canned), List.of());
        ChatResponse actual = client.send(sampleRequest());

        assertSame(canned, actual);
    }

    @Test
    void sendPostsToChatCompletionsEndpoint() {
        AtomicReference<HttpRequest> captured = new AtomicReference<>();
        HttpTransport transport = req -> {
            captured.set(req);
            return response(200, "{}", Map.of());
        };
        build(transport, cannedCodec(chatResponse()), List.of()).send(sampleRequest());

        HttpRequest sent = captured.get();
        assertEquals("POST", sent.method());
        assertEquals("/v1/chat/completions", sent.uri().getPath());
        assertEquals(Optional.of("application/json"), sent.headers().firstValue("Content-Type"));
        assertEquals(Optional.of("application/json"), sent.headers().firstValue("Accept"));
    }

    @Test
    void sendAuthorizationHeaderIsInjected() {
        AtomicReference<HttpRequest> captured = new AtomicReference<>();
        HttpTransport transport = req -> { captured.set(req); return response(200, "{}", Map.of()); };
        build(transport, cannedCodec(chatResponse()), List.of(), "my-token").send(sampleRequest());

        assertEquals(Optional.of("Bearer my-token"),
                captured.get().headers().firstValue("Authorization"));
    }

    @Test
    void sendInvokesUserInterceptorsInOrderAroundAuth() {
        AtomicInteger counter = new AtomicInteger();
        AtomicInteger firstSeenAt = new AtomicInteger(-1);
        AtomicInteger secondSeenAt = new AtomicInteger(-1);
        Interceptor first = (req, ch) -> { firstSeenAt.set(counter.incrementAndGet()); return ch.proceed(req); };
        Interceptor second = (req, ch) -> { secondSeenAt.set(counter.incrementAndGet()); return ch.proceed(req); };

        HttpTransport transport = req -> response(200, "{}", Map.of());
        build(transport, cannedCodec(chatResponse()), List.of(first, second)).send(sampleRequest());

        assertEquals(1, firstSeenAt.get());
        assertEquals(2, secondSeenAt.get());
    }

    @Test
    void sendAppliesDefaultHeadersAndUserAgent() {
        AtomicReference<HttpRequest> captured = new AtomicReference<>();
        HttpTransport transport = req -> { captured.set(req); return response(200, "{}", Map.of()); };

        ChatClientImpl client = new ChatClientImpl(
                BASE,
                cannedCodec(chatResponse()),
                () -> "tok",
                List.of(),
                transport,
                ObservabilityPlugin.noop(),
                Map.of("X-Trace-Id", "abc"),
                "my-app/1.0");
        client.send(sampleRequest());

        HttpRequest sent = captured.get();
        assertEquals(Optional.of("abc"), sent.headers().firstValue("X-Trace-Id"));
        assertEquals(Optional.of("my-app/1.0"), sent.headers().firstValue("User-Agent"));
    }

    @Test
    void sendWrapsCodecEncodeFailureAsTransportException() {
        FanarJsonCodec failing = new FanarJsonCodec() {
            public <T> T decode(InputStream s, Class<T> t) throws IOException { throw new IOException("decode"); }
            public void encode(OutputStream s, Object v) throws IOException { throw new IOException("encode"); }
        };
        HttpTransport transport = req -> { throw new AssertionError("should not reach transport"); };

        ChatClientImpl client = build(transport, failing, List.of());
        assertThrows(FanarTransportException.class, () -> client.send(sampleRequest()));
    }

    @Test
    void sendWrapsCodecDecodeFailureAsTransportException() {
        FanarJsonCodec decodingFailure = new FanarJsonCodec() {
            public <T> T decode(InputStream s, Class<T> t) throws IOException { throw new IOException("decode"); }
            public void encode(OutputStream s, Object v) throws IOException { s.write(new byte[]{'{','}'}); }
        };
        HttpTransport transport = req -> response(200, "{}", Map.of());

        ChatClientImpl client = build(transport, decodingFailure, List.of());
        assertThrows(FanarTransportException.class, () -> client.send(sampleRequest()));
    }

    @Test
    void sendMaps401ToAuthenticationException() {
        HttpTransport transport = req -> response(401, "bad token", Map.of());
        ChatClientImpl client = build(transport, cannedCodec(chatResponse()), List.of());
        assertThrows(FanarAuthenticationException.class, () -> client.send(sampleRequest()));
    }

    @Test
    void sendMaps429WithRetryAfter() {
        HttpTransport transport = req -> response(429, "too fast",
                Map.of("Retry-After", List.of("5")));
        ChatClientImpl client = build(transport, cannedCodec(chatResponse()), List.of());
        FanarRateLimitException ex = assertThrows(FanarRateLimitException.class, () -> client.send(sampleRequest()));
        assertEquals(java.time.Duration.ofSeconds(5), ex.retryAfter());
    }

    @Test
    void sendPropagatesRuntimeExceptionFromInterceptor() {
        RuntimeException boom = new RuntimeException("custom interceptor failure");
        Interceptor bad = (req, ch) -> { throw boom; };
        HttpTransport transport = req -> { throw new AssertionError("should not reach transport"); };

        ChatClientImpl client = build(transport, cannedCodec(chatResponse()), List.of(bad));
        RuntimeException actual = assertThrows(RuntimeException.class, () -> client.send(sampleRequest()));
        assertSame(boom, actual);
    }

    @Test
    void sendAsyncCompletesSuccessfullyOnVirtualThread() throws Exception {
        ChatResponse canned = chatResponse();
        HttpTransport transport = req -> response(200, "{}", Map.of());

        ChatClientImpl client = build(transport, cannedCodec(canned), List.of());
        CompletableFuture<ChatResponse> future = client.sendAsync(sampleRequest());

        assertSame(canned, future.get(5, TimeUnit.SECONDS));
    }

    @Test
    void sendAsyncCompletesExceptionallyOnFailure() {
        FanarJsonCodec failing = new FanarJsonCodec() {
            public <T> T decode(InputStream s, Class<T> t) throws IOException { throw new IOException("decode"); }
            public void encode(OutputStream s, Object v) throws IOException { throw new IOException("encode"); }
        };
        HttpTransport transport = req -> { throw new AssertionError("should not reach"); };

        ChatClientImpl client = build(transport, failing, List.of());
        CompletableFuture<ChatResponse> future = client.sendAsync(sampleRequest());

        ExecutionException ex = assertThrows(ExecutionException.class,
                () -> future.get(5, TimeUnit.SECONDS));
        assertInstanceOf(FanarTransportException.class, ex.getCause());
    }

    @Test
    void streamThrowsUnsupportedOperationException() {
        HttpTransport transport = req -> response(200, "{}", Map.of());
        ChatClientImpl client = build(transport, cannedCodec(chatResponse()), List.of());
        UnsupportedOperationException ex = assertThrows(UnsupportedOperationException.class,
                () -> client.stream(sampleRequest()));
        assertTrue(ex.getMessage().contains("SSE"));
    }

    @Test
    void allMethodsRejectNullRequest() {
        HttpTransport transport = req -> response(200, "{}", Map.of());
        ChatClientImpl client = build(transport, cannedCodec(chatResponse()), List.of());
        assertThrows(NullPointerException.class, () -> client.send(null));
        assertThrows(NullPointerException.class, () -> client.sendAsync(null));
        assertThrows(NullPointerException.class, () -> client.stream(null));
    }

    @Test
    void constructorRejectsNulls() {
        FanarJsonCodec codec = cannedCodec(chatResponse());
        HttpTransport transport = req -> response(200, "{}", Map.of());
        ObservabilityPlugin obs = ObservabilityPlugin.noop();
        assertThrows(NullPointerException.class, () ->
                new ChatClientImpl(null, codec, () -> "t", List.of(), transport, obs, Map.of(), null));
        assertThrows(NullPointerException.class, () ->
                new ChatClientImpl(BASE, null, () -> "t", List.of(), transport, obs, Map.of(), null));
        assertThrows(NullPointerException.class, () ->
                new ChatClientImpl(BASE, codec, null, List.of(), transport, obs, Map.of(), null));
        assertThrows(NullPointerException.class, () ->
                new ChatClientImpl(BASE, codec, () -> "t", null, transport, obs, Map.of(), null));
        assertThrows(NullPointerException.class, () ->
                new ChatClientImpl(BASE, codec, () -> "t", List.of(), null, obs, Map.of(), null));
        assertThrows(NullPointerException.class, () ->
                new ChatClientImpl(BASE, codec, () -> "t", List.of(), transport, null, Map.of(), null));
        assertThrows(NullPointerException.class, () ->
                new ChatClientImpl(BASE, codec, () -> "t", List.of(), transport, obs, null, null));
    }

    @Test
    void observationIsOpenedAndAttributesAreSet() {
        AtomicReference<String> opened = new AtomicReference<>();
        AtomicInteger attributes = new AtomicInteger();
        ObservabilityPlugin plugin = name -> {
            opened.set(name);
            return new qa.fanar.core.spi.ObservationHandle() {
                public qa.fanar.core.spi.ObservationHandle attribute(String k, Object v) { attributes.incrementAndGet(); return this; }
                public qa.fanar.core.spi.ObservationHandle event(String n) { return this; }
                public qa.fanar.core.spi.ObservationHandle error(Throwable t) { return this; }
                public qa.fanar.core.spi.ObservationHandle child(String c) { return this; }
                public java.util.Map<String, String> propagationHeaders() { return Map.of(); }
                public void close() { }
            };
        };
        HttpTransport transport = req -> response(200, "{}", Map.of());
        ChatClientImpl client = new ChatClientImpl(BASE, cannedCodec(chatResponse()), () -> "t", List.of(),
                transport, plugin, Map.of(), null);

        client.send(sampleRequest());

        assertEquals("fanar.chat", opened.get());
        assertTrue(attributes.get() >= 3); // at least model, method, url, status
    }

    @Test
    void observationPropagationHeadersAreMergedIntoRequest() {
        ObservabilityPlugin plugin = name -> new qa.fanar.core.spi.ObservationHandle() {
            public qa.fanar.core.spi.ObservationHandle attribute(String k, Object v) { return this; }
            public qa.fanar.core.spi.ObservationHandle event(String n) { return this; }
            public qa.fanar.core.spi.ObservationHandle error(Throwable t) { return this; }
            public qa.fanar.core.spi.ObservationHandle child(String c) { return this; }
            public Map<String, String> propagationHeaders() { return Map.of("traceparent", "00-abc"); }
            public void close() { }
        };
        AtomicReference<HttpRequest> captured = new AtomicReference<>();
        HttpTransport transport = req -> { captured.set(req); return response(200, "{}", Map.of()); };
        ChatClientImpl client = new ChatClientImpl(BASE, cannedCodec(chatResponse()), () -> "t", List.of(),
                transport, plugin, Map.of(), null);

        client.send(sampleRequest());

        assertEquals(Optional.of("00-abc"), captured.get().headers().firstValue("traceparent"));
    }

    // --- helpers

    private static ChatClientImpl build(HttpTransport transport, FanarJsonCodec codec, List<Interceptor> interceptors) {
        return build(transport, codec, interceptors, "stub-token");
    }

    private static ChatClientImpl build(HttpTransport transport, FanarJsonCodec codec,
                                        List<Interceptor> interceptors, String token) {
        return new ChatClientImpl(
                BASE, codec, () -> token, interceptors,
                transport, ObservabilityPlugin.noop(), Map.of(), null);
    }

    private static ChatRequest sampleRequest() {
        return ChatRequest.builder()
                .model(ChatModel.FANAR)
                .addMessage(UserMessage.of("hello"))
                .build();
    }

    private static ChatResponse chatResponse() {
        ChatChoice choice = new ChatChoice(FinishReason.STOP, 0, new ChatMessage(null, null, null), null);
        return new ChatResponse("c_1", List.of(choice), 0L, "Fanar", null, null);
    }

    private static FanarJsonCodec cannedCodec(ChatResponse canned) {
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

    private static HttpResponse<InputStream> response(int status, String body, Map<String, List<String>> headers) {
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

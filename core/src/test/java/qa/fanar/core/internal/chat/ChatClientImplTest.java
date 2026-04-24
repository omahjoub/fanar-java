package qa.fanar.core.internal.chat;

import org.junit.jupiter.api.Test;
import qa.fanar.core.FanarAuthenticationException;
import qa.fanar.core.FanarRateLimitException;
import qa.fanar.core.FanarTransportException;
import qa.fanar.core.RetryPolicy;
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
import java.util.concurrent.Flow;
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
                RetryPolicy.disabled(),
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
    void streamReturnsPublisherAndInjectsStreamFlag() throws Exception {
        AtomicReference<HttpRequest> captured = new AtomicReference<>();
        HttpTransport transport = req -> {
            captured.set(req);
            return response(200, "", Map.of());
        };
        ChatClientImpl client = build(transport, cannedCodec(chatResponse()), List.of());

        Flow.Publisher<StreamEvent> publisher = client.stream(sampleRequest());
        assertNotNull(publisher);

        HttpRequest sent = captured.get();
        assertEquals(Optional.of("text/event-stream"), sent.headers().firstValue("Accept"));
        assertEquals("/v1/chat/completions", sent.uri().getPath());

        // Body was rewritten to contain "stream":true.
        String sentBody = bodyOf(sent);
        assertTrue(sentBody.contains("\"stream\":true"), "body: " + sentBody);
    }

    @Test
    void streamMaps4xxToTypedException() {
        HttpTransport transport = req -> response(401, "bad token", Map.of());
        ChatClientImpl client = build(transport, cannedCodec(chatResponse()), List.of());
        assertThrows(FanarAuthenticationException.class, () -> client.stream(sampleRequest()));
    }

    @Test
    void streamWrapsCodecEncodeFailureAsTransportException() {
        FanarJsonCodec failing = new FanarJsonCodec() {
            public <T> T decode(InputStream s, Class<T> t) throws IOException { throw new IOException("decode"); }
            public void encode(OutputStream s, Object v) throws IOException { throw new IOException("encode"); }
        };
        HttpTransport transport = req -> { throw new AssertionError("should not reach transport"); };

        ChatClientImpl client = build(transport, failing, List.of());
        assertThrows(FanarTransportException.class, () -> client.stream(sampleRequest()));
    }

    @Test
    void streamInjectsStreamFlagBeforeExistingFields() throws Exception {
        FanarJsonCodec fieldCodec = new FanarJsonCodec() {
            public <T> T decode(InputStream s, Class<T> t) throws IOException {
                s.readAllBytes(); return t.cast(chatResponse());
            }
            public void encode(OutputStream s, Object v) throws IOException {
                s.write("{\"model\":\"Fanar\"}".getBytes(StandardCharsets.UTF_8));
            }
        };
        AtomicReference<HttpRequest> captured = new AtomicReference<>();
        HttpTransport transport = req -> {
            captured.set(req);
            return response(200, "", Map.of());
        };
        ChatClientImpl client = build(transport, fieldCodec, List.of());
        client.stream(sampleRequest());

        assertEquals("{\"stream\":true,\"model\":\"Fanar\"}", bodyOf(captured.get()));
    }

    @Test
    void streamRejectsEmptyCodecOutput() {
        // Codec writes nothing — injectStreamFlag guard catches `src.length < 2`.
        FanarJsonCodec emptyCodec = new FanarJsonCodec() {
            public <T> T decode(InputStream s, Class<T> t) throws IOException { throw new IOException("decode"); }
            public void encode(OutputStream s, Object v) { /* write nothing */ }
        };
        HttpTransport transport = req -> { throw new AssertionError("should not reach transport"); };
        ChatClientImpl client = build(transport, emptyCodec, List.of());
        FanarTransportException ex = assertThrows(FanarTransportException.class,
                () -> client.stream(sampleRequest()));
        assertTrue(ex.getMessage().contains("unexpected body shape"));
    }

    @Test
    void streamRejectsNonObjectCodecOutput() {
        // Codec produces a JSON array — injectStreamFlag guard must refuse.
        FanarJsonCodec arrayCodec = new FanarJsonCodec() {
            public <T> T decode(InputStream s, Class<T> t) throws IOException { throw new IOException("decode"); }
            public void encode(OutputStream s, Object v) throws IOException {
                s.write("[]".getBytes(StandardCharsets.UTF_8));
            }
        };
        HttpTransport transport = req -> { throw new AssertionError("should not reach transport"); };

        ChatClientImpl client = build(transport, arrayCodec, List.of());
        FanarTransportException ex = assertThrows(FanarTransportException.class,
                () -> client.stream(sampleRequest()));
        assertTrue(ex.getMessage().contains("unexpected body shape"),
                "message: " + ex.getMessage());
    }

    private static String bodyOf(HttpRequest request) throws Exception {
        HttpRequest.BodyPublisher bp = request.bodyPublisher().orElseThrow();
        java.util.concurrent.atomic.AtomicReference<byte[]> buf =
                new java.util.concurrent.atomic.AtomicReference<>(new byte[0]);
        java.util.concurrent.CountDownLatch done = new java.util.concurrent.CountDownLatch(1);
        bp.subscribe(new java.util.concurrent.Flow.Subscriber<>() {
            public void onSubscribe(java.util.concurrent.Flow.Subscription s) { s.request(Long.MAX_VALUE); }
            public void onNext(java.nio.ByteBuffer b) {
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
        RetryPolicy rp = RetryPolicy.disabled();
        assertThrows(NullPointerException.class, () ->
                new ChatClientImpl(null, codec, () -> "t", List.of(), transport, obs, rp, Map.of(), null));
        assertThrows(NullPointerException.class, () ->
                new ChatClientImpl(BASE, null, () -> "t", List.of(), transport, obs, rp, Map.of(), null));
        assertThrows(NullPointerException.class, () ->
                new ChatClientImpl(BASE, codec, null, List.of(), transport, obs, rp, Map.of(), null));
        assertThrows(NullPointerException.class, () ->
                new ChatClientImpl(BASE, codec, () -> "t", null, transport, obs, rp, Map.of(), null));
        assertThrows(NullPointerException.class, () ->
                new ChatClientImpl(BASE, codec, () -> "t", List.of(), null, obs, rp, Map.of(), null));
        assertThrows(NullPointerException.class, () ->
                new ChatClientImpl(BASE, codec, () -> "t", List.of(), transport, null, rp, Map.of(), null));
        assertThrows(NullPointerException.class, () ->
                new ChatClientImpl(BASE, codec, () -> "t", List.of(), transport, obs, null, Map.of(), null));
        assertThrows(NullPointerException.class, () ->
                new ChatClientImpl(BASE, codec, () -> "t", List.of(), transport, obs, rp, null, null));
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
                transport, plugin, RetryPolicy.disabled(), Map.of(), null);

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
                transport, plugin, RetryPolicy.disabled(), Map.of(), null);

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
                transport, ObservabilityPlugin.noop(), RetryPolicy.disabled(), Map.of(), null);
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

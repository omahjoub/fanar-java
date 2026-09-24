package qa.fanar.core.internal.sadiq;

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
import java.time.Duration;
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
import qa.fanar.core.FanarOverloadedException;
import qa.fanar.core.FanarTransportException;
import qa.fanar.core.RetryPolicy;
import qa.fanar.core.chat.ChatModel;
import qa.fanar.core.chat.ChoiceError;
import qa.fanar.core.chat.ChoiceToken;
import qa.fanar.core.chat.DoneChunk;
import qa.fanar.core.chat.ErrorChunk;
import qa.fanar.core.chat.ProgressChunk;
import qa.fanar.core.chat.ProgressMessage;
import qa.fanar.core.chat.TokenChunk;
import qa.fanar.core.internal.transport.HttpTransport;
import qa.fanar.core.sadiq.DeepResearchEvent;
import qa.fanar.core.sadiq.DeepResearchReport;
import qa.fanar.core.sadiq.DeepResearchRequest;
import qa.fanar.core.sadiq.ReportChunk;
import qa.fanar.core.sadiq.SadiqValidationRequest;
import qa.fanar.core.sadiq.SadiqValidationResponse;
import qa.fanar.core.spi.FanarJsonCodec;
import qa.fanar.core.spi.Interceptor;
import qa.fanar.core.spi.ObservabilityPlugin;
import qa.fanar.core.spi.ObservationHandle;
import qa.fanar.testsupport.CollectingSubscriber;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SadiqClientImplTest {

    private static final URI BASE = URI.create("https://api.example.com");

    @Test
    void validateHappyPathReturnsDecodedResponse() {
        SadiqValidationResponse canned = response();
        HttpTransport transport = req -> httpResponse(200, "{}", Map.of());
        SadiqClientImpl client = build(transport, cannedCodec(canned), List.of());
        assertSame(canned, client.validate(request()));
    }

    @Test
    void validatePostsToSadiqValidateEndpointWithJsonHeaders() {
        AtomicReference<HttpRequest> captured = new AtomicReference<>();
        HttpTransport transport = req -> { captured.set(req); return httpResponse(200, "{}", Map.of()); };
        build(transport, cannedCodec(response()), List.of()).validate(request());

        HttpRequest sent = captured.get();
        assertEquals("POST", sent.method());
        assertEquals("/v1/sadiq/validate", sent.uri().getPath());
        assertEquals(Optional.of("application/json"), sent.headers().firstValue("Content-Type"));
        assertEquals(Optional.of("application/json"), sent.headers().firstValue("Accept"));
    }

    @Test
    void validateWritesEncodedRequestAsBody() throws Exception {
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
        build(transport, markerCodec, List.of()).validate(request());

        assertEquals("{\"marker\":true}", bodyOf(captured.get()));
    }

    @Test
    void validateInjectsBearerToken() {
        AtomicReference<HttpRequest> captured = new AtomicReference<>();
        HttpTransport transport = req -> { captured.set(req); return httpResponse(200, "{}", Map.of()); };
        build(transport, cannedCodec(response()), List.of(), "my-token").validate(request());
        assertEquals(Optional.of("Bearer my-token"),
                captured.get().headers().firstValue("Authorization"));
    }

    @Test
    void validateInvokesUserInterceptorsInOrder() {
        AtomicInteger counter = new AtomicInteger();
        AtomicInteger firstSeenAt = new AtomicInteger(-1);
        AtomicInteger secondSeenAt = new AtomicInteger(-1);
        Interceptor first = (req, ch) -> { firstSeenAt.set(counter.incrementAndGet()); return ch.proceed(req); };
        Interceptor second = (req, ch) -> { secondSeenAt.set(counter.incrementAndGet()); return ch.proceed(req); };
        HttpTransport transport = req -> httpResponse(200, "{}", Map.of());

        build(transport, cannedCodec(response()), List.of(first, second)).validate(request());

        assertEquals(1, firstSeenAt.get());
        assertEquals(2, secondSeenAt.get());
    }

    @Test
    void validateAppliesDefaultHeadersAndUserAgent() {
        AtomicReference<HttpRequest> captured = new AtomicReference<>();
        HttpTransport transport = req -> { captured.set(req); return httpResponse(200, "{}", Map.of()); };
        SadiqClientImpl client = new SadiqClientImpl(
                BASE, cannedCodec(response()), () -> "t", List.of(), transport,
                ObservabilityPlugin.noop(), RetryPolicy.disabled(),
                Map.of("X-Test", "true"), "Fanar-Java/0.1");
        client.validate(request());

        assertEquals(Optional.of("true"), captured.get().headers().firstValue("X-Test"));
        assertEquals(Optional.of("Fanar-Java/0.1"), captured.get().headers().firstValue("User-Agent"));
    }

    @Test
    void validateOmitsUserAgentWhenNull() {
        AtomicReference<HttpRequest> captured = new AtomicReference<>();
        HttpTransport transport = req -> { captured.set(req); return httpResponse(200, "{}", Map.of()); };
        build(transport, cannedCodec(response()), List.of()).validate(request());
        assertTrue(captured.get().headers().firstValue("User-Agent").isEmpty());
    }

    @Test
    void validateMaps401ToAuthenticationException() {
        HttpTransport transport = req -> httpResponse(401, "{\"error\":{}}", Map.of());
        SadiqClientImpl client = build(transport, cannedCodec(response()), List.of());
        assertThrows(FanarAuthenticationException.class, () -> client.validate(request()));
    }

    @Test
    void validateWrapsCodecEncodeFailureAsTransportException() {
        FanarJsonCodec failingEncode = new FanarJsonCodec() {
            public <T> T decode(InputStream s, Class<T> t) { return null; }
            public void encode(OutputStream s, Object v) throws IOException { throw new IOException("encode"); }
        };
        HttpTransport transport = req -> { throw new AssertionError("should not reach transport"); };
        SadiqClientImpl client = build(transport, failingEncode, List.of());
        FanarTransportException ex = assertThrows(FanarTransportException.class, () -> client.validate(request()));
        assertTrue(ex.getMessage().contains("Failed to encode SadiqValidationRequest"));
    }

    @Test
    void validateWrapsCodecDecodeFailureAsTransportException() {
        FanarJsonCodec failingDecode = new FanarJsonCodec() {
            public <T> T decode(InputStream s, Class<T> t) throws IOException { throw new IOException("decode"); }
            public void encode(OutputStream s, Object v) throws IOException { s.write("{}".getBytes(StandardCharsets.UTF_8)); }
        };
        HttpTransport transport = req -> httpResponse(200, "{}", Map.of());
        SadiqClientImpl client = build(transport, failingDecode, List.of());
        FanarTransportException ex = assertThrows(FanarTransportException.class, () -> client.validate(request()));
        assertTrue(ex.getMessage().contains("Failed to decode SadiqValidationResponse"));
    }

    @Test
    void validateAsyncCompletesSuccessfullyOnVirtualThread() throws Exception {
        SadiqValidationResponse canned = response();
        HttpTransport transport = req -> httpResponse(200, "{}", Map.of());
        SadiqClientImpl client = build(transport, cannedCodec(canned), List.of());
        CompletableFuture<SadiqValidationResponse> f = client.validateAsync(request());
        assertSame(canned, f.get());
    }

    @Test
    void validateAsyncCompletesExceptionallyOnFailure() {
        HttpTransport transport = req -> httpResponse(401, "{\"error\":{}}", Map.of());
        SadiqClientImpl client = build(transport, cannedCodec(response()), List.of());
        CompletableFuture<SadiqValidationResponse> f = client.validateAsync(request());
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
        SadiqClientImpl client = new SadiqClientImpl(
                BASE, cannedCodec(response()), () -> "t", List.of(), transport,
                plugin, RetryPolicy.disabled(), Map.of(), null);
        client.validate(request());

        assertEquals("fanar.sadiq.validate", opened.get());
        assertTrue(attributes.get() >= 4); // model + method + url + status
    }

    @Test
    void observationPropagationHeadersAreMergedIntoRequest() {
        ObservabilityPlugin plugin = name -> new ObservationHandle() {
            public ObservationHandle attribute(String k, Object v) { return this; }
            public ObservationHandle event(String n) { return this; }
            public ObservationHandle error(Throwable t) { return this; }
            public ObservationHandle child(String c) { return this; }
            public Map<String, String> propagationHeaders() { return Map.of("traceparent", "00-sadiq"); }
            public void close() { }
        };
        AtomicReference<HttpRequest> captured = new AtomicReference<>();
        HttpTransport transport = req -> { captured.set(req); return httpResponse(200, "{}", Map.of()); };
        SadiqClientImpl client = new SadiqClientImpl(
                BASE, cannedCodec(response()), () -> "t", List.of(), transport,
                plugin, RetryPolicy.disabled(), Map.of(), null);
        client.validate(request());
        assertEquals(Optional.of("00-sadiq"), captured.get().headers().firstValue("traceparent"));
    }

    @Test
    void validateReportsErrorOnObservationWhenInterceptorThrows() {
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
        SadiqClientImpl client = new SadiqClientImpl(
                BASE, cannedCodec(response()), () -> "t", List.of(blowingUp), transport,
                plugin, RetryPolicy.disabled(), Map.of(), null);
        assertThrows(RuntimeException.class, () -> client.validate(request()));
        assertEquals(1, errored.get());
    }

    @Test
    void bothMethodsRejectNullRequest() {
        HttpTransport transport = req -> httpResponse(200, "{}", Map.of());
        SadiqClientImpl client = build(transport, cannedCodec(response()), List.of());
        assertThrows(NullPointerException.class, () -> client.validate(null));
        assertThrows(NullPointerException.class, () -> client.validateAsync(null));
        assertThrows(NullPointerException.class, () -> client.deepResearch(null));
        assertThrows(NullPointerException.class, () -> client.deepResearchAsync(null));
        assertThrows(NullPointerException.class, () -> client.deepResearchStream(null));
    }

    // --- deep research (ADR-031)

    private static final String RUN = "data: {\"progress\":1}\n\n"
            + "data: {\"choices\":[{}]}\n\n"
            + "data: {\"report\":1}\n\n"
            + "data: {\"metadata\":1}\n\n"
            + "data: [DONE]\n\n";

    @Test
    void deepResearchStreamPostsToTheResearchEndpointAsAnSseRequestWithTheStreamFlag() throws Exception {
        AtomicReference<HttpRequest> captured = new AtomicReference<>();
        HttpTransport transport = req -> { captured.set(req); return httpResponse(200, RUN, Map.of()); };
        build(transport, researchCodec(), List.of()).deepResearchStream(researchRequest());

        HttpRequest sent = captured.get();
        assertEquals("POST", sent.method());
        assertEquals("/v1/sadiq/deep-research", sent.uri().getPath());
        assertEquals(Optional.of("application/json"), sent.headers().firstValue("Content-Type"));
        assertEquals(Optional.of("text/event-stream"), sent.headers().firstValue("Accept"));
        assertEquals(Optional.of("Bearer stub-token"), sent.headers().firstValue("Authorization"));
        assertEquals("{\"stream\":true,\"marker\":true}", bodyOf(sent),
                "the stream flag is spliced in front of the encoded request (ADR-023)");
    }

    @Test
    void deepResearchStreamEmitsEveryEventKindInWireOrder() throws Exception {
        HttpTransport transport = req -> httpResponse(200, RUN, Map.of());
        CollectingSubscriber<DeepResearchEvent> sub = CollectingSubscriber.unbounded();
        build(transport, researchCodec(), List.of()).deepResearchStream(researchRequest()).subscribe(sub);

        List<DeepResearchEvent> events = sub.awaitCompletion(Duration.ofSeconds(5));
        assertEquals(List.of(ProgressChunk.class, TokenChunk.class, ReportChunk.class, DoneChunk.class),
                events.stream().map(Object::getClass).toList());
        assertNull(events.getFirst().model(), "the first progress event arrives without a model");
        assertEquals("Title", ((ReportChunk) events.get(2)).report().title());
    }

    @Test
    void deepResearchReturnsTheReportFromTheStream() {
        HttpTransport transport = req -> httpResponse(200, RUN, Map.of());
        DeepResearchReport report = build(transport, researchCodec(), List.of()).deepResearch(researchRequest());
        assertEquals("Title", report.title());
    }

    @Test
    void deepResearchFailsWhenTheStreamEndsWithoutAReport() {
        HttpTransport transport = req -> httpResponse(200,
                "data: {\"progress\":1}\n\ndata: {\"choices\":[{}]}\n\ndata: [DONE]\n\n", Map.of());
        SadiqClientImpl client = build(transport, researchCodec(), List.of());
        FanarTransportException ex = assertThrows(FanarTransportException.class,
                () -> client.deepResearch(researchRequest()));
        assertTrue(ex.getMessage().contains("ended without a report"), ex.getMessage());
    }

    @Test
    void deepResearchFailsOnAnErrorChunkWithItsDetail() {
        HttpTransport transport = req -> httpResponse(200,
                "data: {\"progress\":1}\n\ndata: {\"error\":1}\n\ndata: [DONE]\n\n", Map.of());
        SadiqClientImpl client = build(transport, researchCodec(), List.of());
        FanarTransportException ex = assertThrows(FanarTransportException.class,
                () -> client.deepResearch(researchRequest()));
        assertTrue(ex.getMessage().endsWith("reported an error: boom"), ex.getMessage());
    }

    @Test
    void deepResearchFailsOnAnErrorChunkWithoutDetail() {
        HttpTransport transport = req -> httpResponse(200,
                "data: {\"error\":1,\"nodetail\":1}\n\ndata: [DONE]\n\n", Map.of());
        SadiqClientImpl client = build(transport, researchCodec(), List.of());
        FanarTransportException ex = assertThrows(FanarTransportException.class,
                () -> client.deepResearch(researchRequest()));
        assertTrue(ex.getMessage().endsWith("reported an error: (no detail)"), ex.getMessage());
    }

    @Test
    void deepResearchRethrowsARuntimeFailureFromTheStreamAsIs() {
        // A frame the codec cannot decode fails the stream with a FanarTransportException; the
        // blocking variant rethrows that very exception on the calling thread.
        FanarJsonCodec failingDecode = new FanarJsonCodec() {
            public <T> T decode(InputStream s, Class<T> t) throws IOException { throw new IOException("decode"); }
            public void encode(OutputStream s, Object v) throws IOException { s.write("{}".getBytes(StandardCharsets.UTF_8)); }
        };
        HttpTransport transport = req -> httpResponse(200, RUN, Map.of());
        SadiqClientImpl client = build(transport, failingDecode, List.of());
        FanarTransportException ex = assertThrows(FanarTransportException.class,
                () -> client.deepResearch(researchRequest()));
        assertTrue(ex.getMessage().startsWith("Failed to parse SSE payload"), ex.getMessage());
    }

    @Test
    void deepResearchRethrowsAnErrorFromTheStream() {
        FanarJsonCodec exploding = new FanarJsonCodec() {
            public <T> T decode(InputStream s, Class<T> t) { throw new AssertionError("codec exploded"); }
            public void encode(OutputStream s, Object v) throws IOException { s.write("{}".getBytes(StandardCharsets.UTF_8)); }
        };
        HttpTransport transport = req -> httpResponse(200, RUN, Map.of());
        SadiqClientImpl client = build(transport, exploding, List.of());
        assertThrows(AssertionError.class, () -> client.deepResearch(researchRequest()));
    }

    @Test
    void deepResearchWrapsACheckedFailureFromTheStream() {
        // The publisher hands a mid-body IOException to the subscriber raw; the blocking variant
        // wraps it, since the caller can only see unchecked exceptions (ADR-006).
        InputStream broken = new InputStream() {
            public int read() throws IOException { throw new IOException("connection reset"); }
        };
        HttpTransport transport = req -> httpResponse(200, broken, Map.of());
        SadiqClientImpl client = build(transport, researchCodec(), List.of());
        FanarTransportException ex = assertThrows(FanarTransportException.class,
                () -> client.deepResearch(researchRequest()));
        assertEquals("Deep research stream failed: connection reset", ex.getMessage());
        assertInstanceOf(IOException.class, ex.getCause());
    }

    @Test
    void interruptingDeepResearchCancelsTheRunAndClosesTheBody() throws Exception {
        HeldOpenBody body = new HeldOpenBody();
        HttpTransport transport = req -> httpResponse(200, body, Map.of());
        SadiqClientImpl client = build(transport, researchCodec(), List.of());
        AtomicReference<Throwable> caught = new AtomicReference<>();
        Thread worker = Thread.ofVirtual().unstarted(() -> {
            try {
                client.deepResearch(researchRequest());
            } catch (Throwable t) {
                caught.set(t);
                interrupted.set(Thread.currentThread().isInterrupted());
            }
        });
        worker.start();
        assertTrue(body.readEntered.await(5, TimeUnit.SECONDS), "the run must be in flight before the interrupt");

        worker.interrupt();
        assertTrue(worker.join(Duration.ofSeconds(5)), "the interrupted call must return");

        assertInstanceOf(FanarTransportException.class, caught.get());
        assertTrue(caught.get().getMessage().contains("interrupted"), caught.get().getMessage());
        assertTrue(interrupted.get(), "the interrupt flag is preserved");
        assertTrue(body.closed.await(5, TimeUnit.SECONDS), "cancelling the subscription closes the body");
    }

    private final java.util.concurrent.atomic.AtomicBoolean interrupted = new java.util.concurrent.atomic.AtomicBoolean();

    @Test
    void deepResearchAsyncCompletesWithTheReport() throws Exception {
        HttpTransport transport = req -> httpResponse(200, RUN, Map.of());
        CompletableFuture<DeepResearchReport> f = build(transport, researchCodec(), List.of())
                .deepResearchAsync(researchRequest());
        assertEquals("Title", f.get(5, TimeUnit.SECONDS).title());
    }

    @Test
    void deepResearchAsyncCompletesExceptionallyOnFailure() {
        HttpTransport transport = req -> httpResponse(401, "{\"error\":{}}", Map.of());
        CompletableFuture<DeepResearchReport> f = build(transport, researchCodec(), List.of())
                .deepResearchAsync(researchRequest());
        ExecutionException ex = assertThrows(ExecutionException.class, f::get);
        assertInstanceOf(FanarAuthenticationException.class, ex.getCause());
    }

    @Test
    void cancellingDeepResearchAsyncClosesTheBody() throws Exception {
        HeldOpenBody body = new HeldOpenBody();
        HttpTransport transport = req -> httpResponse(200, body, Map.of());
        CompletableFuture<DeepResearchReport> f = build(transport, researchCodec(), List.of())
                .deepResearchAsync(researchRequest());
        assertTrue(body.readEntered.await(5, TimeUnit.SECONDS), "the run must be in flight before the cancel");

        assertTrue(f.cancel(true));
        assertTrue(body.closed.await(5, TimeUnit.SECONDS), "cancelling the future closes the body");
        assertTrue(f.isCancelled());
    }

    @Test
    void deepResearchIsNeverRetriedWhateverTheClientPolicy() {
        AtomicInteger calls = new AtomicInteger();
        HttpTransport transport = req -> { calls.incrementAndGet(); return httpResponse(503, "{\"error\":{}}", Map.of()); };
        SadiqClientImpl client = new SadiqClientImpl(
                BASE, researchCodec(), () -> "t", List.of(), transport,
                ObservabilityPlugin.noop(), RetryPolicy.defaults(), Map.of(), null);

        assertThrows(FanarOverloadedException.class, () -> client.deepResearchStream(researchRequest()));
        assertEquals(1, calls.get(), "a 503 is retryable under the default policy, but not here");
        assertThrows(FanarOverloadedException.class, () -> client.deepResearch(researchRequest()));
        assertEquals(2, calls.get());
    }

    @Test
    void deepResearchObservationsAreNamedPerVariantAndClosedOnTheTerminalSignal() throws Exception {
        List<String> opened = new java.util.concurrent.CopyOnWriteArrayList<>();
        CountDownLatch closed = new CountDownLatch(2);
        ObservabilityPlugin plugin = name -> {
            opened.add(name);
            return new ObservationHandle() {
                public ObservationHandle attribute(String k, Object v) { return this; }
                public ObservationHandle event(String n) { return this; }
                public ObservationHandle error(Throwable t) { return this; }
                public ObservationHandle child(String c) { return this; }
                public Map<String, String> propagationHeaders() { return Map.of(); }
                public void close() { closed.countDown(); }
            };
        };
        HttpTransport transport = req -> httpResponse(200, RUN, Map.of());
        SadiqClientImpl client = new SadiqClientImpl(
                BASE, researchCodec(), () -> "t", List.of(), transport,
                plugin, RetryPolicy.disabled(), Map.of(), null);

        client.deepResearch(researchRequest());
        CollectingSubscriber<DeepResearchEvent> sub = CollectingSubscriber.unbounded();
        client.deepResearchStream(researchRequest()).subscribe(sub);
        sub.awaitCompletion(Duration.ofSeconds(5));

        assertEquals(List.of("fanar.sadiq.deep_research", "fanar.sadiq.deep_research.stream"), opened);
        assertTrue(closed.await(5, TimeUnit.SECONDS), "the publisher closes each observation on the terminal signal");
    }

    @Test
    void deepResearchHandshakeFailureReportsAndClosesTheObservation() {
        AtomicInteger errored = new AtomicInteger();
        AtomicInteger closed = new AtomicInteger();
        ObservabilityPlugin plugin = name -> new ObservationHandle() {
            public ObservationHandle attribute(String k, Object v) { return this; }
            public ObservationHandle event(String n) { return this; }
            public ObservationHandle error(Throwable t) { errored.incrementAndGet(); return this; }
            public ObservationHandle child(String c) { return this; }
            public Map<String, String> propagationHeaders() { return Map.of(); }
            public void close() { closed.incrementAndGet(); }
        };
        HttpTransport transport = req -> httpResponse(401, "{\"error\":{}}", Map.of());
        SadiqClientImpl client = new SadiqClientImpl(
                BASE, researchCodec(), () -> "t", List.of(), transport,
                plugin, RetryPolicy.disabled(), Map.of(), null);

        assertThrows(FanarAuthenticationException.class, () -> client.deepResearchStream(researchRequest()));
        assertEquals(1, errored.get());
        assertEquals(1, closed.get(), "no publisher exists to own the handle, so the facade closes it");
    }

    @Test
    void deepResearchWrapsCodecEncodeFailureAsTransportException() {
        FanarJsonCodec failingEncode = new FanarJsonCodec() {
            public <T> T decode(InputStream s, Class<T> t) { return null; }
            public void encode(OutputStream s, Object v) throws IOException { throw new IOException("encode"); }
        };
        HttpTransport transport = req -> { throw new AssertionError("should not reach transport"); };
        SadiqClientImpl client = build(transport, failingEncode, List.of());
        FanarTransportException ex = assertThrows(FanarTransportException.class,
                () -> client.deepResearchStream(researchRequest()));
        assertEquals("Failed to encode DeepResearchRequest", ex.getMessage());
    }

    @Test
    void deepResearchStreamIsSingleSubscriber() throws Exception {
        HttpTransport transport = req -> httpResponse(200, RUN, Map.of());
        Flow.Publisher<DeepResearchEvent> publisher = build(transport, researchCodec(), List.of())
                .deepResearchStream(researchRequest());
        CollectingSubscriber<DeepResearchEvent> first = CollectingSubscriber.unbounded();
        CollectingSubscriber<DeepResearchEvent> second = CollectingSubscriber.unbounded();
        publisher.subscribe(first);
        publisher.subscribe(second);
        assertEquals(4, first.awaitCompletion(Duration.ofSeconds(5)).size());
        assertInstanceOf(IllegalStateException.class, second.awaitError(Duration.ofSeconds(5)));
        assertFalse(first.error() != null, "the first subscriber is unaffected");
    }

    @Test
    void constructorRejectsNulls() {
        FanarJsonCodec codec = cannedCodec(response());
        HttpTransport transport = req -> httpResponse(200, "{}", Map.of());
        ObservabilityPlugin obs = ObservabilityPlugin.noop();
        RetryPolicy rp = RetryPolicy.disabled();
        assertThrows(NullPointerException.class, () ->
                new SadiqClientImpl(null, codec, () -> "t", List.of(), transport, obs, rp, Map.of(), null));
        assertThrows(NullPointerException.class, () ->
                new SadiqClientImpl(BASE, null, () -> "t", List.of(), transport, obs, rp, Map.of(), null));
        assertThrows(NullPointerException.class, () ->
                new SadiqClientImpl(BASE, codec, null, List.of(), transport, obs, rp, Map.of(), null));
        assertThrows(NullPointerException.class, () ->
                new SadiqClientImpl(BASE, codec, () -> "t", null, transport, obs, rp, Map.of(), null));
        assertThrows(NullPointerException.class, () ->
                new SadiqClientImpl(BASE, codec, () -> "t", List.of(), null, obs, rp, Map.of(), null));
        assertThrows(NullPointerException.class, () ->
                new SadiqClientImpl(BASE, codec, () -> "t", List.of(), transport, null, rp, Map.of(), null));
        assertThrows(NullPointerException.class, () ->
                new SadiqClientImpl(BASE, codec, () -> "t", List.of(), transport, obs, null, Map.of(), null));
        assertThrows(NullPointerException.class, () ->
                new SadiqClientImpl(BASE, codec, () -> "t", List.of(), transport, obs, rp, null, null));
    }

    // --- helpers

    private static SadiqClientImpl build(HttpTransport transport, FanarJsonCodec codec, List<Interceptor> interceptors) {
        return build(transport, codec, interceptors, "stub-token");
    }

    private static SadiqClientImpl build(HttpTransport transport, FanarJsonCodec codec,
                                               List<Interceptor> interceptors, String token) {
        return new SadiqClientImpl(
                BASE, codec, () -> token, interceptors,
                transport, ObservabilityPlugin.noop(), RetryPolicy.disabled(), Map.of(), null);
    }

    private static SadiqValidationRequest request() {
        return SadiqValidationRequest.of(ChatModel.FANAR_SADIQ_2, "a quoted verse");
    }

    private static DeepResearchRequest researchRequest() {
        return DeepResearchRequest.of(ChatModel.FANAR_SADIQ_2, "seeking knowledge");
    }

    private static final DeepResearchReport REPORT =
            new DeepResearchReport(null, null, "Title", null, null, null, null, null, null);

    /**
     * Two-pass codec for the scripted deep-research frames above: the first pass answers the
     * decoder's shape question from the discriminator key each frame carries, the second returns
     * the canned event for the target record. Encodes every request as a marker object.
     */
    private static FanarJsonCodec researchCodec() {
        return new FanarJsonCodec() {
            @SuppressWarnings("unchecked")
            public <T> T decode(InputStream s, Class<T> t) throws IOException {
                String json = new String(s.readAllBytes(), StandardCharsets.UTF_8);
                if (t == Map.class) {
                    return (T) shapeOf(json);
                }
                if (t == ProgressChunk.class) {
                    return t.cast(new ProgressChunk("c_1", 1L, null, new ProgressMessage("planning", "تخطيط")));
                }
                if (t == TokenChunk.class) {
                    return t.cast(new TokenChunk("c_1", 2L, "Fanar-Sadiq-2", List.of(new ChoiceToken(0, null, "draft "))));
                }
                if (t == ReportChunk.class) {
                    return t.cast(new ReportChunk("c_1", 3L, "Fanar-Sadiq-2", REPORT));
                }
                if (t == DoneChunk.class) {
                    return t.cast(new DoneChunk("c_1", 4L, "Fanar-Sadiq-2", List.of(), null, Map.of("depth", "quick")));
                }
                if (t == ErrorChunk.class) {
                    List<ChoiceError> choices = json.contains("\"nodetail\"")
                            ? List.of() : List.of(new ChoiceError(0, null, "boom"));
                    return t.cast(new ErrorChunk("c_1", 5L, "Fanar-Sadiq-2", choices));
                }
                throw new IOException("unexpected target " + t);
            }
            public void encode(OutputStream s, Object v) throws IOException {
                s.write("{\"marker\":true}".getBytes(StandardCharsets.UTF_8));
            }
        };
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
        if (json.contains("\"error\"")) {
            return Map.of("choices", List.of(Map.of("finish_reason", "error")));
        }
        return Map.of("choices", List.of(Map.of()));
    }

    /** A response body that blocks in read() until closed, then reports end of stream — a socket, not a pipe. */
    private static final class HeldOpenBody extends InputStream {
        final CountDownLatch readEntered = new CountDownLatch(1);
        final CountDownLatch closed = new CountDownLatch(1);
        private final Object lock = new Object();
        private boolean isClosed;

        @Override
        public int read() throws IOException {
            readEntered.countDown();
            synchronized (lock) {
                while (!isClosed) {
                    try {
                        lock.wait();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw new IOException("read interrupted", e);
                    }
                }
            }
            return -1;
        }

        @Override
        public void close() {
            synchronized (lock) {
                isClosed = true;
                lock.notifyAll();
            }
            closed.countDown();
        }
    }

    private static SadiqValidationResponse response() {
        return new SadiqValidationResponse("req_1", "<quran_start>x<quran_end>");
    }

    private static FanarJsonCodec cannedCodec(SadiqValidationResponse canned) {
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
        return httpResponse(status, new ByteArrayInputStream(body.getBytes(StandardCharsets.UTF_8)), headers);
    }

    private static HttpResponse<InputStream> httpResponse(int status, InputStream body, Map<String, List<String>> headers) {
        return new HttpResponse<>() {
            public int statusCode() { return status; }
            public HttpRequest request() { return null; }
            public Optional<HttpResponse<InputStream>> previousResponse() { return Optional.empty(); }
            public HttpHeaders headers() { return HttpHeaders.of(headers, (a, b) -> true); }
            public InputStream body() { return body; }
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
        assertTrue(done.await(1, TimeUnit.SECONDS), "request body publisher must complete");
        return new String(buf.get(), StandardCharsets.UTF_8);
    }
}

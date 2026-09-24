package qa.fanar.core.internal.sse;

import org.junit.jupiter.api.Test;
import qa.fanar.core.chat.ChoiceToken;
import qa.fanar.core.chat.StreamEvent;
import qa.fanar.core.chat.TokenChunk;
import qa.fanar.core.sadiq.DeepResearchEvent;
import qa.fanar.core.sadiq.DeepResearchReport;
import qa.fanar.core.sadiq.ReportChunk;
import qa.fanar.core.spi.FanarJsonCodec;
import qa.fanar.core.spi.FanarObservationAttributes;
import qa.fanar.core.spi.ObservationHandle;
import qa.fanar.core.internal.observability.NoopObservationHandle;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class SseStreamPublisherTest {

    @Test
    void happyPathEmitsEventsAndCompletes() throws Exception {
        String body = """
                data: {"kind":"token","content":"a"}

                data: {"kind":"token","content":"b"}

                data: {"kind":"token","content":"c"}

                """;

        CollectingSubscriber sub = new CollectingSubscriber(Long.MAX_VALUE);
        SseStreamPublisher.forChat(bytes(body), scriptedCodec(
                new TokenChunk("c", 0L, "m", List.of(new ChoiceToken(0, null, "a"))),
                new TokenChunk("c", 0L, "m", List.of(new ChoiceToken(0, null, "b"))),
                new TokenChunk("c", 0L, "m", List.of(new ChoiceToken(0, null, "c")))
        ), NoopObservationHandle.INSTANCE, System.nanoTime()).subscribe(sub);

        sub.completed.get(5, TimeUnit.SECONDS);
        assertEquals(3, sub.events.size());
        sub.events.forEach(e -> assertInstanceOf(TokenChunk.class, e));
        assertTrue(sub.completedFlag.get());
    }

    @Test
    void boundedDemandIsRespected() throws Exception {
        PipedOutputStream out = new PipedOutputStream();
        PipedInputStream in = new PipedInputStream(out, 8192);

        CollectingSubscriber sub = new CollectingSubscriber(1); // request exactly one
        SseStreamPublisher.forChat(in, scriptedCodec(
                new TokenChunk("c", 0L, "m", List.of(new ChoiceToken(0, null, "first"))),
                new TokenChunk("c", 0L, "m", List.of(new ChoiceToken(0, null, "second")))
        ), NoopObservationHandle.INSTANCE, System.nanoTime()).subscribe(sub);

        out.write("data: {\"a\":1}\n\ndata: {\"a\":2}\n\n".getBytes(StandardCharsets.UTF_8));
        out.flush();

        // After the first emission the producer decodes the second frame and, with demand
        // exhausted, parks in awaitDemand. Wait for the park itself before requesting: a request
        // that lands first is honoured too, but then the producer never waits, and this is the
        // test that proves wait() wakes on request.
        sub.nextReceived.get(5, TimeUnit.SECONDS);
        assertEquals(1, sub.events.size());
        awaitParkedInAwaitDemand(sub.producer);

        // Request one more — producer wakes up and delivers the second event.
        sub.subscription.request(1);
        sub.secondReceived.get(5, TimeUnit.SECONDS);
        assertEquals(2, sub.events.size());

        // Cleanly shut down so the virtual thread exits.
        out.close();
        sub.completed.get(5, TimeUnit.SECONDS);
    }

    @Test
    void secondSubscriberIsRejected() throws Exception {
        SseStreamPublisher<StreamEvent> publisher = SseStreamPublisher.forChat(bytes(""), scriptedCodec(), NoopObservationHandle.INSTANCE, System.nanoTime());

        CollectingSubscriber first = new CollectingSubscriber(Long.MAX_VALUE);
        publisher.subscribe(first);
        first.completed.get(5, TimeUnit.SECONDS);

        CollectingSubscriber second = new CollectingSubscriber(Long.MAX_VALUE);
        publisher.subscribe(second);

        Throwable err = second.errored.get(5, TimeUnit.SECONDS);
        assertInstanceOf(IllegalStateException.class, err);
        assertTrue(err.getMessage().contains("single subscriber"));

        // NoopSubscription is a no-op — requesting / cancelling must be safe.
        second.subscription.request(10);
        second.subscription.cancel();
    }

    @Test
    void cancelStopsDeliveryAndClosesStream() throws Exception {
        AtomicBoolean closed = new AtomicBoolean();
        PipedOutputStream out = new PipedOutputStream();
        PipedInputStream piped = new PipedInputStream(out, 8192);
        InputStream body = new InputStream() {
            public int read() throws IOException { return piped.read(); }
            public int read(byte[] b, int off, int len) throws IOException { return piped.read(b, off, len); }
            public void close() throws IOException { closed.set(true); piped.close(); }
        };

        // Cancel synchronously from inside onNext — this runs on the producer thread, so
        // `cancelled=true` is published before the producer re-evaluates the while-loop header.
        // Without this, cancelling from the test thread races the producer: when it wins, the
        // producer enters a blocking readLine() that wakes up via close() and either returns
        // null (covering the cancelled-short-circuit branches at L115/L131 of the publisher) or
        // throws IOException (jumping to the catch path and missing those branches). The race
        // flips between Java 21 and 25 on CI; cancelling from onNext makes the path deterministic.
        CollectingSubscriber sub = new CollectingSubscriber(Long.MAX_VALUE) {
            @Override
            public void onNext(StreamEvent item) {
                super.onNext(item);
                subscription.cancel();
            }
        };
        RecordingObservation obs = new RecordingObservation();
        SseStreamPublisher.forChat(body, scriptedCodec(
                new TokenChunk("c", 0L, "m", List.of(new ChoiceToken(0, null, "x")))
        ), obs, System.nanoTime()).subscribe(sub);

        out.write("data: {\"a\":1}\n\n".getBytes(StandardCharsets.UTF_8));
        out.flush();
        sub.nextReceived.get(5, TimeUnit.SECONDS);
        // The producer closes the observation in its finally block, so this latch firing proves
        // run() has exited — anything it was going to signal, it has signalled. Deterministic
        // where a fixed sleep only guesses.
        assertTrue(obs.closed.await(5, TimeUnit.SECONDS), "the producer must finish");

        assertEquals(1, sub.events.size());
        assertFalse(sub.completedFlag.get(), "onComplete must not fire after cancel");
        assertTrue(closed.get(), "underlying body must be closed on cancel");
    }

    @Test
    void ioErrorDuringReadSurfacesAsOnError() throws Exception {
        InputStream broken = new InputStream() {
            public int read() throws IOException { throw new IOException("boom"); }
        };
        CollectingSubscriber sub = new CollectingSubscriber(Long.MAX_VALUE);
        SseStreamPublisher.forChat(broken, scriptedCodec(), NoopObservationHandle.INSTANCE, System.nanoTime()).subscribe(sub);

        Throwable err = sub.errored.get(5, TimeUnit.SECONDS);
        assertInstanceOf(IOException.class, err);
    }

    @Test
    void requestZeroTerminatesWithIllegalArgument() throws Exception {
        CollectingSubscriber sub = new CollectingSubscriber(0); // no initial demand
        SseStreamPublisher.forChat(bytes(""), scriptedCodec(), NoopObservationHandle.INSTANCE, System.nanoTime()).subscribe(sub);

        sub.subscription.request(0);
        Throwable err = sub.errored.get(5, TimeUnit.SECONDS);
        assertInstanceOf(IllegalArgumentException.class, err);
    }

    @Test
    void requestOverflowSaturatesToMaxValue() throws Exception {
        String body = "data: {}\n\ndata: {}\n\ndata: {}\n\n";

        CollectingSubscriber sub = new CollectingSubscriber(0);
        SseStreamPublisher.forChat(bytes(body), scriptedCodec(
                new TokenChunk("c", 0L, "m", List.of()),
                new TokenChunk("c", 0L, "m", List.of()),
                new TokenChunk("c", 0L, "m", List.of())
        ), NoopObservationHandle.INSTANCE, System.nanoTime()).subscribe(sub);

        // Long.MAX_VALUE twice — must not roll negative.
        sub.subscription.request(Long.MAX_VALUE);
        sub.subscription.request(Long.MAX_VALUE);

        sub.completed.get(5, TimeUnit.SECONDS);
        assertEquals(3, sub.events.size());
    }

    @Test
    void nullArgsAreRejected() {
        FanarJsonCodec codec = scriptedCodec();
        assertThrows(NullPointerException.class, () -> SseStreamPublisher.forChat(null, codec, NoopObservationHandle.INSTANCE, System.nanoTime()));
        assertThrows(NullPointerException.class, () -> SseStreamPublisher.forChat(bytes(""), null, NoopObservationHandle.INSTANCE, System.nanoTime()));
        assertThrows(NullPointerException.class, () -> SseStreamPublisher.forChat(bytes(""), codec, null, System.nanoTime()));

        SseStreamPublisher<StreamEvent> publisher = SseStreamPublisher.forChat(bytes(""), codec, NoopObservationHandle.INSTANCE, System.nanoTime());
        assertThrows(NullPointerException.class, () -> publisher.subscribe(null));
    }

    @Test
    void cancelDuringAwaitDemandExitsWithoutDelivery() throws Exception {
        PipedOutputStream out = new PipedOutputStream();
        PipedInputStream in = new PipedInputStream(out, 8192);

        CollectingSubscriber sub = new CollectingSubscriber(1); // only allow one event
        RecordingObservation obs = new RecordingObservation();
        SseStreamPublisher.forChat(in, scriptedCodec(
                new TokenChunk("c", 0L, "m", List.of()),
                new TokenChunk("c", 0L, "m", List.of())
        ), obs, System.nanoTime()).subscribe(sub);

        // Emit two frames. The first consumes the single unit of demand; the producer then
        // parks inside awaitDemand waiting for the second delivery request.
        out.write("data: {}\n\ndata: {}\n\n".getBytes(StandardCharsets.UTF_8));
        out.flush();

        // The first delivery proves the producer consumed the single unit of demand; the park
        // itself is waited for, not inferred, so the cancel wakes a parked producer rather than
        // beating it to the loop check (both are correct, only the first exercises the wait). The
        // observation close then proves it exited.
        sub.nextReceived.get(5, TimeUnit.SECONDS);
        awaitParkedInAwaitDemand(sub.producer);
        sub.subscription.cancel();
        assertTrue(obs.closed.await(5, TimeUnit.SECONDS), "cancel must wake and finish the producer");

        assertEquals(1, sub.events.size(), "second event must not be delivered after cancel");
        assertFalse(sub.completedFlag.get());
    }

    @Test
    void interruptDuringAwaitDemandSurfacesAsError() throws Exception {
        String body = "data: {}\n\ndata: {}\n\n";
        CollectingSubscriber sub = new CollectingSubscriber(1) {
            @Override
            public void onNext(StreamEvent item) {
                super.onNext(item);
                // Self-interrupt the producer (we are executing on it) so the next awaitDemand
                // wait() throws immediately instead of blocking forever.
                Thread.currentThread().interrupt();
            }
        };
        SseStreamPublisher.forChat(bytes(body), scriptedCodec(
                new TokenChunk("c", 0L, "m", List.of()),
                new TokenChunk("c", 0L, "m", List.of())
        ), NoopObservationHandle.INSTANCE, System.nanoTime()).subscribe(sub);

        Throwable err = sub.errored.get(5, TimeUnit.SECONDS);
        assertInstanceOf(qa.fanar.core.FanarTransportException.class, err);
        assertInstanceOf(InterruptedException.class, err.getCause());
        assertTrue(err.getMessage().contains("interrupted"));
        assertEquals(1, sub.events.size());
    }

    @Test
    void ioErrorAfterCancelIsSwallowed() throws Exception {
        // Exercises the catch (Throwable) branch where cancel has already been called:
        // the reader throws IOException because we just closed the body; the producer must
        // not surface that to the subscriber (they asked to stop).
        CountDownLatch inRead = new CountDownLatch(1);
        CountDownLatch closed = new CountDownLatch(1);
        InputStream body = new InputStream() {
            @Override
            public int read() throws IOException {
                inRead.countDown();
                try {
                    // Park until close() releases us — no polling, no sleep.
                    closed.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                throw new IOException("read-after-close (expected — must be swallowed)");
            }
            @Override
            public void close() { closed.countDown(); }
        };

        CollectingSubscriber sub = new CollectingSubscriber(Long.MAX_VALUE);
        RecordingObservation obs = new RecordingObservation();
        SseStreamPublisher.forChat(body, scriptedCodec(), obs, System.nanoTime()).subscribe(sub);
        assertTrue(inRead.await(5, TimeUnit.SECONDS));

        sub.subscription.cancel();
        assertTrue(obs.closed.await(5, TimeUnit.SECONDS), "the producer must finish after cancel");

        assertFalse(sub.errored.isDone(), "post-cancel IOException must not surface to the subscriber");
        assertFalse(sub.completedFlag.get(), "no terminal signal after cancel");
    }

    @Test
    void cancelSwallowsCloseIoError() throws Exception {
        // Deliberately not a PipedInputStream: closing one does not wake a reader already parked
        // inside read(), so whether the producer ever finished came down to which thread won the
        // race with cancel(). A real response body releases a parked read on close() — modelled
        // here by the latch — which makes both interleavings terminate.
        CountDownLatch released = new CountDownLatch(1);
        AtomicBoolean closeCalled = new AtomicBoolean();
        InputStream body = new InputStream() {
            public int read() throws IOException {
                try {
                    released.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IOException(e);
                }
                return -1;
            }
            public void close() throws IOException {
                closeCalled.set(true);
                released.countDown();
                throw new IOException("close failure (expected — must be swallowed)");
            }
        };

        CollectingSubscriber sub = new CollectingSubscriber(Long.MAX_VALUE);
        RecordingObservation obs = new RecordingObservation();
        SseStreamPublisher.forChat(body, scriptedCodec(), obs, System.nanoTime()).subscribe(sub);

        // Must not propagate the close IOException out of cancel().
        sub.subscription.cancel();
        // Asserted before the latch: only cancel() can have set it this early. The producer's
        // own close() in its finally block would set it too, and prove nothing about cancel().
        assertTrue(closeCalled.get(), "cancel() must close the body");

        assertTrue(obs.closed.await(5, TimeUnit.SECONDS), "the producer must finish");
        assertFalse(sub.errored.isDone(), "close-time IOException must be swallowed silently");
    }

    @Test
    void nonEventFramesAreSkipped() throws Exception {
        // [DONE] sentinel + blank frames — the decoder returns null for them, producer loops on.
        String body = """
                data: [DONE]

                data: {"kind":"token"}

                """;
        CollectingSubscriber sub = new CollectingSubscriber(Long.MAX_VALUE);
        SseStreamPublisher.forChat(bytes(body), scriptedCodec(
                new TokenChunk("c", 0L, "m", List.of(new ChoiceToken(0, null, "x")))
        ), NoopObservationHandle.INSTANCE, System.nanoTime()).subscribe(sub);

        sub.completed.get(5, TimeUnit.SECONDS);
        assertEquals(1, sub.events.size());
    }

    // --- helpers

    @Test
    void observationSpansTheStreamAndRecordsChunkMetricsOnCompletion() throws Exception {
        String body = """
                data: {"kind":"token","content":"a"}

                data: {"kind":"token","content":"b"}

                """;

        RecordingObservation obs = new RecordingObservation();
        CollectingSubscriber sub = new CollectingSubscriber(Long.MAX_VALUE);
        SseStreamPublisher.forChat(bytes(body), scriptedCodec(
                new TokenChunk("c", 0L, "m", List.of(new ChoiceToken(0, null, "a"))),
                new TokenChunk("c", 0L, "m", List.of(new ChoiceToken(0, null, "b")))
        ), obs, System.nanoTime()).subscribe(sub);

        sub.completed.get(5, TimeUnit.SECONDS);
        assertTrue(obs.closed.await(5, TimeUnit.SECONDS), "the observation must close at the terminal signal");

        assertEquals(2L, obs.attributes.get(FanarObservationAttributes.FANAR_STREAM_CHUNKS));
        assertNotNull(obs.attributes.get(FanarObservationAttributes.FANAR_STREAM_FIRST_CHUNK_MS),
                "first-chunk latency is recorded when the first event arrives");
        assertEquals(1, obs.closes.get(), "closed exactly once");
        assertNull(obs.error.get(), "a completed stream is not an error");
    }

    @Test
    void observationRecordsTheErrorAndStillClosesOnFailure() throws Exception {
        InputStream broken = new InputStream() {
            public int read() throws IOException { throw new IOException("boom"); }
        };
        RecordingObservation obs = new RecordingObservation();
        CollectingSubscriber sub = new CollectingSubscriber(Long.MAX_VALUE);
        SseStreamPublisher.forChat(broken, scriptedCodec(), obs, System.nanoTime()).subscribe(sub);

        sub.errored.get(5, TimeUnit.SECONDS);
        assertTrue(obs.closed.await(5, TimeUnit.SECONDS), "a failed stream still closes its observation");

        assertInstanceOf(IOException.class, obs.error.get(), "the failure reaches the observation");
        assertEquals(0L, obs.attributes.get(FanarObservationAttributes.FANAR_STREAM_CHUNKS),
                "no chunk arrived before the failure");
    }

    @Test
    void observationClosesOnCancellationAndReportsChunksSeenSoFar() throws Exception {
        String body = "data: {}\n\ndata: {}\n\ndata: {}\n\n";

        RecordingObservation obs = new RecordingObservation();
        CollectingSubscriber sub = new CollectingSubscriber(1); // one chunk, then cancel
        SseStreamPublisher.forChat(bytes(body), scriptedCodec(
                new TokenChunk("c", 0L, "m", List.of()),
                new TokenChunk("c", 0L, "m", List.of()),
                new TokenChunk("c", 0L, "m", List.of())
        ), obs, System.nanoTime()).subscribe(sub);

        assertTrue(sub.firstLatch.await(5, TimeUnit.SECONDS), "first event must arrive");
        sub.subscription.cancel();

        assertTrue(obs.closed.await(5, TimeUnit.SECONDS), "a cancelled stream still closes its observation");
        assertEquals(1, obs.closes.get(), "closed exactly once");
    }

    /** Records what the publisher puts on the observation, and when it closes it. */
    @Test
    void forDeepResearchDeliversTheReportChunkAndCompletes() throws Exception {
        // The deep-research publisher differs from the chat one only in its classifier: a frame
        // with a top-level `report` becomes a ReportChunk instead of falling through to a token.
        ReportChunk report = new ReportChunk("c_1", 3L, "Fanar-Sadiq-2",
                new DeepResearchReport(null, null, "Title", null, null, null, null, null, null));
        FanarJsonCodec codec = new FanarJsonCodec() {
            @SuppressWarnings("unchecked")
            public <T> T decode(InputStream s, Class<T> t) throws IOException {
                s.readAllBytes();
                return t == Map.class ? (T) Map.of("report", Map.of()) : t.cast(report);
            }
            public void encode(OutputStream s, Object v) { throw new AssertionError("encode must not be called"); }
        };
        List<DeepResearchEvent> received = new java.util.concurrent.CopyOnWriteArrayList<>();
        CountDownLatch completed = new CountDownLatch(1);
        SseStreamPublisher.forDeepResearch(bytes("data: {\"report\":{}}\n\ndata: [DONE]\n\n"), codec,
                NoopObservationHandle.INSTANCE, System.nanoTime()).subscribe(new Flow.Subscriber<>() {
                    public void onSubscribe(Flow.Subscription s) { s.request(Long.MAX_VALUE); }
                    public void onNext(DeepResearchEvent item) { received.add(item); }
                    public void onError(Throwable t) { completed.countDown(); }
                    public void onComplete() { completed.countDown(); }
                });

        assertTrue(completed.await(5, TimeUnit.SECONDS), "the stream must complete");
        assertEquals(List.of(report), received);
        assertEquals("Title", ((ReportChunk) received.getFirst()).report().title());
    }

    @Test
    void forDeepResearchRejectsNulls() {
        FanarJsonCodec codec = scriptedCodec();
        assertThrows(NullPointerException.class, () -> SseStreamPublisher.forDeepResearch(null, codec, NoopObservationHandle.INSTANCE, 0L));
        assertThrows(NullPointerException.class, () -> SseStreamPublisher.forDeepResearch(bytes(""), null, NoopObservationHandle.INSTANCE, 0L));
        assertThrows(NullPointerException.class, () -> SseStreamPublisher.forDeepResearch(bytes(""), codec, null, 0L));
    }

    private static final class RecordingObservation implements ObservationHandle {
        final Map<String, Object> attributes = new ConcurrentHashMap<>();
        final AtomicInteger closes = new AtomicInteger();
        final AtomicReference<Throwable> error = new AtomicReference<>();
        final CountDownLatch closed = new CountDownLatch(1);

        @Override public ObservationHandle attribute(String key, Object value) {
            attributes.put(key, value);
            return this;
        }
        @Override public ObservationHandle event(String name) { return this; }
        @Override public ObservationHandle error(Throwable t) { error.set(t); return this; }
        @Override public ObservationHandle child(String operationName) { return this; }
        @Override public Map<String, String> propagationHeaders() { return Map.of(); }
        @Override public void close() { closes.incrementAndGet(); closed.countDown(); }
    }

    private static InputStream bytes(String body) {
        return new ByteArrayInputStream(body.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Blocks until {@code producer} is parked in {@code awaitDemand}'s {@code wait()} — state
     * {@code WAITING} with that frame on its stack. Only then is a request or cancel guaranteed to
     * wake a parked producer rather than beat it to the loop check; both interleavings are correct,
     * but only the first exercises the wait, and JaCoCo counts a {@code wait()} that never returns
     * normally as an unexecuted line. Yields until a deadline — no sleep.
     */
    private static void awaitParkedInAwaitDemand(Thread producer) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!parkedInAwaitDemand(producer)) {
            assertTrue(System.nanoTime() < deadline,
                    "producer never parked in awaitDemand; state " + producer.getState());
            Thread.yield();
        }
    }

    private static boolean parkedInAwaitDemand(Thread producer) {
        return producer.getState() == Thread.State.WAITING
                && Arrays.stream(producer.getStackTrace())
                        .anyMatch(frame -> "awaitDemand".equals(frame.getMethodName()));
    }

    /** Codec that returns the shape map on odd calls and a pre-canned event on even calls, in order. */
    private static FanarJsonCodec scriptedCodec(StreamEvent... events) {
        return new FanarJsonCodec() {
            int evIdx;
            boolean expectingShape = true;
            @SuppressWarnings("unchecked")
            @Override
            public <T> T decode(InputStream stream, Class<T> type) throws IOException {
                stream.readAllBytes();
                if (expectingShape) {
                    expectingShape = false;
                    return (T) Map.of(); // empty shape → classifier returns TokenChunk (our fallback)
                }
                expectingShape = true;
                if (evIdx >= events.length) {
                    throw new AssertionError("codec called more times than canned events available");
                }
                return type.cast(events[evIdx++]);
            }
            @Override
            public void encode(OutputStream stream, Object value) {
                throw new AssertionError("encode must not be called");
            }
        };
    }

    private static class CollectingSubscriber implements Flow.Subscriber<StreamEvent> {
        final List<StreamEvent> events = new CopyOnWriteArrayList<>();
        final CountDownLatch firstLatch = new CountDownLatch(1);
        final CountDownLatch secondLatch = new CountDownLatch(2);
        final CompletableFuture<Void> completed = new CompletableFuture<>();
        final CompletableFuture<Throwable> errored = new CompletableFuture<>();
        final CompletableFuture<Void> nextReceived = new CompletableFuture<>();
        final CompletableFuture<Void> secondReceived = new CompletableFuture<>();
        final AtomicBoolean completedFlag = new AtomicBoolean();
        final long initialDemand;

        volatile Flow.Subscription subscription;
        /** The producer thread, captured on the first delivery — onNext runs on it. */
        volatile Thread producer;

        CollectingSubscriber(long initialDemand) {
            this.initialDemand = initialDemand;
        }

        @Override
        public void onSubscribe(Flow.Subscription s) {
            this.subscription = s;
            if (initialDemand > 0) s.request(initialDemand);
        }
        @Override
        public void onNext(StreamEvent item) {
            producer = Thread.currentThread();
            events.add(item);
            firstLatch.countDown();
            secondLatch.countDown();
            if (!nextReceived.isDone()) nextReceived.complete(null);
            else if (!secondReceived.isDone()) secondReceived.complete(null);
        }
        @Override
        public void onError(Throwable throwable) { errored.complete(throwable); completed.complete(null); }
        @Override
        public void onComplete() { completedFlag.set(true); completed.complete(null); }
    }

}

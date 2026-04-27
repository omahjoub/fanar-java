package qa.fanar.core.internal.sse;

import org.junit.jupiter.api.Test;
import qa.fanar.core.chat.ChoiceToken;
import qa.fanar.core.chat.StreamEvent;
import qa.fanar.core.chat.TokenChunk;
import qa.fanar.core.spi.FanarJsonCodec;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

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
        new SseStreamPublisher(bytes(body), scriptedCodec(
                new TokenChunk("c", 0L, "m", List.of(new ChoiceToken(0, null, "a"))),
                new TokenChunk("c", 0L, "m", List.of(new ChoiceToken(0, null, "b"))),
                new TokenChunk("c", 0L, "m", List.of(new ChoiceToken(0, null, "c")))
        )).subscribe(sub);

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
        new SseStreamPublisher(in, scriptedCodec(
                new TokenChunk("c", 0L, "m", List.of(new ChoiceToken(0, null, "first"))),
                new TokenChunk("c", 0L, "m", List.of(new ChoiceToken(0, null, "second")))
        )).subscribe(sub);

        out.write("data: {\"a\":1}\n\ndata: {\"a\":2}\n\n".getBytes(StandardCharsets.UTF_8));
        out.flush();

        // After the first emission, the producer parks awaiting demand.
        sub.nextReceived.get(5, TimeUnit.SECONDS);
        assertEquals(1, sub.events.size());

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
        SseStreamPublisher publisher = new SseStreamPublisher(bytes(""), scriptedCodec());

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
        new SseStreamPublisher(body, scriptedCodec(
                new TokenChunk("c", 0L, "m", List.of(new ChoiceToken(0, null, "x")))
        )).subscribe(sub);

        out.write("data: {\"a\":1}\n\n".getBytes(StandardCharsets.UTF_8));
        out.flush();
        sub.nextReceived.get(5, TimeUnit.SECONDS);
        // Bounded wait: lets the producer reach the while header (and therefore L131) so a
        // regression that fires onComplete after cancel would be caught here.
        Thread.sleep(100);

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
        new SseStreamPublisher(broken, scriptedCodec()).subscribe(sub);

        Throwable err = sub.errored.get(5, TimeUnit.SECONDS);
        assertInstanceOf(IOException.class, err);
    }

    @Test
    void requestZeroTerminatesWithIllegalArgument() throws Exception {
        CollectingSubscriber sub = new CollectingSubscriber(0); // no initial demand
        new SseStreamPublisher(bytes(""), scriptedCodec()).subscribe(sub);

        sub.subscription.request(0);
        Throwable err = sub.errored.get(5, TimeUnit.SECONDS);
        assertInstanceOf(IllegalArgumentException.class, err);
    }

    @Test
    void requestOverflowSaturatesToMaxValue() throws Exception {
        String body = "data: {}\n\ndata: {}\n\ndata: {}\n\n";

        CollectingSubscriber sub = new CollectingSubscriber(0);
        new SseStreamPublisher(bytes(body), scriptedCodec(
                new TokenChunk("c", 0L, "m", List.of()),
                new TokenChunk("c", 0L, "m", List.of()),
                new TokenChunk("c", 0L, "m", List.of())
        )).subscribe(sub);

        // Long.MAX_VALUE twice — must not roll negative.
        sub.subscription.request(Long.MAX_VALUE);
        sub.subscription.request(Long.MAX_VALUE);

        sub.completed.get(5, TimeUnit.SECONDS);
        assertEquals(3, sub.events.size());
    }

    @Test
    void nullArgsAreRejected() {
        FanarJsonCodec codec = scriptedCodec();
        assertThrows(NullPointerException.class, () -> new SseStreamPublisher(null, codec));
        assertThrows(NullPointerException.class, () -> new SseStreamPublisher(bytes(""), null));

        SseStreamPublisher publisher = new SseStreamPublisher(bytes(""), codec);
        assertThrows(NullPointerException.class, () -> publisher.subscribe(null));
    }

    @Test
    void cancelDuringAwaitDemandExitsWithoutDelivery() throws Exception {
        PipedOutputStream out = new PipedOutputStream();
        PipedInputStream in = new PipedInputStream(out, 8192);

        CollectingSubscriber sub = new CollectingSubscriber(1); // only allow one event
        new SseStreamPublisher(in, scriptedCodec(
                new TokenChunk("c", 0L, "m", List.of()),
                new TokenChunk("c", 0L, "m", List.of())
        )).subscribe(sub);

        // Emit two frames. The first consumes the single unit of demand; the producer then
        // parks inside awaitDemand waiting for the second delivery request.
        out.write("data: {}\n\ndata: {}\n\n".getBytes(StandardCharsets.UTF_8));
        out.flush();

        sub.nextReceived.get(5, TimeUnit.SECONDS);
        Thread.sleep(100);

        sub.subscription.cancel();
        Thread.sleep(100);

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
        new SseStreamPublisher(bytes(body), scriptedCodec(
                new TokenChunk("c", 0L, "m", List.of()),
                new TokenChunk("c", 0L, "m", List.of())
        )).subscribe(sub);

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
        AtomicBoolean closed = new AtomicBoolean();
        InputStream body = new InputStream() {
            @Override
            public int read() throws IOException {
                inRead.countDown();
                while (!closed.get()) {
                    try { Thread.sleep(10); }
                    catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                }
                throw new IOException("read-after-close (expected — must be swallowed)");
            }
            @Override
            public void close() { closed.set(true); }
        };

        CollectingSubscriber sub = new CollectingSubscriber(Long.MAX_VALUE);
        new SseStreamPublisher(body, scriptedCodec()).subscribe(sub);
        assertTrue(inRead.await(5, TimeUnit.SECONDS));

        sub.subscription.cancel();
        Thread.sleep(100);

        assertFalse(sub.errored.isDone(), "post-cancel IOException must not surface to the subscriber");
        assertFalse(sub.completedFlag.get(), "no terminal signal after cancel");
    }

    @Test
    void cancelSwallowsCloseIoError() throws Exception {
        PipedOutputStream out = new PipedOutputStream();
        PipedInputStream piped = new PipedInputStream(out, 8192);
        AtomicBoolean closeCalled = new AtomicBoolean();
        InputStream body = new InputStream() {
            public int read() throws IOException { return piped.read(); }
            public int read(byte[] b, int off, int len) throws IOException { return piped.read(b, off, len); }
            public void close() throws IOException {
                closeCalled.set(true);
                piped.close();
                throw new IOException("close failure (expected — must be swallowed)");
            }
        };

        CollectingSubscriber sub = new CollectingSubscriber(Long.MAX_VALUE);
        new SseStreamPublisher(body, scriptedCodec()).subscribe(sub);
        Thread.sleep(50);

        // Must not propagate the close IOException out of cancel().
        sub.subscription.cancel();
        Thread.sleep(50);

        assertTrue(closeCalled.get());
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
        new SseStreamPublisher(bytes(body), scriptedCodec(
                new TokenChunk("c", 0L, "m", List.of(new ChoiceToken(0, null, "x")))
        )).subscribe(sub);

        sub.completed.get(5, TimeUnit.SECONDS);
        assertEquals(1, sub.events.size());
    }

    // --- helpers

    private static InputStream bytes(String body) {
        return new ByteArrayInputStream(body.getBytes(StandardCharsets.UTF_8));
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

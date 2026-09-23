package qa.fanar.core.internal.audio;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;

import qa.fanar.core.FanarTransportException;
import qa.fanar.core.internal.observability.NoopObservationHandle;
import qa.fanar.core.spi.FanarObservationAttributes;
import qa.fanar.core.spi.ObservationHandle;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AudioStreamPublisherTest {

    @Test
    void happyPathEmitsChunksAndCompletes() throws Exception {
        byte[] payload = new byte[20_000]; // > 2 × 8 KiB chunk size → at least 3 chunks
        for (int i = 0; i < payload.length; i++) {
            payload[i] = (byte) i;
        }

        CollectingSubscriber sub = new CollectingSubscriber(Long.MAX_VALUE);
        new AudioStreamPublisher(new ByteArrayInputStream(payload), NoopObservationHandle.INSTANCE, System.nanoTime()).subscribe(sub);

        sub.completed.get(5, TimeUnit.SECONDS);
        assertTrue(sub.chunks.size() >= 3, "expected multiple chunks, got " + sub.chunks.size());
        assertArrayEquals(payload, concat(sub.chunks),
                "chunks concatenated in emission order must reproduce the payload");
        assertTrue(sub.completedFlag.get());
    }

    @Test
    void boundedDemandIsRespected() throws Exception {
        PipedOutputStream out = new PipedOutputStream();
        PipedInputStream in = new PipedInputStream(out, 32768);

        CollectingSubscriber sub = new CollectingSubscriber(1); // request exactly one
        new AudioStreamPublisher(in, NoopObservationHandle.INSTANCE, System.nanoTime()).subscribe(sub);

        out.write(new byte[]{1, 2, 3});
        out.flush();
        sub.nextReceived.get(5, TimeUnit.SECONDS);
        assertEquals(1, sub.chunks.size());

        out.write(new byte[]{4, 5});
        out.flush();
        // Demand is exhausted, so the producer takes chunk 2 and parks in awaitDemand. Wait for
        // the park itself before requesting: a request that lands first is honoured too, but then
        // the producer never waits, and this is the test that proves wait() wakes on request.
        awaitParkedInAwaitDemand(sub.producer);
        sub.subscription.request(1);
        sub.secondReceived.get(5, TimeUnit.SECONDS);
        assertEquals(2, sub.chunks.size());

        out.close();
        sub.completed.get(5, TimeUnit.SECONDS);
    }

    @Test
    void zeroLengthReadsAreSkipped() throws Exception {
        // A transport read may legally return 0 bytes; the producer must loop rather than emit
        // an empty chunk or complete early.
        AtomicBoolean zeroServed = new AtomicBoolean();
        InputStream body = new InputStream() {
            private final ByteArrayInputStream data = new ByteArrayInputStream(new byte[]{7, 8});
            @Override
            public int read() throws IOException {
                return data.read();
            }
            @Override
            public int read(byte[] b, int off, int len) throws IOException {
                if (zeroServed.compareAndSet(false, true)) {
                    return 0;
                }
                return data.read(b, off, len);
            }
        };

        CollectingSubscriber sub = new CollectingSubscriber(Long.MAX_VALUE);
        new AudioStreamPublisher(body, NoopObservationHandle.INSTANCE, System.nanoTime()).subscribe(sub);

        sub.completed.get(5, TimeUnit.SECONDS);
        assertEquals(1, sub.chunks.size());
        assertArrayEquals(new byte[]{7, 8}, sub.chunks.getFirst());
    }

    @Test
    void secondSubscriberIsRejected() throws Exception {
        AudioStreamPublisher publisher = new AudioStreamPublisher(new ByteArrayInputStream(new byte[0]), NoopObservationHandle.INSTANCE, System.nanoTime());

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
        PipedInputStream piped = new PipedInputStream(out, 32768);
        InputStream body = new InputStream() {
            public int read() throws IOException { return piped.read(); }
            public int read(byte[] b, int off, int len) throws IOException { return piped.read(b, off, len); }
            public void close() throws IOException { closed.set(true); piped.close(); }
        };

        // Cancel synchronously from inside onNext (producer thread) so `cancelled=true` is
        // published before the producer re-evaluates the while-loop header — same determinism
        // rationale as SseStreamPublisherTest.
        CollectingSubscriber sub = new CollectingSubscriber(Long.MAX_VALUE) {
            @Override
            public void onNext(byte[] item) {
                super.onNext(item);
                subscription.cancel();
            }
        };
        RecordingObservation obs = new RecordingObservation();
        new AudioStreamPublisher(body, obs, System.nanoTime()).subscribe(sub);

        out.write(new byte[]{1});
        out.flush();
        sub.nextReceived.get(5, TimeUnit.SECONDS);
        // The producer closes the observation in its finally block, so this latch firing proves
        // run() has exited — anything it was going to signal, it has signalled.
        assertTrue(obs.closed.await(5, TimeUnit.SECONDS), "the producer must finish");

        assertEquals(1, sub.chunks.size());
        assertFalse(sub.completedFlag.get(), "onComplete must not fire after cancel");
        assertTrue(closed.get(), "underlying body must be closed on cancel");
    }

    @Test
    void ioErrorDuringReadSurfacesAsOnError() throws Exception {
        InputStream broken = new InputStream() {
            public int read() throws IOException { throw new IOException("boom"); }
        };
        CollectingSubscriber sub = new CollectingSubscriber(Long.MAX_VALUE);
        new AudioStreamPublisher(broken, NoopObservationHandle.INSTANCE, System.nanoTime()).subscribe(sub);

        Throwable err = sub.errored.get(5, TimeUnit.SECONDS);
        assertInstanceOf(IOException.class, err);
    }

    @Test
    void requestZeroTerminatesWithIllegalArgument() throws Exception {
        CollectingSubscriber sub = new CollectingSubscriber(0); // no initial demand
        new AudioStreamPublisher(new ByteArrayInputStream(new byte[0]), NoopObservationHandle.INSTANCE, System.nanoTime()).subscribe(sub);

        sub.subscription.request(0);
        Throwable err = sub.errored.get(5, TimeUnit.SECONDS);
        assertInstanceOf(IllegalArgumentException.class, err);
    }

    @Test
    void requestOverflowSaturatesToMaxValue() throws Exception {
        CollectingSubscriber sub = new CollectingSubscriber(0);
        new AudioStreamPublisher(new ByteArrayInputStream(new byte[]{1, 2, 3}), NoopObservationHandle.INSTANCE, System.nanoTime()).subscribe(sub);

        // Long.MAX_VALUE twice — must not roll negative.
        sub.subscription.request(Long.MAX_VALUE);
        sub.subscription.request(Long.MAX_VALUE);

        sub.completed.get(5, TimeUnit.SECONDS);
        assertArrayEquals(new byte[]{1, 2, 3}, concat(sub.chunks));
    }

    @Test
    void nullArgsAreRejected() {
        assertThrows(NullPointerException.class, () -> new AudioStreamPublisher(null, NoopObservationHandle.INSTANCE, System.nanoTime()));
        assertThrows(NullPointerException.class,
                () -> new AudioStreamPublisher(new ByteArrayInputStream(new byte[0]), null, System.nanoTime()));

        AudioStreamPublisher publisher = new AudioStreamPublisher(new ByteArrayInputStream(new byte[0]), NoopObservationHandle.INSTANCE, System.nanoTime());
        assertThrows(NullPointerException.class, () -> publisher.subscribe(null));
    }

    @Test
    void cancelDuringAwaitDemandExitsWithoutDelivery() throws Exception {
        PipedOutputStream out = new PipedOutputStream();
        PipedInputStream in = new PipedInputStream(out, 32768);

        CollectingSubscriber sub = new CollectingSubscriber(1); // only allow one chunk
        RecordingObservation obs = new RecordingObservation();
        new AudioStreamPublisher(in, obs, System.nanoTime()).subscribe(sub);

        out.write(new byte[]{1});
        out.flush();
        sub.nextReceived.get(5, TimeUnit.SECONDS);

        // Second chunk arrives while demand is exhausted; the producer takes it and parks in
        // awaitDemand. The cancel must land on the parked producer and not earlier: a reader
        // parked inside PipedInputStream.read() (its wait(1000) loop checks closedByReader only on
        // entry) is never woken by close(), so cancelling then would hang the producer, and a
        // cancel that beats the producer to the loop check is safe but leaves wait() unexercised.
        // Only the park itself is observable, so wait for that. (A latch on the second read
        // returning, and before it a fixed sleep, both stood in for this and only narrowed the
        // race — the Java 25 CI leg lost it.)
        out.write(new byte[]{2});
        out.flush();
        awaitParkedInAwaitDemand(sub.producer);

        sub.subscription.cancel();
        assertTrue(obs.closed.await(5, TimeUnit.SECONDS), "cancel must wake and finish the producer");

        assertEquals(1, sub.chunks.size(), "second chunk must not be delivered after cancel");
        assertFalse(sub.completedFlag.get());
    }

    @Test
    void interruptDuringAwaitDemandSurfacesAsError() throws Exception {
        CollectingSubscriber sub = new CollectingSubscriber(1) {
            @Override
            public void onNext(byte[] item) {
                super.onNext(item);
                // Self-interrupt the producer (we are executing on it) so the next awaitDemand
                // wait() throws immediately instead of blocking forever.
                Thread.currentThread().interrupt();
            }
        };
        byte[] payload = new byte[10_000]; // two chunks; demand of 1 forces awaitDemand
        new AudioStreamPublisher(new ByteArrayInputStream(payload), NoopObservationHandle.INSTANCE, System.nanoTime()).subscribe(sub);

        Throwable err = sub.errored.get(5, TimeUnit.SECONDS);
        assertInstanceOf(FanarTransportException.class, err);
        assertInstanceOf(InterruptedException.class, err.getCause());
        assertTrue(err.getMessage().contains("interrupted"));
        assertEquals(1, sub.chunks.size());
    }

    @Test
    void ioErrorAfterCancelIsSwallowed() throws Exception {
        // Exercises the catch (Throwable) branch where cancel has already been called: the read
        // throws because we just closed the body; the producer must not surface that to the
        // subscriber (they asked to stop).
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
            public int read(byte[] b, int off, int len) throws IOException {
                int c = read();
                return c;
            }
            @Override
            public void close() { closed.countDown(); }
        };

        CollectingSubscriber sub = new CollectingSubscriber(Long.MAX_VALUE);
        RecordingObservation obs = new RecordingObservation();
        new AudioStreamPublisher(body, obs, System.nanoTime()).subscribe(sub);
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
        new AudioStreamPublisher(body, obs, System.nanoTime()).subscribe(sub);

        // Must not propagate the close IOException out of cancel().
        sub.subscription.cancel();
        // Asserted before the latch: only cancel() can have set it this early. The producer's
        // own close() in its finally block would set it too, and prove nothing about cancel().
        assertTrue(closeCalled.get(), "cancel() must close the body");

        assertTrue(obs.closed.await(5, TimeUnit.SECONDS), "the producer must finish");
        assertFalse(sub.errored.isDone(), "close-time IOException must be swallowed silently");
    }

    // --- helpers

    @Test
    void observationSpansTheStreamAndRecordsChunkMetricsOnCompletion() throws Exception {
        RecordingObservation obs = new RecordingObservation();
        CollectingSubscriber sub = new CollectingSubscriber(Long.MAX_VALUE);
        new AudioStreamPublisher(new ByteArrayInputStream(new byte[]{1, 2, 3}), obs, System.nanoTime())
                .subscribe(sub);

        sub.completed.get(5, TimeUnit.SECONDS);
        assertTrue(obs.closed.await(5, TimeUnit.SECONDS), "the observation must close at the terminal signal");

        assertEquals(1L, obs.attributes.get(FanarObservationAttributes.FANAR_STREAM_CHUNKS));
        assertNotNull(obs.attributes.get(FanarObservationAttributes.FANAR_STREAM_FIRST_CHUNK_MS),
                "first-chunk latency is recorded when the first chunk arrives");
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
        new AudioStreamPublisher(broken, obs, System.nanoTime()).subscribe(sub);

        sub.errored.get(5, TimeUnit.SECONDS);
        assertTrue(obs.closed.await(5, TimeUnit.SECONDS), "a failed stream still closes its observation");

        assertInstanceOf(IOException.class, obs.error.get(), "the failure reaches the observation");
        assertEquals(0L, obs.attributes.get(FanarObservationAttributes.FANAR_STREAM_CHUNKS),
                "no chunk arrived before the failure");
    }

    /** Records what the publisher puts on the observation, and when it closes it. */
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

    private static byte[] concat(List<byte[]> chunks) {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        chunks.forEach(c -> buf.writeBytes(c));
        return buf.toByteArray();
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

    private static class CollectingSubscriber implements Flow.Subscriber<byte[]> {
        final List<byte[]> chunks = new CopyOnWriteArrayList<>();
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
        public void onNext(byte[] item) {
            producer = Thread.currentThread();
            chunks.add(item);
            if (!nextReceived.isDone()) nextReceived.complete(null);
            else if (!secondReceived.isDone()) secondReceived.complete(null);
        }
        @Override
        public void onError(Throwable throwable) { errored.complete(throwable); completed.complete(null); }
        @Override
        public void onComplete() { completedFlag.set(true); completed.complete(null); }
    }
}

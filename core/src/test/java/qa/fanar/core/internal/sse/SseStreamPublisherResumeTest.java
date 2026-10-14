package qa.fanar.core.internal.sse;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Flow;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;

import qa.fanar.core.FanarInternalServerException;
import qa.fanar.core.FanarNotFoundException;
import qa.fanar.core.FanarTransportException;
import qa.fanar.core.chat.TokenChunk;
import qa.fanar.core.sadiq.DeepResearchEvent;
import qa.fanar.core.spi.FanarJsonCodec;
import qa.fanar.core.spi.FanarObservationAttributes;
import qa.fanar.core.spi.ObservationHandle;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The job stream's resume across responses (ADR-032): {@code SseStreamPublisher.forDeepResearchJob}.
 * Every frame's payload is its event's id ({@link #CODEC}), so a test asserts the delivered sequence
 * directly. Interleavings are forced with latches and gates inside the fakes, never by sleeping.
 */
class SseStreamPublisherResumeTest {

    private static final String ERROR_FRAME =
            "{\"error\":{\"code\":\"internal_server_error\",\"message\":\"worker died\",\"status\":500}}";

    // --- completion and resume

    @Test
    void completesAtDoneWithoutWaitingForTheResponseToEnd() throws Exception {
        // The response stays open after [DONE]: completing proves the reader stops at the sentinel.
        Response first = Response.holding("id: 1\ndata: a\n\nid: 2\ndata: [DONE]\n\n");
        Run run = run(first, new ScriptedResumer());

        run.sub.completed.get(5, TimeUnit.SECONDS);
        assertEquals(List.of("a"), run.sub.ids());
        assertTrue(run.obs.closed.await(5, TimeUnit.SECONDS));
        assertEquals(1L, run.obs.attributes.get(FanarObservationAttributes.FANAR_STREAM_CHUNKS));
        assertEquals(List.of(), run.obs.events, "nothing resumed");
        assertTrue(first.closes.get() >= 1, "the response is closed once the job ended");
    }

    @Test
    void resumesAfterTheLastCommittedIdUntilDone() throws Exception {
        Response first = Response.ending("id: 1\ndata: a\n\nid: 2\ndata: b\n\n");
        Response second = Response.ending("id: 3\ndata: c\n\nid: 4\ndata: [DONE]\n\n");
        AtomicInteger firstClosesWhenResumed = new AtomicInteger(-1);
        ScriptedResumer resumer = new ScriptedResumer().then(() -> {
            firstClosesWhenResumed.set(first.closes.get());
            return second;
        });
        Run run = run(first, resumer);

        run.sub.completed.get(5, TimeUnit.SECONDS);
        assertEquals(List.of("a", "b", "c"), run.sub.ids(), "each event once, in order, across responses");
        assertEquals(List.of("2"), resumer.lastEventIds);
        assertEquals(1, firstClosesWhenResumed.get(), "a finished response is released before the next opens");
        assertTrue(run.obs.closed.await(5, TimeUnit.SECONDS));
        assertEquals(List.of("stream_resumed"), run.obs.events);
        assertEquals(3L, run.obs.attributes.get(FanarObservationAttributes.FANAR_STREAM_CHUNKS),
                "chunks count across responses");
        assertNotNull(run.obs.attributes.get(FanarObservationAttributes.FANAR_STREAM_FIRST_CHUNK_MS));
        assertEquals(1, run.obs.attributeWrites.get(FanarObservationAttributes.FANAR_STREAM_FIRST_CHUNK_MS).get(),
                "first-chunk latency is the stream's, recorded once");
        assertEquals(1, run.obs.closes.get());
        assertNull(run.obs.error.get());
    }

    @Test
    void aDropMidEventResumesFromTheLastCompleteEvent() throws Exception {
        Response first = Response.dropping("id: 1\ndata: a\n\nid: 2\ndata: {par");
        ScriptedResumer resumer = new ScriptedResumer()
                .opens(Response.ending("id: 2\ndata: b\n\nid: 3\ndata: [DONE]\n\n"));
        Run run = run(first, resumer);

        run.sub.completed.get(5, TimeUnit.SECONDS);
        assertEquals(List.of("1"), resumer.lastEventIds,
                "the event cut off by the drop was never committed, so the server sends it again");
        assertEquals(List.of("a", "b"), run.sub.ids());
    }

    @Test
    void aResponseThatEndsBeforeAnyIdResumesFromTheFirstEvent() throws Exception {
        ScriptedResumer resumer = new ScriptedResumer()
                .opens(Response.ending("id: 1\ndata: a\n\nid: 2\ndata: [DONE]\n\n"));
        Run run = run(Response.ending(""), resumer);

        run.sub.completed.get(5, TimeUnit.SECONDS);
        assertEquals(java.util.Collections.singletonList(null), resumer.lastEventIds,
                "nothing was delivered, so starting over replays nothing");
        assertEquals(List.of("a"), run.sub.ids());
    }

    @Test
    void eventsWithoutAnIdKeepThePreviousResumePoint() throws Exception {
        ScriptedResumer resumer = new ScriptedResumer()
                .opens(Response.ending("id: 3\ndata: c\n\ndata: [DONE]\n\n"));
        Run run = run(Response.ending("id: 1\ndata: a\n\ndata: b\n\n"), resumer);

        run.sub.completed.get(5, TimeUnit.SECONDS);
        assertEquals(List.of("1"), resumer.lastEventIds);
        assertEquals(List.of("a", "b", "c"), run.sub.ids());
    }

    @Test
    void demandCarriesAcrossResponses() throws Exception {
        ScriptedResumer resumer = new ScriptedResumer()
                .opens(Response.ending("id: 2\ndata: b\n\nid: 3\ndata: [DONE]\n\n"));
        Run run = run(Response.ending("id: 1\ndata: a\n\n"), resumer, 1);

        assertTrue(run.sub.first.await(5, TimeUnit.SECONDS), "the first event arrives");
        // Demand is spent: the producer resumes, decodes `b` and parks until the next request.
        awaitParkedInAwaitDemand(run.sub.producer);
        run.sub.subscription.request(1);

        run.sub.completed.get(5, TimeUnit.SECONDS);
        assertEquals(List.of("a", "b"), run.sub.ids());
    }

    // --- guards

    @Test
    void threeConsecutiveResponsesWithoutABlockFailTheStream() throws Exception {
        ScriptedResumer resumer = new ScriptedResumer()
                .opens(Response.ending(""))
                .opens(Response.ending("data: {\"cut"))
                .opens(Response.dropping(""));
        Run run = run(Response.ending("id: 1\ndata: a\n\n"), resumer);

        Throwable error = run.sub.errored.get(5, TimeUnit.SECONDS);
        FanarTransportException stalled = assertInstanceOf(FanarTransportException.class, error);
        assertTrue(stalled.getMessage().contains("3 consecutive responses"), stalled.getMessage());
        assertInstanceOf(IOException.class, stalled.getCause(), "the last response's drop is the cause");
        assertEquals(List.of("1", "1", "1"), resumer.lastEventIds);
        assertEquals(List.of("a"), run.sub.ids());
        assertTrue(run.obs.closed.await(5, TimeUnit.SECONDS));
        assertSame(error, run.obs.error.get());
        assertEquals(3, run.obs.events.size(), "three resumes, then the guard");
    }

    @Test
    void aKeepaliveBlockResetsTheStallCount() throws Exception {
        ScriptedResumer resumer = new ScriptedResumer()
                .opens(Response.ending(""))
                .opens(Response.ending(""))
                .opens(Response.ending(": keepalive\n\n"))
                .opens(Response.ending(""))
                .opens(Response.ending(""))
                .opens(Response.ending("id: 2\ndata: [DONE]\n\n"));
        Run run = run(Response.ending("id: 1\ndata: a\n\n"), resumer);

        run.sub.completed.get(5, TimeUnit.SECONDS);
        assertEquals(6, resumer.lastEventIds.size(), "two empty responses, a keepalive, two more, then [DONE]");
        assertEquals(List.of("a"), run.sub.ids());
    }

    @Test
    void aResumeAfterEventsWithoutAnyIdFailsRatherThanReplay() throws Exception {
        ScriptedResumer resumer = new ScriptedResumer();
        Run run = run(Response.ending("data: a\n\n"), resumer);

        FanarTransportException error = assertInstanceOf(FanarTransportException.class,
                run.sub.errored.get(5, TimeUnit.SECONDS));
        assertTrue(error.getMessage().contains("without an event id"), error.getMessage());
        assertEquals(List.of(), resumer.lastEventIds, "never resumed");
        assertEquals(List.of("a"), run.sub.ids());
    }

    @Test
    void aFailedResumeEndsTheStreamWithItsOwnException() throws Exception {
        for (Throwable failure : List.of(new FanarNotFoundException("No job found with this id."),
                new IllegalStateException("bug"), new AssertionError("error"))) {
            ScriptedResumer resumer = new ScriptedResumer().then(() -> {
                if (failure instanceof Error e) {
                    throw e;
                }
                throw (RuntimeException) failure;
            });
            Run run = run(Response.ending("id: 1\ndata: a\n\n"), resumer);

            assertSame(failure, run.sub.errored.get(5, TimeUnit.SECONDS), "passed through as thrown");
            assertTrue(run.obs.closed.await(5, TimeUnit.SECONDS));
            assertSame(failure, run.obs.error.get());
            assertEquals(List.of(), run.obs.events, "nothing resumed");
        }
    }

    @Test
    void anUndecodableFrameFailsWithoutResuming() throws Exception {
        ScriptedResumer resumer = new ScriptedResumer();
        Run run = run(Response.ending("id: 1\ndata: bad\n\n"), resumer);

        assertInstanceOf(FanarTransportException.class, run.sub.errored.get(5, TimeUnit.SECONDS));
        assertEquals(List.of(), resumer.lastEventIds);
    }

    // --- error frames (held, superseded, or delivered)

    @Test
    void anErrorFrameFollowedByDoneFailsTheStreamWithTheTypedError() throws Exception {
        ScriptedResumer resumer = new ScriptedResumer();
        Run run = run(Response.ending(
                "id: 1\ndata: a\n\nid: 2\ndata: " + ERROR_FRAME + "\n\nid: 3\ndata: [DONE]\n\n"), resumer);

        FanarInternalServerException error = assertInstanceOf(FanarInternalServerException.class,
                run.sub.errored.get(5, TimeUnit.SECONDS));
        assertEquals("worker died", error.getMessage());
        assertEquals(List.of("a"), run.sub.ids());
        assertTrue(run.obs.closed.await(5, TimeUnit.SECONDS));
        assertSame(error, run.obs.error.get());
    }

    @Test
    void anErrorFrameFollowedByEventsIsSuperseded() throws Exception {
        // A restarted worker replays the job's events after the failure it reported.
        Run run = run(Response.ending("id: 1\ndata: a\n\nid: 2\ndata: " + ERROR_FRAME
                + "\n\nid: 3\ndata: a\n\nid: 4\ndata: [DONE]\n\n"), new ScriptedResumer());

        run.sub.completed.get(5, TimeUnit.SECONDS);
        assertEquals(List.of("a", "a"), run.sub.ids(), "the replay is delivered, not deduplicated");
        assertTrue(run.obs.closed.await(5, TimeUnit.SECONDS));
        assertEquals(List.of("stream_error_superseded"), run.obs.events);
        assertNull(run.obs.error.get());
    }

    @Test
    void aHeldErrorOutranksTheFailureThatEndsTheStream() throws Exception {
        FanarNotFoundException gone = new FanarNotFoundException("No job found with this id.");
        ScriptedResumer resumer = new ScriptedResumer().then(() -> {
            throw gone;
        });
        Run run = run(Response.ending("id: 1\ndata: " + ERROR_FRAME + "\n\n"), resumer);

        FanarInternalServerException error = assertInstanceOf(FanarInternalServerException.class,
                run.sub.errored.get(5, TimeUnit.SECONDS));
        assertEquals(List.of(gone), Arrays.asList(error.getSuppressed()), "the failure travels as suppressed");
        assertEquals(List.of("1"), resumer.lastEventIds, "an error frame's id is a resume point too");
    }

    // --- cancellation in every phase

    @Test
    void cancelDuringAReconnectInterruptsItAndEndsSilently() throws Exception {
        ScriptedResumer resumer = new ScriptedResumer().blocksInterruptibly();
        Run run = run(Response.ending("id: 1\ndata: a\n\n"), resumer);

        assertTrue(resumer.entered.await(5, TimeUnit.SECONDS), "the producer is reconnecting");
        run.sub.subscription.cancel();

        assertTrue(run.obs.closed.await(5, TimeUnit.SECONDS), "the interrupted reconnect ends the producer");
        assertTrue(resumer.sawInterrupt, "cancel() interrupts a reconnect in flight");
        assertFalse(run.obs.interruptedAtClose, "the interrupt aimed at the reconnect is cleared");
        assertFalse(run.sub.errored.isDone(), "no terminal signal after cancel");
        assertFalse(run.sub.completed.isDone(), "no terminal signal after cancel");
    }

    @Test
    void cancelRacingACompletedReconnectClosesTheResponseItOpened() throws Exception {
        Response second = Response.holding("id: 2\ndata: b\n\n");
        ScriptedResumer resumer = new ScriptedResumer().blocksUninterruptiblyThenOpens(second);
        Run run = run(Response.ending("id: 1\ndata: a\n\n"), resumer);

        assertTrue(resumer.entered.await(5, TimeUnit.SECONDS));
        run.sub.subscription.cancel();
        resumer.gate.release();

        assertTrue(run.obs.closed.await(5, TimeUnit.SECONDS));
        assertTrue(resumer.sawInterrupt, "cancel() reached the reconnect while it was in flight");
        assertEquals(0, second.reads.get(), "the response that arrived after the cancel is never read");
        assertTrue(second.closes.get() >= 1, "and it is closed");
        assertFalse(run.obs.interruptedAtClose);
        assertFalse(run.sub.errored.isDone());
        assertFalse(run.sub.completed.isDone());
    }

    @Test
    void cancelBetweenResponsesStartsNoReconnect() throws Exception {
        CountDownLatch closing = new CountDownLatch(1);
        CountDownLatch proceed = new CountDownLatch(1);
        Response first = Response.ending("id: 1\ndata: a\n\n");
        // The producer closes a finished response before it reconnects: hold it there.
        first.onFirstClose = () -> {
            closing.countDown();
            awaitQuietly(proceed);
        };
        ScriptedResumer resumer = new ScriptedResumer();
        Run run = run(first, resumer);

        assertTrue(closing.await(5, TimeUnit.SECONDS), "the first response ended");
        run.sub.subscription.cancel();
        proceed.countDown();

        assertTrue(run.obs.closed.await(5, TimeUnit.SECONDS));
        assertEquals(List.of(), resumer.lastEventIds, "no request starts after the cancel");
        assertFalse(run.sub.errored.isDone());
        assertFalse(run.sub.completed.isDone());
    }

    @Test
    void cancelWhileReadingAResumedResponseClosesIt() throws Exception {
        Response second = Response.holding("id: 2\ndata: b\n\n");
        Run run = run(Response.ending("id: 1\ndata: a\n\n"), new ScriptedResumer().opens(second));

        assertTrue(second.parked.await(5, TimeUnit.SECONDS), "the producer waits on the resumed response");
        run.sub.subscription.cancel();

        assertTrue(run.obs.closed.await(5, TimeUnit.SECONDS),
                "only cancel() closing the current response can release that read");
        assertTrue(second.closes.get() >= 1);
        assertEquals(List.of("a", "b"), run.sub.ids());
        assertFalse(run.sub.completed.isDone());
        assertFalse(run.sub.errored.isDone());
    }

    @Test
    void requestZeroDuringAReconnectFailsTheSubscriptionAndInterruptsIt() throws Exception {
        ScriptedResumer resumer = new ScriptedResumer().blocksInterruptibly();
        Run run = run(Response.ending("id: 1\ndata: a\n\n"), resumer);

        assertTrue(resumer.entered.await(5, TimeUnit.SECONDS));
        run.sub.subscription.request(0);

        assertInstanceOf(IllegalArgumentException.class, run.sub.errored.get(5, TimeUnit.SECONDS));
        assertTrue(run.obs.closed.await(5, TimeUnit.SECONDS));
        assertTrue(resumer.sawInterrupt);
        assertFalse(run.obs.interruptedAtClose);
    }

    @Test
    void forDeepResearchJobRejectsNulls() {
        ScriptedResumer resumer = new ScriptedResumer();
        RecordingObservation obs = new RecordingObservation();
        assertThrows(NullPointerException.class,
                () -> SseStreamPublisher.forDeepResearchJob(null, resumer, CODEC, obs, 0L));
        assertThrows(NullPointerException.class,
                () -> SseStreamPublisher.forDeepResearchJob(Response.ending(""), null, CODEC, obs, 0L));
        assertThrows(NullPointerException.class,
                () -> SseStreamPublisher.forDeepResearchJob(Response.ending(""), resumer, null, obs, 0L));
        assertThrows(NullPointerException.class,
                () -> SseStreamPublisher.forDeepResearchJob(Response.ending(""), resumer, CODEC, null, 0L));
    }

    // --- fixtures

    private record Run(Collecting sub, RecordingObservation obs) { }

    private static Run run(Response first, ScriptedResumer resumer) {
        return run(first, resumer, Long.MAX_VALUE);
    }

    private static Run run(Response first, ScriptedResumer resumer, long demand) {
        RecordingObservation obs = new RecordingObservation();
        Collecting sub = new Collecting(demand);
        SseStreamPublisher.forDeepResearchJob(first, resumer, CODEC, obs, System.nanoTime()).subscribe(sub);
        return new Run(sub, obs);
    }

    /**
     * Each frame's payload becomes a {@link TokenChunk} whose id is the payload; {@code bad} cannot be
     * decoded; a payload with a top-level {@code error} is an error frame, whose envelope the decoder
     * parses itself.
     */
    private static final FanarJsonCodec CODEC = new FanarJsonCodec() {
        @Override
        @SuppressWarnings("unchecked")
        public <T> T decode(InputStream stream, Class<T> type) throws IOException {
            String payload = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
            if ("bad".equals(payload)) {
                throw new IOException("undecodable");
            }
            if (type == Map.class) {
                return (T) (payload.contains("\"error\"") ? Map.of("error", Map.of()) : Map.of());
            }
            return type.cast(new TokenChunk(payload, 0L, null, List.of()));
        }

        @Override
        public void encode(OutputStream stream, Object value) {
            throw new AssertionError("encode must not be called");
        }
    };

    /**
     * A response body: its bytes, then EOF ({@link #ending}), a dropped connection
     * ({@link #dropping}), or a read that blocks until {@link #close()} and then ends
     * ({@link #holding}) — a real response body releases a parked read when closed; a pipe does not.
     */
    private static final class Response extends InputStream {

        private enum End { EOF, DROP, HOLD }

        private final byte[] bytes;
        private final End end;
        private int pos;
        final AtomicInteger closes = new AtomicInteger();
        final AtomicInteger reads = new AtomicInteger();
        final CountDownLatch closed = new CountDownLatch(1);
        final CountDownLatch parked = new CountDownLatch(1);
        volatile Runnable onFirstClose;

        private Response(String sse, End end) {
            this.bytes = sse.getBytes(StandardCharsets.UTF_8);
            this.end = end;
        }

        static Response ending(String sse) {
            return new Response(sse, End.EOF);
        }

        static Response dropping(String sse) {
            return new Response(sse, End.DROP);
        }

        static Response holding(String sse) {
            return new Response(sse, End.HOLD);
        }

        @Override
        public int read() throws IOException {
            byte[] one = new byte[1];
            return read(one, 0, 1) < 0 ? -1 : one[0] & 0xff;
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            reads.incrementAndGet();
            if (pos < bytes.length) {
                int n = Math.min(len, bytes.length - pos);
                System.arraycopy(bytes, pos, b, off, n);
                pos += n;
                return n;
            }
            if (end == End.DROP) {
                throw new IOException("connection reset");
            }
            if (end == End.HOLD) {
                parked.countDown();
                awaitQuietly(closed);
            }
            return -1;
        }

        @Override
        public void close() {
            Runnable hook = onFirstClose;
            if (closes.getAndIncrement() == 0 && hook != null) {
                hook.run();
            }
            closed.countDown();
        }
    }

    /** Opens each later response from a script and records the id it was asked to resume after. */
    private static final class ScriptedResumer implements SseStreamPublisher.Resumer {

        private interface Step {
            InputStream open();
        }

        private final Queue<Step> steps = new ArrayDeque<>();
        final List<String> lastEventIds = new CopyOnWriteArrayList<>();
        final CountDownLatch entered = new CountDownLatch(1);
        final Semaphore gate = new Semaphore(0);
        volatile boolean sawInterrupt;

        ScriptedResumer opens(Response response) {
            return then(() -> response);
        }

        ScriptedResumer then(Step step) {
            steps.add(step);
            return this;
        }

        /** Waits until interrupted, then fails as the transport does when its wait is interrupted. */
        ScriptedResumer blocksInterruptibly() {
            return then(() -> {
                entered.countDown();
                try {
                    new CountDownLatch(1).await();
                    throw new AssertionError("unreachable");
                } catch (InterruptedException e) {
                    sawInterrupt = true;
                    Thread.currentThread().interrupt();
                    throw new FanarTransportException("HTTP request interrupted", e);
                }
            });
        }

        /** Waits for the gate ignoring interrupts, then returns {@code response} — a reply that lands anyway. */
        ScriptedResumer blocksUninterruptiblyThenOpens(Response response) {
            return then(() -> {
                entered.countDown();
                gate.acquireUninterruptibly();
                sawInterrupt = Thread.currentThread().isInterrupted();
                return response;
            });
        }

        @Override
        public InputStream resume(String lastEventId) {
            lastEventIds.add(lastEventId);
            Step step;
            synchronized (steps) {
                step = steps.poll();
            }
            if (step == null) {
                throw new AssertionError("unscripted resume after " + lastEventId);
            }
            return step.open();
        }
    }

    /** Records what the publisher puts on the observation, and the thread's interrupt flag at close. */
    private static final class RecordingObservation implements ObservationHandle {
        final Map<String, Object> attributes = new ConcurrentHashMap<>();
        final Map<String, AtomicInteger> attributeWrites = new ConcurrentHashMap<>();
        final List<String> events = new CopyOnWriteArrayList<>();
        final AtomicReference<Throwable> error = new AtomicReference<>();
        final AtomicInteger closes = new AtomicInteger();
        final CountDownLatch closed = new CountDownLatch(1);
        volatile boolean interruptedAtClose;

        @Override public ObservationHandle attribute(String key, Object value) {
            attributes.put(key, value);
            attributeWrites.computeIfAbsent(key, k -> new AtomicInteger()).incrementAndGet();
            return this;
        }
        @Override public ObservationHandle event(String name) { events.add(name); return this; }
        @Override public ObservationHandle error(Throwable t) { error.set(t); return this; }
        @Override public ObservationHandle child(String operationName) { return this; }
        @Override public Map<String, String> propagationHeaders() { return Map.of(); }
        @Override public void close() {
            interruptedAtClose = Thread.currentThread().isInterrupted();
            closes.incrementAndGet();
            closed.countDown();
        }
    }

    private static final class Collecting implements Flow.Subscriber<DeepResearchEvent> {
        final List<DeepResearchEvent> events = new CopyOnWriteArrayList<>();
        final CompletableFuture<Void> completed = new CompletableFuture<>();
        final CompletableFuture<Throwable> errored = new CompletableFuture<>();
        final CountDownLatch first = new CountDownLatch(1);
        final long initialDemand;
        volatile Flow.Subscription subscription;
        /** The producer thread, captured on delivery — onNext runs on it. */
        volatile Thread producer;

        Collecting(long initialDemand) {
            this.initialDemand = initialDemand;
        }

        List<String> ids() {
            return events.stream().map(DeepResearchEvent::id).toList();
        }

        @Override public void onSubscribe(Flow.Subscription s) {
            subscription = s;
            s.request(initialDemand);
        }
        @Override public void onNext(DeepResearchEvent item) {
            producer = Thread.currentThread();
            events.add(item);
            first.countDown();
        }
        @Override public void onError(Throwable t) { errored.complete(t); }
        @Override public void onComplete() { completed.complete(null); }
    }

    private static void awaitQuietly(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** Yields until the producer is parked in {@code awaitDemand}'s wait — no sleep. */
    private static void awaitParkedInAwaitDemand(Thread producer) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!(producer.getState() == Thread.State.WAITING && Arrays.stream(producer.getStackTrace())
                .anyMatch(frame -> "awaitDemand".equals(frame.getMethodName())))) {
            assertTrue(System.nanoTime() < deadline, "producer never parked in awaitDemand; " + producer.getState());
            Thread.yield();
        }
    }
}

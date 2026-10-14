package qa.fanar.core.internal.sse;

import java.io.BufferedReader;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.concurrent.CancellationException;
import java.util.concurrent.Flow;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import qa.fanar.core.FanarException;
import qa.fanar.core.FanarTransportException;
import qa.fanar.core.chat.StreamEvent;
import qa.fanar.core.sadiq.DeepResearchEvent;
import qa.fanar.core.spi.FanarJsonCodec;
import qa.fanar.core.spi.FanarObservationAttributes;
import qa.fanar.core.spi.ObservationHandle;

/**
 * {@link Flow.Publisher} that reads an SSE response body on a virtual thread and emits one
 * event per parsed frame — a {@link StreamEvent} for a chat stream ({@link #forChat}), a
 * {@link DeepResearchEvent} for a deep-research stream ({@link #forDeepResearch}) or a
 * deep-research job's events ({@link #forDeepResearchJob}).
 *
 * <p>Single-subscriber by construction: subscribing twice triggers {@code onError} on the
 * second subscriber. The first subscription launches a virtual thread that pulls lines from
 * the underlying {@link InputStream}, feeds them through {@link SseFrameAssembler}, decodes
 * each frame via the endpoint's {@link StreamEventDecoder}, and honours the subscriber's
 * {@code request(long)} demand before every {@code onNext}. Cancellation closes the response
 * being read, which releases a read parked on it.</p>
 *
 * <p>A job stream (ADR-032) is one stream over as many responses as the server sends: the
 * {@code [DONE]} sentinel completes it; a response that ends without it — cleanly or with a
 * dropped connection — is followed by the next one, which the {@link Resumer} opens after the last
 * committed event id. {@value #MAX_CONSECUTIVE_STALLS} consecutive responses that complete no
 * block, or a resume needed after events were delivered without any id, fail the stream with
 * {@link FanarTransportException}. An error frame is held rather than raised: an event after it
 * supersedes it, and {@code [DONE]} or a failure while it is held delivers it. Cancelling the
 * subscription also interrupts a reconnect in flight; it does not cancel the job.</p>
 *
 * <p>The publisher owns the caller's {@link ObservationHandle} for the life of the stream. It
 * records {@code fanar.stream.first_chunk_ms} when the first event arrives and
 * {@code fanar.stream.chunks} when the stream ends, then closes the handle — on completion,
 * failure and cancellation alike; a job stream adds a {@code stream_resumed} event per resumed
 * response and a {@code stream_error_superseded} event per superseded error frame. A publisher
 * that is never subscribed to never reaches that terminal path, so it leaves the observation open
 * along with the response body; subscribing exactly once is the contract.</p>
 *
 * <p>Internal (ADR-018).</p>
 *
 * @param <E> the endpoint's event union
 *
 * @author Oussama Mahjoub
 */
public final class SseStreamPublisher<E> implements Flow.Publisher<E> {

    /**
     * Consecutive responses of a job stream that may end without completing a single SSE block
     * before the stream fails — the default retry policy's attempt count (ADR-032).
     */
    static final int MAX_CONSECUTIVE_STALLS = 3;

    /**
     * Opens the next response of a resumable stream (ADR-032). Called on the publisher's thread,
     * which it may block; the thread is interrupted if the subscription is cancelled meanwhile.
     */
    @FunctionalInterface
    public interface Resumer {

        /**
         * Open the response that continues the stream.
         *
         * @param lastEventId the last event id the stream committed, or {@code null} to start
         *                    from the first event
         * @return the body of a successful response, which the publisher reads and closes
         */
        InputStream resume(String lastEventId);
    }

    private final InputStream body;
    private final StreamEventDecoder<E> decoder;
    private final ObservationHandle observation;
    private final long startNanos;
    private final Resumer resumer;
    private final AtomicBoolean subscribed = new AtomicBoolean();

    private SseStreamPublisher(InputStream body, StreamEventDecoder<E> decoder,
                               ObservationHandle observation, long startNanos, Resumer resumer) {
        this.body = Objects.requireNonNull(body, "body");
        this.decoder = decoder;
        this.observation = Objects.requireNonNull(observation, "observation");
        this.startNanos = startNanos;
        this.resumer = resumer;
    }

    /**
     * A publisher over a {@code POST /v1/chat/completions} stream.
     *
     * @param body        the SSE response body; must not be {@code null}
     * @param codec       codec used to decode each frame; must not be {@code null}
     * @param observation the caller's observation, which this publisher owns from here: it is
     *                    closed on the terminal signal, after the stream attributes are recorded.
     *                    Must not be {@code null} — pass the no-op handle when unobserved.
     * @param startNanos  {@code System.nanoTime()} taken before the request was sent, used to
     *                    derive {@code fanar.stream.first_chunk_ms}
     * @return the publisher
     */
    public static SseStreamPublisher<StreamEvent> forChat(InputStream body, FanarJsonCodec codec,
                                                          ObservationHandle observation, long startNanos) {
        return new SseStreamPublisher<>(body, StreamEventDecoder.forChat(codec), observation, startNanos, null);
    }

    /**
     * A publisher over a {@code POST /v1/sadiq/deep-research} stream. Same contract as
     * {@link #forChat}.
     *
     * @param body        the SSE response body; must not be {@code null}
     * @param codec       codec used to decode each frame; must not be {@code null}
     * @param observation the caller's observation, owned by the publisher from here; must not be
     *                    {@code null}
     * @param startNanos  {@code System.nanoTime()} taken before the request was sent
     * @return the publisher
     */
    public static SseStreamPublisher<DeepResearchEvent> forDeepResearch(InputStream body, FanarJsonCodec codec,
                                                                        ObservationHandle observation, long startNanos) {
        return new SseStreamPublisher<>(body, StreamEventDecoder.forDeepResearch(codec), observation, startNanos,
                null);
    }

    /**
     * A publisher over a deep-research job's events, {@code GET /v1/sadiq/deep-research/{id}?stream=true},
     * resumed across responses until {@code [DONE]} (ADR-032).
     *
     * @param firstResponse the first response's SSE body; must not be {@code null}
     * @param resumer       opens each later response; must not be {@code null}
     * @param codec         codec used to decode each frame; must not be {@code null}
     * @param observation   the caller's observation, owned by the publisher from here and spanning
     *                      every response; must not be {@code null}
     * @param startNanos    {@code System.nanoTime()} taken before the first request was sent
     * @return the publisher
     */
    public static SseStreamPublisher<DeepResearchEvent> forDeepResearchJob(InputStream firstResponse,
                                                                           Resumer resumer,
                                                                           FanarJsonCodec codec,
                                                                           ObservationHandle observation,
                                                                           long startNanos) {
        return new SseStreamPublisher<>(firstResponse, StreamEventDecoder.forDeepResearch(codec), observation,
                startNanos, Objects.requireNonNull(resumer, "resumer"));
    }

    @Override
    public void subscribe(Flow.Subscriber<? super E> subscriber) {
        Objects.requireNonNull(subscriber, "subscriber");
        if (!subscribed.compareAndSet(false, true)) {
            subscriber.onSubscribe(NoopSubscription.INSTANCE);
            subscriber.onError(new IllegalStateException(
                    "SseStreamPublisher supports a single subscriber"));
            return;
        }
        new Session(subscriber).start();
    }

    private final class Session implements Flow.Subscription {

        private final Flow.Subscriber<? super E> subscriber;
        private final AtomicLong demand = new AtomicLong();
        private final AtomicBoolean cancelled = new AtomicBoolean();
        private final Object demandLock = new Object();
        private final Object phaseLock = new Object();
        /** The response being read; {@link #cancel()} closes whichever one it is. */
        private volatile InputStream current = body;
        /** The producer thread while it is inside {@link Resumer#resume}; guarded by {@code phaseLock}. */
        private Thread reconnecting;
        // Producer-thread state. Counted here rather than in the subscriber so cancellation and
        // failure still report how far the stream got (ADR-013).
        private long chunks;
        private FanarException heldError;

        Session(Flow.Subscriber<? super E> subscriber) {
            this.subscriber = subscriber;
        }

        void start() {
            subscriber.onSubscribe(this);
            Thread.ofVirtual().name("fanar-sse-", 0).start(this::run);
        }

        @Override
        public void request(long n) {
            if (n <= 0) {
                cancelled.set(true);
                wake();
                interruptReconnect();
                subscriber.onError(new IllegalArgumentException(
                        "Flow.Subscription.request(n): n must be > 0"));
                return;
            }
            demand.updateAndGet(curr -> {
                long sum = curr + n;
                return sum < 0 ? Long.MAX_VALUE : sum;
            });
            wake();
        }

        @Override
        public void cancel() {
            cancelled.set(true);
            wake();
            interruptReconnect();
            closeQuietly(current);
        }

        private void wake() {
            synchronized (demandLock) {
                demandLock.notifyAll();
            }
        }

        private void interruptReconnect() {
            synchronized (phaseLock) {
                if (reconnecting != null) {
                    reconnecting.interrupt();
                }
            }
        }

        private void awaitDemand() throws InterruptedException {
            synchronized (demandLock) {
                while (!cancelled.get() && demand.get() <= 0) {
                    demandLock.wait();
                }
            }
        }

        private void run() {
            try {
                String lastEventId = null;
                int stalls = 0;
                while (true) {
                    SseFrameAssembler assembler = new SseFrameAssembler(lastEventId);
                    boolean done = false;
                    IOException drop = null;
                    try {
                        done = readResponse(current, assembler);
                    } catch (IOException e) {
                        if (resumer == null) {
                            throw e;
                        }
                        drop = e;
                    }
                    if (cancelled.get()) {
                        return;
                    }
                    if (done || resumer == null) {
                        if (heldError != null) {
                            // A job's events that end on an error frame report it (ADR-032).
                            observation.error(heldError);
                            subscriber.onError(heldError);
                        } else {
                            subscriber.onComplete();
                        }
                        return;
                    }
                    lastEventId = assembler.lastEventId();
                    stalls = assembler.progressed() ? 0 : stalls + 1;
                    if (stalls >= MAX_CONSECUTIVE_STALLS) {
                        throw new FanarTransportException("SSE stream stalled: " + MAX_CONSECUTIVE_STALLS
                                + " consecutive responses ended before completing an event", drop);
                    }
                    if (lastEventId == null && chunks > 0) {
                        // Resuming from the first event would replay what the subscriber has seen.
                        throw new FanarTransportException(
                                "SSE stream cannot resume: events were delivered without an event id", drop);
                    }
                    closeQuietly(current);
                    current = resume(lastEventId);
                    observation.event("stream_resumed");
                    if (cancelled.get()) {
                        // cancel() raced the reconnect; the finally block closes the new response.
                        return;
                    }
                }
            } catch (Throwable t) {
                if (cancelled.get()) {
                    // Torn down from the outside — swallow; the subscriber's terminal signal
                    // (if any) is its own responsibility.
                    return;
                }
                Throwable failure = t;
                if (t instanceof InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    failure = new FanarTransportException("SSE stream interrupted", ie);
                }
                if (heldError != null) {
                    // The server's own report outranks how the stream then failed (ADR-032).
                    heldError.addSuppressed(failure);
                    failure = heldError;
                }
                observation.error(failure);
                subscriber.onError(failure);
            } finally {
                closeQuietly(current);
                // Terminal for every exit — complete, error, or cancellation.
                observation.attribute(FanarObservationAttributes.FANAR_STREAM_CHUNKS, chunks);
                observation.close();
            }
        }

        /**
         * Read one response to its end, delivering its events.
         *
         * @return {@code true} when a job stream reached {@code [DONE]}; {@code false} when the
         *         response ended or the subscription was cancelled
         */
        private boolean readResponse(InputStream response, SseFrameAssembler assembler)
                throws IOException, InterruptedException {
            BufferedReader reader = new BufferedReader(new InputStreamReader(response, StandardCharsets.UTF_8));
            String line;
            while (!cancelled.get() && (line = reader.readLine()) != null) {
                SseFrame frame = assembler.accept(line);
                if (frame == null) {
                    continue;
                }
                if (resumer != null && StreamEventDecoder.isDone(frame)) {
                    return true;
                }
                E event = decoder.decode(frame, this::onErrorFrame);
                if (event == null) {
                    continue;
                }
                if (chunks == 0) {
                    // Measured at arrival, before awaitDemand: this is the server's
                    // time-to-first-token, not the subscriber's back-pressure.
                    observation.attribute(
                            FanarObservationAttributes.FANAR_STREAM_FIRST_CHUNK_MS,
                            (System.nanoTime() - startNanos) / 1_000_000L);
                }
                chunks++;
                awaitDemand();
                if (cancelled.get()) {
                    return false;
                }
                demand.decrementAndGet();
                if (heldError != null) {
                    // An event after an error frame: the job carried on — a restarted worker
                    // replays its events — so the error no longer describes how it ends (ADR-032).
                    heldError = null;
                    observation.event("stream_error_superseded");
                }
                subscriber.onNext(event);
            }
            return false;
        }

        private void onErrorFrame(FanarException error) {
            if (resumer == null) {
                // A single-response stream's connection is its run: the error frame ends it (ADR-031).
                throw error;
            }
            heldError = error;
        }

        private InputStream resume(String lastEventId) {
            synchronized (phaseLock) {
                if (cancelled.get()) {
                    throw new CancellationException("cancelled before the stream resumed");
                }
                reconnecting = Thread.currentThread();
            }
            try {
                return Objects.requireNonNull(resumer.resume(lastEventId), "resumer returned null");
            } finally {
                synchronized (phaseLock) {
                    reconnecting = null;
                    if (cancelled.get()) {
                        // Drop an interrupt cancel() aimed at the reconnect, so code the SDK runs
                        // later on this thread — observation plugins — never sees it.
                        Thread.interrupted();
                    }
                }
            }
        }
    }

    private static void closeQuietly(Closeable c) {
        try {
            c.close();
        } catch (IOException ignored) {
            // Best-effort cleanup — the stream is being torn down either way.
        }
    }

    private static final class NoopSubscription implements Flow.Subscription {
        static final NoopSubscription INSTANCE = new NoopSubscription();
        @Override public void request(long n) { /* no-op: already errored */ }
        @Override public void cancel() { /* no-op: already errored */ }
    }
}

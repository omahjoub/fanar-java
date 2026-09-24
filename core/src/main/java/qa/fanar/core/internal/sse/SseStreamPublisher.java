package qa.fanar.core.internal.sse;

import java.io.BufferedReader;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.concurrent.Flow;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import qa.fanar.core.FanarTransportException;
import qa.fanar.core.chat.StreamEvent;
import qa.fanar.core.sadiq.DeepResearchEvent;
import qa.fanar.core.spi.FanarJsonCodec;
import qa.fanar.core.spi.FanarObservationAttributes;
import qa.fanar.core.spi.ObservationHandle;

/**
 * {@link Flow.Publisher} that reads an SSE response body on a virtual thread and emits one
 * event per parsed frame — a {@link StreamEvent} for a chat stream ({@link #forChat}), a
 * {@link DeepResearchEvent} for a deep-research stream ({@link #forDeepResearch}).
 *
 * <p>Single-subscriber by construction: subscribing twice triggers {@code onError} on the
 * second subscriber. The first subscription launches a virtual thread that pulls lines from
 * the underlying {@link InputStream}, feeds them through {@link SseFrameAssembler}, decodes
 * each frame via the endpoint's {@link StreamEventDecoder}, and honours the subscriber's
 * {@code request(long)} demand before every {@code onNext}. Cancellation closes the stream
 * and interrupts the reader.</p>
 *
 * <p>The publisher owns the caller's {@link ObservationHandle} for the life of the stream. It
 * records {@code fanar.stream.first_chunk_ms} when the first event arrives and
 * {@code fanar.stream.chunks} when the stream ends, then closes the handle — on completion,
 * failure and cancellation alike. A publisher that is never subscribed to never reaches that
 * terminal path, so it leaves the observation open along with the response body; subscribing
 * exactly once is the contract.</p>
 *
 * <p>Internal (ADR-018).</p>
 *
 * @param <E> the endpoint's event union
 *
 * @author Oussama Mahjoub
 */
public final class SseStreamPublisher<E> implements Flow.Publisher<E> {

    private final InputStream body;
    private final StreamEventDecoder<E> decoder;
    private final ObservationHandle observation;
    private final long startNanos;
    private final AtomicBoolean subscribed = new AtomicBoolean();

    private SseStreamPublisher(InputStream body, StreamEventDecoder<E> decoder,
                               ObservationHandle observation, long startNanos) {
        this.body = Objects.requireNonNull(body, "body");
        this.decoder = decoder;
        this.observation = Objects.requireNonNull(observation, "observation");
        this.startNanos = startNanos;
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
        return new SseStreamPublisher<>(body, StreamEventDecoder.forChat(codec), observation, startNanos);
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
        return new SseStreamPublisher<>(body, StreamEventDecoder.forDeepResearch(codec), observation, startNanos);
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
            closeQuietly(body);
        }

        private void wake() {
            synchronized (demandLock) {
                demandLock.notifyAll();
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
            SseFrameAssembler assembler = new SseFrameAssembler();
            BufferedReader reader = new BufferedReader(
                    new InputStreamReader(body, StandardCharsets.UTF_8));
            // Counted here rather than in the subscriber so cancellation and failure still report
            // how far the stream got (ADR-013).
            long chunks = 0;
            try {
                String line;
                while (!cancelled.get() && (line = reader.readLine()) != null) {
                    SseFrame frame = assembler.accept(line);
                    if (frame == null) {
                        continue;
                    }
                    E event = decoder.decode(frame);
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
                        return;
                    }
                    demand.decrementAndGet();
                    subscriber.onNext(event);
                }
                if (!cancelled.get()) {
                    subscriber.onComplete();
                }
            } catch (Throwable t) {
                if (cancelled.get()) {
                    // Torn down from the outside — swallow; the subscriber's terminal signal
                    // (if any) is its own responsibility.
                    return;
                }
                if (t instanceof InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    FanarTransportException wrapped =
                            new FanarTransportException("SSE stream interrupted", ie);
                    observation.error(wrapped);
                    subscriber.onError(wrapped);
                } else {
                    observation.error(t);
                    subscriber.onError(t);
                }
            } finally {
                closeQuietly(reader);
                // Terminal for every exit — complete, error, or cancellation.
                observation.attribute(FanarObservationAttributes.FANAR_STREAM_CHUNKS, chunks);
                observation.close();
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

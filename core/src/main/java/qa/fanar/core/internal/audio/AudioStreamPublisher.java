package qa.fanar.core.internal.audio;

import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;
import java.util.Objects;
import java.util.concurrent.Flow;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import qa.fanar.core.FanarTransportException;
import qa.fanar.core.spi.FanarObservationAttributes;
import qa.fanar.core.spi.ObservationHandle;

/**
 * {@link Flow.Publisher} that reads a streamed audio response body on a virtual thread and emits
 * one {@code byte[]} per transport read, as the server generates the audio.
 *
 * <p>Structural twin of {@code qa.fanar.core.internal.sse.SseStreamPublisher}, minus frame
 * assembly and JSON decoding — audio chunks are opaque bytes in whatever container the request
 * selected (mp3 or wav). Chunk boundaries follow transport reads and carry no semantic meaning;
 * consumers concatenate them in emission order to reconstruct the full clip.</p>
 *
 * <p>Single-subscriber by construction: subscribing twice triggers {@code onError} on the second
 * subscriber. The first subscription launches a virtual thread that pulls chunks from the
 * underlying {@link InputStream} and honours the subscriber's {@code request(long)} demand
 * before every {@code onNext}. Cancellation closes the stream.</p>
 *
 * <p>The publisher owns the caller's {@link ObservationHandle} for the life of the stream. It
 * records {@code fanar.stream.first_chunk_ms} when the first chunk arrives and
 * {@code fanar.stream.chunks} when the stream ends, then closes the handle — on completion,
 * failure and cancellation alike. A publisher that is never subscribed to never reaches that
 * terminal path, so it leaves the observation open along with the response body; subscribing
 * exactly once is the contract.</p>
 *
 * <p>Internal (ADR-018).</p>
 *
 * @author Oussama Mahjoub
 */
public final class AudioStreamPublisher implements Flow.Publisher<byte[]> {

    private static final int CHUNK_SIZE = 8192;

    private final InputStream body;
    private final ObservationHandle observation;
    private final long startNanos;
    private final AtomicBoolean subscribed = new AtomicBoolean();

    /**
     * @param body        the streamed audio response body; must not be {@code null}
     * @param observation the caller's observation, which this publisher owns from here: it is
     *                    closed on the terminal signal, after the stream attributes are recorded.
     *                    Must not be {@code null} — pass the no-op handle when unobserved.
     * @param startNanos  {@code System.nanoTime()} taken before the request was sent, used to
     *                    derive {@code fanar.stream.first_chunk_ms}
     */
    public AudioStreamPublisher(InputStream body, ObservationHandle observation, long startNanos) {
        this.body = Objects.requireNonNull(body, "body");
        this.observation = Objects.requireNonNull(observation, "observation");
        this.startNanos = startNanos;
    }

    @Override
    public void subscribe(Flow.Subscriber<? super byte[]> subscriber) {
        Objects.requireNonNull(subscriber, "subscriber");
        if (!subscribed.compareAndSet(false, true)) {
            subscriber.onSubscribe(NoopSubscription.INSTANCE);
            subscriber.onError(new IllegalStateException(
                    "AudioStreamPublisher supports a single subscriber"));
            return;
        }
        new Session(subscriber).start();
    }

    private final class Session implements Flow.Subscription {

        private final Flow.Subscriber<? super byte[]> subscriber;
        private final AtomicLong demand = new AtomicLong();
        private final AtomicBoolean cancelled = new AtomicBoolean();
        private final Object demandLock = new Object();

        Session(Flow.Subscriber<? super byte[]> subscriber) {
            this.subscriber = subscriber;
        }

        void start() {
            subscriber.onSubscribe(this);
            Thread.ofVirtual().name("fanar-audio-stream-", 0).start(this::run);
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
            byte[] buffer = new byte[CHUNK_SIZE];
            // Counted here rather than in the subscriber so cancellation and failure still report
            // how far the stream got (ADR-013).
            long chunks = 0;
            try {
                int read;
                while (!cancelled.get() && (read = body.read(buffer)) != -1) {
                    if (read == 0) {
                        continue;
                    }
                    if (chunks == 0) {
                        // Measured at arrival, before awaitDemand: this is the server's
                        // time-to-first-audio, not the subscriber's back-pressure.
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
                    subscriber.onNext(Arrays.copyOf(buffer, read));
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
                            new FanarTransportException("Audio stream interrupted", ie);
                    observation.error(wrapped);
                    subscriber.onError(wrapped);
                } else {
                    observation.error(t);
                    subscriber.onError(t);
                }
            } finally {
                closeQuietly(body);
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

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
 * <p>Internal (ADR-018).</p>
 *
 * @author Oussama Mahjoub
 */
public final class AudioStreamPublisher implements Flow.Publisher<byte[]> {

    private static final int CHUNK_SIZE = 8192;

    private final InputStream body;
    private final AtomicBoolean subscribed = new AtomicBoolean();

    public AudioStreamPublisher(InputStream body) {
        this.body = Objects.requireNonNull(body, "body");
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
            try {
                int read;
                while (!cancelled.get() && (read = body.read(buffer)) != -1) {
                    if (read == 0) {
                        continue;
                    }
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
                    subscriber.onError(new FanarTransportException("Audio stream interrupted", ie));
                } else {
                    subscriber.onError(t);
                }
            } finally {
                closeQuietly(body);
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

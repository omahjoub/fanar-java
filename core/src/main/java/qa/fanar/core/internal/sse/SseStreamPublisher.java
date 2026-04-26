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
import qa.fanar.core.spi.FanarJsonCodec;

/**
 * {@link Flow.Publisher} that reads an SSE response body on a virtual thread and emits one
 * {@link StreamEvent} per parsed frame.
 *
 * <p>Single-subscriber by construction: subscribing twice triggers {@code onError} on the
 * second subscriber. The first subscription launches a virtual thread that pulls lines from
 * the underlying {@link InputStream}, feeds them through {@link SseFrameAssembler}, decodes
 * each frame via {@link StreamEventDecoder}, and honours the subscriber's
 * {@code request(long)} demand before every {@code onNext}. Cancellation closes the stream
 * and interrupts the reader.</p>
 *
 * <p>Internal (ADR-018).</p>
 *
 * @author Oussama Mahjoub
 */
public final class SseStreamPublisher implements Flow.Publisher<StreamEvent> {

    private final InputStream body;
    private final StreamEventDecoder decoder;
    private final AtomicBoolean subscribed = new AtomicBoolean();

    public SseStreamPublisher(InputStream body, FanarJsonCodec codec) {
        this.body = Objects.requireNonNull(body, "body");
        this.decoder = new StreamEventDecoder(Objects.requireNonNull(codec, "codec"));
    }

    @Override
    public void subscribe(Flow.Subscriber<? super StreamEvent> subscriber) {
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

        private final Flow.Subscriber<? super StreamEvent> subscriber;
        private final AtomicLong demand = new AtomicLong();
        private final AtomicBoolean cancelled = new AtomicBoolean();
        private final Object demandLock = new Object();

        Session(Flow.Subscriber<? super StreamEvent> subscriber) {
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
            try {
                String line;
                while (!cancelled.get() && (line = reader.readLine()) != null) {
                    SseFrame frame = assembler.accept(line);
                    if (frame == null) {
                        continue;
                    }
                    StreamEvent event = decoder.decode(frame);
                    if (event == null) {
                        continue;
                    }
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
                    subscriber.onError(new FanarTransportException("SSE stream interrupted", ie));
                } else {
                    subscriber.onError(t);
                }
            } finally {
                closeQuietly(reader);
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

package qa.fanar.core;

import java.util.Objects;
import java.util.Spliterator;
import java.util.Spliterators;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.Flow;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

/**
 * Bridges a {@link Flow.Publisher} into a blocking {@link Stream}.
 *
 * <p>The SDK is sync-primary (ADR-004), but streaming arrives as a {@code Flow.Publisher}
 * (ADR-005, ADR-023) because that is the JDK's own type for it and costs core no dependency.
 * Writing a {@code Flow.Subscriber} by hand to read a chat stream top-to-bottom is a lot of
 * ceremony for a loop, so this is the sync-shaped way to consume one:</p>
 *
 * {@snippet lang = "java":
 * try (Stream<StreamEvent> events = Streams.toStream(client.chat().stream(request))) {
 *     events.forEach(event -> {
 *         switch (event) {
 *             case TokenChunk t -> System.out.print(t.choices().getFirst().content());
 *             case DoneChunk d  -> System.out.println();
 *             default           -> { }
 *         }
 *     });
 * }
 *}
 *
 * <p><strong>Close the stream.</strong> It is returned inside try-with-resources above for a
 * reason: closing cancels the subscription and releases the underlying HTTP response. A stream
 * abandoned part-way — a {@code findFirst}, a {@code limit}, a {@code break} out of an iterator —
 * leaks the connection unless it is closed. A stream consumed to exhaustion has already released
 * it, so closing then is harmless.</p>
 *
 * <p>The returned stream is sequential and lazy: it pulls one item at a time, so the producer is
 * paced by the consumer rather than buffering the whole response. Consuming it blocks the calling
 * thread, which on a virtual thread does not hold a carrier (ADR-004). Do not run it
 * {@link Stream#parallel() in parallel} — a single publisher has one ordered sequence of
 * items.</p>
 *
 * <p>Failures surface where they happen: the exception the publisher reports is thrown from the
 * stream operation that was consuming it. Anything that is not already unchecked is wrapped in a
 * {@link FanarTransportException}, so a caller still only ever catches the sealed
 * {@link FanarException} hierarchy (ADR-006).</p>
 *
 * @author Oussama Mahjoub
 */
public final class Streams {

    private Streams() {
        // not instantiable
    }

    /**
     * Adapt a publisher into a blocking, sequential {@link Stream}.
     *
     * @param publisher the publisher to consume; must not be {@code null}. It is subscribed to
     *                  immediately, and single-subscriber publishers — which every publisher this
     *                  SDK returns is — can therefore be adapted only once.
     * @param <T>       the item type
     * @return a lazy, sequential stream over the publisher's items; close it to release the
     *         subscription early
     */
    public static <T> Stream<T> toStream(Flow.Publisher<T> publisher) {
        Objects.requireNonNull(publisher, "publisher");
        BlockingSubscriber<T> subscriber = new BlockingSubscriber<>();
        publisher.subscribe(subscriber);
        return StreamSupport.stream(subscriber.spliterator(), false).onClose(subscriber::cancel);
    }

    /** Terminal marker placed on the queue by {@code onComplete}. */
    private static final Object COMPLETE = new Object();

    /** Terminal marker placed on the queue by {@code onError}, carrying the cause. */
    private record Failure(Throwable cause) { }

    /**
     * Hands items from the publisher's thread to the consuming thread one at a time.
     *
     * <p>Demand is requested one item ahead, so a publisher that honours back-pressure — every
     * publisher this SDK returns — leaves at most the current item and a terminal marker on the
     * queue. The queue is unbounded rather than capacity-2 deliberately: a publisher that
     * overshoots its demand then buffers instead of deadlocking inside {@code onNext}.</p>
     */
    private static final class BlockingSubscriber<T> implements Flow.Subscriber<T> {

        private final BlockingQueue<Object> queue = new LinkedBlockingQueue<>();
        private final AtomicBoolean cancelled = new AtomicBoolean();
        private volatile Flow.Subscription subscription;

        @Override
        public void onSubscribe(Flow.Subscription s) {
            this.subscription = s;
            if (cancelled.get()) {
                s.cancel();
                return;
            }
            s.request(1);
        }

        @Override
        public void onNext(T item) {
            queue.add(item);
        }

        @Override
        public void onError(Throwable throwable) {
            queue.add(new Failure(throwable));
        }

        @Override
        public void onComplete() {
            queue.add(COMPLETE);
        }

        void cancel() {
            if (cancelled.compareAndSet(false, true)) {
                Flow.Subscription s = subscription;
                if (s != null) {
                    s.cancel();
                }
                // Unblock a consumer parked in take() on a stream closed from another thread.
                queue.add(COMPLETE);
            }
        }

        Spliterator<T> spliterator() {
            return new Spliterators.AbstractSpliterator<T>(
                    Long.MAX_VALUE,
                    Spliterator.ORDERED | Spliterator.NONNULL | Spliterator.IMMUTABLE) {

                @Override
                public boolean tryAdvance(Consumer<? super T> action) {
                    Objects.requireNonNull(action, "action");
                    Object taken = take();
                    if (taken == COMPLETE) {
                        // Re-arm the marker so a stray traversal after the end returns instead of
                        // parking forever. Cheaper and harder to get wrong than a terminal flag.
                        queue.add(COMPLETE);
                        return false;
                    }
                    if (taken instanceof Failure failure) {
                        queue.add(COMPLETE);
                        throw asUnchecked(failure.cause());
                    }
                    // Room has been made; ask for the next one before handing this one over.
                    subscription.request(1);
                    @SuppressWarnings("unchecked")
                    T typed = (T) taken;
                    action.accept(typed);
                    return true;
                }

                private Object take() {
                    try {
                        return queue.take();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        cancel();
                        throw new FanarTransportException(
                                "Interrupted while awaiting the next stream item", e);
                    }
                }
            };
        }

        private static RuntimeException asUnchecked(Throwable t) {
            if (t instanceof RuntimeException runtime) {
                return runtime;
            }
            if (t instanceof Error error) {
                throw error;
            }
            return new FanarTransportException("Stream failed: " + t.getMessage(), t);
        }
    }
}

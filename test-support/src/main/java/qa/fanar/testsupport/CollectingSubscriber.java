package qa.fanar.testsupport;

import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Flow;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.BooleanSupplier;

/**
 * A {@link Flow.Subscriber} for tests: collects every item, remembers the terminal signal and lets
 * the test <em>wait with a timeout</em> for subscription, items or termination — never
 * {@code Thread.sleep}.
 *
 * <p>{@link #unbounded()} requests {@link Long#MAX_VALUE} on subscription; {@link #requesting(long)}
 * requests exactly that much (possibly nothing) so back-pressure can be driven by hand through
 * {@link #request(long)}. The {@code await*} methods return {@code false} on timeout, while
 * {@link #awaitCompletion(Duration)} and {@link #awaitError(Duration)} turn a timeout or the wrong
 * terminal signal into an {@link AssertionError} with a message that says what was received.</p>
 *
 * @param <T> the item type
 * @author Oussama Mahjoub
 */
public final class CollectingSubscriber<T> implements Flow.Subscriber<T> {

    private final long initialDemand;
    private final List<T> items = new CopyOnWriteArrayList<>();
    private final ReentrantLock lock = new ReentrantLock();
    private final Condition changed = lock.newCondition();

    private volatile Flow.Subscription subscription;
    private volatile boolean completed;
    private volatile Throwable error;

    private CollectingSubscriber(long initialDemand) {
        this.initialDemand = initialDemand;
    }

    /**
     * A subscriber that requests everything as soon as it is subscribed.
     *
     * @param <T> the item type
     * @return the subscriber
     */
    public static <T> CollectingSubscriber<T> unbounded() {
        return new CollectingSubscriber<>(Long.MAX_VALUE);
    }

    /**
     * A subscriber that requests exactly {@code initialDemand} items on subscription — zero to
     * drive demand entirely by hand.
     *
     * @param initialDemand the demand to signal on subscription, {@code >= 0}
     * @param <T>           the item type
     * @return the subscriber
     */
    public static <T> CollectingSubscriber<T> requesting(long initialDemand) {
        if (initialDemand < 0) {
            throw new IllegalArgumentException("initialDemand must be >= 0, was " + initialDemand);
        }
        return new CollectingSubscriber<>(initialDemand);
    }

    @Override
    public void onSubscribe(Flow.Subscription s) {
        subscription = Objects.requireNonNull(s, "subscription");
        signal();
        if (initialDemand > 0) {
            s.request(initialDemand);
        }
    }

    @Override
    public void onNext(T item) {
        items.add(item);
        signal();
    }

    @Override
    public void onError(Throwable throwable) {
        error = Objects.requireNonNull(throwable, "throwable");
        signal();
    }

    @Override
    public void onComplete() {
        completed = true;
        signal();
    }

    /**
     * The subscription handed to {@link #onSubscribe}.
     *
     * @return the subscription, or {@code null} before subscription
     */
    public Flow.Subscription subscription() {
        return subscription;
    }

    /**
     * Request more items from the publisher.
     *
     * @param n the additional demand
     * @throws IllegalStateException if not subscribed yet
     */
    public void request(long n) {
        subscriptionOrFail().request(n);
    }

    /**
     * Cancel the subscription.
     *
     * @throws IllegalStateException if not subscribed yet
     */
    public void cancel() {
        subscriptionOrFail().cancel();
    }

    /**
     * Snapshot of the items received so far, in order.
     *
     * @return an unmodifiable copy
     */
    public List<T> items() {
        return List.copyOf(items);
    }

    /**
     * Whether {@link #onComplete()} was called.
     *
     * @return {@code true} after normal completion
     */
    public boolean isCompleted() {
        return completed;
    }

    /**
     * The error passed to {@link #onError}, if any.
     *
     * @return the error, or {@code null}
     */
    public Throwable error() {
        return error;
    }

    /**
     * Whether a terminal signal — completion or error — has arrived.
     *
     * @return {@code true} once terminated
     */
    public boolean isTerminated() {
        return completed || error != null;
    }

    /**
     * Wait until {@link #onSubscribe} has been called.
     *
     * @param timeout how long to wait at most
     * @return {@code true} if subscribed within the timeout
     * @throws InterruptedException if interrupted while waiting
     */
    public boolean awaitSubscription(Duration timeout) throws InterruptedException {
        return await(() -> subscription != null, timeout);
    }

    /**
     * Wait until at least {@code count} items have been received.
     *
     * @param count   the number of items to wait for
     * @param timeout how long to wait at most
     * @return {@code true} if that many items arrived within the timeout
     * @throws InterruptedException if interrupted while waiting
     */
    public boolean awaitItems(int count, Duration timeout) throws InterruptedException {
        return await(() -> items.size() >= count, timeout);
    }

    /**
     * Wait for the terminal signal, whichever it is.
     *
     * @param timeout how long to wait at most
     * @return {@code true} if the stream terminated within the timeout
     * @throws InterruptedException if interrupted while waiting
     */
    public boolean awaitTerminal(Duration timeout) throws InterruptedException {
        return await(this::isTerminated, timeout);
    }

    /**
     * Wait for normal completion and return every item received.
     *
     * @param timeout how long to wait at most
     * @return the items, in order
     * @throws AssertionError       if the stream did not terminate in time, or terminated with an
     *                              error (attached as the cause)
     * @throws InterruptedException if interrupted while waiting
     */
    public List<T> awaitCompletion(Duration timeout) throws InterruptedException {
        if (!awaitTerminal(timeout)) {
            throw new AssertionError("no terminal signal within " + timeout + " (" + items.size()
                    + " item(s) received)");
        }
        if (error != null) {
            throw new AssertionError("stream failed instead of completing after " + items.size()
                    + " item(s)", error);
        }
        return items();
    }

    /**
     * Wait for the stream to fail and return the error.
     *
     * @param timeout how long to wait at most
     * @return the error passed to {@link #onError}
     * @throws AssertionError       if the stream did not terminate in time, or completed normally
     * @throws InterruptedException if interrupted while waiting
     */
    public Throwable awaitError(Duration timeout) throws InterruptedException {
        if (!awaitTerminal(timeout)) {
            throw new AssertionError("no terminal signal within " + timeout + " (" + items.size()
                    + " item(s) received)");
        }
        if (error == null) {
            throw new AssertionError("stream completed normally instead of failing (" + items.size()
                    + " item(s) received)");
        }
        return error;
    }

    private Flow.Subscription subscriptionOrFail() {
        Flow.Subscription s = subscription;
        if (s == null) {
            throw new IllegalStateException("not subscribed yet");
        }
        return s;
    }

    private boolean await(BooleanSupplier condition, Duration timeout) throws InterruptedException {
        long remainingNanos = timeout.toNanos();
        lock.lock();
        try {
            while (!condition.getAsBoolean()) {
                if (remainingNanos <= 0) {
                    return false;
                }
                remainingNanos = changed.awaitNanos(remainingNanos);
            }
            return true;
        } finally {
            lock.unlock();
        }
    }

    private void signal() {
        lock.lock();
        try {
            changed.signalAll();
        } finally {
            lock.unlock();
        }
    }
}

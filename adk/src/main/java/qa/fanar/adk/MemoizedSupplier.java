package qa.fanar.adk;

import java.util.Objects;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;

import qa.fanar.core.FanarClient;

/**
 * Builds the client on first use and returns the same instance afterwards, so a client can be
 * constructed outside the agent's static initialiser (ADR-030). Always takes the lock: the call
 * sits in front of an HTTP round trip, and a double-checked fast path would only buy a race that
 * no test can force.
 */
final class MemoizedSupplier implements Supplier<FanarClient> {

    private final Supplier<FanarClient> delegate;
    private final ReentrantLock lock = new ReentrantLock();
    private FanarClient value;

    MemoizedSupplier(Supplier<FanarClient> delegate) {
        this.delegate = Objects.requireNonNull(delegate, "client");
    }

    @Override
    public FanarClient get() {
        lock.lock();
        try {
            if (value == null) {
                value = Objects.requireNonNull(delegate.get(), "the client supplier returned null");
            }
            return value;
        } finally {
            lock.unlock();
        }
    }
}

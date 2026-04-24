package qa.fanar.core.internal.retry;

import java.time.Duration;

/**
 * Minimal abstraction over {@link Thread#sleep(Duration)} so tests can drive the retry loop
 * without actually waiting on the wall clock. Internal.
 */
@FunctionalInterface
interface Sleeper {

    /**
     * Block for {@code duration}. Throws {@link InterruptedException} if the sleep is cut short
     * by {@link Thread#interrupt()}.
     */
    void sleep(Duration duration) throws InterruptedException;

    /** Production sleeper — delegates to {@link Thread#sleep(Duration)}. */
    Sleeper THREAD = Thread::sleep;
}

package qa.fanar.core;

import java.time.Duration;
import java.util.Objects;
import java.util.function.Predicate;

/**
 * Retry configuration for the SDK's built-in retry interceptor.
 *
 * <p>Immutable and thread-safe. Construct via {@link #defaults()} or {@link #disabled()} for the
 * canonical presets, or via the record constructor for full control. Derive variants through the
 * {@code with*} methods — each returns a new record.</p>
 *
 * <p>This type holds only the <em>configuration</em>. The retry loop — applying backoff, honoring
 * {@code Retry-After}, computing jitter — lives in the SDK's internal retry interceptor.</p>
 *
 * <h2>Default policy</h2>
 * <p>{@link #defaults()} returns 3 attempts, exponential backoff with
 * {@link JitterStrategy#FULL full jitter}, base 500&nbsp;ms, cap 30&nbsp;s, multiplier 2.0, and
 * the {@link #isDefaultRetryable default retryable predicate} (transient server-side errors and
 * transport failures — never client-side or content-filter errors).</p>
 *
 * <h2>Validation</h2>
 * <p>The canonical constructor validates all invariants at construction time. {@code with*}
 * methods re-validate on the new record. Invalid values throw {@link IllegalArgumentException} or
 * {@link NullPointerException} synchronously — never silently, never at retry time.</p>
 *
 * @param maxAttempts       total attempts including the first; must be ≥ 1. A value of 1
 *                          disables retries.
 * @param baseDelay         initial backoff delay; must be positive
 * @param maxDelay          cap on the computed backoff; must be positive and ≥ {@code baseDelay}
 * @param backoffMultiplier factor applied to the backoff on each retry; must be ≥ 1.0
 * @param jitter            jitter policy applied to the computed backoff
 * @param retryable         predicate deciding whether a given exception is worth retrying
 *
 * @author Oussama Mahjoub
 */
public record RetryPolicy(
        int maxAttempts,
        Duration baseDelay,
        Duration maxDelay,
        double backoffMultiplier,
        JitterStrategy jitter,
        Predicate<FanarException> retryable
) {

    public RetryPolicy {
        if (maxAttempts < 1) {
            throw new IllegalArgumentException("maxAttempts must be >= 1, got " + maxAttempts);
        }
        Objects.requireNonNull(baseDelay, "baseDelay");
        if (baseDelay.isNegative() || baseDelay.isZero()) {
            throw new IllegalArgumentException("baseDelay must be positive, got " + baseDelay);
        }
        Objects.requireNonNull(maxDelay, "maxDelay");
        if (maxDelay.isNegative() || maxDelay.isZero()) {
            throw new IllegalArgumentException("maxDelay must be positive, got " + maxDelay);
        }
        if (maxDelay.compareTo(baseDelay) < 0) {
            throw new IllegalArgumentException(
                    "maxDelay (" + maxDelay + ") must be >= baseDelay (" + baseDelay + ")");
        }
        if (backoffMultiplier < 1.0) {
            throw new IllegalArgumentException(
                    "backoffMultiplier must be >= 1.0, got " + backoffMultiplier);
        }
        Objects.requireNonNull(jitter, "jitter");
        Objects.requireNonNull(retryable, "retryable");
    }

    /**
     * The SDK's default retry policy: 3 attempts, exponential backoff with full jitter, base 500&nbsp;ms,
     * cap 30&nbsp;s, multiplier 2.0, and the default retryable predicate.
     *
     * @return a new policy instance with the documented defaults
     */
    public static RetryPolicy defaults() {
        return new RetryPolicy(
                3,
                Duration.ofMillis(500),
                Duration.ofSeconds(30),
                2.0,
                JitterStrategy.FULL,
                RetryPolicy::isDefaultRetryable);
    }

    /**
     * A policy with retries disabled: {@code maxAttempts = 1}. Other fields are set to valid but
     * unused values so the record passes validation.
     *
     * @return a new policy that performs no retries
     */
    public static RetryPolicy disabled() {
        return new RetryPolicy(
                1,
                Duration.ofMillis(500),
                Duration.ofSeconds(30),
                2.0,
                JitterStrategy.FULL,
                RetryPolicy::isDefaultRetryable);
    }

    /**
     * Canonical retryable-exception matrix. All transient server-side errors and transport-level
     * failures are retryable; all deterministic client-side errors and content-filter rejections
     * are not.
     *
     * <p>Implemented as an exhaustive pattern-match on the sealed {@link FanarException} hierarchy.
     * If a future release adds a new top-level branch to that hierarchy, the compiler flags this
     * method until the branch is handled explicitly.</p>
     *
     * @param e the exception to classify; must not be {@code null}
     * @return {@code true} if the exception represents a transient condition worth retrying
     */
    public static boolean isDefaultRetryable(FanarException e) {
        Objects.requireNonNull(e, "e");
        return switch (e) {
            case FanarServerException s        -> true;
            case FanarTransportException t     -> true;
            case FanarClientException c        -> false;
            case FanarContentFilterException f -> false;
        };
    }

    /** @return a new policy with the given {@code maxAttempts}, all other fields unchanged */
    public RetryPolicy withMaxAttempts(int maxAttempts) {
        return new RetryPolicy(maxAttempts, baseDelay, maxDelay, backoffMultiplier, jitter, retryable);
    }

    /** @return a new policy with the given {@code baseDelay}, all other fields unchanged */
    public RetryPolicy withBaseDelay(Duration baseDelay) {
        return new RetryPolicy(maxAttempts, baseDelay, maxDelay, backoffMultiplier, jitter, retryable);
    }

    /** @return a new policy with the given {@code maxDelay}, all other fields unchanged */
    public RetryPolicy withMaxDelay(Duration maxDelay) {
        return new RetryPolicy(maxAttempts, baseDelay, maxDelay, backoffMultiplier, jitter, retryable);
    }

    /** @return a new policy with the given {@code backoffMultiplier}, all other fields unchanged */
    public RetryPolicy withBackoffMultiplier(double backoffMultiplier) {
        return new RetryPolicy(maxAttempts, baseDelay, maxDelay, backoffMultiplier, jitter, retryable);
    }

    /** @return a new policy with the given {@code jitter}, all other fields unchanged */
    public RetryPolicy withJitter(JitterStrategy jitter) {
        return new RetryPolicy(maxAttempts, baseDelay, maxDelay, backoffMultiplier, jitter, retryable);
    }

    /** @return a new policy with the given {@code retryable} predicate, all other fields unchanged */
    public RetryPolicy withRetryable(Predicate<FanarException> retryable) {
        return new RetryPolicy(maxAttempts, baseDelay, maxDelay, backoffMultiplier, jitter, retryable);
    }
}

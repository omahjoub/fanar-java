package qa.fanar.core;

import java.time.Duration;
import java.util.Objects;
import java.util.function.Predicate;

/**
 * Retry configuration for the SDK's built-in retry interceptor.
 *
 * <p>Immutable and thread-safe. Construct via {@link #defaults()} or {@link #disabled()} for the
 * canonical presets, via {@link #builder()} (which starts from the defaults) for full control, or
 * via the record constructor. Derive variants through the {@code with*} methods — each returns a
 * new record. The builder is the stable way to set knobs: the canonical constructor changes
 * arity whenever a knob is added (ADR-027).</p>
 *
 * <p>This type holds only the <em>configuration</em>. The retry loop — applying backoff, honoring
 * {@code Retry-After}, computing jitter — lives in the SDK's internal retry interceptor.</p>
 *
 * <h2>Default policy</h2>
 * <p>{@link #defaults()} returns 3 attempts, exponential backoff with
 * {@link JitterStrategy#FULL full jitter}, base 500&nbsp;ms, cap 30&nbsp;s, a total sleep budget of
 * 1&nbsp;min, multiplier 2.0, and the {@link #isDefaultRetryable default retryable predicate}
 * (transient server-side errors and transport failures — never client-side or content-filter
 * errors). The cap also bounds honoured server {@code Retry-After} hints: a hint above it ends
 * retrying and the exception surfaces immediately with the hint preserved (ADR-025). The budget
 * bounds the <em>sum</em> of all sleeps within one call: a sleep that would push the total over it
 * is never taken — retrying ends and the exception surfaces, hint preserved (ADR-027). At the
 * defaults the budget equals the worst case the other knobs allow (two sleeps of at most 30&nbsp;s),
 * so it only bites once {@code maxAttempts} or {@code maxDelay} are raised.</p>
 *
 * <h2>Validation</h2>
 * <p>The canonical constructor validates all invariants at construction time. {@code with*}
 * methods re-validate on the new record. Invalid values throw {@link IllegalArgumentException} or
 * {@link NullPointerException} synchronously — never silently, never at retry time.</p>
 *
 * @param maxAttempts       total attempts including the first; must be ≥ 1. A value of 1
 *                          disables retries.
 * @param baseDelay         initial backoff delay; must be positive
 * @param maxDelay          cap on the computed backoff, and ceiling on honoured server
 *                          {@code Retry-After} hints — a hint above it ends retrying and the
 *                          exception surfaces with the hint preserved (ADR-025); must be
 *                          positive, ≥ {@code baseDelay}, and representable in milliseconds
 * @param maxTotalDelay     budget for the sum of all sleeps within one call — the next sleep
 *                          (computed back-off or honoured hint) is taken only if the total stays
 *                          within it, otherwise retrying ends and the exception surfaces with
 *                          the hint preserved (ADR-027); must be positive, ≥ {@code maxDelay},
 *                          and representable in milliseconds
 * @param backoffMultiplier factor applied to the backoff on each retry; must be ≥ 1.0
 * @param jitter            jitter policy applied to the computed backoff
 * @param retryable         predicate deciding whether a given exception is worth retrying.
 *                          Consulted only while the policy can still honour a retry: the
 *                          attempt budget ({@code maxAttempts}), the delay ceiling
 *                          ({@code maxDelay}) and the total sleep budget ({@code maxTotalDelay})
 *                          end retrying regardless of its answer
 *
 * @author Oussama Mahjoub
 */
public record RetryPolicy(
        int maxAttempts,
        Duration baseDelay,
        Duration maxDelay,
        Duration maxTotalDelay,
        double backoffMultiplier,
        JitterStrategy jitter,
        Predicate<FanarException> retryable
) {

    /**
     * Largest {@code maxDelay} the retry loop's millisecond arithmetic can represent; the
     * {@code - 1} keeps the full-jitter draw's {@code bound = delay + 1} inside {@code long} range.
     */
    private static final Duration MAX_REPRESENTABLE_DELAY = Duration.ofMillis(Long.MAX_VALUE - 1);

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
        if (maxDelay.compareTo(MAX_REPRESENTABLE_DELAY) > 0) {
            throw new IllegalArgumentException(
                    "maxDelay must be representable in milliseconds, got " + maxDelay);
        }
        Objects.requireNonNull(maxTotalDelay, "maxTotalDelay");
        if (maxTotalDelay.isNegative() || maxTotalDelay.isZero()) {
            throw new IllegalArgumentException("maxTotalDelay must be positive, got " + maxTotalDelay);
        }
        if (maxTotalDelay.compareTo(maxDelay) < 0) {
            throw new IllegalArgumentException(
                    "maxTotalDelay (" + maxTotalDelay + ") must be >= maxDelay (" + maxDelay + ")");
        }
        if (maxTotalDelay.compareTo(MAX_REPRESENTABLE_DELAY) > 0) {
            throw new IllegalArgumentException(
                    "maxTotalDelay must be representable in milliseconds, got " + maxTotalDelay);
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
     * cap 30&nbsp;s, total sleep budget 1&nbsp;min, multiplier 2.0, and the default retryable predicate.
     *
     * @return a new policy instance with the documented defaults
     */
    public static RetryPolicy defaults() {
        return new RetryPolicy(
                3,
                Duration.ofMillis(500),
                Duration.ofSeconds(30),
                Duration.ofMinutes(1),
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
                Duration.ofMinutes(1),
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
        return new RetryPolicy(maxAttempts, baseDelay, maxDelay, maxTotalDelay, backoffMultiplier, jitter, retryable);
    }

    /** @return a new policy with the given {@code baseDelay}, all other fields unchanged */
    public RetryPolicy withBaseDelay(Duration baseDelay) {
        return new RetryPolicy(maxAttempts, baseDelay, maxDelay, maxTotalDelay, backoffMultiplier, jitter, retryable);
    }

    /** @return a new policy with the given {@code maxDelay}, all other fields unchanged */
    public RetryPolicy withMaxDelay(Duration maxDelay) {
        return new RetryPolicy(maxAttempts, baseDelay, maxDelay, maxTotalDelay, backoffMultiplier, jitter, retryable);
    }

    /**
     * @return a new policy with the given {@code maxTotalDelay}, all other fields unchanged
     * @since 0.4.0
     */
    public RetryPolicy withMaxTotalDelay(Duration maxTotalDelay) {
        return new RetryPolicy(maxAttempts, baseDelay, maxDelay, maxTotalDelay, backoffMultiplier, jitter, retryable);
    }

    /** @return a new policy with the given {@code backoffMultiplier}, all other fields unchanged */
    public RetryPolicy withBackoffMultiplier(double backoffMultiplier) {
        return new RetryPolicy(maxAttempts, baseDelay, maxDelay, maxTotalDelay, backoffMultiplier, jitter, retryable);
    }

    /** @return a new policy with the given {@code jitter}, all other fields unchanged */
    public RetryPolicy withJitter(JitterStrategy jitter) {
        return new RetryPolicy(maxAttempts, baseDelay, maxDelay, maxTotalDelay, backoffMultiplier, jitter, retryable);
    }

    /**
     * @return a new policy with the given {@code retryable} predicate, all other fields unchanged;
     *         the attempt budget and the delay ceiling still apply regardless of its answer
     */
    public RetryPolicy withRetryable(Predicate<FanarException> retryable) {
        return new RetryPolicy(maxAttempts, baseDelay, maxDelay, maxTotalDelay, backoffMultiplier, jitter, retryable);
    }

    /**
     * Begin building a policy from {@link #defaults()}: every knob starts at its default and only
     * the ones set change. Validation happens in {@link Builder#build()}, exactly as for the
     * canonical constructor.
     *
     * @return a new builder
     * @since 0.4.0
     */
    public static Builder builder() {
        return new Builder(defaults());
    }

    /**
     * Fluent builder for {@link RetryPolicy} (ADR-027). Not thread-safe; build once.
     *
     * @since 0.4.0
     */
    public static final class Builder {

        private int maxAttempts;
        private Duration baseDelay;
        private Duration maxDelay;
        private Duration maxTotalDelay;
        private double backoffMultiplier;
        private JitterStrategy jitter;
        private Predicate<FanarException> retryable;

        private Builder(RetryPolicy start) {
            this.maxAttempts = start.maxAttempts();
            this.baseDelay = start.baseDelay();
            this.maxDelay = start.maxDelay();
            this.maxTotalDelay = start.maxTotalDelay();
            this.backoffMultiplier = start.backoffMultiplier();
            this.jitter = start.jitter();
            this.retryable = start.retryable();
        }

        /** @param maxAttempts total attempts including the first; {@code 1} disables retries */
        public Builder maxAttempts(int maxAttempts) {
            this.maxAttempts = maxAttempts;
            return this;
        }

        /** @param baseDelay initial backoff delay */
        public Builder baseDelay(Duration baseDelay) {
            this.baseDelay = baseDelay;
            return this;
        }

        /** @param maxDelay cap on one sleep and ceiling on honoured {@code Retry-After} hints (ADR-025) */
        public Builder maxDelay(Duration maxDelay) {
            this.maxDelay = maxDelay;
            return this;
        }

        /** @param maxTotalDelay budget for the sum of all sleeps within one call (ADR-027) */
        public Builder maxTotalDelay(Duration maxTotalDelay) {
            this.maxTotalDelay = maxTotalDelay;
            return this;
        }

        /** @param backoffMultiplier factor applied to the backoff on each retry */
        public Builder backoffMultiplier(double backoffMultiplier) {
            this.backoffMultiplier = backoffMultiplier;
            return this;
        }

        /** @param jitter jitter policy applied to the computed backoff */
        public Builder jitter(JitterStrategy jitter) {
            this.jitter = jitter;
            return this;
        }

        /** @param retryable predicate deciding whether an exception is worth retrying */
        public Builder retryable(Predicate<FanarException> retryable) {
            this.retryable = retryable;
            return this;
        }

        /**
         * Validate and build.
         *
         * @return the policy
         * @throws IllegalArgumentException or {@link NullPointerException} on an invalid knob,
         *                                  exactly as the canonical constructor
         */
        public RetryPolicy build() {
            return new RetryPolicy(maxAttempts, baseDelay, maxDelay, maxTotalDelay, backoffMultiplier, jitter, retryable);
        }
    }
}

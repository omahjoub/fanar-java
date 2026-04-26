package qa.fanar.core;

/**
 * Jitter policy applied to the SDK's retry-backoff delays.
 *
 * <p>Controls how randomness is added to the computed exponential-backoff delay. Full jitter is the
 * recommended default — the AWS Architecture Blog post <em>"Exponential Backoff and Jitter"</em>
 * establishes that it prevents thundering-herd reconvergence more effectively than deterministic
 * or equal-jitter schemes.</p>
 *
 * @author Oussama Mahjoub
 */
public enum JitterStrategy {

    /**
     * No jitter. The sleep duration is exactly the computed backoff.
     *
     * <p>Useful for deterministic tests. Not recommended for production: many retrying clients
     * wake up simultaneously and re-synchronize, which re-overloads the provider right as it
     * recovers.</p>
     */
    NONE,

    /**
     * Full jitter. The sleep duration is {@code random(0, backoff)}.
     *
     * <p>Recommended default. Spreads retry attempts evenly across the backoff window, preventing
     * thundering-herd reconvergence after provider outages.</p>
     */
    FULL,

    /**
     * Equal jitter. The sleep duration is {@code backoff/2 + random(0, backoff/2)}.
     *
     * <p>A middle ground between {@link #NONE} and {@link #FULL}. Retries wait at least half the
     * backoff, with the remaining half randomized. Useful when a strict minimum delay between
     * attempts is required (for example to respect a known rate limit).</p>
     */
    EQUAL
}

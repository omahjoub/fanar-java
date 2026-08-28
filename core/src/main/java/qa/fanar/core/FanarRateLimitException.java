package qa.fanar.core;

import java.time.Duration;

/**
 * Request rate limit exceeded. Maps to {@link ErrorCode#RATE_LIMIT_REACHED} and HTTP 429.
 *
 * <p>Transient — the built-in retry interceptor retries it under the default policy, honouring
 * a server {@code Retry-After} hint up to {@link RetryPolicy#maxDelay()}; a hint above that
 * ceiling ends retrying and the exception surfaces immediately with the hint preserved
 * (ADR-025).</p>
 *
 * <p>The hint, when the server sent one, is exposed via {@link #retryAfter()} so callers can
 * schedule around it. Distinct from {@link FanarQuotaExceededException}, which is a permanent
 * condition at the same HTTP status.</p>
 *
 * @author Oussama Mahjoub
 */
public final class FanarRateLimitException extends FanarServerException {

    /** Nullable — server may omit the {@code Retry-After} header. */
    private final Duration retryAfter;

    public FanarRateLimitException(String message) {
        this(message, null);
    }

    public FanarRateLimitException(String message, Duration retryAfter) {
        super(message, ErrorCode.RATE_LIMIT_REACHED, 429);
        this.retryAfter = retryAfter;
    }

    public FanarRateLimitException(String message, Duration retryAfter, Throwable cause) {
        super(message, ErrorCode.RATE_LIMIT_REACHED, 429, cause);
        this.retryAfter = retryAfter;
    }

    /**
     * @return the server-provided {@code Retry-After} duration, or {@code null} if the server
     *         did not send one
     */
    public Duration retryAfter() {
        return retryAfter;
    }
}

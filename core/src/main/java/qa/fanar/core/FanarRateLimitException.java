package qa.fanar.core;

import java.time.Duration;

/**
 * Request rate limit exceeded. Maps to {@link ErrorCode#RATE_LIMIT_REACHED} and HTTP 429.
 *
 * <p>Transient — the built-in retry interceptor handles this automatically when configured
 * (default policy: retry with exponential backoff plus full jitter, respecting a
 * {@code Retry-After} hint up to {@link RetryPolicy#maxDelay()}). A hint <em>beyond</em> that
 * ceiling — Fanar quota windows reach a day — is a wait no in-policy retry can bridge, so the
 * interceptor rethrows immediately instead of blocking the calling thread for it.</p>
 *
 * <p>When the server provides a {@code Retry-After} header, its value is exposed via
 * {@link #retryAfter()} so callers can schedule around long waits (or custom retry logic can
 * honor short ones). Distinct from {@link FanarQuotaExceededException}, which is a permanent
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

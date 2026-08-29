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
 * schedule around it, and the window the server reported via {@link #rateLimit()} (ADR-026).
 * Distinct from {@link FanarQuotaExceededException}, which is a permanent condition at the same
 * HTTP status.</p>
 *
 * @author Oussama Mahjoub
 */
public final class FanarRateLimitException extends FanarServerException {

    /** Nullable — server may omit the {@code Retry-After} header. */
    private final Duration retryAfter;

    /** Nullable — absent on non-model calls, before admission, and for unlimited-quota keys. */
    private final RateLimitInfo rateLimit;

    public FanarRateLimitException(String message) {
        this(message, null, (RateLimitInfo) null);
    }

    public FanarRateLimitException(String message, Duration retryAfter) {
        this(message, retryAfter, (RateLimitInfo) null);
    }

    /**
     * @param message    the server's message
     * @param retryAfter the normalised {@code Retry-After} hint, or {@code null}
     * @param rateLimit  the window the server reported, or {@code null}
     * @since 0.4.0
     */
    public FanarRateLimitException(String message, Duration retryAfter, RateLimitInfo rateLimit) {
        super(message, ErrorCode.RATE_LIMIT_REACHED, 429);
        this.retryAfter = retryAfter;
        this.rateLimit = rateLimit;
    }

    public FanarRateLimitException(String message, Duration retryAfter, Throwable cause) {
        this(message, retryAfter, null, cause);
    }

    /**
     * @param message    the server's message
     * @param retryAfter the normalised {@code Retry-After} hint, or {@code null}
     * @param rateLimit  the window the server reported, or {@code null}
     * @param cause      the underlying cause
     * @since 0.4.0
     */
    public FanarRateLimitException(String message, Duration retryAfter, RateLimitInfo rateLimit,
                                   Throwable cause) {
        super(message, ErrorCode.RATE_LIMIT_REACHED, 429, cause);
        this.retryAfter = retryAfter;
        this.rateLimit = rateLimit;
    }

    /**
     * @return the server-provided {@code Retry-After} duration, or {@code null} if the server
     *         did not send one
     */
    public Duration retryAfter() {
        return retryAfter;
    }

    /**
     * @return the rate-limit window the server reported on this response, or {@code null} when
     *         it sent no {@code x-ratelimit-*} headers
     * @since 0.4.0
     */
    public RateLimitInfo rateLimit() {
        return rateLimit;
    }
}

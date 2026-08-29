package qa.fanar.core;

import java.time.Duration;

/**
 * Account quota has been exhausted. Maps to {@link ErrorCode#EXCEEDED_QUOTA} and HTTP 429.
 *
 * <p>Classified as a client-side error (under {@link FanarClientException}) rather than a
 * server-side one, because quota exhaustion is a permanent condition until a period reset or
 * plan upgrade. Distinct from {@link FanarRateLimitException}, which is a transient throttling
 * event at the same HTTP status.</p>
 *
 * <p>Not retryable under the default policy — retrying wastes cycles. The server's
 * {@code Retry-After} countdown to the next free slot, when sent, is exposed via
 * {@link #retryAfter()} so callers can schedule around it (ADR-025), and the window the server
 * reported via {@link #rateLimit()} (ADR-026).</p>
 *
 * @author Oussama Mahjoub
 */
public final class FanarQuotaExceededException extends FanarClientException {

    /** Nullable — server may omit the {@code Retry-After} header. */
    private final Duration retryAfter;

    /** Nullable — absent on non-model calls, before admission, and for unlimited-quota keys. */
    private final RateLimitInfo rateLimit;

    public FanarQuotaExceededException(String message) {
        this(message, (Duration) null);
    }

    public FanarQuotaExceededException(String message, Duration retryAfter) {
        this(message, retryAfter, (RateLimitInfo) null);
    }

    /**
     * @param message    the server's message
     * @param retryAfter the normalised {@code Retry-After} hint, or {@code null}
     * @param rateLimit  the window the server reported, or {@code null}
     * @since 0.4.0
     */
    public FanarQuotaExceededException(String message, Duration retryAfter, RateLimitInfo rateLimit) {
        super(message, ErrorCode.EXCEEDED_QUOTA, 429);
        this.retryAfter = retryAfter;
        this.rateLimit = rateLimit;
    }

    public FanarQuotaExceededException(String message, Throwable cause) {
        this(message, null, cause);
    }

    public FanarQuotaExceededException(String message, Duration retryAfter, Throwable cause) {
        this(message, retryAfter, null, cause);
    }

    /**
     * @param message    the server's message
     * @param retryAfter the normalised {@code Retry-After} hint, or {@code null}
     * @param rateLimit  the window the server reported, or {@code null}
     * @param cause      the underlying cause
     * @since 0.4.0
     */
    public FanarQuotaExceededException(String message, Duration retryAfter, RateLimitInfo rateLimit,
                                       Throwable cause) {
        super(message, ErrorCode.EXCEEDED_QUOTA, 429, cause);
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

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
 * {@link #retryAfter()} so callers can schedule around it (ADR-025).</p>
 *
 * @author Oussama Mahjoub
 */
public final class FanarQuotaExceededException extends FanarClientException {

    /** Nullable — server may omit the {@code Retry-After} header. */
    private final Duration retryAfter;

    public FanarQuotaExceededException(String message) {
        this(message, (Duration) null);
    }

    public FanarQuotaExceededException(String message, Duration retryAfter) {
        super(message, ErrorCode.EXCEEDED_QUOTA, 429);
        this.retryAfter = retryAfter;
    }

    public FanarQuotaExceededException(String message, Throwable cause) {
        this(message, null, cause);
    }

    public FanarQuotaExceededException(String message, Duration retryAfter, Throwable cause) {
        super(message, ErrorCode.EXCEEDED_QUOTA, 429, cause);
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

package qa.fanar.core;

/**
 * Account quota has been exhausted. Maps to {@link ErrorCode#EXCEEDED_QUOTA} and HTTP 429.
 *
 * <p>Classified as a client-side error (under {@link FanarClientException}) rather than a
 * server-side one, because quota exhaustion is a permanent condition until a period reset or
 * plan upgrade. Distinct from {@link FanarRateLimitException}, which is a transient throttling
 * event at the same HTTP status.</p>
 *
 * <p>Not retryable — retrying wastes cycles. Fail fast and surface to the caller.</p>
 */
public final class FanarQuotaExceededException extends FanarClientException {

    public FanarQuotaExceededException(String message) {
        super(message, ErrorCode.EXCEEDED_QUOTA, 429);
    }

    public FanarQuotaExceededException(String message, Throwable cause) {
        super(message, ErrorCode.EXCEEDED_QUOTA, 429, cause);
    }
}

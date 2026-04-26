package qa.fanar.core;

/**
 * 5xx-class errors and transient throttling: conditions where the request was valid but the
 * Fanar backend could not (or would not) produce a response at that moment.
 *
 * <p>These are generally retryable with backoff. The built-in {@code RetryInterceptor} treats
 * all subtypes here as retryable by default; consult the SDK's retry-policy documentation for
 * the exact matrix.</p>
 *
 * <p>{@link FanarRateLimitException} is included here despite its HTTP 429 status because rate
 * limits are a transient server-side throttling event, not a permanent client-side condition
 * (contrast with {@link FanarQuotaExceededException}, which sits under
 * {@link FanarClientException}).</p>
 *
 * @author Oussama Mahjoub
 */
public abstract sealed class FanarServerException extends FanarException
        permits FanarRateLimitException, FanarOverloadedException,
                FanarTimeoutException, FanarInternalServerException {

    protected FanarServerException(String message, ErrorCode code, int httpStatus) {
        super(message, code, httpStatus);
    }

    protected FanarServerException(String message, ErrorCode code, int httpStatus, Throwable cause) {
        super(message, code, httpStatus, cause);
    }
}

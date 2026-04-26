package qa.fanar.core;

/**
 * 4xx-class errors: the request as sent is rejected by Fanar.
 *
 * <p>These are <em>not</em> transient — retrying without changing the request will fail the same
 * way. The {@link FanarQuotaExceededException} is included under this category even though its
 * HTTP status is {@code 429} (overlapping with {@link FanarRateLimitException} under
 * {@link FanarServerException}), because quota exhaustion is a permanent client-side condition,
 * not a transient throttling event.</p>
 *
 * @author Oussama Mahjoub
 */
public abstract sealed class FanarClientException extends FanarException
        permits FanarAuthenticationException, FanarAuthorizationException,
                FanarQuotaExceededException, FanarNotFoundException,
                FanarConflictException, FanarTooLargeException,
                FanarUnprocessableException, FanarGoneException {

    protected FanarClientException(String message, ErrorCode code, int httpStatus) {
        super(message, code, httpStatus);
    }

    protected FanarClientException(String message, ErrorCode code, int httpStatus, Throwable cause) {
        super(message, code, httpStatus, cause);
    }
}

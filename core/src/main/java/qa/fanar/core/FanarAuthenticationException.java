package qa.fanar.core;

/**
 * API key missing or invalid. Maps to {@link ErrorCode#INVALID_AUTHENTICATION} and HTTP 401.
 *
 * <p>Not retryable — the credential itself is the problem.</p>
 *
 * @author Oussama Mahjoub
 */
public final class FanarAuthenticationException extends FanarClientException {

    public FanarAuthenticationException(String message) {
        super(message, ErrorCode.INVALID_AUTHENTICATION, 401);
    }

    public FanarAuthenticationException(String message, Throwable cause) {
        super(message, ErrorCode.INVALID_AUTHENTICATION, 401, cause);
    }
}

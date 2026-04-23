package qa.fanar.core;

/**
 * API key valid but not authorized for the requested operation or model. Maps to
 * {@link ErrorCode#INVALID_AUTHORIZATION} and HTTP 403.
 *
 * <p>Not retryable — the credential lacks the required scope or model access.</p>
 */
public final class FanarAuthorizationException extends FanarClientException {

    public FanarAuthorizationException(String message) {
        super(message, ErrorCode.INVALID_AUTHORIZATION, 403);
    }

    public FanarAuthorizationException(String message, Throwable cause) {
        super(message, ErrorCode.INVALID_AUTHORIZATION, 403, cause);
    }
}

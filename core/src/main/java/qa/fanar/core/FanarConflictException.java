package qa.fanar.core;

/**
 * Resource state conflict. Maps to {@link ErrorCode#CONFLICT} and HTTP 409.
 *
 * <p>Typical cause: attempting to create a personalized voice with a name already in use. Not
 * retryable without changing the request.</p>
 */
public final class FanarConflictException extends FanarClientException {

    public FanarConflictException(String message) {
        super(message, ErrorCode.CONFLICT, 409);
    }

    public FanarConflictException(String message, Throwable cause) {
        super(message, ErrorCode.CONFLICT, 409, cause);
    }
}
